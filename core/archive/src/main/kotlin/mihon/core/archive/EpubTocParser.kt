package mihon.core.archive

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Parsers for the two table-of-contents formats an EPUB can use.
 *
 * EPUB 3 puts the TOC in a navigation document (`nav.xhtml`) selected by the `nav` manifest
 * property; EPUB 2 puts it in `toc.ncx`, referenced by `<spine toc="…">`. Plenty of real books carry
 * both, and plenty carry only the old one, so a reader that handles just EPUB 3 finds no contents in
 * a large share of the library. [EpubTocParser.parse] tries EPUB 3 first and falls back.
 */
internal object EpubTocParser {

    /**
     * Reads whichever TOC format the book provides.
     *
     * @param navHref path of the EPUB 3 navigation document, when the manifest declares one.
     * @param ncxHref path of the EPUB 2 NCX, when `<spine toc>` points at one.
     * @param open stream opener, so a missing document costs nothing.
     */
    fun parse(
        navHref: String?,
        ncxHref: String?,
        open: (String) -> InputStream?,
    ): List<EpubTocNode> {
        // EPUB 3 first: when both exist, nav wins, because it is the one the producer kept current.
        navHref?.let { href ->
            open(href)?.use { parseNavDocument(it, href) }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        ncxHref?.let { href ->
            open(href)?.use { parseNcx(it, href) }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    /**
     * EPUB 3 navigation document.
     *
     * Looks for `<nav epub:type="toc">` and reads the nested `<ol>` beneath it. Two things this has
     * to survive, both of which occur in real files:
     *
     * - The `epub:type` attribute is in the EPUB vocabulary, not XHTML, so it must be matched by
     *   attribute *value* rather than by namespace on the element.
     * - `<nav>` without `epub:type` is a landmarks list, not a TOC. Reading it would show the user
     *   "Table of contents: Start of content / Table of contents / Bibliography" instead of the
     *   book's chapters.
     */
    fun parseNavDocument(input: InputStream, navHref: String): List<EpubTocNode> {
        val xml = EpubXmlReader(input)
        val baseDir = EpubPaths.parentOf(navHref)

        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.matches(EpubXmlReader.NS_XHTML, "nav") && xml.isNavToc()) {
                // The TOC list is the first <ol> directly inside this nav.
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.matches(EpubXmlReader.NS_XHTML, "ol")) return parseOrderedList(xml, baseDir)
                    if (xml.matches(EpubXmlReader.NS_XHTML, "nav")) return emptyList()
                }
                return emptyList()
            }
        }
        return emptyList()
    }

    /**
     * True when the current `<nav>` carries `epub:type` containing `toc`.
     *
     * `epub:type` is a space-separated list (`epub:type="toc"`), so it is tokenised rather than
     * compared whole. The value lives in the EPUB vocabulary namespace, not the XHTML one, so it
     * has to be looked up by namespace — see [EpubXmlReader.attr].
     */
    private fun EpubXmlReader.isNavToc(): Boolean {
        val type = attr(EpubXmlReader.NS_EPUB, "type") ?: return false
        return type.propertyTokens().any { it.equals("toc", ignoreCase = true) }
    }

    /**
     * Reads `<ol>` / `<li>` nesting, including the recursive `<ol>` that carries children.
     *
     * Depth is bounded at [MAX_TOC_DEPTH]: a malformed or generated file can nest without end, and
     * the reader thread is not the place to discover that with a `StackOverflowError`.
     *
     * Total node count is bounded too, by [budget], shared across the whole recursion rather than
     * reset per subtree. Depth alone does not bound the work: a flat contents with a million `<li>`s in
     * it nests exactly one level and still allocates a million nodes, and the reader turns every one of
     * them into a row. The cap is far above a real book — a 500-chapter novel with a detailed contents
     * is a couple of thousand — and hitting it truncates the list rather than failing the book,
     * because a long contents is still a usable one.
     */
    private fun parseOrderedList(
        xml: EpubXmlReader,
        baseDir: String,
        depth: Int = 0,
        budget: TocBudget = TocBudget(),
    ): List<EpubTocNode> {
        if (depth >= MAX_TOC_DEPTH) return emptyList()

        val nodes = mutableListOf<EpubTocNode>()
        var event = xml.next()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && xml.matches(EpubXmlReader.NS_XHTML, "ol")) break

            if (event == XmlPullParser.START_TAG && xml.matches(EpubXmlReader.NS_XHTML, "li")) {
                if (!budget.take()) return nodes
                val node = parseListItem(xml, baseDir, depth, budget) ?: continue
                nodes += node
                event = xml.next()
                continue
            }
            event = xml.next()
        }
        return nodes
    }

    private fun parseListItem(
        xml: EpubXmlReader,
        baseDir: String,
        depth: Int,
        budget: TocBudget,
    ): EpubTocNode? {
        var label: String? = null
        var href: String? = null
        var fragment: String? = null
        var children = mutableListOf<EpubTocNode>()
        var event = xml.next()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && xml.matches(EpubXmlReader.NS_XHTML, "li")) break

            if (event == XmlPullParser.START_TAG) {
                when {
                    xml.matches(EpubXmlReader.NS_XHTML, "a") || xml.matches(EpubXmlReader.NS_XHTML, "span") -> {
                        // EPUB 3.3 allows a <span> heading for a division that has no target.
                        val raw = xml.attr("href")
                        if (raw != null && href == null) {
                            val (path, frag) = EpubPaths.splitFragment(raw)
                            href = EpubPaths.resolve(baseDir, path)
                            fragment = frag
                        }
                        if (label == null) label = xml.textContentAsPlainText().ifBlank { null }
                        event = xml.next()
                        continue
                    }

                    xml.matches(EpubXmlReader.NS_XHTML, "ol") -> {
                        children = parseOrderedList(xml, baseDir, depth + 1, budget).toMutableList()
                        event = xml.next()
                        continue
                    }
                }
            }
            event = xml.next()
        }

        // An <li> with no anchor is a grouping heading; it carries its children and no target.
        val resolved = href ?: return if (children.isEmpty()) {
            null
        } else {
            EpubTocNode(label.orEmpty(), href = "", children = children)
        }
        return EpubTocNode(
            label = label ?: href.substringAfterLast('/'),
            href = resolved,
            fragment = fragment,
            children = children,
        )
    }

    /**
     * EPUB 2 NCX (`toc.ncx`).
     *
     * `playOrder` is ignored on purpose: producers emit it inconsistently and often wrong, and the
     * document order is what a reader actually means by "table of contents".
     */
    fun parseNcx(input: InputStream, ncxHref: String): List<EpubTocNode> {
        val xml = EpubXmlReader(input)
        val baseDir = EpubPaths.parentOf(ncxHref)

        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.matches(EpubXmlReader.NS_NCX, "navMap")) {
                return parseNavPoints(xml, baseDir, depth = 0, budget = TocBudget())
            }
        }
        return emptyList()
    }

    /**
     * Reads `<navPoint>` siblings.
     *
     * Bounded by depth and by a node budget for the same reasons as the EPUB 3 path above; see
     * [parseOrderedList].
     */
    private fun parseNavPoints(
        xml: EpubXmlReader,
        baseDir: String,
        depth: Int,
        budget: TocBudget,
    ): List<EpubTocNode> {
        if (depth >= MAX_TOC_DEPTH) return emptyList()

        val nodes = mutableListOf<EpubTocNode>()
        var event = xml.next()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && xml.matches(EpubXmlReader.NS_NCX, "navMap")) break

            if (event == XmlPullParser.START_TAG && xml.matches(EpubXmlReader.NS_NCX, "navPoint")) {
                if (!budget.take()) return nodes
                parseNavPoint(xml, baseDir, depth, budget)?.let(nodes::add)
                event = xml.next()
                continue
            }
            event = xml.next()
        }
        return nodes
    }

    private fun parseNavPoint(
        xml: EpubXmlReader,
        baseDir: String,
        depth: Int,
        budget: TocBudget,
    ): EpubTocNode? {
        var label: String? = null
        var href: String? = null
        var fragment: String? = null
        var children = mutableListOf<EpubTocNode>()
        var event = xml.next()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && xml.matches(EpubXmlReader.NS_NCX, "navPoint")) break

            if (event == XmlPullParser.START_TAG) {
                when {
                    xml.matches(EpubXmlReader.NS_NCX, "navLabel") -> {
                        label = readNcxText(xml).ifBlank { null }
                        event = xml.next()
                        continue
                    }

                    xml.matches(EpubXmlReader.NS_NCX, "content") -> {
                        xml.attr("src")?.let { raw ->
                            val (path, frag) = EpubPaths.splitFragment(raw)
                            href = EpubPaths.resolve(baseDir, path)
                            fragment = frag
                        }
                        event = xml.next()
                        continue
                    }

                    xml.matches(EpubXmlReader.NS_NCX, "navPoint") -> {
                        if (budget.take()) {
                            parseNavPoint(xml, baseDir, depth + 1, budget)?.let(children::add)
                        }
                        event = xml.next()
                        continue
                    }
                }
            }
            event = xml.next()
        }

        val target = href ?: return if (children.isEmpty()) {
            null
        } else {
            EpubTocNode(label.orEmpty(), href = "", children = children)
        }
        return EpubTocNode(
            label = label ?: target.substringAfterLast('/'),
            href = target,
            fragment = fragment,
            children = children,
        )
    }

    /** `<navLabel><text>…</text></navLabel>` — skips the wrapper and reads the inner text. */
    private fun readNcxText(xml: EpubXmlReader): String {
        var event = xml.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && xml.matches(EpubXmlReader.NS_NCX, "navLabel")) break
            if (event == XmlPullParser.START_TAG && xml.matches(EpubXmlReader.NS_NCX, "text")) {
                return xml.textContentAsPlainText()
            }
            event = xml.next()
        }
        return ""
    }

    /** Guards against a generated or corrupt TOC nesting forever. */
    private const val MAX_TOC_DEPTH = 32

    /**
     * Guards against a generated TOC declaring an unbounded number of entries.
     *
     * Depth alone does not bound this: a flat `<navMap>` with a million `<navPoint>`s nests one level
     * and still allocates a million nodes, each of which the reader turns into a row. The cap is orders
     * of magnitude above a real contents and truncates rather than fails — a very long contents is
     * still a usable one, and refusing to open the book over it would be worse.
     */
    private const val MAX_TOC_NODES = 50_000

    /**
     * A node allowance shared by one whole table of contents.
     *
     * Shared rather than per-call so that the limit is on the document, not on each subtree: a
     * thousand siblings each with a thousand children is a million nodes, and a per-subtree budget
     * would wave all of them through.
     */
    private class TocBudget(private val limit: Int = MAX_TOC_NODES) {
        private var spent = 0

        /** True if a node may still be built. False once the contents has spent its allowance. */
        fun take(): Boolean = spent++ < limit
    }
}
