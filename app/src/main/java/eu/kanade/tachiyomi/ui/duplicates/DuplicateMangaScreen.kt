package eu.kanade.tachiyomi.ui.duplicates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.duplicates.DuplicateMangaScreen
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.domain.manga.interactor.DuplicateEntry
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

class DuplicateMangaScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { DuplicateMangaScreenModel() }
        val state by screenModel.state.collectAsState()
        var selected by remember { mutableStateOf<DuplicateEntry?>(null) }

        AppBar(
            title = stringResource(KMR.strings.find_duplicates),
            navigateUp = navigator::pop,
        )

        DuplicateMangaScreen(
            state = state,
            onClickEntry = { selected = it },
        )

        // Reuses the existing duplicate review dialog rather than restating it: it already handles
        // opening either side and reporting how many chapters the duplicate carries.
        selected?.let { entry ->
            DuplicateMangaDialog(
                duplicates = entry.duplicates,
                targetManga = entry.manga,
                onDismissRequest = { selected = null },
                // Nothing is being added here, so confirming simply closes the review.
                onConfirm = { selected = null },
                onOpenManga = { navigator.push(MangaScreen(it.id)) },
                onMigrate = { navigator.push(MigrationConfigScreen(it.id)) },
            )
        }
    }
}
