package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import tachiyomi.domain.manga.model.Manga

private val NAME_SEPARATORS = Regex("[,;、；]")

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * An extension answers a query with whatever its own matching decided, so searching "John Smith" can
 * come back as John Smithson, or as a manga that merely mentions the name somewhere. The search has no
 * way to ask an extension to narrow that down, so it narrows the results itself against the creator
 * fields, which is the only place the distinction exists.
 *
 * Comma and semicolon are the separators extensions use to pack multiple creators into the one string
 * the model has. A name only counts as an exact hit when it equals one whole entry, so a second
 * creator never blocks a match. Whitespace runs collapse and case is ignored, since those are
 * artefacts of formatting rather than differences between names; nothing else is normalised, which is
 * what keeps "John Smith" from matching "John Smithson" or "Johnny Smith".
 */
fun Manga.matchesCreatorExactly(query: String): Boolean {
    val needle = query.normalizeCreatorName()
    if (needle.isEmpty()) return true
    return author.creatorNames().any { it == needle } || artist.creatorNames().any { it == needle }
}

private fun String?.creatorNames(): List<String> = this
    ?.split(NAME_SEPARATORS)
    ?.map { it.normalizeCreatorName() }
    ?.filter { it.isNotEmpty() }
    .orEmpty()

private fun String.normalizeCreatorName(): String = lowercase().replace(WHITESPACE_RUN, " ").trim()
