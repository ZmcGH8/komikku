# Native text renderer for the novel reader

**Date:** 2026-10-16
**Status:** Approved design
**Supersedes:** the WebView-based novel renderer in `viewer/novel/`

---

## 1. Problem

A novel chapter is rendered by handing publisher-supplied XHTML to an Android `WebView`
(`NovelWebViewer`) with `javaScriptEnabled = true`, loading it via `loadDataWithBaseURL` against an
`epub-archive://book.internal/` base URL, and injecting a `<script>` bridge so the host can measure
and scroll the document.

That design has one property that cannot be fixed by hardening: **the content is executed, not
parsed.** Everything the WebView exposes — a JS context, `<base>`, `<meta http-equiv="refresh">`,
navigation, `file://` and `content://` access, mixed content — is reachable by the file the user
downloaded. Hardening narrows it. Deleting the WebView removes the category.

The WebView was chosen for a real reason, recorded in `app/.../ui/reader/AGENTS.md`: an EPUB *is*
XHTML carrying the publisher's own stylesheet — drop caps, ruby, columns, vertical writing, tables —
and re-implementing that in Compose "means re-implementing a browser." This design keeps the part of
that argument that matters (publisher typography survives) and drops the part that does not (we
interpret a *defined subset* of EPUB CSS rather than shipping a browser engine).

### What this deletes

| Deleted | Because |
|---|---|
| `NovelWebViewer.kt` | The WebView itself. Replaced by `NovelTextViewer.kt`. |
| `NovelDocument.kt` | Existed only to make XHTML palatable to a browser (strip prolog, neutralise doctype, inject `<base>`). Nothing needs it. |
| `NovelScrollBridge.kt` | `window.KmkReader`, `quoteForJs`, the script-injection surface. All of it. |
| `NovelTypographyCss.kt` | Typography becomes resolved values, not a stylesheet string. |
| `epub-archive://` as a *document-loading* mechanism | Survives only as an internal image path. |

### What this fixes outright

**The base-URL class of bugs ceases to exist.** `ui/reader/AGENTS.md` calls the per-document base URL
"the highest-consequence detail in the folder" — loaded against the archive root instead of the
document's own directory, every stylesheet, illustration and embedded font 404s and the book renders
as bare text with no error anywhere. There is no browser to hand a base URL to. Relative hrefs are
resolved against the document's own in-archive path using `EpubPaths.resolve`, which already exists
in `core/archive`, is pure JVM, and is covered by `PathHarness`.

---

## 2. Pipeline

```
spine item XHTML (bytes from the archive)
  ↓  NovelMarkupParser      XHTML        → NovelNode tree      (jsoup in, our tree out)
  ↓  NovelCssParser         CSS text     → List<NovelRule>
  ↓  NovelCascade           rules + prefs→ Map<NodeId, NovelStyle>
  ↓  NovelLayout            doc + styles → List<NovelBlock>
  ↓  NovelTextViewer        Compose
```

One concern per file, matching how `viewer/webgpu/` is organised (flat, one file per concern, with
the class itself kept to wiring).

| File | Concern |
|---|---|
| `NovelNode.kt` | The renderable tree. Pure data, no Android. |
| `NovelStyle.kt` | Resolved style + `WritingMode`. Pure data. |
| `NovelSanitizer.kt` | The allow-list. What is dropped and why. |
| `NovelMarkupParser.kt` | XHTML → `NovelNode`. |
| `NovelCssParser.kt` | CSS text → `NovelRule[]`. |
| `NovelSelector.kt` | Selector parse, match, specificity. |
| `NovelProperties.kt` | The property subset: parse, inherit, resolve. |
| `NovelCascade.kt` | Rules + reader preferences → `NovelStyle` per node. |
| `NovelLayout.kt` | Document + styles → measured `NovelBlock`s. |
| `NovelWalkerHorizontal.kt` | Phase-1 layout strategy. |
| `NovelWalkerVertical.kt` | **Deferred to phase 4.** Named now so the seam is real. |
| `NovelTextViewer.kt` | The `Viewer`: Compose, taps, keys, chapter turning. |

---

## 3. The node model

`NovelNode` is a small sealed hierarchy containing only what Compose can draw. **Anything not on this
list does not exist.** That is the security boundary, enforced by the type rather than by a denylist.

