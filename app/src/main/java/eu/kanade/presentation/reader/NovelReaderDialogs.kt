package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelSearchResult
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelSearchScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTocEntry
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTocScreenModel
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The reader's *contents* sheet for a text chapter.
 *
 * An [AdaptiveSheet] rather than a full screen, for the reason the chapter list is one too: it is
 * something the reader dips into and comes back from, and the text behind it should stay visible so
 * landing on a section reads as landing in the book.
 *
 * Entries outside the open chapter are shown but not tappable. A whole-book contents points at files
 * that live in other chapters, and silently doing nothing on tap reads as a broken button — so the
 * row is there, greyed, and the reader can see the book's structure.
 */
@Composable
fun NovelTocDialog(
    onDismissRequest: () -> Unit,
    screenModel: NovelTocScreenModel,
    onClickEntry: (NovelTocEntry) -> Unit,
) {
    val state by screenModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Open on the entry the reader is already in, rather than at the top of a long contents.
    LaunchedEffect(state) {
        val rows = (state as? NovelTocScreenModel.State.Loaded)?.rows ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.current }
        if (index >= 0) listState.scrollToItem(index)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(
                text = stringResource(KMR.strings.novel_contents),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // `state` is a delegated property, so the branches below cannot smart-cast it. Bound to the
            // when's own subject instead, which is a plain val and does cast.
            Box(modifier = Modifier.heightIn(min = 120.dp, max = 460.dp)) {
                when (val current = state) {
                    NovelTocScreenModel.State.Loading -> CenteredSpinner()

                    is NovelTocScreenModel.State.Error -> CenteredMessage(
                        stringResource(KMR.strings.novel_error_chapter_not_found),
                    )

                    is NovelTocScreenModel.State.Loaded -> if (!current.hasReachable) {
                        // A contents that lists nothing from the chapter on screen is a different
                        // state from one that is genuinely empty, and conflating them tells the reader
                        // their book has no contents when it has plenty - just not this chapter's.
                        CenteredMessage(
                            if (current.rows.isEmpty()) {
                                stringResource(KMR.strings.novel_contents_empty)
                            } else {
                                stringResource(KMR.strings.novel_contents_no_reachable)
                            },
                        )
                    } else {
                        ScrollbarLazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            itemsIndexed(current.rows, key = { index, row -> "toc-$index-${row.document}-${row.fragment}" }) { _, row ->
                                TocRow(row = row, onClick = { onClickEntry(row) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocRow(row: NovelTocEntry, onClick: () -> Unit) {
    val alpha = if (row.reachable) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.reachable, onClick = onClick)
            .padding(start = 16.dp + INDENT_PER_DEPTH.dp * row.depth.coerceAtMost(MAX_INDENT_DEPTH), end = 16.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.current) FontWeight.Bold else null,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The reader's *find in chapter* sheet.
 *
 * Results are selectable text rather than a list of one-word rows: a snippet is the only way to tell
 * two matches of the same query apart, and a reader looking for a passage should be able to copy it
 * out of here.
 */
@Composable
fun NovelSearchDialog(
    onDismissRequest: () -> Unit,
    screenModel: NovelSearchScreenModel,
    onClickResult: (NovelSearchResult) -> Unit,
) {
    val query by screenModel.query.collectAsState()
    val results by screenModel.results.collectAsState()

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = screenModel::onQueryChange,
                singleLine = true,
                label = { Text(stringResource(KMR.strings.novel_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            when (val current = results) {
                NovelSearchScreenModel.Results.Idle -> CenteredMessage(
                    stringResource(KMR.strings.novel_search_idle),
                )

                NovelSearchScreenModel.Results.Searching -> CenteredSpinner()

                is NovelSearchScreenModel.Results.Error -> CenteredMessage(
                    current.error.message ?: stringResource(KMR.strings.novel_error_chapter_not_found),
                )

                is NovelSearchScreenModel.Results.Loaded -> if (current.rows.isEmpty()) {
                    CenteredMessage(stringResource(KMR.strings.novel_search_no_results))
                } else {
                    Column {
                        Text(
                            text = pluralStringResource(
                                KMR.plurals.novel_search_result_count,
                                current.rows.size,
                                current.rows.size,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            itemsIndexed(
                                items = current.rows,
                                key = { index, row -> "search-$index-${row.pageIndex}-${row.matchOffset}" },
                            ) { _, row ->
                                SearchResultRow(row = row, onClick = { onClickResult(row) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: NovelSearchResult, onClick: () -> Unit) {
    // SelectionContainer rather than selectable= so a long press starts a copy of the snippet instead
    // of a drag-select across rows, which is what makes a result list feel like a result list.
    SelectionContainer {
        Text(
            text = row.snippet,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CenteredSpinner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Horizontal nudge per nesting level. Indentation is the only structure a flat list can show. */
private const val INDENT_PER_DEPTH = 16

/**
 * Cap on the indentation.
 *
 * EPUB 3 nav documents nest six and seven deep without embarrassment, and a seventh-level indent
 * pushes a short label off the edge of a narrow screen. Beyond this, depth keeps counting — for
 * [NovelTocEntry.depth] and for anything that wants it — but the layout stops.
 */
private const val MAX_INDENT_DEPTH = 4
