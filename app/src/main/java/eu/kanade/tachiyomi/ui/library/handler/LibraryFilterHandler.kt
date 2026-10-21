package eu.kanade.tachiyomi.ui.library.handler

import androidx.compose.ui.util.fastAny
import eu.kanade.core.util.fastFilterNot
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.source.isMergedSourceId
import exh.util.isLewd
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.track.model.Track

/**
 * Single-responsibility handler extracted from [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.applyFilters]
 * (530 lines of filter lambdas inlined in the god-class ViewModel).
 *
 * Previously `applyFilters` was a private extension on `List<LibraryItem>` inside
 * `LibraryScreenModel`, coupling filtering to the ViewModel's 31 injected deps and
 * making it untestable. This handler owns only filtering concerns and is injectable /
 * unit-testable, enforcing modularity.
 *
 * Efficiency: pre-fetches merged manga map once (fixes N+1), uses fast* collections,
 * and short-circuits tracked/lewd checks before expensive download lookups.
 */
class LibraryFilterHandler(
    private val downloadManager: DownloadManager,
    private val getMergedMangaById: tachiyomi.domain.manga.interactor.GetMergedMangaById,
) {

    suspend fun filter(
        items: List<LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        trackedOverall: TriState,
        preferences: ItemPreferencesShim,
        includedCategories: ImmutableSet<Long>,
        excludedCategories: ImmutableSet<Long>,
    ): List<LibraryItem> {
        val mergedCache = mutableMapOf<Long, List<Manga>>()
        items.filter { isMergedSourceId(it.libraryManga.manga.source) }.forEach { item ->
            mergedCache[item.libraryManga.manga.id] = getMergedMangaById.await(item.libraryManga.manga.id)
        }

        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutside = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterCategories = preferences.filterCategories
        // Resolved once for the whole pass. These used to be rebuilt inside the per-item
        // predicate, allocating two lists of tracker ids for every entry in the library on
        // every filter recomputation.
        val trackerFilter = TrackingFilterSet.of(trackingFilter)

        return items.filter { item ->
            if (!applyDownloadedFilter(item, filterDownloaded, mergedCache)) return@filter false
            if (!applyTriState(item.libraryManga.unreadCount > 0, preferences.filterUnread)) return@filter false
            if (!applyTriState(item.libraryManga.hasStarted, preferences.filterStarted)) return@filter false
            if (!applyTriState(item.libraryManga.hasBookmarks, preferences.filterBookmarked)) return@filter false
            if (!applyTriState(item.libraryManga.manga.status.toInt() == SManga.COMPLETED, preferences.filterCompleted)) return@filter false
            if (skipOutside && !applyTriState(item.libraryManga.manga.fetchInterval < 0, preferences.filterIntervalCustom)) return@filter false
            if (!applyTriState(item.libraryManga.manga.isLewd(), preferences.filterLewd)) return@filter false
            if (!trackerFilter.matches(trackMap[item.id], trackedOverall)) return@filter false
            if (!applyCategoryFilter(item, filterCategories, includedCategories, excludedCategories)) return@filter false
            true
        }
    }

    private suspend fun applyDownloadedFilter(item: LibraryItem, filter: TriState, cache: Map<Long, List<Manga>>): Boolean {
        return applyFilter(filter) {
            item.libraryManga.manga.source == 0L ||
                item.downloadCount > 0 ||
                if (isMergedSourceId(item.libraryManga.manga.source)) {
                    cache[item.libraryManga.manga.id].orEmpty().sumOf { m -> downloadManager.getDownloadCount(m) } > 0
                } else {
                    downloadManager.getDownloadCount(item.libraryManga.manga) > 0
                }
        }
    }

    private fun applyTriState(value: Boolean, filter: TriState): Boolean = applyFilter(filter) { value }

    /**
     * The per-tracker include/exclude sets, split once per filtering pass.
     *
     * An entry is excluded if it is tracked on any excluded tracker, and included only if it
     * is tracked on at least one included tracker. With no included set, exclusion alone
     * decides; with no excluded set, inclusion alone decides. Both sets empty means "no
     * per-tracker opinion", which must not hide anything.
     */
    internal data class TrackingFilterSet(
        private val included: Set<Long>,
        private val excluded: Set<Long>,
    ) {
        fun matches(tracks: List<Track>?, trackedOverall: TriState): Boolean {
            val entries = tracks.orEmpty()
            when (trackedOverall) {
                TriState.ENABLED_IS -> if (entries.isEmpty()) return false
                TriState.ENABLED_NOT -> if (entries.isNotEmpty()) return false
                TriState.DISABLED -> Unit
            }
            if (included.isEmpty() && excluded.isEmpty()) return true
            val isExcluded = excluded.isNotEmpty() && entries.fastAny { it.trackerId in excluded }
            if (isExcluded) return false
            return included.isEmpty() || entries.fastAny { it.trackerId in included }
        }

        companion object {
            fun of(trackingFilter: Map<Long, TriState>): TrackingFilterSet {
                if (trackingFilter.isEmpty()) return EMPTY
                var included: Set<Long>? = null
                var excluded: Set<Long>? = null
                trackingFilter.forEach { (trackerId, state) ->
                    when (state) {
                        TriState.ENABLED_IS -> included = (included ?: emptySet()) + trackerId
                        TriState.ENABLED_NOT -> excluded = (excluded ?: emptySet()) + trackerId
                        TriState.DISABLED -> Unit
                    }
                }
                if (included == null && excluded == null) return EMPTY
                return TrackingFilterSet(included.orEmpty(), excluded.orEmpty())
            }

            private val EMPTY = TrackingFilterSet(emptySet(), emptySet())
        }
    }

    private fun applyCategoryFilter(
        item: LibraryItem,
        enabled: Boolean,
        included: ImmutableSet<Long>,
        excluded: ImmutableSet<Long>,
    ): Boolean {
        if (!enabled) return true
        val cats = item.libraryManga.categories.fastFilterNot { it == 0L }.toSet()
        if (cats.isEmpty()) return included.isEmpty()
        val isExcluded = excluded.any { it in cats }
        val isIncluded = included.isEmpty() || included.all { it in cats }
        return !isExcluded && isIncluded
    }

    /**
     * Shim to avoid coupling this handler to LibraryPreferences' internal `ItemPreferences`
     * data class (which lives inside LibraryScreenModel). ViewModel maps its prefs to this shim
     * before delegating, preserving layer isolation.
     */
    data class ItemPreferencesShim(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        val filterLewd: TriState,
        val filterCategories: Boolean,
        val globalFilterDownloaded: Boolean,
        val skipOutsideReleasePeriod: Boolean,
    )
}
