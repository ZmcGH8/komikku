// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Page-processing state for [ViewerPage]s in the WebGPU viewers.
 */
enum class PageState {
    IDLE,
    QUEUED,
    LOADING,
    DECODING,
}

/**
 * Which side of a dual-page spread a [ViewerReaderPage] belongs on - app-level bookkeeping
 * for [WebGpuViewer.getSpreadAnchor]/[WebGpuViewer.buildSpreadPage], independent of the
 * decoded image itself.
 */
enum class SpreadPosition { LEFT, RIGHT, SINGLE }

// Stable key types for page identity - data classes provide correct equals/hashCode
sealed class PageKey {
    data class Reader(val chapterId: Long?, val index: Int) : PageKey()
    data class Transition(val prevId: Long?, val nextId: Long?) : PageKey()
}

/**
 * Memoizes one neighbor link (`prev`/`next`), which the pager library re-evaluates on
 * every render snapshot while scrolling. The key must cover every input the resolution
 * depends on: a chapter finishing loading, a chapter-set swap, or a transition-pref flip
 * all change it and force a recompute. An evicted cached target is detected by identity
 * and recomputed. While the neighboring chapter is still loading, the preload nudge is
 * repeated on hits so load retries keep working without a full recompute.
 */
private class NeighborLink {
    private var page: ViewerPage? = null
    private var valid = false
    private var pageCount = -2
    private var chapter: ReaderChapter? = null
    private var loaded = false
    private var transition = false

    fun get(
        viewer: WebGpuViewer,
        pageCount: Int,
        chapter: ReaderChapter?,
        loaded: Boolean,
        transition: Boolean,
        resolve: () -> ViewerPage?,
    ): ViewerPage? {
        val hit = page
        if (valid && pageCount == this.pageCount && chapter === this.chapter &&
            loaded == this.loaded && transition == this.transition &&
            (hit == null || viewer.pageInCache(hit))
        ) {
            if (!loaded) chapter?.let { viewer.preloadChapterThenRetry(it) }
            return hit
        }
        val resolved = resolve()
        page = resolved
        valid = true
        this.pageCount = pageCount
        this.chapter = chapter
        this.loaded = loaded
        this.transition = transition
        return resolved
    }
}

fun pageKey(page: ViewerPage): PageKey = when (page) {
    is ViewerReaderPage -> PageKey.Reader(page.page.chapter.chapter.id, page.page.index)
    is ViewerTransitionPage -> PageKey.Transition(page.prevChapter?.chapter?.id, page.nextChapter?.chapter?.id)
    else -> PageKey.Transition(null, null)
}

abstract class ViewerPage {
    abstract val prevChapter: ReaderChapter?
    abstract val nextChapter: ReaderChapter?
    abstract val prev: ViewerPage?
    abstract val next: ViewerPage?

    @Volatile
    var state: PageState = PageState.IDLE

    @Volatile
    open var imagePage: ImagePage = ImagePage.Dummy(400, 400)

    open val isDecoded = true
}

class ViewerTransitionPage(
    private val viewer: WebGpuViewer,
    override val prevChapter: ReaderChapter?,
    override val nextChapter: ReaderChapter?,
) : ViewerPage() {
    @Volatile
    override var imagePage: ImagePage = TransitionPage(viewer, prevChapter, nextChapter)

    private val prevLink = NeighborLink()
    private val nextLink = NeighborLink()

    override val prev: ViewerPage?
        get() {
            val prevCh = prevChapter
            val prevPages = prevCh?.pages
            // Transition links only depend on the neighboring pages list; load state and
            // the transition pref do not branch here, so constant key slots are correct.
            return prevLink.get(viewer, prevPages?.size ?: -1, prevCh, true, false) {
                prevPages?.lastOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
            }
        }

    override val next: ViewerPage?
        get() {
            val nextCh = nextChapter
            val nextPages = nextCh?.pages
            return nextLink.get(viewer, nextPages?.size ?: -1, nextCh, true, false) {
                nextPages?.firstOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
            }
        }
}

