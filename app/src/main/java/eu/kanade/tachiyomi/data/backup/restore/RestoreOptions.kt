package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    // SY -->
    val savedSearchesFeeds: Boolean = true,
    // SY <--
    // KMK -->
    /**
     * Whether a restore that wrote library entries is followed by a full library update.
     * Default on: the follow-up refresh is usually wanted (new chapters, fetch intervals).
     * Restoring into a finished library can skip it here instead of stopping the job.
     */
    val updateLibrary: Boolean = true,
    /**
     * Houri+: make the library exactly what the backup holds. A restore otherwise only adds: entries that are
     * in the library but not in the backup stay, and merge entries keep the links they already had. With this
     * on, those entries are taken out of the library (nothing is deleted) and the backup's merge entries get
     * exactly the backup's links. Default off: it is for restoring a whole prepared library.
     */
    val replaceLibrary: Boolean = false,
    /**
     * Houri+: after restoring, send every library entry's tracking (status, chapters read, score, dates) to the
     * logged-in trackers, so the lists on MyAnimeList, AniList, Anime-Planet... match the backup. Default off.
     */
    val pushTracking: Boolean = false,
    // KMK <--
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        sourceSettings,
        // SY -->
        savedSearchesFeeds,
        // SY <--
        // KMK -->
        updateLibrary,
        replaceLibrary,
        pushTracking,
        // KMK <--
    )

    fun canRestore() =
        libraryEntries ||
            categories ||
            appSettings ||
            extensionStores ||
            sourceSettings /* SY --> */ ||
            savedSearchesFeeds /* SY <-- */

    companion object {
        val options = persistentListOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = RestoreOptions::extensionStores,
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            // SY -->
            Entry(
                // KMK-->
                label = KMR.strings.saved_searches_feeds,
                // KMK <--
                getter = RestoreOptions::savedSearchesFeeds,
                setter = { options, enabled -> options.copy(savedSearchesFeeds = enabled) },
            ),
            // SY <--
        )

        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            // getOrNull rather than indexing: the array travels as WorkManager input data, and a
            // restart of a queued restore from a build that wrote fewer fields must not crash -
            // defaulting to true resumes with the pre-toggle behavior.
            libraryEntries = array.getOrNull(0) ?: true,
            categories = array.getOrNull(1) ?: true,
            appSettings = array.getOrNull(2) ?: true,
            extensionStores = array.getOrNull(3) ?: true,
            sourceSettings = array.getOrNull(4) ?: true,
            // SY -->
            savedSearchesFeeds = array.getOrNull(5) ?: true,
            // SY <--
            // KMK -->
            updateLibrary = array.getOrNull(6) ?: true,
            replaceLibrary = array.getOrNull(7) ?: false,
            pushTracking = array.getOrNull(8) ?: false,
            // KMK <--
        )

        // KMK -->
        /**
         * The one entry that is a follow-up to the restore rather than content from it, so it does
         * not belong in [options]: checking nothing but this must still leave a backup with
         * [canRestore] false. Rendered by the restore screen in its own section.
         */
        val updateLibraryEntry = Entry(
            label = KMR.strings.restore_update_library,
            getter = RestoreOptions::updateLibrary,
            setter = { options, enabled -> options.copy(updateLibrary = enabled) },
        )

        /** Houri+: see [RestoreOptions.replaceLibrary]. Shown next to [updateLibraryEntry]. */
        val replaceLibraryEntry = Entry(
            label = KMR.strings.restore_replace_library,
            getter = RestoreOptions::replaceLibrary,
            setter = { options, enabled -> options.copy(replaceLibrary = enabled) },
        )

        /** Houri+: see [RestoreOptions.pushTracking]. */
        val pushTrackingEntry = Entry(
            label = KMR.strings.restore_push_tracking,
            getter = RestoreOptions::pushTracking,
            setter = { options, enabled -> options.copy(pushTracking = enabled) },
        )
        // KMK <--
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
