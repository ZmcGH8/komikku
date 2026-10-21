package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.system.logcat
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identity indexes over one page list, plus the list they were built from.
 *
 * The list reference is what makes it safe: [ReaderChapter.positionOf] only trusts the indices when
 * they came from the exact list instance it just read, so a reader racing [ReaderChapter.replacePages]
 * either sees the previous pair (and rebuilds) or the new one - never new indices against an old list.
 *
 * Both maps are built in one pass and published together, so the two answers can never describe
 * different lists.
 */
private class PositionIndex(
    val pages: List<ReaderPage>,
    val positions: IdentityHashMap<ReaderPage, Int>,
    /** Superseded page -> the first segment that took its place. See [ReaderChapter.splitReplacementOf]. */
    val replacements: IdentityHashMap<ReaderPage, ReaderPage>,
)

/**
 * Single pass that fills both identity maps, first-seen wins for a replacement.
 *
 * A run of segments shares one [ReaderPage.splitSourcePage], so the first segment of each run is
 * the one the replacement has to land on - which is why `putIfAbsent` (first wins) and not
 * `put` (last wins). Pure and side-effect free apart from its arguments, so the rule is testable
 * without a chapter.
 */
internal fun buildPageIndexes(
    pages: List<ReaderPage>,
    positions: IdentityHashMap<ReaderPage, Int>,
    replacements: IdentityHashMap<ReaderPage, ReaderPage>,
) {
    pages.forEachIndexed { index, page ->
        positions[page] = index
        val source = page.splitSourcePage
        if (source != null && !replacements.containsKey(source)) {
            replacements[source] = page
        }
    }
}

/** Initial capacities for the two identity maps of a list of [size] pages. */
internal fun pageIndexCapacity(size: Int): Int = size.coerceAtLeast(1) * 2

