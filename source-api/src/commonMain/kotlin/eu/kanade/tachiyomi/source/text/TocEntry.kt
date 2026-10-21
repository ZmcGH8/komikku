package eu.kanade.tachiyomi.source.text

/**
 * One entry in a table of contents.
 *
 * @property label text shown in the navigation UI.
 * @property href destination, in the same form as [TextPage.url] — the in-archive path of an EPUB
 *   spine item, or a chapter URL for a webnovel. Must match a [TextPage.url] exactly for
 *   navigation to resolve, because that is how the host maps an entry back onto a loaded page.
 * @property children nested entries. EPUB3 nav documents nest arbitrarily deep; hosts should
 *   support at least two levels and may flatten deeper ones.
 * @property pageIndex position of the destination within its chapter, when the source knows it up
 *   front. Null means "resolve by href when the user taps", which is correct for a whole-book TOC
 *   where one entry spans many chapters.
 *
 * @since komikku/extensions-lib 1.6
 */
data class TocEntry(
    val label: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
    val pageIndex: Int? = null,
) {
    /** This entry and every descendant, depth-first — what a flattened list needs. */
    fun flatten(): List<TocEntry> = buildList {
        fun walk(entry: TocEntry) {
            add(entry)
            entry.children.forEach(::walk)
        }
        walk(this@TocEntry)
    }

    companion object {
        /**
         * Collapses a nested TOC into a flat, ordered list.
         *
         * Hosts paginate or scroll a flat list but still want to show nesting, so this is the
         * expected way to consume a TOC without reimplementing the traversal.
         */
        fun flatten(entries: List<TocEntry>): List<TocEntry> = entries.flatMap { it.flatten() }
    }
}
