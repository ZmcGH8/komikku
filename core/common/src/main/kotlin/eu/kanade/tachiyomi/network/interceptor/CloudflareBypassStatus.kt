package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cloudflare bypasses running right now, so the UI can show what a stuck request is waiting on.
 *
 * Ported from jobobby04/TachiyomiSY#1124 (FlareSolverr support).
 */
object CloudflareBypassStatus {
    enum class Method { FLARESOLVERR, WEBVIEW }

    data class Bypass(val host: String, val method: Method)

    private val _active = MutableStateFlow<List<Bypass>>(emptyList())
    val active: StateFlow<List<Bypass>> = _active.asStateFlow()

    fun <T> track(host: String, method: Method, block: () -> T): T {
        val bypass = Bypass(host, method)
        _active.update { it + bypass }
        try {
            return block()
        } finally {
            _active.update { it - bypass }
        }
    }
}
