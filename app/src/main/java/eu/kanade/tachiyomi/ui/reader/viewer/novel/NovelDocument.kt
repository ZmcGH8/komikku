package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Turns a book's own XHTML into a document a layout engine can be handed directly.
 *
 * ### Why a builder at all
 *
 * The markup that comes out of an EPUB spine item is *XML*, not something the engine is contractually
 * obliged to accept as HTML, and in practice it is all four of the following at once:
 *
 *  - an `<?xml …?>` prolog, which is a bogus comment to an HTML parser and sits *before* the doctype,
 *    so it changes which doctype the engine sees;
 *  - an XHTML doctype naming a DTD over the network, which is pointless to resolve here, and for the
 *    `XHTML 1.1` flavour pulls the engine into a different layout mode than the one a book wants;
 *  - no `<meta charset>`, because XML takes the encoding from the prolog instead — and the prolog is
 *    exactly what was just discarded, so a Shift_JIS book renders as mojibake;
 *  - arbitrary well-formedness. A publisher who pasted HTML into an XHTML template leaves `&nbsp;`
 *    behind, which is a fatal error in XML and merely ordinary in HTML.
 *
 * Normalising to a plain `<!DOCTYPE html>` document with an explicit charset makes the reader behave
 * the same for every book instead of depending on which tool produced it.
 *
 * ### Why the `<base>` tag is emitted and not only passed to the engine
 *
 * The host also passes the base URL to `loadDataWithBaseURL`, which is what the engine uses. The tag
 * is belt and braces for one specific and very visible failure: if the two ever disagree, the
 * document says which base its own relative references resolve against, so a book renders the way the
 * source intended rather than the way the host guessed. A `<base>` already in the publisher's markup
 * is removed first, because the first one in the document is the one that counts and theirs would
 * silently win.
 *
 * No Android imports on purpose — this is the part of the reader that can be asserted off-device.
 */
object NovelDocument {

    /**
     * Builds the document to load for [source], resolved against [baseUrl].
     *
     * [baseUrl] must be absolute and end in `/`; see
     * [eu.kanade.tachiyomi.source.text.TextBaseUrlSource] for why.
     *
     * [segment], when set, marks the document as one section of the book's continuous scroll:
     * its body content is wrapped in the marker `NovelContinuous` probes. Null for callers that
     * are not the reader — the harness among them.
     *
     * [extraHead] is stylesheet source, emitted after the base and the charset — the reader font's
     * `@font-face`, which is CSS and so cares about the cascade but not about the base. It is
     * wrapped in a `<style>` here rather than by the caller, because CSS sitting loose in the
     * `<head>` is not CSS: the parser meets a character token in head insertion mode, has nothing
     * to do with it, closes the head and opens a body to hold it. The rule then renders as the
     * paragraph of literal text it is — which is what picking a font from the reader's own list
     * used to do, and it declared a font the document was still not using.
     *
     * The head content is injected at **two** points, and the split is not cosmetic:
     *
     *  - `<base>` and the charset go at the very top of the head. A `<base>` has to precede every
     *    element that uses a relative URL — including the publisher's own `<link>`, which the preload
     *    scanner may well have resolved before the end of the head. And a charset declaration is only
     *    honoured near the top of a document, which is the one thing an XML prolog was doing for us
     *    and is no longer doing anything at all.
     *  - the reader's stylesheet and the scroll bridge go at the very **end**. They are `!important`
     *    for a reason — a publisher rule on `body` outranks a host rule on specificity grounds, and
     *    that is the single most common complaint about EPUB readers — but `!important` against
     *    `!important` is decided by source order, so a publisher that also uses it has to come second.
     */
    fun build(
        source: String,
        typography: NovelTypography,
        baseUrl: String,
        // KMK -->
        segment: Pair<Int, String>? = null,
        extraHead: String = "",
        /**
         * The request stamp the continuous growth scripts verify against. Every document carries one,
         * and `NovelContinuous`' append/prepend/trim scripts are stamped with the request that built
         * the document whose window they grow or trim; a script handed to a renderer that has since
         * moved to another document refuses to run, instead of adding a section of somebody else's
         * chapter to a window that just ended. Default `0` keeps non-reader callers (the harness)
         * building documents whose scripts simply find agreement and run.
         */
        epoch: Int = 0,
        // KMK <--
    ): String {
        val document = wrapBody(normalize(source), segment)
        val prologue = prologue(baseUrl) + styleTag(extraHead)
        val epilogue = epilogue(typography, epoch)

        val headOpen = HEAD_OPEN.find(document)
        if (headOpen != null) {
            val headClose = HEAD_CLOSE.find(document, headOpen.range.last + 1)
            return if (headClose != null) {
                // Closed head: the later offset is patched first, so inserting there cannot shift the
                // earlier one out from under the second edit.
                document
                    .replaceRange(headClose.range, epilogue + headClose.value)
                    .replaceRange(headOpen.range, headOpen.value + prologue)
            } else {
                // A document that opens <head> and never closes it: common in generated EPUBs. Putting
                // everything after the opening tag is the best that can be done, and the rules still
                // precede the body, which is where the text is.
                document.replaceRange(headOpen.range, headOpen.value + prologue + epilogue)
            }
        }

        val htmlOpen = HTML_OPEN.find(document)
        return if (htmlOpen != null) {
            document.replaceRange(htmlOpen.range, "${htmlOpen.value}<head>$prologue$epilogue</head>")
        } else {
            // No <html> at all — a fragment, or a file whose root element is something else. Wrapping is
            // the only way to give the engine the head the base and the charset need.
            "$HTML_SHELL<head>$prologue$epilogue</head>\n$document\n</body>\n</html>"
        }
    }

