package eu.kanade.tachiyomi.source.text

/**
 * A text source that can tell the host **which URL each page has to be loaded at** for the
 * references inside it to resolve.
 *
 * ### Why this exists
 *
 * [TextResourceSource] answers "open this absolute URL". It does not answer "which absolute URL do I
 * hand the renderer so that `../images/plate.jpg` becomes the right absolute URL in the first
 * place" — and getting that wrong is invisible and total. A book whose spine item is
 * `OEBPS/Text/chapter1.xhtml` links to `../styles/main.css`. Load that document against the *archive
 * root* and the browser resolves the link to `styles/main.css` instead of `OEBPS/styles/main.css`,
 * the stylesheet is not found, and the book renders with the publisher's own typography, ruby
 * annotations, drop caps and illustrations all missing. Nothing errors; the page is simply not the
 * page it should be.
 *
 * [TextPage.baseHref] carries the information — for an EPUB it is the spine item's in-archive path —
 * but it is deliberately *relative*, because only the source knows how its own resources are
 * addressed. An extension serving chapters over HTTP needs no help: its `baseHref` is already
 * absolute. One serving archive-internal resources does, and this is how it says so.
 *
 * ### ABI
 *
 * A separate interface rather than a method on [TextResourceSource] or [TextSource]: both already
 * ship and are implemented by extensions loaded through a classloader, so adding a method to either
 * breaks every one of them that predates the change. A new interface has no implementors to break,
 * and a host that does not find it falls back to a base URL that suits absolute references.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextBaseUrlSource {

    /**
     * Absolute URL the host should load [page] with, so every relative reference in its markup
     * resolves the way the publisher intended.
     *
     * Must end in `/` when it names a directory. Returning the document's own URL rather than its
     * directory is the classic mistake here: `…/OEBPS/Text/chapter1.xhtml` resolves `../styles` to
     * `OEBPS/styles`, which is correct, while the directory form resolves it identically — but a
     * base *without* the trailing slash would drop the last segment and resolve to `OEBPS/styles`
     * from `…/Text/chapter1` as well, which is right by accident and breaks the moment the markup
     * uses a root-relative reference.
     */
    fun baseUrlFor(page: TextPage): String
}
