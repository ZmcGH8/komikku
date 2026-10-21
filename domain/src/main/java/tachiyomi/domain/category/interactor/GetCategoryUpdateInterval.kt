package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * How long an entry should wait before being updated again, given the categories it sits in.
 *
 * Returns null when no category the entry belongs to carries an interval of its own, which is the
 * normal case: the global interval already governs when the job runs at all, so applying one here
 * as well would change how every existing entry is scheduled. A per-category interval may only ever
 * narrow that, never loosen it.
 *
 * When several bound categories apply, the shortest wins. A user who asked for one category to
 * update daily should keep getting daily updates for an entry that is also filed under a monthly
 * one; taking the longest would let the monthly category silently override the daily one.
 */
@Inject
class GetCategoryUpdateInterval(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun intervalDaysFor(categoryIds: Set<Long>): Int? {
        if (categoryIds.isEmpty()) return null
        val intervals = categoryRepository.getAll()
            .mapNotNull { category ->
                category.updateInterval?.let { category.id to it }
            }
            .toMap()
        return intervalDaysFor(intervals, categoryIds)
    }

    companion object {

        /**
         * Pure overload for callers that already hold every category, which is what the update job
         * does - querying per entry would be one database round trip per manga in the library.
         */
        fun intervalDaysFor(intervalsByCategoryId: Map<Long, Int>, categoryIds: Set<Long>): Int? {
            if (categoryIds.isEmpty()) return null
            return categoryIds.mapNotNull { intervalsByCategoryId[it] }.minOrNull()
        }
    }
}
