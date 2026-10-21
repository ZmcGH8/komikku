package tachiyomi.domain.category.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// KMK -->
/**
 * Custom build: a broad category that groups library categories, which then act as its sub-categories
 * (e.g. "Controller?" -> Summoner, Necromancer, Spirit Summoner, Beast Tamer).
 *
 * Sub-categories are stored by name so groups survive backups and restores, where category ids change.
 * Groups are kept in the order of the list.
 */
@Serializable
data class CategoryGroup(
    val name: String,
    val categories: List<String> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(value: String): List<CategoryGroup> {
            if (value.isBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<CategoryGroup>>(value) }.getOrDefault(emptyList())
        }

        fun encode(groups: List<CategoryGroup>): String = json.encodeToString(groups)
    }
}
// KMK <--
