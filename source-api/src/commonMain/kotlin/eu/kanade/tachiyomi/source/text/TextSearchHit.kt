package eu.kanade.tachiyomi.source.text

/**
 * A hit produced by [TextSearchSource].
 *
 * @property pageIndex page within the chapter, matching a [TextPage.index].
 * @property snippet text surrounding the match, already cut to something displayable. Sources
 *   should elide the middle of long matches rather than return the whole paragraph.
 * @property matchOffset character offset of the first match character within the snippet, for
 *   highlighting. Must be within `0..snippet.length`.
 * @property documentOffset character offset of the match within the **whole page**, in the same
 *   plain-text form the source searched. This is what lets a host scroll to the match: the snippet
 *   is a fragment, and an offset into a fragment says nothing about where the fragment is.
 * @property documentLength length of that whole page in the same units, so a host can turn
 *   [documentOffset] into a position without re-deriving the page's text. Zero means "not reported",
 *   and a host must then fall back to the top of the page rather than divide by it.
 *
 * ### Why [documentOffset] *and* [documentLength], and not just the offset
 *
 * The units are not the host's. A host holds [TextPage.text] as **markup**; the source searched it as
 * **plain text**, with tags removed and whitespace collapsed. Dividing an offset in plain text by a
 * markup length lands somewhere else entirely — routinely tens of percent of a page out, because the
 * markup is longer by exactly the tags and indentation the strip discarded. So the source reports the
 * denominator too, and the host never has to convert.
 *
 * Both default to 0, so an extension built before this pair existed still loads and still reports
 * hits — the host simply cannot scroll to one, and says so by landing at the top of the page rather
 * than guessing.
 *
 * @since komikku/extensions-lib 1.6
 */
data class TextSearchHit(
    val pageIndex: Int,
    val snippet: String,
    val matchOffset: Int = 0,
    val documentOffset: Int = 0,
    val documentLength: Int = 0,
)
