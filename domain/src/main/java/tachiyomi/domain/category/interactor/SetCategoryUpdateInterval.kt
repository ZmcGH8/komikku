package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class SetCategoryUpdateInterval(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long, updateIntervalDays: Int?) {
        if (categoryId == Category.UNCATEGORIZED_ID) return
        categoryRepository.updateUpdateInterval(categoryId, updateIntervalDays)
    }
}
