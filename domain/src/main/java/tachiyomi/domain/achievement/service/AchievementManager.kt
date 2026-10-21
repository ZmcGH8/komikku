package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.interactor.GetStaleUnstartedBacklog
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.AchievementTier
import tachiyomi.domain.achievement.model.Achievements
import java.util.concurrent.atomic.AtomicBoolean

@SingleIn(AppScope::class)
@Inject
class AchievementManager(
    private val prefs: AchievementPreferences,
    // KMK -->
    // Non-null: Metro treats `Type? = null` as an *optional* binding satisfied only by a
    // nullable binding, so the old nullable param silently resolved to null and webhooks
    // never fired. Non-null forces a compile-time graph error instead of silent loss.
    private val notifier: AchievementUnlockNotifier,
    // KMK <--
    // KMK --> Only source of the backlog number now; the two counters it used to subtract
    // described a different population and could not tell "never started" from "not finished".
    private val getStaleUnstartedBacklog: GetStaleUnstartedBacklog,
    // KMK <--
) {
    /** Single-flight guard for the suspending backlog query; see [refreshBacklog]. */
    private val backlogQueryInFlight = AtomicBoolean(false)

    @Synchronized
    fun onOrganicChapterRead(totalRead: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (totalRead < 0) return emptyList()
        prefs.incrementOrganicRead()
        val count = prefs.organicChaptersRead().get().coerceAtLeast(0L)
        val r = checkThresholds(count).toMutableList()
        r += bumpWeekendReads()
        if (r.isNotEmpty()) notifyIfNeeded(r)
        return r
    }

    /**
     * Counts a read toward the current Sat+Sun window. The key is the Saturday's epoch day, so
     * Saturday and Sunday share one bucket and Monday starts a fresh one. Weekdays leave the
     * stored window alone rather than clearing it, so a partial weekend is not lost before
     * Sunday arrives.
     */
    @Synchronized
    private fun bumpWeekendReads(): List<String> {
        val today = currentEpochDay()
        val dayOfWeek = (today + 4L) % 7L
        val key = when (dayOfWeek) {
            6L -> today
            0L -> today - 1L
            else -> return emptyList()
        }
        if (prefs.weekendReadKey().get() != key) {
            prefs.weekendReadKey().set(key)
            prefs.weekendReadCount().set(0L)
        }
        val weekend = prefs.weekendReadCount().get() + 1L
        prefs.weekendReadCount().set(weekend)
        if (weekend < 20L) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("weekend_warrior", unlocked)
        return unlocked
    }

    @Synchronized
    fun onReadingTimeMinutes(minutes: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (minutes <= 0) return emptyList()
        prefs.addReadingTimeMinutes(minutes)
        val total = prefs.totalReadingTimeMinutes().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (total >= 60) tryUnlock("reading_time_1h", unlocked)
        if (total >= 600) tryUnlock("reading_time_10h", unlocked)
        if (total >= 3000) tryUnlock("reading_time_50h", unlocked)
        if (total >= 6000) tryUnlock("reading_time_100h", unlocked)
        if (total >= 30000) tryUnlock("reading_time_500h", unlocked)
        if (total >= 60000) tryUnlock("reading_time_1000h", unlocked)
        if (total >= 120000) tryUnlock("ultimate_time_dilation", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    // KMK -->
    /**
     * Recomputes the stale-unstarted backlog and re-evaluates its thresholds. The count is a
     * library query, so it is cached in prefs for the synchronous [AchievementPreferences.computeStats].
     *
     * Not `@Synchronized`: it suspends across the query and must not hold the monitor. Only the
     * pref writes and the unlock evaluation are guarded, by [onBacklogChanged].
     *
     * [force] skips [REFRESH_THROTTLE_MS]. Callers that fire on ordinary reading pass false so a
     * chapter-turn cannot run a full library query; the ones that must be exact (library
     * changes, the startup pass that catches the 30-day threshold) pass true.
     */
    suspend fun refreshBacklog(force: Boolean = false): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val now = System.currentTimeMillis()
        if (!force && now - prefs.backlogLastRefresh().get() < REFRESH_THROTTLE_MS) return emptyList()
        // The throttle stamp is only written after the query returns, so without this every
        // caller racing the same window cleared the check and queued its own full library
        // query - a burst of reads meant a burst of identical queries. One in flight is enough:
        // it writes the stamp, which parks the rest of the window, and its own
        // onBacklogChanged covers the unlock evaluation the skipped callers gave up.
        if (!backlogQueryInFlight.compareAndSet(false, true)) return emptyList()
        try {
            val stale = getStaleUnstartedBacklog.await()
            prefs.setStaleUnstartedCount(stale)
            prefs.backlogLastRefresh().set(now)
            return onBacklogChanged(stale)
        } finally {
            backlogQueryInFlight.set(false)
        }
    }
    // KMK <--

    // KMK -->
    /**
     * Records today as a reading day and unlocks the streak tiers it now satisfies. This is the
     * only thing that can ever unlock `streak_3/7/30/100`: they previously had no call site and
     * no day state behind them, so they were unreachable.
     */
    @Synchronized
    fun onReadingDay(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val today = currentEpochDay()
        prefs.markReadingDay(today)
        val streak = readingStreak(today)
        val unlocked = mutableListOf<String>()
        if (streak >= 3) tryUnlock("streak_3", unlocked)
        if (streak >= 7) tryUnlock("streak_7", unlocked)
        if (streak >= 30) tryUnlock("streak_30", unlocked)
        if (streak >= 100) tryUnlock("streak_100", unlocked)
        // KMK --> Binge guilt is judged here because this is the only place that can observe a
        // return after an absence: the stamp was written by onBingeRead on the 100-chapter day,
        // and "not reading for three days" only becomes visible on the next day the user reads.
        // The window closes on that read either way - someone back the next day was not away
        // for three, and leaving the stamp would re-arm the check for every later absence.
        val bingeDay = prefs.bingeHundredDay().get()
        if (bingeDay >= 0L && today > bingeDay) {
            prefs.bingeHundredDay().set(-1L)
            if (today - bingeDay >= BINGE_GUILT_MIN_DAYS) tryUnlock("negative_binge_guilt", unlocked)
        }
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Consecutive reading days ending today, tolerating [STREAK_GRACE_DAYS] missed days.
     *
     * Each day is compared against the one *before it*, not against today. Comparing every entry
     * to today broke at the third day of any perfect run - `days = [1,2,3]`, today = 3, and the
     * entry two days back is 2 away, which exceeds the grace - so "Three Day Run" and every
     * longer tier were unreachable no matter how consistent the reader was.
     */
    private fun readingStreak(today: Long): Int {
        val days = prefs.readDays().filter { it <= today }.sortedDescending()
        var streak = 0
        var previous = today
        for (day in days) {
            if (previous - day > STREAK_GRACE_DAYS) break
            streak++
            previous = day
        }
        return streak
    }

    // KMK <--

    // KMK -->
    /**
     * Counts one occurrence of [name] and unlocks every tier it now reaches. Tiers are declared
     * here rather than at each call site so the numbers, the ids and the progress bars
     * ([AchievementProgress.thresholds]) cannot drift apart - the audit found these ids
     * defined with no unlock site at all, spread over half the catalogue.
     */
    @Synchronized
    fun incrementCounter(name: String, amount: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        prefs.incrementCounter(name, amount)
        return evaluateCounter(name, prefs.counter(name).get())
    }

    /**
     * Same, for a counter that resets at the day boundary. [name] must be listed in
     * [DAILY_COUNTER_TIERS]; anything else is ignored so a stray call cannot create junk prefs.
     */
    @Synchronized
    private fun incrementDailyCounter(name: String, amount: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (name !in DAILY_COUNTER_TIERS) return emptyList()
        if (amount <= 0) return emptyList()
        var count = 0L
        repeat(amount.coerceIn(1, 1000)) { count = prefs.bumpDailyCounter(name, currentEpochDay()) }
        return evaluateCounter(name, count)
    }

    /**
     * Counts one chapter toward today's binge total and stamps the day on the 100th, which is
     * what [onReadingDay] later judges "three days of nothing afterwards" against.
     *
     * Separate from [incrementDailyCounter] because the stamp is a side effect the generic
     * path has no business knowing about; folding it in would give every daily counter a
     * binge-guilt check the moment one of them crossed its own threshold.
     */
    @Synchronized
    fun onBingeRead(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val today = currentEpochDay()
        val count = prefs.bumpDailyCounter("binge", today)
        if (count >= BINGE_HUNDRED && prefs.bingeHundredDay().get() != today) {
            prefs.bingeHundredDay().set(today)
        }
        return evaluateCounter("binge", count)
    }

    /** Records that the user jumped chapters today, which disqualifies the perfect-day tier. */
    @Synchronized
    fun onChapterSkipped() {
        if (!prefs.achievementsEnabled().get()) return
        prefs.lastSkippedDay().set(currentEpochDay())
    }

    private fun evaluateCounter(name: String, count: Long): List<String> {
        val tiers = COUNTER_TIERS[name] ?: return emptyList()
        val unlocked = mutableListOf<String>()
        for ((at, id) in tiers) {
            if (count >= at) tryUnlock(id, unlocked)
        }
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }
    // KMK <--

    // KMK -->
    /**
     * Reader-side time-of-day achievements. [hour] and [minute] are local, so a 3 AM read is 3 AM
     * for the user rather than UTC. All of it comes off the same chapter-read event that already
     * drives the binge counter, so there is no extra query here.
     */
    @Synchronized
    fun onChapterReadAtHour(hour: Int, minute: Int): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        if (hour < 6) tryUnlock("early_bird", unlocked)
        // 11:30-13:30 inclusive, so the noon window covers both stated endpoints.
        if ((hour == 11 && minute >= 30) || hour == 12 || (hour == 13 && minute <= 30)) {
            tryUnlock("lunch_break", unlocked)
        }
        if (hour in 0..3) unlocked += incrementDailyCounterQuiet("midnight_reads")
        if (hour == 3) unlocked += incrementDailyCounterQuiet("late_night_reads")
        if (hour == 0) unlocked += incrementDailyCounterQuiet("after_midnight_reads")
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Completion tiers that depend on how long the series was. Separate from [onMangaSpeedrun]
     * because these are about the finish, not the time taken.
     */
    @Synchronized
    fun onMangaCompletedWithCount(totalChapters: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        if (totalChapters == 1L) tryUnlock("one_shot", unlocked)
        if (totalChapters >= 200L) tryUnlock("long_runner", unlocked)
        if (totalChapters >= 500L) tryUnlock("ultra_long", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    private fun incrementDailyCounterQuiet(name: String): List<String> =
        if (name in DAILY_COUNTER_TIERS) incrementDailyCounter(name) else emptyList()

    /** "Open the app at 03:33". Checked on process start only, which is the only place that is true. */
    @Synchronized
    fun onAppOpenedAt(hour: Int, minute: Int): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (hour != 3 || minute != 33) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("secret_houri", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * "Switch reader direction 20 times in a row." The streak resets on the opposite direction, so
     * alternating forever never counts - which is the point, since the description says "in a row".
     */
    @Synchronized
    fun onReaderDirectionChanged(direction: Int): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        // "Switch reader direction 20 times in a row" counts switches, so a *change* is what
        // advances the streak and a rebuild in the same direction is what breaks it.
        //
        // It was the other way round: a run of identical directions incremented the count and a
        // real switch reset it to zero. Every chapter transition rebuilds the viewer, so reading a
        // series start to finish without touching the mode button filled the counter - the exact
        // opposite of what the achievement describes - while a reader genuinely flipping between
        // directions twenty times never moved it at all.
        if (prefs.directionStreakDir().get() == direction) {
            prefs.directionStreakCount().set(0)
            return unlocked
        }
        prefs.directionStreakDir().set(direction)
        val next = (prefs.directionStreakCount().get() + 1).coerceAtMost(1_000)
        prefs.directionStreakCount().set(next)
        if (next >= 20) tryUnlock("secret_flip_phone", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Time-windowed reader sessions. [durationMs] is one continuous stretch with the reader
     * open, so a quit and reopen does not stitch two sittings into one 3-hour run.
     */
    @Synchronized
    fun onReaderSessionEnded(durationMs: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        if (durationMs >= 3 * 60 * 60 * 1000L) tryUnlock("secret_no_sleep", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Stamps the start of a run through [mangaId], if one is not already running.
     *
     * Paired with [finishMangaRun] to measure a whole-series run for the speedrun secret, which
     * is only measurable if the clock starts at the first chapter read rather than at the last.
     */
    @Synchronized
    fun touchMangaRun(mangaId: Long) {
        if (!prefs.achievementsEnabled().get()) return
        prefs.mangaRunElapsedMs(mangaId, System.currentTimeMillis())
    }

    /**
     * Ends the run through [mangaId] and returns how long it took, or -1 when there is no run
     * worth judging (never started, or started long enough ago that the elapsed time says
     * nothing about a sub-hour finish).
     *
     * The stamp is cleared either way: a finished series cannot be speedrun again until it is
     * started again, and leaving the original stamp in place would pin every later attempt to
     * the elapsed time of the first run forever.
     */
    @Synchronized
    fun finishMangaRun(mangaId: Long): Long {
        val elapsed = prefs.mangaRunElapsedMs(mangaId, System.currentTimeMillis())
        prefs.clearMangaRun(mangaId)
        return elapsed
    }

    /** Chapters read today, which is what the perfect-day rotating tier is measured against. */
    fun chaptersReadToday(): Long = prefs.dailyCounter("binge").get().coerceAtLeast(0L)

    /** Whether the user jumped chapters today, which disqualifies the perfect-day tier. */
    fun skippedToday(): Boolean = prefs.lastSkippedDay().get() == currentEpochDay()

    /**
     * "Finish a 20+ chapter manga in under an hour."
     */
    @Synchronized
    fun onMangaSpeedrun(totalChapters: Long, durationMs: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        if (totalChapters >= 20 && durationMs in 1..(60 * 60 * 1000L)) {
            tryUnlock("secret_speedrun", unlocked)
        }
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * "Complete 7 manga in 7 days", counted over the days on which a completion actually
     * happened.
     *
     * It used to test `readDays().size >= 7` - seven days on which the user read *anything*,
     * ever. That made a week of ordinary reading satisfy a secret about finishing series, which
     * is why it looked broken: the requirement was nowhere near the description. [finishDays]
     * is only written from the two completion paths, so the count now means what the
     * achievement says, and the seven-day window keeps it a "perfect week" rather than a total.
     */
    @Synchronized
    fun onMangaFinishedOnDay(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val today = currentEpochDay()
        prefs.markReadingDay(today)
        prefs.markFinishedDay(today)
        // `count` is an Int; the window is a Long so it can index epoch-day ranges directly.
        val recent = prefs.finishDays().count { today - it in 0 until PERFECT_WEEK_DAYS }.toLong()
        val unlocked = mutableListOf<String>()
        if (recent >= PERFECT_WEEK_DAYS) tryUnlock("secret_perfect_week", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }
    // KMK <--

    @Synchronized
    fun onBacklogChanged(staleUnstarted: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val backlog = staleUnstarted.coerceAtLeast(0L)
        val finishedCount = prefs.mangaFinishedCount().get()
        val unlocked = mutableListOf<String>()
        if (backlog >= 10) tryUnlock("backlog_10", unlocked)
        if (backlog >= 25) tryUnlock("backlog_25", unlocked)
        if (backlog >= 50) tryUnlock("backlog_50", unlocked)
        if (backlog >= 100) tryUnlock("backlog_100", unlocked)
        if (backlog >= 250) tryUnlock("backlog_250", unlocked)
        if (backlog >= 500) {
            tryUnlock("negative_hoarder_shame", unlocked)
            if (finishedCount == 0L) tryUnlock("negative_abandoned", unlocked)
        }
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Counts a cleared backlog entry and returns what it unlocked, without notifying. Callers
     * that own a wider batch add the result to their own list so one event yields one toast
     * batch and one complete return value. Already `@Synchronized` by way of its callers.
     */
    private fun onBacklogClearedQuiet(count: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        repeat(count.coerceIn(1, 1000)) { prefs.incrementBacklogCleared() }
        val total = prefs.backlogClearedCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (total >= 10) tryUnlock("backlog_cleared_10", unlocked)
        if (total >= 100) tryUnlock("backlog_cleared_100", unlocked)
        return unlocked
    }

    @Synchronized
    fun onEhBrowsed(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("eh_browsed", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Records that [mangaId] was finished in reading mode [modeFlag], and unlocks
     * `webtoon_pager_both` if this series has now been finished both ways.
     *
     * [pager] and [webtoon] are passed in rather than re-derived from [modeFlag] because the
     * pager/webtoon split is a property of `ReadingMode`, which lives in the app module. The
     * flag is still stored, because the condition spans two separate completions of the same
     * series - minutes or weeks apart - and no single call can see both.
     */
    @Synchronized
    fun onMangaCompletedInMode(mangaId: Long, modeFlag: Int, pager: Boolean, webtoon: Boolean): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (mangaId <= 0L) return emptyList()
        val previous = prefs.completionModes()[mangaId]
        prefs.recordCompletionMode(mangaId, modeFlag)
        // First completion of this series: there is no earlier attempt to differ from, so
        // "both ways" cannot be satisfied yet.
        if (previous == null) return emptyList()
        val wasPager = previous in PAGER_MODE_FLAGS
        val wasWebtoon = previous in WEBTOON_MODE_FLAGS
        if (!((wasPager && webtoon) || (wasWebtoon && pager))) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("webtoon_pager_both", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onMangaFinished(mangaId: Long = 0L): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (prefs.suppressOrganicForImport) return emptyList()
        // KMK --> Reached every time the reader lands on the last page of a series that is
        // already finished, so without this the tiers counted page views rather than series.
        // 0 means "no id supplied" and keeps counting, for callers that genuinely cannot tell.
        if (mangaId > 0L && !prefs.claimFinishedManga(mangaId)) return emptyList()
        prefs.incrementMangaFinished()
        val count = prefs.mangaFinishedCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_finished", unlocked)
        if (count >= 5) tryUnlock("five_manga_finished", unlocked)
        if (count >= 10) tryUnlock("ten_manga_finished", unlocked)
        if (count >= 20) tryUnlock("twenty_manga_finished", unlocked)
        if (count >= 50) tryUnlock("fifty_manga_finished", unlocked)
        // KMK --> Composed rather than delegated: onBacklogCleared used to notify on its own, so
        // finishing one manga produced two toast batches and its return value (which the caller
        // uses for the "what did this event unlock" list) silently dropped the backlog tiers.
        // onBacklogClearedQuiet counts without notifying or re-evaluating the library gate.
        // No backlog recompute here either: finishing implies the entry was already started, so
        // it cannot be in the stale-unstarted set. refreshBacklog covers the other triggers.
        // KMK <--
        unlocked += onBacklogClearedQuiet()
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onMangaCaughtUp(mangaId: Long = 0L): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (prefs.suppressOrganicForImport) return emptyList()
        // KMK --> Same claim as onMangaFinished. Kept in one shared set rather than two: a
        // series that is completed as "caught up" can later be completed for real, and that is
        // still the same piece of work, so it should be claimed once either way.
        if (mangaId > 0L && !prefs.claimFinishedManga(mangaId)) return emptyList()
        prefs.incrementMangaCaughtUp()
        val count = prefs.mangaCaughtUpCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_caught_up", unlocked)
        if (count >= 5) tryUnlock("five_manga_caught_up", unlocked)
        if (count >= 10) tryUnlock("ten_manga_caught_up", unlocked)
        if (count >= 20) tryUnlock("twenty_manga_caught_up", unlocked)
        if (count >= 50) tryUnlock("fifty_manga_caught_up", unlocked)
        // KMK --> Composed for the same reason as onMangaFinished: one event, one batch.
        // KMK <--
        unlocked += onBacklogClearedQuiet()
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    fun isPermanentStatus(status: Long): Boolean {
        return when (status.toInt()) {
            eu.kanade.tachiyomi.source.model.SManga.COMPLETED,
            eu.kanade.tachiyomi.source.model.SManga.CANCELLED,
            eu.kanade.tachiyomi.source.model.SManga.PUBLISHING_FINISHED,
            eu.kanade.tachiyomi.source.model.SManga.LICENSED,
            -> true
            else -> false
        }
    }

    @Synchronized
    fun onLibraryCountChanged(count: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = count.coerceIn(0L, 10_000L)
        prefs.setLibraryCount(safe)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("library_1", unlocked)
        if (safe >= 5) tryUnlock("library_5", unlocked)
        if (safe >= 10) tryUnlock("library_10", unlocked)
        if (safe >= 25) tryUnlock("library_25", unlocked)
        if (safe >= 50) tryUnlock("library_50", unlocked)
        if (safe >= 100) tryUnlock("library_100", unlocked)
        if (safe >= 250) tryUnlock("library_250", unlocked)
        if (safe >= 500) tryUnlock("library_500", unlocked)
        if (safe >= 1000) tryUnlock("library_1000", unlocked)
        // KMK --> Library membership changed, so the stale-unstarted set did too - but that needs
        // a query, and this entry point is synchronous and called from the library's own flows.
        // The caller refreshes with force = true instead.
        // KMK <--
        // The only event that moves the library count, so it is the only one that can satisfy
        // the library-side collection gate without an unlock happening first.
        checkCollectionProgress(unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onTrackerConnected(totalTrackers: Int): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = totalTrackers.coerceIn(0, 20)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("tracker_connected", unlocked)
        if (safe >= 2) tryUnlock("tracker_two", unlocked)
        if (safe >= 3) tryUnlock("tracker_three", unlocked)
        if (safe >= 5) tryUnlock("tracker_five", unlocked)
        if (safe >= 8) tryUnlock("tracker_all", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    /**
     * Counts one reread of a series and unlocks the reread tiers off a lifetime total.
     *
     * [count] is how many series this action started rereading, so a bulk reread counts that
     * many. It used to be compared against the tiers directly, which meant `reread_five` needed
     * a single call carrying a count of five - and the only call site passes one - so the three
     * lifetime tiers were unreachable. The tiers now read `counter("rereads")`, which the same
     * [count] advances, so description and number agree.
     *
     * Starting a reread also releases [mangaId]'s completion claim and run stamp. Completion is
     * claimed once per series precisely so that reopening a finished series does not count
     * again; a deliberate reread is new work, and both the completion tiers and the speedrun
     * clock have to be free for it.
     */
    @Synchronized
    fun onRereadStarted(mangaId: Long, count: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (count <= 0) return emptyList()
        if (mangaId > 0L) {
            prefs.clearFinishedManga(mangaId)
            prefs.clearMangaRun(mangaId)
        }
        return incrementCounter("rereads", count.coerceIn(1, 10_000))
    }

    @Synchronized
    fun onUpscaled(count: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = count.coerceIn(0L, 1_000_000L)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("upscale_first", unlocked)
        if (safe >= 10) tryUnlock("upscale_ten", unlocked)
        if (safe >= 100) tryUnlock("upscale_hundred", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun tryUnlockDirect(id: String): Boolean {
        if (!prefs.achievementsEnabled().get()) return false
        val out = mutableListOf<String>()
        tryUnlock(id, out)
        if (out.isNotEmpty()) notifyIfNeeded(out)
        return out.isNotEmpty()
    }

    @Synchronized
    fun onJxlDecoded(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("secret_jxl", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    private fun checkThresholds(count: Long): List<String> {
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_chapter", unlocked)
        if (count >= 10) tryUnlock("ten_chapters", unlocked)
        if (count >= 25) tryUnlock("twenty_five", unlocked)
        if (count >= 50) tryUnlock("fifty_chapters", unlocked)
        if (count >= 100) tryUnlock("hundred_chapters", unlocked)
        if (count >= 250) tryUnlock("two_fifty", unlocked)
        if (count >= 500) tryUnlock("five_hundred", unlocked)
        if (count >= 1000) tryUnlock("thousand", unlocked)
        if (count >= 2000) tryUnlock("two_thousand", unlocked)
        if (count >= 5000) tryUnlock("five_thousand", unlocked)
        if (count >= 10000) tryUnlock("ten_thousand", unlocked)
        if (count >= 20000) tryUnlock("ultimate_ink_god", unlocked)
        return unlocked
    }

    /**
     * Collection achievements are a function of the unlocked set and the library size, so they
     * only need re-evaluating when one of those two moves - never on every event. They used to
     * be checked from each handler's tail, which meant the per-chapter-read path re-parsed and
     * re-validated the whole unlocked CSV to evaluate two thresholds; [tryUnlock] is the one
     * place the set can change, and [onLibraryCountChanged] the one place the library can.
     */
    private fun checkCollectionProgress(out: MutableList<String>) {
        // Both conditions are monotone - the unlocked set and the library count only grow - so
        // once both are satisfied this can never fire again.
        if (prefs.isUnlocked(ULTIMATE_PERFECTION) && prefs.isUnlocked(ULTIMATE_ETERNAL_LIBRARY)) return
        val countable = prefs.getUnlockedIds().count { Achievements.forId(it)?.countsTowardsProgress == true }
        // The private tryUnlock, not tryUnlockDirect: this runs inside another event's batch, and
        // notifying here would split one event into two toast batches.
        if (countable >= 200) tryUnlock(ULTIMATE_PERFECTION, out)
        if (prefs.libraryMangaCount().get() >= 2000) tryUnlock(ULTIMATE_ETERNAL_LIBRARY, out)
    }

    private fun tryUnlock(id: String, out: MutableList<String>) {
        if (!prefs.achievementsEnabled().get()) return
        // KMK --> Onboarding is where the reader decides whether achievements exist at all, so
        // nothing may unlock before it is completed — a default-on preference would otherwise
        // earn its achievement before the reader had made any choice, and a reader who then
        // disables the system would be carrying unlocks they never consented to. The hold is
        // released by the app layer when onboarding finishes; see
        // [AchievementPreferences.unlocksHeldForOnboarding] for why the flag lives where it does.
        if (prefs.unlocksHeldForOnboarding) return
        // KMK <--
        if (prefs.unlock(id)) {
            out.add(id)
            // Collection achievements can only be reached by re-checking whenever the set grows,
            // so tryUnlock is the one funnel they all pass through. Without this they were
            // wait-and-see-forever: defined, given a progress bar, and no code path ever
            // evaluated them.
            unlockCollections(out)
            checkCollectionProgress(out)
        }
    }

    /**
     * Re-evaluates the achievements defined over the unlocked set rather than over a counter.
     *
     * Calls [AchievementPreferences.unlock] directly rather than [tryUnlock] on purpose:
     * routing back through tryUnlock would re-enter unlockCollections on every collection
     * unlock, which is unbounded recursion. Going straight to the pref store cannot re-enter.
     */
    private fun unlockCollections(out: MutableList<String>) {
        // KMK --> This runs on every successful unlock, and a restore can perform fifty of them
        // back to back. Deriving the tier lists here re-filtered the whole catalogue each time,
        // so they are resolved once per catalogue and cached; the sets they are derived from
        // (byId, all) are themselves already lazily built from the same source.
        // KMK <--
        val unlocked = prefs.getUnlockedIds()

        val secrets = unlocked.count { it in SECRET_IDS }
        if (secrets >= 10) unlockQuietly("secret_all_secret", out)
        if (secrets >= 20) unlockQuietly("ultimate_secret_hunter_ultimate", out)

        if (unlocked.count { it in MYTHIC_IDS } >= 5) unlockQuietly("secret_mythic_hoard", out)

        // "Every PLATINUM" reads as the non-secret tiers; the secret platinums are themselves
        // gated behind collection achievements, so counting them here would deadlock.
        if (OPEN_PLATINUM_IDS.isNotEmpty() && unlocked.containsAll(OPEN_PLATINUM_IDS)) {
            unlockQuietly("secret_platinum_club", out)
        }

        if (OPEN_IDS.isNotEmpty() && unlocked.containsAll(OPEN_IDS)) unlockQuietly("secret_100_percent", out)

        // "Complete all weekly dailies at least once" is a function of the unlocked set like the
        // others here, and was declared with no unlock site at all. It excludes itself: counting
        // a set that contains the achievement being awarded can never reach the set's size.
        if (WEEKLY_ROTATING_IDS.isNotEmpty() && unlocked.containsAll(WEEKLY_ROTATING_IDS)) {
            unlockQuietly(WEEKLY_PERFECT_WEEK, out)
        }
    }

    private fun unlockQuietly(id: String, out: MutableList<String>) {
        if (prefs.unlock(id)) out.add(id)
    }

    private fun notifyIfNeeded(unlocked: List<String>) {
        if (unlocked.isNotEmpty()) {
            // KMK --> notifier is non-null so unlocks always fan out to webhooks
            notifier.onUnlocked(unlocked)
            // KMK <--
        }
    }

    // KMK -->
    private companion object {
        val SECRET_IDS: Set<String> by lazy { Achievements.secrets.mapToIdSet() }
        val MYTHIC_IDS: Set<String> by lazy {
            Achievements.all.filterToIdSet { it.tier == AchievementTier.MYTHIC }
        }
        val OPEN_PLATINUM_IDS: Set<String> by lazy {
            Achievements.all.filterToIdSet { it.tier == AchievementTier.PLATINUM && !it.isSecret }
        }
        val OPEN_IDS: Set<String> by lazy {
            Achievements.all.filterToIdSet { !it.isSecret }
        }

        /** The weekly rotating catalogue minus the meta-tier that is measured against it. */
        val WEEKLY_ROTATING_IDS: Set<String> by lazy {
            Achievements.all.filterToIdSet { it.isRotating && it.id.startsWith("rotating_weekly") && it.id != WEEKLY_PERFECT_WEEK }
        }

        const val WEEKLY_PERFECT_WEEK = "rotating_weekly_extra_9"

        /**
         * Ids of the achievements matching [keep], for O(1) membership tests.
         *
         * Takes an [Iterable] rather than a [Sequence] because the catalogue accessors hand back
         * lists, and `Achievement` has to be projected to its id - filtering straight into a set
         * would collect the achievements themselves, which is not what every caller compares
         * against.
         */
        private fun Iterable<Achievement>.filterToIdSet(keep: (Achievement) -> Boolean): Set<String> {
            val ids = mutableSetOf<String>()
            for (achievement in this) {
                if (keep(achievement)) ids += achievement.id
            }
            return ids
        }

        private fun Iterable<Achievement>.mapToIdSet(): Set<String> = mapTo(mutableSetOf()) { it.id }

        const val ULTIMATE_PERFECTION = "ultimate_perfection"
        const val ULTIMATE_ETERNAL_LIBRARY = "ultimate_eternal_library"

        const val REFRESH_THROTTLE_MS = 15L * 60L * 1000L
        // A single missed day does not end a streak, so "read N days in a row" tolerates one gap.
        const val STREAK_GRACE_DAYS = 1L

        // "Complete 7 manga in 7 days": the window is inclusive of today, hence `0 until N`.
        const val PERFECT_WEEK_DAYS = 7L
        const val BINGE_HUNDRED = 100L
        // Days away after a 100-chapter binge before it counts as a collapse.
        const val BINGE_GUILT_MIN_DAYS = 3L

        /**
         * Reading-mode flag values, duplicated here as plain ints because `ReadingMode` lives in
         * the app module. Only the pager-vs-webtoon split matters to this class, and that split
         * is a property of the enum that does not change: LTR, RTL and vertical are pagers,
         * webtoon and continuous vertical are the continuous viewers. `DEFAULT` (0) is in
         * neither, because a resolved default is not a viewer the user chose.
         */
        val PAGER_MODE_FLAGS = setOf(1, 3)
        val WEBTOON_MODE_FLAGS = setOf(4, 5)

        /**
         * Days are the reader's, not Greenwich's.
         *
         * This used to divide `System.currentTimeMillis()` by the epoch-day length, which counts
         * UTC days. Every time-driven achievement inherited that: a reader east of Greenwich was
         * credited with Friday-evening reads on Saturday (the weekend window opened at 8 PM on
         * the wrong day), and anyone whose local midnight fell after 00:00 UTC had their streak
         * and daily counters turn over during the evening. `LocalDate` resolves the day in the
         * device's own zone, which is what "today" means to the person holding the phone.
         *
         * Stored stamps stay valid: a value written under the old rule is still a day number, it
         * just sits at most one day away from where the same wall-clock day now lands, and every
         * comparison here is between two stamps written by the same rule.
         */
        fun currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

        /** Counter name -> ordered (count, achievement id) tiers. */
        val COUNTER_TIERS: Map<String, List<Pair<Long, String>>> = mapOf(
            "downloads" to listOf(
                1L to "download_one",
                10L to "download_ten",
                100L to "download_hundred",
                1000L to "download_thousand",
            ),
            "custom_covers" to listOf(10L to "cover_ten"),
            "incognito_reads" to listOf(10L to "incognito_reader"),
            "categories" to listOf(5L to "category_master", 10L to "category_ten"),
            "translated_chapters" to listOf(
                1L to "translator",
                5L to "translator_five",
                10L to "translator_ten",
                50L to "translator_fifty",
                100L to "translator_hundred",
            ),
            "sources" to listOf(5L to "sources_five", 10L to "sources_ten"),
            "subcategories" to listOf(
                1L to "subcategory_creator",
                5L to "subcategory_five",
                20L to "subcategory_twenty",
            ),
            "tracker_updates" to listOf(10L to "track_status"),
            // Behaviour counters, same mechanism: a one-off misbehaviour rather than a pile.
            "empty_searches" to listOf(5L to "secret_404"),
            "moans" to listOf(50L to "secret_pillow"),
            "language_changes" to listOf(10L to "secret_uwu"),
            "dropped_unfinished" to listOf(10L to "negative_rage_quit"),
            "spoiler_jumps" to listOf(1L to "negative_spoiled"),
            "webgpu_rescues" to listOf(1L to "secret_webgpu_rescue"),
            // KMK --> Lifetime rereads. These were compared against the per-action count instead,
            // so reread_five needed one call carrying five and nothing ever passed one.
            "rereads" to listOf(
                1L to "rereader",
                5L to "reread_five",
                20L to "reread_twenty",
                100L to "reread_hundred",
            ),
            // KMK --> The mango easter egg is countable, so "find it twice" is a tier rather
            // than a second hand-written call site beside the first.
            "mango_easter" to listOf(2L to "secret_mango_double"),
        )

        /** Counters that reset at the day boundary; same tier shape. */
        val DAILY_COUNTER_TIERS: Map<String, List<Pair<Long, String>>> = mapOf(
            "binge" to listOf(10L to "binge_10", 50L to "binge_50", 100L to "binge_100"),
            "midnight_reads" to listOf(100L to "secret_midnight_100"),
            "late_night_reads" to listOf(5L to "negative_midnight_oil"),
            "after_midnight_reads" to listOf(10L to "night_owl"),
        )
    }
    // KMK <--

    fun wipeWithConfirmation(firstConfirmed: Boolean, secondConfirmed: Boolean): Boolean {
        if (!firstConfirmed || !secondConfirmed) return false
        // The counter names are supplied here because this class owns the catalogue; without
        // them the wipe left lifetime counters populated and the tiers built on them
        // unreachable for the rest of the install.
        prefs.wipe(
            counterNames = COUNTER_TIERS.keys,
            dailyCounterNames = DAILY_COUNTER_TIERS.keys,
        )
        return true
    }
}
