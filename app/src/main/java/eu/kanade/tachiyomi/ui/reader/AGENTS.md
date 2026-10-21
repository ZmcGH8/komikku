# ui/reader/ — Reader

Fullscreen reading surface. Deliberately a classic `Activity`, NOT a Voyager `Screen` (fullscreen, `singleTask`, S-Pen remote actions, container transforms).

## Entry

- `ReaderActivity.kt` — `singleTask` Activity. `ReaderActivity.newIntent(context, mangaId, chapterId, page)`.
  Uses AndroidX `viewModels<ReaderViewModel> { graph.viewModelFactory }` + `graph.inject(this)` with
  `@Inject lateinit var` fields. `ReaderViewModel.kt` holds chapter/page state (not a Voyager ScreenModel).

## Key directories

| Path | Purpose |
|------|---------|
| `viewer/` | `Viewer.kt` + `ViewerConfig.kt` contracts; `pager/` (strip) and `navigation/` (webtoon/LTR/RTL/vertical) viewers; `ReaderPageImageView`, progress/transition views |
| `viewer/PageTranslator.kt`, `viewer/ReaderTranslation.kt` | MTL translation overlay hookup — engine itself lives in `yakuyomi-engine/` (see its AGENTS.md) |
| `loader/` | `PageLoaderFactory` → `PageLoader`: `HttpPageLoader`, `ArchivePageLoader`, `DirectoryPageLoader`, `DownloadPageLoader`, `EpubPageLoader` |
| `model/` | `ReaderChapter`, `ReaderPage`, `ReaderItem`, `ViewerChapters`, `ChapterTransition`, `InsertPage` |
| `setting/` | `ReaderPreferences`, `ReadingMode`, `ReaderSettingsScreenModel`, `ReaderOrientation`, `ReaderBottomButton` |
| `setting/Upscale*.kt` | On-device upscale stack (MTL-gated): `UpscaleReaderHook` → `UpscaleEngine` (+ `UpscaleBackendDetector`), `UpscaleModelManager`, `UpscaleCacheManager`, `UpscaleMangaStore`, `UpscalePreferences` |
| `chapter/`, `loader/` | `ReaderChapterItem`, chapter loading pipeline |

## WebGPU viewer (`viewer/webgpu/`)

`WebGpuViewer.kt` is wiring only — fields, pager callbacks, chapter swap, navigation. Everything with
a state machine or a retry ladder is a top-level extension beside it, one concern per file:

| File | Concern |
|------|---------|
| `WebGpuViewer.kt` | the class: fields, `fetchPage`/`onPageChange` wiring, `setChapters`, navigation, lifecycle |
| `WebGpuPageModel.kt` | `ViewerPage` hierarchy + split-aware `prev`/`next` neighbour links |
| `WebGpuPageCache.kt` | page residency, eviction policy, `fetchPage` resolution, split reconciliation (`syncPageList`) |
| `WebGpuDecodeQueue.kt` | what the worker owes a page (`queueForDecode`) + the stuck-page retry ladder |
| `WebGpuDecodePipeline.kt` | bytes → GPU image for one page |
| `WebGpuViewerWorkers.kt` | the three long-lived coroutines (decode worker, liveness sweep, progress spinner) |
| `WebGpuViewerImageState.kt` | preferences → filters, transition, zoom, HUD |
| `WebGpuSpread.kt` / `WebGpuSpreadHeightMatch.kt` | dual-page pairing / height matching |
| `WebGpuZoom.kt`, `WebGpuExif.kt` | per-page presentation, reapplied after every decode and every image swap |
| `WebGpuPlaceholderPages.kt` | `ProgressPage` / `ErrorPage` / `TransitionPage` — drawn procedurally |
| `WebGpuRestore.kt` | deferred reading-position restore, deferred until the target page has real heights |

