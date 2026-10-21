package tachiyomi.domain.manga.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Contains the required data for MangaCoverFetcher
 */
data class MangaCover(
    val mangaId: Long,
    val sourceId: Long,
    val isMangaFavorite: Boolean,
    // SY -->
    val ogUrl: String?,
    // SY <--
    val lastModified: Long,
) {
    // SY -->
    private val customThumbnailUrl = if (isMangaFavorite) {
        CustomMangaInfoLookup.resolve?.invoke(mangaId)?.thumbnailUrl
    } else {
        null
    }
    val url: String? = customThumbnailUrl ?: ogUrl
    // SY <--

    // KMK -->

    /**
     * [vibrantCoverColor] is used to set the color theme in manga detail page.
     * It contains color for all mangas, both in library or browsing.
     *
     * It reads/saves to a hashmap in [MangaCover.vibrantCoverColorMap] for multiple mangas.
     *
     * Writers run on Coil's fetch scope and on `launchIO`, readers run on the UI thread
     * and inside db mappers, so the backing map has to be concurrent - a plain `HashMap`
     * mutated from several `Dispatchers.IO` workers can hand back stale values or spin
     * forever on a corrupted bucket chain.
     *
     * Assigning null is a no-op: the map holds no nulls, so "absent" already means
     * "not extracted yet" and `null` is how that reads back through the getter.
     */
    var vibrantCoverColor: Int?
        get() = vibrantCoverColorMap[mangaId]
        set(value) {
            if (value == null) return
            if (vibrantCoverColorMap.size >= MAX_VIBRANT_COLOR_ENTRIES) {
                trimVibrantCoverColors()
            }
            vibrantCoverColorMap[mangaId] = value
        }

    /**
     * [dominantCoverColors] is used to set cover/text's color in Library (Favorite) grid view.
     * It contains only color for in-library (favorite) mangas.
     *
     * It reads/saves to a hashmap in [MangaCover.dominantCoverColorMap].
     *
     * Format: <first: cover color, second: text color>.
     *
     * Set in *[MangaCoverMetadata.setRatioAndColors]* whenever browsing meets a favorite manga
     *  by loading from *[CoverCache]*.
     *
     * Get in *[CommonMangaItem.MangaCompactGridItem]*, *[CommonMangaItem.MangaComfortableGridItem]* and
     *  *[CommonMangaItem.MangaListItem]*
     */
    @Suppress("KDocUnresolvedReference")
    var dominantCoverColors: Pair<Int, Int>?
        get() = dominantCoverColorMap[mangaId]
        set(value) {
            value ?: return
            dominantCoverColorMap[mangaId] = value.first to value.second
        }

    var ratio: Float?
        get() = coverRatioMap[mangaId]
        set(value) {
            value ?: return
            coverRatioMap[mangaId] = value
        }

    companion object {
        // KMK -->
        const val MIN_COVER_RATIO = 0.5f
        const val MAX_COVER_RATIO = 3f
        const val DEFAULT_COVER_RATIO = 2f / 3f
        // KMK <--

        /**
         * [vibrantCoverColorMap] store color generated while browsing library.
         * It always empty at beginning each time app starts, then add more color while browsing.
         *
         * Unlike [dominantCoverColorMap] it is never persisted, so it is bounded rather
         * than trimmed: once it grows past [MAX_VIBRANT_COLOR_ENTRIES] the oldest entries
         * are dropped. Insertion order is not tracked, so eviction is by the lowest
         * manga id, which is stable and cheap; a miss only costs one extra palette
         * extraction on the next cover display.
         */
        val vibrantCoverColorMap: ConcurrentHashMap<Long, Int> = ConcurrentHashMap()

        private const val MAX_VIBRANT_COLOR_ENTRIES = 2000

        /**
         * Halves [vibrantCoverColorMap] by dropping the lowest manga ids. Only reached
         * once per `MAX_VIBRANT_COLOR_ENTRIES / 2` insertions, so the sort is amortised
         * to nothing. Racy over-eviction is harmless - a missing entry only means the
         * palette is extracted again the next time the cover is drawn.
         */
        private fun trimVibrantCoverColors() {
            // Size is sampled once and the overflow is read from the key set rather than re-read:
            // several Coil fetch threads can pass the setter's size check at once, and re-reading
            // size here (as a second `size - HALF`) made the target move under them. Over-eviction
            // is harmless either way - a missing entry only means the palette is extracted again the
            // next time the cover is drawn - but each pass sorts the whole key set, so bounded,
            // predictable slices are what keep that off the fetch threads.
            val keys = vibrantCoverColorMap.keys
            val excess = keys.size - MAX_VIBRANT_COLOR_ENTRIES / 2
            if (excess <= 0) return
            keys.sorted().take(excess).forEach { vibrantCoverColorMap.remove(it) }
        }

        /**
         * [dominantCoverColorMap] stores favorite manga's cover & text's color as a joined string in Prefs.
         * They will be loaded each time *[App]* is initialized with *[MangaCoverMetadata.load]*.
         *
         * They will be saved back when *[MainActivity.onPause]* is triggered.
         */
        @Suppress("KDocUnresolvedReference")
        var dominantCoverColorMap = ConcurrentHashMap<Long, Pair<Int, Int>>()

        var coverRatioMap = ConcurrentHashMap<Long, Float>()
        // KMK <--
    }
}

fun Manga.asMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        ogUrl = thumbnailUrl,
        lastModified = coverLastModified,
    )
}
