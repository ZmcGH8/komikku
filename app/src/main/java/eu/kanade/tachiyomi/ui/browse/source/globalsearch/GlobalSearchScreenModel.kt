package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.Source

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }

        // KMK -->
        shouldPinnedSourcesHidden()
        // KMK <--
    }

    override fun getEnabledSources(): List<Source> {
        // KMK -->
        val category = state.value.sourceCategory
        // null means "no category chosen", which must not skip the pinned filter
        val memberIds = if (category.isBlank()) {
            null
        } else {
            // Membership lives in the "sourceId|category" pref set; Source has no
            // categories property, so match the id half.
            sourcesInCategories
                .mapNotNull { entry ->
                    entry.substringBefore('|')
                        .takeIf { entry.substringAfter('|') == category }
                        ?.toLongOrNull()
                }
                .toSet()
        }
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
            .filter { memberIds == null || it.id in memberIds }
        // KMK <--
    }
}
