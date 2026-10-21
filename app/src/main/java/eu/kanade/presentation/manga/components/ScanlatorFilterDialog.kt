package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.domain.chapter.model.ScanlatorPriority
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ScanlatorFilterDialog(
    availableScanlators: ImmutableSet<String>,
    excludedScanlators: ImmutableSet<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    // KMK -->
    // Custom build: preferred scanlator order for duplicate chapters
    scanlatorPriority: List<String> = emptyList(),
    chapterCounts: Map<String, Int> = emptyMap(),
    onConfirmPriority: ((List<String>) -> Unit)? = null,
    // KMK <--
) {
    val sortedAvailableScanlators = remember(availableScanlators/* KMK --> */, scanlatorPriority/* KMK <-- */) {
        availableScanlators.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            // KMK -->
            .sortedBy { ScanlatorPriority.rank(scanlatorPriority, it) ?: Int.MAX_VALUE }
        // KMK <--
    }
    val mutableExcludedScanlators = remember(excludedScanlators) { excludedScanlators.toMutableStateList() }
    // KMK -->
    val order = remember(sortedAvailableScanlators) { sortedAvailableScanlators.toMutableStateList() }
    var orderChanged by remember { mutableStateOf(false) }
    // KMK <--
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.exclude_scanlators)) },
        text = textFunc@{
            if (sortedAvailableScanlators.isEmpty()) {
                Text(text = stringResource(MR.strings.no_scanlators_found))
                return@textFunc
            }
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    // KMK -->
                    if (onConfirmPriority != null && order.size > 1) {
                        item(key = "priority-hint") {
                            Text(
                                text = stringResource(KMR.strings.custom_scanlator_priority_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                            )
                        }
                    }
                    itemsIndexed(
                        items = order,
                        contentType = { _, _ -> "item" },
                        key = { _, scanlator -> scanlator },
                    ) { index, scanlator ->
                        // KMK <--
                        val isExcluded = mutableExcludedScanlators.contains(scanlator)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    if (isExcluded) {
                                        mutableExcludedScanlators.remove(scanlator)
                                    } else {
                                        mutableExcludedScanlators.add(scanlator)
                                    }
                                }
                                .minimumInteractiveComponentSize()
                                .clip(MaterialTheme.shapes.small)
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.padding.small),
                        ) {
                            Icon(
                                imageVector = if (isExcluded) {
                                    Icons.Rounded.DisabledByDefault
                                } else {
                                    Icons.Rounded.CheckBoxOutlineBlank
                                },
                                tint = if (isExcluded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                                contentDescription = null,
                            )
                            Text(
                                text = scanlator,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(start = 24.dp)
                                    // KMK -->
                                    .weight(1f),
                                // KMK <--
                            )
                            // KMK -->
                            chapterCounts[scanlator]?.takeIf { it > 0 }?.let { count ->
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.extraSmall),
                                )
                            }
                            if (onConfirmPriority != null && order.size > 1) {
                                IconButton(
                                    onClick = {
                                        order.add(index - 1, order.removeAt(index))
                                        orderChanged = true
                                    },
                                    enabled = index > 0,
                                ) {
                                    Icon(imageVector = Icons.Outlined.ArrowDropUp, contentDescription = null)
                                }
                                IconButton(
                                    onClick = {
                                        order.add(index + 1, order.removeAt(index))
                                        orderChanged = true
                                    },
                                    enabled = index < order.lastIndex,
                                ) {
                                    Icon(imageVector = Icons.Outlined.ArrowDropDown, contentDescription = null)
                                }
                            }
                            // KMK <--
                        }
                    }
                }
                if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
        confirmButton = {
            if (sortedAvailableScanlators.isEmpty()) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            } else {
                FlowRow {
                    if (mutableExcludedScanlators.isEmpty()) {
                        TextButton(onClick = { mutableExcludedScanlators.addAll(availableScanlators) }) {
                            Text(text = stringResource(MR.strings.action_select_all))
                        }
                    } else {
                        TextButton(onClick = mutableExcludedScanlators::clear) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            onConfirm(mutableExcludedScanlators.toSet())
                            // KMK -->
                            if (orderChanged) onConfirmPriority?.invoke(order.toList())
                            // KMK <--
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
    )
}
