package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.service.RereadPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.source.isMergedSourceId
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks

@Inject
class StopRereading(
    private val mangaRepository: MangaRepository,
    private val setReadStatus: SetReadStatus,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val getIncognitoState: GetIncognitoState,
    private val trackPreferences: TrackPreferences,
    // KMK --> The pre-reread read state, recorded by StartRereading. See RereadPreferences.
    // KMK <--
    private val rereadPreferences: RereadPreferences,
) {

    suspend fun await(manga: Manga): Result {
        if (!manga.rereading) return Result.Failure

        // Clear the flag first so restoring chapter read state below cannot
        // be mistaken for a completed reread
        mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                rereading = false,
                rereadStartedAt = 0L,
            ),
        )

        val chapters = if (isMergedSourceId(manga.source)) {
            getMergedChaptersByMangaId.await(manga.id, dedupe = false)
        } else {
            getChaptersByMangaId.await(manga.id)
        }
        // KMK --> Restore the read state the series had when the reread began, and only that.
        //
        // The old test was `!chapter.read && (startedAt == 0L || chapter.dateFetch <= startedAt)`.
        // dateFetch is the timestamp of the last *fetch*, not of a read-state change, so starting
        // a reread - which marks every chapter unread and rewrites none of those stamps - left
        // every chapter looking older than the reread start. Stopping one therefore marked the
        // whole series read however little had been re-read: a 10-of-50 series went to 50/50.
        //
        // What belongs here is the set of chapters that were read at the moment the reread
        // started, which is what [StartRereading] records as the baseline. Those go back to read
        // - re-reading them is what made them read in the first place - and everything the user
        // skipped past stays unread, which is the whole difference between stopping and finishing.
        //
        // The fallback covers a reread whose baseline was never recorded (migrated from an older
        // build, or the pref was wiped): the same test as before, which is a guess but not a
        // guaranteed mark-everything.
        val startedAt = manga.rereadStartedAt
        val baseline = rereadPreferences.baseline(manga.id)
        val chaptersToRestore = if (baseline != null) {
            chapters.filter { it.id in baseline }
        } else {
            chapters.filter { !it.read && (startedAt == 0L || it.dateFetch <= startedAt) }
        }
        rereadPreferences.clearBaseline(manga.id)
        // KMK <--
        setReadStatus.await(
            read = true,
            manually = false,
            chapters = chaptersToRestore.toTypedArray(),
        )

        syncTrackers(manga)

        return Result.Success
    }

    private suspend fun syncTrackers(manga: Manga) {
        if (getIncognitoState.await(manga.source)) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        getTracks.await(manga.id).forEach { track ->
            val service = trackerManager.get(track.trackerId)
            if (service == null || !service.isLoggedIn) return@forEach

            try {
                if (
                    track.status == service.getRereadingStatus() &&
                    service.getCompletionStatus() != -1L
                ) {
                    service.setRemoteStatus(track.toDbTrack(), service.getCompletionStatus())
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to restore tracker status on ${service.name} for manga ${manga.id}" }
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Failure : Result
    }
}