The render engine is a git **submodule** (`external/webgpuviewer-houri`, `:webgpuviewer`); the app
never fetches images itself — it hands the library an `ImagePage` per page and the library draws it.

**Read `viewer/webgpu/AGENTS.md` before debugging anything in this folder.** It holds the thread
model, the page state machine, the stuck-page ladder, the device-recovery rule (an adapter is
consumed and cannot be reused), a symptom→cause table, and the landmines that have already cost
an incident each.

## Novel / text reader (`viewer/novel/`, KMK)

Renders text chapters (EPUB, web novel) in a WebView rather than in a native text widget. Chosen
because an EPUB *is* an XHTML document with the publisher's own stylesheet — drop caps, ruby,
columns, vertical writing, tables. Re-implementing that in Compose means re-implementing a browser.
The reader's preferences are layered on as an injected stylesheet; the document keeps its own.

| File | Concern |
|------|---------|
| `NovelWebViewer.kt` | The WebView, taps and keys, document/chapter turning, the continuous window's orchestration, and the `ViewerProvider` that selects it |
| `NovelDocument.kt` | Turns one spine item's XHTML into a document the engine can be handed |
| `NovelContinuous.kt` | The book as one continuous scroll: segment markers, the probe, append/prepend/trim/seek scripts. Pure strings |
| `NovelInline.kt` | Rewrites a document's images and stylesheets into `data:` URIs. Pure |
| `NovelFonts.kt` | Fonts the reader imported, stored and offered beside the built-in families |
| `NovelScrollBridge.kt` | `window.KmkReader` — how the host measures and scrolls the document, and `quoteForJs` |
| `NovelNavigation.kt` | Contents rows and search results: the pure mapping from source models to renderable ones |
| `NovelReaderScreenModels.kt` | `NovelTocScreenModel` + `NovelSearchScreenModel` |
| `NovelTypography.kt` | The settings value. No Android imports, so it is unit-testable |
| `NovelReaderTheme.kt` | The general reader theme's background, as the book's stylesheet can use it |
| `NovelTypographyCss.kt` | Turns that value into a stylesheet; `styleTag` escapes `</` |
| `NovelProgress.kt` | Reading position and percentage progress |

The two sheets themselves are in `presentation/reader/NovelReaderDialogs.kt`, beside the rest of the
reader's dialogs.

Typography settings *and* per-chapter reading positions both live in `NovelTypographyPreferences`
(`reader/setting/`), not here — the viewer reads them through it and subscribes to `changes()` for
the live re-apply. A separate store for either would be a second answer to the same question.

**Tri-state preferences.** `justified`, `hyphenation` and `verticalWriting` are `Boolean?` where
`null` means *follow the publisher*, stored as the string `"follow"`. They are nullable on purpose: a
default of `false` would mean "emit `text-align: left !important`", which silently restyles a
Japanese light novel out of vertical writing and cannot be turned off again.

**The stylesheet is never empty.** Even for default typography, because that is the only way a user
can turn *off* a publisher's own justification. Properties that default to "follow the publisher" are
simply omitted when null.

**`!important` everywhere on purpose.** An EPUB stylesheet sets `font-size` on `body` or a class; on
specificity grounds those win over a host rule and the user's font size silently does nothing. This is
the single most common complaint about EPUB readers.

**The base URL is per document, and it is the highest-consequence detail in the folder.** It comes
from `TextPage.baseHref` through `TextBaseUrlSource`, which for an EPUB is the *directory* the spine
item sits in — not the archive root. With the root as the base, a document at `OEBPS/Text/ch1.xhtml`
resolves `../styles/main.css` to `styles/main.css` instead of `OEBPS/styles/main.css`: every
stylesheet, illustration and embedded font 404s and the book renders as bare text wearing only the
reader's own styling. Nothing errors, which is exactly why it presents as "the reader shows nothing"
rather than "a file is missing".

