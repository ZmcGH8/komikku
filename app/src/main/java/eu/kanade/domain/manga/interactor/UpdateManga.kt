package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.event.AppEvent
import eu.kanade.tachiyomi.data.event.AppEventBus
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.Instant
import java.time.ZonedDateTime

@Inject
class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {
    // KMK -->
    private val appEventBus: AppEventBus get() = globalAppGraph.appEventBus
    // KMK <--

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        val result = mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
        // KMK -->
        if (result) {
            val manga = mangaRepository.getMangaById(mangaId)
            appEventBus.emit(
                AppEvent.FavoriteToggled(
                    mangaTitle = manga.title,
                    favorite = favorite,
                    sourceId = manga.source,
                    mangaId = manga.id,
                ),
            )
            // Bulk adds use awaitAll, so this is the single-manga path and the count
            // query is not per item.
            if (favorite) {
                runCatching {
                    val graph = globalAppGraph
                    graph.achievementManager.onLibraryCountChanged(
                        mangaRepository.getFavorites().size.toLong(),
                    )
                    graph.rotatingAchievementPool.markProgress("rotating_daily_library_add_3")
                    graph.rotatingAchievementPool.markProgress("rotating_weekly_library_10")
                    graph.rotatingAchievementPool.markProgress("rotating_weekly_extra_8")
                }
            }
        }
        // KMK <--
        return result
    }
}
