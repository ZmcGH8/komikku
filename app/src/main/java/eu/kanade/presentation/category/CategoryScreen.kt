package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.category.components.ReaderModePickerDialog
import eu.kanade.presentation.category.components.TrackStatusPickerDialog
import eu.kanade.presentation.category.components.UpdateIntervalPickerDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.category.CategoryManagerSort
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import eu.kanade.tachiyomi.ui.category.categorySortOrderOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.sortedForDisplay
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun CategoryScreen(
    state: CategoryScreenState.Success,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    // KMK -->
    onClickHide: (Category) -> Unit,
    onCreateSubcategory: (Category) -> Unit,
    onReparentSubcategory: (Category, Long, Int) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    collapsedIds: Set<Long>,
    onToggleCollapsed: (Long) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    sortMode: Int,
    onSortMode: (Int) -> Unit,
    mangaCounts: ImmutableMap<Long, Int>,
    selectMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelectMode: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectVisible: (Set<Long>) -> Unit,
    onBulkHide: () -> Unit,
    onBulkShow: () -> Unit,
    onShowMoveDialog: () -> Unit,
    onShowMergeDialog: () -> Unit,
    onShowDeleteMany: () -> Unit,
    onSelectTrackStatus: (Category, Int?) -> Unit,
    onSelectUpdateInterval: (Category, Int?) -> Unit,
    // KMK --> Per-category reading mode. (category, flagValue|null) - null clears the override.
    onSelectReaderMode: (Category, Int?) -> Unit,
    readerModeFor: (Category) -> Int?,
    // KMK <--
    navigateUp: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.action_edit_categories),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onToggleSelectMode) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = stringResource(KMR.strings.category_manager_select),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            CategoryFloatingActionButton(
                lazyListState = lazyListState,
                onCreate = onClickCreate,
            )
        },
    ) { paddingValues ->
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_category,
                modifier = Modifier.padding(paddingValues),
            )
            return@Scaffold
        }

        CategoryContent(
            categories = state.categories,
            lazyListState = lazyListState,
            paddingValues = paddingValues,
            onClickRename = onClickRename,
            onClickDelete = onClickDelete,
            onChangeOrder = onChangeOrder,
            // KMK -->
            onClickHide = onClickHide,
            onCreateSubcategory = onCreateSubcategory,
            onReparentSubcategory = onReparentSubcategory,
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            collapsedIds = collapsedIds,
            onToggleCollapsed = onToggleCollapsed,
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            sortMode = sortMode,
            onSortMode = onSortMode,
            mangaCounts = mangaCounts,
            selectMode = selectMode,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
            onSelectVisible = onSelectVisible,
            onBulkHide = onBulkHide,
            onBulkShow = onBulkShow,
            onShowMoveDialog = onShowMoveDialog,
            onShowMergeDialog = onShowMergeDialog,
            onShowDeleteMany = onShowDeleteMany,
            onSelectTrackStatus = onSelectTrackStatus,
            onSelectUpdateInterval = onSelectUpdateInterval,
            onSelectReaderMode = onSelectReaderMode,
            readerModeFor = readerModeFor,
            // KMK <--
        )
    }
}

