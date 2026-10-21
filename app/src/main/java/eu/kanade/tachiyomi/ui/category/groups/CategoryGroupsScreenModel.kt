package eu.kanade.tachiyomi.ui.category.groups

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.CategoryGroup
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
/** Custom build: manages broad categories and which categories (sub-categories) belong to them. */
class CategoryGroupsScreenModel(
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
) : StateScreenModel<CategoryGroupsScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            combine(
                libraryPreferences.categoryGroups().changes(),
                getCategories.subscribe(),
            ) { groups, categories ->
                CategoryGroup.decode(groups) to categories
                    .filterNot { it.isSystemCategory }
                    .sortedBy { it.order }
                    .map { it.name }
            }.collectLatest { (groups, categories) ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        groups = groups.toImmutableList(),
                        categories = categories.toImmutableList(),
                    )
                }
            }
        }
    }

    private val groups: List<CategoryGroup>
        get() = CategoryGroup.decode(libraryPreferences.categoryGroups().get())

    private fun save(groups: List<CategoryGroup>) {
        libraryPreferences.categoryGroups().set(CategoryGroup.encode(groups))
    }

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || groups.any { it.name == trimmed }) return
        save(groups + CategoryGroup(trimmed))
    }

    fun rename(group: CategoryGroup, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || groups.any { it.name == trimmed }) return
        save(groups.map { if (it.name == group.name) it.copy(name = trimmed) else it })
    }

    fun delete(group: CategoryGroup) {
        save(groups.filterNot { it.name == group.name })
    }

    fun move(group: CategoryGroup, offset: Int) {
        val list = groups.toMutableList()
        val from = list.indexOfFirst { it.name == group.name }
        val to = from + offset
        if (from == -1 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    fun setCategories(group: CategoryGroup, categories: Collection<String>) {
        val order = state.value.categories
        val sorted = categories.sortedBy { order.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        save(groups.map { if (it.name == group.name) it.copy(categories = sorted) else it })
    }

    fun showDialog(dialog: Dialog) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Dialog {
        data object Create : Dialog
        data class Rename(val group: CategoryGroup) : Dialog
        data class Delete(val group: CategoryGroup) : Dialog
        data class ChooseCategories(val group: CategoryGroup) : Dialog
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val groups: ImmutableList<CategoryGroup> = persistentListOf(),
        val categories: ImmutableList<String> = persistentListOf(),
        val dialog: Dialog? = null,
    )

    companion object {
        /** Keeps broad categories pointing at a category after it's renamed. */
        fun renameCategory(libraryPreferences: LibraryPreferences, oldName: String, newName: String) {
            val pref = libraryPreferences.categoryGroups()
            val groups = CategoryGroup.decode(pref.get())
            if (groups.none { oldName in it.categories }) return
            pref.set(
                CategoryGroup.encode(
                    groups.map { group ->
                        group.copy(categories = group.categories.map { if (it == oldName) newName else it })
                    },
                ),
            )
        }
    }
}
// KMK <--
