package eu.kanade.tachiyomi.data.achievement

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.AchievementUnlockNotifier

/**
 * Turns achievement unlocks into toasts, sounds and webhooks.
 *
 * Everything funnels through [notify]. The previous implementation had two independent
 * paths - [AchievementUnlockNotifier.onUnlocked] and a collector on the unlocked-ids
 * preference - each with its own loop, neither consulting the other's de-duplication, and
 * the collector had no upper bound at all. Restoring a backup on a fresh install writes
 * every previously unlocked id in one go, so the collector saw the whole set as "new" and
 * queued one toast plus one sound per achievement, staggered 900 ms apart.
 *
 * Three rules now bound what a user can see, regardless of producer:
 *
 * 1. **One funnel.** Direct unlocks and preference-observed unlocks share [notify], so
 *    de-duplication is applied once rather than per path.
 * 2. **One toast per batch.** Anything past [SUMMARY_THRESHOLD] becomes a single summary
 *    line, and only the highest tier in the batch plays a sound.
 * 3. **Bulk mode wins.** [setBulkMode] suppresses toasts and sound entirely for the
 *    duration of a restore; the unlock is still recorded and the webhook still fires.
 */
@SingleIn(AppScope::class)
@Inject
class AchievementNotifier(
    private val context: Context,
    private val prefs: AchievementPreferences,
    private val soundPlayer: AchievementSoundPlayer,
    // KMK -->
    // Non-null: Metro resolves `Type? = null` only from a nullable binding, so the old
    // nullable param silently stayed null and no ACHIEVEMENT_UNLOCKED webhook was sent.
    private val webhookNotifier: eu.kanade.tachiyomi.data.webhook.WebhookNotifier,
    // KMK <--
) : AchievementUnlockNotifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val lock = Any()

    /** Last set observed by the preference collector; the baseline for "newly unlocked". */
    private var lastSeen: Set<String> = prefs.getUnlockedIds()

    private var started = false

    /** id -> uptime millis of the last toast, so a re-notified id stays quiet. */
    private val recentlyNotified = HashMap<String, Long>()

    /** Nesting depth for [setBulkMode]; only 0 <-> nonzero transitions matter. */
    private var bulkDepth = 0

    private var bulkSummary: MutableList<String>? = null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            // Re-read rather than trusting the field: the notifier is constructed eagerly but
            // start() may run later, and a restore in between must be the baseline, not a
            // difference to be announced.
            lastSeen = prefs.getUnlockedIds()
        }
        scope.launch {
            prefs.unlockedAchievements().changes()
                .map { raw -> if (raw.isBlank()) emptySet() else raw.split(",").filter { it.isNotBlank() }.toSet() }
                .distinctUntilChanged()
                .collect { current ->
                    val fresh = synchronized(lock) {
                        val added = current - lastSeen
                        lastSeen = current
                        added
                    }
                    notify(fresh.toList())
                }
        }
    }

    override fun setBulkMode(enabled: Boolean) {
        val pending = synchronized(lock) {
            bulkDepth = (bulkDepth + if (enabled) 1 else -1).coerceAtLeast(0)
            if (bulkDepth == 0) {
                val collected = bulkSummary
                bulkSummary = null
                collected
            } else {
                null
            }
        }
        if (pending != null) flushBulkSummary(pending)
    }

    override fun onUnlocked(ids: List<String>) = notify(ids)

    private fun notify(ids: List<String>) {
        if (ids.isEmpty()) return
        if (!prefs.achievementsEnabled().get()) return

        val now = SystemClock.uptimeMillis()
        val fresh = synchronized(lock) {
            pruneRecent()
            ids.filter { (now - (recentlyNotified[it] ?: 0L)) >= DEDUPE_WINDOW_MS }
                .onEach { recentlyNotified[it] = now }
        }
        if (fresh.isEmpty()) return

        // Before the bulk branch, deliberately: a webhook is a machine-readable record that the
        // achievement happened, and the whole point of bulk mode is to collapse what the *user*
        // sees - fifty toasts and fifty sounds - not to suppress the record. Returning above it
        // silently dropped every webhook for the unlocks a restore performed.
        sendWebhooks(fresh)

        if (isBulk()) {
            synchronized(lock) {
                (bulkSummary ?: mutableListOf<String>().also { bulkSummary = it }).addAll(fresh)
            }
            return
        }

        val valid = fresh.mapNotNull(Achievements::forId)
        if (valid.isEmpty()) return

        present(valid)
    }

    /**
     * Bulk mode still owes the user one line, otherwise a restore looks like it silently
     * ate their progress. Kept to a single toast with no sound.
     */
    private fun flushBulkSummary(ids: List<String>) {
        if (ids.isEmpty()) return
        if (!prefs.achievementsEnabled().get()) return
        if (!prefs.achievementToastsEnabled().get()) return
        val valid = ids.mapNotNull(Achievements::forId)
        if (valid.isEmpty()) return
        handler.post {
            runCatching { themedToastContext().toast(summaryText(valid), duration = Toast.LENGTH_LONG) }
        }
    }

    private fun sendWebhooks(ids: List<String>) {
        // KMK -->
        ids.forEach { id ->
            val ach = Achievements.forId(id) ?: return@forEach
            val revealed = if (ach.isSecret) ach.copy(unlockedAt = 1L) else ach
            runCatching {
                webhookNotifier.notify(
                    event = eu.kanade.tachiyomi.data.webhook.WebhookEvent.ACHIEVEMENT_UNLOCKED,
                    data = mapOf(
                        "achievement_id" to id,
                        "achievement_title" to revealed.displayTitle,
                        "achievement_tier" to ach.tier.name,
                    ),
                )
            }
        }
        // KMK <--
    }

    private fun present(valid: List<Achievement>) {
        val highest = valid.maxBy { it.tier.ordinal }

        if (valid.size > SUMMARY_THRESHOLD) {
            handler.post {
                if (prefs.achievementToastsEnabled().get()) {
                    runCatching { themedToastContext().toast(summaryText(valid), duration = Toast.LENGTH_LONG) }
                }
                soundPlayer.play(highest.tier)
            }
            return
        }

        var delayMs = 0L
        for (ach in valid) {
            handler.postDelayed({
                if (prefs.achievementToastsEnabled().get()) showToast(ach)
                soundPlayer.play(ach.tier)
            }, delayMs)
            delayMs += TOAST_SPACING_MS
        }
    }

    private fun summaryText(valid: List<Achievement>): String {
        val revealed = valid.map { if (it.isSecret) it.copy(unlockedAt = 1L) else it }
        val shown = revealed.take(SUMMARY_SHOWN)
        val remaining = revealed.size - shown.size
        return buildString {
            append("Unlocked ")
            append(revealed.size)
            append(if (revealed.size == 1) " achievement: " else " achievements: ")
            append(shown.joinToString(", ") { it.displayTitle })
            if (remaining > 0) append(" +$remaining more")
        }
    }

    private fun showToast(ach: Achievement) {
        if (!prefs.achievementsEnabled().get()) return
        if (!prefs.achievementToastsEnabled().get()) return
        val resolved = if (ach.isSecret) ach.copy(unlockedAt = 1L) else ach
        val secretPrefix = if (resolved.isSecret) "Secret Unlocked! " else ""
        val desc = resolved.displayDescription
            .let { if (it.length > MAX_DESCRIPTION_CHARS) it.take(MAX_DESCRIPTION_CHARS - 3) + "..." else it }
        val msg = "${resolved.displayIcon}  $secretPrefix${resolved.displayTitle} — $desc"
        runCatching { themedToastContext().toast(msg, duration = Toast.LENGTH_LONG) }
    }

    private fun isBulk(): Boolean = synchronized(lock) { bulkDepth > 0 }

    private fun pruneRecent() {
        val cutoff = SystemClock.uptimeMillis() - DEDUPE_WINDOW_MS
        recentlyNotified.entries.removeAll { it.value < cutoff }
    }

    private fun themedToastContext(): Context {
        return try {
            val uiPrefs: UiPreferences = globalAppGraph.uiPreferences
            val night = when (uiPrefs.themeMode().get()) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> context.applicationContext.isNightMode()
            }
            val expected = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val base = context.applicationContext
            val wrapped = ContextThemeWrapper(base, R.style.Theme_Tachiyomi)
            if (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != expected) {
                val overrideConf = Configuration()
                overrideConf.setTo(base.resources.configuration)
                overrideConf.uiMode = (overrideConf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or expected
                wrapped.applyOverrideConfiguration(overrideConf)
            }
            ThemingDelegate.getThemeResIds(uiPrefs.appTheme().get(), uiPrefs.themeDarkAmoled().get())
                .forEach { wrapped.theme.applyStyle(it, true) }
            wrapped
        } catch (_: Exception) {
            context
        }
    }

    private companion object {
        /** Above this many unlocks at once, one summary toast replaces the individual ones. */
        const val SUMMARY_THRESHOLD = 3

        /** Titles listed in a summary before it collapses into "+N more". */
        const val SUMMARY_SHOWN = 3

        const val TOAST_SPACING_MS = 900L

        /** Same id re-announced inside this window is treated as the same event. */
        const val DEDUPE_WINDOW_MS = 10_000L

        const val MAX_DESCRIPTION_CHARS = 80
    }
}
