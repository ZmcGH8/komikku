package eu.kanade.tachiyomi.data.achievement

import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.log.xLogE
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Unlocks "you turned this on" achievements from the preference behind them.
 *
 * The reachability audit found these ids defined with no unlock site anywhere, so they could
 * never be earned. Each is a setting the user controls, which made one generic watcher cheaper
 * and less error prone than a bespoke call in every settings screen - and it also catches
 * values that only ever arrive from a backup restore or a migration.
 *
 * Two properties the previous version got wrong, and which are the reason this is not just a
 * `collect`:
 *
 * - **Seeded, not change-only.** [Preference.changes] never emits the current value, so a
 *   preference that was already set before the hooks were installed - including one restored
 *   from a backup - could never unlock. Each watcher now evaluates the value it starts with.
 * - **Installed unconditionally.** The old `if (!enabled) return` meant enabling achievements
 *   later left no watchers at all for the rest of the process. [AchievementManager] already
 *   gates every unlock on the same preference, so the watchers can be installed either way.
 */
class FeatureAchievementHooks(
    private val manager: AchievementManager,
    private val uiPreferences: UiPreferences,
    private val connectionsPreferences: ConnectionsPreferences,
    private val webhookPreferences: WebhookPreferences,
    private val readerPreferences: ReaderPreferences,
    private val translationPreferences: TranslationPreferences,
    private val libraryPreferences: LibraryPreferences,
) {

    fun install(scope: CoroutineScope) {
        watch(scope, { uiPreferences.censorLewdManga() }, { it }, "censor_toggle")
        watch(scope, { connectionsPreferences.enableDiscordRPC() }, { it }, "discord_rpc")
        watch(scope, { readerPreferences.chapterCompletionSound() }, { it }, "moan_enabled")
        watch(scope, { translationPreferences.autoTranslateOnDownload() }, { it }, "mtl_auto_download")
        watch(scope, { webhookPreferences.enabled() }, { it }, "webhook")
        watch(scope, { libraryPreferences.smartScanlatorMerge() }, { it }, "smart_filter")
        watch(
            scope,
            { libraryPreferences.displayMode() },
            { it == LibraryDisplayMode.StaggeredGrid },
            "staggered_grid",
        )
        // A model path or glossary JSON is the only evidence either was ever configured.
        watch(scope, { translationPreferences.localModel() }, { it.isNotBlank() }, "mtl_local")
        watch(scope, { translationPreferences.glossaryJson() }, { it.isNotBlank() }, "mtl_glossary")
        // Reading with the cutout left on, and turning the AST search parser on: both are
        // settings, so both belong with the rest of them rather than in a screen's onClick.
        watch(scope, { readerPreferences.drawUnderCutout() }, { it }, "cutout_enthusiast")
        watch(scope, { libraryPreferences.librarySearchAstEnabled() }, { it }, "search_ast")
        // Offline fallback only means anything while Gemini Nano is switched on too - the
        // description names both halves, and the fallback alone is a preference nobody sets.
        // Watched off the fallback itself, so the combination is also caught when it was
        // already true before these hooks were installed.
        watch(
            scope,
            { translationPreferences.offlineFallback() },
            { it && translationPreferences.geminiNanoEnabled().get() },
            "mtl_offline",
        )
    }

    private fun <T> watch(
        scope: CoroutineScope,
        pref: () -> Preference<T>,
        satisfied: (T) -> Boolean,
        id: String,
    ) {
        scope.launch {
            runCatching {
                val p = pref()
                // Seed from the current value so a preference that was satisfied before
                // installation - or arrived via restore - still unlocks exactly once.
                if (satisfied(p.get())) manager.tryUnlockDirect(id)
                p.changes().collect { if (satisfied(it)) manager.tryUnlockDirect(id) }
            }.onFailure {
                xLogE("Achievement watcher for $id failed", it)
            }
        }
    }
}
