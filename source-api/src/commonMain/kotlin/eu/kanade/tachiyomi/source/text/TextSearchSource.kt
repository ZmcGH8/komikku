package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A [TextSource] whose chapters can be searched from the reader.
 *
 * Search runs over text the host already has, so the contract is deliberately small: return
 * positions, and let the host render. A source that cannot search its own content (a protected
 * EPUB, a site that hides full text) simply does not implement this.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextSearchSource {

    /**
     * Finds [query] in [chapter], case-insensitively.
     *
     * @return every match, in reading order. Empty when nothing matched — never null, and never an
     *   error for "not found".
     * @since komikku/extensions-lib 1.6
     */
    suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit>
}
