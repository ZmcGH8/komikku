package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.Serializable

/**
 * A reader-owned annotation: highlight, underline, bookmark or free note.
 *
 * The host owns these; the source only stores and returns them. That split matters because the
 * host is what knows a page was reflowed and the offsets below refer to, and the source is what
 * knows where the file lives after a library refresh.
 *
 * @property id stable identifier assigned by the host. Reused across reloads so an annotation can
 *   be updated rather than duplicated.
 * @property kind what the annotation does.
 * @property start first character offset of the annotated range within its [TextPage.text].
 * @property end exclusive end offset. Equal to [start] for a bookmark or note, which mark a
 *   position rather than a range.
 * @property pageIndex page the annotation lives in. Combined with [chapterUrl] this is what
 *   survives a library refresh: the text may be re-fetched at a new offset, but the page it lives
 *   on does not move.
 * @property chapterUrl [SChapter.url] of the chapter the annotation belongs to.
 * @property note free text the user attached. Null for a plain highlight.
 * @property colorIndex index into the host's highlight palette. Null for the default.
 *
 * @since komikku/extensions-lib 1.6
 */
@Serializable
data class TextAnnotation(
    val id: String,
    val kind: Kind,
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val chapterUrl: String,
    val note: String? = null,
    val colorIndex: Int? = null,
) {
    /** What an annotation does to the text it covers. */
    enum class Kind {
        HIGHLIGHT,
        UNDERLINE,
        STRIKETHROUGH,
        BOOKMARK,
        NOTE,
    }

    /** Whether this annotation covers a range of text rather than a single position. */
    val isRange: Boolean get() = kind != Kind.BOOKMARK && kind != Kind.NOTE && end > start
}

/**
 * A [TextSource] that persists reader annotations.
 *
 * Implementations must key storage on [TextAnnotation.chapterUrl] plus [TextAnnotation.pageIndex]
 * rather than on text offsets alone, and must return annotations sorted by
 * `(chapterUrl, pageIndex, start)`.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextAnnotationSource {

    /** Every annotation for [chapter], ordered for display. */
    suspend fun getAnnotations(chapter: SChapter): List<TextAnnotation>

    /**
     * Inserts [annotation], replacing any existing one with the same [TextAnnotation.id].
     *
     * Must be idempotent: hosts call this on every edit, including re-saving an unchanged
     * annotation during a sync.
     */
    suspend fun upsertAnnotation(annotation: TextAnnotation)

    /** Deletes the annotation with [id]. Deleting an unknown id is not an error. */
    suspend fun deleteAnnotation(id: String)
}