@Composable
private fun CategoryContent(
    categories: ImmutableList<Category>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    // KMK -->
    onClickHide: (Category) -> Unit,
    onCreateSubcategory: (Category) -> Unit,
    onReparentSubcategory: (Category, Long, Int) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    collapsedIds: Set<Long>,
    onToggleCollapsed: (Long) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    sortMode: Int,
    onSortMode: (Int) -> Unit,
    mangaCounts: ImmutableMap<Long, Int>,
    selectMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onSelectVisible: (Set<Long>) -> Unit,
    onBulkHide: () -> Unit,
    onBulkShow: () -> Unit,
    onShowMoveDialog: () -> Unit,
    onShowMergeDialog: () -> Unit,
    onShowDeleteMany: () -> Unit,
    onSelectTrackStatus: (Category, Int?) -> Unit,
    onSelectUpdateInterval: (Category, Int?) -> Unit,
    // KMK -->
    onSelectReaderMode: (Category, Int?) -> Unit,
    readerModeFor: (Category) -> Int?,
    // KMK <--
) {
    // KMK -->
    var trackStatusTarget by remember { mutableStateOf<Category?>(null) }
    var updateIntervalTarget by remember { mutableStateOf<Category?>(null) }
    var readerModeTarget by remember { mutableStateOf<Category?>(null) }
    // KMK <--
    val query = searchQuery.trim()
    // KMK --> Sort the whole list once, then split by level. Only the subcategories used to be
    // sorted here, so any non-manual mode put the top-level rows in database order while the
    // library's tabs used the selected one - which is what the two screens disagreed about.
    val sorted = remember(categories, sortMode, mangaCounts) {
        categories.sortedForDisplay(categorySortOrderOf(sortMode), mangaCounts)
    }
    val topLevel = remember(sorted) { sorted.filter { it.parentId == 0L } }
    val subMap = remember(sorted) { sorted.filter { it.parentId != 0L }.groupBy { it.parentId } }

    // Single flat list so every row is an individually reorderable item.
    // Searching ignores collapsed state and shows matching parents (with all
    // their subs for context) or matching subs under their parent header.
    val rows = remember(topLevel, subMap, query, collapsedIds, mangaCounts) {
        buildList {
            topLevel.forEach { parent ->
                val subs = subMap[parent.id].orEmpty()
                if (query.isEmpty()) {
                    val collapsed = parent.id in collapsedIds && subs.isNotEmpty()
                    add(CategoryRow(parent, isTopLevel = true, mangaCount = mangaCounts[parent.id] ?: 0, collapsed = collapsed))
                    if (!collapsed) {
                        subs.forEach { add(CategoryRow(it, isTopLevel = false, mangaCount = mangaCounts[it.id] ?: 0)) }
                    }
                } else {
                    val parentMatches = parent.name.contains(query, ignoreCase = true)
                    val matching = subs.filter { it.name.contains(query, ignoreCase = true) }
                    if (parentMatches || matching.isNotEmpty()) {
                        add(CategoryRow(parent, isTopLevel = true, mangaCount = mangaCounts[parent.id] ?: 0))
                        (if (parentMatches) subs else matching).forEach {
                            add(CategoryRow(it, isTopLevel = false, mangaCount = mangaCounts[it.id] ?: 0))
                        }
                    }
                }
            }
        }
    }
    // Manual drag-reorder only makes sense on the unfiltered, manually-sorted list.
    val reorderEnabled = query.isEmpty() && sortMode == CategoryManagerSort.MANUAL && !selectMode
    // KMK <--
    val rowState = remember { rows.toMutableStateList() }
    // KMK --> defer the DB commit until the drag ends: writing on every onMove
    // churns the categories flow mid-gesture and fights the drag, leaving rows stuck.
    var pendingDrop by remember { mutableStateOf<Pair<CategoryRow, Int>?>(null) }
    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        // KMK -->
        // from.index/to.index are ABSOLUTE LazyColumn indices (they include the two header
        // item()s above the category rows), so resolve rowState positions by stable item key
        // instead. Guard -1: the dragged row may hover the unwrapped header items, which have
        // no entry in rowState.
        val fromRow = rowState.indexOfFirst { it.category.key == from.key }
        val toRow = rowState.indexOfFirst { it.category.key == to.key }
        if (fromRow == -1 || toRow == -1) return@rememberReorderableLazyListState
        val moved = rowState.removeAt(fromRow)
        rowState.add(toRow, moved)
        pendingDrop = moved to toRow
        // KMK <--
    }

    fun commitDrop(moved: CategoryRow, dropIndex: Int) {
        if (!moved.isTopLevel) {
            var newParentId = moved.category.parentId
            for (i in dropIndex - 1 downTo 0) {
                if (rowState[i].isTopLevel) {
                    newParentId = rowState[i].category.id
                    break
                }
            }
            if (newParentId != moved.category.parentId) {
                var countBefore = 0
                for (i in 0 until dropIndex) {
                    if (!rowState[i].isTopLevel) {
                        var parentForI = moved.category.parentId
                        for (k in i - 1 downTo 0) {
                            if (rowState[k].isTopLevel) {
                                parentForI = rowState[k].category.id
                                break
                            }
                        }
                        if (parentForI == newParentId) countBefore++
                    }
                }
                onReparentSubcategory(moved.category, newParentId, countBefore.coerceAtLeast(0))
                return
            }
            val simpleIndex = rowState.take(dropIndex).count { !it.isTopLevel && it.category.parentId == moved.category.parentId }
            onChangeOrder(moved.category, simpleIndex.coerceAtLeast(0))
        } else {
            val siblingIndex = rowState.take(dropIndex).count { it.isTopLevel }
            onChangeOrder(moved.category, siblingIndex.coerceAtLeast(0))
        }
    }
    // KMK <--

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            pendingDrop?.let { (moved, index) ->
                pendingDrop = null
                commitDrop(moved, index)
            }
        }
    }

    LaunchedEffect(rows) {
        if (!reorderableState.isAnyItemDragging) {
            rowState.clear()
            rowState.addAll(rows)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = paddingValues +
            topSmallPaddingValues +
            PaddingValues(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        // KMK -->
        item(key = "category-manager-controls") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQuery,
                modifier = Modifier.fillMaxWidth().animateItem(),
                placeholder = { Text(stringResource(KMR.strings.category_manager_search)) },
                leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item(key = "category-manager-sort") {
            // KMK --> The dropdown owns the full width and the expand/collapse pair sits on its
            // own line. Sharing one row squeezed a translated dropdown label to nothing on a
            // 360dp phone while the two buttons kept their full intrinsic width.
            // KMK <--
            Column(modifier = Modifier.fillMaxWidth().animateItem()) {
                SortModeDropdown(sortMode = sortMode, onSortMode = onSortMode)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onExpandAll) {
                        Text(stringResource(KMR.strings.category_manager_expand_all))
                    }
                    TextButton(onClick = onCollapseAll) {
                        Text(stringResource(KMR.strings.category_manager_collapse_all))
                    }
                }
            }
        }
        if (selectMode) {
            item(key = "category-manager-bulk") {
                // KMK --> FlowRow, not Row: this used to lay six full-width TextButtons out on a
                // single line, so on any phone the last two were simply off-screen and
                // unreachable. Wrapping keeps every bulk action tappable at every width.
                // KMK <--
                FlowRow(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    TextButton(onClick = { onSelectVisible(rows.map { it.category.id }.toSet()) }) {
                        Text(stringResource(KMR.strings.category_manager_select))
                    }
                    TextButton(onClick = onBulkHide, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.action_hide))
                    }
                    TextButton(onClick = onBulkShow, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.category_manager_show))
                    }
                    TextButton(onClick = onShowMoveDialog, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.category_manager_move))
                    }
                    TextButton(onClick = onShowMergeDialog, enabled = selectedIds.size >= 2) {
                        Text(stringResource(KMR.strings.category_manager_merge))
                    }
                    TextButton(onClick = onShowDeleteMany, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                }
            }
        }
        // KMK <--
        items(
            items = rowState,
            key = { it.category.key },
        ) { row ->
            ReorderableItem(reorderableState, row.category.key) {
                // KMK -->
                val itemModifier = if (row.isTopLevel) {
                    Modifier.animateItem()
                } else {
                    Modifier.animateItem().padding(start = 16.dp)
                }
                CategoryListItem(
                    modifier = itemModifier,
                    category = row.category,
                    onRename = { onClickRename(row.category) },
                    onDelete = { onClickDelete(row.category) },
                    onHide = { onClickHide(row.category) },
                    onCreateSubcategory = ({ onCreateSubcategory(row.category) }).takeIf { row.isTopLevel },
                    isTopLevel = row.isTopLevel,
                    subcategoryCount = if (row.isTopLevel) subMap[row.category.id]?.size ?: 0 else 0,
                    showDragHandle = reorderEnabled,
                    mangaCount = row.mangaCount,
                    expanded = (!row.collapsed).takeIf { row.isTopLevel && (subMap[row.category.id]?.size ?: 0) > 0 },
                    onToggleExpand = ({ onToggleCollapsed(row.category.id) }).takeIf { row.isTopLevel },
                    selected = (row.category.id in selectedIds).takeIf { selectMode },
                    onToggleSelection = ({ onToggleSelection(row.category.id) }).takeIf { selectMode },
                    onClickTrackStatus = { trackStatusTarget = row.category },
                    onClickUpdateInterval = { updateIntervalTarget = row.category },
                    onClickReaderMode = { readerModeTarget = row.category },
                )
                // KMK <--
            }
        }
    }

    // KMK -->
    trackStatusTarget?.let { target ->
        TrackStatusPickerDialog(
            currentStatus = target.trackStatus,
            onSelect = { status ->
                onSelectTrackStatus(target, status)
                trackStatusTarget = null
            },
            onDismiss = { trackStatusTarget = null },
        )
    }
    updateIntervalTarget?.let { target ->
        UpdateIntervalPickerDialog(
            currentIntervalDays = target.updateInterval,
            onSelect = { days ->
                onSelectUpdateInterval(target, days)
                updateIntervalTarget = null
            },
            onDismiss = { updateIntervalTarget = null },
        )
    }
    readerModeTarget?.let { target ->
        ReaderModePickerDialog(
            currentFlag = readerModeFor(target),
            onSelect = { flag ->
                onSelectReaderMode(target, flag)
                readerModeTarget = null
            },
            onDismiss = { readerModeTarget = null },
        )
    }
    // KMK <--
}