```kotlin
sealed interface NovelNode {
    sealed interface Block : NovelNode
    data class Paragraph(val runs: List<NovelRun>, val style: NovelStyle) : Block
    data class Heading(val level: Int, val runs: List<NovelRun>, val style: NovelStyle) : Block
    data class Quote(val runs: List<NovelRun>, val style: NovelStyle) : Block
    data class Figure(val image: NovelImage, val caption: List<NovelRun>) : Block
    data class Rule(val style: NovelStyle) : Block
    data class Spacer(val height: Float) : Block
}

sealed interface NovelRun {
    data class Text(val text: String) : NovelRun
    data class Styled(val runs: List<NovelRun>, val style: RunOverride) : NovelRun
    data class Ruby(val base: List<NovelRun>, val annotation: List<NovelRun>) : NovelRun
    data object Break : NovelRun
}

/** An image, already resolved against the document's own directory. */
data class NovelImage(
    val archivePath: String,   // in-archive path, `EpubPaths.resolve`d — never a URL
    val alt: String,
    val maxWidthFraction: Float,  // of the column; clamped before decode
)
```

`archivePath` is an **in-archive path, not a URL**. The renderer never sees a scheme, and the loader
resolves it against the open archive at paint time. Same decision as dropping base URLs, applied to
images.

Unknown-but-harmless elements — including namespaced ones such as `<div epub:type="chapter">` —
contribute their children. That is what keeps them working.

---

## 4. The styling engine

### 4.1 Origins, not `!important` fights

The current design injects the reader's stylesheet last with `!important` on nearly every line, and
`!important` vs `!important` is decided by source order — correct by accident, fragile by
construction.

Every declaration carries an **origin**. The winner per property is the maximum of:

```
(origin, important, specificity, sourceIndex)
```

with `PUBLISHER < USER`. **`USER` beats `PUBLISHER` outright.** When a reader picks 20pt, the
publisher's `body { font-size: 12pt }` does not get a vote.

The existing tri-state preferences survive and get *stronger*. `justified`, `hyphenation` and
`verticalWriting` are `Boolean?` meaning "follow the publisher". In the old world that was a
convention — "omit the rule when null and hope". Here it is structural: `null` emits **nothing** from
the `USER` origin, so the publisher's value stands by construction rather than by remembering to
leave a line out.

**A non-null `false` still means "force it off"** and must never be dropped. That rule in
`ui/reader/AGENTS.md` survives the rewrite unchanged.

### 4.2 Property subset

**Interpreted:** `font-size` (pt/px/em/rem/%/keywords), `font-family`, `font-weight`, `font-style`,
`font-variant` (small-caps), `color`, `line-height`, `letter-spacing`, `word-spacing`, `text-align`,
`text-indent`, `text-transform`, `text-decoration`, `vertical-align`, `white-space`, `margin-*`,
`padding-*`, `display`, `writing-mode`.

**Dropped** — each safe for *prose*:

| Dropped | Why |
|---|---|
| `float`, `position`, `z-index`, `transform`, `animation`, `transition` | Layout engine. Prose does not use it. |
| `columns`, `column-count` | Multi-column: rare, expensive. |
| `flex`, `grid` | Essentially absent from EPUB prose. |
| `box-shadow`, `opacity`, `filter`, `background-image` | Decorative. |
| `@media print` | We are a screen. Width queries evaluated against a small context. |

**Dropped as security decisions**, not layout ones:

- **`@import` is refused outright.** A book saying `@import url(...)` makes the reader fetch an
  attacker-chosen URL — leaking that this user opened this book, from this IP, today. There is no
  rendering reason to allow it.
- **`@font-face` captures the family name and never fetches `src`.** Same class of leak. The family
  remains usable if the reader already has it installed.

### 4.3 Vertical writing — designed in, not built

`NovelStyle.writingMode` is a resolved, inherited property — not a flag bolted onto the renderer.

`NovelLayout` returns `NovelBlock`s carrying **measured geometry**, so a horizontal walker and a
vertical column-walker produce the same output type and differ only in strategy. Phase 1 ships
`HorizontalWalker` and, on a vertical book, **says so plainly** rather than silently mangling it.
Phase 4 adds `VerticalColumnWalker` with nothing above it changing.

This is the honest meaning of designed-in: the seam is named and typed, not that it ships.

---

## 5. Sanitizer

`NovelSanitizer` is an allow-list. Dropped, each with a reason:

| Dropped | Why |
|---|---|
| `<script>` | Script execution. The whole reason for this rewrite. |
| `<base>`, `<meta http-equiv>` | We resolve hrefs ourselves; a redirect cannot be honoured by construction. |
| `<iframe>`, `<object>`, `<embed>`, `<frame>` | No content embedding. |
| `<form>`, `<input>`, `<button>` | No interaction model; nothing to submit to. |
| every `on*` attribute | Script execution. |
| `javascript:` URLs | Script execution. |
| `data:` image `src` | A megabyte of base64 inside a markup string is a memory bomb, and it is never legitimate in an EPUB. |

