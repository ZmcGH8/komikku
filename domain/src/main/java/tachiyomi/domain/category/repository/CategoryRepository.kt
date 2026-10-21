package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    suspend fun getAll(): List<Category>

    fun getAllAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    // SY -->
    suspend fun insert(category: Category): Long
    // SY <--

    suspend fun updatePartial(update: CategoryUpdate)

    suspend fun updatePartial(updates: List<CategoryUpdate>)

    suspend fun updateAllFlags(flags: Long?)

    suspend fun delete(categoryId: Long)

    // KMK -->
    suspend fun getSubcategories(parentId: Long): List<Category>

    fun getSubcategoriesAsFlow(parentId: Long): Flow<List<Category>>

    suspend fun getTopLevelCategories(): List<Category>

    suspend fun getMangaIdsInCategoryTree(categoryId: Long): List<Long>

    suspend fun deleteOrphanedSubcategories()

    // Separate from updatePartial because that path's coalesce(:param, col) idiom reads a
    // null as "leave alone", so it can never express unbinding a category. null here clears it.
    suspend fun updateTrackStatus(categoryId: Long, trackStatus: Int?)

    // Same reason as updateTrackStatus: NULL clears the interval so the category goes back to
    // inheriting the global one, which a coalesce-style update could not express.
    suspend fun updateUpdateInterval(categoryId: Long, updateInterval: Int?)
    // KMK <--
}
