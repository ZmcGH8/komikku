package eu.kanade.tachiyomi.ui.duplicates

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.interactor.DuplicateEntry
import tachiyomi.domain.manga.interactor.GetLibraryDuplicates

/**
 * Lists every library entry that has at least one duplicate so they can be reviewed in one place.
 *
 * The scan runs on open rather than on a library update: it costs one query per entry, which is fine
 * for something a user asks for deliberately but not something to repeat on every background run.
 */
class DuplicateMangaScreenModel(
    private val getLibraryDuplicates: GetLibraryDuplicates = globalAppGraph.getLibraryDuplicates,
) : StateScreenModel<DuplicateMangaScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launch {
            val entries = getLibraryDuplicates.await()
            mutableState.value = if (entries.isEmpty()) State.Empty else State.Content(entries)
        }
    }

    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Content(val entries: List<DuplicateEntry>) : State
    }
}
