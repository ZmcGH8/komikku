package tachiyomi.domain.achievement.service

/**
 * Fan-out for achievement unlocks.
 *
 * Two very different producers sit behind this interface:
 *
 * - a single unlock earned by what the user just did, which deserves a toast;
 * - a bulk import - restoring a backup, or a database restore - which can legitimately
 *   unlock fifty achievements in a single write. Notifying per achievement there is not
 *   a cosmetic problem: fifty staggered toasts bury the app for the better part of a
 *   minute, and fifty sounds play over whatever the user is trying to do next.
 *
 * [setBulkMode] is how a producer declares which of the two it is. Bulk mode still
 * records the unlock and still sends the webhook; only the user-facing toast and sound
 * are collapsed into a single summary.
 */
interface AchievementUnlockNotifier {
    fun onUnlocked(ids: List<String>)

    /**
     * Enters or leaves bulk mode. Nestable by depth: only the outermost transition
     * changes behaviour, so overlapping bulk sections cannot silently re-enable toasts
     * while an outer one is still running.
     */
    fun setBulkMode(enabled: Boolean)
}
