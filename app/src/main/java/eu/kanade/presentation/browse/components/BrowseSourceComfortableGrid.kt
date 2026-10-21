package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.R
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.RankedSearchMetadata
import exh.util.isLewd
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceComfortableGrid(
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    // KMK --> Owned by the screen, not here: a state created inside this composable belongs to its
    // composition group, and the shimmer below returns before it is ever created. See
    // `BrowseSourceContent`.
    gridState: LazyGridState,
    // KMK <--
    columns: GridCells,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // KMK -->
    selection: List<Manga>,
    usePanoramaCover: Boolean = false,
    // KMK <--
) {
    // First page in flight with nothing to show: draw the grid's own cells *instead of* the grid.
    // Nesting a lazy grid inside a lazy grid item throws, so this cannot be a full-span item.
    if (mangaList.loadState.refresh is LoadState.Loading && mangaList.itemCount == 0) {
        BrowseSourceComfortableGridShimmer(
            columns = columns,
            contentPadding = contentPadding,
            usePanoramaCover = usePanoramaCover,
        )
        return
    }

    // Read through a derivedStateOf so the count changing is the only thing that recomposes this
    // grid; reading layoutInfo directly would recompose on every scroll.
    val loadingCellCount by remember(gridState) { derivedStateOf { gridState.layoutInfo.maxSpan } }

    LazyVerticalGrid(
        state = gridState,
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (mangaList.loadState.prepend is LoadState.Loading) {
            browseSourceComfortableGridLoadingCells(
                count = loadingCellCount,
                usePanoramaCover = usePanoramaCover,
            )
        }

        items(count = mangaList.itemCount) { index ->
            // SY -->
            val pair by mangaList[index]?.collectAsState() ?: return@items
            val manga = pair.first
            val metadata = pair.second
            // SY <--

            BrowseSourceComfortableGridItem(
                manga = manga,
                // SY -->
                metadata = metadata,
                // SY <--
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                // KMK -->
                isSelected = selection.fastAny { selected -> selected.id == manga.id },
                usePanoramaCover = usePanoramaCover,
                // KMK <--
            )
        }

        // A refresh or a further page is a footer, not a placeholder for the whole grid - but it
        // still has to be grid-shaped, or the covers above it sit above three list rows.
        if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
            if (mangaList.itemCount > 0) {
                browseSourceComfortableGridLoadingCells(
                    count = loadingCellCount,
                    usePanoramaCover = usePanoramaCover,
                )
            }
        }
    }
}

@Composable
internal fun BrowseSourceComfortableGridItem(
    manga: Manga,
    // SY -->
    metadata: RaisedSearchMetadata?,
    // SY <--
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
    // KMK -->
    isSelected: Boolean = false,
    usePanoramaCover: Boolean,
    // KMK <--
) {
    MangaComfortableGridItem(
        title = manga.title,
        isLewd = manga.isLewd(),
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        // KMK -->
        isSelected = isSelected,
        usePanoramaCover = usePanoramaCover,
        fitToPanoramaCover = true,
        // KMK <--
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = {
            InLibraryBadge(enabled = manga.favorite)
        },
        // SY -->
        coverBadgeEnd = {
            if (metadata is MangaDexSearchMetadata) {
                metadata.followStatus?.let { followStatus ->
                    val text = LocalResources.current
                        .let { resources ->
                            remember(resources, followStatus) {
                                resources.getStringArray(R.array.md_follows_options)
                                    .getOrNull(followStatus)
                            }
                        }
                        ?: return@let
                    Badge(
                        text = text,
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
                metadata.relation?.let {
                    Badge(
                        text = stringResource(it.res),
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            } else if (metadata is RankedSearchMetadata) {
                metadata.rank?.let {
                    Badge(
                        text = "+$it",
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        },
        // SY <--
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
