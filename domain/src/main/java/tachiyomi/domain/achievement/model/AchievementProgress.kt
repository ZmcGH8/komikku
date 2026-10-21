package tachiyomi.domain.achievement.model

import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.RotatingAchievementPool

/**
 * Pseudo-framework: per-achievement progress tracking.
 * Centralizes threshold mapping so UI and manager share one source of truth.
 * Usage: AchievementProgress.progressFor(id, prefs) -> 0f..1f, or labelFor().
 */
object AchievementProgress {

    // Thresholds for countable numeric achievements. Null = no numeric progress (binary).
    private val thresholds: Map<String, Long> = mapOf(
        // Reading organic
        "first_chapter" to 1,
        "ten_chapters" to 10,
        "twenty_five" to 25,
        "fifty_chapters" to 50,
        "hundred_chapters" to 100,
        "two_fifty" to 250,
        "five_hundred" to 500,
        "thousand" to 1000,
        "two_thousand" to 2000,
        "five_thousand" to 5000,
        "ten_thousand" to 10000,
        "ultimate_ink_god" to 20000,
        // Manga finished
        "first_manga_finished" to 1,
        "five_manga_finished" to 5,
        "ten_manga_finished" to 10,
        "twenty_manga_finished" to 20,
        "fifty_manga_finished" to 50,
        // Caught up
        "first_manga_caught_up" to 1,
        "five_manga_caught_up" to 5,
        "ten_manga_caught_up" to 10,
        "twenty_manga_caught_up" to 20,
        "fifty_manga_caught_up" to 50,
        // Library
        "library_1" to 1,
        "library_5" to 5,
        "library_10" to 10,
        "library_25" to 25,
        "library_50" to 50,
        "library_100" to 100,
        "library_250" to 250,
        "library_500" to 500,
        "library_1000" to 1000,
        "ultimate_eternal_library" to 2000,
        // Reading time (minutes)
        "reading_time_1h" to 60,
        "reading_time_10h" to 600,
        "reading_time_50h" to 3000,
        "reading_time_100h" to 6000,
        "reading_time_500h" to 30000,
        "reading_time_1000h" to 60000,
        "ultimate_time_dilation" to 120000,
        // Backlog
        "backlog_10" to 10,
        "backlog_25" to 25,
        "backlog_50" to 50,
        "backlog_100" to 100,
        "backlog_250" to 250,
        "backlog_cleared_10" to 10,
        "backlog_cleared_100" to 100,
        // KMK --> Counter tiers, matching AchievementManager.COUNTER_TIERS exactly.
        "download_one" to 1,
        "download_ten" to 10,
        "download_hundred" to 100,
        "download_thousand" to 1000,
        "cover_ten" to 10,
        "category_master" to 5,
        "category_ten" to 10,
        "sources_five" to 5,
        "sources_ten" to 10,
        "subcategory_creator" to 1,
        "subcategory_five" to 5,
        "subcategory_twenty" to 20,
        "track_status" to 10,
        // KMK --> Daily counters, matching AchievementManager.DAILY_COUNTER_TIERS. Absent
        // before, so these bars could never render even though the tiers were earning.
        "binge_10" to 10,
        "binge_50" to 50,
        "binge_100" to 100,
        "night_owl" to 10,
        "incognito_reader" to 10,
        "streak_3" to 3,
        "streak_7" to 7,
        "streak_30" to 30,
        "streak_100" to 100,
        // KMK <--
        // Reread
        "rereader" to 1,
        "reread_five" to 5,
        "reread_twenty" to 20,
        "reread_hundred" to 100,
        // Translation
        "translator" to 1,
        "translator_five" to 5,
        "translator_ten" to 10,
        "translator_fifty" to 50,
        "translator_hundred" to 100,
        // Upscale
        "upscale_first" to 1,
        "upscale_ten" to 10,
        "upscale_hundred" to 100,
        // Trackers
        "tracker_connected" to 1,
        "tracker_two" to 2,
        "tracker_three" to 3,
        "tracker_five" to 5,
        "tracker_all" to 8,
        // Ultimate perfection
        "ultimate_perfection" to 200,
        "ultimate_secret_hunter_ultimate" to 20,
    )

    fun thresholdFor(id: String): Long? {
        // KMK --> The rotating pool owns its own targets - they gate the unlock, so a second
        // copy here would be a second thing to keep in step. Delegating is also what makes
        // their bars appear at all: they were absent from the table above, so every rotating
        // achievement reported no progress regardless of how far along it was.
        Achievements.forId(id)?.takeIf { it.isRotating }?.let {
            return RotatingAchievementPool.thresholdFor(id).toLong()
        }
        return thresholds[id]
    }

