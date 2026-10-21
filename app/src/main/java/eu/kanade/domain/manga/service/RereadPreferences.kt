package eu.kanade.domain.manga.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The read state a manga had when its reread started.
 *
 * ### Why this exists
 *
 * A reread works by marking every chapter unread ([StartRereading]) and restoring that state when
 * it stops ([StopRereading]). The restore had no record of what the state *was*, so it guessed
 * from `dateFetch` - the last-fetch timestamp, which marking read or unread does not touch. Every
 * chapter therefore looked older than the reread start and the whole series came back read: a
 * series with 10 of 50 chapters read went to 50/50 the moment the user hit Stop.
 *
 * Recording the baseline at the start is the only way to restore what was actually there. The ids
 * live in an app-state pref, not in the manga row, because they are a property of one reread and
 * are meaningless once it ends.
 */
@SingleIn(AppScope::class)
@Inject
class RereadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Records [chapterIds] as the read state [mangaId] had at the start of its reread.
     *
     * Stored before the chapters are marked unread, so the baseline is the pre-reread state by
     * construction rather than by ordering that a future edit could reorder.
     */
    @Synchronized
    fun recordBaseline(mangaId: Long, chapterIds: List<Long>) {
        if (mangaId <= 0L) return
        // A series can run to a few thousand chapters; the pref is a CSV, so bound it. Only the
        // ids that can still be restored matter, and anything past the cap is unrecoverable
        // either way - which the caller's fallback path also handles.
        baselineFor(mangaId).set(chapterIds.takeLast(MAX_BASELINE).joinToString(","))
    }

    /** The recorded baseline, or null when there is none (never started, or already cleared). */
    @Synchronized
    fun baseline(mangaId: Long): Set<Long>? {
        if (mangaId <= 0L) return null
        val raw = baselineFor(mangaId).get()
        if (raw.isBlank()) return null
        return raw.splitToSequence(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    /** Forgets the baseline. Called when the reread ends, either way. */
    @Synchronized
    fun clearBaseline(mangaId: Long) {
        if (mangaId <= 0L) return
        baselineFor(mangaId).delete()
    }

    /**
     * App-state prefixed, so a baseline never lands in a backup: it describes one reread on one
     * device and is meaningless anywhere else. It is also not a user preference, which is what
     * the prefix means.
     */
    private fun baselineFor(mangaId: Long) =
        preferenceStore.getString(Preference.appStateKey("pref_reread_baseline_$mangaId"), "")

    private companion object {
        const val MAX_BASELINE = 5_000
    }
}
