// KMK -->
package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A text source that can hand out a chapter's documents **without** reading them.
 *
 * ### Why this exists
 *
 * [TextSource.getTextPageList] returns every document of a chapter at once, and for an EPUB that is
 * every spine item of the whole book. Reading them all is what made opening a long novel slow: the
 * reader inflated and decoded five hundred files before it could show the first screen, held them
 * all in memory for as long as the chapter was open, and then threw most of them away — the reader
 * had scrolled through two.
 *
 * The cost is entirely in the *text*, not in the list. A spine is a few hundred small records
 * already parsed by the time anything asks for it, so a host can know the shape of a book, jump to
 * its third document, and read only that one. This interface is that split.
 *
 * ### How a host uses it
 *
 * 1. [getTextPageIndex] for the page list, as pages whose `text` is empty.
 * 2. [getTextPageText] for a document, immediately before it is shown.
 *
 * A host must therefore treat an empty `text` as "not read yet", never as "an empty document". The
 * two are indistinguishable from the list alone, which is why the host asks for the text explicitly
 * rather than deciding for itself.
 *
 * ### Optional by construction
 *
 * A separate interface, never a member of [TextSource]. Extensions implement the text API across a
 * classloader boundary, so a new abstract method would break every extension that predates it — the
 * rule [TextBaseUrlSource] already follows, for the same reason. A host falls back to
 * [TextSource.getTextPageList] when this is absent, and behaviour is then exactly what it was.
 *
 * ### What a source must guarantee
 *
 * - **[getTextPageIndex] returns a stable, complete, in-order list.** It must not depend on a
 *   document having been read, because that is the whole point: the host decides what to read from
 *   this list and must be able to trust it before reading anything.
 * - **[TextPage.index] and [TextPage.url] must match what the eager path would have produced**, and
 *   they must match what [TextSearchSource] reports in [TextSearchHit.pageIndex]. A host locates a
 *   search hit by `textPage.index`, so the two enumerations have to be one enumeration.
 * - **[getTextPageText] returns the document named by the [TextPage] it is given**, or null when
 *   that document cannot be read. Null is per-document and recoverable: a host shows the document
 *   empty and moves on, exactly as the eager path did by leaving a hole.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextIncrementalSource {

    /**
     * The chapter's documents in reading order, with their text **not** read.
     *
     * Each page carries everything except [TextPage.text]: its index, its url, its base href and its
     * title, which is everything a host needs to show a page list, a contents list or a position
     * before deciding which document to open.
     *
     * Suspend because it is not free: for an EPUB it opens the archive and parses the package
     * document. Cheap next to reading the documents themselves, and not something to do on a main
     * thread.
     */
    suspend fun getTextPageIndex(chapter: SChapter): List<TextPage>

    /**
     * The markup of the document [page] names, or null when it cannot be read.
     *
     * Takes the [TextPage] rather than an index because the spine index is sparse in the general
     * case — a document that failed to read leaves a hole — so it is an identity, not an offset.
     */
    suspend fun getTextPageText(chapter: SChapter, page: TextPage): String?
}
// KMK <--
