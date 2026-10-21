package eu.kanade.tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.service.TrackPreferences
import exh.md.related.MangaDexSequelPrequelProvider
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SequelPrequelProvider
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.track.interactor.GetTracks

// KMK -->
/**
 * Sequel/prequel provider that follows tracker metadata instead of MangaDex:
 * the per-manga preferred tracker first, then the appwide priority tracker,
 * then every other logged-in tracker in order. Each service reports its
 * relations via [BaseTracker.getRelatedEntries] (null when unsupported —
 * currently only AniList), so services added later plug in with one override.
 * Results stay cached in
 * [tachiyomi.domain.manga.interactor.RelatedMangaCache] (24h TTL), so tracker
 * APIs see at most one fetch per manga per day.
 *
 * When no tracker in the chain yields relations (untracked manga, or none of
 * the bound services expose relations), the MangaDex API is tried as a
 * last resort for MangaDex-based manga, preserving the pre-rework behavior.
 *
 * ### A tracker's answer is final
 *
 * [BaseTracker.getRelatedEntries] distinguishes "cannot tell" (null) from "no relations"
 * (empty). Only the former continues down the chain. A tracker that answered is taken at its
 * word: falling through to the next one let an unrelated service's relations, or the MangaDex
 * fallback's, be rendered for a title the bound tracker says is standalone.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TrackerSequelPrequelProvider(
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val mangaDexFallback: MangaDexSequelPrequelProvider,
) : SequelPrequelProvider {
    override suspend fun fetch(mangaId: Long, preferredTrackerId: Long?): List<SequelPrequelEntry> {
        val manga = getManga.await(mangaId) ?: return emptyList()
        val tracks = try {
            getTracks.await(mangaId)
        } catch (_: Exception) {
            emptyList()
        }
        val orderedIds = (
            listOfNotNull(preferredTrackerId, trackPreferences.getPriorityTrackerId()) +
                trackerManager.trackers.filter { it.isLoggedIn }.map { it.id }
            ).distinct()
        // KMK --> A tracker's answer ends the search, whether or not it found anything.
        //
        // `getRelatedEntries` returns null when the service could not tell (it has no relations
        // API, or the call failed) and a list - possibly empty - when it answered. Only null
        // means "ask someone else". Collapsing both into "empty, keep looking" meant a title
        // MangaBaka reports as unlinked fell through to the next logged-in tracker and finally to
        // the MangaDex fallback, and whatever they returned was rendered as this title's
        // relations. That is the reported case: a manga bound to MangaBaka showed a "Source" that
        // MangaBaka does not list for it at all - the series payload carries
        // `relationships_v2: []`, so the row came from somewhere else entirely.
        var answered = false
        for (trackerId in orderedIds) {
            val service = trackerManager.get(trackerId) as? BaseTracker ?: continue
            if (!service.isLoggedIn) continue
            val remoteId = tracks.find { it.trackerId == trackerId }?.remoteId?.takeIf { it > 0 } ?: continue
            val entries = try {
                service.getRelatedEntries(remoteId)
            } catch (_: Exception) {
                null
            }
            if (entries == null) continue
            answered = true
            if (entries.isNotEmpty()) return entries
            // Authoritative "no relations": stop, rather than letting an unrelated tracker or the
            // MangaDex fallback answer for a title this one says is standalone.
            break
        }
        if (answered) return emptyList()

        // KMK --> stubs carry the tracker's own url but no bound track row, so
        // the loop above never matches them. Let a logged-in tracker claim the
        // url directly, making sequel/prequel rows recursive: stubs show their
        // own relations too.
        for (trackerId in orderedIds) {
            val service = trackerManager.get(trackerId) as? BaseTracker ?: continue
            if (!service.isLoggedIn) continue
            val remoteId = service.parseRelatedEntryId(manga.url) ?: continue
            val entries = try {
                service.getRelatedEntries(remoteId)
            } catch (_: Exception) {
                null
            }
            if (entries == null) continue
            if (entries.isNotEmpty()) return entries
            break
        }
        return try {
            mangaDexFallback.fetch(mangaId, preferredTrackerId)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
// KMK <--
