package tachiyomi.domain.updates.model

import tachiyomi.domain.chapter.model.BookmarkColor
import tachiyomi.domain.manga.model.CustomMangaInfoLookup
import tachiyomi.domain.manga.model.MangaCover

data class UpdatesWithRelations(
    val mangaId: Long,
    // SY -->
    val ogMangaTitle: String,
    // SY <--
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    // KMK --> Why the chapter was bookmarked, so the update list can tint its bookmark icon the
    // same way the details screen does. Defaulted rather than required because the column only
    // arrived in the view with migration 64, and every other consumer of this model would
    // otherwise have to name it.
    val bookmarkColor: BookmarkColor = BookmarkColor.NONE,
    val lastPageRead: Long,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val mangaTitle: String = CustomMangaInfoLookup.resolve?.invoke(mangaId)?.title ?: ogMangaTitle
    // SY <--
}
