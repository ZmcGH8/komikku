package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.Json
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

// KMK -->
/**
 * Custom build: per-entry order of preferred scanlators. When "skip duplicate chapters" is on and the same
 * chapter exists from several scanlators, the reader uses the highest-ranked one.
 *
 * Stored in [LibraryPreferences.scanlatorPriority] keyed by source and url (stable across backups/restores,
 * unlike database ids).
 */
object ScanlatorPriority {
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(manga: Manga) = "${manga.source}|${manga.url}"

    private fun decode(value: String): Map<String, List<String>> {
        if (value.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, List<String>>>(value) }.getOrDefault(emptyMap())
    }

    fun get(preferences: LibraryPreferences, manga: Manga): List<String> {
        return decode(preferences.scanlatorPriority().get())[key(manga)].orEmpty()
    }

    fun set(preferences: LibraryPreferences, manga: Manga, order: List<String>) {
        val pref = preferences.scanlatorPriority()
        val map = decode(pref.get()).toMutableMap()
        if (order.isEmpty()) map.remove(key(manga)) else map[key(manga)] = order
        pref.set(json.encodeToString(map))
    }

    /** Rank of [scanlator] in [order] (0 = most preferred), or null when it isn't ranked. */
    fun rank(order: List<String>, scanlator: String?): Int? {
        val name = scanlator?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return order.indexOfFirst { it.trim().equals(name, ignoreCase = true) }.takeIf { it >= 0 }
    }
}
// KMK <--
