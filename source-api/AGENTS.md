# source-api/ Module

Extension `Source` API (Kotlin Multiplatform). Package root: `eu.kanade.tachiyomi.source.*`.

## Purpose

Defines the contract that APK extensions (installed separately) must conform to. **Avoid breaking extension ABI.**

## Key interfaces

- `Source` – Base source interface
- `CatalogueSource` – Sources with browse/search (suspend APIs)
- `HttpSource` – HTTP-based sources with OkHttp client
- `MetadataSource<M, R>` – Sources providing structured metadata
- `UrlImportableSource` – Sources supporting URL import
- `NamespaceSource` – Sources with tag namespaces
- `PagePreviewSource` – Sources supporting page preview thumbnails

## Text source API (`eu.kanade.tachiyomi.source.text`, KMK)

The extension point for **EPUBs and web novels**. It sits *beside* the image `Page` model rather than
replacing it: an image source implements `getPageList(chapter): List<Page>`, a text source implements
`getTextPageList(chapter): List<TextPage>`. A host checks which applies before building a renderer.

| File | Contract |
|------|----------|
| `TextPage.kt` | One unit of text. `kind` is `HTML` or `PLAIN`; `baseHref` resolves relative refs |
| `TextSource.kt` | `getTextPageList` + `textCapabilities` |
| `TextCapabilities.kt` | What the source actually implements, so hosts hide controls they cannot honour |
| `TextTocSource.kt` | `getTableOfContents` -> `TocEntry` (nested) |
| `TextSearchSource.kt` | `searchInChapter` -> `TextSearchHit` |
| `TextSearchHit.kt` | One match: snippet + where it is, in the source's own plain-text units |
| `TextAnnotationSource.kt` | Highlights / bookmarks / notes persistence |
| `TextResourceSource.kt` | Streams a page's images, CSS and fonts |
| `TextBaseUrlSource.kt` | Optional: the URL each page must be *loaded* at |

**Why `TextPage` is not `Page`.** An image has progressive load states, a progress flow and a retry
path. Text arrives with the chapter list. Modelling it as `Page` would make every host carry a status
machine for a failure mode that cannot occur.

**Why `TextResourceSource` is separate from `TextSource`.** Markup references resources the host
cannot reach. Inlining every image as a `data:` URI turns one illustrated chapter into tens of
megabytes of base64 held for as long as the chapter is open. Instead the source streams them on
demand — see `EpubArchiveUrls` / `EpubResourceServer` in `core/archive` for the reserved
`.internal` origin that makes `../images/plate.jpg` resolve inside a zip file.

**Rules for implementors.** All three are enforced by hosts and all three are easy to get wrong:

- `TextPage.url` must be **stable across library refreshes** — it is the identity for reading
  position and annotations. No timestamps, no array indexes.
- `TextPage.text` must be **self-contained** — hosts hand it straight to a WebView, so inline the
  styles a page needs rather than assuming host-supplied CSS.
- Set `TextPage.baseHref` whenever a page references external resources.
- Return **natural units** (EPUB spine items, one webnovel chapter per page), not pre-paginated
  chunks: hosts re-flow text to the screen, so a fixed character count is cut mid-sentence at most
  font sizes.

**Never add a method to an interface here.** Extensions loaded through a classloader implement
`TextSource`, `TextResourceSource` and friends; a new abstract member breaks every extension that
predates it, and R8 cannot see the implementations across the dex boundary to warn you. Add a new
optional interface instead and have hosts check for it — `TextBaseUrlSource` exists for exactly this
reason: `TextResourceSource` can open an absolute URL, but only the source knows the absolute URL its
pages' relative references should resolve against, and that is the difference between a book
rendering with its own stylesheet and rendering as bare text.

**Adding a field to a model here is safe; adding a parameter without a default is not.** The models
are data classes with defaults and are covered by `@Serializable`, so a new field is source- and
format-compatible: an older extension keeps loading, a newer host reads the default. `TextSearchHit`
gained `documentOffset`/`documentLength` this way, both defaulting to 0, because without them a host
physically cannot scroll to a hit — the only offset it had was into the snippet.

R8: every class here is covered by `consumer-proguard.pro`. Extension APKs load via classloader, so
R8 cannot see their implementations across the dex boundary.

## KMP structure

- `commonMain` source set defines the extension API
- `androidTarget()` for Android platform
- Non-standard: only this module uses KMP; all others use `kotlin("android")`

## Delegated sources

In `exh/source/`: `DelegatedHttpSource`, `EnhancedHttpSource` for in-repo sources (NHentai, 8Muses, etc.)

## Per-source preferences (adjacent surface)

`ConfigurableSource` implementations keep prefs in per-source SharedPreferences files named
`source_$id` (extension ABI) — separate from the app-wide `PreferenceStore` and deliberately
**outside** the modular settings framework (`settings/framework/`). Do not migrate source
prefs into `SettingKey`s; the framework must never assume the default shared-prefs file.

## Conventions

- **NEVER** break extension ABI
- Package root: `eu.kanade.tachiyomi.source.*`
- Fork markers: `// SY -->` for TachiyomiSY additions
