package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A [TextSource] whose chapters have a navigable table of contents.
 *
 * For EPUB this is the book's own `nav.xhtml` (EPUB 3) or `toc.ncx` (EPUB 2), which is the only
 * place a publisher's part structure exists — recovering it from spine order alone is guesswork.
 * For a webnovel it is usually the volume and chapter list the site itself exposes.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextTocSource {

    /**
     * The table of contents for [chapter].
     *
     * Returns an empty list when the format has none, which is normal — not every book ships a TOC.
     * Hosts must render an empty result as "this book has no table of contents" rather than an
     * error, so that a missing TOC is never confused with a broken source.
     *
     * @since komikku/extensions-lib 1.6
     */
    suspend fun getTableOfContents(chapter: SChapter): List<TocEntry>
}
