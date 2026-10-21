package tachiyomi.domain.track.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.track.model.Track

interface TrackRepository {

    suspend fun getTrackById(id: Long): Track?

    // SY -->
    suspend fun getTracks(): List<Track>

    suspend fun getTracksByMangaIds(mangaIds: List<Long>): List<Track>
    // SY <--

    suspend fun getTracksByMangaId(mangaId: Long): List<Track>

    fun getTracksAsFlow(): Flow<List<Track>>

    fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>>

    suspend fun delete(mangaId: Long, trackerId: Long)

    // KMK -->
    /**
     * Drops every track this tracker holds, across the whole library.
     *
     * Logout is a different operation from [delete]: that one removes a single entry because the
     * reader untracked one manga, this one says "I no longer want anything this service knows about
     * me" and therefore has to cover every row the service wrote. Credentials are not enough — the
     * local record outlives the session that fetched it, and on the next login it is read back as
     * though the user had never left.
     */
    suspend fun deleteAllByTrackerId(trackerId: Long)
    // KMK <--

    suspend fun insert(track: Track)

    suspend fun insertAll(tracks: List<Track>)
}
