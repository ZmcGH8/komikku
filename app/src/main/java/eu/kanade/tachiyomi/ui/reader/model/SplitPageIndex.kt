package eu.kanade.tachiyomi.ui.reader.model

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// How a page cut into segments is numbered, and how to read that number back.
//
// A page too tall for the decoder is replaced by SEGMENT_INDEX_BASE-range ReaderPage.index values,
// so the scheme has to be readable from everywhere that maps between "where the reader is" and
// "where the chapter says that page is" - the loader writes it, and the chapter counts around it.
// Living here rather than in the loader is what keeps the two from drifting apart.

/**
 * First index handed to an injected split segment.
 *
 * Well clear of any real page count, so a segment index can never collide with the index of a page
 * the source listed.
 */
internal const val SEGMENT_INDEX_BASE = 1 shl 28

/**
 * Slots reserved per source page inside the segment index range.
 *
 * Derived from the page's own index rather than from the chapter's current length, so the same
 * slice of the same image always gets the same index. Deriving it from the length meant a second
 * split pass - which sees a longer list - produced a different index for the same slice, leaving
 * the previous copy live in every index-keyed page cache alongside the new one.
 *
 * The stride has to exceed the number of segments one page can produce, or two source pages
 * share index space and the readers - which key their page cache on `PageKey.Reader(chapterId,
 * index)` - hand back the wrong page. [tachiyomi.core.common.util.system.TallPageSplitter]
 * bisects, so the count is a power of two bounded by `2 * ceil(height / maxSegmentHeight)`:
 * at an 8192px segment limit that exceeds 16 for any strip over ~64k px, which long webtoon
 * chapters do reach. 4096 covers strips up to ~33M px, and still leaves room for ~450k source
 * pages per chapter inside the 27 bits [SEGMENT_INDEX_BASE] leaves above it.
 */
internal const val SEGMENT_INDEX_STRIDE = 1L shl 12

/**
 * Index for segment [number] of [source]'s image.
 *
 * [ReaderPage] takes its index at construction and cannot be renumbered afterwards, so a segment
 * inserted mid-chapter cannot take the position it now sits at without colliding with the page
 * that already owns that index. Segment indices are therefore drawn from a range no real page
 * reaches, which keeps them unique - all the readers key their page cache on
 * `PageKey.Reader(chapterId, index)` and would hand back the wrong page on a collision.
 */
internal fun segmentIndex(source: ReaderPage, number: Int): Int {
    if (number >= SEGMENT_INDEX_STRIDE) {
        // Unreachable in practice, but a silent wrap here would alias two segments of the
        // same page onto one cache key, so it is worth saying out loud. Called on `source` rather
        // than bare: the wrapper is an extension, and a top-level function has nothing to dispatch
        // it on.
        source.logcat(LogPriority.ERROR) {
            "Page ${source.index} produced more than $SEGMENT_INDEX_STRIDE segments; " +
                "segment $number collides with another page's index range"
        }
    }
    // SEGMENT_INDEX_STRIDE is a Long, so the sum widens; it stays inside Int for any
    // real chapter (< 2^27 / 2^12 pages) because the base leaves 27 bits of headroom.
    return (
        SEGMENT_INDEX_BASE +
            source.index.toLong().coerceAtLeast(0) * SEGMENT_INDEX_STRIDE +
            number.coerceIn(0, SEGMENT_INDEX_STRIDE.toInt() - 1)
        ).toInt()
}

/**
 * Index of the page that a segment carrying [index] was cut from, or null when [index] is not a
 * segment index at all.
 *
 * The exact inverse of [segmentIndex] for every number within the stride, which is what lets a run
 * of segments be folded back into the one page it replaced - both when persisting the page list and
 * when converting a position for storage.
 */
internal fun parentIndexOfSegment(index: Int): Int? =
    if (index < SEGMENT_INDEX_BASE) {
        null
    } else {
        ((index - SEGMENT_INDEX_BASE) / SEGMENT_INDEX_STRIDE).toInt()
    }

/** Whether [this] page is a segment, and if so the index of the page its split replaced. */
internal val ReaderPage.segmentParentIndex: Int?
    get() = if (splitSegment) parentIndexOfSegment(index) else null

/**
 * Which slice of its source image the segment carrying [index] is, read back from the index alone.
 *
 * The `#segment-N` suffix on a segment's `imageUrl` normally records this, but the URL is
 * re-resolved from the source on some retry paths and the suffix is not always preserved. Falling
 * back to the index keeps the slice identity recoverable when it is not, rather than silently
 * defaulting every segment to slice 0 and drawing one slice of a strip as the whole strip.
 *
 * The other half of the inverse of [segmentIndex]: the stride is what makes the remainder the
 * number, so this only agrees with [segmentIndex] for numbers within the stride.
 */
internal fun segmentNumberOfIndex(index: Int): Int =
    if (index < SEGMENT_INDEX_BASE) {
        0
    } else {
        (index - SEGMENT_INDEX_BASE) % SEGMENT_INDEX_STRIDE.toInt()
    }
