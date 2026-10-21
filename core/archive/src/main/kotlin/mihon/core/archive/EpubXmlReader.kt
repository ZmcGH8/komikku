package mihon.core.archive

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Namespace-aware pull parser over the EPUB structural documents.
 *
 * Uses [XmlPullParser] with namespace processing enabled rather than Jsoup's XML mode. Jsoup keeps
 * the literal prefix in the tag name (`dc:title`), so every lookup would depend on whichever prefix
 * the publisher happened to choose — a book that binds Dublin Core to `dcterms:` instead of `dc:`
 * would parse into an empty title. Matching on the namespace URI is the only correct way, and it is
 * what the spec defines.
 *
 * Deliberately a thin wrapper, not a DOM: the structural documents are small and streaming them
 * keeps a 500-chapter book's `nav.xhtml` from being materialised as a tree the parser never needs
 * twice.
 *
 * Callers drive it with the usual pull-parser shape:
 * ```
 * val xml = EpubXmlReader(stream)
 * while (xml.next() == XmlPullParser.START_TAG) { ... }
 * ```
 */
internal class EpubXmlReader private constructor(private val parser: XmlPullParser) {

    /**
     * Builds the parser for an EPUB structural document.
     *
     * A companion function rather than inline constructor code because it has to run *before* `this`
     * exists: the secondary constructor delegates to the primary one, so anything reaching an instance
     * member from in here is "access before initialisation". See [checkedParser].
     */
    constructor(input: InputStream) : this(checkedParser(input))

    /** Advances to the next event and returns it. */
    fun next(): Int = parser.next()

    /** Local name of the current element, with any prefix already removed by the parser. */
    val name: String get() = parser.name

    /** Namespace URI of the current element, or "" when it declares none. */
    val namespace: String get() = parser.namespace.orEmpty()

    /** True when the current element is [localName] in [namespaceUri]. */
    fun matches(namespaceUri: String, localName: String): Boolean =
        parser.name == localName && parser.namespace.orEmpty() == namespaceUri

    /**
     * Attribute value by local name, ignoring its namespace.
     *
     * `href`, `media-type` and `full-path` are unprefixed in every producer, and matching the bare
     * name tolerates the stray prefix some writers put on OPF attributes.
     */
    fun attr(name: String): String? = parser.getAttributeValue(null, name)

    /**
     * Attribute value looked up by namespace **and** local name.
     *
     * Required for prefixed attributes once [FEATURE_PROCESS_NAMESPACES] is on: `epub:type` is not
     * in any namespace under the bare name `type`, so [attr] cannot see it at all. Getting this
     * wrong means the EPUB 3 navigation document is read as a landmarks list and the user is shown
     * "Start of content" instead of the chapter list.
     */
    fun attr(namespaceUri: String, localName: String): String? =
        parser.getAttributeValue(namespaceUri, localName)

    fun attrOrEmpty(name: String): String = attr(name).orEmpty().trim()

    fun hasAttr(name: String): Boolean = attr(name) != null

    /**
     * Reads the full text content of the element the parser is currently positioned on, including
     * child elements, re-serialised as escaped markup.
     *
     * `dc:description` routinely carries HTML paragraphs, and dropping the tags would silently
     * discard what the publisher wrote. Re-serialising keeps the round-trip lossless: the caller
     * decides later whether to parse it as HTML.
     *
     * The parser must be positioned on a START_TAG. On return it is positioned on the matching
     * END_TAG, so a caller that walks with `next()` continues from the right place.
     */
    fun textContent(): String {
        val out = StringBuilder()
        appendTextContentTo(out, plainText = false)
        return out.toString()
    }

    /**
     * Reads the text content of the current element as a single line of plain text.
     *
     * The Dublin Core fields a book *displays as text* — its title, author, publisher — are simple
     * literals by spec, but real OPFs carry markup inside them two ways: child elements
     * (`<dc:creator>` wrapping a bare `<creator>`), and tags hand-escaped into text
     * (`&lt;creator&gt;`). [textContent] preserved both, and the escapes came back out through
     * every name and author the app renders — literal `<creator>J.K. Rowling</…>` in a subtitle
     * field, visibly broken. Child markup is dropped to a separating space so an inline element
     * still reads as a word boundary, and tag-shaped sequences that arrived as text are stripped
     * (bounded to a plausible tag; a stray, unclosed `<` in a title survives). Whitespace runs
     * collapse: these are single-line display fields, not prose.
     *
     * Selection belongs to the caller: `dc:description` is authored HTML on purpose and keeps
     * [textContent].
     *
     * Same walk contract as [textContent] — entered on a START_TAG, returned on the element's own
     * END_TAG — and the same [MAX_TEXT_CHARS] ceiling.
     */
    fun textContentAsPlainText(): String {
        val out = StringBuilder()
        appendTextContentTo(out, plainText = true)
        return out.toString()
            .replace(TAG_SHAPED_TEXT, " ")
            .trim()
            .replace(WHITESPACE_RUN, " ")
    }

