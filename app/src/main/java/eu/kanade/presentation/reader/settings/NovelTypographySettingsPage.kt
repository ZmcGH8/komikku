package eu.kanade.presentation.reader.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelFonts
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.IconItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The text reader's own settings, as a tab beside the image reader's.
 *
 * ### Why these rows are hand-built
 *
 * The modular settings framework (`settings/framework/AGENTS.md`) covers boolean, text, int-slider,
 * multi-select and enum-list keys. Two thirds of this reader's settings are none of those: they are
 * floats the stylesheet reads as fractions of the page, and tri-states stored as the string
 * `"follow"`. Projecting a float margin into a whole percent to fit `intSliderSetting` would quantise
 * the value on every drag, and no representation of "follow the publisher" exists in a switch. So the
 * rows are built from `presentation-core`'s settings components — the same ones `GeneralPage` uses —
 * against the store that is already the only writer, [NovelTypographyPreferences].
 *
 * ### Why a tab rather than rows inside the general page
 *
 * A book has none of the image reader's controls: no page transition, no scale type, no crop, no
 * spread. Putting these beside them would leave the general page half rows that do nothing for the
 * chapter in front of the reader. A tab also makes its visibility honest — it is offered exactly
 * when a text loader is showing, which is the same signal that chose the renderer.
 *
 * ### One value, one source
 *
 * Every row reads from [NovelTypographyPreferences.changes] and writes through the same store, so the
 * page cannot show a stale number while the open document re-lays out behind it. Reading the individual
 * preferences instead would mean two paths to the same state, and a slider that disagreed with the
 * page it is supposed to be adjusting.
 *
 * Order is legibility first, because that is the order in which a page stops being readable: size and
 * colours, then what changes how lines break, then measurements.
 */