    /**
     * Wraps [document]'s body content in the continuous-reading segment marker.
     *
     * Untouched when [segment] is null. A document with a `<body>` gets the marker inside it — one
     * per body, however the publisher nested their sections, because the marker names a *spine
     * section*, which is exactly what the body of a spine document holds. A fragment with no body
     * of its own is wrapped wholesale; the shell [build] puts around it then carries both.
     */
    private fun wrapBody(document: String, segment: Pair<Int, String>?): String {
        if (segment == null) return document
        val (position, url) = segment
        val open = NovelContinuous.openTag(position, url)
        val bodyOpen = BODY_OPEN.find(document)
        if (bodyOpen == null) return "$open$document${NovelContinuous.CLOSE}"
        val bodyClose = BODY_CLOSE.find(document, bodyOpen.range.last + 1)
        return if (bodyClose != null) {
            // Later offset first, for the same reason as the head surgery above.
            document
                .replaceRange(bodyClose.range, NovelContinuous.CLOSE + bodyClose.value)
                .replaceRange(bodyOpen.range, bodyOpen.value + open)
        } else {
            document.replaceRange(bodyOpen.range, bodyOpen.value + open + NovelContinuous.CLOSE)
        }
    }

    /**
     * Drops the XML prolog, reduces the doctype to `<!DOCTYPE html>`, and removes the `<base>` and
     * `<meta http-equiv>` the publisher supplied.
     *
     * The prolog is a bogus comment that hides the doctype from the HTML parser's doctype sniffing,
     * and an XHTML doctype's SYSTEM identifier is a network fetch that cannot succeed inside a
     * document loaded from a string.
     *
     * The two removals are for the same reason, which is that each one silently outranks something
     * the reader depends on. A `<base>` is first-match-wins, so theirs would beat the one we emit —
     * and it is exactly wrong, being written for whatever the producer rendered the book with. A
     * `<meta http-equiv="refresh">` is honoured by the engine with no involvement from us at all, so
     * a book carrying one navigates the reader's document to a URL of the book's choosing: the text
     * they were reading is replaced, without a tap, by something we then decline to serve.
     */
    private fun normalize(source: String): String {
        val withoutProlog = XML_PROLOG.replaceFirst(source, "")
        val withoutDoctype = DOCTYPE.replaceFirst(withoutProlog, "<!DOCTYPE html>")
        // A <base> already in the publisher's markup would silently win over ours, because the first one
        // in the document is the one that counts — and it is exactly the wrong one, since it was written
        // for whatever the producer rendered the book with.
        val withoutBase = BASE_TAG.replace(withoutDoctype, "")
        // Global, not first: a second one is inert while the first survives, but if the first is
        // malformed enough for the engine to skip it, the next in document order takes effect.
        return META_REFRESH.replace(withoutBase, "").trimStart()
    }

