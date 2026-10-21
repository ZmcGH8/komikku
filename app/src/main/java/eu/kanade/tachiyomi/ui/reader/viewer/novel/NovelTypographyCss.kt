package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Turns a [NovelTypography] into the stylesheet the reader injects into a document.
 *
 * ### Why a stylesheet and not a native renderer
 *
 * The text arrives as XHTML with the publisher's own CSS — drop caps, ruby annotations, tables,
 * vertical writing, columns. Re-implementing that in Compose means re-implementing a browser. So
 * the document keeps its own styling and the reader's preferences are layered on top.
 *
 * ### Why almost everything is `!important`
 *
 * An EPUB stylesheet sets `font-size` on `body`, on `p`, often on a class. Without `!important`
 * those rules win the cascade on specificity grounds and the user's font size does nothing — the
 * single most common complaint about EPUB readers. Injecting the overrides last with `!important`
 * is the only reliable way to make a preference actually apply.
 *
 * ### The selector ladder
 *
 * Publishers scope text in wildly different ways, so several selectors are targeted at once rather
 * than betting on one. `!important` plus a group selector covers `body`, unclassed elements and the
 * common container classes without needing to know the book's class names.
 *
 * ### A background implies a text colour
 *
 * The colour written into the rules is [NovelTypography.effectiveFontColor], not its `fontColor`
 * field. Emitting each independently is what made every background except white unreadable: the
 * sheet honoured a reader-chosen background and left the text in the publisher's own colour, so a
 * dark plate came out as black on black. Deriving the foreground from the background is the fix, and
 * it is deliberately the only colour logic here — the alternative, guessing a "best" text colour for
 * every book, is what turned a missing rule into a wrong one.
 */
object NovelTypographyCss {

    /**
     * The reader's stylesheet, to be appended after the document's own `<style>` blocks.
     *
     * Always non-empty. Earlier this returned "" for default typography, which broke the one case
     * that matters most: a book whose own stylesheet justifies text, read by someone who turned
     * justification off. Emitting an explicit `text-align: left !important` is what makes "off"
     * mean off. The cost is about a kilobyte of CSS per document.
     *
     * Properties that default to "follow the publisher" ([NovelTypography.justified],
     * [hyphenation], [verticalWriting]) are omitted entirely when null, so the book keeps its own
     * choice unless the reader made one.
     */
    fun stylesheet(type: NovelTypography): String {
        val t = type.sanitized()
        // Not `t.fontColor`: a background the reader chose forces a text colour even when they did
        // not choose one, or the page is the publisher's black text on the reader's black plate.
        val textColor = t.effectiveFontColor

        return buildString {
            appendLine("/* komikku reader typography */")
            appendLine(":root {")
            appendLine("  --kmk-font-size: ${fmt(t.effectiveFontSizePt)}pt;")
            textColor?.let { appendLine("  --kmk-text: $it;") }
            t.backgroundColor?.let { appendLine("  --kmk-bg: $it;") }
            appendLine("  --kmk-line: ${fmt(t.lineSpacing)};")
            appendLine("  --kmk-para: ${fmt(t.paragraphSpacing)}em;")
            appendLine("  --kmk-tracking: ${fmt(t.fontSpacing)}em;")
            appendLine("}")

            // Root sizing. `html` is set too because publisher CSS commonly overrides `body` and
            // inherits from `html`, so scaling only `body` leaves some books unchanged.
            appendLine("html, body {")
            appendLine("  font-size: var(--kmk-font-size) !important;")
            t.fontFamily.takeIf { it.isNotBlank() }?.let {
                appendLine("  font-family: ${quoteFamily(it)} !important;")
            }
            textColor?.let { appendLine("  color: $it !important;") }
            t.backgroundColor?.let { appendLine("  background-color: $it !important;") }
            appendLine("  line-height: var(--kmk-line) !important;")
            appendLine("  letter-spacing: var(--kmk-tracking) !important;")
            t.justified?.let { appendLine("  text-align: ${if (it) "justify" else "left"} !important;") }
            t.hyphenation?.let { appendLine("  ${hyphens(it)} !important;") }
            appendLine("  ${fontSmoothing(t)} !important;")
            t.verticalWriting?.let { appendLine("  ${writingMode(it)} !important;") }
            appendLine("  margin: ${margins(t)} !important;")
            appendLine("  padding: 0 !important;")
            appendLine("  -webkit-text-size-adjust: none !important;")
            appendLine("}")

            // Re-asserted on the text-bearing elements because a publisher rule on `p` or a class
            // outranks a rule on `body` regardless of where it appears in the document.
            appendLine(elementsRule(t, textColor))

            appendLine("p, div, blockquote, li, dd, section, article {")
            appendLine("  margin-top: 0 !important;")
            appendLine("  margin-bottom: var(--kmk-para) !important;")
            appendLine("}")
            // Paragraph indent is the publisher's; spacing is ours. Keeping both is what stops the
            // page looking like a wall of unindented lines - so `on` emits the indent and `off` has to
            // emit `0` rather than nothing, or a publisher that indents would keep indenting and the
            // setting would read as having done nothing. `null` omits the rule entirely, which is the
            // only way to say "follow the publisher" once the reader has seen both answers.
            t.paragraphIndent?.let {
                appendLine("""p + p { text-indent: ${if (it) "1em" else "0"} !important; }""")
            }

            // Headings keep the publisher's scale but inherit the colour choice.
            textColor?.let { appendLine("h1, h2, h3, h4, h5, h6 { color: $it !important; }") }

            // An image must never bleed into the margin box once the user has set one.
            if (t.marginLeft > 0f || t.marginRight > 0f) {
                appendLine("img, svg, video { max-width: 100% !important; height: auto !important; }")
            }
            // A figure the reader has tapped open. Lifted out of the text so it reads as magnified
            // rather than as a stretched picture, and `!important` because the publisher's own
            // `position` on an image would otherwise win on specificity and leave it in the line.
            appendLine(
                "${NovelScrollBridge.ZOOMED_CLASS.let { "img.$it" }} " +
                    "{ position: relative !important; z-index: 2 !important; cursor: zoom-out !important; }",
            )
            appendLine("/* end komikku typography */")
        }
    }