data class ReaderChapter(val chapter: Chapter) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    /**
     * Bumped every time the loaded page list is replaced wholesale.
     *
     * The loader grows this list after the viewers have already built theirs: a page too tall for
     * the decoder is cut into segments and the result swapped in mid-session, which is long after
     * `setChapters` snapshotted it. Both viewers enumerate pages by walking that snapshot, so
     * without a signal they never learn the list changed - the split is simply invisible, and the
     * pages the viewer is still holding for the removed parent stay laid out next to the segments
     * that replaced it.
     *
     * Identity rather than a hash of the list, so it is a cheap read on the render path and cannot
     * collide. Callers compare it against the value they last built from.
     */
    val pageListVersion: Int get() = pageListVersionCounter.get()

    /**
     * Atomic rather than a plain counter, because the writers are not guaranteed to be one thread.
     * A split runs on a loader worker while a fresh
     * [eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader] load runs on its own, so two of them can
     * touch the same chapter - and a non-atomic `++` on a volatile int loses one of the two
     * increments, leaving a viewer to reconcile against a version it has already seen and never
     * notice the list it missed.
     */
    private val pageListVersionCounter = AtomicInteger()

    /**
     * Replaces the loaded page list and announces it. Every writer that changes the list's contents
     * or length must go through here, or the viewers will keep rendering the previous one.
     */
    fun replacePages(newPages: List<ReaderPage>) {
        // KMK --> Index first, state second. Publishing the list before its index left a window
        // in which every reader that arrived had to rebuild the whole IdentityHashMap itself on
        // the render thread - the exact cost building it here exists to avoid - and the reader that
        // lost the race stored its index for the *old* list, so the next one rebuilt again.
        val positions = IdentityHashMap<ReaderPage, Int>(pageIndexCapacity(newPages.size))
        val replacements = IdentityHashMap<ReaderPage, ReaderPage>(pageIndexCapacity(newPages.size))
        buildPageIndexes(newPages, positions, replacements)
        positionIndex = PositionIndex(newPages, positions, replacements)
        state = State.Loaded(newPages)
        pageListVersionCounter.incrementAndGet()
    }

    @Volatile
    private var positionIndex: PositionIndex? = null

    /**
     * Indexes for [list], rebuilt whenever the cached pair was built from a different list - so
     * [positionOf] and [splitReplacementOf] can never answer from two different lists.
     */
    private fun indexesFor(list: List<ReaderPage>?): PositionIndex? {
        if (list == null) return null
        val cached = positionIndex
        if (cached != null && cached.pages === list) return cached
        val positions = IdentityHashMap<ReaderPage, Int>(pageIndexCapacity(list.size))
        val replacements = IdentityHashMap<ReaderPage, ReaderPage>(pageIndexCapacity(list.size))
        buildPageIndexes(list, positions, replacements)
        return PositionIndex(list, positions, replacements).also { positionIndex = it }
    }

    /**
     * Position of [page] in the current page list, or -1 when it is not in it.
     *
     * [Page.index] cannot answer this: it is assigned when the list is built and is immutable,
     * so a page inserted later (a split segment) sits at a position its index does not name. Match
     * by identity, since two distinct pages can legitimately share an index across a chapter swap.
     *
     * Backed by an [IdentityHashMap] rather than a linear scan: the continuous viewer resolves this
     * for every page either side of the anchor on every rendered frame, so a 500-page webtoon
     * chapter was paying 500 identity comparisons per lookup, times dozens of lookups per frame.
     */
    fun positionOf(page: ReaderPage): Int = indexesFor(pages)?.positions?.get(page) ?: -1

    /**
     * The page that now stands where [page] used to, or null when [page] is still in the list.
     *
     * A split removes the page it cuts and inserts its segments in its place, so a viewer that was
     * showing [page] has to land on the first of them. Falling back to the chapter's resume target
     * instead would teleport the reader to wherever the chapter was opened, which for a page they
     * had already turned to is somewhere else entirely.
     *
     * Identity, like [positionOf]: the segments record the page they came from, so two pages sharing
     * one image cannot hand each other's replacement over.
     *
     * KMK --> Served from the same identity index [positionOf] uses, built alongside it by
     * [replacePages]. This is on the viewer's `prev`/`next` path - every page either side of the
     * anchor, every rendered frame - and a linear scan here meant a 500-page webtoon chapter paid
     * 500 identity comparisons per chain step, with the shell of a removed parent re-asking for it
     * on every pass.
     */
    fun splitReplacementOf(page: ReaderPage): ReaderPage? = indexesFor(pages)?.replacements?.get(page)

    /**
     * Position to store for [page], in the coordinates the page list has before any split.
     *
     * [positionOf] counts positions in the list as it stands, which is what the reader pages through
     * and what the progress bar has to show. It is not what to *persist*: a split inserts pages, so
     * every position after one shifts by however many segments came before it, and the list that
     * survives a close is the un-split one the loader restores. A stored position therefore names a
     * different page on the next open than it did on the way out.
     *
     * Every segment of one strip maps to the same stored position, which is the point - reading any
     * slice of a long image has to resume the image, not some later page. Unlisted pages fall back to
     * [Page.index], the coordinate they had before anything could shift them.
     */
    fun savedIndexOf(page: ReaderPage): Int {
        val list = pages ?: return page.index
        val position = positionOf(page)
        if (position < 0) return page.index
        val run = page.segmentParentIndex
        var saved = 0
        var index = 0
        while (index < position) {
            val parent = list[index].segmentParentIndex
            if (parent == null) {
                saved++
                index++
                continue
            }
            // Stops on the run [page] belongs to, before counting it: a page inside a run answers
            // with the slot that run occupies, not with the slot after it. Every other run is
            // skipped whole and costs one position, matching how it restores.
            if (run != null && parent == run) break
            saved++
            while (index < position && list[index].segmentParentIndex == parent) {
                index++
            }
        }
        return saved
    }

    /**
     * 1-based number to show the reader for [page].
     *
     * [Page.number] is derived from the immutable [Page.index], so it cannot report a position for
     * a page inserted after the list was built. Falls back to the index when [page] is unlisted,
     * which is what every page looked like before this existed - except that a segment's own index
     * is a slot in the segment range rather than a page number, so the parent it was cut from is
     * what names the page the reader is on.
     */
    fun displayNumber(page: ReaderPage): Int {
        val position = positionOf(page)
        return if (position >= 0) {
            position + 1
        } else {
            (parentIndexOfSegment(page.index) ?: page.index) + 1
        }
    }

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.name}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}