    /**
     * Appends the current element's content, abandoning the element once [MAX_TEXT_CHARS] is
     * reached.
     *
     * Bounded because both callers are exposed to hostile input: a book's TOC can nest a label
     * containing anything, and a `dc:description` is unbounded markup re-serialised into a
     * `StringBuilder` before anybody decides whether it is worth showing.
     *
     * Returns at the element's **own** closing tag, leaving the parser there, which is the contract
     * [textContent] documents and the only one a caller walking with `next()` can build on.
     *
     * The stopping condition used to be `depth < startDepth`, which is one tag *too far*: the
     * element's own end tag sits at `startDepth`, so that test sailed past it and kept consuming —
     * a `dc:description` holding `<p>` elements ran on through its siblings to the end of
     * `<metadata>`, swallowing every field declared after it. Worse, it left the parser past the end
     * tag the caller then advanced from, so a label followed by a nested `<ol>` lost its children
     * and every Dublin Core field after the first came back empty for any real book.
     *
     * When the ceiling is hit the parser is left mid-subtree rather than on the end tag. That is safe
     * for every caller — each one's loop terminates on `END_DOCUMENT` — and the alternative,
     * rescanning to find the end tag, is exactly the unbounded walk this method exists to prevent.
     */
    private fun appendTextContentTo(out: StringBuilder, plainText: Boolean) {
        val startDepth = parser.depth
        while (out.length < MAX_TEXT_CHARS) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    out.append(parser.text.orEmpty())
                    // Truncate rather than abandon: a single text node can itself be enormous, and a
                    // 40 MB `dc:description` should cost a truncated description, not the whole parse.
                    if (out.length > MAX_TEXT_CHARS) out.setLength(MAX_TEXT_CHARS)
                }
                XmlPullParser.START_TAG -> {
                    if (plainText) {
                        // An inline element marks a word boundary, not content: separating the
                        // text around it keeps "Harry <i>Potter</i>" reading as one phrase. The
                        // leading space is suppressed so a nested wrapper does not space out a
                        // lone value (`<dc:creator><creator>…`).
                        if (out.isNotEmpty() && !out[out.length - 1].isWhitespace()) out.append(' ')
                    } else {
                        out.append('<').append(parser.name)
                        for (i in 0 until parser.attributeCount) {
                            out.append(' ')
                                .append(parser.getAttributeName(i))
                                .append("=\"")
                                .append(
                                    parser.getAttributeValue(i).orEmpty()
                                        .replace("&", "&amp;")
                                        .replace("\"", "&quot;")
                                        .replace("<", "&lt;"),
                                )
                                .append('"')
                        }
                        out.append('>')
                    }
                }
                // The element's own closing tag, or one of its ancestors' — either way, this text
                // content is over.
                XmlPullParser.END_TAG -> if (parser.depth <= startDepth) return
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    companion object {
        /**
         * The platform parser, over an input with any doctype removed by [EpubDoctype.strip].
         *
         * `FEATURE_PROCESS_DOCDECL = false` is the parser-level half of the same XXE defence; see
         * [EpubDoctype] for why the input-level half is the one that carries the guarantee, and for
         * why asserting things about the parser implementation was the wrong way round.
         */
        private fun checkedParser(input: InputStream): XmlPullParser =
            Xml.newPullParser().apply {
                setInput(EpubDoctype.strip(input), null)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }

        /**
         * Ceiling on one [textContent] result.
         *
         * A real TOC label is under a kilobyte and a real `dc:description` under a few. This is two
         * orders of magnitude above both, so nothing legitimate is affected, and it is low enough
         * that a book cannot make the parse allocate its way into a low-memory kill.
         */
        const val MAX_TEXT_CHARS = 256 * 1024

        const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
        const val NS_OPF = "http://www.idpf.org/2007/opf"
        const val NS_DC = "http://purl.org/dc/elements/1.1/"
        const val NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"
        const val NS_ENCRYPTION = "http://www.w3.org/2001/04/xmlenc#"
        const val NS_ENC_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"

        /** `epub:type` vocabulary, used to pick the TOC out of a nav document. */
        const val NS_EPUB = "http://www.idpf.org/2007/ops"

        /** XHTML vocabulary used by EPUB content and navigation documents. */
        const val NS_XHTML = "http://www.w3.org/1999/xhtml"

        /**
         * A complete tag-shaped sequence, as [textContentAsPlainText] strips it out of text that
         * arrived with the markup escaped into it. Bounded to a plausible tag (name + attributes);
         * a bare, unclosed `<` in a title does not match, so it survives.
         */
        private val TAG_SHAPED_TEXT = Regex("</?[A-Za-z][^>]{0,127}>")

        /** Runs of any whitespace, collapsed to the single space [textContentAsPlainText] asks for. */
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}

/** Splits a whitespace-separated attribute value such as `nav scripted`. */
internal fun String.propertyTokens(): List<String> =
    split(' ', '\t', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
