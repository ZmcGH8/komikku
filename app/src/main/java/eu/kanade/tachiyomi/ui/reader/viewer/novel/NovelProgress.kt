package eu.kanade.tachiyomi.ui.reader.viewer.novel

import kotlin.math.roundToInt

/**
 * Reading progress for text, as a percentage.
 *
 * ### Why not a page index
 *
 * Text has no stable page count. The same chapter is 6 screens at 12pt on a phone and 3 on a tablet,
 * 9 again once the user turns on hyphenation and justification, and completely different in
 * vertical writing mode. Persisting "page 4 of 9" therefore restores to a different sentence on
 * every device, and a percentage has the same defect — 40% of a chapter lands somewhere else at a
 * different font size.
 *
 * ### What is stable
 *
 * The **document**, and the fraction scrolled within it. Both are independent of font size, screen
 * size and orientation, so they survive every one of those changes and restore to the same
 * paragraph. That is what this computes.
 *
 * Within a document, the fraction is derived from a text offset rather than a pixel scroll, for the
 * same reason: pixel offsets move when the font size changes, character offsets do not.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
object NovelProgress {

    /**
     * A position in a chapter.
     *
     * @property documentIndex which document (spine item / chapter) the position is in.
     * @property characterOffset how far through that document, in characters of its plain text.
     *   0 means the start; a value at or beyond the document's length means its end.
     * @property characterLength length of that document's plain text.
     */
    data class Position(
        val documentIndex: Int,
        val characterOffset: Int,
        val characterLength: Int,
    ) {
        /** Fraction of this document, clamped to 0..1. */
        val fractionInDocument: Float
            get() {
                if (characterLength <= 0) return 0f
                return (characterOffset.toFloat() / characterLength).coerceIn(0f, 1f)
            }

        /** Stable string for persistence. */
        fun encode(): String = "$documentIndex:$characterOffset:$characterLength"

        companion object {
            /** Parses [encode] output, returning null on anything malformed rather than throwing. */
            fun decode(raw: String?): Position? {
                if (raw.isNullOrBlank()) return null
                val parts = raw.split(':')
                if (parts.size != 3) return null
                val index = parts[0].toIntOrNull() ?: return null
                val offset = parts[1].toIntOrNull() ?: return null
                val length = parts[2].toIntOrNull() ?: return null
                if (index < 0 || offset < 0 || length < 0) return null
                return Position(index, offset, length)
            }
        }
    }

    /**
     * Progress through one chapter, as a whole percentage.
     *
     * Documents are weighted by **character length**, not counted equally. Without that, a book
     * whose spine is 200 one-paragraph interstitial pages plus three long chapters reads as 2%
     * done after a whole chapter — the bar would barely move for hours.
     */
    fun chapterPercent(position: Position, chapterDocumentLengths: List<Int>): Int {
        val total = chapterDocumentLengths.sum()
        if (total <= 0) return 0

        val before = chapterDocumentLengths
            .take(position.documentIndex.coerceIn(0, chapterDocumentLengths.size))
            .sum()
        val current = chapterDocumentLengths
            .getOrNull(position.documentIndex)
            ?.let { (it * position.fractionInDocument).toInt() }
            ?: 0

        return percentOf(before + current, total)
    }

    /**
     * Progress through the whole book, as a whole percentage.
     *
     * The within-chapter term is scaled by [Position.characterLength] rather than by this chapter's
     * entry in [chapterLengths]. The two are measured differently — one from the document the
     * reader is actually in, the other from whatever the library recorded at scan time — so mixing
     * them skews the result whenever they disagree, which is exactly when a book has been reflowed
     * or re-imported. [chapterLengths] only supplies the "everything before this chapter" total.
     *
     * @param chapterIndex which chapter of the book this is.
     * @param chapterLengths character length of every chapter, in reading order.
     * @param position position within [chapterIndex].
     */
    fun bookPercent(
        chapterIndex: Int,
        chapterLengths: List<Int>,
        position: Position,
    ): Int {
        val total = chapterLengths.sum()
        if (total <= 0) return 0

        val safeChapter = chapterIndex.coerceIn(0, chapterLengths.size)
        val before = chapterLengths.take(safeChapter).sum()
        val within = (position.characterLength * position.fractionInDocument).toInt()

        return percentOf(before + within, total)
    }

    /**
     * Characters scrolled past, for a given document fraction.
     *
     * The inverse of [Position.fractionInDocument], used to turn a scroll position back into the
     * stable offset that gets persisted.
     */
    fun characterOffsetFor(fraction: Float, length: Int): Int =
        (fraction.coerceIn(0f, 1f) * length).roundToInt().coerceIn(0, length.coerceAtLeast(0))

    /**
     * Fraction through a document for a target character offset.
     *
     * Used to restore: the stored offset is turned back into a scroll fraction that the renderer
     * can act on. An offset past the end of a shorter document (the user reduced the font size, or
     * the book was reflowed) clamps to 1 rather than failing, so the reader lands on the last page
     * instead of the first.
     */
    fun fractionFor(characterOffset: Int, length: Int): Float {
        if (length <= 0) return 0f
        return (characterOffset.toFloat() / length).coerceIn(0f, 1f)
    }

    private fun percentOf(part: Int, total: Int): Int =
        if (total <= 0) 0 else ((part.toDouble() / total) * 100).roundToInt().coerceIn(0, 100)
}
