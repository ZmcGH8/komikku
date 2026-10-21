package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    // KMK --> FlareSolverr support, ported from jobobby04/TachiyomiSY#1124.
    private val preferences: NetworkPreferences,
    defaultUserAgentProvider: () -> String,
    // A provider rather than a value: NetworkHelper builds this interceptor and FlareSolverr needs
    // NetworkHelper, so handing over a factory keeps that from being a construction-time cycle, and it
    // reads the enabled preference per challenge instead of freezing it at build time.
    private val flareSolverrProvider: () -> FlareSolverr,
    // KMK <--
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        return if (response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK) {
            val document = Jsoup.parse(
                response.peekBody(Long.MAX_VALUE).string(),
                response.request.url.toString(),
            )

            // solve with webview only on captcha, not on geo block
            document.getElementById("challenge-error-title") != null ||
                document.getElementById("challenge-error-text") != null
        } else {
            false
        }
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }

            // KMK --> FlareSolverr first, then the WebView. Solved retries carry the solver's own
            // user agent because Cloudflare ties cf_clearance to it.
            if (preferences.enableFlareSolverr.get()) {
                val solved = resolveWithFlareSolverr(request)
                if (solved != null) return chain.proceed(solved)
                return chain.proceed(resolveWithStockUserAgent(request))
            }
            // KMK <--
            CloudflareBypassStatus.track(request.url.host, CloudflareBypassStatus.Method.WEBVIEW) {
                resolveWithWebView(request, oldCookie)
            }

            return chain.proceed(request)
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    // KMK -->
    /** Returns the request to retry with, or null to fall back to the WebView. */
    private fun resolveWithFlareSolverr(request: Request): Request? {
        val oldClearance = flareSolverrProvider().clearanceCookie(request.url)
        val previousUserAgent = preferences.defaultUserAgent().get()
        return try {
            CloudflareBypassStatus.track(request.url.host, CloudflareBypassStatus.Method.FLARESOLVERR) {
                runBlocking { flareSolverrProvider().resolve(request, oldClearance) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "FlareSolverr failed, falling back to WebView" }
            null
        }.also { solved ->
            if (solved != null && preferences.defaultUserAgent().get() != previousUserAgent) {
                executor.execute { context.toast(KMR.strings.flare_solver_user_agent_updated, Toast.LENGTH_LONG) }
            }
        }
    }

    /**
     * WebView fallback under the stock user agent: the solver's desktop one makes the challenge fail,
     * so it has to be reset before retrying.
     */
    private fun resolveWithStockUserAgent(request: Request): Request {
        val userAgent = preferences.defaultUserAgent().defaultValue()
        if (preferences.defaultUserAgent().get() != userAgent) {
            preferences.defaultUserAgent().delete()
            executor.execute { context.toast(KMR.strings.flare_solver_user_agent_reset, Toast.LENGTH_LONG) }
        }
        val fallbackRequest = request.newBuilder().header("User-Agent", userAgent).build()
        cookieManager.remove(fallbackRequest.url, COOKIE_NAMES, 0)
        val oldCookie = cookieManager.get(fallbackRequest.url).firstOrNull { it.name in COOKIE_NAMES }
        CloudflareBypassStatus.track(fallbackRequest.url.host, CloudflareBypassStatus.Method.WEBVIEW) {
            resolveWithWebView(fallbackRequest, oldCookie)
        }
        return fallbackRequest
    }
    // KMK <--

    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest)

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl && !challengeFound) {
                        // The first request didn't return the challenge, abort.
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
// KMK --> Widened from private so FlareSolverr.kt reads the same constant; a second copy in that
// file's package would be a conflicting declaration and make this file's uses ambiguous.
// KMK <--
internal val COOKIE_NAMES = listOf("cf_clearance")

// KMK --> Shared with FlareSolverr.kt, which reports a failed solve through the same signal.
internal class CloudflareBypassException(message: String? = null) : Exception(message)
// KMK <--
