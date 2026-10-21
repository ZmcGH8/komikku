# Library entry types — books in the manga library

**Date:** 2026-10-17
**Status:** Proposed design
**Supersedes:** the split between `source-local`'s `LocalSource` (manga, EPUB→images) and
`source-epub`'s `LocalEpubSource` (books, EPUB→text)

---

## 1. Problem

An `.epub` in the manga library renders as a strip of images. This is not a bug in the reader's
routing; it is the designed behaviour, and it is decided in one place — `PageLoaderFactory`:

```kotlin
fun localLoader(source: LocalSource): PageLoader =
    source.getFormat(dbChapter).let { format ->
        when (format) {
            is Format.Directory -> DirectoryPageLoader(format.file)
            is Format.Archive   -> ArchivePageLoader(format.file.archiveReader(context))
            is Format.Epub      -> EpubPageLoader(format.file.epubReader(context))   // ← always images
        }
    }
```

`EpubPageLoader.getPages()` calls `EpubReader.getImagesFromPages()` and produces `ReaderPage`s whose
streams are image bytes. `TextPageLoaderProvider` is never consulted: it only claims a `TextSource`,
and `LocalSource` is not one. So a book in the manga library cannot reach the book reader by any
route, and the log line `loader chosen = EpubPageLoader` is the correct answer to "why is this a
comic".

Today the only way to read an EPUB as text is `LocalEpubSource` (id `7812`), rooted at
`<local>/novels/`. That is a **separate source identity**: a separate row in the source table, a
separate browse surface, a separate library. A book cannot appear next to the comics it was filed
with, and the two libraries drift apart for no reason the user chose.

The goal is one library where an entry declares what it is, and the reader honours that.

### What this does not do

It does not remove `LocalEpubSource`, and it does not change how `source-local` stores or lists
anything. It changes *which renderer an entry gets*, and gives that decision a place to live.

---

## 2. The decision, and where it lives

An entry is **COMIC** or **BOOK**. One bit of persisted state per manga, consulted by the loader
chain, exactly where `Format.Epub` is consulted today.

### Reusing `is_light_novel` rather than adding a column

`mangas.is_light_novel` already exists, is already plumbed through `MangaMapper`, the domain `Manga`,
and `BackupManga` (`@ProtoNumber(617)`). It is currently read only by
`exh.source.DomainSourceHelpers.Manga.isLightNovel()`, where it means "this is E-Hentai light-novel
content" — a *provenance* question, not a *content-type* question.

Two arguments for reusing it anyway, and one against:

- **For:** zero migration, zero SQLDelight regeneration, zero backup-proto change, and one flag
  rather than two that can disagree. `BOOK` is a superset of what `is_light_novel` currently means
  for the E-Hentai case.
- **Against:** the two meanings are not the same. A source can be E-Hentai *and* serve an art book as
  EPUB; `isLightNovel()` would then be true and the entry would open as text, breaking the art-book
  case that `source-epub/AGENTS.md` says is relied upon.

**Decision: reuse the column, and narrow the E-Hentai helper.** `Manga.isLightNovel()` becomes
`isLightNovel || source in lnSourceIds` — already what it is — while the *reader* consults the raw
column as content type. This keeps E-Hentai's behaviour identical (it already ORs the source-id
check) and gives the reader a clean bit. The column's SQL name stays `is_light_novel` for
migration reasons; the Kotlin name is where the new meaning is documented.

If the E-Hentai semantics turn out to conflict in practice, the fallback is a new
`entry_type INTEGER NOT NULL DEFAULT 0` column and a `65.sqm`. That is a strictly larger change and
is deliberately **not** taken up front.

### Overriding

Per-entry, in manga info. An entry can be forced to BOOK (a book whose spine defeats detection) or
forced to COMIC (an art book that would otherwise open as text). Overrides live in the same column:
`BOOK` when set, otherwise detected.

### Detection

Per chapter, because a folder can mix formats and the manga source already knows the per-file format.

| Signal | Reading |
|---|---|
| `Format.Epub` | candidate — never decisive on its own |
| spine is mostly prose vs mostly `<img>` | the real discriminator |
| substantial TOC present | corroborating |

**A heuristic will misfire**, and that is the accepted trade: it is overridable, and the override is
the reason this is safe to ship. Detection lives in `core/archive` (`EpubReader.proseMajority()`),
pure and harness-assertable, because it reads the spine and is exactly the kind of thing that should
be provable off-device.

