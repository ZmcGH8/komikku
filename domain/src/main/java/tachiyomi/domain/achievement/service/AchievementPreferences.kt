package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.achievement.model.Achievements

@SingleIn(AppScope::class)
@Inject
class AchievementPreferences(
    private val preferenceStore: PreferenceStore,
) {
    // KMK --> Longest streak tier is 100, so 200 days of history covers it with slack and keeps
    // the stored list small.
    private companion object {
        const val READING_DAY_WINDOW = 200

        // Completion bookkeeping. The reader re-displays the last page of an already finished
        // series every time the user opens it, and `ReaderViewModel` only remembers what it
        // credited for the life of one session. Counting a series once therefore needs state
        // that outlives the process, or "complete 5 manga" is reachable by reading one series
        // five times. Bounded, oldest-out, for the same reason `upscalePageCounts` is.
        const val FINISHED_MANGA_LIMIT = 500
        const val FINISHED_DAY_WINDOW = 200

        // Per-manga start stamps, only ever read by the speedrun secret. Short window on
        // purpose: a stamp older than this can no longer contribute to an under-an-hour run.
        const val MANGA_START_LIMIT = 50

        /**
         * Upper bound on a run before [mangaRunElapsedMs] stops reporting one at all. A stamp
         * left behind by a session that never finished would otherwise report an elapsed time
         * of weeks, and "under an hour" would become "not since March" rather than nothing.
         */
        const val MAX_RUN_MS = 24L * 60L * 60L * 1000L

        // One parser per value type rather than each accessor re-implementing the split. `substringAfter`
        // keeps the value on its own token, so an id or value that somehow contains a separator
        // cannot swallow the next entry - a malformed pref then drops one record, not two.
        private fun readIdSet(raw: String): Set<Long> =
            raw.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toLongOrNull() }

        private fun readIntMap(raw: String): Map<Long, Int> =
            raw.splitToSequence(',')
                .mapNotNull { entry ->
                    val id = entry.substringBefore(':').trim().toLongOrNull()
                    val value = entry.substringAfter(':', "").trim().toIntOrNull()
                    if (id == null || value == null) null else id to value
                }
                .toMap()

        private fun readLongMap(raw: String): Map<Long, Long> =
            raw.splitToSequence(',')
                .mapNotNull { entry ->
                    val id = entry.substringBefore(':').trim().toLongOrNull()
                    val value = entry.substringAfter(':', "").trim().toLongOrNull()
                    if (id == null || value == null) null else id to value
                }
                .toMap()

        private fun readDayList(raw: String): List<Long> =
            raw.split(',').mapNotNull { it.trim().toLongOrNull() }

        /** Keeps the newest [FINISHED_MANGA_LIMIT] ids, oldest out, so the pref cannot grow forever. */
        private fun boundedIds(ids: List<Long>): String =
            ids.takeLast(FINISHED_MANGA_LIMIT).joinToString(",")

        // `entries` is a Set, so it has to become a List before `takeLast` - the insertion
        // order of the parsed map is what makes "newest last" the right end to trim.
        private fun boundedIntMap(map: Map<Long, Int>): String =
            map.entries.toList().takeLast(FINISHED_MANGA_LIMIT).joinToString(",") { "${it.key}:${it.value}" }
    }
    fun achievementsEnabled() = preferenceStore.getBoolean("pref_achievements_enabled", true)
    fun achievementToastsEnabled() = preferenceStore.getBoolean("pref_achievement_toasts_enabled", true)
    fun achievementSoundsEnabled() = preferenceStore.getBoolean("pref_achievement_sounds_enabled", false)

    fun unlockedAchievements() = preferenceStore.getString("pref_unlocked_achievements", "")
    fun organicChaptersRead() = preferenceStore.getLong("pref_organic_chapters_read", 0)
    fun mangaFinishedCount() = preferenceStore.getLong("pref_achievement_manga_finished", 0)
    fun mangaCaughtUpCount() = preferenceStore.getLong("pref_achievement_manga_caught_up", 0)
    fun libraryMangaCount() = preferenceStore.getLong("pref_achievement_library_count", 0)
    fun unlockedTimestamps() = preferenceStore.getString("pref_achievement_timestamps", "")
    fun totalReadingTimeMinutes() = preferenceStore.getLong("pref_achievement_reading_time_minutes", 0)
    fun backlogClearedCount() = preferenceStore.getLong("pref_achievement_backlog_cleared", 0)
    // KMK --> Cached because computeStats is synchronous and the count needs a library query;
    // AchievementManager.refreshBacklog owns writing it.
    fun staleUnstartedCount() = preferenceStore.getLong("pref_achievement_stale_unstarted", 0)
    fun backlogLastRefresh() = preferenceStore.getLong("pref_achievement_backlog_refresh", 0)
    // KMK <--
    // KMK --> Day epochs (local, see AchievementManager.currentEpochDay) on which a chapter was read,
    // comma separated, oldest first.
    // Bounded to the trailing window the longest streak needs, so it cannot grow without limit.
    fun readingDays() = preferenceStore.getString("pref_achievement_reading_days", "")

    // Direction-switch streak for the flip-phone secret: the last direction and its run length.
    fun directionStreakDir() = preferenceStore.getInt("pref_achievement_dir_streak_dir", -1)
    fun directionStreakCount() = preferenceStore.getInt("pref_achievement_dir_streak_count", 0)

    // KMK --> Completion bookkeeping, all keyed by manga id so the same shape can be reused.
    fun finishedMangaIds() = preferenceStore.getString("pref_achievement_finished_manga_ids", "")
    fun mangaCompletionModes() = preferenceStore.getString("pref_achievement_completion_modes", "")
    fun mangaStartTimes() = preferenceStore.getString("pref_achievement_manga_start_times", "")
    fun finishedDays() = preferenceStore.getString("pref_achievement_finished_days", "")
    // Last epoch day on which the user skipped ahead without reading, for the "perfect day"
    // rotating tier. -1 rather than 0 so "never skipped" cannot collide with epoch day 0.
    fun lastSkippedDay() = preferenceStore.getLong("pref_achievement_skipped_day", -1L)
    // Epoch day a 100-chapter binge happened on; the binge-guilt negative is judged against it.
    fun bingeHundredDay() = preferenceStore.getLong("pref_achievement_binge_hundred_day", -1L)

    @Synchronized
    fun markReadingDay(epochDay: Long) {
        val days = readDays()
        if (days.contains(epochDay)) return
        val updated = (days + epochDay).sorted().takeLast(READING_DAY_WINDOW)
        readingDays().set(updated.joinToString(","))
    }

    fun readDays(): List<Long> = readDayList(readingDays().get())

    // Chapters read in the current Sat+Sun window, and the Saturday's epoch day as the key. Both
    // reset together, so a new weekend starts from zero without a scheduled cleanup.
    fun weekendReadKey() = preferenceStore.getLong("pref_achievement_weekend_key", 0L)
    fun weekendReadCount() = preferenceStore.getLong("pref_achievement_weekend_count", 0L)

    /**
     * Claims [mangaId] as "already credited as completed". Returns false when it was claimed
     * before, which is what makes the completion tiers count series rather than page turns.
     *
     * [AchievementManager.onMangaFinished] is reached every time the reader displays the last
     * page of a finished series, not just the first time, so the guard has to be durable. It
     * is released again by a real reread (`clearFinishedManga`), so re-reading a series does
     * earn its tiers a second time.
     */
    @Synchronized
    fun claimFinishedManga(mangaId: Long): Boolean {
        if (mangaId <= 0L) return false
        val ids = readIdSet(finishedMangaIds().get()).toMutableList()
        if (mangaId in ids) return false
        ids += mangaId
        finishedMangaIds().set(boundedIds(ids))
        return true
    }

    @Synchronized
    fun clearFinishedManga(mangaId: Long) {
        if (mangaId <= 0L) return
        val ids = readIdSet(finishedMangaIds().get()).toMutableList()
        if (!ids.remove(mangaId)) return
        finishedMangaIds().set(boundedIds(ids))
    }

    /** Which reading-mode bucket this manga was last completed in, for "both pager and webtoon". */
    @Synchronized
    fun recordCompletionMode(mangaId: Long, modeFlag: Int) {
        if (mangaId <= 0L) return
        val map = readIntMap(mangaCompletionModes().get()).toMutableMap()
        map[mangaId] = modeFlag
        mangaCompletionModes().set(boundedIntMap(map))
    }

    fun completionModes(): Map<Long, Int> = readIntMap(mangaCompletionModes().get())

    /**
     * Stamps the moment reading of [mangaId] first produced a chapter read, and returns the
     * elapsed milliseconds at [nowMs]. Only the earliest stamp is kept: the speedrun secret
     * measures the whole run, so re-entering the series must not restart the clock.
     *
     * Returns -1 when no usable stamp is available, which the caller treats as "not a run".
     */
    @Synchronized
    fun mangaRunElapsedMs(mangaId: Long, nowMs: Long): Long {
        if (mangaId <= 0L) return -1L
        val stamps = readLongMap(mangaStartTimes().get()).toMutableMap()
        val existing = stamps[mangaId]
        val startedAt = existing ?: nowMs
        if (existing == null) stamps[mangaId] = startedAt
        // Only written when this call actually changed something. It runs once per chapter
        // read, and rewriting an unchanged pref on every one of them is pure disk churn.
        if (stamps.size > MANGA_START_LIMIT) {
            // Oldest first: drop the runs least likely to still be open.
            stamps.keys.sortedBy { stamps[it] }.take(stamps.size - MANGA_START_LIMIT).forEach {
                stamps.remove(it)
            }
            mangaStartTimes().set(stamps.entries.joinToString(",") { "${it.key}:${it.value}" })
        } else if (existing == null) {
            mangaStartTimes().set(stamps.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
        val elapsed = nowMs - startedAt
        return if (elapsed in 0..MAX_RUN_MS) elapsed else -1L
    }

    /** Forgets [mangaId]'s run stamp, so a reread measures a fresh run. */
    @Synchronized
    fun clearMangaRun(mangaId: Long) {
        if (mangaId <= 0L) return
        val stamps = readLongMap(mangaStartTimes().get()).toMutableMap()
        if (stamps.remove(mangaId) == null) return
        mangaStartTimes().set(stamps.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    /** Records that a series was completed today, for the "7 completions in 7 days" secret. */
    @Synchronized
    fun markFinishedDay(epochDay: Long) {
        val days = readDayList(finishedDays().get())
        if (epochDay in days) return
        finishedDays().set((days + epochDay).sorted().takeLast(FINISHED_DAY_WINDOW).joinToString(","))
    }

    fun finishDays(): List<Long> = readDayList(finishedDays().get())

    /**
     * Named lifetime counter, keyed off a shared prefix so a new counter needs no accessor.
     * Only the names in AchievementManager.COUNTER_TIERS are read; anything else is ignored.
     */
    fun counter(name: String) = preferenceStore.getLong("pref_achievement_ctr_$name", 0)

    /** Day-stamped counters (binge) reset when [dayEpoch] no longer matches the stored one. */
    fun dailyCounter(name: String) = preferenceStore.getLong("pref_achievement_day_$name", 0)

    /**
     * The day a given daily counter was last bumped on.
     *
     * Per counter, not one shared stamp: the counters are independent ("chapters read today" and
     * "chapters read after midnight" are separate tiers and a reader has plenty of nights where
     * only one of them moves). A single stamp meant whichever counter happened to be bumped first
     * each day published *its* day for all of them, so the others carried yesterday's total into
     * today and unlocked at, say, four chapters instead of ten.
     */
    fun dailyCounterDay(name: String) = preferenceStore.getLong("pref_achievement_day_epoch_$name", 0)

    @Synchronized
    fun incrementCounter(name: String, amount: Int = 1) {
        if (amount <= 0) return
        val cur = counter(name).get()
        if (cur < 10_000_000L) counter(name).set(cur + amount)
    }

    /**
     * Bumps [name] for [dayEpoch] and returns the new count, resetting first when the day has
     * rolled over. Only the named counter is reset; the others keep their own stamps.
     */
    @Synchronized
    fun bumpDailyCounter(name: String, dayEpoch: Long): Long {
        if (dailyCounterDay(name).get() != dayEpoch) {
            dailyCounterDay(name).set(dayEpoch)
            dailyCounter(name).set(0)
        }
        val cur = dailyCounter(name).get()
        val next = cur + 1
        dailyCounter(name).set(next)
        return next
    }
    // KMK <--
    fun animationsEnabled() = preferenceStore.getBoolean("pref_achievement_animations_enabled", true)
    fun rotatingLastDailyEpoch() = preferenceStore.getLong("pref_achievement_rotating_daily_epoch", 0)
    fun rotatingLastWeeklyEpoch() = preferenceStore.getLong("pref_achievement_rotating_weekly_epoch", 0)
    fun rotatingDailyIds() = preferenceStore.getString("pref_achievement_rotating_daily_ids", "")
    fun rotatingWeeklyIds() = preferenceStore.getString("pref_achievement_rotating_weekly_ids", "")
    fun rotatingProgress() = preferenceStore.getString("pref_achievement_rotating_progress", "")
    // KMK --> Day stamps for the "once per day" rotating ids, so a page turn cannot fill a
    // multi-day tier on its own. Format is id:epochDay,id:epochDay.
    fun rotatingDayStamps() = preferenceStore.getString("pref_achievement_rotating_day_stamps", "")

    /**
     * Parsed views of the two rotating CSVs. The pool owns the policy around them; the format
     * lives here with the other accessors, so [tachiyomi.domain.achievement.model.AchievementProgress]
     * can read a rotating bar without duplicating the parser.
     */
    fun rotatingProgressMap(): Map<String, Int> =
        rotatingProgress().get().splitToSequence(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').trim()
                val value = entry.substringAfter(':', "").trim().toIntOrNull()
                if (id.isEmpty() || value == null) null else id to value
            }
            .toMap()

    fun setRotatingProgress(progress: Map<String, Int>) {
        rotatingProgress().set(progress.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    fun rotatingDayStampMap(): Map<String, Long> =
        rotatingDayStamps().get().splitToSequence(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').trim()
                val day = entry.substringAfter(':', "").trim().toLongOrNull()
                if (id.isEmpty() || day == null) null else id to day
            }
            .toMap()

    fun setRotatingDayStamps(stamps: Map<String, Long>) {
        rotatingDayStamps().set(stamps.entries.joinToString(",") { "${it.key}:${it.value}" })
    }
    fun upscalesServed() = preferenceStore.getLong("pref_achievement_upscales_served", 0)
    fun upscalePageCounts() = preferenceStore.getString("pref_achievement_upscale_page_counts", "")

    @Synchronized
    fun incrementOrganicRead() {
        if (suppressOrganicForImport) return
        val cur = organicChaptersRead().get()
        if (cur < 1_000_000L) organicChaptersRead().set(cur + 1)
    }

    @Synchronized
    fun incrementMangaFinished() {
        if (suppressOrganicForImport) return
        val cur = mangaFinishedCount().get()
        if (cur < 1_000_000L) mangaFinishedCount().set(cur + 1)
    }

    @Synchronized
    fun incrementMangaCaughtUp() {
        if (suppressOrganicForImport) return
        val cur = mangaCaughtUpCount().get()
        if (cur < 1_000_000L) mangaCaughtUpCount().set(cur + 1)
    }

    @Synchronized
    fun setLibraryCount(count: Long) {
        libraryMangaCount().set(count.coerceIn(0L, 10_000L))
    }

    @Synchronized
    fun addReadingTimeMinutes(minutes: Long) {
        if (minutes <= 0) return
        if (suppressOrganicForImport) return
        val cur = totalReadingTimeMinutes().get()
        totalReadingTimeMinutes().set((cur + minutes).coerceIn(0L, 10_000_000L))
    }

    @Synchronized
    fun incrementBacklogCleared() {
        val cur = backlogClearedCount().get()
        if (cur < 1_000_000L) backlogClearedCount().set(cur + 1)
    }

    // KMK -->
    @Synchronized
    fun setStaleUnstartedCount(count: Long) {
        staleUnstartedCount().set(count.coerceIn(0L, 10_000L))
    }
    // KMK <--

    @Synchronized
    fun incrementUpscalesServed(): Long {
        val next = (upscalesServed().get() + 1).coerceIn(0L, 1_000_000L)
        upscalesServed().set(next)
        return next
    }

    @Synchronized
    fun incrementUpscalePageCount(cacheKey: String): Int {
        if (cacheKey.isBlank()) return 0
        val counts = linkedMapOf<String, Int>()
        upscalePageCounts().get().split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                val key = parts.getOrNull(0)?.trim().orEmpty()
                val count = parts.getOrNull(1)?.toIntOrNull()
                if (key.isBlank() || key.length > 64 || count == null || count <= 0) {
                    null
                } else {
                    key to count
                }
            }
            .forEach { (key, count) -> counts[key] = count }
        val next = ((counts[cacheKey] ?: 0) + 1).coerceIn(1, 10_000)
        counts[cacheKey] = next
        while (counts.size > 200) {
            counts.remove(counts.keys.first())
        }
        upscalePageCounts().set(counts.entries.joinToString(",") { "${it.key}:${it.value}" })
        return next
    }

    @Synchronized
    fun unlock(id: String): Boolean {
        if (id.isBlank() || id.length > 64) return false
        if (tachiyomi.domain.achievement.model.Achievements.forId(id) == null) return false
        if (isUnlocked(id)) return false
        val current = unlockedAchievements().get()
        val set = if (current.isBlank()) mutableSetOf<String>() else current.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        if (set.size >= 300) return false
        if (!set.add(id)) return false
        unlockedAchievements().set(set.joinToString(","))
        val ts = unlockedTimestamps().get()
        val entry = "$id:${System.currentTimeMillis()}"
        val newTs = if (ts.isBlank()) entry else "$ts,$entry"
        if (newTs.length > 16_000) {
            val trimmed = newTs.split(",").takeLast(200).joinToString(",")
            unlockedTimestamps().set(trimmed)
        } else {
            unlockedTimestamps().set(newTs)
        }
        return true
    }

    @Synchronized
    fun isUnlocked(id: String): Boolean {
        val current = unlockedAchievements().get()
        if (current.isBlank()) return false
        // KMK --> Membership without building the list. Every tier check calls this, and a
        // chapter read runs ~30 of them, so each was allocating a split copy of the whole
        // unlocked set to answer a yes/no. Suffixing both sides turns the stored CSV into an
        // unambiguous delimited token match - no id contains a comma, and the same answer comes
        // out for hand-edited or restored values, which split() would also have missed.
        // KMK <--
        return ",$current,".contains(",$id,")
    }

    @Synchronized
    fun getUnlockedIds(): Set<String> {
        val current = unlockedAchievements().get()
        if (current.isBlank()) return emptySet()
        return current.split(",").map { it.trim() }.filter { it.isNotBlank() && it.length <= 64 && Achievements.forId(it) != null }.toSet()
    }

    // KMK --> `unlockedTimestamps` is written on every unlock but had no reader: its only
    // consumer was a manager accessor nothing called. The recording is kept rather than
    // removed - it is accumulated user data, it costs one append per unlock, and the tier
    // animation and any future "unlocked on" display both want it. What was removed is the
    // dead parser, so there is now exactly one format to keep in step.

    @Volatile
    var suppressOrganicForImport: Boolean = false

    // KMK -->
    /**
     * In-memory: unlocks are dropped while onboarding has not been completed.
     *
     * Onboarding is where the reader decides whether achievements exist at all — it has an
     * enable switch — so anything unlocked before that step was answered would be recorded for a
     * user who then turns the whole system off. Even for one who keeps it on, the feature hooks
     * evaluate their settings once at install: a value that is true merely because it *shipped*
     * true would earn its achievement before the reader had made any choice at all.
     *
     * An in-memory mirror of the app layer's onboarding-complete preference rather than a read of
     * it: this module cannot see the class that owns the key, and duplicating a key string here
     * would be a second answer that drifts. The app arms it on every start from the real
     * preference and releases it when onboarding finishes; a backup restore is unaffected because
     * it rewrites the unlocked set directly, and one started from onboarding happens after the
     * hold is released anyway. See [AchievementManager.tryUnlock] for the gate itself.
     */
    @Volatile
    var unlocksHeldForOnboarding: Boolean = false
    // KMK <--

    @Synchronized
    fun computeStats(totalAchievements: Int): tachiyomi.domain.achievement.model.AchievementStats {
        val ids = getUnlockedIds()
        val countableIds = ids.filter { tachiyomi.domain.achievement.model.Achievements.forId(it)?.countsTowardsProgress == true }
        val unlocked = countableIds.size
        val secretUnlocked = ids.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.isSecret == true }
        val negatives = ids.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.isNegative == true }
        val backlog = staleUnstartedCount().get().coerceAtLeast(0L)
        return tachiyomi.domain.achievement.model.AchievementStats(
            organicChaptersRead = organicChaptersRead().get().coerceAtLeast(0L),
            mangaFinished = mangaFinishedCount().get().coerceAtLeast(0L),
            mangaCaughtUp = mangaCaughtUpCount().get().coerceAtLeast(0L),
            libraryCount = libraryMangaCount().get().coerceAtLeast(0L),
            totalAchievements = totalAchievements.coerceAtLeast(0),
            unlockedCount = unlocked,
            secretUnlocked = secretUnlocked,
            readingTimeMinutes = totalReadingTimeMinutes().get().coerceAtLeast(0L),
            backlogCount = backlog,
            negativeUnlocked = negatives,
        )
    }

    /**
     * Clears every achievement-scoped value.
     *
     * [counterNames] and [dailyCounterNames] are supplied by the caller because the catalogue of
     * lifetime counters lives with [AchievementManager]. Wiping without them left those counters
     * (downloads, categories, sources, subcategories, tracker updates, ...) populated, so a
     * user who wiped and then re-created ten categories could never re-earn `category_ten` -
     * the tier had already been "consumed" against a counter that was never reset.
     */
    @Synchronized
    fun wipe(counterNames: Collection<String> = emptyList(), dailyCounterNames: Collection<String> = emptyList()) {
        // Only touch counters that hold something, so a wipe does not materialise preferences
        // for tiers the user never came near.
        counterNames.forEach { if (counter(it).get() != 0L) counter(it).set(0) }
        // The day stamp travels with its counter: resetting only the count would leave the stamp
        // pointing at today, so the first bump after a wipe would carry the old day over.
        dailyCounterNames.forEach {
            if (dailyCounter(it).get() != 0L) dailyCounter(it).set(0)
            dailyCounterDay(it).set(0)
        }
        directionStreakDir().set(-1)
        directionStreakCount().set(0)
        weekendReadKey().set(0)
        weekendReadCount().set(0)

        unlockedAchievements().set("")
        organicChaptersRead().set(0)
        mangaFinishedCount().set(0)
        mangaCaughtUpCount().set(0)
        libraryMangaCount().set(0)
        totalReadingTimeMinutes().set(0)
        backlogClearedCount().set(0)
        // KMK -->
        staleUnstartedCount().set(0)
        backlogLastRefresh().set(0)
        readingDays().set("")
        // KMK <--
        // KMK --> Completion bookkeeping. Left populated, the claim set would keep refusing
        // completions the user has every right to count again after a wipe, and the run stamps
        // would keep reporting elapsed times measured before it.
        // KMK <--
        finishedMangaIds().set("")
        mangaCompletionModes().set("")
        mangaStartTimes().set("")
        finishedDays().set("")
        lastSkippedDay().set(-1L)
        bingeHundredDay().set(-1L)
        upscalesServed().set(0)
        upscalePageCounts().set("")
        rotatingDailyIds().set("")
        rotatingWeeklyIds().set("")
        rotatingProgress().set("")
        rotatingDayStamps().set("")
        rotatingLastDailyEpoch().set(0)
        rotatingLastWeeklyEpoch().set(0)
        unlockedTimestamps().set("")
    }

    fun isEnabled(): Boolean = achievementsEnabled().get()
}
