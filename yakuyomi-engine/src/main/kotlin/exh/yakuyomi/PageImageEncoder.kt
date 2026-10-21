package exh.yakuyomi

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Single definition of how page bitmaps become bytes for the pipeline output and for vision
 * providers.
 *
 * Three copies of the JPEG encode lived across the local and cloud translators and the manager,
 * each with its own quality and downscale threshold, so the bytes a provider received depended on
 * which provider happened to be selected.
 */
object PageImageEncoder {

    /** Longest edge allowed in a page image sent to a provider, in pixels. */
    private const val MAX_VISION_PIXELS = 2_000_000.0
    private const val MIN_VISION_EDGE = 512
    private const val JPEG_QUALITY = 80

    /** Encodes as WEBP for pipeline output and the on-disk caches. */
    fun toWebp(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /**
     * Encodes as JPEG for vision providers, downscaling first when the page exceeds
     * [MAX_VISION_PIXELS] so a full-resolution page cannot blow a provider's payload limit.
     */
    fun toJpeg(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return ByteArray(0)
        if (width.toDouble() * height.toDouble() <= MAX_VISION_PIXELS) {
            return compressJpeg(bitmap)
        }
        val scale = sqrt(MAX_VISION_PIXELS / (width.toDouble() * height.toDouble())).toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(MIN_VISION_EDGE)
        val targetHeight = (height * scale).toInt().coerceAtLeast(MIN_VISION_EDGE)
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true) }
            .getOrElse { return compressJpeg(bitmap) }
        return try {
            compressJpeg(scaled)
        } finally {
            // Never recycle the caller's bitmap; only the copy this method made.
            if (scaled !== bitmap) runCatching { scaled.recycle() }
        }
    }

    private fun compressJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
