package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.Image
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Extracts the colour a cover-seeded theme is built from.
 *
 * Two callers need it and they must agree on the answer, otherwise the manga details
 * screen and the reader can end up tinted from different colours for the same cover:
 *
 * - [MangaCoverMetadata.setRatioAndColors], off Coil's fetch scope, for every cover
 *   displayed anywhere in the app;
 * - `MangaScreenModel.onCoverPaletteAvailable`, from the image the details screen has
 *   already decoded, so the theme appears without a second image request.
 *
 * Everything here is best-effort: covers arrive as hardware bitmaps, get recycled under
 * us, or are too large to sample cheaply, and none of that may crash a browse.
 *
 * ## One palette, two answers
 *
 * [colorsOf] returns both swatches from a single `Palette` build. `Palette.generate()` is the
 * expensive half - it quantises the image and scores every candidate - so exposing one getter per
 * swatch would make every favourite cover in the library pay for the whole thing twice just to read
 * two fields out of the same object.
 */
object CoverPaletteExtractor {

    /**
     * Covers are decoded at display size, which is already modest, but a panorama cover
     * spans the full screen width and a custom cover can be arbitrarily large. Scaling
     * before `Palette` runs keeps the cost flat regardless of source resolution, and
     * palette generation only needs region averages, so the result is unchanged.
     */
    private const val MAX_SAMPLED_EDGE = 512

    /** Edge the cover is reduced to before its pixels are read; 24² samples is plenty. */
    private const val SAMPLE_GRID = 24

    /** Bits kept per channel when bucketing samples, so near-identical shades share a bucket. */
    private const val BUCKET_BITS = 4

    /** Samples below this alpha are skipped: a transparent cover has no colour to theme from. */
    private const val MIN_ALPHA = 0x80

    /**
     * The swatches a cover contributes.
     *
     * @param vibrant best available accent, tracked for every cover.
     * @param dominant dominant swatch plus the title colour the library grid draws over it,
     *   only meaningful for in-library covers.
     */
    data class CoverColors(
        val vibrant: Int?,
        val dominant: Pair<Int, Int>?,
    )

    /**
     * Both swatches from one palette build, or null when the cover cannot be sampled at all.
     *
     * Accepts an already-decoded bitmap so [MangaCoverMetadata] does not have to wrap it in a
     * [BitmapImage] only for this to unwrap it again.
     */
    fun colorsOf(bitmap: Bitmap?): CoverColors? =
        withPalette(bitmap) { sampled, palette ->
            CoverColors(vibrantOf(sampled, palette), dominantOf(palette))
        }

