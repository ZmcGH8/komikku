// KMK -->
package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.components.LocalBookImportRow
import eu.kanade.presentation.reader.settings.NovelTypographyPage
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The book reader's typography, reachable from the global settings hub.
 *
 * ### Why this screen exists
 *
 * `NovelTypographyPage` was written for the reader's own *Reading appearance* tab and that is still
 * where it is edited — a reader adjusts text while reading it, and the open document re-lays out
 * behind the slider. But a tab inside the reader is only reachable while a book is open, so the
 * settings were invisible to anyone not currently reading one: reported as "was there supposed to be
 * epub related settings in the global houri settings screen? if so it doesn't have any".
 *
 * This is the same rows, offered a second way. One definition, two homes: the page and the tab read
 * and write the same [eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences], so a change
 * made here is what the next book opens with and a change made there is what this screen shows.
 *
 * ### Why importing books lives here too
 *
 * Getting an EPUB into the app used to mean a file manager and a folder the user had to know the
 * name of. That is a step most people do not discover, so the import sits beside the settings for
 * the thing it produces: you come here to change how a book reads, and "I have a book to add" is
 * the same errand.
 *
 * ### Why a `CustomPreference` wrapping the page
 *
 * The modular settings framework builds `Preference.PreferenceItem`, and its item types cover boolean,
 * text, int-slider, multi-select and enum-list keys. Two thirds of these rows are none of those: they
 * are floats the stylesheet reads as fractions of the page, and tri-states stored as the string
 * `"follow"`. A float margin projected into `SliderPreference`'s whole percent would quantise the value
 * on every drag, and no variant of `SwitchPreference` can say "leave the book alone". So the rows stay
 * hand-built in `NovelTypographyPage` and `CustomPreference` hosts them — its `title` is only the index
 * key settings search matches against, and it renders `content` alone.
 */
object SettingsBookReadingScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_book_reading

    @Composable
    override fun getPreferences(): List<Preference> = listOf(
        Preference.PreferenceItem.CustomPreference(
            title = stringResource(KMR.strings.local_book_import),
            content = { LocalBookImportRow() },
        ),
        Preference.PreferenceItem.CustomPreference(
            title = stringResource(KMR.strings.novel_reader_typography),
            // The page emits its rows bare and relies on the host for horizontal stacking — in the
            // reader that is the scrolled Column at ReaderSettingsDialog. A LazyColumn item is a
            // single measurement slot, not a Column, so here the same bare rows would all land in
            // that one slot and draw on top of each other. Every other CustomPreference content in
            // this codebase is likewise a container; wrap instead of relying on the host again.
            content = { Column { NovelTypographyPage() } },
        ),
    )
}
// KMK <--
