package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Everything the reader can change about how text looks, independent of any Android or WebView
 * type.
 *
 * Kept free of platform imports on purpose: it is the value that gets persisted, passed to the
 * renderer, and asserted against in unit tests, and none of those need a device.
 *
 * See [NovelTypographyCss] for how this reaches the screen.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
data class NovelTypography(
    /**
     * Base text size in points, before [fontScale].
     *
     * Points rather than pixels so the reading size stays consistent across screen densities: a user
     * who picks 16pt means the same physical size on a 1x tablet and a 4x phone.
     */
    val fontSizePt: Int = DEFAULT_FONT_SIZE_PT,

    /**
     * Text colour as `#rrggbb`, or null to let the publisher's own colour stand.
     *
     * Null is not the same as "unpainted": a book that styles its own text dark now gets a colour
     * derived from [backgroundColor] whenever that is set. See [effectiveFontColor].
     */
    val fontColor: String? = null,

    /**
     * Page background as `#rrggbb`, or null to keep whatever the book sets.
     *
     * Null matters: a publisher that chose a dark plate for one illustration expects it to stay
     * dark, and overriding it globally is how a reader ends up with unreadable black-on-black.
     *
     * Setting it forces a text colour even when [fontColor] is null. See [effectiveFontColor] for
     * why the two cannot be chosen independently.
     */
    val backgroundColor: String? = null,

    /** Family name to request, e.g. `serif`, `sans-serif` or a bundled font file. */
    val fontFamily: String = DEFAULT_FONT_FAMILY,

    /**
     * Anti-aliasing quality.
     *
     * Subpixel uses the LCD subpixels and looks better for black-on-white but produces colour
     * fringing, which is wrong for coloured text on a dark background — so this is a real choice
     * rather than a quality slider.
     */
    val smoothText: Boolean = true,

    /**
     * Justify both edges. Null leaves the publisher's own alignment alone.
     *
     * Nullable rather than defaulting to false on purpose. A book whose stylesheet sets
     * `text-align: justify` looks wrong to a reader who wants a ragged right edge, and they cannot
     * express "off" if the default silently means "off" and emits no rule. Null = follow the book;
     * true and false both override it.
     */
    val justified: Boolean? = null,

    /** Break words across lines. Null follows the publisher; see [justified]. */
    val hyphenation: Boolean? = null,

    /** Space between paragraphs, in multiples of the text size. */
    val paragraphSpacing: Float = 0.0f,

    /** Line height as a multiple of the text size. 1.0 is single spacing. */
    val lineSpacing: Float = DEFAULT_LINE_SPACING,

    /** Extra tracking between characters, in em. 0 is the font's own default. */
    val fontSpacing: Float = 0.0f,

    /**
     * Global multiplier on top of [fontSizePt].
     *
     * Separate from the size so that a reader who wants "this book at my normal size" can store one
     * scale and reuse it after changing the base size, instead of the two fighting each other.
     */
    val fontScale: Float = 1.0f,

    /** Left page margin as a fraction of page width (0..0.5). */
    val marginLeft: Float = DEFAULT_MARGIN,

    /** Right page margin as a fraction of page width (0..0.5). */
    val marginRight: Float = DEFAULT_MARGIN,

    /** Top page margin as a fraction of page height. */
    val marginTop: Float = DEFAULT_VERTICAL_MARGIN,

    /** Bottom page margin as a fraction of page height. */
    val marginBottom: Float = DEFAULT_VERTICAL_MARGIN,

    /**
     * Lay text out top-to-bottom, right-to-left (縦書き). Null follows the publisher's writing mode.
     *
     * Null matters here more than anywhere else: forcing `horizontal-tb` on a Japanese light novel
     * rotates every line and destroys vertical ruby annotations.
     */
    val verticalWriting: Boolean? = null,

    /**
     * Whether a paragraph after the first is indented.
     *
     * Null follows the publisher, the same tri-state as [justified] and [hyphenation]. **Defaults to
     * true** rather than to null, which is the exception among the tri-states and is deliberate: the
     * stylesheet forced a 1em indent unconditionally before this was a setting, so true is what every
     * existing reader is already reading, and null would quietly restyle their whole library on
     * upgrade. The other three default to null because they did nothing before they were settings.
     */
    val paragraphIndent: Boolean? = true,
) {
    /**
     * The text size actually used for layout: [fontSizePt] scaled by [fontScale].
     *
     * Clamped at the low end so a user who drags both controls to minimum still gets legible text —
     * below roughly 8pt an EPUB is unreadable and users usually come back from that setting.
     */
    val effectiveFontSizePt: Float
        get() = (fontSizePt * fontScale).coerceIn(MIN_EFFECTIVE_SIZE_PT, MAX_EFFECTIVE_SIZE_PT)

    /**
     * The colour text is actually painted in, or null to leave the publisher's own alone.
     *
     * ### Why this exists rather than emitting `fontColor` directly
     *
     * A background and a text colour cannot be chosen independently once either one is set. The
     * reported symptom was that *any* background other than white made the page unreadable, and the
     * cause is structural rather than a bad default: the stylesheet emitted `background-color` only
     * when the reader asked for one and emitted `color` only when the reader *also* asked for one. A
     * reader who set a dark background and nothing else therefore got the publisher's own black text
     * on the reader's own black page — black on black, no warning, no error, and a setting screen
     * that looked like it had done nothing.
     *
     * So a background the reader chose implies a foreground the reader did not have to: whatever
     * contrasts with it. Derived rather than hard-coded to white, because a black background is just
     * as likely as a white one and a reader who sets one usually wants dark-mode text on it.
     *
     * It stays null when neither is set, which is the case the tri-state rules above exist to
     * protect: a book's own palette — a dark plate, a sepia page, coloured drop caps — is left alone.
     */
    val effectiveFontColor: String?
        get() = fontColor ?: backgroundColor?.let(::contrastingForeground)

    /** True when anything differs from the defaults and the stylesheet has to be injected. */
    val isCustomised: Boolean
        get() = this != DEFAULT

    /** This value with every field clamped into its legal range. */
    fun sanitized(): NovelTypography = copy(
        fontSizePt = fontSizePt.coerceIn(MIN_FONT_SIZE_PT, MAX_FONT_SIZE_PT),
        paragraphSpacing = paragraphSpacing.coerceIn(0f, MAX_PARAGRAPH_SPACING),
        lineSpacing = lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        fontSpacing = fontSpacing.coerceIn(-0.2f, 1.0f),
        fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        marginLeft = marginLeft.coerceIn(0f, MAX_MARGIN),
        marginRight = marginRight.coerceIn(0f, MAX_MARGIN),
        marginTop = marginTop.coerceIn(0f, MAX_VERTICAL_MARGIN),
        marginBottom = marginBottom.coerceIn(0f, MAX_VERTICAL_MARGIN),
    )

    companion object {
        const val DEFAULT_FONT_SIZE_PT = 18
        const val DEFAULT_FONT_FAMILY = "serif"
        const val DEFAULT_LINE_SPACING = 1.6f

        /** 5% of the page width. Wider than a phone screen needs, narrow enough to keep a line long. */
        const val DEFAULT_MARGIN = 0.05f

        /** 3% of page height, plus the system bars the reader already reserves. */
        const val DEFAULT_VERTICAL_MARGIN = 0.03f

        const val MIN_FONT_SIZE_PT = 10
        const val MAX_FONT_SIZE_PT = 40
        const val MIN_EFFECTIVE_SIZE_PT = 8f
        const val MAX_EFFECTIVE_SIZE_PT = 72f

        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 3.0f

        const val MAX_PARAGRAPH_SPACING = 3.0f

        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 3.0f

        /** A margin can never take more than half the page, or there is no column left to read. */
        const val MAX_MARGIN = 0.45f
        const val MAX_VERTICAL_MARGIN = 0.45f

        val DEFAULT = NovelTypography()

        /**
         * Black or white, whichever reads better on [background].
         *
         * Pure, so it can be asserted off-device, and it is the reason the reader never paints a
         * reader-chosen background under a publisher-chosen text colour. See
         * [NovelTypography.effectiveFontColor].
         *
         * The choice is by WCAG relative luminance rather than a brightness sum: perceived brightness
         * is not the same as contrast, and a mid-tone like `#808080` is exactly where a naive
         * average picks the wrong one. Only the two extremes are offered, so this is a binary
         * decision between two known contrasts rather than a search for a better third colour.
         *
         * Null when [background] is not a hex colour at all — a hand-edited preference, or a value an
         * extension wrote. A null here means "keep the publisher's colour", which is the status quo
         * and never worse than guessing.
         */
        fun contrastingForeground(background: String): String? {
            val channels = hexChannels(background) ?: return null
            val luminance = 0.2126 * relativeLuminance(channels[0]) +
                0.7152 * relativeLuminance(channels[1]) +
                0.0722 * relativeLuminance(channels[2])
            // WCAG contrast against each candidate: (lighter + 0.05) / (darker + 0.05).
            val againstBlack = (luminance + 0.05) / 0.05
            val againstWhite = 1.05 / (luminance + 0.05)
            return if (againstWhite >= againstBlack) LIGHT else DARK
        }

        /** `#rgb` / `#rrggbb` to 0..255 channels, or null for anything else. */
        private fun hexChannels(color: String): IntArray? {
            val hex = color.trim().removePrefix("#")
            if (hex.length != 3 && hex.length != 6) return null
            if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
            val value = expanded.toLongOrNull(16) ?: return null
            return intArrayOf(
                ((value shr 16) and 0xFF).toInt(),
                ((value shr 8) and 0xFF).toInt(),
                (value and 0xFF).toInt(),
            )
        }

        /** The sRGB gamma expansion WCAG defines, so mid-tones are not read as far too bright. */
        private fun relativeLuminance(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }

        const val DARK = "#000000"
        const val LIGHT = "#ffffff"
    }
}
