package eu.kanade.tachiyomi.data.track.comick

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.comick.dto.ComickOAuth
import eu.kanade.tachiyomi.data.track.core.TrackerLoginMode
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Comick tracker (https://comick.dev) — OAuth 2.0 Authorization Code + S256 PKCE
 * against the official Library API (api.comick.dev/integrations/v1).
 *
 * Ported from mihonapp/mihon#4056 ("Add Comick Tracker", @MajorTanya), adapted to
 * this repo's tracker framework (login mode, token preferences, `ComicK` naming).
 *
 * Note: the tracker only works once [ComicKApi]'s CLIENT_ID is filled in — see the
 * registration instructions there.
 */
class ComicK(id: Long) : BaseTracker(id, "ComicK"), DeletableTracker {

    private val json: Json by lazy { globalAppGraph.json }

    private val interceptor by lazy { ComicKInterceptor(this) }

    private val api by lazy { ComicKApi(id, client, interceptor) }

    override fun getLogo(): Int = R.drawable.brand_comick

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, PLAN_TO_READ, DROPPED)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        PLAN_TO_READ -> MR.strings.plan_to_read
        DROPPED -> MR.strings.dropped
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = READING // ComicK has no re-reading concept

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ

    override fun getLoginMode(): TrackerLoginMode = TrackerLoginMode.OAUTH

    override fun getAuthUrl(): String? = ComicKApi.authUrl().toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED && track.status != READING && didReadChapter) {
            track.status = READING
            if (track.last_chapter_read == 1.0) {
                track.started_reading_date = System.currentTimeMillis()
            }
        }

        return api.updateLibManga(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteTrack = api.findLibManga(track) ?: run {
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            track.score = 0.0

            return api.addLibManga(track)
        }
        // Keep the remote status/score/progress; only adjust status locally.
        track.copyPersonalFrom(remoteTrack)
        track.title = remoteTrack.title
        track.remote_id = remoteTrack.remote_id

        if (track.status != COMPLETED && hasReadChapters) {
            track.status = READING
        }

        return update(track)
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().let { hid ->
                return api.getMangaDetails(hid)?.let { listOf(it) } ?: emptyList()
            }
        }

        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.findLibManga(track) ?: throw Exception("Could not find manga")
        track.copyPersonalFrom(remoteTrack)
        track.remote_id = remoteTrack.remote_id
        track.title = remoteTrack.title
        return track
    }

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val remote = api.getMangaDetails(track.remoteId.toHid())
            ?: throw Exception("Could not find manga")
        return TrackMangaMetadata(
            remoteId = track.remoteId,
            title = remote.title,
            thumbnailUrl = remote.cover_url,
            description = remote.summary,
        )
    }

    override suspend fun login(username: String, password: String) = login(password)

    /**
     * Complete the OAuth flow with the authorization code from the redirect.
     * Requires the user to have granted `offline_access` (refresh token) and
     * `library:write`, otherwise [ComickMissingScopesException] propagates.
     */
    suspend fun login(code: String) {
        try {
            val oauth = api.getAccessToken(code)
            interceptor.setAuth(oauth)
            val currentUser = api.getCurrentUser()
            saveCredentials(currentUser.username ?: currentUser.id, oauth.accessToken)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ComicK OAuth login failed" }
            logout()
            if (e is ComickMissingScopesException) throw e
        }
    }

    fun saveToken(oauth: ComickOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): ComickOAuth? {
        return try {
            json.decodeFromString(trackPreferences.trackToken(this).get())
        } catch (_: Exception) {
            null
        }
    }

    fun verifyOAuthState(state: String): Boolean = api.verifyOAuthState(state)

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteLibManga(track)
    }

    companion object {
        // Tracker status constants
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val PLAN_TO_READ = 4L
        const val DROPPED = 5L

        private val SCORE_LIST = (0..10)
            .map { if (it == 0) "-" else it.toString() }
            .toImmutableList()

        private const val SEARCH_ID_PREFIX = "id:"
    }
}
