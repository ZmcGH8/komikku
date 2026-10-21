package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.Achievements
import kotlin.random.Random

/**
 * The rotating pool: a small set of daily and weekly achievements drawn from a larger
 * catalogue, re-drawn at each period boundary.
 *
 * The whole design rests on one rule, and every bug here came from breaking it: **progress is
 * only ever counted for an achievement that is currently in an active set.** Without that,
 * every rotating achievement with a low threshold quietly unlocked the first time the user
 * happened to trigger it, whether or not it was on show - so the pool drained itself instead
 * of rotating, and [getActiveDaily] kept falling back to offering already-unlocked entries.
 */
@SingleIn(AppScope::class)
@Inject
class RotatingAchievementPool(
    private val prefs: AchievementPreferences,
    private val manager: AchievementManager,
) {
    /**
     * Today's three daily achievements.
     *
     * `@Synchronized` because a rollover is a read-decide-write over three prefs plus a prune:
     * two callers racing it would each pick their own set, and the loser's progress would be
     * pruned away by the winner's call.
     */
    @Synchronized
    private fun getActiveDaily(): List<Achievement> {
        val epoch = today()
        val storedEpoch = prefs.rotatingLastDailyEpoch().get()
        val storedIds = prefs.rotatingDailyIds().get()
        if (storedEpoch == epoch && storedIds.isNotBlank()) {
            return storedIds.split(",").mapNotNull { Achievements.forId(it.trim()) }
        }
        val pool = Achievements.rotating.filter { it.id.startsWith("rotating_daily") }
        val unlocked = prefs.getUnlockedIds()
        val available = pool.filter { it.id !in unlocked }
        // Below three unclaimed entries the pool would be empty and the user would see nothing,
        // so fall back to the full pool: an already-unlocked card is better than a gap.
        val candidates = if (available.size >= DAILY_SLOTS) available else pool
        val selected = candidates.shuffled(Random(epoch)).take(DAILY_SLOTS)
        prefs.rotatingLastDailyEpoch().set(epoch)
        prefs.rotatingDailyIds().set(selected.joinToString(",") { it.id })
        pruneProgress()
        return selected
    }

    /** This week's four weekly achievements. Same rules as [getActiveDaily]. */
    @Synchronized
    private fun getActiveWeekly(): List<Achievement> {
        val epoch = today() / 7
        val storedEpoch = prefs.rotatingLastWeeklyEpoch().get()
        val storedIds = prefs.rotatingWeeklyIds().get()
        if (storedEpoch == epoch && storedIds.isNotBlank()) {
            return storedIds.split(",").mapNotNull { Achievements.forId(it.trim()) }
        }
        val pool = Achievements.rotating.filter { it.id.startsWith("rotating_weekly") }
        val unlocked = prefs.getUnlockedIds()
        val available = pool.filter { it.id !in unlocked }
        val candidates = if (available.size >= WEEKLY_SLOTS) available else pool
        val selected = candidates.shuffled(Random(epoch + WEEKLY_SEED_OFFSET)).take(WEEKLY_SLOTS)
        prefs.rotatingLastWeeklyEpoch().set(epoch)
        prefs.rotatingWeeklyIds().set(selected.joinToString(",") { it.id })
        pruneProgress()
        return selected
    }

    fun getAllActive(): List<Achievement> = getActiveDaily() + getActiveWeekly()

    /**
     * Advances [id] by [progress], unlocking it at its threshold.
     *
     * Three properties this needs, each of which was a real defect:
     *
     * - **Active-only.** A mark for an id outside both active sets is discarded. Counting it
     *   meant achievements unlocked while invisible, and the counts sat in the CSV until a
     *   later period happened to re-select the id and handed the user a free unlock.
     * - **Atomic.** The whole read-modify-write is under the instance monitor. Progress lives
     *   in one CSV pref, so two unsynchronised marks - a chapter read on the reader thread and
     *   a tracker update on a worker - both read the old value and the second write lost one.
     * - **Lossless.** Progress is only cleared once the unlock actually lands. Clearing it on a
     *   failed unlock - achievements disabled, the id unknown, the 300-entry ceiling hit -
     *   threw away hours of progress for nothing.
     */
    @Synchronized
    fun markProgress(id: String, progress: Int = 1) {
        if (progress <= 0) return
        val ach = Achievements.forId(id) ?: return
        if (!ach.isRotating) return
        if (prefs.getUnlockedIds().contains(id)) return
        // Resolves (and, on the first call of a period, draws) the sets itself rather than
        // trusting that something else already drew them: nothing else reliably did, so testing
        // against empty stored ids discarded every mark until the user happened to open the
        // achievements screen.
        if (id !in drawnActiveIds()) return

        val map = prefs.rotatingProgressMap().toMutableMap()
        val current = ((map[id] ?: 0) + progress).coerceAtMost(PROGRESS_CEILING)
        map[id] = current
        prefs.setRotatingProgress(map)

        if (current >= thresholdFor(id)) {
            // Only clear the bar once the unlock actually landed. Clearing it on a failed
            // unlock - achievements disabled, id unknown, entry ceiling reached - discarded the
            // progress the user had built up, with nothing to show for it.
            if (manager.tryUnlockDirect(id)) {
                map.remove(id)
                prefs.setRotatingProgress(map)
            }
        }
    }

    /**
     * Marks [id] at most once per calendar day, for tiers that count days rather than events -
     * a plain [markProgress] here would let one sitting fill a seven-day tier.
     *
     * The stamp is written before pruning so the "already stamped today" test survives the
     * prune, and the prune then drops stamps for ids that are no longer on show.
     */
    @Synchronized
    fun markOncePerDay(id: String) {
        if (id !in drawnActiveIds()) return
        val epoch = today()
        val stamps = prefs.rotatingDayStampMap().toMutableMap()
        if (stamps[id] == epoch) return
        stamps[id] = epoch
        prefs.setRotatingDayStamps(pruneDayStamps(stamps))
        markProgress(id)
    }

    fun getProgress(id: String): Int = prefs.rotatingProgressMap()[id] ?: 0

    /**
     * Whether [id] is currently on show in either period. The same test [markProgress] applies,
     * exposed so callers can skip work whose only purpose is to advance a tier that is not live.
     */
    fun isActive(id: String): Boolean = id in drawnActiveIds()

    /**
     * Rolls both periods forward if the stored epoch is stale. Idempotent, so process start can
     * call it unconditionally: the only work a current period costs is one pref read each.
     */
    @Synchronized
    fun refreshIfNeeded() {
        getActiveDaily()
        getActiveWeekly()
    }

    /**
     * Drops progress for ids that are no longer in either period's active set.
     *
     * Rotating progress is keyed per achievement id, so without this a count earned in one
     * period is still sitting there when the same id comes back around and the bar starts
     * part-finished.
     *
     * Keeps the union of the stored daily and weekly sets, so it is safe whichever period's
     * getter triggered it - pruning on the daily set alone would wipe the weekly progress.
     * Only called from an epoch rollover, after the caller's own ids have been stored.
     */
    private fun pruneProgress() {
        val parsed = prefs.rotatingProgressMap()
        if (parsed.isEmpty()) return
        val active = activeIds()
        val kept = parsed.filterKeys { it in active }
        if (kept.size != parsed.size) prefs.setRotatingProgress(kept)
    }

    private fun pruneDayStamps(stamps: Map<String, Long>): Map<String, Long> =
        if (stamps.size <= DAY_STAMP_LIMIT) stamps else stamps.filterKeys { it in activeIds() }

    /**
     * The active sets, drawing them first if the stored ids belong to an earlier period.
     *
     * This is the fix for the rotation being silently ignored. [activeIds] reads whatever is
     * stored, and the stored ids were only ever written by a draw - which in practice meant the
     * achievements screen being opened. A reader who never opened it got `markProgress` calls
     * against an empty set, so every rotating count was thrown away and none of the daily or
     * weekly cards could ever unlock. Rolling the draw in here means the first mark of a period
     * draws the period.
     *
     * `@Synchronized` because it mutates: two marks racing a rollover would each draw their own
     * set and the loser's progress would be pruned by the winner's.
     */
    @Synchronized
    private fun drawnActiveIds(): Set<String> {
        getActiveDaily()
        getActiveWeekly()
        return activeIds()
    }

    private fun activeIds(): Set<String> {
        val raw = prefs.rotatingDailyIds().get() + ',' + prefs.rotatingWeeklyIds().get()
        return raw.splitToSequence(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    companion object {
        /**
         * Today in the reader's own timezone, matching the day stamps the rest of the
         * achievement layer writes.
         *
         * The pool used to divide the epoch millis by the day length, which counts UTC days: a
         * draw therefore rolled over at 00:00 UTC rather than at the reader's midnight, so the
         * "daily" cards could disappear before the user's day was over and a mark could be
         * discarded by a rollover the reader had not reached yet. Same reason as
         * [AchievementManager.currentEpochDay].
         */
        fun today(): Long = java.time.LocalDate.now().toEpochDay()

        const val DAILY_SLOTS = 3
        const val WEEKLY_SLOTS = 4

        /** Keeps the daily and weekly draws from correlating across a shared epoch value. */
        const val WEEKLY_SEED_OFFSET = 1000L

        /**
         * Ceiling on a single progress value. Without it a caller passing a large amount - the
         * reading-time tiers pass whole minutes - could write a number that never clears.
         */
        const val PROGRESS_CEILING = 1_000_000

        /** Cap on retained day stamps; see [pruneDayStamps]. */
        const val DAY_STAMP_LIMIT = 120

        /**
         * Target count for a rotating achievement, keyed by id.
         *
         * These have to agree with the catalogue's own descriptions, and they drifted: two ids
         * sat in the threshold-1 list below while their descriptions said "2 days in a row" and
         * "10 chapters", so both unlocked on the first qualifying read. Anything not named here
         * is a one-shot achievement and unlocks on its first mark.
         *
         * Public because the UI needs the same number to draw a progress bar - two tables that
         * have to agree should not be two tables.
         */
        private val THRESHOLDS: Map<String, Int> = buildMap {
            // Explicit where the target is not 1.
            //
            // Every count in the id belongs here. The suffix table below only carries ids whose
            // target is *not* the number in the id (`_tracker_5` covers the weekly tracker tier
            // and would also match a hypothetical `_tracker_5` daily), so relying on it for
            // `_read_5`, `_library_add_3`, `_search_5`, `_genre_explore`, `_webtoon_5` and
            // `_data_saver_5` left all six at the default of 1: the first chapter, the first
            // search, the first browse of the day unlocked a tier described as five or three.
            put("rotating_daily_read_5", 5)
            put("rotating_daily_read_15", 15)
            put("rotating_daily_library_add_3", 3)
            put("rotating_daily_search_5", 5)
            put("rotating_daily_genre_explore", 3)
            put("rotating_daily_webtoon_5", 5)
            put("rotating_daily_data_saver_5", 5)
            put("rotating_weekly_read_30", 30)
            put("rotating_weekly_read_75", 75)
            put("rotating_weekly_library_10", 10)
            put("rotating_weekly_finish_3", 3)
            put("rotating_weekly_translate_10", 10)
            put("rotating_weekly_upscale_20", 20)
            put("rotating_weekly_data_saver_20", 20)
            put("rotating_daily_tracker_update_3", 3)
            put("rotating_weekly_tracker_5", 5)
            put("rotating_weekly_night_owl", 3)
            put("rotating_weekly_reread_2", 2)
            put("rotating_weekly_ltr_3", 3)
            put("rotating_weekly_category_2", 2)
            put("rotating_weekly_upload_cover_3", 3)
            put("rotating_weekly_backup", 1)
            put("rotating_daily_extra_4", 2)
            put("rotating_daily_extra_10", 10)
            put("rotating_weekly_extra_4", 5)
            put("rotating_weekly_extra_5", 7)
            put("rotating_weekly_extra_6", 2)
            put("rotating_weekly_extra_7", 300)
            put("rotating_weekly_extra_8", 15)
            put("rotating_weekly_extra_3", 1)
            put("rotating_weekly_extra_9", 1)
            // "Read 2 days in a row this week" - the description, not the old threshold of 1.
            put("rotating_daily_streak_bonus", 2)
        }

        /** Tiers whose id states the target; keeps the named list above short. */
        private val SUFFIX_TARGETS = listOf(
            "_read_5" to 5,
            "_read_15" to 15,
            "_read_30" to 30,
            "_read_75" to 75,
            "_translate_2" to 2,
            "_translate_10" to 10,
            "_library_10" to 10,
            "_finish_3" to 3,
            "_reread_2" to 2,
            "_ltr_3" to 3,
            "_category_2" to 2,
            "_upload_cover_3" to 3,
            "_tracker_5" to 5,
            "_tracker_update_3" to 3,
        )

        /** Target for [id]; 1 for the one-shot majority of the catalogue. */
        fun thresholdFor(id: String): Int {
            THRESHOLDS[id]?.let { return it }
            for ((suffix, target) in SUFFIX_TARGETS) {
                if (id.endsWith(suffix)) return target
            }
            return 1
        }
    }
}
