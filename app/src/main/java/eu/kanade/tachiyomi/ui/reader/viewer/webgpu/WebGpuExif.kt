// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import java.nio.ByteBuffer

// EXIF orientation for the WebGPU decode path.
//
// The native decoder hands back raw pixels, so a camera-scan page would display sideways and pair
// with the wrong aspect unless the buffer is rotated here. Only the single-frame path rotates: an
// animated frame stack carrying an orientation tag is vanishingly rare, and rotating every frame
// would multiply transient memory for it.

/**
 * Orientation tags are SHORT values, but readMetadata hands back the raw text; matching digits
 * keeps a stray-space or prefixed value from failing toIntOrNull. Compiled once - this sits on the
 * per-page decode path.
 */
internal val exifOrientationDigits = Regex("\\d+")

/** A rotated RGBA buffer with its new dimensions. Pure math, no Android dependency. */
internal data class RotatedRgba(val buffer: ByteBuffer, val width: Int, val height: Int)

/**
 * Reorients an RGBA [src] (4 bytes/pixel) per EXIF orientation 1-8, moving 4-byte units
 * opaquely. Returns the input untouched for orientation 1/out-of-range, degenerate dims, or a
 * short buffer - never throws. 5-8 swap axes. Single pass, no intermediate allocation.
 */
internal fun rotateRgbaForExif(src: ByteBuffer, width: Int, height: Int, orientation: Int): RotatedRgba {
    if (orientation !in 2..8 || width <= 0 || height <= 0) return RotatedRgba(src, width, height)
    val swapAxes = orientation >= 5
    val dstWidth = if (swapAxes) height else width
    val dstHeight = if (swapAxes) width else height
    if (dstWidth !in 1..SPREAD_MAX_DIM || dstHeight !in 1..SPREAD_MAX_DIM) {
        return RotatedRgba(src, width, height)
    }
    return try {
        val srcInts = src.duplicate().asIntBuffer()
        if (srcInts.remaining() < width * height) return RotatedRgba(src, width, height)
        val out = ByteBuffer.allocateDirect(dstWidth * dstHeight * 4)
        val outInts = out.asIntBuffer()
        for (dy in 0 until dstHeight) {
            for (dx in 0 until dstWidth) {
                val sx: Int
                val sy: Int
                when (orientation) {
                    2 -> {
                        sx = width - 1 - dx
                        sy = dy
                    }
                    3 -> {
                        sx = width - 1 - dx
                        sy = height - 1 - dy
                    }
                    4 -> {
                        sx = dx
                        sy = height - 1 - dy
                    }
                    5 -> {
                        sx = dy
                        sy = dx
                    }
                    6 -> {
                        sx = dy
                        sy = height - 1 - dx
                    }
                    7 -> {
                        sx = width - 1 - dy
                        sy = height - 1 - dx
                    }
                    else -> {
                        sx = width - 1 - dy
                        sy = dx
                    }
                }
                outInts.put(dy * dstWidth + dx, srcInts.get(sy * width + sx))
            }
        }
        out.rewind()
        RotatedRgba(out, dstWidth, dstHeight)
    } catch (_: OutOfMemoryError) {
        System.gc()
        RotatedRgba(src, width, height)
    } catch (_: Exception) {
        RotatedRgba(src, width, height)
    }
}
// KMK <--
