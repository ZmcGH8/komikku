package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.text.TextPage
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    // KMK -->
    /**
     * True for the extra pages a too-tall image was split into.
     *
     * Such a page is one vertical slice of a source page, so it never pairs into a spread and its
     * [index] is a synthetic value rather than a position in the chapter's list.
     */
    var splitSegment: Boolean = false

    /**
     * True once this page has been replaced by the segments of its own split.
     *
     * A superseded page is no longer in its chapter's list, but a viewer may still be holding it
     * with a decoded surface attached, which is what draws the old strip over its replacement.
     * Recording the state on the page itself lets a viewer reject one without having to go back to
     * the chapter to ask whether it is still real.
     *
     * Volatile because the writer and the viewer that reads it are on different threads, and the
     * write deliberately happens *before* [eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.replacePages]
     * announces the new list - otherwise a viewer woken by that announcement could still read `false`
     * here and keep the page it was told to drop.
     */
    @Volatile
    var supersededBySplit: Boolean = false

    /**
     * The page whose split produced this one, or null when this page is not a segment.
     *
     * What lets a viewer that was showing the parent land on the first segment instead of on the
     * chapter's resume target, which after a mid-session split is a different page entirely.
     */
    var splitSourcePage: ReaderPage? = null

    /**
     * The text this page carries, when the source is a text source.
     *
     * Null for every image page, which is the overwhelming majority — the field exists so a chapter
     * can be *either* images or text without the page list, the chapter state machine, the progress
     * bar or the volume tracking needing a second parallel representation. A viewer picks its
     * renderer from this field's presence, exactly as it picks an image loader from `stream`.
     */
    var textPage: TextPage? = null
    // KMK <--
}
