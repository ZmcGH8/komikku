package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChromeReaderMode
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import sh.calvin.reorderable.ReorderableCollectionItemScope
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Category management row.
 *
 * A fully-expanded row carries up to five icon buttons plus a checkbox, an expander and a drag
 * handle. That is ~340dp of fixed chrome, so on a 360dp phone - or in landscape on almost any
 * phone - the name was squeezed to zero width and the row read as a wall of icons. Below
 * [INLINE_ACTIONS_BREAKPOINT] the secondary actions collapse into an overflow menu, which keeps
 * the name legible and the delete affordance a deliberate, separate tap rather than the fourth
 * identical icon in a row.
 */
@Composable
fun ReorderableCollectionItemScope.CategoryListItem(
    category: Category,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    // KMK -->
    onHide: () -> Unit,
    onCreateSubcategory: (() -> Unit)? = null,
    isTopLevel: Boolean = true,
    subcategoryCount: Int = 0,
    showDragHandle: Boolean = true,
    mangaCount: Int = 0,
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    selected: Boolean? = null,
    onToggleSelection: (() -> Unit)? = null,
    onClickTrackStatus: (() -> Unit)? = null,
    onClickUpdateInterval: (() -> Unit)? = null,
    // KMK --> Per-category reading mode; `null` hides the action entirely.
    onClickReaderMode: (() -> Unit)? = null,
    // KMK <--
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        BoxWithConstraints {
            val useOverflow = maxWidth < INLINE_ACTIONS_BREAKPOINT

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { (onToggleSelection ?: onRename)() })
                    .padding(vertical = MaterialTheme.padding.small)
                    .padding(
                        start = MaterialTheme.padding.small,
                        end = MaterialTheme.padding.medium,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDragHandle) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(MaterialTheme.padding.medium)
                            .draggableHandle(),
                    )
                }
                if (selected != null && onToggleSelection != null) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                    )
                }
                if (expanded != null && onToggleExpand != null) {
                    IconButton(onClick = onToggleExpand) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
                if (isTopLevel && subcategoryCount > 0) {
                    BadgedBox(
                        badge = { Badge { Text(subcategoryCount.toString()) } },
                        modifier = Modifier.padding(end = MaterialTheme.padding.small),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    // KMK --> isolate the name so the count never reorders under bidi
                    text = if (mangaCount > 0) {
                        "${BidiFormatter.getInstance().unicodeWrap(category.name)} ($mangaCount)"
                    } else {
                        category.name
                    },
                    // KMK -->
                    color = LocalContentColor.current.let { if (category.hidden) it.copy(alpha = 0.6f) else it },
                    textDecoration = TextDecoration.LineThrough.takeIf { category.hidden },
                    // KMK <--
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (useOverflow) {
                    CategoryActionsOverflow(
                        onRename = onRename,
                        onHide = onHide,
                        onDelete = onDelete,
                        onCreateSubcategory = onCreateSubcategory?.takeIf { isTopLevel },
                        onClickTrackStatus = onClickTrackStatus,
                        onClickUpdateInterval = onClickUpdateInterval,
                        onClickReaderMode = onClickReaderMode,
                    )
                } else {
                    IconButton(onClick = onRename) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(MR.strings.action_rename_category),
                        )
                    }
                    // KMK -->
                    if (isTopLevel && onCreateSubcategory != null) {
                        IconButton(onClick = onCreateSubcategory) {
                            Icon(
                                imageVector = Icons.Outlined.CreateNewFolder,
                                contentDescription = stringResource(KMR.strings.action_create_subcategory),
                            )
                        }
                    }
                    IconButton(
                        onClick = onHide,
                        content = {
                            Icon(
                                imageVector = if (category.hidden) {
                                    Icons.Outlined.Visibility
                                } else {
                                    Icons.Outlined.VisibilityOff
                                },
                                contentDescription = stringResource(KMR.strings.action_hide),
                            )
                        },
                    )
                    if (onClickTrackStatus != null) {
                        IconButton(onClick = onClickTrackStatus) {
                            Icon(
                                imageVector = Icons.Outlined.Flag,
                                contentDescription = stringResource(KMR.strings.category_track_status),
                                // Same dimming the hidden state uses above, rather than a second
                                // glyph: an unbound category still needs this as its entry point, and
                                // one icon that reads as "not set" beats a near-identical pair.
                                tint = LocalContentColor.current.let {
                                    if (category.trackStatus != null) it else it.copy(alpha = 0.6f)
                                },
                            )
                        }
                    }
                    if (onClickUpdateInterval != null) {
                        IconButton(onClick = onClickUpdateInterval) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = stringResource(KMR.strings.category_update_interval),
                            )
                        }
                    }
                    if (onClickReaderMode != null) {
                        IconButton(onClick = onClickReaderMode) {
                            Icon(
                                imageVector = Icons.Outlined.ChromeReaderMode,
                                contentDescription = stringResource(KMR.strings.category_reader_mode),
                            )
                        }
                    }
                    // KMK <--
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryActionsOverflow(
    onRename: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onCreateSubcategory: (() -> Unit)?,
    onClickTrackStatus: (() -> Unit)? = null,
    onClickUpdateInterval: (() -> Unit)? = null,
    // KMK -->
    onClickReaderMode: (() -> Unit)? = null,
    // KMK <--
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(MR.strings.action_menu_overflow_description),
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.action_rename_category)) },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            onClick = {
                expanded = false
                onRename()
            },
        )
        // KMK -->
        if (onCreateSubcategory != null) {
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.action_create_subcategory)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreateSubcategory()
                },
            )
        }
        if (onClickTrackStatus != null) {
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.category_track_status)) },
                leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                onClick = {
                    expanded = false
                    onClickTrackStatus()
                },
            )
        }
        if (onClickUpdateInterval != null) {
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.category_update_interval)) },
                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                onClick = {
                    expanded = false
                    onClickUpdateInterval()
                },
            )
        }
        // KMK -->
        if (onClickReaderMode != null) {
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.category_reader_mode)) },
                leadingIcon = { Icon(Icons.Outlined.ChromeReaderMode, contentDescription = null) },
                onClick = {
                    expanded = false
                    onClickReaderMode()
                },
            )
        }
        // KMK <--
        DropdownMenuItem(
            text = { Text(stringResource(KMR.strings.action_hide)) },
            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
            onClick = {
                expanded = false
                onHide()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.action_delete)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            onClick = {
                expanded = false
                onDelete()
            },
        )
        // KMK <--
    }
}

/**
 * Width below which the row collapses its actions into an overflow menu.
 *
 * Sized against the widest possible row: drag handle (48) + checkbox (48) + expander (48) +
 * folder badge (24) + four icon buttons (192) + horizontal padding (~32) is ~390dp before the
 * name gets a single pixel. Anything narrower has to overflow.
 */
private val INLINE_ACTIONS_BREAKPOINT = 420.dp
