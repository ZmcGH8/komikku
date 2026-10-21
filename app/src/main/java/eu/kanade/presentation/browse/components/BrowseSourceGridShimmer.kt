package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ComfortableGridItemShimmer
import eu.kanade.presentation.components.CompactGridItemShimmer
import eu.kanade.presentation.library.components.CommonMangaItemDefaults

/**
 * Placeholder cells for a source's manga grid while it is loading.
 *
 * The grid layouts used to reuse [BrowseSourceLoadingItem], which is a list row - a 56x80 cover
 * beside text lines - so an empty grid showed three full-width rows and then snapped into a grid of
 * covers. Same complaint the library's shimmer documents: the placeholder has to be the layout it
 * stands in for, or the content re-flows the moment real data lands.
 *
 * That was only half fixed. The first page got these grid-shaped cells, but paginating still
 * appended [BrowseSourceLoadingItem] as a full-span footer, so the grid turned into three list rows
 * every time the next page arrived - visible as soon as the user scrolls to the end.
 *
 * Spacing matches [BrowseSourceCompactGrid] and [BrowseSourceComfortableGrid] so the cells land on
 * the same grid the real items will.
 */
@Composable
internal fun BrowseSourceCompactGridShimmer(
    columns: GridCells,
    contentPadding: PaddingValues,
    itemCount: Int = 18,
) {
    BrowseSourceGridShimmer(columns, contentPadding, itemCount) { CompactGridItemShimmer() }
}

@Composable
internal fun BrowseSourceComfortableGridShimmer(
    columns: GridCells,
    contentPadding: PaddingValues,
    usePanoramaCover: Boolean = false,
    itemCount: Int = 18,
) {
    BrowseSourceGridShimmer(columns, contentPadding, itemCount) {
        ComfortableGridItemShimmer(panorama = usePanoramaCover)
    }
}

@Composable
private fun BrowseSourceGridShimmer(
    columns: GridCells,
    contentPadding: PaddingValues,
    itemCount: Int,
    item: @Composable () -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding + PaddingValues(8.dp)),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(itemCount) { item() }
    }
}

/**
 * One row of grid-shaped placeholder cells, added to a live grid while a page is in flight.
 *
 * Ordinary single-column items rather than one full-span footer, so the placeholders sit on the real
 * columns and the arriving covers land on top of them instead of below a stack of list rows.
 *
 * [count] is the measured column count, not the caller's `GridCells`: `Fixed` exposes no count and
 * `Adaptive` only resolves against a width. It is 0 until the first measure, which draws nothing
 * for one frame rather than guessing a wrong width.
 */
internal fun LazyGridScope.browseSourceCompactGridLoadingCells(count: Int) {
    browseSourceLoadingCells(count) { CompactGridItemShimmer() }
}

internal fun LazyGridScope.browseSourceComfortableGridLoadingCells(count: Int, usePanoramaCover: Boolean) {
    browseSourceLoadingCells(count) { ComfortableGridItemShimmer(panorama = usePanoramaCover) }
}

private fun LazyGridScope.browseSourceLoadingCells(
    count: Int,
    cell: @Composable () -> Unit,
) {
    if (count <= 0) return
    // String keys so they cannot collide with the real items' index keys.
    items(count = count, key = { "browse-source-loading-$it" }) { cell() }
}
