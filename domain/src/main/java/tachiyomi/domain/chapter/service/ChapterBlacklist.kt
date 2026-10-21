package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter
import kotlin.math.roundToInt

/**
 * Cap on the scanlator half of a blacklist key. The name comes from a source and a backup can
 * carry anything, so an unbounded one would let a corrupt or hostile list bloat the persisted
 * value and every comparison made against it.
 */
private const val MAX_KEY_SCANLATOR_LEN = 64

/**
 * Cap on the upload half of a narrowed blacklist key, for the same reason as the scanlator half:
 * it arrives from a source, and a backup can carry anything.
 */
private const val MAX_KEY_URL_LEN = 256

private const val URL_SEPARATOR = '#'

/**
 * Identifies one chapter of one scanlator inside [tachiyomi.domain.manga.model.Manga.blacklistedChapters],
 * as `"<chapterNumber>@<scanlator>"`.
 *
 * Lives in domain rather than alongside the merge helpers because the blacklist gates more than
 * the chapter list: it also has to be honoured by anything acting on a series' chapters, and
 * several of those are domain interactors.
 */
fun scanlatorBlacklistKey(chapterNumber: Double, scanlator: String?): String {
    // Round to 2 decimals so float artifacts (1.5000000000000002) don't leak into the stored key.
    val rounded = (chapterNumber * 100).roundToInt() / 100.0
    // A scanlator name is display text that arrives from a source, so it can carry stray
    // whitespace. Two entries differing only by it would blacklist different things.
    val name = scanlator.orEmpty().trim().take(MAX_KEY_SCANLATOR_LEN)
    return "$rounded@$name"
}

/**
 * [scanlatorBlacklistKey] narrowed to the one upload at [url].
 *
 * The broad key covers a chapter number per scanlator, which is exactly right when a scanlator
 * published it once but not when it published the same number twice: blacklisting one upload would
 * take the other with it. The upload url is what tells them apart, and it is stable for a given
 * manga because a manga comes from a single source.
 */
fun scanlatorBlacklistKey(chapterNumber: Double, scanlator: String?, url: String): String =
    scanlatorBlacklistKey(chapterNumber, scanlator) + URL_SEPARATOR + url.trim().take(MAX_KEY_URL_LEN)

/**
 * Whether [chapter] is covered by [blacklist], the per-manga list of [scanlatorBlacklistKey]s.
 *
 * Every surface that shows or acts on a series' chapters must ask this rather than testing
 * membership of a raw key, so the key format has exactly one definition and callers cannot drift
 * apart from it.
 *
 * Both key widths are honoured, the broad one covering every upload and the narrowed one just this
 * one, so a blacklist written before [scanlatorBlacklistKey] took a url keeps working unchanged.
 *
 * Deliberately not case-folding. Keys are already persisted per manga, so changing the format
 * would silently un-blacklist every entry a user has.
 */
fun isChapterBlacklisted(chapter: Chapter, blacklist: Collection<String>): Boolean {
    if (blacklist.isEmpty()) return false
    if (scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator) in blacklist) return true
    // Skipped when blank: a narrowed key is never written for one, and building it here would
    // match a blacklist entry that happens to end in the separator.
    if (chapter.url.isBlank()) return false
    return scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator, chapter.url) in blacklist
}