    /**
     * The same stylesheet wrapped for delivery to the renderer, which is [NovelDocument]'s job to place.
     *
     * The rules are escaped for a `<style>` element on the way in. A closing tag inside a stylesheet ends
     * it in the HTML parser, before any CSS is read, so a font family containing `</style>` would silently
     * truncate every rule after it — including the base font size, which is the one thing the reader is
     * certain to emit. `<\/style>` is not a closing tag to the parser and is the same string to a CSS
     * parser, where `\/` is an escaped solidus.
     */
    fun styleTag(type: NovelTypography): String =
        "<style id=\"kmk-typography\">${stylesheet(type).replace("</", "<\\/")}</style>"

    private fun elementsRule(t: NovelTypography, textColor: String?): String = buildString {
        append("body, p, div, span, li, td, th, blockquote, dd, dt, section, article, h1, h2, h3, h4, h5, h6 {")
        append(" font-size: var(--kmk-font-size) !important;")
        append(" line-height: var(--kmk-line) !important;")
        if (t.fontSpacing != 0f) append(" letter-spacing: var(--kmk-tracking) !important;")
        textColor?.let { append(" color: $it !important;") }
        append(" }")
    }

    /**
     * Hyphenation needs both the standard property and WebKit's, and `word-break` only as a
     * fallback — setting `word-break: break-word` unconditionally breaks CJK and ruby layout.
     */
    private fun hyphens(on: Boolean) = if (on) {
        "hyphens: auto; -webkit-hyphens: auto"
    } else {
        "hyphens: manual; -webkit-hyphens: manual"
    }

    private fun fontSmoothing(t: NovelTypography) = if (t.smoothText) {
        "-webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale"
    } else {
        "-webkit-font-smoothing: subpixel-antialiased"
    }

    private fun writingMode(vertical: Boolean) = if (vertical) {
        "writing-mode: vertical-rl; text-orientation: mixed"
    } else {
        "writing-mode: horizontal-tb"
    }

    /** CSS lengths for the four margins. Horizontal ones are % so they track the page width. */
    private fun margins(t: NovelTypography): String =
        "${percent(t.marginTop)} ${percent(t.marginRight)} ${percent(t.marginBottom)} ${percent(t.marginLeft)}"

    private fun percent(fraction: Float): String = "${fmt(fraction * 100f)}%"

    /** Locale-independent number formatting — a comma decimal separator breaks CSS parsing. */
    private fun fmt(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')

    /**
     * Quotes a family name so `Georgia, serif` does not parse as one broken family — and so a
     * name that is not a CSS identifier at all still arrives intact.
     *
     * The non-identifier case is not hypothetical: a reader-imported font's family value is
     * `user/<file name>`, which is an arbitrary reader-chosen string with a slash in it. Left
     * unquoted that is a syntax error in the `font-family` declaration, the whole rule is dropped,
     * and the book silently keeps its publisher's font — the exact "the setting does nothing"
     * failure the quoting here exists to prevent. A name is an identifier exactly when it is
     * one: letters, digits, underscore and hyphen, not starting with a digit. Everything else is
     * quoted, and a quote inside the name cannot survive being one, so it is stripped.
     */
    private fun quoteFamily(name: String): String {
        val generic = GENERIC_FAMILIES
        if (name in generic) return name
        val safe = name.replace("\"", "")
        return if (CSS_IDENTIFIER.matches(safe)) safe else "\"$safe\""
    }

    /** A name that needs no quotes: a single CSS identifier. */
    private val CSS_IDENTIFIER = Regex("""-?[A-Za-z_][-A-Za-z0-9_]*""")

    private val GENERIC_FAMILIES = setOf(
        "serif",
        "sans-serif",
        "monospace",
        "cursive",
        "fantasy",
        "system-ui",
    )
}