    /**
     * The seed the cover-seeded theme is built from.
     *
     * Android 12 and up read the accent through [getBestColor], which weighs swatch population and
     * saturation and is what the rest of the per-platform colour code agrees with.
     *
     * Below Android 12 there is no system palette to be consistent with, so the accent is taken
     * straight from the cover's own pixels by [representativeColorOf] rather than from a swatch
     * score. A single pixel would be too fragile - one sample of a highlight, a caption or a
     * border decides the colour of the whole page - so the pixels are reduced to a small grid and
     * the most populated colour bucket is averaged. That is the cheapest thing that is both
     * deterministic and stable under re-decodes.
     *
     * The palette result stays as a fallback for the case where the cover cannot be sampled that
     * way, so a cover never leaves the page without a seed.
     */
    private fun vibrantOf(sampled: Bitmap, palette: Palette): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bestOf(palette)
        } else {
            representativeColorOf(sampled) ?: bestOf(palette)
        }

    /**
     * Best available accent, or null only when the palette holds no usable swatch at all.
     *
     * [getBestColor] weighs swatch population and saturation, but every branch of it can still come
     * back empty - a cover made of one flat tone quantises to a handful of swatches, none of which
     * may qualify. A null here is not cosmetic: it is the seed itself, and the palette is not
     * rebuilt for a cover already in memory, so there is nothing to retry from. The individual
     * targets stand in behind it, ending on the dominant swatch because that one is chosen by
     * population and so is the likeliest to exist at all.
     */
    private fun bestOf(palette: Palette): Int? =
        palette.getBestColor()
            ?: palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb

    /**
     * The cover's dominant colour, as an opaque ARGB int, or null when it holds no opaque pixel.
     *
     * The bitmap is reduced to [SAMPLE_GRID] square first, so the cost is a fixed few hundred
     * samples whatever the cover's resolution. Buckets are 4 bits per channel: coarse enough that
     * a cover's near-identical shades land in one bucket, fine enough to keep distinct colours
     * apart. The winner is averaged rather than taken from a single member, which is what stops the
     * seed flickering as the cover is re-decoded at slightly different sizes.
     */
    private fun representativeColorOf(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val grid = runCatching {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_GRID, SAMPLE_GRID, true)
        }.getOrNull() ?: return null
        val ownsGrid = grid !== bitmap
        try {
            val pixels = IntArray(SAMPLE_GRID * SAMPLE_GRID)
            grid.getPixels(pixels, 0, SAMPLE_GRID, 0, 0, SAMPLE_GRID, SAMPLE_GRID)
            val bucketCount = 1 shl (BUCKET_BITS * 3)
            val counts = IntArray(bucketCount)
            val sumRed = IntArray(bucketCount)
            val sumGreen = IntArray(bucketCount)
            val sumBlue = IntArray(bucketCount)
            for (pixel in pixels) {
                if (pixel ushr 24 < MIN_ALPHA) continue
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                val bucket = (red shr (8 - BUCKET_BITS)) shl (BUCKET_BITS * 2) or
                    (green shr (8 - BUCKET_BITS)) shl BUCKET_BITS or
                    (blue shr (8 - BUCKET_BITS))
                counts[bucket]++
                sumRed[bucket] += red
                sumGreen[bucket] += green
                sumBlue[bucket] += blue
            }
            var best = -1
            var bestCount = 0
            for (bucket in 0 until bucketCount) {
                if (counts[bucket] > bestCount) {
                    bestCount = counts[bucket]
                    best = bucket
                }
            }
            if (best < 0) return null
            val meanRed = sumRed[best] / bestCount
            val meanGreen = sumGreen[best] / bestCount
            val meanBlue = sumBlue[best] / bestCount
            return (0xFF shl 24) or (meanRed shl 16) or (meanGreen shl 8) or meanBlue
        } finally {
            if (ownsGrid) grid.recycle()
        }
    }

    /**
     * The Coil overload.
     *
     * [coil3.Image] is a sealed-ish hierarchy rather than a bitmap: Coil 3 decodes into a
     * *hardware* bitmap by default, which arrives here as a `HardwareImage` rather than a
     * [BitmapImage]. Casting and giving up - which is what this did before - meant the cover
     * palette silently never resolved for any cover loaded through Coil without an explicit
     * `allowHardware(false)`, so cover-seeded theming was a no-op on most devices. A hardware
     * image is rendered into a software bitmap instead, which is cheap (the GPU already holds
     * the pixels) and is the only way `Palette` - which reads through `getPixels()` - can see it.
     */
    fun colorsOf(image: Image): CoverColors? {
        (image as? BitmapImage)?.let { return colorsOf(it.bitmap) }
        val rendered = renderSoftware(image) ?: return null
        // Owned by this function, unlike a BitmapImage's bitmap, which belongs to Coil.
        return try {
            colorsOf(rendered)
        } finally {
            rendered.recycle()
        }
    }

    private fun dominantOf(palette: Palette): Pair<Int, Int>? =
        palette.dominantSwatch?.let { swatch -> swatch.rgb to swatch.titleTextColor }

    /**
     * Draws [image] into a software bitmap small enough to quantise.
     *
     * Returns null when the image has no area or refuses to draw.
     */
    private fun renderSoftware(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        return runCatching {
            // Scaled on the way out rather than after: `Palette` only ever reads a
            // MAX_SAMPLED_EDGE image, so materialising a panorama header at full size first
            // would allocate megabytes of ARGB_8888 on the IO dispatcher purely to throw all but
            // 512px of it away.
            val scale = minOf(
                1f,
                MAX_SAMPLED_EDGE.toFloat() / maxOf(width, height),
            )
            Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).also { rendered ->
                val canvas = Canvas(rendered)
                if (scale != 1f) canvas.scale(scale, scale)
                image.draw(canvas)
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Could not render cover image for palette extraction" }
        }.getOrNull()
    }

    private inline fun <T> withPalette(bitmap: Bitmap?, select: (Bitmap, Palette) -> T?): T? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return runCatching {
            val sampled = downsampleForPalette(bitmap)
            try {
                select(sampled, Palette.from(sampled).generate())
            } finally {
                if (sampled !== bitmap) sampled.recycle()
            }
        }.getOrNull()
    }

    private fun downsampleForPalette(bitmap: Bitmap): Bitmap {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            // Palette reads through getPixels(), which a hardware bitmap cannot serve.
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return bitmap
        } else {
            bitmap
        }

        if (software.width <= MAX_SAMPLED_EDGE && software.height <= MAX_SAMPLED_EDGE) return software

        val scale = minOf(
            MAX_SAMPLED_EDGE.toFloat() / software.width,
            MAX_SAMPLED_EDGE.toFloat() / software.height,
        )
        val scaled = Bitmap.createScaledBitmap(
            software,
            (software.width * scale).toInt().coerceAtLeast(1),
            (software.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== software) software.recycle()
        return scaled
    }
}