**Injected at two points, for two reasons.** `NovelDocument` puts `<base>` and the charset at the
*top* of the head and the reader's stylesheet at the *end*. The base has to precede every element
with a relative URL — including the publisher's own `<link>`, which the preload scanner may have
resolved already — and a charset declaration is only honoured near the top of a document. The
stylesheet has to be last because `!important` against `!important` is decided by source order. A
publisher's own `<base>` is stripped first: the first one in a document wins, so theirs would
silently outrank ours.

**The bridge is a `<script>`, and its result is JSON.** `evaluateJavascript` hands back the *JSON
encoding* of the script's return value, so a script returning `NaN` arrives as the five characters
`null`. `NovelScrollBridge.number` has to read that as "no value", or the position save breaks on
every document.

**Turning is decided by the host, natively — never by asking the document.** A tap scrolls
`WebView.scrollBy` and reads `scrollY` back after a `post`: no movement means the document ran out
of room in the tapped direction, and the document turns. This used to be two `evaluateJavascript`
round trips through the injected bridge, which made turning depend on the book's own `<script>`
having run — and where it did not, every tap silently did nothing and the page navigator was the
only way from one chapter to the next, which is exactly how it was reported. The bridge survives
only for what the *laid-out document* alone can answer: the fraction a reading position is stored
as, and the element a contents entry names. Both degrade to nothing without the bridge, which is
survivable — a book opens at the top of a section instead of where it was left. A document that
has not laid out yet (`contentHeight` 0) answers every scroll with "no movement", so turning is
guarded on it — turning on that reading is what skipped a document on the first tap of a
freshly-opened book.

**A missing resource answers 404, not null.** `shouldInterceptRequest` returning null hands the URL
back to the platform, which then waits on a request nothing answers — so one missing plate holds the
document's layout hostage. A 404 shows a broken image and gets out of the way.

**The URL space is real `https`, on a reserved host.** A book's documents and resources live under
`https://book.internal/…` — the `WebViewAssetLoader` pattern. It was an invented `epub-archive://`
scheme first, and that was why "images are not rendered" outlived the first round of fixes: the
platform documents that a `loadDataWithBaseURL` base "must be an HTTP(S) URL" and that WebView
restricts resource requests using custom schemes, so `<img>`, `<link>` and `@font-face` requests
were refused before `shouldInterceptRequest` was ever consulted. The text still rendered — which is
what made it read as "the reader is broken" rather than "one file is missing".

**A document's own images and stylesheets are inlined as `data:` URIs (`NovelInline`).** The https
origin fixed the class of failure above and images *still* did not render on the reporting device,
which is the point of this rule: intercepted subresource loading is the one link in the book path
that can fail without a single log line, and it has now failed three times. So the references a
reader actually reads — `<img>`, SVG `<image>`, and `<link rel="stylesheet">` with the `url()`s
inside it — are resolved and embedded into the document itself before it is handed over, travelling
the same channel as the text, which has never once failed to arrive. Per-resource and per-document
ceilings degrade oversized books to the streaming path rather than to a missing chapter; external
URLs are never copied. The interception path stays — for over-ceiling files and anything the
inliner does not cover — and the two read from the same archive lease.

**A reader-imported font reaches the document the same way (`NovelFonts`).** The picked file is
copied into private storage (a SAF grant is not the document's to inherit, and the reader's own
hardening closes `file://`), the family is stored as `user/<name>`, and the `@font-face` rides in
the document's head as a `data:` URI — built into `NovelDocument.build`'s head and into
`liveTypographyJs`, so a live family change re-declares the face. `quoteFamily` quotes any name
that is not a CSS identifier: `user/My Font` unquoted would drop the whole `font-family` rule and
the setting would silently do nothing.