    /** Goes at the very top of the head: everything here has to be seen before the publisher's own URLs are. */
    private fun prologue(baseUrl: String): String = buildString {
        appendLine(HEAD_MARKER)
        appendLine("""<base href="${escapeAttribute(baseUrl)}">""")
        // Ours lands before any the publisher declared, and the first charset declaration is the one
        // that wins — so a book that got its encoding wrong is corrected rather than left alone.
        appendLine("""<meta http-equiv="Content-Type" content="text/html; charset=utf-8">""")
    }

    /**
     * Wraps [css] in a `<style>` for the head, or returns nothing for nothing.
     *
     * Bare text is not a place CSS can be, so this is not decoration. See [build].
     *
     * `</` is escaped the way [NovelTypographyCss.styleTag] escapes it, and for the same reason it
     * matters there: a closing tag inside a stylesheet ends it in the parser before any CSS is read.
     * A reader-imported font's family is an arbitrary reader-chosen string, so the one thing these
     * rules carry is exactly the kind of text that can contain it.
     */
    private fun styleTag(css: String): String =
        if (css.isBlank()) "" else """<style id="kmk-reader-font">${css.replace("</", "<\\/")}</style>"""

    /** Goes at the very end of the head, so it outranks the publisher's rules on source order. */
    private fun epilogue(typography: NovelTypography, epoch: Int): String = buildString {
        appendLine(NovelTypographyCss.styleTag(typography))
        // Wrapped, obviously. Script text emitted bare is not script: it is the first thing on the
        // page, printed in the reader's own font, and it is not the last thing anyone notices.
        append("<script>")
        append(NovelScrollBridge.BRIDGE_SCRIPT.replace("</script", """<\/script"""))
        // The stamp the growth scripts compare themselves against, in the same script element so it
        // is declared before any of them can run rather than beside it. `var` — this must land on
        // `window` itself, and the value is the reader's own request number: no book counts here.
        append(""";window.KmkEpoch = $epoch;""")
        appendLine("</script>")
    }

    /** Neutralises the markup-significant characters in an attribute value. */
    private fun escapeAttribute(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    /** An XML declaration, which is only ever the first thing in the file. */
    private val XML_PROLOG = Regex("""^\s*<\?xml[\s\S]*?\?>""", RegexOption.IGNORE_CASE)

    /**
     * A doctype, including the internal subset form.
     *
     * `[^>\[]*` rather than `.*?` so a `>` inside a quoted public identifier cannot end the match
     * early — XHTML doctypes quote one, and it routinely contains no `>` but nothing guarantees it.
     */
    private val DOCTYPE = Regex("""^\s*<!DOCTYPE[^>\[]*(?:\[[\s\S]*?\])?\s*>""", RegexOption.IGNORE_CASE)

    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val HEAD_CLOSE = Regex("""</head\s*>""", RegexOption.IGNORE_CASE)

    // KMK --> the continuous-reading marker wraps the body; see [wrapBody] and NovelContinuous.
    private val BODY_OPEN = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val BODY_CLOSE = Regex("""</body\s*>""", RegexOption.IGNORE_CASE)
    // KMK <--

    private val BASE_TAG = Regex("""<base\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * `<meta http-equiv="refresh" …>`.
     *
     * `http-equiv` matched separately from `content` so a malformed attribute order does not smuggle
     * a refresh past the pattern.
     */
    private val META_REFRESH = Regex(
        """<meta\b[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    private const val HEAD_MARKER = "<!--kmk:document-->"

    private const val HTML_SHELL = """<!DOCTYPE html><html><body>"""
}
