package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.animeplanet.AnimePlanet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.util.concurrent.atomic.AtomicInteger

/**
 * Houri+: sends the library's tracking - status, chapters read, score, dates - to the trackers, so a prepared
 * backup fixes the lists on MyAnimeList, AniList, Anime-Planet and every other logged-in tracker too. A restore
 * otherwise only changes the app's copy, and the next sync can copy a wrong tracker number back into the library.
 *
 * One request at a time per tracker, spaced out to stay well under their rate limits (AniList allows 90 a minute).
 * A track that is not on the user's list yet is added first. Entries outside the library are left alone.
 */
class TrackingPusher(
    private val trackerManager: TrackerManager = globalAppGraph.trackerManager,
    private val getTracks: GetTracks = globalAppGraph.getTracks,
    private val insertTrack: InsertTrack = globalAppGraph.insertTrack,
    private val getFavorites: GetFavorites = globalAppGraph.getFavorites,
) {

    data class Result(val sent: Int, val failed: List<String>)

    suspend fun pushLibrary(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): Result = coroutineScope {
        val favoriteIds = getFavorites.await().map { it.id }
        val tracks = getTracks.await(favoriteIds).values.flatten()
        val loggedIn = tracks.map { it.trackerId }.distinct()
            .mapNotNull { id -> trackerManager.get(id)?.takeIf { it.isLoggedIn } }
            .associateBy { it.id }
        val work = tracks.filter { it.trackerId in loggedIn }
        val done = AtomicInteger(0)
        val sent = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
        work.groupBy { it.trackerId }
            .map { (trackerId, list) ->
                async {
                    val service = loggedIn.getValue(trackerId)
                    list.forEach { track ->
                        try {
                            push(service, track)
                            sent.incrementAndGet()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "Couldn't send ${track.title} to ${service.name}" }
                            failed += "${track.title} (${service.name}): ${e.message}"
                        }
                        onProgress(done.incrementAndGet(), work.size)
                        delay(SPACING_MS)
                    }
                }
            }
            .awaitAll()
        Result(sent.get(), failed.toList())
    }

    private suspend fun push(service: Tracker, track: Track) {
        var dbTrack = track.toDbTrack()
        // Anime-Planet links carry only the series slug: look up its id first
        if (service is AnimePlanet && dbTrack.remote_id == 0L) dbTrack = service.refresh(dbTrack)
        val wanted = track
        val updated = try {
            service.update(dbTrack, didReadChapter = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // not on the user's list yet: add it, then set the backup's values
            val added = service.bind(dbTrack, hasReadChapters = wanted.lastChapterRead > 0)
            added.status = wanted.status
            added.last_chapter_read = wanted.lastChapterRead
            added.score = wanted.score
            added.started_reading_date = wanted.startDate
            added.finished_reading_date = wanted.finishDate
            service.update(added, didReadChapter = false)
        }
        updated.toDomainTrack(idRequired = false)?.let { insertTrack.await(it.copy(id = track.id, mangaId = track.mangaId)) }
    }

    companion object {
        private const val SPACING_MS = 900L
    }
}
