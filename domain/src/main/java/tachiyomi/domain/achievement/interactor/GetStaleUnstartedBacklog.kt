package tachiyomi.domain.achievement.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.interactor.GetLibraryManga
import kotlin.time.Duration.Companion.days

/**
 * Backlog = library entries added to the library more than [STALE_AFTER] ago that were never
 * started (no chapter read).
 *
 * Deliberately not "library minus finished": that is a different population - nearly the whole
 * library - and it cannot express "never opened". Computed in Kotlin over [LibraryManga.hasStarted]
 * rather than SQL, because a new query would need `:data:generateSqlDelightInterface` to
 * regenerate the database interface.
 */
@Inject
class GetStaleUnstartedBacklog(
    private val getLibraryManga: GetLibraryManga,
) {

    suspend fun await(): Long {
        val cutoff = System.currentTimeMillis() - STALE_AFTER.inWholeMilliseconds
        return getLibraryManga.await()
            // dateAdded 0 means never recorded, which is an old entry rather than a new one, so
            // `<= cutoff` keeps it. Library inserts always stamp it.
            .count { !it.hasStarted && it.manga.dateAdded <= cutoff }
            .toLong()
    }

    companion object {
        val STALE_AFTER = 30.days
    }
}