class ViewerReaderPage(
    private val viewer: WebGpuViewer,
    val page: ReaderPage,
) : ViewerPage() {
    /**
     * Cached spread ImagePage when this page is the anchor of a dual-page spread.
     *
     * Volatile, like every other mutable field here, and for the same reason: the render thread
     * builds and retires this in [buildSpreadPage], while `releasePageResources` clears it from
     * whichever thread happened to drop the shell - the device-loss handler, the stuck-page sweep
     * and `onTrimMemory` are all main-thread, while the render thread is not. Without the fence
     * the render thread reads back a spread whose textures were already released (a dead grid,
     * drawn as garbage), and two threads can each install a spread with nothing left to clean up
     * the one that lost.
     */
    @Volatile
    var spreadPage: ImagePage.ImageSpread? = null

    /**
     * What this page's uploaded textures actually occupy, 0 while it is still a placeholder.
     *
     * Volatile for the same reason as every other field here: written by the decode thread on
     * adoption, read by the render walk summing the cache against the byte budget.
     *
     * **A page count is not a memory measurement.** An ordinary page is a few MB of texture and a
     * split webtoon segment is ~47MB, so a cache of 8 shells is either 20MB or 380MB depending
     * entirely on what the chapter happens to contain. The budget needs the real number, and it
     * cannot be guessed from the page's identity.
     *
     * Carries the full mip chain, not just the base image: `1/4 + 1/16 + ...` sums to `1/3` more,
     * so the multiplier is 4/3. Undercounting by that third is what let a cache "fit" and then not.
     */
    @Volatile
    var decodedBytes: Long = 0L
        set(value) {
            field = if (value > 0L) value else 0L
        }

    private val prevLink = NeighborLink()
    private val nextLink = NeighborLink()

    /** The side the file names, or null for none. Never a value merely derived from the index. */
    @Volatile
    internal var taggedSpreadPosition: SpreadPosition? = null

    /** The decoded image's shape, or null while this page is still a placeholder. */
    internal val aspectRatio: Float?
        get() = (imagePage as? ImagePage.ImageSingle)?.let {
            val height = it.trimHeight
            if (it.isDecoded && height > 0) it.trimWidth.toFloat() / height else null
        }

    /**
     * Which half of a spread this page is on - derived until the file tags it. Without that a
     * still-loading page stays SINGLE, never pairs, and its ring draws mid-screen; deriving it
     * live also re-decides it on a rotation in or out of dual mode.
     *
     * Untagged goes by [wideAspect] first, then [derivedSpreadPosition].
     */
    internal val spreadPosition: SpreadPosition
        get() {
            taggedSpreadPosition?.let { return it }
            if (standsAlone) return SpreadPosition.SINGLE
            return viewer.derivedSpreadPosition(page)
        }

    /** True when nothing may share this page's spread - it is one already. */
    internal val standsAlone: Boolean
        get() = taggedSpreadPosition == SpreadPosition.SINGLE ||
            (aspectRatio ?: 0f) > viewer.wideAspect

    // KMK -->
    /**
     * Compressed source bytes retained for spread height-matching. Only set for
     * partner-position pages decoded in dual-page mode; freed on eviction.
     */
    @Volatile
    var spreadBytes: ByteArray? = null

    /** Guards against scheduling duplicate height-match rescales for this page */
    @Volatile
    var rescaleInFlight: Boolean = false
    // KMK <--

    /**
     * Set when the renderer actually asked for this page, which distinguishes a page that is on
     * screen (or prewarmed) from one only a speculative preload walk ever touched. Read by the
     * liveness sweep so a stalled page on screen is recovered without queueing shells that nothing
     * will ever draw.
     */
    @Volatile
    var wantedByRender: Boolean = false

    // KMK -->
    /**
     * Pre-translation page retained for the compare toggle. Set once on the
     * first translation swap; the translated image replaces [imagePage] (or is
     * parked in [compareTranslated]) so peeking at the original never re-decodes.
     */
    @Volatile
    var compareOriginal: ImagePage? = null

    /** Translated page parked while the compare toggle shows the original. */
    @Volatile
    var compareTranslated: ImagePage.ImageSingle? = null

    /** True once a translation has been swapped in (compare state valid). */
    @Volatile
    var hasTranslation: Boolean = false

    @Volatile
    var translationGeneration: Long = 0L

    fun cleanupCompare() {
        translationGeneration++
        try {
            compareTranslated?.let { if (it !== imagePage) it.cleanup() }
        } catch (_: Exception) {
        }
        compareTranslated = null
        try {
            compareOriginal?.let { if (it !== imagePage) it.cleanup() }
        } catch (_: Exception) {
        }
        compareOriginal = null
        hasTranslation = false
    }
    // KMK <--

    @Volatile
    override var imagePage: ImagePage = ProgressPage(viewer)

    override val isDecoded
        get() = (imagePage as? ImagePage.ImageSingle)?.isDecoded == true

    override val prevChapter: ReaderChapter?
        get() = when (page.chapter) {
            viewer.viewerChapters?.currChapter -> viewer.viewerChapters?.prevChapter
            viewer.viewerChapters?.nextChapter -> viewer.viewerChapters?.currChapter
            else -> null
        }

    override val nextChapter: ReaderChapter?
        get() = when (page.chapter) {
            viewer.viewerChapters?.currChapter -> viewer.viewerChapters?.nextChapter
            viewer.viewerChapters?.prevChapter -> viewer.viewerChapters?.currChapter
            else -> null
        }

    /**
     * The neighbour links have to be answered from a page that is still in its chapter's list.
     *
     * A page a split removed answers [ReaderChapter.positionOf] with -1, and every "one before the
     * first / one after the last" test below reads that as a chapter edge - so the walk jumped into
     * the neighbouring chapter, laying its pages into the strip where the segments belonged. The
     * chain from the replacement's side is the same chain the parent used to be part of, so
     * delegating keeps the ordering intact instead of inventing a boundary that is not there.
     *
     * Resolved outside the [NeighborLink] memo on purpose: the memo keys on the page count, which a
     * split does change, but only by the time the replacement is asked for - and a stale hit here
     * would reinstate exactly the neighbour that caused the duplication.
     */
    private fun linkedNeighbour(step: Int): ViewerPage? {
        if (page.chapter.positionOf(page) < 0) {
            val replacement = page.chapter.splitReplacementOf(page) ?: return null
            return viewer.getPage(replacement, viewer.currentPage).let { replacementPage ->
                if (step > 0) replacementPage.next else replacementPage.prev
            }
        }
        return null
    }

    override val prev: ViewerPage?
        get() {
            linkedNeighbour(-1)?.let { return it }
            val chapterPages = page.chapter.pages
            val prevCh = prevChapter
            return prevLink.get(
                viewer,
                pageCount = chapterPages?.size ?: -1,
                chapter = prevCh,
                loaded = prevCh?.state is ReaderChapter.State.Loaded,
                transition = viewer.config.alwaysShowChapterTransition,
            ) {
                chapterPages?.let { pages ->
                    pages.getOrNull(page.chapter.positionOf(page) - 1)?.let { viewer.getPage(it, viewer.currentPage) } ?: run {
                        if (prevCh == null) return@run viewer.getPage(null, page.chapter, viewer.currentPage)

                        if (prevCh.state !is ReaderChapter.State.Loaded) {
                            viewer.preloadChapterThenRetry(prevCh)
                        }

                        if (viewer.config.alwaysShowChapterTransition) {
                            viewer.getPage(prevCh, page.chapter, viewer.currentPage)
                        } else {
                            prevCh.pages?.lastOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
                        }
                    }
                }
            }
        }

    override val next: ViewerPage?
        get() {
            linkedNeighbour(1)?.let { return it }
            val chapterPages = page.chapter.pages
            val nextCh = nextChapter
            return nextLink.get(
                viewer,
                pageCount = chapterPages?.size ?: -1,
                chapter = nextCh,
                loaded = nextCh?.state is ReaderChapter.State.Loaded,
                transition = viewer.config.alwaysShowChapterTransition,
            ) {
                chapterPages?.let { pages ->
                    pages.getOrNull(page.chapter.positionOf(page) + 1)?.let { viewer.getPage(it, viewer.currentPage) } ?: run {
                        if (nextCh == null) return@run viewer.getPage(page.chapter, null, viewer.currentPage)

                        if (nextCh.state !is ReaderChapter.State.Loaded) {
                            viewer.preloadChapterThenRetry(nextCh)
                        }

                        if (viewer.config.alwaysShowChapterTransition) {
                            viewer.getPage(page.chapter, nextCh, viewer.currentPage)
                        } else {
                            nextCh.pages?.firstOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
                        }
                    }
                }
            }
        }
}
// Mihon <--
