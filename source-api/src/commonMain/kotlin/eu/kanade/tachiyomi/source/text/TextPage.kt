package eu.kanade.tachiyomi.source.text

import kotlinx.serialization.Serializable

/**
 * One unit of readable text in a chapter.
 *
 * Where [eu.kanade.tachiyomi.source.model.Page] models a single *image* that has to be fetched,
 * decoded and cached, a [TextPage] is markup that is already in hand when [TextSource.getTextPageList]
 * returns. That is the whole reason the two models are separate: text has no meaningful intermediate
 * state to report, so there is no [eu.kanade.tachiyomi.source.model.Page.State] machine, no progress
 * flow, and no retry. A page is either ready or the chapter failed to load.
 *
 * Splitting a chapter across several pages is a rendering concern, not a source concern: a source
 * returns the natural units it has (EPUB spine items, webnovel chapters, blog posts) and the reader
 * decides how to paginate them for the screen.
 */
@Serializable
data class TextPage(
    /**
     * Position of this page in the chapter. Ignored by hosts that re-order, but sources should keep
     * it ascending so it can be used as a stable tiebreaker.
     */
    val index: Int,

    /**
     * Identifier for this page within its chapter. Either the URL a page was fetched from, or the
     * in-archive path of the spine item. Used for bookmarks, for resolving relative resources, and
     * for restoring a reading position — so it must be **stable across library refreshes**. Never
     * put a timestamp, a random id, or an array index here.
     */
    val url: String = "",

    /**
     * The content itself, interpreted according to [kind].
     *
     * For [Kind.HTML] this is a complete, self-contained HTML document (not a fragment): hosts
     * render it in a WebView and rely on it carrying its own `<head>`, so a source must inline any
     * stylesheet it needs rather than assuming the host will inject one.
     */
    val text: String = "",

    /**
     * How [text] should be interpreted.
     */
    val kind: Kind = Kind.HTML,

    /**
     * Base URL used to resolve relative references inside [text] — the `src` of an image, the
     * `href` of a stylesheet or an internal link.
     *
     * For EPUB this is the spine item's path within the archive, so `../images/plate.jpg` resolves
     * against the archive root instead of against the host app's own scheme. For a webnovel it is
     * the chapter URL, and an absolute `https://` reference works unchanged.
     *
     * Null means "no base", and hosts must then treat relative references as unresolvable rather
     * than resolving them against their own base.
     */
    val baseHref: String? = null,

    /**
     * Display title for this page, when the source has one — a spine item's `<title>`, a webnovel
     * chapter heading. Hosts use it for page-level navigation and accessibility; it is never shown
     * in place of the text itself.
     */
    val name: String? = null,

    /**
     * Words in [text], used by hosts to size reading-time estimates and pre-computed text
     * statistics. Null means "not known", never "zero words".
     */
    val wordCount: Int? = null,
) {

    /** How the [text] of a [TextPage] should be interpreted by a host. */
    enum class Kind {
        /** A full HTML/XHTML document. Rendered with a real layout engine. */
        HTML,

        /** Plain text. Line breaks in [text] are paragraph breaks. */
        PLAIN,
        ;

        /**
         * Whether a host needs a WebView-class renderer for this kind.
         *
         * [PLAIN] renders acceptably in a plain text widget, which lets hosts pick a cheaper
         * renderer for webnovel sources that ship unformatted chapter text.
         */
        val needsRichRenderer: Boolean get() = this == HTML
    }
}