// KMK -->
@Composable
private fun SortModeDropdown(sortMode: Int, onSortMode: (Int) -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val labels = mapOf(
        CategoryManagerSort.MANUAL to stringResource(KMR.strings.category_manager_sort_manual),
        CategoryManagerSort.AZ to stringResource(KMR.strings.category_manager_sort_az),
        CategoryManagerSort.ZA to stringResource(KMR.strings.category_manager_sort_za),
        CategoryManagerSort.MOST_ITEMS to stringResource(KMR.strings.category_manager_sort_most),
        CategoryManagerSort.LEAST_ITEMS to stringResource(KMR.strings.category_manager_sort_least),
        CategoryManagerSort.DATE_ADDED_NEWEST to stringResource(KMR.strings.category_manager_sort_date_added_newest),
        CategoryManagerSort.DATE_ADDED_OLDEST to stringResource(KMR.strings.category_manager_sort_date_added_oldest),
        CategoryManagerSort.DATE_MODIFIED_NEWEST to stringResource(KMR.strings.category_manager_sort_date_modified_newest),
        CategoryManagerSort.DATE_MODIFIED_OLDEST to stringResource(KMR.strings.category_manager_sort_date_modified_oldest),
    )
    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }) {
            Text("${stringResource(KMR.strings.category_manager_sort)}: ${labels[sortMode]}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            labels.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false
                        onSortMode(mode)
                    },
                )
            }
        }
    }
}
// KMK <--

private val Category.key inline get() = "category-$id"

// KMK -->
private data class CategoryRow(
    val category: Category,
    val isTopLevel: Boolean,
    val mangaCount: Int = 0,
    val collapsed: Boolean = false,
)
// KMK <--
