package tachiyomi.domain.chapter.model

import exh.source.MERGED_SOURCE_ID
import tachiyomi.domain.manga.model.MergedMangaReference

// KMK -->
/**
 * Houri+ (custom build): "one source per chapter" for merged entries.
 *
 * When on, a merged entry whose dedupe is off shows each chapter number from a single source, picked by
 * source priority, so the official release is read until it catches up and the scan fills in after that.
 * When the merge has no explicit order (all priorities equal), official sources come first.
 * Set from the app at startup.
 */
object MergedChapterDefaults {
    @Volatile
    var onePerChapter: Boolean = true

    /** Resolves a source id to its name; set by the app. */
    @Volatile
    var sourceName: (Long) -> String? = { null }

    private val OFFICIAL_SOURCES = listOf(
        "webtoons.com", "webtoons", "webtoon", "line webtoon", "kagane", "tapas", "manga plus", "mangaplus",
        "comikey", "tappytoon", "lezhin", "lezhin comics", "inkr", "inkr comics", "azuki", "k manga",
        "manta", "pocket comics", "webnovel", "bilibili comics", "mangamo", "comic walker", "j-novel club",
        "viz", "zebrack",
    )

    fun isOfficialSource(sourceId: Long): Boolean {
        val name = sourceName(sourceId)?.trim()?.lowercase() ?: return false
        return OFFICIAL_SOURCES.any { name == it || name.startsWith("$it ") || name.startsWith("$it (") }
    }

    /**
     * Gives the merged sources an official-first priority when the merge has no explicit order
     * (all of its sources share the same priority). An explicit order is left as it is.
     */
    fun withDefaultPriority(references: List<MergedMangaReference>): List<MergedMangaReference> {
        val sources = references.filter { it.mangaSourceId != MERGED_SOURCE_ID }
        if (sources.map { it.chapterPriority }.distinct().size > 1) return references
        return references.map { reference ->
            if (reference.mangaSourceId == MERGED_SOURCE_ID) {
                reference
            } else {
                reference.copy(chapterPriority = if (isOfficialSource(reference.mangaSourceId)) 0 else 1)
            }
        }
    }
}
// KMK <--
