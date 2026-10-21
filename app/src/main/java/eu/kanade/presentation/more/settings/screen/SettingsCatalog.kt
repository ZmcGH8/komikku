package eu.kanade.presentation.more.settings.screen

// KMK -->
import eu.kanade.tachiyomi.BuildConfig

/**
 * Single source of truth for the list of searchable settings screens.
 *
 * Previously this list was hardcoded in `SettingsSearchScreen` while the settings hub
 * (`SettingsMainScreen.items`) kept its own parallel list, so adding a screen meant
 * editing two files. Screen registration now happens here:
 *
 * - Full category entries (hub + search): add the screen here AND an `Item` in
 *   `SettingsMainScreen.items` (which additionally carries icon/subtitle UI metadata).
 * - Search-only coverage is automatic: `SettingsSearchScreen` indexes this list.
 *
 * Keep this list in sync with `SettingsMainScreen.items` — every hub screen that
 * implements [SearchableSettings] must appear here.
 */
object SettingsCatalog {

    /**
     * AI feature screens, dropped from the no-MTL build.
     *
     * Filtered out of [searchableScreens] rather than branched around, so search cannot reach a
     * screen the settings hub hides on that flavor.
     */
    private val aiScreens: Set<SearchableSettings> = setOf(
        SettingsYakuyomiScreen,
        SettingsYakuyomiProviderScreen,
        SettingsYakuyomiModelsScreen,
        SettingsYakuyomiBehaviorScreen,
        SettingsYakuyomiPromptScreen,
        SettingsUpscalerScreen,
    )

    val searchableScreens: List<SearchableSettings> = listOfNotNull(
        SettingsAppearanceScreen,
        SettingsLibraryScreen,
        SettingsReaderScreen,
        // KMK -->
        // The book reader's typography. Also reachable as the reader's own *Reading appearance* tab;
        // this is the copy a reader can find without a book open.
        SettingsBookReadingScreen,
        // KMK <--
        SettingsDownloadScreen,
        SettingsTrackingScreen,
        // AM (CONNECTIONS) -->
        SettingsConnectionScreen,
        // <-- AM (CONNECTIONS)
        SettingsBrowseScreen,
        SettingsDataScreen,
        SettingsSecurityScreen,
        // SY -->
        SettingsEhScreen,
        SettingsMangadexScreen,
        // SY <--
        // KMK -->
        SettingsYakuyomiScreen,
        SettingsYakuyomiProviderScreen,
        SettingsYakuyomiModelsScreen,
        SettingsYakuyomiBehaviorScreen,
        SettingsYakuyomiPromptScreen,
        SettingsUpscalerScreen,
        // KMK <--
        SettingsAdvancedScreen,
    ).filterNot { BuildConfig.IS_NOMTL && it in aiScreens }
}
// KMK <--