// KMK -->
@Composable
internal fun NovelTypographyPage(
    novelPrefs: NovelTypographyPreferences = globalAppGraph.novelTypographyPreferences,
) {
    // `values()`, not `changes()`: the page has to show what is stored, and `changes()` deliberately
    // drops its first emission so the reader is not told about a value it already has. Without the
    // leading value this screen opens on the defaults and only corrects itself once a preference is
    // touched - which reads as "my settings did not save" rather than as a display bug.
    val typography by novelPrefs.values().collectAsState(NovelTypography.DEFAULT)

    HeadingItem(stringResource(KMR.strings.novel_reader_typography))

    // ---------------------------------------------------------------- legibility

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_size),
        value = typography.fontSizePt,
        valueRange = NovelTypography.MIN_FONT_SIZE_PT..NovelTypography.MAX_FONT_SIZE_PT,
        valueString = "${typography.fontSizePt}pt",
        onChange = { novelPrefs.fontSize.set(it) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_scale),
        value = percent(typography.fontScale),
        valueRange = percent(NovelTypography.MIN_FONT_SCALE)..percent(NovelTypography.MAX_FONT_SCALE),
        valueString = "${percent(typography.fontScale)}%",
        onChange = { novelPrefs.fontScale.set(it / 100f) },
    )

    // KMK --> Fonts the reader brought themselves, offered beside the built-in families.
    val fontContext = LocalContext.current
    var fontImportVersion by remember { mutableIntStateOf(0) }
    val fontFamilies = remember(fontImportVersion) {
        FONT_FAMILIES.toList() + NovelFonts.list(fontContext)
    }
    SelectItem(
        label = stringResource(KMR.strings.pref_novel_font_family),
        options = fontFamilies.map { it.removePrefix(NovelFonts.PREFIX) }.toTypedArray(),
        selectedIndex = fontFamilies.indexOf(typography.fontFamily).coerceAtLeast(0),
        onSelect = { novelPrefs.fontFamily.set(fontFamilies[it]) },
    )

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            if (NovelFonts.import(fontContext, uri)) {
                fontContext.toast(KMR.strings.novel_font_added)
            } else {
                fontContext.toast(KMR.strings.novel_font_add_failed)
            }
            fontImportVersion++
        }
    }
    IconItem(
        label = stringResource(KMR.strings.novel_font_add),
        icon = Icons.Outlined.UploadFile,
        onClick = {
            // `font/*` first, but storage providers are uneven about font MIME types, and a
            // font picker that offers nothing is a dead button — octet-stream catches the rest.
            fontPicker.launch(
                arrayOf(
                    "font/*",
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "application/font-woff",
                    "application/octet-stream",
                ),
            )
        },
    )
    // KMK <--

    // Colours are a fixed palette rather than a free-text hex field on purpose. They are stored as
    // `#rrggbb`, which is what the stylesheet needs, and a hex field would let a reader type a colour
    // the contrast rule cannot reason about — and that reasoning is what keeps a dark background from
    // rendering as black text on black. See NovelTypography.effectiveFontColor.
    SettingsChipRow(KMR.strings.pref_novel_font_color) {
        ColorChips(FONT_COLORS, typography.fontColor.orEmpty()) { novelPrefs.fontColor.set(it) }
    }

    SettingsChipRow(KMR.strings.pref_novel_background_color) {
        ColorChips(BACKGROUND_COLORS, typography.backgroundColor.orEmpty()) {
            novelPrefs.backgroundColor.set(it)
        }
    }

    CheckboxItem(
        label = stringResource(KMR.strings.novel_show_images),
        pref = novelPrefs.showImages,
    )

    // ---------------------------------------------------------------- shape

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_line_spacing),
        value = percent(typography.lineSpacing),
        valueRange = percent(NovelTypography.MIN_LINE_SPACING)..percent(NovelTypography.MAX_LINE_SPACING),
        valueString = decimal(typography.lineSpacing),
        onChange = { novelPrefs.lineSpacing.set(it / 100f) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_paragraph_spacing),
        value = percent(typography.paragraphSpacing),
        valueRange = 0..percent(NovelTypography.MAX_PARAGRAPH_SPACING),
        valueString = decimal(typography.paragraphSpacing),
        onChange = { novelPrefs.paragraphSpacing.set(it / 100f) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_spacing),
        value = percent(typography.fontSpacing),
        valueRange = percent(-0.2f)..percent(1f),
        valueString = decimal(typography.fontSpacing),
        onChange = { novelPrefs.fontSpacing.set(it / 100f) },
    )

    // ---------------------------------------------------------------- margins

    HeadingItem(stringResource(KMR.strings.pref_novel_margins))

    MarginSlider(stringResource(KMR.strings.pref_novel_margin_left), typography.marginLeft) {
        novelPrefs.marginLeft.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_right), typography.marginRight) {
        novelPrefs.marginRight.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_top), typography.marginTop) {
        novelPrefs.marginTop.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_bottom), typography.marginBottom) {
        novelPrefs.marginBottom.set(it)
    }

    // ---------------------------------------------------------------- tri-state
    //
    // These are the three that must be able to say "leave the book alone", so they are not checkboxes
    // with an off state: an off state would mean "force this off", which is the setting these exist to
    // avoid. See the tri-state rules in ui/reader/AGENTS.md.

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_justified),
        state = typography.justified.toTriState(),
        onClick = { novelPrefs.justified = typography.justified.next() },
    )

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_hyphenation),
        state = typography.hyphenation.toTriState(),
        onClick = { novelPrefs.hyphenation = typography.hyphenation.next() },
    )

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_vertical_writing),
        state = typography.verticalWriting.toTriState(),
        onClick = { novelPrefs.verticalWriting = typography.verticalWriting.next() },
    )

    // A fourth, and the only one whose default is not "follow the book": it used to indent
    // unconditionally, so "on" is what every existing reader is reading. See
    // NovelTypographySettingKeys.PARAGRAPH_INDENT for why defaulting it the other way would have
    // restyled everyone's library on upgrade.
    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_paragraph_indent),
        state = typography.paragraphIndent.toTriState(),
        onClick = { novelPrefs.paragraphIndent = typography.paragraphIndent.next() },
    )
}

