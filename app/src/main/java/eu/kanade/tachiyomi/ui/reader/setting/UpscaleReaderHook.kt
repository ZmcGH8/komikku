package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Display-time upscale entry shared by the pager, webtoon, and WebGPU readers.
 * Returns upscaled WEBP bytes, or null when the page must be shown untouched.
 * Callers keep their original bytes on null, so translation and fallback paths
 * never observe this helper.
 */
object UpscaleReaderHook {

    /**
     * Whether [mangaId] would actually be upscaled. Cheap pref reads only - no byte
     * inspection, no session setup - so the reader can use it to skip capturing a
     * page's encoded bytes when upscaling cannot apply.
     */
    fun isUpscaleActive(mangaId: Long?): Boolean {
        if (mangaId == null || mangaId <= 0) return false
        return try {
            globalAppGraph.upscaleEngine.isEnabledForManga(mangaId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun upscaleDisplayBytes(mangaId: Long?, bytes: ByteArray?): ByteArray? {
        if (mangaId == null || mangaId <= 0 || bytes == null || bytes.isEmpty()) return null
        // Checked before the animated-image header parse below, which copies up to 64KB of the
        // page on the calling thread: callers hold a page's bytes for MTL alone far more often
        // than for upscaling, and then pay this for a guaranteed null.
        if (!isUpscaleActive(mangaId)) return null
        return try {
            if (ImageUtil.isAnimatedAndSupported(bytes)) return null
            globalAppGraph.upscaleEngine.upscaleIfNeeded(mangaId, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
