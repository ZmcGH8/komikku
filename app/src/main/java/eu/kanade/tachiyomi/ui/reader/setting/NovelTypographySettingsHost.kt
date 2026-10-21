// KMK -->
package eu.kanade.tachiyomi.ui.reader.setting

import tachiyomi.core.common.preference.SettingHost
import tachiyomi.core.common.preference.SettingsRegistry

/**
 * Registers the novel reader's typography keys with [SettingsRegistry].
 *
 * The registry asserts global key uniqueness at startup, so a typography key that collides with one
 * from another feature fails immediately and visibly rather than silently shadowing that setting
 * depending on which class happened to load first.
 *
 * Registration is deliberately all this does. The rows are hand-built in
 * `NovelTypographySettingsPage`, beside the rest of the reader's settings, for the same reason the
 * rest of *those* are: the novel settings are two thirds floats and one third tri-state strings,
 * which the definition helpers in the modular framework do not model, and a projection into whole
 * percentages would quantise the values the stylesheet reads. See that file for what the rows are.
 */
object NovelTypographySettingsHost : SettingHost {
    override val settingKeys = NovelTypographySettingKeys.all

    init {
        SettingsRegistry.register(this)
    }
}
// KMK <--