/**
 * A margin as a whole percent of the page.
 *
 * Percent because that is the unit the reader thinks in, and an integer because a slider with
 * hundredth-of-a-percent resolution is not a control. The division happens on the way out only, so
 * nothing is quantised in the store and the stylesheet still reads the fraction the reader dragged to.
 *
 * The ceiling is the same one the stylesheet enforces, reached through the value's own axis: a
 * horizontal margin is a fraction of the page *width* and a vertical one of its *height*. Reading the
 * limit off the stored value would make the slider's own range depend on what it is currently set to,
 * so both callers pass the maximum they are allowed and the row uses whichever is smaller.
 */
@Composable
private fun MarginSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    SliderItem(
        label = label,
        value = percent(value),
        valueRange = 0..percent(NovelTypography.MAX_MARGIN),
        valueString = "${percent(value)}%",
        onChange = { onChange(it / 100f) },
    )
}

/**
 * One row of colour choices, in the order they are offered.
 *
 * Selection is read from [selected] rather than remembered per chip, so exactly one chip is ever
 * highlighted and the row follows a change made anywhere else — including the derived one the reader
 * has not made yet, which is the whole point of the background/foreground pairing.
 *
 * The empty value is "follow the book" and is listed first, because it is what a reader wants to get
 * back to after experimenting.
 */
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.ColorChips(
    choices: List<Pair<String, StringResource>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    choices.forEach { (value, label) ->
        FilterChip(
            selected = value == selected,
            onClick = { onSelect(value) },
            label = { Text(stringResource(label)) },
        )
    }
}

/** `null` — follow the publisher — is the row a reader starts from, so it is the unselected state. */
private fun Boolean?.toTriState(): TriState = when (this) {
    true -> TriState.ENABLED_IS
    false -> TriState.ENABLED_NOT
    null -> TriState.DISABLED
}

/** The next value round the three-state cycle, ending back at "follow the book". */
private fun Boolean?.next(): Boolean? = when (this) {
    null -> true
    true -> false
    false -> null
}

/** A fraction the store holds as a float to the whole percent a slider can express. */
private fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

/**
 * Locale-independent, because the stylesheet is.
 *
 * `String.format` with the default locale puts a comma where the dot belongs in much of the world, and
 * "1,60" is not a number a reader recognises as a line height — nor one `fmt` in the stylesheet would
 * parse. Same reason `NovelTypographyCss.fmt` pins `Locale.ROOT`.
 */
private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

private val FONT_FAMILIES = arrayOf("serif", "sans-serif", "monospace", "cursive")

/**
 * (stored value, label). The empty value is "follow the book" and leads every list.
 *
 * Text and background are deliberately different lists rather than one palette for both. They are not
 * independent choices — a background implies a foreground — so the background offers a wider range of
 * papers and the text offers only extremes, which is all a reader needs when the colour is being
 * derived for them anyway.
 */
private val FONT_COLORS: List<Pair<String, StringResource>> = listOf(
    "" to KMR.strings.novel_follow_publisher,
    "#ffffff" to KMR.strings.novel_color_white,
    "#e8e6e3" to KMR.strings.novel_color_light_grey,
    "#202124" to KMR.strings.novel_color_dark_grey,
    "#000000" to KMR.strings.novel_color_black,
)

private val BACKGROUND_COLORS: List<Pair<String, StringResource>> = listOf(
    "" to KMR.strings.novel_follow_publisher,
    "#ffffff" to KMR.strings.novel_color_white,
    "#f5f2e8" to KMR.strings.novel_color_cream,
    "#e8e8e8" to KMR.strings.novel_color_light_grey,
    "#d9c7a7" to KMR.strings.novel_color_paper,
    "#1a1614" to KMR.strings.novel_color_sepia,
    "#202124" to KMR.strings.novel_color_dark_grey,
    "#121212" to KMR.strings.novel_color_black,
)
// KMK <--
