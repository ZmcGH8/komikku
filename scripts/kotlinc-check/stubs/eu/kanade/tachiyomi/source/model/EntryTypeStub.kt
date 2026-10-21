// Stub for the extension API's entry-type model, so `EpubTextSource` and the loader chain can be
// type-checked offline. Signatures mirror the real file; see `text/TextModelsStub.kt` for why.
@file:Suppress("unused")

package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.source.text.TextSource

enum class EntryType { COMIC, BOOK }

interface EntryTypeSource {
    fun entryTypeOf(manga: SManga): EntryType? = null
}

interface MixedContentSource {
    val bookSource: TextSource?
    fun detectBookEntry(manga: SManga, chapter: SChapter): Boolean
}

interface SManga {
    var url: String
}