package eu.kanade.presentation.duplicates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.ui.duplicates.DuplicateMangaScreenModel
import exh.util.isLewd
import tachiyomi.domain.manga.interactor.DuplicateEntry
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

@Composable
fun DuplicateMangaScreen(
    state: DuplicateMangaScreenModel.State,
    onClickEntry: (DuplicateEntry) -> Unit,
) {
    when (state) {
        DuplicateMangaScreenModel.State.Loading -> LoadingScreen()

        DuplicateMangaScreenModel.State.Empty -> EmptyState()

        is DuplicateMangaScreenModel.State.Content -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(
                    items = state.entries,
                    key = { it.manga.id },
                ) { entry ->
                    val manga = entry.manga
                    MangaListItem(
                        coverData = MangaCoverModel(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            ogUrl = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        title = manga.title,
                        isLewd = manga.isLewd(),
                        onClick = { onClickEntry(entry) },
                        // Nothing here is selectable, so a long press has nothing to offer.
                        onLongClick = {},
                        badge = {
                            Text(
                                text = pluralStringResource(
                                    KMR.plurals.duplicate_manga_count,
                                    entry.duplicates.size,
                                    entry.duplicates.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Text(
        text = stringResource(KMR.strings.duplicate_manga_none),
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.padding.large),
        style = MaterialTheme.typography.bodyMedium,
    )
}
