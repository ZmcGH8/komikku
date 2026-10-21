package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retry
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Duration.Companion.seconds

@Inject
class GetLibraryManga(
    private val mangaRepository: MangaRepository,
    private val customMangaRepository: CustomMangaRepository,
) {

    suspend fun await(): List<LibraryManga> {
        return mangaRepository.getLibraryManga()
    }

    fun subscribe(): Flow<List<LibraryManga>> {
        return merge(
            flowOf(Unit),
            // When custom manga info changes (e.g. title cleaning), re-emit
            // the library so the UI picks up the new titles without a restart.
            customMangaRepository.changes,
        )
            // A single flatMap keeps exactly one live library subscription in flight: it runs
            // immediately (the seed `flowOf(Unit)`), and re-runs once per custom-info edit.
            // The merge this replaces ran both branches side by side, and the moment one edit
            // landed, two live subscriptions of the same query stayed alive for the rest of the
            // session - every chapter/history invalidation re-ran the full libraryView
            // aggregation twice and the screen received the whole list twice.
            .flatMapLatest {
                mangaRepository.getLibraryMangaAsFlow()
            }
            .retry {
                if (it is NullPointerException) {
                    delay(0.5.seconds)
                    true
                } else {
                    false
                }
            }.catch {
                this@GetLibraryManga.logcat(LogPriority.ERROR, it)
            }
    }
}
