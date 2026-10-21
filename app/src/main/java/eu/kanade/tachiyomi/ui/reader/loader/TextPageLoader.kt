package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.EpubArchiveSource
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.app.di.globalAppGraph
import mihon.core.archive.stripImages
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Loader for chapters served as text by a [TextSource] — EPUBs and web novels.
 *
 * ### Why text needs no loading state
 *
 * [PageLoader] exists because images arrive slowly, partially, and sometimes not at all: a status
 * machine, a progress flow and a retry path are all loaders for a network fetch. Text arrives with
 * the chapter list. So every page is born [Page.State.Ready], [loadPage] has nothing to report, and
 * [retryPage] is a no-op — the failure mode for text is "the whole chapter failed", which is an
 * exception from [getPages], not a per-page error state.
 *
 * Pages are marked [ReaderPage.textPage] rather than streamed, so the renderer can lay out and, more
 * importantly, measure the document without going back to the source.
 *
 * ### Why the documents are not read up front
 *
 * [TextSource.getTextPageList] returns a whole chapter, and for an EPUB a chapter is the whole book:
 * opening a five-hundred-document novel inflated and decoded every one of them before the reader
 * could see the first screen, then held them all in memory to display two.
 *
 * A source that also implements [TextIncrementalSource] is asked for the *list* instead, which costs
 * nothing for an EPUB because the spine is already parsed, and each document's markup is read only
 * when the reader actually reaches it — [ensureText]. The eager path stays as the fallback, so an
 * extension that implements only [TextSource] behaves exactly as before.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
