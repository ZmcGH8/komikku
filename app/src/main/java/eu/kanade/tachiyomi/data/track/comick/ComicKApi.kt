package eu.kanade.tachiyomi.data.track.comick

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.comick.dto.ComickLibraryEntry
import eu.kanade.tachiyomi.data.track.comick.dto.ComickOAuth
import eu.kanade.tachiyomi.data.track.comick.dto.ComickSearchResult
import eu.kanade.tachiyomi.data.track.comick.dto.ComickTitleLookupResult
import eu.kanade.tachiyomi.data.track.comick.dto.ComickUser
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.PATCH
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.PkceUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.app.di.globalAppGraph
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Comick Library API (https://comick.dev/docs) — OAuth 2.0 Authorization Code with
 * S256 PKCE against `https://api.comick.dev/integrations/v1`.
 *
 * Ported from mihonapp/mihon#4056 ("Add Comick Tracker", @MajorTanya) and adjusted
 * to the published OpenAPI document (progress numbers are strings, `expires_in`).
 */
class ComicKApi(
    private val trackerId: Long,
    baseClient: OkHttpClient,
    private val interceptor: ComicKInterceptor,
) {

    private val json: Json by lazy { globalAppGraph.json }

    private val client = baseClient.newBuilder()
        // Comick allows 60 requests/minute per app/user; leave headroom.
        .rateLimit(permits = 55, period = 1.minutes)
        .addInterceptor {
            it.request().newBuilder()
                .header(
                    "User-Agent",
                    buildString {
                        append("Houri/v${BuildConfig.VERSION_NAME} ")
                        append("(${BuildConfig.APPLICATION_ID} ${BuildConfig.COMMIT_SHA}) ")
                        append("(Android) (https://github.com/PineappleTwilight/komikku-pineapple)")
                    },
                )
                .header("Accept", jsonMime.toString())
                .build()
                .let(it::proceed)
        }.build()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .build()

    /**
     * Add (or replace) a library entry. PUT requires `status` (1–5); omitted fields
     * are preserved server-side.
     */
    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = libraryUrl(track.remote_id.toHid())
            val body = buildJsonObject {
                put("status", track.status.toApiListStatus())
                // ratings are integers 1-10; null clears. 0 means "unrated" locally.
                put("rating", track.score.toInt().takeIf { it > 0 })
                // progress.number is a string per the API schema; null clears it.
                if (track.last_chapter_read > 0) {
                    putJsonObject("progress") {
                        put("number", track.last_chapter_read.toString())
                    }
                } else {
                    put("progress", null)
                }
            }
                .toString()
                .toRequestBody(jsonMime)

            authClient
                .newCall(PUT(url, body = body))
                .awaitSuccess()

            // only returns the same data back with nothing additionally useful
            track
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            val url = libraryUrl(track.remoteId.toHid())

            authClient
                .newCall(DELETE(url, body = RequestBody.EMPTY))
                .awaitSuccess()
        }
    }

    /** Current library entry, or null when the user does not follow the title (404). */
    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val url = libraryUrl(track.remote_id.toHid())
            with(json) {
                try {
                    authClient.newCall(GET(url))
                        .awaitSuccess()
                        .parseAs<ComickLibraryEntry>()
                        .toTrack(trackerId)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    /** PATCH sends only the supplied fields; 404 when the entry does not exist. */
    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = libraryUrl(track.remote_id.toHid())
            val body = buildJsonObject {
                // ratings are integers 1–10; null clears. 0 means "unrated" locally.
                put("rating", track.score.toInt().takeIf { it > 0 })
                put("status", track.status.toApiListStatus())
                putJsonObject("progress") {
                    put("number", track.last_chapter_read.toString())
                }
            }
                .toString()
                .toRequestBody(jsonMime)

            val response = authClient
                .newCall(PATCH(url, body = body))
                .awaitSuccess()

            // The response is the entry as Comick stores it *after* the write, which is worth reading.
            // This returned the request track untouched, so the local row kept whatever was asked
            // for whether or not Comick had taken it - and the caller's row is what the app
            // persists from this call, so a field the server quietly ignored sat in the UI looking
            // saved until the next refresh pulled it back down. Reading the server's copy is what
            // keeps the two honest.
            //
            // Only the three fields the write covers are adopted, onto the caller's own track:
            // everything else (the local id, start/finish dates, the private flag, the reread
            // count) is not in this response and replacing the object would blank it.
            //
            // A body that cannot be read leaves the caller's track as it was rather than failing
            // a write that did land.
            runCatching {
                with(json) { response.parseAs<ComickLibraryEntry>().toTrack(trackerId) }
            }.getOrNull()?.let { stored ->
                track.status = stored.status
                track.last_chapter_read = stored.last_chapter_read
                track.score = stored.score
            }

            track
        }
    }

    /** Bounded tracker title search; requires `library:read` via the access token. */
    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$RESOURCE_URL/titles".toUri().buildUpon()
                // Comick throws a 400 when the query is over 100 characters
                .appendQueryParameter("q", search.take(100))
                .appendQueryParameter("media_type", "manga")
                .appendQueryParameter("limit", "20")
                .build()

            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<ComickSearchResult>()
                    .data
                    .map { it.toTrackSearch(trackerId) }
            }
        }
    }

    /** HID-only title lookup for binding/details. */
    suspend fun getMangaDetails(hid: String): TrackSearch? {
        return withIOContext {
            val url = "$RESOURCE_URL/titles".toUri().buildUpon()
                .appendPath(hid)
                .build()

            with(json) {
                try {
                    authClient.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<ComickTitleLookupResult>()
                        .data
                        .toTrackSearch(trackerId)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    /** Connected user's public profile (id + username). */
    suspend fun getCurrentUser(): ComickUser {
        return withIOContext {
            val url = "$RESOURCE_URL/me"

            with(json) {
                authClient.newCall(GET(url))
                    .awaitSuccess()
                    .parseAs<ComickUser>()
            }
        }
    }

    /**
     * Exchange the authorization code for tokens.
     *
     * @throws ComickMissingScopesException when the user declined `offline_access`
     * or `library:write`.
     */
    suspend fun getAccessToken(code: String): ComickOAuth {
        return withIOContext {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", persistedCodeVerifier())
                .add("resource", RESOURCE_URL)
                .build()

            with(json) {
                val oauth = client.newCall(POST("$OAUTH_URL/token", body = formBody))
                    .awaitSuccess()
                    .parseAs<ComickOAuth>()

                if (oauth.refreshToken.isNullOrBlank()) {
                    throw ComickMissingScopesException("Permission for ongoing synchronisation is required.")
                }
                if (!oauth.scope.contains(LIBRARY_WRITE_SCOPE)) {
                    // Users may forget to enable this since it's off by default
                    throw ComickMissingScopesException("Permission to add/remove manga from your library is required.")
                }
                oauth
            }
        }
    }

    fun verifyOAuthState(state: String): Boolean {
        val expected = persistedState()
        return state == expected && expected.isNotBlank()
    }

    companion object {
        /**
         * Houri's public client ID, registered at https://comick.dev/developers/apps
         * as a public "Native mobile or desktop app" client with library:write enabled
         * and these exact callbacks (single slash — no authority, per RFC 8252):
         *   app.houri:/comick-auth
         *   app.houri.dev:/comick-auth
         *   app.houri.rt:/comick-auth
         *   app.houri.nomtl:/comick-auth
         *
         * If the Comick app is ever re-registered, update this together with
         * [REDIRECT_URI] and the `app.houri` intent-filter in AndroidManifest.xml.
         */
        private const val CLIENT_ID = "TNHNwmJiTpJcEmDvvBhkqjtSXMEtEFjU"

        // Comick requires a native reverse-domain scheme (plain `houri://` is rejected)
        // in RFC 8252 form: scheme + path, no authority component.
        private const val REDIRECT_URI = "app.houri:/comick-auth"

        private const val LIBRARY_WRITE_SCOPE = "library:write"
        private const val SCOPES = "library:read $LIBRARY_WRITE_SCOPE offline_access"

        private const val OAUTH_URL = "https://comick.dev/api/auth/oauth2"
        private const val API_BASE_URL = "https://api.comick.dev"
        private const val RESOURCE_URL = "$API_BASE_URL/integrations/v1"

        private var codeVerifier: String = ""
        private var oauthStateParam: String = ""

        private const val PREF_CODE_VERIFIER = "comick_code_verifier"
        private const val PREF_OAUTH_STATE = "comick_oauth_state"

        private fun preferenceStore() = try {
            globalAppGraph.preferenceStore
        } catch (_: Exception) {
            null
        }

        private fun libraryUrl(hid: String): String =
            "$RESOURCE_URL/me/library".toUri().buildUpon()
                .appendPath(hid)
                .build()
                .toString()

        fun authUrl(): Uri = "$OAUTH_URL/authorize".toUri().buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("resource", RESOURCE_URL)
            .appendQueryParameter("state", getOAuthStateParam())
            .appendQueryParameter("code_challenge", getPkceS256ChallengeCode())
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        fun refreshTokenRequest(refreshToken: String) = POST(
            "$OAUTH_URL/token",
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken)
                .add("resource", RESOURCE_URL)
                .build(),
        )

        private fun getOAuthStateParam(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            oauthStateParam = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes)

            preferenceStore()?.getString(PREF_OAUTH_STATE, "")?.set(oauthStateParam)
            return oauthStateParam
        }

        private fun persistedState(): String {
            if (oauthStateParam.isNotBlank()) return oauthStateParam
            return preferenceStore()?.getString(PREF_OAUTH_STATE, "")?.get().orEmpty()
        }

        private fun persistedCodeVerifier(): String {
            if (codeVerifier.isNotBlank()) return codeVerifier
            return preferenceStore()?.getString(PREF_CODE_VERIFIER, "")?.get().orEmpty()
        }

        /**
         * Comick requires a conformant PKCE process (unlike MAL):
         * 1. create verifier
         * 2. create challenge from verifier (S256 hash -> base64url, no padding)
         * 3. send challenge to /authorize
         * 4. send verifier to /token
         */
        private fun getPkceS256ChallengeCode(): String {
            codeVerifier = PkceUtil.generateCodeVerifier()
            preferenceStore()?.getString(PREF_CODE_VERIFIER, "")?.set(codeVerifier)
            val bytes = codeVerifier.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
