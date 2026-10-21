@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.data.database.models

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.chapter.model.BookmarkColor
import java.io.Serializable
import tachiyomi.domain.chapter.model.Chapter as DomainChapter

interface Chapter : SChapter, Serializable {

    var id: Long?

    var manga_id: Long?

    var read: Boolean

    var bookmark: Boolean

    var last_page_read: Int

    var date_fetch: Long

    var source_order: Int

    var last_modified: Long

    var version: Long

    // KMK -->
    /**
     * Why the chapter was bookmarked, as [tachiyomi.domain.chapter.model.BookmarkColor.value].
     *
     * Part of this interface rather than only of the domain model because the reader converts its
     * chapter list through the legacy model on the way to the chapter-list sheet. A field that
     * only the domain [tachiyomi.domain.chapter.model.Chapter] carried was silently dropped there,
     * and every bookmark in the reader showed the default tint. Anything a legacy-model round trip
     * has to preserve belongs here, not only in the domain model.
     */
    var bookmark_color: Long
    // KMK <--
}

val Chapter.isRecognizedNumber: Boolean
    get() = chapter_number >= 0f

fun Chapter.toDomainChapter(): DomainChapter? {
    if (id == null || manga_id == null) return null
    return DomainChapter(
        id = id!!,
        mangaId = manga_id!!,
        read = read,
        bookmark = bookmark,
        lastPageRead = last_page_read.toLong(),
        dateFetch = date_fetch,
        sourceOrder = source_order.toLong(),
        url = url,
        name = name,
        dateUpload = date_upload,
        chapterNumber = chapter_number.toDouble(),
        scanlator = scanlator,
        lastModifiedAt = last_modified,
        version = version,
        memo = memo,
        // KMK -->
        bookmarkColor = BookmarkColor.fromValue(bookmark_color),
        // KMK <--
    )
}
