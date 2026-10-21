package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata.setRatioAndColors
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import mihon.app.di.globalAppGraph
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Object that holds info about a covers size ratio + dominant colors
 * @author Jays2Kings
 */
object MangaCoverMetadata {
    private val preferences by lazy { globalAppGraph.libraryPreferences }
    private val coverCache by lazy { globalAppGraph.coverCache }

    fun load() {
        val ratios = preferences.coverRatios().get()
        val loadedRatios = ConcurrentHashMap<Long, Float>(
            ratios.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val ratio = splits.lastOrNull()?.toFloatOrNull()
                if (id != null && ratio != null) {
                    id to ratio
                } else {
                    null
                }
            }.toMap(),
        )
        val colors = preferences.coverColors().get()
        val loadedColors = ConcurrentHashMap<Long, Pair<Int, Int>>(
            colors.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val color = splits.getOrNull(1)?.toIntOrNull()
                val textColor = splits.getOrNull(2)?.toIntOrNull()
                if (id != null && color != null) {
                    // A stored colour is only half an entry: consumers use the pair as
                    // container + content, so a missing or fully transparent text colour
                    // renders the cover's title invisible on top of the container colour.
                    // Derive the readable partner instead of defaulting it to 0 (transparent).
                    id to (color to (textColor ?: contrastingTextColor(color)))
                } else {
                    null
                }
            }.toMap(),
        )
        MangaCover.coverRatioMap = loadedRatios
        MangaCover.dominantCoverColorMap = loadedColors
        // Seeded from what was just restored so the first savePrefs() after a load is a no-op.
        // Left null instead, the very first ON_PAUSE would rewrite both prefs byte-for-byte with
        // the values load() only just read.
        lastSavedRatios = loadedRatios
        lastSavedColors = loadedColors
    }

    /**
     * WCAG relative luminance, used to pick black or white text for an arbitrary container colour.
     * `Palette.Swatch.titleTextColor` normally supplies this, so this is only reached for a
     * truncated, hand-edited or older-format pref entry.
     */
    private fun contrastingTextColor(background: Int): Int {
        val channel = { value: Int ->
            val srgb = value / 255.0
            if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
        }
        val luminance =
            0.2126 * channel(background shr 16 and 0xFF) +
                0.7152 * channel(background shr 8 and 0xFF) +
                0.0722 * channel(background and 0xFF)
        return if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    /**
     * [setRatioAndColors] generate cover's color & ratio by reading cover's bitmap from [CoverCache].
     * It's called along with [MangaCoverFetcher.fetch] everytime a cover is **displayed** (anywhere).
     *
     * When called:
     *  - It removes saved colors from saved Prefs of [MangaCover.dominantCoverColorMap] if manga is not favorite.
     *  - If a favorite manga already restored [MangaCover.dominantCoverColors] then it
     * will skip actually reading bitmap, only extract ratio. Except when [MangaCover.vibrantCoverColor]
     * is not loaded then it will read bitmap & extract vibrant color.
     * => always set [force] to true so it will always re-calculate ratio & color.
     *
     * Set [MangaCover.dominantCoverColors] for favorite manga only.
     * Set [MangaCover.vibrantCoverColor] for all mangas.
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     *
     * This is only for loading color first time it appears on Library/Browse. Any new colors caused by loading new
     * cover when open a manga detail or change cover will be updated separately on
     * [MangaScreenModel.onCoverPaletteAvailable].
     *
     * @author Jays2Kings, cuong-tran
     */
    fun setRatioAndColors(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyDominantColor: Boolean = true,
        force: Boolean = false,
    ) {
        if (!mangaCover.isMangaFavorite) {
            mangaCover.remove()
            if (mangaCover.vibrantCoverColor != null) return
        }

        if (mangaCover.isMangaFavorite && onlyDominantColor && mangaCover.dominantCoverColors != null) return

        val options = BitmapFactory.Options()

        val updateColors =
            (mangaCover.isMangaFavorite && mangaCover.dominantCoverColors == null) ||
                (!onlyDominantColor && mangaCover.vibrantCoverColor == null) ||
                force

        if (updateColors) {
            /*
             * + Manga is Favorite & doesn't have dominant color
             *   For non-favorite, it doesn't care if dominant is there or not, if it has vibrant color then it will
             *   already be returned from beginning.
             * + [onlyDominantColor] = false
             *   - Manga doesn't have vibrant color
             */
            options.inSampleSize = SUB_SAMPLE
        } else {
            /*
             * + [onlyDominantColor] = true
             *   - Manga is Favorite & already have dominant color
             * + [onlyDominantColor] = false
             *   - Manga is Favorite & already have dominant color & vibrant color
             *   - Manga is not Favorite & already have vibrant color (already skip at beginning)
             */
            // Just trying to update ratio without actual reading bitmap (bitmap will be null)
            options.inJustDecodeBounds = true
            // Don't even need to update ratio because we don't use it yet.
            return
        }

        val file = ogFile
            ?: coverCache.getCustomCoverFile(mangaCover.mangaId).takeIf { it.exists() }
            ?: coverCache.getCoverFile(mangaCover.url)

        if (!bitmapFactoryCanRead(file, bufferedSource)) return

        val bitmap = when {
            bufferedSource != null -> BitmapFactory.decodeStream(bufferedSource.inputStream(), null, options)
            file?.exists() == true -> BitmapFactory.decodeFile(file.path, options)
            else -> return
        } ?: return

        try {
            // One palette build for both swatches: quantising the cover is the expensive half,
            // and reading two fields out of it used to mean paying for it twice per cover.
            // Not an early return - the ratio below is derived from `options`, not the bitmap,
            // so it stays worth computing even when the cover itself cannot be sampled.
            val colors = CoverPaletteExtractor.colorsOf(bitmap)
            if (colors != null) {
                if (mangaCover.isMangaFavorite) {
                    colors.dominant?.let { (rgb, textColor) ->
                        mangaCover.dominantCoverColors = rgb to textColor
                    }
                }
                colors.vibrant?.let { color ->
                    mangaCover.vibrantCoverColor = color
                }
            }
        } finally {
            // Covers come from the shared bitmap pool; leaking here is what eventually
            // makes Coil skip decoding altogether.
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }

        val width = options.outWidth
        val height = options.outHeight
        // Guarded on > 0, not != -1: a 0 height divides to Infinity, which coerceIn silently
        // clamps to MAX_COVER_RATIO, and a NaN passes coerceIn through unchanged - either way
        // the stored ratio stops describing the cover.
        if (mangaCover.isMangaFavorite && width > 0 && height > 0) {
            val raw = width / height.toFloat()
            if (raw.isFinite()) {
                mangaCover.ratio = raw.coerceIn(MangaCover.MIN_COVER_RATIO, MangaCover.MAX_COVER_RATIO)
            }
        }
    }

    fun MangaCover.remove() {
        MangaCover.coverRatioMap.remove(mangaId)
        MangaCover.dominantCoverColorMap.remove(mangaId)
    }

    /**
     * Whether [BitmapFactory] can decode the cover [file] or [bufferedSource] points at.
     *
     * `BitmapFactory` has no JXL or JP2 decoder at any API level - the app reads those through
     * libvips instead. Handed one it opens the file, recognises nothing and returns null, and since
     * the dominant colour it was meant to produce never arrives, the guard that skips an
     * already-sampled cover never fires either. Every later bind tries the same doomed decode
     * again: for a library of such covers that was one wasted read-and-sniff per cover per scroll,
     * queued on the shared IO dispatcher right alongside the Coil requests it was competing with.
     * On a library of them the covers that should have loaded first were the ones held up.
     *
     * Answered from a 32-byte header read, which is exactly what the decode attempt used to do
     * before giving up, so the check costs a fraction of the work it replaces rather than adding
     * to it. An unreadable or unrecognised cover answers true, leaving the outcome to the decode
     * as it was before this existed.
     *
     * AVIF and HEIF are deliberately not listed: the platform decodes both on the API levels that
     * realistically meet them, so excluding them would drop colours that are obtainable.
     */
    private fun bitmapFactoryCanRead(file: File?, bufferedSource: BufferedSource?): Boolean {
        val type = try {
            when {
                bufferedSource != null -> ImageUtil.findImageType(bufferedSource.peek().inputStream())
                file?.exists() == true -> ImageUtil.findImageType(file.inputStream())
                // Nothing to read either way; the decode below answers this with its own `return`.
                else -> return true
            }
        } catch (_: Throwable) {
            null
        }
        return type !in NOT_DECODABLE_BY_BITMAP_FACTORY
    }

    /** Types `BitmapFactory` cannot decode on any API level. See [bitmapFactoryCanRead]. */
    private val NOT_DECODABLE_BY_BITMAP_FACTORY = setOf(
        ImageUtil.ImageType.JXL,
        ImageUtil.ImageType.JP2,
    )

    /**
     * Persists the ratio and colour maps, but only when something actually changed since the last
     * write.
     *
     * Called from [androidx.lifecycle.Lifecycle.Event.ON_PAUSE], which fires on every dialog,
     * permission prompt and Home press, not just when the user leaves. Both maps are serialised
     * into `StringSet` prefs (up to 2000 entries each) and trimmed on the way, so an unconditional
     * write makes a user who opens and immediately backgrounds the app pay a full serialise for
     * zero delta. The maps are `ConcurrentHashMap`s whose `contentEquals` is a cheap identity
     * check on size plus a hash of the entries.
     */
    fun savePrefs() {
        val ratioCopy = MangaCover.coverRatioMap.toMap()
        val colorCopy = MangaCover.dominantCoverColorMap.toMap()
        if (ratioCopy == lastSavedRatios && colorCopy == lastSavedColors) return
        val ratios = trimmed(ratioCopy)
        val colors = trimmed(colorCopy)
        MangaCover.coverRatioMap.clear()
        MangaCover.coverRatioMap.putAll(ratios)
        MangaCover.dominantCoverColorMap.clear()
        MangaCover.dominantCoverColorMap.putAll(colors)
        preferences.coverRatios().set(ratios.map { "${it.key}|${it.value}" }.toSet())
        preferences.coverColors().set(colors.map { "${it.key}|${it.value.first}|${it.value.second}" }.toSet())
        lastSavedRatios = ratios
        lastSavedColors = colors
    }

    @Volatile
    private var lastSavedRatios: Map<Long, Float>? = null

    @Volatile
    private var lastSavedColors: Map<Long, Pair<Int, Int>>? = null

    /**
     * Caps a map at [MAX_PERSISTED_ENTRIES] by keeping the highest manga ids.
     *
     * Ids are assigned at insert, so the highest ones are the most recently added. The cap is
     * lossy on purpose: [load] can only restore what was written, so an evicted entry has to be
     * re-extracted from its cover next time the grid draws it.
     */
    private fun <K : Comparable<K>, V> trimmed(map: Map<K, V>): Map<K, V> =
        if (map.size <= MAX_PERSISTED_ENTRIES) {
            map
        } else {
            map.keys.sorted().takeLast(MAX_PERSISTED_ENTRIES).associateWith { key -> map.getValue(key) }
        }

    private const val MAX_PERSISTED_ENTRIES = 2000

    private const val SUB_SAMPLE = 4
}
