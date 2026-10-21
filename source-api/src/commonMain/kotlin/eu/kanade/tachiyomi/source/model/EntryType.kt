package eu.kanade.tachiyomi.source.model

/**
 * What a library entry **is**, independent of which source it came from.
 *
 * ### Why this exists
 *
 * The manga contract has one shape — a source produces `Page`s and the host draws them — and an
 * `.epub` in a manga library has always been drawn as a strip of images, because that is what
 * `Format.Epub` routes to. A book wants the opposite: one continuous text document per spine item,
 * with the publisher's own stylesheet and its illustrations inline.
 *
 * Declaring the type per **entry** rather than per source is the point. A source may serve both —
 * the manga local source holds comic folders and novel files side by side — so a source-level answer
 * cannot be right for every entry it owns, which is exactly why one source cannot simply implement
 * [eu.kanade.tachiyomi.source.text.TextSource] wholesale.
 *
 * ### Detection is not authority
 *
 * A host infers this from the content and lets the user override it. Any heuristic will misfire on
 * some book, and that is acceptable precisely because the entry is overridable. Never let a detected
 * value silently override a user's explicit choice.
 *
 * @since komikku/extensions-lib 1.7
 */
enum class EntryType {
    /** Drawn as images. A comic, an art book, a scanned page. The historical behaviour. */
    COMIC,

    /** Read as text: one spine item per document, publisher styling preserved. A novel. */
    BOOK,
}

/**
 * A source that knows what kind of content each of its entries holds.
 *
 * ### Optional on purpose
 *
 * This ships as its own interface rather than a member on [Source], [SManga] or any existing
 * interface. Extensions load through a classloader across a dex boundary: a new abstract member on a
 * shipped interface breaks every extension that predates it, and R8 cannot see the implementations to
 * warn you. A separate interface is type-checked for by the host and costs an old extension nothing.
 *
 * The same reasoning that produced `TextBaseUrlSource` and the rest of the `text` package.
 *
 * @since komikku/extensions-lib 1.7
 */
interface EntryTypeSource {

    /**
     * The content type of [manga], or null when this source has no opinion.
     *
     * Null is the default and the honest answer for most sources: it means "detect it", not "make it
     * a comic". A host only asks when nothing has been decided yet — never to overrule a user's
     * explicit choice on that entry.
     *
     * Declared per entry rather than per source so that a source serving both comics and books can
     * answer correctly for each.
     */
    fun entryTypeOf(manga: SManga): EntryType? = null
}

/**
 * A source that can serve books, but does **not** serve them for every entry it holds.
 *
 * ### Why this exists separately from [eu.kanade.tachiyomi.source.text.TextSource]
 *
 * A `TextSource` answers for the whole source: if it implements one, every chapter it returns is
 * text. That is right for a webnovel extension and **wrong** for the manga library, which holds comic
 * folders and novel files side by side. Making the manga source a `TextSource` and nothing else
 * would hand every comic to the book reader.
 *
 * So a mixed source implements both and says so here. A host then routes **per entry** instead of
 * per source: this interface is the signal that the source-level answer is not sufficient and the
 * entry's own [EntryType] has to be consulted first.
 *
 * A source that serves *only* books implements `TextSource` and needs nothing from this interface.
 *
 * @since komikku/extensions-lib 1.7
 */
interface MixedContentSource {

    /**
     * Whether [chapter] of [manga] should be read as a book, when neither a user nor
     * [EntryTypeSource] has already said.
     *
     * May open the file — the honest answer usually requires looking at it. A host calls this at most
     * once per chapter, and never when a user has expressed a preference, so it is allowed to be
     * expensive.
     */
    fun detectBookEntry(manga: SManga, chapter: SChapter): Boolean
}
