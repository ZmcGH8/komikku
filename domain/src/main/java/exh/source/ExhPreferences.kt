package exh.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.EHLogLevel
import exh.log.EHLogLevel.Companion.EH_LOG_LEVEL_PREF
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.service.AppUpdatePolicy

@SingleIn(AppScope::class)
@Inject
class ExhPreferences(
    private val preferenceStore: PreferenceStore,
) {
    // KMK -->
    fun appShouldAutoUpdate() = preferenceStore.getStringSet(
        "should_auto_update",
        setOf(
            AppUpdatePolicy.DEVICE_ONLY_ON_WIFI,
        ),
    )
    // KMK <--

    // SY -->
    fun isHentaiEnabled() = preferenceStore.getBoolean("eh_is_hentai_enabled", true)

    // KMK --> Light novel reader gate (default off until real LN sources ship).
    fun isLightNovelEnabled() = preferenceStore.getBoolean("eh_is_light_novel_enabled", false)
    // KMK <--

    fun enableExhentai() = preferenceStore.getBoolean(Preference.privateKey("enable_exhentai"), false)

    fun imageQuality() = preferenceStore.getString("ehentai_quality", "auto")

    fun useHentaiAtHome() = preferenceStore.getInt("eh_enable_hah", 0)

    fun useJapaneseTitle() = preferenceStore.getBoolean("use_jp_title", false)

    fun exhUseOriginalImages() = preferenceStore.getBoolean("eh_useOrigImages", false)

    fun ehTagFilterValue() = preferenceStore.getInt("eh_tag_filtering_value", 0)

    fun ehTagWatchingValue() = preferenceStore.getInt("eh_tag_watching_value", 0)

    // EH Cookies
    fun memberIdVal() = preferenceStore.getString(Preference.privateKey("eh_ipb_member_id"), "")

    fun passHashVal() = preferenceStore.getString(Preference.privateKey("eh_ipb_pass_hash"), "")
    fun igneousVal() = preferenceStore.getString(Preference.privateKey("eh_igneous"), "")
    fun ehSettingsProfile() = preferenceStore.getInt(Preference.privateKey("eh_ehSettingsProfile"), -1)
    fun exhSettingsProfile() = preferenceStore.getInt(Preference.privateKey("eh_exhSettingsProfile"), -1)
    fun exhSettingsKey() = preferenceStore.getString(Preference.privateKey("eh_settingsKey"), "")
    fun exhSessionCookie() = preferenceStore.getString(Preference.privateKey("eh_sessionCookie"), "")
    fun exhHathPerksCookies() = preferenceStore.getString(Preference.privateKey("eh_hathPerksCookie"), "")

    fun exhShowSyncIntro() = preferenceStore.getBoolean("eh_show_sync_intro", true)

    fun exhReadOnlySync() = preferenceStore.getBoolean("eh_sync_read_only", false)

    fun exhLenientSync() = preferenceStore.getBoolean("eh_lenient_sync", false)

    fun exhShowSettingsUploadWarning() = preferenceStore.getBoolean("eh_showSettingsUploadWarning2", true)

    // KMK -->
    fun logLevel(isDebugBuildType: Boolean) = preferenceStore.getInt(EH_LOG_LEVEL_PREF, EHLogLevel.defaultLogLevel(isDebugBuildType))
    // KMK <--

    fun exhAutoUpdateFrequency() = preferenceStore.getInt("eh_auto_update_frequency", 1)

    fun exhAutoUpdateRequirements() = preferenceStore.getStringSet("eh_auto_update_restrictions", emptySet())

    fun exhAutoUpdateStats() = preferenceStore.getString(Preference.appStateKey("eh_auto_update_stats"), "")

    fun exhWatchedListDefaultState() = preferenceStore.getBoolean("eh_watched_list_default_state", false)

    fun exhSettingsLanguages() = preferenceStore.getString(
        "eh_settings_languages",
        "false*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\n" +
            "false*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\n" +
            "false*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\nfalse*false*false\n" +
            "false*false*false\nfalse*false*false",
    )

    fun exhEnabledCategories() = preferenceStore.getString(
        "eh_enabled_categories",
        "false,false,false,false,false,false,false,false,false,false",
    )

    fun enhancedEHentaiView() = preferenceStore.getBoolean("enhanced_e_hentai_view", true)

    // KMK -->

    /**
     * The E-Hentai languages the user has switched on, as the language codes the source ids use.
     *
     * Every language the source supports is a **separate source** with its own id, and
     * [eu.kanade.tachiyomi.source.AndroidSourceManager] instantiates all of them whenever the
     * source is on. So a library that has an entry filed under a language the user turned off still
     * has a perfectly live source behind it, and it turns up in the migration picker beside the ones
     * actually in use — which is what "I only have English enabled, why are all the languages
     * showing" was.
     *
     * A language counts as on when **any** of its row's flags is set. The three columns are which
     * title fields E-Hentai may search in that language, and a language with all three off is one
     * the user has not asked for at all; requiring any particular one of the three would hide a
     * language they search by its original title.
     *
     * "all" — the catch-all source, shown as *Multi* — is always in. It has no row of its own in the
     * settings UI, so its stored flags are never written and reading them would hide a source the
     * user cannot switch back on.
     *
     * A value too short to index is a value that cannot be read, and the safe direction for that is
     * to report **every** language: a parse failure must not silently take sources out of the list.
     */
    fun enabledEHentaiLanguages(): Set<String> {
        val languages = ehLanguageCodes
        val rows = exhSettingsLanguages().get().split("\n")
        if (rows.size < languages.size) return languages.toSet()
        return buildSet {
            languages.forEachIndexed { index, code ->
                if (code == MULTI_LANGUAGE || rows[index].split("*").any { it.trim() == "true" }) {
                    add(code)
                }
            }
        }
    }

    /**
     * Whether [sourceId] is an E-Hentai source whose language the user has switched on.
     *
     * False only for an E-Hentai source in a disabled language; a source of any other kind is never
     * filtered by this, which is why the caller does not have to know what it is asking about.
     */
    fun isEHentaiSourceEnabled(sourceId: Long): Boolean {
        val language = EHENTAI_EXT_SOURCES[sourceId] ?: EXHENTAI_EXT_SOURCES[sourceId] ?: return true
        return language in enabledEHentaiLanguages()
    }

    /**
     * Language codes in the order `eh_settings_languages` stores them.
     *
     * Taken from [EHENTAI_EXT_SOURCES] rather than written out, because that map is already the one
     * that decides which id a language gets and a second list could drift from it silently. Its
     * order has to agree with `EhUConfigBuilder.LanguageSystem.getLanguages`, which reads the same
     * preference by position.
     */
    private val ehLanguageCodes = EHENTAI_EXT_SOURCES.values.toList()
    // KMK <--
}

/** The catch-all E-Hentai source, labelled *Multi*; has no row in the language settings. */
private const val MULTI_LANGUAGE = "all"