**Kept:** `<style>` and `<link rel=stylesheet>` — that CSS is the entire point. `<a>`, with a
sanitised href: `#fragment` becomes an in-book anchor, `http(s)` is handed to the system and **never
loaded in-reader**.

Image `src` resolves against the document's own directory; decoded size is clamped.

---

## 6. Reading position

Today's `NovelProgress.Position(documentIndex, characterOffset, characterLength)` plus a scroll
fraction is approximate precisely because it is measured against a layout the host does not control.

The native renderer should persist a **structural** position:

```
Position(documentIndex, blockIndex, offsetInBlock)
```

Stable across font size, orientation and screen size, because it is structural rather than geometric.

Migration is one-way and lossy in the harmless direction: an old fraction-based position is mapped to
a fraction within the block containing that fraction on first open. One imperfect restore, once.

> **Scope note:** this is separable from the renderer and can ship independently. It is in scope
> because the reading-position model is part of "how the reader works", but it is the piece most
> likely to be deferred if phases run long.

---

## 7. Delivery

Four phases. Phase 1 is deliberately separated because it carries the risk and needs no UI.

| Phase | Contents | Depends on |
|---|---|---|
| **1. Engine (offline)** | `NovelNode`, `NovelStyle`, `NovelSanitizer`, `NovelMarkupParser`, `NovelCssParser`, `NovelSelector`, `NovelProperties`, `NovelCascade` + harness assertions | — |
| **2. Renderer** | `NovelLayout`, `NovelWalkerHorizontal`, `NovelTextViewer` | 1 |
| **3. Cutover** | Contents / search / position integration; **delete** `NovelWebViewer`, `NovelDocument`, `NovelScrollBridge`, `NovelTypographyCss` | 2 |
| **4. Vertical writing** *(deferred)* | `NovelWalkerVertical`, behind a flag | 2 |

**Replacement is outright** — no feature-flagged fallback, no long-lived dual path. The concentration
of risk is the point: phase 1 is proven against fixture books before it becomes the only path.

### What survives the cutover, and why

| Kept | Note |
|---|---|
| `EpubResourceReader` | Still streams images out of the archive. Loses `BASE_URL` / `isArchiveUrl` / `documentBaseUrl` — all three existed only to feed a browser. |
| `TextResourceSource` (extension API) | Images are the remaining use. |
| `TextBaseUrlSource` (extension API) | **Stays in the API untouched** — it ships and extensions implement it across a classloader boundary. `LocalEpubSource` simply stops implementing it, because it no longer has a browser to address. |
| `NovelTypographyPreferences`, `NovelProgress` | The settings and the percentage model are renderer-independent. |
| `EpubPageLoader` + `source-local`'s `fillMetadata` | The manga local source renders EPUB as images and is untouched throughout. |
| `NovelViewerProvider` | Moves out of `NovelWebViewer.kt` into its own file, since the file it lives in is deleted. `ViewerRegistry.declinesTextChapters` and the "never declines a text chapter" rule are unchanged. |

### Testing

The styling engine is pure JVM with no Android imports, which makes it directly assertable through
the existing `scripts/kotlinc-check/harness` mechanism — the same pattern `EpubPaths`, `NovelDocument`
and `NovelProgress` already use.

| Harness | Asserts |
|---|---|
| `MarkupHarness` | Fixture XHTML → expected `NovelNode` tree; `<script>`/`on*`/`javascript:` absent; `<style>` retained; unknown elements contribute children |
| `StyleHarness` | Specificity, origin precedence, `!important` within an origin, inheritance, `null` = follow the publisher, `false` = force off |
| `LayoutHarness` | Document + styles → blocks; vertical mode recorded and flagged, not mangled |

`core/archive/AGENTS.md`'s existing `PathHarness` continues to cover href resolution, which is now the
*only* mechanism by which a document finds its resources.

---

## 8. Explicitly out of scope

- Floats, positioning, transforms, animation, multi-column, flex, grid.
- Full ruby *positioning* (over/under runs). Basic ruby above the base is in scope; the full annotation
  model is not.
- Vertical writing (phase 4).
- Remote resource fetching of any kind, from books, ever.
- HTML content beyond the EPUB subset (the manga local source renders EPUB as *images* and is
  untouched by this work).