---

## 3. Extension ABI

New **optional** interface. Nothing existing changes shape, so every extension that predates this
keeps loading.

```kotlin
// source-api, eu.kanade.tachiyomi.source.model
interface EntryTypeSource {
    /**
     * The content type of [manga], or null to leave it to the host.
     *
     * Null is the default and the honest answer for a source that has no opinion. Returning null
     * never overrides a user's explicit choice — the host only asks when nothing has been decided.
     */
    fun entryTypeOf(manga: SManga): EntryType? = null
}

enum class EntryType { COMIC, BOOK }
```

Per the `source-api/AGENTS.md` rule — *never add a member to a shipped interface, add an optional
one* — this is a new interface the host type-checks for, exactly as `TextBaseUrlSource` already is.

`TextCapabilities.IMAGES` deserves a note: it is declared and **read by nothing**. It describes
whether a text chapter's pages contain images. Under this design it becomes load-bearing for the
image toggle below, which today has no implementation at all.

---

## 4. Behaviour

### Routing

`PageLoaderFactory.localLoader` consults the resolved type before the format:

```
Format.Epub + entry is BOOK  -> TextPageLoader (via the EPUB text path)
Format.Epub + entry is COMIC -> EpubPageLoader  (unchanged)
Directory / Archive          -> unchanged
```

The text path needs a `TextResourceSource` to stream images out of the archive. `LocalSource` does
not implement it today. It must — or the book renders as bare text with every illustration missing,
which is the exact failure the per-document base URL work fixed and would reintroduce.

**This is the single largest piece of the work and the easiest to get wrong.** It is also the reason
this is phased rather than done in one commit.

### Images inside a book

Per the decision: **default on**. A book with inline illustrations shows them. Off means the
`<img>` elements are removed rather than broken, so the text reflows without placeholder gaps.

The toggle is a reader preference (`NovelTypographyPreferences` is the store; typography is the
right home because it is a rendering choice, not a library fact). It gates the sanitizer, not the
source — a source cannot honour it, since the markup has already been produced.

Today this is inert: `TextCapabilities.IMAGES` is unread, the WebView fetches whatever the markup
asks for, and there is no switch. So "default on" is the current behaviour and the toggle is new
functionality rather than a change to it.

### Art books

They keep working. `Format.Epub` + no TOC + image-dominated spine resolves to COMIC and routes to
`EpubPageLoader` unchanged. The regression risk is real and is why detection is overridable.

---

## 5. Delivery

Four phases. Phases 1–2 are provable off-device; 3–4 need the reader.

| Phase | Contents | Gate |
|---|---|---|
| **1. Model + ABI** | `EntryType`, `EntryTypeSource`, narrow `isLightNovel()`, `EpubReader.proseMajority()`, `NavigationHarness`-style assertions | harness passes |
| **2. EPUB text path** | `LocalSource` implements `TextSource` + `TextResourceSource` for EPUB; contents and search via the existing interfaces | harness passes |
| **3. Routing** | `PageLoaderFactory` consults the entry type; `ViewerRegistry`/`isTextChapter` unchanged (still loader-derived) | user build |
| **4. Surfaces** | Manga-info override, image toggle, `source-epub/AGENTS.md` + `ui/reader/AGENTS.md` | user build |

Phase 3 is where the images bug dies, and it is deliberately **after** phase 2: routing a book to a
text path that cannot yet serve its images would produce a worse bug than the one being fixed.

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| Detection misfires on a book | Per-entry override; art books resolve to COMIC by default |
| `LocalSource` becomes a `TextSource` and every comic becomes text | The text path is gated on `Format.Epub` + entry type, never on the source alone |
| Art-book libraries silently change | COMIC is the detection default for image-dominated spines; `source-epub/AGENTS.md` records the reliance |
| Phase 3 lands before phase 2 | Sequencing above; phase 3 is not viable alone |
| Schema needed after all | Fallback documented in §2; no migration taken up front |

---

## 7. Explicitly out of scope

- Removing `LocalEpubSource` or unifying the two source identities.
- Multi-format chapters (CBZ containing an EPUB, and so on).
- A library-wide view filtered by entry type — this makes the type *honoured*, not *visible*.
- The native text renderer (`2026-10-16-native-text-renderer-design.md`). This design is orthogonal:
  it decides *which* renderer claims a chapter, not how text is laid out.