package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext

/**
 * RecyclerView Adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class WebtoonAdapter(
    val viewer: WebtoonViewer,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * List of currently set items.
     */
    var items: List<Any> = emptyList()
        private set

    var currentChapter: ReaderChapter? = null

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        // Latched up front for the same reason the pager's adapter does it: setChapters below can
        // re-enter through the viewer, and a version read afterwards would let that nested call
        // rebuild against a baseline that is not in place yet.
        lastPageListVersion = chapters.currChapter.pageListVersion
        lastChapters = chapters

        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition.
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            newItems.addAll(currPages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        if (nextHasMissingChapters || forceTransition || chapters.nextChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
        }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        updateItems(newItems)
    }

    private fun updateItems(newItems: List<Any>) {
        val result = DiffUtil.calculateDiff(Callback(items, newItems))
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    /**
     * Rebuilds the item list if the chapter's page list was replaced since we last read it.
     *
     * The loader swaps in a taller page's segments after this adapter has already snapshotted the
     * chapter's pages, and it does so by removing the page that was on screen. Nothing else notices:
     * without this the item list stays the length it was, so the strip renders as its first slice
     * and the pages the split pushed out of reach cannot be scrolled to.
     *
     * Returns true when the list changed, so the caller can re-resolve where it was.
     */
    fun syncPageList(chapter: ReaderChapter): Boolean {
        val version = chapter.pageListVersion
        if (version == lastPageListVersion) return false
        // Re-set with the chapters as they were last given; rebuilding a ViewerChapters from the
        // current chapter alone would drop the adjacent chapters and the transitions between them.
        val chapters = lastChapters ?: return false
        setChapters(chapters, forceTransition = viewer.config.alwaysShowChapterTransition)
        return true
    }

    /** [ReaderChapter.pageListVersion] the item list was last built from, or -1 before the first set. */
    val currentPageListVersion: Int get() = lastPageListVersion

    private var lastPageListVersion = -1

    private var lastChapters: ViewerChapters? = null

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getItemCount(): Int {
        return items.size
    }

    /**
     * Returns the view type for the item at the given [position].
     */
    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ReaderPage -> PAGE_VIEW
            is ChapterTransition -> TRANSITION_VIEW
            else -> error("Unknown view type for ${item.javaClass}")
        }
    }

    /**
     * Creates a new view holder for an item with the given [viewType].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            PAGE_VIEW -> {
                val view = ReaderPageImageView(readerThemedContext, isWebtoon = true)
                WebtoonPageHolder(
                    view,
                    viewer,
                    // KMK -->
                    seedColor = seedColor,
                    // KMK <--
                )
            }
            TRANSITION_VIEW -> {
                val view = LinearLayout(readerThemedContext)
                WebtoonTransitionHolder(
                    view,
                    viewer,
                    // KMK -->
                    seedColor = seedColor,
                    // KMK <--
                )
            }
            else -> error("Unknown view type")
        }
    }

    /**
     * Binds an existing view [holder] with the item at the given [position].
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is WebtoonPageHolder -> holder.bind(item as ReaderPage)
            is WebtoonTransitionHolder -> holder.bind(item as ChapterTransition)
        }
    }

    /**
     * Recycles an existing view [holder] before adding it to the view pool.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is WebtoonPageHolder -> holder.recycle()
            is WebtoonTransitionHolder -> holder.recycle()
        }
    }

    /**
     * Diff util callback used to dispatch delta updates instead of full dataset changes.
     */
    private class Callback(
        private val oldItems: List<Any>,
        private val newItems: List<Any>,
    ) : DiffUtil.Callback() {

        /**
         * Returns true if these two items are the same.
         */
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            val newItem = newItems[newItemPosition]

            return oldItem == newItem
        }

        /**
         * Returns true if the contents of the items are the same.
         */
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return true
        }

        /**
         * Returns the size of the old list.
         */
        override fun getOldListSize(): Int {
            return oldItems.size
        }

        /**
         * Returns the size of the new list.
         */
        override fun getNewListSize(): Int {
            return newItems.size
        }
    }
}

/**
 * View holder type of a chapter page view.
 */
private const val PAGE_VIEW = 0

/**
 * View holder type of a chapter transition view.
 */
private const val TRANSITION_VIEW = 1
