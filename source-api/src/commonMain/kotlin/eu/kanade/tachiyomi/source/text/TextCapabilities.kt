package eu.kanade.tachiyomi.source.text

/**
 * Declares which optional text features a [TextSource] actually implements.
 *
 * Hosts use this to decide which controls to show *before* a chapter is opened, so that a reader
 * without a table of contents does not present a "Table of contents" button that opens an empty
 * sheet. Every flag defaults to `false`.
 *
 * @property TOC the source implements [TextTocSource].
 * @property SEARCH the source implements [TextSearchSource].
 * @property ANNOTATIONS the source implements [TextAnnotationSource] — highlights, bookmarks and
 *   notes that survive a library refresh.
 * @property IMAGES pages may contain images, so hosts must resolve relative references and allow
 *   image loading rather than sanitising them away. EPUB chapters are almost always `true`.
 * @property VERTICAL_WRITING content uses vertical writing mode (縦書き), typical of Japanese and
 *   Chinese light novels. Hosts switch the renderer to vertical flow instead of forcing it
 *   horizontal.
 * @property RUBY content uses ruby annotations (furigana). Without this hosts may strip the
 *   `<ruby>` markup and lose small kana readings entirely.
 *
 * @since komikku/extensions-lib 1.6
 */
data class TextCapabilities(
    val TOC: Boolean = false,
    val SEARCH: Boolean = false,
    val ANNOTATIONS: Boolean = false,
    val IMAGES: Boolean = false,
    val VERTICAL_WRITING: Boolean = false,
    val RUBY: Boolean = false,
) {
    companion object {
        /**
         * The common case: a whole book with a table of contents, images, and nothing else.
         *
         * What a local EPUB library reports.
         */
        val EPUB = TextCapabilities(TOC = true, IMAGES = true)
    }
}
