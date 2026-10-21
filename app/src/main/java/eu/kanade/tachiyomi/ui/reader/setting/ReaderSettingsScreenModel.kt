package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.app.di.globalAppGraph

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = globalAppGraph.readerPreferences,
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    // KMK -->
    /**
     * Whether the chapter on screen is served as text, and so has a book's settings to offer.
     *
     * Decided from the loader rather than from the pages for the same reason the renderer is: the
     * loader is assigned before a single page is read, so a tab offered on it appears at the same
     * moment as the renderer that makes those settings meaningful, and disappears with it. A chapter
     * that has not loaded yet reports false, which is the honest answer — there is nothing to adjust.
     */
    val isTextChapterFlow: StateFlow<Boolean> = readerState
        .map { it.currentChapter?.pageLoader is TextPageLoader }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * Whether the chapter on screen has pages to colour-filter.
     *
     * A text chapter has none, and that is decided from the loader rather than from the page count:
     * a book's documents *are* its entries in the page list, so "pages is non-empty" is true of a
     * novel too — and gating the colour tab on that alone offered a colour filter with no bitmap to
     * filter, every time an EPUB was opened. Distinct from [isTextChapterFlow] on purpose — the two
     * are read independently rather than as one flag, so a chapter that is somehow neither (its
     * pages are still loading) keeps the base tabs rather than losing one.
     */
    val hasPagesFlow: StateFlow<Boolean> = readerState
        .map { state ->
            val chapter = state.currentChapter
            chapter?.pageLoader !is TextPageLoader && chapter?.pages?.isNotEmpty() == true
        }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
    // KMK <--

    private companion object {
        /**
         * How long the tab flags outlive a subscriber.
         *
         * The reader's settings dialog is opened from a menu that is itself transient, so a share of a
         * second is generous; long enough that rebuilding the model for a rotation does not flicker the
         * tabs, short enough that a closed reader is not kept alive by a boolean.
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
