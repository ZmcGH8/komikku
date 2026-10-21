package eu.kanade.tachiyomi.ui.category.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.category.model.CategoryGroup
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

// KMK -->
/**
 * Custom build: broad categories. Each one groups existing categories, which act as its sub-categories.
 * Sub-categories themselves are added, renamed, sorted and hidden in the regular category screen.
 */
class CategoryGroupsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CategoryGroupsScreenModel() }
        val state by screenModel.state.collectAsState()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        val lazyListState = rememberLazyListState()
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KMR.strings.custom_category_groups),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                CategoryFloatingActionButton(
                    lazyListState = lazyListState,
                    onCreate = { screenModel.showDialog(CategoryGroupsScreenModel.Dialog.Create) },
                )
            },
        ) { paddingValues ->
            if (state.groups.isEmpty()) {
                EmptyScreen(
                    stringRes = KMR.strings.custom_category_groups_empty,
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }

            LazyColumn(
                state = lazyListState,
                contentPadding = paddingValues + topSmallPaddingValues +
                    PaddingValues(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                itemsIndexed(state.groups, key = { _, group -> "category-group-${group.name}" }) { index, group ->
                    CategoryGroupItem(
                        modifier = Modifier.animateItem(),
                        group = group,
                        existingCategories = state.categories,
                        canMoveUp = index != 0,
                        canMoveDown = index != state.groups.lastIndex,
                        onMoveUp = { screenModel.move(group, -1) },
                        onMoveDown = { screenModel.move(group, 1) },
                        onChooseCategories = {
                            screenModel.showDialog(CategoryGroupsScreenModel.Dialog.ChooseCategories(group))
                        },
                        onRename = { screenModel.showDialog(CategoryGroupsScreenModel.Dialog.Rename(group)) },
                        onDelete = { screenModel.showDialog(CategoryGroupsScreenModel.Dialog.Delete(group)) },
                    )
                }
            }
        }

        val groupNames = remember(state.groups) { state.groups.map { it.name }.toImmutableList() }
        when (val dialog = state.dialog) {
            null -> {}
            CategoryGroupsScreenModel.Dialog.Create -> {
                CategoryCreateDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onCreate = screenModel::create,
                    categories = groupNames,
                    title = stringResource(KMR.strings.custom_category_group_add),
                )
            }
            is CategoryGroupsScreenModel.Dialog.Rename -> {
                CategoryRenameDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onRename = { screenModel.rename(dialog.group, it) },
                    categories = groupNames,
                    category = dialog.group.name,
                )
            }
            is CategoryGroupsScreenModel.Dialog.Delete -> {
                CategoryDeleteDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onDelete = { screenModel.delete(dialog.group) },
                    title = stringResource(KMR.strings.custom_category_group_delete),
                    text = stringResource(KMR.strings.custom_category_group_delete_confirmation, dialog.group.name),
                )
            }
            is CategoryGroupsScreenModel.Dialog.ChooseCategories -> {
                ChooseSubCategoriesDialog(
                    group = dialog.group,
                    groups = state.groups,
                    categories = state.categories,
                    onDismissRequest = screenModel::dismissDialog,
                    onConfirm = { screenModel.setCategories(dialog.group, it) },
                )
            }
        }
    }
}

@Composable
private fun CategoryGroupItem(
    modifier: Modifier,
    group: CategoryGroup,
    existingCategories: ImmutableList<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChooseCategories: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val subCategories = group.categories.filter { it in existingCategories }
    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onChooseCategories)
                .padding(
                    start = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Outlined.Folder, contentDescription = null)
            Column(modifier = Modifier.padding(start = MaterialTheme.padding.medium)) {
                Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subCategories.joinToString(" · ")
                        .ifEmpty { stringResource(KMR.strings.custom_category_group_none) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(imageVector = Icons.Outlined.ArrowDropUp, contentDescription = null)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(imageVector = Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onChooseCategories) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = stringResource(KMR.strings.custom_category_group_choose, group.name),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(KMR.strings.custom_category_group_rename),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(KMR.strings.custom_category_group_delete),
                )
            }
        }
    }
}

@Composable
private fun ChooseSubCategoriesDialog(
    group: CategoryGroup,
    groups: ImmutableList<CategoryGroup>,
    categories: ImmutableList<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember(group) { mutableStateListOf<String>().apply { addAll(group.categories) } }
    val otherGroups = remember(groups, group) {
        categories.associateWith { category ->
            groups.filter { it.name != group.name && category in it.categories }.joinToString { it.name }
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.custom_category_group_choose, group.name)) },
        text = {
            LazyColumn {
                itemsIndexed(categories, key = { _, name -> "sub-category-$name" }) { _, name ->
                    val checked = name in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (checked) selected.remove(name) else selected.add(name) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { if (it) selected.add(name) else selected.remove(name) },
                        )
                        Column {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                            otherGroups[name]?.takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    text = stringResource(KMR.strings.custom_category_group_other, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selected.toList())
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
// KMK <--