**CSS in the head has to be in a `<style>`, and the wrapper belongs to the injector.** `faceCss`
returns a bare `@font-face` rule and `NovelDocument.build` takes it as `extraHead`. It was emitted
loose, which is not a place CSS can be: the parser meets a character token in head insertion mode,
has nothing to do with it, closes the head and opens a body to hold it — so the rule rendered as
the paragraph of literal text the reader reported seeing, *and* declared a font the document was
not using, so choosing an imported font silently did nothing either. Two failures from one missing
tag. `NovelDocument.styleTag` wraps it (and escapes `</`, since the family is a reader-chosen string
and can carry a closing tag); `DocumentHarness` asserts the rule is inside a `<style>`, inside the
head, and cannot break out of one.

**Progress is a character offset, not a page index.** A page index in a reflowable document means
something different on every device and at every font size; `NovelProgress.Position`
(`documentIndex:characterOffset:characterLength`) is stable across all of them. Chapters are weighted
by character length so a book with 200 one-paragraph interstitial files does not read as 2% done.
Persisted per chapter in a `__PRIVATE_` preference, not a column: it is a property of how a document
happens to lay out today, not of the chapter, and losing it costs one lost position.

**The viewer subscribes to `NovelTypographyPreferences.changes()` itself.** Read once at construction
so the first document is never painted unstyled, then observed for the rest of its life — otherwise the
settings screen re-lays-out a book nobody reopens and the reader concludes the setting does nothing.
The job is cancelled in `destroy()`, because the activity's scope outlives a viewer that a
reading-mode switch has already torn down.

**The general reader theme's background reaches the book, through the appearance tab's value.** The
novel viewer merges `readerTheme`'s colour into the typography it applies: a dark theme (black, gray,
automatic-in-dark) becomes the document's `background-color`, and the existing
`effectiveFontColor` rule derives a contrasting text colour from it — choosing *black* in the
general settings used to paint only the window behind a transparent WebView, leaving the
publisher's own black text invisible on it. A white theme forces nothing (white is "no opinion";
the container already shows white), and an appearance-tab page colour always wins as the more
specific choice. The merge is in `NovelWebViewer.withReaderThemeBackground`, the policy in
`NovelReaderTheme`, and the subscription covers both settings so a change applies to the open
document rather than to the next one.

**A book loads a document at a time — and then grows into a window (`NovelContinuous`).**
`TextIncrementalSource` — optional, like every interface an extension may or may not implement —
splits the eager `getTextPageList` into `getTextPageIndex` (the spine, no text read) and
`getTextPageText` (one document). `TextPageLoader.ensureText` reads a page's markup the first time
the viewer shows it, and `showDocument` carries a monotonic request token so a stale read cannot
displace a newer tap.

