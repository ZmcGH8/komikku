package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

/** A library entry together with the entries it duplicates. */
data class DuplicateEntry(
    val manga: Manga,
    val duplicates: List<MangaWithChapterCount>,
)

/**
 * Finds every library entry that has at least one duplicate, so they can be reviewed in one place
 * rather than only being surfaced at the moment something is added to the library.
 *
 * Matching is the same as when adding: a shared title substring, or the same remote entry on the
 * same tracker. That means the scan issues one query per library entry, which is why this runs only
 * when the screen is opened rather than on any library update.
 *
 * ### One row per pair, not one per side
 *
 * The duplicate query is symmetric — it matches on title and excludes only the entry it was handed —
 * so asking it about both halves of a pair answers "yes, you have a duplicate" twice. Rendering that
 * raw lists every pair twice, and a library with three mutually-duplicated entries lists all three
 * even though there is only one group to review. It reads as though the screen is broken rather than
 * as a quirk of the query, because the two rows are the same single finding wearing different titles.
 *
 * So the first entry to claim a set stands for it, and any entry already claimed by an earlier row is
 * skipped. `duplicates` is left untouched, because the review dialog still needs the full set from
 * whichever side it was reached: the pair stays reachable, it is just listed once.
 */
@Inject
class GetLibraryDuplicates(
    private val getLibraryManga: GetLibraryManga,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
) {

    suspend fun await(): List<DuplicateEntry> {
        val results = mutableListOf<DuplicateEntry>()
        // Ids already shown as somebody else's duplicate, so they do not get a row of their own.
        val represented = mutableSetOf<Long>()

        for (item in getLibraryManga.await()) {
            if (item.manga.id in represented) continue
            val duplicates = getDuplicateLibraryManga(item.manga)
            if (duplicates.isEmpty()) continue
            duplicates.forEach { represented += it.manga.id }
            results.add(DuplicateEntry(manga = item.manga, duplicates = duplicates))
        }
        return results
    }
}
