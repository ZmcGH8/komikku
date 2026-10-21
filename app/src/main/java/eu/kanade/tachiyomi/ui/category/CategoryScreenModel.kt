package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.MergeCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetCategoryTrackStatus
import tachiyomi.domain.category.interactor.SetCategoryUpdateInterval
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategorySortOrder
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.service.CategoryReaderPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR

class CategoryScreenModel(
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val createCategoryWithName: CreateCategoryWithName = globalAppGraph.createCategoryWithName,
    private val deleteCategory: DeleteCategory = globalAppGraph.deleteCategory,
    private val reorderCategory: ReorderCategory = globalAppGraph.reorderCategory,
    private val renameCategory: RenameCategory = globalAppGraph.renameCategory,
    // KMK -->
    private val hideCategory: HideCategory = globalAppGraph.hideCategory,
    private val updateCategory: UpdateCategory = globalAppGraph.updateCategory,
    private val mergeCategories: MergeCategories = globalAppGraph.mergeCategories,
    private val getLibraryManga: GetLibraryManga = globalAppGraph.getLibraryManga,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
    private val setCategoryTrackStatus: SetCategoryTrackStatus = globalAppGraph.setCategoryTrackStatus,
    private val setCategoryUpdateInterval: SetCategoryUpdateInterval = globalAppGraph.setCategoryUpdateInterval,
    // KMK --> Per-category reading mode, so a shelf can pin the viewer (webtoon for long strips).
    private val categoryReaderPreferences: CategoryReaderPreferences = globalAppGraph.categoryReaderPreferences,
    // KMK <--
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            combine(
                getCategories.subscribe(),
                getLibraryManga.subscribe(),
            ) { categories, libraryManga ->
                val counts = mutableMapOf<Long, Int>()
                for (item in libraryManga) {
                    for (categoryId in item.categories) {
                        counts[categoryId] = (counts[categoryId] ?: 0) + 1
                    }
                }
                categories.filterNot(Category::isSystemCategory) to counts.toPersistentMap()
            }
                .collectLatest { (categories, counts) ->
                    mutableState.update {
                        when (it) {
                            CategoryScreenState.Loading -> CategoryScreenState.Success(
                                categories = categories.toImmutableList(),
                                mangaCounts = counts,
                                collapsedIds = libraryPreferences.categoryManagerCollapsedIds().get()
                                    .mapNotNull(String::toLongOrNull)
                                    .toSet(),
                                sortMode = libraryPreferences.categoryManagerSortMode().get(),
                            )
                            is CategoryScreenState.Success -> it.copy(
                                categories = categories.toImmutableList(),
                                mangaCounts = counts,
                            )
                        }
                    }
                }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            when (val r = createCategoryWithName.await(name)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                // KMK --> Remembered so deleteCategory can spot a create-then-delete.
                is CreateCategoryWithName.Result.Success -> noteCreated(r.category.id)
            }
        }
    }

    // KMK -->
    fun createSubcategory(name: String, parentId: Long) {
        screenModelScope.launch {
            when (val r = createCategoryWithName.await(name, parentId)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                is CreateCategoryWithName.Result.Success -> noteCreated(r.category.id)
            }
        }
    }
    // KMK <--

    // KMK -->
    fun hideCategory(category: Category) {
        screenModelScope.launch {
            when (hideCategory.await(category)) {
                is HideCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }
    // KMK <--

    fun deleteCategory(categoryId: Long) {
        screenModelScope.launch {
            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                // KMK -->
                else -> {
                    if (wasJustCreated(categoryId)) {
                        runCatching { mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("secret_ghost_category") }
                    }
                    if (lastCreatedCategoryId == categoryId) lastCreatedCategoryId = 0L
                }
                // KMK <--
            }
        }
    }

    // KMK --> "Create and immediately delete a category". Scoped to this screen model on purpose:
    // create and delete of the same id only happen here, so there is no cross-screen state to
    // keep and no risk of matching a delete against an unrelated older category.
    private var lastCreatedCategoryId: Long = 0L
    private var lastCreatedAtMs: Long = 0L

    private fun noteCreated(id: Long) {
        if (id <= 0L) return
        lastCreatedCategoryId = id
        lastCreatedAtMs = System.currentTimeMillis()
        runCatching {
            mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_weekly_category_2")
        }
    }

    private fun wasJustCreated(categoryId: Long): Boolean =
        categoryId > 0L && categoryId == lastCreatedCategoryId &&
            System.currentTimeMillis() - lastCreatedAtMs <= GHOST_CATEGORY_WINDOW_MS

    private companion object {
        /** Long enough to be "immediately", short enough not to catch a later tidy-up. */
        const val GHOST_CATEGORY_WINDOW_MS = 30_000L
    }
    // KMK <--

    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // KMK -->
    fun reparentSubcategory(category: Category, newParentId: Long, newIndex: Int) {
        screenModelScope.launch {
            val updateResult = updateCategory.await(CategoryUpdate(id = category.id, parentId = newParentId))
            if (updateResult is UpdateCategory.Result.Error) {
                _events.send(CategoryEvent.InternalError)
                return@launch
            }
            val updated = category.copy(parentId = newParentId)
            when (reorderCategory.await(updated, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }
    // KMK <--

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launch {
            when (renameCategory.await(category, name)) {
                is RenameCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // KMK -->
    fun setSearchQuery(query: String) {
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(searchQuery = query) else it
        }
    }

    fun setCategoryTrackStatus(categoryId: Long, trackStatus: Int?) {
        screenModelScope.launch {
            setCategoryTrackStatus.await(categoryId, trackStatus)
        }
    }

    fun setCategoryUpdateInterval(categoryId: Long, updateIntervalDays: Int?) {
        screenModelScope.launch {
            setCategoryUpdateInterval.await(categoryId, updateIntervalDays)
        }
    }

    // KMK -->
    /**
     * Sets (or clears, with [flag] null) the reading mode this category's manga open with.
     *
     * Stored on the subcategory's own key when it has a parent, so a subcategory can override its
     * parent's mode - which is the whole reason the preference has two shapes.
     */
    fun setCategoryReaderMode(category: Category, flag: Int?) {
        screenModelScope.launchIO {
            categoryReaderPreferences.setReaderMode(
                categoryId = category.id,
                mode = flag?.toString(),
                parentId = category.parentId,
            )
        }
    }

    /** The reading mode flag set on [category], or null when it has none of its own. */
    fun categoryReaderMode(category: Category): Int? {
        // Read through the same accessor the write goes through, so the two cannot disagree about
        // which key a subcategory uses.
        return categoryReaderPreferences.getReaderMode(category.id, category.parentId)?.toIntOrNull()
    }
    // KMK <--

    fun toggleCollapsed(categoryId: Long) {
        mutableState.update {
            if (it !is CategoryScreenState.Success) return@update it
            val collapsed = if (categoryId in it.collapsedIds) {
                it.collapsedIds - categoryId
            } else {
                it.collapsedIds + categoryId
            }
            libraryPreferences.categoryManagerCollapsedIds().set(collapsed.map(Long::toString).toSet())
            it.copy(collapsedIds = collapsed)
        }
    }

    fun expandAll() {
        libraryPreferences.categoryManagerCollapsedIds().set(emptySet())
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(collapsedIds = emptySet()) else it
        }
    }

    fun collapseAll() {
        mutableState.update {
            if (it !is CategoryScreenState.Success) return@update it
            val collapsed = it.categories.filter { category -> category.parentId == 0L }.map { it.id }.toSet()
            libraryPreferences.categoryManagerCollapsedIds().set(collapsed.map(Long::toString).toSet())
            it.copy(collapsedIds = collapsed)
        }
    }

    fun setSortMode(mode: Int) {
        libraryPreferences.categoryManagerSortMode().set(mode)
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(sortMode = mode) else it
        }
    }

    fun toggleSelectMode() {
        mutableState.update {
            if (it is CategoryScreenState.Success) {
                it.copy(selectMode = !it.selectMode, selectedIds = emptySet())
            } else {
                it
            }
        }
    }

    fun toggleSelection(categoryId: Long) {
        mutableState.update {
            if (it !is CategoryScreenState.Success) return@update it
            val selected = if (categoryId in it.selectedIds) {
                it.selectedIds - categoryId
            } else {
                it.selectedIds + categoryId
            }
            it.copy(selectedIds = selected)
        }
    }

    fun selectIds(ids: Set<Long>) {
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(selectedIds = ids) else it
        }
    }

    fun bulkSetHidden(hidden: Boolean) {
        val ids = selectedIds() ?: return
        screenModelScope.launch {
            var failed = false
            for (id in ids) {
                val result = updateCategory.await(CategoryUpdate(id = id, hidden = hidden))
                if (result is UpdateCategory.Result.Error) failed = true
            }
            if (failed) _events.send(CategoryEvent.InternalError)
            clearSelection()
        }
    }

    fun bulkDelete(ids: List<Long>) {
        screenModelScope.launch {
            var failed = false
            for (id in ids) {
                val result = deleteCategory.await(categoryId = id)
                if (result is DeleteCategory.Result.InternalError) failed = true
            }
            if (failed) _events.send(CategoryEvent.InternalError)
            clearSelection()
        }
    }

    fun bulkMove(ids: List<Long>, newParentId: Long) {
        screenModelScope.launch {
            val state = mutableState.value as? CategoryScreenState.Success ?: return@launch
            val byId = state.categories.associateBy { it.id }
            var appendIndex = state.categories.count { it.parentId == newParentId }
            var failed = false
            for (id in ids) {
                val category = byId[id] ?: continue
                if (category.parentId == newParentId) continue
                val updateResult = updateCategory.await(CategoryUpdate(id = id, parentId = newParentId))
                if (updateResult is UpdateCategory.Result.Error) {
                    failed = true
                    continue
                }
                val reorderResult = reorderCategory.await(category.copy(parentId = newParentId), appendIndex++)
                if (reorderResult is ReorderCategory.Result.InternalError) failed = true
            }
            if (failed) _events.send(CategoryEvent.InternalError)
            clearSelection()
        }
    }

    fun mergeInto(targetId: Long, sourceIds: List<Long>) {
        screenModelScope.launch {
            when (mergeCategories.await(targetId, sourceIds)) {
                is MergeCategories.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
            clearSelection()
        }
    }

    private fun selectedIds(): Set<Long>? {
        val state = mutableState.value as? CategoryScreenState.Success ?: return null
        return state.selectedIds.takeIf { it.isNotEmpty() }
    }

    private fun clearSelection() {
        mutableState.update {
            if (it is CategoryScreenState.Success) {
                it.copy(selectMode = false, selectedIds = emptySet(), dialog = null)
            } else {
                it
            }
        }
    }
    // KMK <--

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    // KMK -->
    data class CreateSubcategory(val parent: Category) : CategoryDialog
    data class MoveSubcategories(val ids: List<Long>) : CategoryDialog
    data class MergeSubcategories(val ids: List<Long>) : CategoryDialog
    data class DeleteMany(val ids: List<Long>) : CategoryDialog
    // KMK <--
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: ImmutableList<Category>,
        val dialog: CategoryDialog? = null,
        // KMK -->
        val mangaCounts: ImmutableMap<Long, Int> = kotlinx.collections.immutable.persistentMapOf(),
        val searchQuery: String = "",
        val collapsedIds: Set<Long> = emptySet(),
        val sortMode: Int = CategoryManagerSort.MANUAL,
        val selectMode: Boolean = false,
        val selectedIds: Set<Long> = emptySet(),
        // KMK <--
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}

// KMK -->
object CategoryManagerSort {
    const val MANUAL = 0
    const val AZ = 1
    const val ZA = 2
    const val MOST_ITEMS = 3
    const val LEAST_ITEMS = 4
    const val DATE_ADDED_NEWEST = 5
    const val DATE_ADDED_OLDEST = 6
    const val DATE_MODIFIED_NEWEST = 7
    const val DATE_MODIFIED_OLDEST = 8
}

// Persisted as a raw Int, so new modes must be appended and never renumbered.
// The count-based modes order the library's tabs by how many entries each group holds, which
// the grouped tabs can do and a bare category list cannot - so they map through and are applied
// by the caller holding the counts, not dropped to manual here.
fun categorySortOrderOf(mode: Int): CategorySortOrder = when (mode) {
    CategoryManagerSort.AZ -> CategorySortOrder.NAME_ASC
    CategoryManagerSort.ZA -> CategorySortOrder.NAME_DESC
    CategoryManagerSort.MOST_ITEMS -> CategorySortOrder.MOST_ITEMS
    CategoryManagerSort.LEAST_ITEMS -> CategorySortOrder.LEAST_ITEMS
    CategoryManagerSort.DATE_ADDED_NEWEST -> CategorySortOrder.DATE_ADDED_NEWEST
    CategoryManagerSort.DATE_ADDED_OLDEST -> CategorySortOrder.DATE_ADDED_OLDEST
    CategoryManagerSort.DATE_MODIFIED_NEWEST -> CategorySortOrder.DATE_MODIFIED_NEWEST
    CategoryManagerSort.DATE_MODIFIED_OLDEST -> CategorySortOrder.DATE_MODIFIED_OLDEST
    else -> CategorySortOrder.MANUAL
}
// KMK <--