On top of that, the live document is the book's *continuous* scroll: every section in the DOM —
including the one the document loaded with — sits in a `kmk-seg` marker, and a throttled probe
(`NovelContinuous.PROBE`) walks them to report which section the viewport is over and how far in.
Near the bottom, the next section is appended into the same DOM (`innerHTML` on a detached node,
so a book's scripts cannot run); near the top, the previous one is prepended with the scroll
compensation in the same synchronous script, so no frame shows the shift. The window is trimmed to
two sections either side of the current one. The probe's report drives the same things a page
report drives in the continuous manga viewer: the page counter (`onPageSelected`), the saved
position, the contents sheet's marker, and the chapter-progress slider. A push that keeps trying to
scroll past the end of the *book* — where no append can answer — turns to the next volume, the way
the continuous manga reader runs off its last page; mid-book, "nowhere to scroll" means an append
has not landed, and is not a turn. A tap, a button or a contents jump re-renders fresh and starts a
new window on purpose.

The settings UI for all of the above is the **Reading appearance** tab in `ReaderSettingsDialog`
(`NovelTypographySettingsPage`), offered only while the open chapter is text — the same signal that
chose the renderer. Its rows are hand-built from `presentation-core`'s `SettingsItems` rather than the
settings framework: the values are floats the stylesheet reads as page fractions, and tri-states,
which the framework's int-slider and switch helpers cannot express.

**While a book is open, the settings sheet offers only what a book can use.** The reading-mode tab
is not offered at all (a book's renderer is fixed; every row there — scale types, crop, spreads,
per-viewer tap layouts — is an image-reader setting that would silently do nothing), the colour
filter waits for actual bitmap pages (`hasPagesFlow` excludes text, because a book's documents *are*
its page-list entries and a count alone cannot tell the two apart), and the general tab drops its
image-reader rows (long-tap page actions, chapter transitions, page flash, auto webtoon). What
remains — background theme, page number, navigator layout, fullscreen, keep-screen-on, completion
sounds — is everything a book can act on, beside its own Reading appearance tab.

**Long-press is split on what the finger is on, and only the WebView's hit test knows.** On text it
answers false and the engine's own selection proceeds; on an `IMAGE_TYPE`/`SRC_IMAGE_ANCHOR_TYPE`
hit (`NovelWebViewer.setOnLongClickListener`) the `NovelImageActionsDialog` sheet opens, whose one
action sets the series cover. This is the revival of the page-actions sheet that was removed because
it crashed on books — with the two halves of that crash designed out: the sheet is offered *only*
for an illustration (never on a text press) and its action acts on that image's own stream, resolved
by `ReaderViewModel.setBookImageAsCover` from the loader (`resourceSource` for a `book.internal`
URL, base64 decode for a `data:` URI `NovelInline` embedded), never on `ReaderPage.stream`, which a
text page does not have. The URL comes from the hit test, which is either of those two spellings;
an external image opens no sheet at all. Known gaps: an inline-SVG `<image>` and a CSS background
are not hit-tested as images, so no sheet appears for them — the extra `Message`-based
`requestFocusNodeHref` hop exists because for an image inside a link the synchronous hit test's
`extra` names the link's target, not the picture.

## Contents and search

`TextTocSource` and `TextSearchSource` are implemented by the EPUB source and had no UI at all. The
two sheets close that, and both are offered from the bottom bar **only when the open chapter's
loader can answer** (`TextPageLoader.tocSource` / `.searchSource`) — a button that opens an empty
sheet reads as a bug in the reader rather than in the book.

**A jump's destination belongs to its [showDocument] request.** `jumpTo`/`jumpToFraction` pass the
anchor or fraction *into* `showDocument`, which sets the pending position when the request starts and
is what a plain call (a turn, a chapter open) clears. Setting it after the call was the original
design and it silently broke every contents jump into a section the reader had not opened yet: `EPUB`
pages are answers to `TextIncrementalSource`, so the target document's markup is read **asynchronously
after** `showDocument` returns, and the `renderDocument` at the end of that read wiped the intent that
the jump had just set — landing wherever the chapter's stored position (or nothing) said: mid-chapter,
near its end, or in the section the window then grew underneath. Every position is still applied in
`onPageFinished`, after layout: measured earlier, an anchor resolves to the top of an empty page.

**A load is a fresh address, or the engine inherits the scroll of the document it replaced.** Every
document of a book folder loads at the same directory base URL, so an untagged `loadDataWithBaseURL`
looked to the engine like the old page continuing — and a fresh document could start from the scroll
offset of the one that ended: random landing positions, clamped to the bottom of a shorter document,
and after that the window growing below the viewport leaves the reader in the *next* section. Each
load therefore carries its own `#kmk-load-<request>` fragment (nothing a book can name), and the
finish guard ignores the fragment since a WebView has exactly one finish in flight.

**Growth scripts are stamped with the request that built their document.** An append/prepend/trim can
outlive its document: a jump re-renders while its read is in flight, and a script that already left
for the renderer cannot be cancelled. The document declares `window.KmkEpoch` (its own request number)
and each script refuses to run against a different one, answering `false` — which the host reads back
with `NovelContinuous.isStale` and drops, instead of moving `windowFirst`/`windowLast` of a window
that just ended, or worse, letting a stale *prepend* scroll the reader with its own compensation. The
render also cancels the window job in flight, for everything that has not left yet.

**A contents entry for another chapter is shown, not dropped.** A whole-book contents points at files
the open chapter never loaded. Those rows render disabled (`NovelTocEntry.reachable`) so the structure
of the book stays visible; silently doing nothing on tap reads as a broken button.

**The chapter selector is the contents list, for a book.** A book is one library chapter, so
`Dialog.ChapterList` renders the ToC sheet for a text chapter instead of the library's chapter list —
for a novel that list was a single row, and for a folder of novels a list of *other books*. Both
entries to the sheet go through one `showNovelContentsSheet` in `ReaderActivity`, because the wiring —
which documents this chapter loaded, which the reader is inside, the model's scope — is most of its
body and a second copy would drift. A chapter whose source cannot answer falls back to the library
list rather than to a dead button.

**A search hit carries a document offset *and* a document length, in the source's plain text.** The
host holds markup, the source searched plain text, and dividing one by the other lands tens of
percent of a page out — so the ratio crosses over and only the ratio. `toSearchResults` keeps hits
whose source reported no length (an extension predating the field) at the top of their document
rather than dropping them. See `TextSearchHit` for the whole argument.

**Contents fragment ids are untrusted.** They come out of the book's own TOC and end up inside a
script, so they go through `NovelScrollBridge.quoteForJs` — including `</` and U+2028/U+2029, which a
JS parser treats as script terminators and Kotlin does not. `DocumentHarness` asserts the
break-out cases.

**Viewer selection is by loader, and it never falls back.** `NovelViewerProvider` claims a chapter
whose loader is a `TextPageLoader` — which is exactly what `PageLoaderRegistry` picks for a
`TextSource` and nothing else. An extension therefore gets this reader the moment it implements
`TextSource`, with no change here.

Two rules make the fall-through impossible, and both were learned the hard way:

- **Decide from the loader, not from the page content.** `page.textPage` was the original signal and
  it is wrong twice over: it needs the page list to exist, and while it does not it reports null —
  indistinguishable from "not a text chapter", which is precisely how an EPUB reached the pager. The
  loader is assigned by `ChapterLoader` before a single page is read, and lands in the same state
  update that publishes `viewerChapters`, so it is available exactly as early as anything could be.
- **`ViewerRegistry` asks `declinesTextChapters`, so an image viewer is never even offered a text
  chapter.** A `null` from a provider means "not my kind of chapter", not "not this instance of it". If
  no provider claims a text chapter, `create` throws rather than handing an EPUB to a pager that can
  only render it blank.

`ReaderActivity.updateViewer` guards on `currentChapter?.pageLoader == null` — not on pages. A viewer
that can decide from chapter content would otherwise be consulted before there was anything to decide
on and decline, and nothing would rebuild it later. `ReaderActivity.setChapters` calls back in.

**The chosen renderer is logged.** `updateViewer` logs which viewer it built and whether the chapter
was text. A wrong choice is invisible from the UI — the reader opens, the app bar looks right, the
page is blank — so that line is what makes the next report diagnosable instead of a guessing game.

**The novel viewer turns chapters itself.** It has no transition page to draw the way the pager and
webtoon viewers do, so reaching a chapter boundary goes through `ReaderViewModel.goToAdjacentChapter`,
which reuses the same `loadAdjacent` path the toolbar buttons use. Not `onPageSelected`: that returns
early while `chapter.pages` is null, which is exactly the case for a chapter not yet loaded.

**For a book, the bottom navigator is a chapter navigator and says so.** The prev/next buttons call
`NovelWebViewer.nextSection`/`previousSection` — the same `advanceDocument`/`retreatDocument` a tap
at either edge runs, so a button can never mean "skip the whole book" (one library chapter *is* the
book) nor disagree with the gesture about what "next" means. Their enabled state is
`hasNextSection`/`hasPreviousSection`, recomputed off the same state changes that repaint the bars.
The slider stays, as a *chapter-progress* slider (`chapterFraction` through `ReaderAppBars`, decided
from the loader): a book has no pages to count, so the bar shows the reader's place in the chapter
on screen as a percentage, and dragging it seeks through that chapter — `seekFraction` mutes the
probe's write-back until the drag ends, so a slow seek cannot fight the thumb. Sequential movement
is scrolling, taps and the buttons; non-sequential is the contents list.

Resources (images, CSS, fonts) are streamed by the `TextPageLoader`'s `resourceSource` over the
reserved `https://book.internal/` origin — see `EpubArchiveUrls` in `core/archive` — and, for the references a
document actually reads, inlined into it by `NovelInline` (see above: the inliner and the server
read the same archive lease).

## Page splitting

A page too tall for the decoder is cut into segments by `HttpPageLoader` and **replaces** its parent
in the chapter's list, mid-session. `ReaderChapter.pageListVersion` is how the viewers learn; the
mechanics are in `model/SplitPageIndex.kt` (`internal`), `ReaderChapter.replacePages` (identity
indexes) and `WebGpuPages`→`WebGpuPageModel.linkedNeighbour`. Anything that walks the page chain
must handle a page that is no longer in the list.

## Pager / webtoon split

Strip and continuous reading use separate image processors (split 2026-08-31):
`PagerPageImageProcessor.kt`, `PagerJoinedItemsBuilder.kt`, `WebtoonPageImageProcessor.kt`.
`WebtoonBaseHolder.viewer` is public for cross-holder access. Keep the two paths separate.

## Komikku additions (KMK)

`ChapterCompleteSoundPlayer.kt` + `ChapterCompleteSoundPackManager.kt` (chapter-finish audio),
`WebhookReadingTimeTracker.kt` (reading-time webhook events). New KMK strings → `KMR` + `i18n-kmk/`.

## Conventions

- Reader stays an `Activity` — do not migrate it into Voyager.
- State in `ReaderViewModel` (AndroidX); inner screens use Voyager ScreenModels, not the reverse.
- Upscale changes stay behind the MTL gate; per-series toggles via `UpscaleMangaStore`.
- Logging: `xLogE()` / `xLog()` from `exh.log` for KMK code; `logcat {}` for Mihon code.

## Anti-patterns

- Do not launch the reader through a Voyager `Screen` push — use `ReaderActivity.newIntent(...)`.
- Do not merge the pager and webtoon image processors back into one path.
- Do not grow `WebGpuViewer.kt` past wiring — a new state machine belongs in its own file as a
  `WebGpuViewer` extension, so the class stays readable as a whole.
- Do not build an `ImagePage` and drop the reference without `cleanup()` — it owns uploaded GPU
  textures.
- Do not give a text preference a non-null `false` default. `null` = follow the publisher; `false` =
  force it, and it cannot then be unforced.
- Do not re-run `loadDataWithBaseURL` to apply a typography change — it drops the scroll position on
  every slider drag. Swap the stylesheet in the live DOM (`liveTypographyJs`).
- Do not load a novel document at `EpubResourceReader.BASE_URL`. That is the archive root and is only
  right for a spine item that already sits there; take the base from the page.
- Do not let a viewer provider return null for a chapter it is the only renderer for. That is
  not a fallback, it is handing the reader to something that cannot display the content.
- Do not add a method to `TextSource` / `TextResourceSource` / `TextPage`. Extensions implement them
  across a classloader boundary, so a new abstract method breaks every one that predates it — add a
  separate optional interface instead, as `TextBaseUrlSource` does.
- Do not wrap a `suspend` call in `runCatching`. Its block is not a suspend lambda; it does not
  compile. Use `try`/`catch`.
- Do not interpolate an id, fragment or path from a book into a script without `quoteForJs`.
