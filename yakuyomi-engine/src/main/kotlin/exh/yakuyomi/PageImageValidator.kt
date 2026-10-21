package exh.yakuyomi

import android.graphics.BitmapFactory

/**
 * Whether a page's bytes are worth decoding, and at what sample size.
 */
sealed interface PageImageCheck {
    /**
     * @param sampleSize power-of-two downscale passed to [BitmapFactory]; 1 means full resolution.
     * @param isTallFullRes true when a sliced tall page is being decoded at full resolution rather
     *   than downscaled, because the pipeline re-slices it and would lose stroke detail.
     */
    data class Ok(
        val width: Int,
        val height: Int,
        val sampleSize: Int,
        val isTallFullRes: Boolean,
    ) : PageImageCheck

    /** [reason] is user-facing wording for the page's error state. */
    data class Rejected(val reason: String) : PageImageCheck
}

/**
 * The single definition of what counts as a translatable page image.
 *
 * These limits were enforced twice - once on the way into the queue and again inside the worker -
 * and the second copy could only ever pass, because the queue is only fed after the first check.
 * Keeping one copy means the size and dimension limits cannot drift apart.
 */
object PageImageValidator {

    /** Below this a "page" is almost certainly a truncated or failed download. */
    private const val MIN_BYTES = 1024L

    /** Above this the decode plus the pipeline's working copies risk an OOM. */
    private const val MAX_BYTES = 30L * 1024 * 1024

    private const val MAX_WIDTH = 10_000
    private const val MAX_HEIGHT = 10_000

    /** Tall pages are allowed extra height, but only while the pixel count stays affordable. */
    private const val MAX_SLICED_HEIGHT = 30_000
    private const val MAX_SLICED_PIXELS = 36_000_000L

    private const val SAMPLE_4X_THRESHOLD = 6_000
    private const val SAMPLE_2X_THRESHOLD = 4_096

    /**
     * Size-only rejection, for callers that guard before enqueuing and want to avoid the bounds
     * decode. Returns the user-facing reason, or null when the byte count alone is acceptable.
     */
    fun sizeRejection(imageBytes: ByteArray): String? = when {
        imageBytes.size > MAX_BYTES -> "Image too large"
        imageBytes.size < MIN_BYTES -> "Image too small or corrupted"
        else -> null
    }

    fun check(imageBytes: ByteArray, longPageSlicingEnabled: Boolean): PageImageCheck {
        sizeRejection(imageBytes)?.let { return PageImageCheck.Rejected(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        } catch (_: Exception) {
            return PageImageCheck.Rejected("Unable to decode image bounds")
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return PageImageCheck.Rejected("Invalid image dimensions ${width}x$height")
        }

        val pixelCount = width.toLong() * height.toLong()
        val isTallFullRes = longPageSlicingEnabled &&
            LongPageSlicer.shouldSlice(width, height) &&
            pixelCount in 1..MAX_SLICED_PIXELS

        val heightLimit = if (isTallFullRes) MAX_SLICED_HEIGHT else MAX_HEIGHT
        if (width !in 1..MAX_WIDTH || height !in 1..heightLimit) {
            return PageImageCheck.Rejected("Invalid image dimensions ${width}x$height")
        }

        val maxDimension = maxOf(width, height)
        val sampleSize = when {
            isTallFullRes -> 1
            maxDimension > SAMPLE_4X_THRESHOLD -> 4
            maxDimension > SAMPLE_2X_THRESHOLD -> 2
            else -> 1
        }
        return PageImageCheck.Ok(width, height, sampleSize, isTallFullRes)
    }
}