    fun currentFor(id: String, prefs: AchievementPreferences): Long = when (id) {
        // Organic reading
        in setOf("first_chapter", "ten_chapters", "twenty_five", "fifty_chapters", "hundred_chapters", "two_fifty", "five_hundred", "thousand", "two_thousand", "five_thousand", "ten_thousand", "ultimate_ink_god") ->
            prefs.organicChaptersRead().get().coerceAtLeast(0)
        // Manga finished
        in setOf("first_manga_finished", "five_manga_finished", "ten_manga_finished", "twenty_manga_finished", "fifty_manga_finished") ->
            prefs.mangaFinishedCount().get().coerceAtLeast(0)
        // Caught up
        in setOf("first_manga_caught_up", "five_manga_caught_up", "ten_manga_caught_up", "twenty_manga_caught_up", "fifty_manga_caught_up") ->
            prefs.mangaCaughtUpCount().get().coerceAtLeast(0)
        // Library
        in setOf("library_1", "library_5", "library_10", "library_25", "library_50", "library_100", "library_250", "library_500", "library_1000", "ultimate_eternal_library") ->
            prefs.libraryMangaCount().get().coerceAtLeast(0)
        // Reading time
        in setOf("reading_time_1h", "reading_time_10h", "reading_time_50h", "reading_time_100h", "reading_time_500h", "reading_time_1000h", "ultimate_time_dilation") ->
            prefs.totalReadingTimeMinutes().get().coerceAtLeast(0)
        // Backlog
        in setOf("backlog_10", "backlog_25", "backlog_50", "backlog_100", "backlog_250") ->
            prefs.staleUnstartedCount().get().coerceAtLeast(0)
        in setOf("backlog_cleared_10", "backlog_cleared_100") ->
            prefs.backlogClearedCount().get().coerceAtLeast(0)
        // KMK --> Named counters, same names as AchievementManager.COUNTER_TIERS so the bar and
        // the unlock tiers read the same number.
        in setOf("download_one", "download_ten", "download_hundred", "download_thousand") ->
            prefs.counter("downloads").get().coerceAtLeast(0)
        "cover_ten" -> prefs.counter("custom_covers").get().coerceAtLeast(0)
        in setOf("category_master", "category_ten") ->
            prefs.counter("categories").get().coerceAtLeast(0)
        in setOf("sources_five", "sources_ten") ->
            prefs.counter("sources").get().coerceAtLeast(0)
        in setOf("subcategory_creator", "subcategory_five", "subcategory_twenty") ->
            prefs.counter("subcategories").get().coerceAtLeast(0)
        "track_status" -> prefs.counter("tracker_updates").get().coerceAtLeast(0)
        in setOf("binge_10", "binge_50", "binge_100") ->
            prefs.dailyCounter("binge").get().coerceAtLeast(0)
        in setOf("streak_3", "streak_7", "streak_30", "streak_100") ->
            prefs.readDays().size.toLong().coerceAtLeast(0)
        // KMK <--
        // KMK --> Read off the same counters the tiers are evaluated against, so a bar and a tier can
        // never disagree: rereads from `rereads`, translations from `translated_chapters`,
        // incognito from `incognito_reads`. These used to report 0 unconditionally, so their
        // bars sat at 0% even once the tiers were earning.
        // Trackers: special, need injected count — handled via unlocked check only.
        "rereader", "reread_five", "reread_twenty", "reread_hundred" ->
            prefs.counter("rereads").get().coerceAtLeast(0)
        in setOf("translator", "translator_five", "translator_ten", "translator_fifty", "translator_hundred") ->
            prefs.counter("translated_chapters").get().coerceAtLeast(0)
        "incognito_reader" -> prefs.counter("incognito_reads").get().coerceAtLeast(0)
        "night_owl" -> prefs.dailyCounter("after_midnight_reads").get().coerceAtLeast(0)
        in setOf("upscale_first", "upscale_ten", "upscale_hundred") ->
            prefs.upscalesServed().get().coerceAtLeast(0)
        "tracker_connected", "tracker_two", "tracker_three", "tracker_five", "tracker_all" -> 0
        "ultimate_perfection" -> prefs.getUnlockedIds().count { Achievements.forId(it)?.countsTowardsProgress == true }.toLong()
        "ultimate_secret_hunter_ultimate" -> prefs.getUnlockedIds().count { Achievements.forId(it)?.isSecret == true }.toLong()
        // KMK --> Rotating progress is neither a lifetime counter nor derived from one: it is
        // the per-period bar the pool maintains, so it is read from there. Gated on the id
        // actually being rotating, because the lookup parses the whole progress CSV and this
        // runs once per card.
        else -> if (Achievements.forId(id)?.isRotating == true) {
            prefs.rotatingProgressMap()[id]?.toLong()?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
    }

    fun progressFor(id: String, prefs: AchievementPreferences): Float {
        if (prefs.getUnlockedIds().contains(id)) return 1f
        val target = thresholdFor(id) ?: return 0f
        if (target <= 0) return 0f
        val cur = currentFor(id, prefs)
        return (cur.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    }

    fun labelFor(id: String, prefs: AchievementPreferences): String? {
        val target = thresholdFor(id) ?: return null
        if (prefs.getUnlockedIds().contains(id)) return "$target/$target"
        val cur = currentFor(id, prefs).coerceAtMost(target)
        return "$cur/$target"
    }

    fun hasProgress(id: String): Boolean =
        thresholds.containsKey(id) || Achievements.forId(id)?.isRotating == true
}
