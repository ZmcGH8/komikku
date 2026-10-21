package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.comick.ComickMissingScopesException
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class TrackLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(uri: Uri) {
        val data = when {
            !uri.encodedQuery.isNullOrBlank() -> uri.encodedQuery
            !uri.encodedFragment.isNullOrBlank() -> uri.encodedFragment
            else -> null
        }
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.associate {
                val parts = it.split("=", limit = 2).map<String, String>(Uri::decode)
                parts[0] to parts.getOrNull(1)
            }
            .orEmpty()

        // KMK --> ComicK's native redirect is RFC 8252 form (scheme + path, no authority),
        // so its callback arrives with a null host; fall back to the first path segment.
        val authHost = uri.host ?: uri.path?.trimStart('/')?.substringBefore('/')
        // KMK <--

        lifecycleScope.launch {
            try {
                when (authHost) {
                    "anilist-auth" -> handleAniList(data["access_token"])
                    "bangumi-auth" -> handleBangumi(data["code"])
                    "hikka-auth" -> handleHikka(data["reference"])
                    "mangabaka-auth" -> handleMangaBaka(data["code"], data["state"])
                    "myanimelist-auth" -> handleMyAnimeList(data["code"])
                    "shikimori-auth" -> handleShikimori(data["code"])
                    // KMK -->
                    "comick-auth" -> handleComick(data["code"], data["state"])
                    // KMK <--
                }
            } finally {
                returnToSettings()
            }
        }
    }

    private suspend fun handleAniList(accessToken: String?) {
        if (accessToken != null) {
            trackerManager.aniList.login(accessToken)
        } else {
            trackerManager.aniList.logout()
        }
    }

    private suspend fun handleBangumi(code: String?) {
        if (code != null) {
            trackerManager.bangumi.login(code)
        } else {
            trackerManager.bangumi.logout()
        }
    }

    // KMK -->
    private suspend fun handleComick(code: String?, state: String?) {
        if (state == null) {
            logcat(LogPriority.WARN) { "Did not receive state parameter from ComicK OAuth" }
            return
        }
        if (code != null) {
            if (!trackerManager.comicK.verifyOAuthState(state)) {
                logcat(LogPriority.WARN) { "Received wrong OAuth state back from ComicK" }
                return
            }
            try {
                trackerManager.comicK.login(code)
            } catch (e: ComickMissingScopesException) {
                withContext(Dispatchers.Main) {
                    this@TrackLoginActivity.toast(e.message, Toast.LENGTH_LONG)
                }
                return
            }
        } else {
            trackerManager.comicK.logout()
        }
    }
    // KMK <--

    private suspend fun handleHikka(reference: String?) {
        if (reference != null) {
            trackerManager.hikka.login(reference)
        } else {
            trackerManager.hikka.logout()
        }
    }

    private suspend fun handleMangaBaka(code: String?, state: String?) {
        if (state == null) {
            return
        }
        if (code != null) {
            if (!trackerManager.mangaBaka.verifyOAuthState(state)) {
                return
            }
            trackerManager.mangaBaka.login(code)
        } else {
            trackerManager.mangaBaka.logout()
        }
    }

    private suspend fun handleMyAnimeList(code: String?) {
        if (code != null) {
            trackerManager.myAnimeList.login(code)
        } else {
            trackerManager.myAnimeList.logout()
        }
    }

    private suspend fun handleShikimori(code: String?) {
        if (code != null) {
            trackerManager.shikimori.login(code)
        } else {
            trackerManager.shikimori.logout()
        }
    }
}
