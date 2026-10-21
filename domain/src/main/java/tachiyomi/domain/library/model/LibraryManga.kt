package tachiyomi.domain.library.model

import tachiyomi.domain.manga.model.Manga

data class LibraryManga(
    val manga: Manga,
    val categories: List<Long>,
    val totalChapters: Long,
    val readCount: Long,
    val bookmarkCount: Long,
    // KMK -->
    val bookmarkReadCount: Long,
    val chapterFlags: Long,
    // KMK <--
    val latestUpload: Long,
    val chapterFetchedAt: Long,
    val lastRead: Long,
    // KMK -->
    /** Chapter count where each chapter number is counted once, across merged sources and scanlators. */
    val uniqueTotalChapters: Long = totalChapters,
    /** Read chapter count where each chapter number is counted once. */
    val uniqueReadCount: Long = readCount,
    // KMK <--
) {
    val id: Long = manga.id

    val unreadCount
        get() = when {
            // KMK -->
            chapterFlags and Manga.CHAPTER_SHOW_NOT_BOOKMARKED != 0L -> totalChapters - bookmarkCount - (readCount - bookmarkReadCount)
            chapterFlags and Manga.CHAPTER_SHOW_BOOKMARKED != 0L -> bookmarkCount - bookmarkReadCount
            // KMK <--
            // KMK -->
            dedupeChapterCounts -> (uniqueTotalChapters - uniqueReadCount).coerceAtLeast(0)
            // KMK <--
            else -> totalChapters - readCount
        }

    // KMK -->
    /** Total chapters, counting each chapter number once when [dedupeChapterCounts] is on. */
    val displayTotalChapters
        get() = if (dedupeChapterCounts) uniqueTotalChapters else totalChapters
    // KMK <--

    val hasBookmarks
        get() = bookmarkCount > 0

    val hasStarted = readCount > 0

    // KMK -->
    companion object {
        /**
         * When true, remaining-chapter counts count each chapter number once, so the same chapter from
         * two merged sources or two scanlators isn't counted twice. Set from LibraryPreferences at startup.
         */
        @Volatile
        var dedupeChapterCounts: Boolean = true
    }
    // KMK <--
}
