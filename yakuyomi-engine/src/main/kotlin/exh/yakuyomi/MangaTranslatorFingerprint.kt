package exh.yakuyomi

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.UUID

/**
 * The device-identity half of [MangaTranslatorService].
 *
 * The service is fronted by bot protection that rejects a lone User-Agent, so every request carries
 * a stable pseudo-browser profile. Those component strings are deliberately fixed rather than probed
 * - the point is to be unremarkable and consistent, not to describe this device - so they live here
 * in one place instead of being spread through the request path, where a partial edit would produce a
 * profile that no longer matches the one the account was registered with.
 */
internal class MangaTranslatorFingerprint(
    private val context: Context,
    private val prefs: TranslationPreferences,
) {
    fun clientUuid(): String {
        val store = prefs.mangaTranslatorClientUuid().get()
        if (store.isNotBlank() && store.length >= 32) return store
        val fresh = UUID.randomUUID().toString()
        prefs.mangaTranslatorClientUuid().set(fresh)
        return fresh
    }

    fun fingerprint(): String {
        val cached = prefs.mangaTranslatorFingerprint().get()
        if (cached.isNotBlank() && cached.length >= 16) return cached.take(128)
        val fresh = buildFingerprint()
        prefs.mangaTranslatorFingerprint().set(fresh)
        return fresh
    }

    private fun buildFingerprint(): String {
        val webGl = "android-gpu-${Build.HARDWARE}-unknown"
        val hardware = "${Runtime.getRuntime().availableProcessors()}-${deviceMemoryBucket()}"
        val connection = "unknown-unknown-unknown-unknown-false"
        val timezone = java.util.TimeZone.getDefault().rawOffset / 60000
        val screen = screenInfo()
        val canvas = canvasHash()
        val browser = "sw,ls,ss,idb,geo,notif,perm,cookie,online,conn"
        val language = "${java.util.Locale.getDefault().language}-${java.util.Locale.getDefault()}"
        val touch = "0-false-false-false"
        val orientation = "portrait-primary-0-false"
        val ua = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.VERSION.SDK_INT}"
        val perf = "0-0-0-0-unknown-0-0"
        val components = listOf(webGl, hardware, connection, timezone.toString(), screen, canvas, browser, language, touch, orientation, ua, perf)
        return hashString(components.joinToString("-"))
    }

    private fun deviceMemoryBucket(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024L * 1024L * 1024L)
            when {
                totalGb >= 8 -> "8"
                totalGb >= 6 -> "6"
                totalGb >= 4 -> "4"
                else -> totalGb.toString()
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun screenInfo(): String {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels
            val d = dm.densityDpi
            val ratio = dm.density
            "$w×$h-$d-$d-$w×$h-$ratio"
        } catch (_: Exception) {
            "screen-unavailable"
        }
    }

    private fun canvasHash(): String {
        return try {
            val payload = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.DISPLAY}${Build.FINGERPRINT}"
            var hash = 0
            for (c in payload) {
                hash = (hash shl 5) - hash + c.code
                hash = hash and hash
            }
            hash.toString()
        } catch (_: Exception) {
            "canvas-error"
        }
    }

    private fun hashString(input: String): String {
        var hash = 5381
        for (c in input) {
            hash = (hash * 33) xor c.code
        }
        val hex = (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        var extended = hex
        val chunkSize = kotlin.math.ceil(input.length / 4.0).toInt().coerceAtLeast(1)
        for (i in 0 until 4) {
            val chunk = input.substring((i * chunkSize).coerceAtMost(input.length), ((i + 1) * chunkSize).coerceAtMost(input.length))
            var chunkHash = 5381
            for (c in chunk) chunkHash = (chunkHash * 33) xor c.code
            extended += (chunkHash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        }
        return extended
    }
}
