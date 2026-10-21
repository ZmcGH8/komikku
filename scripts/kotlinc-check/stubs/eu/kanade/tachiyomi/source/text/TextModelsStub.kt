// Stub for the extension API's text-source models, so `EpubTextSource` can be type-checked offline.
//
// These mirror the *signatures* of the real classes and nothing else. They exist because
// `source-api` is a project module the offline compiler cannot see, and every reference to it
// cascades into "no parameter with name X found" errors that bury the real diagnostic.
//
// If a parameter is added to the real class, add it here or this stub quietly stops proving that the
// call sites are correct.
@file:Suppress("unused")

package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.model.SChapter
import java.io.InputStream

interface TextSource {
    suspend fun getTextPageList(chapter: SChapter): List<TextPage>
}

interface TextTocSource {
    suspend fun getTableOfContents(chapter: SChapter): List<TocEntry>
}

interface TextSearchSource {
    suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit>
}

interface TextResourceSource {
    fun openResource(absoluteUrl: String): InputStream?
}

interface TextBaseUrlSource {
    fun baseUrlFor(page: TextPage): String
}

interface EpubArchiveSource {
    fun releaseResourceReader(chapterUrl: String)
    fun releaseAllResourceReaders()
}

data class TextPage(
    val index: Int,
    val url: String = "",
    val text: String = "",
    val kind: Kind = Kind.HTML,
    val baseHref: String? = null,
    val name: String? = null,
) {
    enum class Kind { HTML, PLAIN }
}

data class TextSearchHit(
    val pageIndex: Int,
    val snippet: String,
    val matchOffset: Int = 0,
    val documentOffset: Int = 0,
    val documentLength: Int = 0,
)

data class TocEntry(
    val label: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)