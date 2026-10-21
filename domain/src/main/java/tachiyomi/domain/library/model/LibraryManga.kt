package tachiyomi.domain.library.model

import androidx.compose.runtime.Immutable
import tachiyomi.domain.manga.model.Manga

@Immutable
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
) {
    val id: Long = manga.id

    /**
     * Chapters the reader has not read, on the same basis the details list lists them.
     *
     * The four counts behind it are chosen for us by the query: one per chapter *number* while the
     * smart scanlator merge is on, and one per *upload* with it off, because a merged list shows a
     * single upload per number while an unmerged one shows all of them. Counting numbers either way
     * is what made a series carried by six scanlator groups report 30 chapters on the badge while
     * the chapter list showed 180.
     *
     * The bookmark branches mirror `getChaptersByMangaId(applyFilter = true)`, which applies the
     * same two flags, so a series with a chapter filter on reports what its list shows rather than
     * everything it has.
     */
    val unreadCount
        get() = when {
            // KMK -->
            chapterFlags and Manga.CHAPTER_SHOW_NOT_BOOKMARKED != 0L -> totalChapters - bookmarkCount - (readCount - bookmarkReadCount)
            chapterFlags and Manga.CHAPTER_SHOW_BOOKMARKED != 0L -> bookmarkCount - bookmarkReadCount
            // KMK <--
            else -> totalChapters - readCount
        }

    val hasBookmarks
        get() = bookmarkCount > 0

    val hasStarted = readCount > 0
}
