package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One tab of the reader's settings.
 *
 * Modelled as a value rather than as an index so the tab that appears and disappears cannot silently
 * shift the page that goes with it. The colour tab is the one that dims the dialog window and hides
 * the reader's own chrome so its images are judged against a neutral field, and a tab indexed by
 * position had that applied to whichever tab happened to land third.
 */
private enum class SettingsTab {
    ReadingMode,
    General,
    Book,
    ColorFilter,
}

/**
 * The reader's settings, as tabs.
 *
 * The tabs a chapter is offered depend on what it is, decided by the same state the renderer was
 * chosen from — a tab that cannot act on the chapter in front of the reader reads as a bug in the
 * reader rather than in the book:
 *
 *  - a **text** chapter gets General (trimmed to what a book can use) and its own Reading
 *    appearance tab. It is not offered the reading-mode tab at all: a book has exactly one
 *    renderer and it is not negotiable, so every row there — scale types, crop, spreads, tap-zone
 *    layouts per viewer — would be a setting that silently does nothing.
 *  - an **image** chapter additionally gets the reading mode, and the colour filter once its pages
 *    exist (see [ReaderSettingsScreenModel.hasPagesFlow] for why a text chapter's documents do not
 *    count as pages here).
 */
@Composable
fun ReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
) {
    val isTextChapter = screenModel.isTextChapterFlow.collectAsState().value
    val hasPages = screenModel.hasPagesFlow.collectAsState().value
    val tabs = buildList {
        // KMK --> The reading-mode tab is manga-only: a book's renderer is fixed, so it is not
        // offered while the open chapter is text.
        if (!isTextChapter) {
            add(SettingsTab.ReadingMode to stringResource(MR.strings.pref_category_reading_mode))
        }
        // KMK <--
        add(SettingsTab.General to stringResource(MR.strings.pref_category_general))
        if (isTextChapter) {
            add(SettingsTab.Book to stringResource(KMR.strings.novel_reader_typography))
        }
        if (hasPages) {
            add(SettingsTab.ColorFilter to stringResource(MR.strings.custom_filter))
        }
    }

    val pagerState = rememberPagerState { tabs.size }
    val tabTitles: ImmutableList<String> = remember(tabs) { tabs.map { it.second }.toImmutableList() }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val tab = tabs.getOrNull(page)?.first

            LaunchedEffect(tab) {
                if (tab == SettingsTab.ColorFilter) {
                    window?.setDimAmount(0f)
                    onHideMenus()
                } else {
                    window?.setDimAmount(0.5f)
                    onShowMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    SettingsTab.ReadingMode -> ReadingModePage(screenModel)
                    SettingsTab.General -> GeneralPage(screenModel)
                    // KMK -->
                    SettingsTab.Book -> NovelTypographyPage()
                    // KMK <--
                    SettingsTab.ColorFilter -> ColorFilterPage(screenModel)
                    null -> Unit
                }
            }
        }
    }
}
