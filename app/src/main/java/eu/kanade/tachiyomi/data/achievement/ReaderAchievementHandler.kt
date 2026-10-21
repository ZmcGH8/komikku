package eu.kanade.tachiyomi.data.achievement

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import exh.log.xLogE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.achievement.service.RotatingAchievementPool
import java.util.Calendar

/**
 * The single place where "something happened in the reader" becomes "advance the
 * achievement counters", keeping achievement ids out of [eu.kanade.tachiyomi.ui.reader.ReaderViewModel].
 *
 * Every step is guarded individually. The previous version wrapped each whole handler in one
 * `try { ... } catch (_: Exception) {}`, which meant a single failure - a preferences backend
 * error, a malformed counter name - silently discarded every step after it in the same event.
 * A chapter read could therefore count the chapter and lose the streak, the binge tier and
 * the reading-mode achievement together, with nothing logged.
 *
 * The split between [onChapterRead] and [onMangaCompleted] is load-bearing. The three
 * reading-mode achievements all read "complete a manga in <mode>", and they used to be
 * unlocked by a single chapter read in that mode; they now fire where the description's
 * condition is actually observable.
 */
@SingleIn(AppScope::class)
@Inject
class ReaderAchievementHandler(
    private val achievementManager: AchievementManager,
    // KMK --> Injected rather than reached for through globalAppGraph, which is what the rest
    // of this class exists to avoid. Direction matters: the pool already depends on the manager,
    // so the manager must not depend back on the pool - the rotating marks belong at the call
    // site for that reason, not just for tidiness.
    private val rotatingPool: RotatingAchievementPool,
) {
    // KMK --> Own scope: starting a chapter is what pulls an entry out of the stale-unstarted
    // backlog, and the query has to leave the reader's call site. refreshBacklog throttles
    // itself, so a page turn cannot run a library query.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * One chapter finished, read in [readingModeFlag].
     *
     * [incognito] is a real case, not a theoretical one. The reader writes neither progress nor
     * history in incognito, but it still runs, so the achievements that measure the act of
     * reading still apply. Only the counters that describe a library - organic chapters read,
     * reading days, streaks - are skipped.
     */
    fun onChapterRead(readingModeFlag: Int, incognito: Boolean = false, mangaId: Long = 0L) {
        if (!incognito) {
            step("organic read") { achievementManager.onOrganicChapterRead(0) }
            // Day state first, so the streak tiers see today, then the per-day binge count.
            // Both are the only path to streak_*/binge_* - nothing else counts a read.
            step("reading day") { achievementManager.onReadingDay() }
            step("binge") { achievementManager.onBingeRead() }
            step("rotating day marks") { markOncePerReadingDay() }
            step("perfect day") { markPerfectDayIfEarned() }
            // KMK --> Run clock for the speedrun secret. Stamped on the first chapter read of a
            // series and read back at completion, so the elapsed time spans the whole run and
            // not just the last chapter - which is the only way a 20-chapter series can finish
            // inside an hour at all. Capped in the prefs layer, so a stale stamp reports no run.
            if (mangaId > 0L) step("run clock") { achievementManager.touchMangaRun(mangaId) }
        } else {
            // Incognito reads are exactly the ones that must not touch the library record, and
            // the organic and streak counters above are that record. The act still happened.
            step("incognito read") { achievementManager.incrementCounter("incognito_reads") }
            mark("rotating_daily_extra_4")
        }

        // Local hour and minute, so "read past 3 AM" and the 11:30-13:30 noon window mean the
        // user's clock rather than UTC. Resolved here rather than passed in: every caller is a
        // chapter read, and all of them want the same window.
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        // Time-of-day tiers measure the act of reading, so they apply in incognito too.
        step("time-of-day tiers") { achievementManager.onChapterReadAtHour(hour, minute) }
        if (isLunchBreak(hour, minute)) mark("rotating_daily_extra_11")
        if (hour < 8) mark("rotating_daily_morning_read")
        // "After 11 PM": the evening tail plus the small hours. The same span the weekly
        // night-owl tier counts, so the two describe one habit at two cadences.
        if (isNightReading(hour)) {
            mark("rotating_daily_midnight_read")
            mark("rotating_weekly_night_owl")
        }

        // Read tiers, all off the one read event, so no extra tracking is needed.
        mark("rotating_daily_read_5")
        mark("rotating_daily_read_15")
        mark("rotating_weekly_read_30")
        mark("rotating_weekly_read_75")

        // Whether a chapter was *read in webtoon* is a property of the read, not of finishing
        // the series, so this one stays on the chapter read.
        if (ReadingMode.isWebtoonType(readingModeFlag)) mark("rotating_daily_webtoon_5")

        // Starting a chapter is what pulls an entry out of the stale-unstarted backlog.
        scope.launch { runCatching { achievementManager.refreshBacklog() } }
    }

    fun onDoublePageDisplayed() {
        step("double page") { achievementManager.tryUnlockDirect("double_page") }
        mark("rotating_daily_extra_5")
    }

    /**
     * Every chapter of the series has now been read.
     *
     * [mangaId] is what keeps this countable once: the reader redraws the last page of an
     * already-finished series on every visit, and without the id each visit counted another
     * completion. [readingModeFlag] resolves the mode achievements here rather than on the
     * chapter read, because "complete a manga in RTL mode" is only true at this point.
     */
    fun onMangaCompleted(
        status: Long,
        mangaId: Long = 0L,
        totalChapters: Long = 0L,
        readingModeFlag: Int = ReadingMode.DEFAULT.flagValue,
    ) {
        // KMK --> Both branches count a cleared backlog entry themselves: onMangaFinished and
        // onMangaCaughtUp each end in onBacklogClearedQuiet(). Calling onBacklogCleared(1) here
        // as well used to be *how* the count happened; leaving it in after that refactor
        // incremented the lifetime counter twice per completed manga and re-created the second
        // toast batch the composition was meant to eliminate.
        step("manga completion") {
            if (achievementManager.isPermanentStatus(status)) {
                achievementManager.onMangaFinished(mangaId)
            } else {
                achievementManager.onMangaCaughtUp(mangaId)
                mark("rotating_daily_backlog_clear_1")
                mark("rotating_weekly_extra_4")
            }
        }
        // KMK --> Both completion paths count: the description is "complete 7 manga in 7 days",
        // and only permanent ones were being routed before.
        step("finished-on-day") { achievementManager.onMangaFinishedOnDay() }
        // Length tiers are about the series, not the finish, so they take the chapter count
        // rather than the status. 0 means the caller did not know it - no tiers fire.
        step("completion length tiers") { achievementManager.onMangaCompletedWithCount(totalChapters) }
        mark("rotating_daily_finish_1")
        mark("rotating_weekly_finish_3")
        if (totalChapters == 1L) mark("rotating_daily_extra_1")
        if (totalChapters >= 50L) mark("rotating_weekly_extra_10")

        // KMK --> Speedrun, read before the run clock is cleared: the elapsed time is only
        // available while the stamp still exists.
        if (mangaId > 0L) {
            step("speedrun") {
                val elapsed = achievementManager.finishMangaRun(mangaId)
                if (elapsed >= 0L) achievementManager.onMangaSpeedrun(totalChapters, elapsed)
            }
        }

        step("reading mode") { onReadingModeCompleted(mangaId, readingModeFlag) }
    }

    /**
     * Banks [minutes] of reading. Whole minutes only, and a session shorter than one is not
     * banked at all.
     *
     * The caller rounds up (`coerceAtLeast(1L)`), so every chapter - including one abandoned
     * after two seconds - credited a minute. `reading_time_1h` was therefore reachable in a
     * fraction of an hour of actual reading, and the same inflation reached every higher tier
     * and the rank that reads off the total. A partial minute is not rounded up because there
     * is nothing to bank until the next one completes.
     */
    fun onReadingTimeMinutes(minutes: Long) {
        if (minutes < 1L) return
        step("reading time") { achievementManager.onReadingTimeMinutes(minutes) }
        // KMK --> The weekly tier is five hours and the counter is in minutes, so this advances
        // by the amount read rather than one tick per call.
        mark("rotating_weekly_extra_7", minutes.toInt())
    }

    /**
     * The three mode achievements, plus the one that needs both. Moved here from the chapter
     * read: they all say "complete a manga", and unlocking them on the first chapter read in
     * that mode made them three free achievements for anyone who opened the reader.
     */
    private fun onReadingModeCompleted(mangaId: Long, readingModeFlag: Int) {
        val id = when (ReadingMode.fromPreference(readingModeFlag)) {
            ReadingMode.LEFT_TO_RIGHT -> "ltr_reader"
            ReadingMode.RIGHT_TO_LEFT -> "rtl_reader"
            ReadingMode.VERTICAL, ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> "vertical_reader"
            ReadingMode.DEFAULT -> null
        } ?: return

        step("reading mode achievement") { achievementManager.tryUnlockDirect(id) }
        // "Complete 3 manga in LTR mode this week" is the same event at weekly cadence.
        if (id == "ltr_reader") mark("rotating_weekly_ltr_3")
        if (mangaId > 0L) {
            step("pager and webtoon") {
                achievementManager.onMangaCompletedInMode(
                    mangaId = mangaId,
                    modeFlag = readingModeFlag,
                    pager = ReadingMode.isPagerType(readingModeFlag),
                    webtoon = ReadingMode.isWebtoonType(readingModeFlag),
                )
            }
        }
    }

    /** Counts the day a chapter was read, for the tiers that measure days rather than reads. */
    private fun markOncePerReadingDay() {
        markOnce("rotating_daily_streak_bonus")
        markOnce("rotating_weekly_extra_5")
    }

    /**
     * "Read 10 chapters without skipping", judged over the day so far instead of marked blind.
     * The binge counter already holds today's chapter total, so the only new fact needed is
     * whether the user jumped chapters today - which [onChapterSkipped] records.
     */
    private fun markPerfectDayIfEarned() {
        if (achievementManager.chaptersReadToday() != PERFECT_DAY_READS) return
        if (achievementManager.skippedToday()) return
        mark("rotating_daily_extra_10")
    }

    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { xLogE("Achievement step failed: $name", it) }
    }

    private fun mark(id: String, progress: Int = 1) {
        runCatching { rotatingPool.markProgress(id, progress) }
            .onFailure { xLogE("Rotating achievement mark failed: $id", it) }
    }

    private fun markOnce(id: String) {
        runCatching { rotatingPool.markOncePerDay(id) }
            .onFailure { xLogE("Rotating daily mark failed: $id", it) }
    }

    private companion object {
        /** The perfect-day rotating tier is "read 10 chapters without skipping". */
        const val PERFECT_DAY_READS = 10L

        /** 11:30-13:30 inclusive, so the noon window covers both stated endpoints. */
        fun isLunchBreak(hour: Int, minute: Int): Boolean =
            (hour == 11 && minute >= 30) || hour == 12 || (hour == 13 && minute <= 30)

        fun isNightReading(hour: Int): Boolean = hour >= 23 || hour < 3
    }
}