internal class TextPageLoader(
    private val source: TextSource,
    private val chapter: Chapter,
) : PageLoader() {

    /**
     * Whether the reader wants a book's inline illustrations drawn.
     *
     * Read through the global graph rather than injected: the loader is constructed by the plugin
     * chain, which has no injector, and this is the same preference store the reader's own typography
     * lives in.
     */
    private val showImages: Preference<Boolean>
        get() = globalAppGraph.novelTypographyPreferences.showImages

    /** True when the source can serve a chapter one document at a time. See [TextIncrementalSource]. */
    private val incremental: TextIncrementalSource? = source as? TextIncrementalSource

    /** The chapter's documents, as the source produced them, kept for length-based progress. */
    private var loadedPages: List<TextPage> = emptyList()

    /**
     * Markup length per document, filled in as documents are read.
     *
     * A sparse array rather than the text itself: progress is weighted by how much text a document
     * holds, and on the incremental path that is not knowable until the document has been read. Kept
     * as lengths so the markup itself is never held twice.
     */
    private val documentLengths = mutableListOf<Int>()

    /** True when the source is local and therefore needs no network for a reload. */
    override var isLocal: Boolean = false

    /** Marks the chapter as text so the viewer picks a text renderer over an image one. */
    val isText: Boolean get() = true

    /**
     * Serves the chapter's images, stylesheets and fonts, when the source can.
     *
     * Handed to the viewer so the archive stays open for as long as the chapter is on screen.
     * Null for a source that inlines everything or serves pages over plain HTTP.
     */
    val resourceSource: TextResourceSource? get() = source as? TextResourceSource

    /**
     * The book's table of contents, when the source publishes one.
     *
     * Narrow on purpose. The reader asks this to decide whether to offer a contents button at all, so
     * the answer it needs is "can you", not "here is an unbounded interface".
     */
    val tocSource: TextTocSource? get() = source as? TextTocSource

    /** In-chapter search, when the source supports it. See [tocSource]. */
    val searchSource: TextSearchSource? get() = source as? TextSearchSource

    /**
     * This chapter in the form the source API speaks.
     *
     * Cached rather than rebuilt: the reader's contents and search surfaces both ask for it, on top of
     * the page list itself, and the hop is not free. Null only for a chapter with no database row,
     * which [getPages] treats as fatal.
     */
    val sChapter: SChapter? by lazy {
        // toSChapter() is defined on the *domain* Chapter, while the loader is handed the legacy
        // database one, so the adapter hop is required - it is the same hop DownloadPageLoader does.
        chapter.toDomainChapter()?.toSChapter()
    }

    /**
     * The chapter's pages.
     *
     * Two shapes, decided by the source rather than by the host: a document list with no text when the
     * source is [TextIncrementalSource], and a fully-read list otherwise. The difference is invisible
     * to callers that go through [ensureText] — which is every renderer path — and only observable to
     * a caller that reads [ReaderPage.textPage] directly, which none does.
     *
     * The image toggle is applied where the markup is produced rather than here: a CSS rule that hid
     * the images would still leave every plate fetched and decoded, which is the cost the setting
     * exists to avoid. On the eager path that is immediately; on the incremental path it is in
     * [ensureText], per document.
     */
    override suspend fun getPages(): List<ReaderPage> = withIOContext {
        val sChapter = sChapter
            ?: throw IllegalStateException("Chapter ${chapter.url} is missing its database id")
        val incremental = this@TextPageLoader.incremental
        val pages = if (incremental != null) {
            incremental.getTextPageIndex(sChapter)
        } else {
            source.getTextPageList(sChapter).let { list ->
                if (showImages.get()) list else list.map { it.copy(text = stripImages(it.text)) }
            }
        }
        loadedPages = pages
        // Seeded with the lengths already known, which on the eager path is all of them and on the
        // incremental path is none. Zero means "not read yet", which NovelProgress already treats as
        // unknown rather than as an empty document.
        documentLengths.clear()
        documentLengths.addAll(pages.map { it.text.length })

        pages.mapIndexed { index, textPage ->
            ReaderPage(index, url = textPage.url).apply {
                this.textPage = textPage
                status = Page.State.Ready
            }
        }
    }

    /**
     * Character length of each document, for
     * [eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelProgress].
     *
     * Measured on the markup rather than the rendered text because the markup is what the loader
     * actually holds, and the ratio between documents is what weighting needs — an approximation
     * that is consistent within a chapter is enough.
     *
     * A snapshot rather than the live list: it is read on every position write, and the viewer's copy
     * should not change underneath it mid-save.
     */
    fun documentLengths(): List<Int> = documentLengths.toList()

    /**
     * Makes sure [page]'s markup is in hand, and reports whether it is.
     *
     * The renderer calls this immediately before showing a document. On the eager path it has
     * nothing to do and answers true. On the incremental path it reads the one document asked for.
     *
     * False means the document could not be read at all — a corrupt or missing file — and the
     * renderer shows it empty rather than refusing the chapter, which is what the eager path did by
     * leaving a hole in the page list. One document failing must not cost a reader the other four
     * hundred.
     */
    suspend fun ensureText(page: ReaderPage): Boolean = withIOContext {
        val textPage = page.textPage ?: return@withIOContext false
        if (textPage.text.isNotEmpty()) return@withIOContext true

        val sChapter = sChapter ?: return@withIOContext false
        val incremental = this@TextPageLoader.incremental ?: return@withIOContext true
        val markup = incremental.getTextPageText(sChapter, textPage) ?: return@withIOContext false

        val ready = textPage.copy(text = if (showImages.get()) markup else stripImages(markup))
        page.textPage = ready
        if (page.index < documentLengths.size) documentLengths[page.index] = ready.text.length
        true
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        // Deliberately does not report the outcome: PageLoader's contract has nowhere to put it, and a
        // text document has no partial state to report. ensureText is the API that does.
        ensureText(page)
    }

    /**
     * Releases everything this chapter was holding.
     *
     * The archive lease goes first, and it is the reason this override exists at all. A source that
     * maps books keeps a memory map and a file descriptor per open one; before this call nothing in
     * the system ever gave it back, because the loader is the only object whose lifetime *is* the
     * chapter's — the viewer is rebuilt on an orientation change with the chapter still open, and the
     * source is asked for pages rather than told they are no longer wanted. A session that read fifty
     * books held fifty maps until the process died.
     *
     * Safe to call while the renderer is still streaming out of the archive: the release marks the
     * book unreachable and the unmap waits for the last open stream to close. See
     * `EpubResourceServer`.
     */
    override fun recycle() {
        super.recycle()
        sChapter?.url?.let { url ->
            // The manga library's books are held by the source too, not only the dedicated EPUB
            // library's. Narrow on the optional interface rather than on a concrete class: the loader
            // holds a TextSource because that is all the extension API promises, and casting to
            // `LocalSource` would make the built-in library the only text chapter this reader can
            // open — the exact mistake the `TextBaseUrlSource` rule exists to prevent.
            (source as? EpubArchiveSource)?.releaseResourceReader(url)
        }
        // The markup goes here. On the eager path every page still holds its own TextPage, so this
        // stops the loader being a second, redundant reference to the whole chapter's text; on the
        // incremental path it is most of what the loader was holding.
        loadedPages = emptyList()
        documentLengths.clear()
    }
}
