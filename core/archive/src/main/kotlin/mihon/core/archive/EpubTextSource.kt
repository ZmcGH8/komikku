package mihon.core.archive

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Serves an EPUB chapter as text, in the form a book's reader wants: one document per spine item,
 * with the publisher's markup untouched.
 *
 * ### Why this lives in `core:archive` rather than in the source that uses it
 *
 * The manga local source serves books, and so does anything else that ever grows a book half —
 * the reader and the source cannot depend on each other, and a book must read the same wherever
 * it was opened from. Everything that decides what a book *is* to a host lives here: the spine,
 * the size ceilings, the stripped plain text that search runs over, and the contents list. Two
 * implementations would drift, and the failure mode of drift is a book that reads correctly in
 * one place and not the other.
 *
 * ### Bounds, and why each is where it is
 *
 * Everything read out of the file is attacker-controlled, because the file is. Each ceiling is
 * written at the point where the allocation happens rather than at the point where it becomes
 * visible, so a hostile book costs a truncated book instead of a heap exhaustion report.
 *
 * Pure with respect to the archive: no Android imports, and the only state is the caller's.
 */
class EpubTextSource(
    private val readerFor: (SChapter) -> EpubReader?,
) : TextTocSource, TextSearchSource, TextIncrementalSource {

    /** What a book reader can rely on. An EPUB chapter almost always has a TOC and inline images. */
    val capabilities = TextCapabilities.EPUB

    /**
     * The chapter's documents, in reading order.
     *
     * Returns untouched markup. Publishers ship their own CSS and their own structure — tables, drop
     * caps, ruby, vertical writing — and the stripping that makes text extraction easier is exactly
     * what destroys the book. Cleanup belongs in the renderer.
     *
     * This is the eager path, kept for [eu.kanade.tachiyomi.source.text.TextSource.getTextPageList]:
     * it reads every spine item, because that is what a caller of that method has asked for. Hosts
     * that can read a document at a time want [getTextPageIndex] and [getTextPageText] instead — see
     * [TextIncrementalSource] for why that split is the difference between opening a novel and
     * waiting for one.
     *
     * [TextPage.index] is the **spine** index, which is sparse whenever a document is skipped. That
     * is deliberate and is what `TextSearchHit.pageIndex` is defined to carry: it is the document's
     * identity in the book, not its position in a list. A host that re-orders or drops pages must
     * map through it rather than indexing positionally.
     */
    fun textPages(chapter: SChapter): List<TextPage> {
        var totalChars = 0
        return buildList {
            for (page in spineIndex(chapter)) {
                val markup = documentText(chapter, page) ?: run {
                    // Every skipped document is logged by name: a chapter that reads as empty is
                    // otherwise indistinguishable from a broken one, and this is the only line that
                    // names the file responsible.
                    logcat { "EpubTextSource: ${chapter.url} could not read spine item ${page.index} (${page.url})" }
                    continue
                }
                totalChars += markup.length
                if (totalChars > MAX_CHAPTER_CHARS) {
                    logcat { "EpubTextSource: ${chapter.url} exceeds the in-memory markup cap; truncating" }
                    break
                }
                add(page.copy(text = markup))
            }
        }.also { pages ->
            logcat {
                "EpubTextSource: ${chapter.url} -> ${pages.size} text page(s), $totalChars chars"
            }
            if (pages.isEmpty()) {
                logcat(LogPriority.ERROR) {
                    "EpubTextSource: ${chapter.url} produced NO text pages; the reader will refuse " +
                        "this chapter. Check the 'could not read spine item' lines for the cause."
                }
            }
        }
    }

    /**
     * The chapter's spine documents, in reading order, **without reading any of them**.
     *
     * The cheap half of [textPages], and the one a reader actually wants. A spine is a list of records
     * the package document already declared, so this costs nothing; [textPages] then costs one
     * inflate-and-decode per document, which for a five-hundred-document novel is the whole of the
     * reader's startup delay.
     *
     * Each page has an empty `text`. That means "not read", never "empty": a document that genuinely
     * has nothing in it is indistinguishable from one that was never opened, which is why a host asks
     * for the text explicitly rather than deciding from the list.
     *
     * Dense, where [textPages] is sparse: this cannot know which documents will fail to read without
     * reading them. That is the intended trade and it is why every consumer that locates a document by
     * identity — search above all — enumerates through here rather than through [textPages], so the
     * two can never disagree about a document's index.
     *
     * Suspend because it is not free — it opens the archive and parses the package document — which is
     * why the internal work is a plain function so that [textPages] and [plainTextFor], which are
     * already blocking by contract, can share it without becoming suspend themselves.
     */
    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> = spineIndex(chapter)

    /** The same enumeration, blocking. See [getTextPageIndex]. */
    private fun spineIndex(chapter: SChapter): List<TextPage> {
        val epub = readerFor(chapter)
            ?: throw IllegalStateException("Chapter not found: ${chapter.url}")

        if (epub.hasDrm) throw IllegalStateException("This book is DRM-protected and cannot be read")

        val documents = epub.textDocuments()
        if (documents.size > MAX_DOCUMENTS) {
            logcat {
                "EpubTextSource: ${chapter.url} has ${documents.size} spine items; " +
                    "exposing the first $MAX_DOCUMENTS"
            }
        }
        return documents.take(MAX_DOCUMENTS).mapIndexed { index, item ->
            TextPage(
                index = index,
                url = item.href,
                text = "",
                kind = TextPage.Kind.HTML,
                baseHref = item.href,
                name = item.title,
            )
        }
    }

    /**
     * The markup of one spine document, or null when it cannot be read or is over the size ceiling.
     *
     * One document, so the ceiling that matters here is [EpubReader]'s per-entry one rather than the
     * chapter-wide total: the point of reading a document at a time is that a book nobody finished
     * never has to hold all of them at once.
     */
    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? {
        val markup = documentText(chapter, page)
        if (markup == null) {
            logcat {
                "EpubTextSource: ${chapter.url} could not read spine item ${page.index} (${page.url})"
            }
        }
        return markup
    }

    /**
     * The book's contents.
     *
     * Whole-book, not this chapter's: the TOC is a property of the file, and a reader opening a
     * multi-file book wants to see the shape of the volume with the parts it cannot reach greyed
     * out rather than a list that silently stops at its own chapter.
     */
    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> {
        val epub = readerFor(chapter) ?: return emptyList()
        if (epub.hasDrm) return emptyList()
        return epub.toc.map { node -> node.toTocEntry() }
    }

    /**
     * One level of the contents, mapped into the shape a host renders.
     *
     * Recursive rather than fixed-depth: EPUB 3 nav documents nest five or six levels without
     * embarrassment, and truncating at a depth would silently drop real structure. The parser
     * already bounds how deep it will follow.
     */
    private fun EpubTocNode.toTocEntry(): TocEntry =
        TocEntry(
            label = label,
            // fullHref, not href: a contents entry addressing a *section* is `document#fragment`,
            // and the host splits that back apart. `href` alone would drop every section entry onto
            // its document's top.
            href = fullHref,
            children = children.map { it.toTocEntry() },
        )

    /**
     * Finds [query] across the chapter's documents.
     *
     * Searches the **stripped plain text** and reports the offset in those same units. The host holds
     * markup; the source searched text. Handing back a markup offset would put the reader tens of
     * percent of a page from the match, because the markup is longer by exactly the tags and
     * indentation the strip removed. Reporting the stripped length alongside lets the host convert
     * without re-deriving anything.
     *
     * Enumerated through [getTextPageIndex] rather than [textPages], and that is not a shortcut. The
     * host locates a hit by matching `pageIndex` against a page's own index, so the two sides have to
     * be the same enumeration: [textPages] drops a document it cannot read and so is sparse, while the
     * index is dense because it has not tried. A hit from one and a page from the other would put the
     * reader in the wrong document for every hit after the first hole, and a document that fails to
     * read cannot be shown anyway, so losing its hits costs nothing.
     *
     * Caching the stripped text is left to the caller. It depends only on the file, so it is
     * computed once per chapter rather than per keystroke, but *where* to keep it belongs to the
     * source that owns the archive — see `LocalEpubTextAdapter` in `source-local`.
     */
    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim()
        return plainTextFor(chapter).flatMap { page ->
            val plain = page.plain
            plain.indexesOf(needle, plain.length).map { hit -> hit.copy(pageIndex = page.index) }
        }
    }

    /**
     * The chapter's visible text with markup removed and whitespace collapsed.
     *
     * Reads a document at a time and keeps only the stripped text, so this costs a whole book's worth
     * of *plain* text rather than a whole book's worth of markup — and the markup is the larger of the
     * two by the tags the strip removed. It is still a whole book, which is why the source that owns
     * the archive caches it rather than recomputing it per keystroke.
     */
    fun plainTextFor(chapter: SChapter): List<IndexedText> =
        spineIndex(chapter).mapNotNull { page ->
            documentText(chapter, page)?.let { IndexedText(page.index, plainTextOf(it)) }
        }

    /** Stripped text for one document, paired with the spine index it belongs to. */
    data class IndexedText(val index: Int, val plain: String)

    /** Reads the markup of one document. Blocking; see [getTextPageText]. */
    private fun documentText(chapter: SChapter, page: TextPage): String? =
        readerFor(chapter)?.takeIf { !it.hasDrm }?.readDocument(spineItemOf(chapter, page))

    /**
     * The spine record behind a page, for reading one document at a time.
     *
     * Resolved by [TextPage.url] first, then by index: the href is the identity that stays stable even
     * when the book has changed underneath a chapter that was left open, and the index is the fallback
     * for a page list built before the spine was re-read.
     */
    private fun spineItemOf(chapter: SChapter, page: TextPage): EpubSpineItem {
        val documents = readerFor(chapter)?.textDocuments().orEmpty()
        return documents.firstOrNull { it.href == page.url }
            ?: documents.getOrNull(page.index)
            ?: EpubSpineItem(id = page.url, href = page.url, mediaType = XHTML_MEDIA_TYPE, linear = true)
    }

    /**
     * The chapter's documents with their inline images removed, for a reader who wants the prose
     * without the plates.
     *
     * Images are *removed* rather than broken: an `<img>` that cannot load still occupies a line, so
     * stripping the element reflows the text around where it was while leaving a broken icon and a
     * gap. On a device where decoding every illustration of a long novel is the slowest part of
     * turning the page, that is the difference between usable and not.
     *
     * Applied to markup on the way out rather than in the source, because a source cannot honour it:
     * the markup has already been produced by the time a preference is known.
     */
    fun textPages(chapter: SChapter, showImages: Boolean): List<TextPage> {
        val pages = textPages(chapter)
        if (showImages) return pages
        return pages.map { it.copy(text = stripImages(it.text)) }
    }

    companion object {
        /**
         * Most spine items a chapter may expose to the reader.
         *
         * The longest real novel in one file is a few hundred. Anything past this is a declaration
         * rather than a book, and the reader can only show so many documents before the list itself
         * stops being usable.
         */
        const val MAX_DOCUMENTS = 5_000

        /**
         * Most markup characters one chapter may hold in memory at once.
         *
         * A `String` is UTF-16, so this is ~32 MB of heap. That number is the whole reason it is
         * 16 M characters and not 64: the earlier ceiling permitted a single chapter to reach ~128 MB,
         * which on a low-end device is the allocation that gets the process killed — so the truncation
         * meant to prevent an OOM arrived too late to do anything about one.
         */
        const val MAX_CHAPTER_CHARS = 16L * 1024 * 1024

        /**
         * Media type given to a spine record synthesised because the book's spine no longer describes
         * the page. Only ever a fallback; see [spineItemOf].
         */
        private const val XHTML_MEDIA_TYPE = "application/xhtml+xml"
    }
}
