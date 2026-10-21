package tachiyomi.domain.track.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Every track row in the database, grouped by manga id.
 *
 * This used to drop any row that was not "followed" on tracker 60 (MangaDex) - a restriction
 * MDLst applies to *its own* source listing. Nothing else consumed it, so the library's
 * tracking filter - both the blanket "tracked / untracked" tri-state and the per-tracker
 * tri-states - was handed an effectively empty map for anyone tracking on MAL, AniList, Kitsu
 * or any other service, and reported every entry as untracked.
 *
 * Being tracked is a property of whether a track row exists, so that is what this returns.
 * [IsTrackFollowed] stays available for callers that genuinely want the MDLst semantics.
 */
@Inject
class GetTracksPerManga(
    private val trackRepository: TrackRepository,
) {

    fun subscribe(): Flow<Map<Long, List<Track>>> {
        return trackRepository.getTracksAsFlow().map { tracks ->
            tracks.groupBy { it.mangaId }
        }
    }
}
