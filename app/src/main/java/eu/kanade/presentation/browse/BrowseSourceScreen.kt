package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceEHentaiList
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.LibraryLayoutShimmer
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.isEhBasedSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    columns: GridCells,
    // KMK --> Raw column count for the loading skeleton; [columns] has no readable count until it
    // is measured. 0 means the adaptive strategy, which falls back to a sensible fixed width.
    columnsCount: Int = 0,
    // SY -->
    ehentaiBrowseDisplayMode: Boolean,
    // SY <--
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    // SY -->
    onWebViewClick: (() -> Unit)?,
    onHelpClick: (() -> Unit)?,
    onLocalSourceHelpClick: (() -> Unit)?,
    // SY <--
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // KMK -->
    selection: List<Manga>,
    // KMK <--
) {
    val context = LocalContext.current

    // KMK --> Created here, above every branch below, and handed to whichever one renders.
    //
    // A `rememberLazyGridState()` written inside a branch belongs to that branch's composition
    // group, so the moment the branch stops being taken the state leaves the composition and is
    // discarded — the grid comes back with a fresh state and scrolls to the top. Two branches in
    // this function swap on conditions that flip routinely: the first page loading, and the display
    // mode. A `rememberSaveable` is restored only if the value is re-registered before the restored
    // one is asked for, which a branch that is not composed never does.
    //
    // Owning it at the top of the screen makes the swap irrelevant: whichever branch renders, it
    // renders with the state that was already there. Both kinds exist because the grid layouts and
    // the list layouts do not share a state type, and only one is ever composed.
    // KMK <--
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        // KMK --> Real layout skeleton rather than a centred spinner: the first page of a source
        // is the slowest fetch in the app, and a bare spinner gave the user nothing to read while
        // it happened. Matching the selected layout also means the grid does not re-flow when the
        // results land.
        // KMK <--
        // Passed as contentPadding, not as modifier padding: LibraryLayoutShimmer
        // applies its own padding, so padding the modifier as well would inset
        // the placeholder by an extra 8dp and make the grid shift on arrival.
        LibraryLayoutShimmer(
            displayMode = if (ehentaiBrowseDisplayMode) LibraryDisplayMode.List else displayMode,
            columns = columnsCount.takeIf { it > 0 } ?: ADAPTIVE_SKELETON_COLUMNS,
            contentPadding = contentPadding,
        )
        // KMK <--
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource /* SY --> */ && onLocalSourceHelpClick != null /* SY <-- */) {
                persistentListOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOfNotNull(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = mangaList::refresh,
                    ),
                    // SY -->
                    if (onWebViewClick != null) {
                        EmptyScreenAction(
                            MR.strings.action_open_in_web_view,
                            icon = Icons.Outlined.Public,
                            onClick = onWebViewClick,
                        )
                    } else {
                        null
                    },
                    if (onHelpClick != null) {
                        EmptyScreenAction(
                            MR.strings.label_help,
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            onClick = onHelpClick,
                        )
                    } else {
                        null
                    },
                    // SY <--
                ).toImmutableList()
            },
        )

        return
    }

    // SY -->
    if (source?.isEhBasedSource() == true && ehentaiBrowseDisplayMode) {
        BrowseSourceEHentaiList(
            mangaList = mangaList,
            listState = listState,
            contentPadding = contentPadding,
            onMangaClick = onMangaClick,
            onMangaLongClick = onMangaLongClick,
            // KMK -->
            selection = selection,
            // KMK <--
        )
        return
    }
    // SY <--

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                gridState = gridState,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                // KMK <--
            )
        }
        // KMK -->
        LibraryDisplayMode.ComfortableGridPanorama -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                gridState = gridState,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                usePanoramaCover = true,
                // KMK <--
            )
        }
        LibraryDisplayMode.StaggeredGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                gridState = gridState,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                selection = selection,
            )
        }
        // KMK <--
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                listState = listState,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                // KMK <--
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                gridState = gridState,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                // KMK -->
                selection = selection,
                // KMK <--
            )
        }
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/** Column count used for the skeleton when the real grid is adaptive. */
private const val ADAPTIVE_SKELETON_COLUMNS = 3
