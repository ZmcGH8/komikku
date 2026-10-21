package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.data.track.comick.dto.ComickOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the bearer access token to Comick requests and refreshes it when expired.
 * Refreshes are serialized so parallel requests trigger at most one token
 * rotation (Comick refresh tokens rotate on use), and both rotated tokens are
 * persisted atomically by [setAuth].
 */
class ComicKInterceptor(private val comicK: ComicK) : Interceptor {

    private val json: Json by lazy { globalAppGraph.json }

    @Volatile
    private var oauth: ComickOAuth? = comicK.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        var currentAuth = oauth ?: throw Exception("Not authenticated with ComicK")

        if (currentAuth.isExpired()) {
            currentAuth = synchronized(this) {
                val latest = oauth ?: throw Exception("Not authenticated with ComicK")
                if (!latest.isExpired()) return@synchronized latest

                val refreshToken = latest.refreshToken
                if (refreshToken.isNullOrBlank()) {
                    comicK.logout()
                    throw Exception("ComicK session expired")
                }

                val response = chain.proceed(ComicKApi.refreshTokenRequest(refreshToken))
                if (response.isSuccessful) {
                    response.use { res ->
                        with(json) { res.parseAs<ComickOAuth>() }
                    }.also(::setAuth)
                } else {
                    response.close()
                    // offline_access declined or session revoked; force a clean re-login
                    comicK.logout()
                    throw Exception("ComicK session expired")
                }
            }
        }

        return originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${currentAuth.accessToken}")
            .build()
            .let(chain::proceed)
    }

    fun setAuth(oauth: ComickOAuth?) {
        this.oauth = oauth

        comicK.saveToken(oauth)
    }
}
