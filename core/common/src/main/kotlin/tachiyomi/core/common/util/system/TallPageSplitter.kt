package tachiyomi.core.common.util.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import logcat.LogPriority
import logcat.logcat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Splits an image that is too tall for the decoder into vertically stacked segments.
 *
 * The decoder refuses any image taller than 16384px on a side (`kMaxDimension` in imagedecoder),
 * and the renderer refuses to resize above 8192px, so a long webtoon strip cannot be decoded at
 * all. Splitting keeps full resolution instead of scaling the strip down.
 */
object TallPageSplitter {

    /**
     * Row ranges covering [0, imageHeight), each at most [maxSegmentHeight] tall.
     *
     * Bisects rather than dividing into equal parts so a strip is never cut more times than it
     * needs to be: a 32k strip against an 8192 limit yields four 8k segments, while an equal-part
     * split would also produce four - but a 20k strip yields four 5k segments here against three
     * uneven ones, and even segments keep the reader's per-page overhead predictable.
     */
    fun segmentBounds(imageHeight: Int, maxSegmentHeight: Int): List<IntRange> {
        if (imageHeight <= 0 || maxSegmentHeight <= 0) return emptyList()
        if (imageHeight <= maxSegmentHeight) return listOf(0 until imageHeight)

        val bounds = mutableListOf<IntRange>()
        fun emit(top: Int, height: Int) {
            if (height <= maxSegmentHeight) {
                bounds += top until (top + height)
                return
            }
            val half = height / 2
            emit(top, half)
            emit(top + half, height - half)
        }
        emit(0, imageHeight)
        return bounds
    }

    /** Height of the image, or -1 when it cannot be read. */
    fun readHeight(imageFile: File): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return options.outHeight
    }

    /**
     * Splits [imageFile] into JPEG segments, passing each encoded one to [onSegment] in order.
     *
     * Returns false without invoking [onSegment] when the image already fits, so the caller can
     * treat a true result as "the original page is now a list of segments".
     *
     * [only] restricts the work to a single band, which is the difference between one decode and
     * encode and a dozen of them. Bands are decoded one at a time regardless, so a selective pass
     * costs a fraction of a full one rather than merely filtering its output. Callers restoring a
     * single segment use it when the cache cannot hold the whole strip - see
     * `HttpPageLoader.ensureSegment`.
     */
    fun split(
        imageFile: File,
        maxSegmentHeight: Int,
        only: Int? = null,
        onSegment: (index: Int, bytes: ByteArray) -> Unit,
    ): Boolean {
        val bounds = segmentBounds(readHeight(imageFile), maxSegmentHeight)
        if (bounds.size <= 1) return false
        if (only != null && only !in bounds.indices) return false

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoder = openRegionDecoder(imageFile) ?: run {
            logcat(LogPriority.ERROR) { "Tall page split: no region decoder for ${imageFile.name}" }
            return false
        }

        return try {
            val width = decoder.width
            for ((index, range) in bounds.withIndex()) {
                if (only != null && index != only) continue
                val rect = Rect(0, range.first, width, range.last + 1)
                // A segment that will not decode fails the whole split rather than being skipped.
                // The caller builds the chapter's page list from whatever arrives and maps it by
                // list position, so a partial result is a strip with a chunk missing that still
                // reports success - and every later segment then carries a page index that no
                // longer matches the index baked into its cache key. OOM is the realistic cause
                // here, which is exactly when a long strip is already under memory pressure.
                val bitmap = decoder.decodeRegion(rect, options)
                if (bitmap == null) {
                    logcat(LogPriority.ERROR) {
                        "Tall page split: segment $index of ${imageFile.name} failed to decode - aborting"
                    }
                    return false
                }
                val out = ByteArrayOutputStream()
                try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    onSegment(index, out.toByteArray())
                } finally {
                    bitmap.recycle()
                }
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Tall page split failed for ${imageFile.name}" }
            false
        } finally {
            decoder.recycle()
        }
    }

    private fun openRegionDecoder(imageFile: File): BitmapRegionDecoder? {
        val stream = imageFile.inputStream()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        } catch (_: IOException) {
            null
        } finally {
            runCatching { stream.close() }
        }
    }

    private const val JPEG_QUALITY = 100
}
