// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import java.util.Locale

/**
 * The general reader theme, as a book's stylesheet can use it.
 *
 * The novel viewer used to honour only the Reading-appearance tab's background. The general tab's
 * reader theme painted the *container* behind the WebView and nothing else, so choosing **black**
 * there left the WebView transparent over a black window while the document kept the publisher's
 * own black text — reported as "the text disappears". This is the bridge across: the theme's
 * background colour, as a `#rrggbb` the stylesheet can emit, at which point the existing
 * [NovelTypography.effectiveFontColor] rule derives a text colour that contrasts with it and the
 * page becomes readable without the reader having to know the rule exists.
 *
 * No Android imports on purpose, so it can be asserted off-device like the rest of this folder's
 * pure half. The input is the colour the app already computes for the reader theme
 * (`Context.readerBackgroundColor`), so this file owns only the *policy* — which of those colours
 * a book should be forced to wear — and not the palette itself.
 */
object NovelReaderTheme {

    /**
     * The packed `argb` a white reader theme answers with. Locally declared rather than read from
     * `android.graphics.Color`, which would drag the platform into the one file here that is
     * asserted without it.
     */
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * The background a text document should be forced to for [readerBackground], or null when the
     * book must be left to the publisher's own palette.
     *
     * Null — and not `#ffffff` — for the light theme, deliberately: white is the absence of a
     * reader's opinion (a transparent WebView over a white container already reads as white), and
     * emitting `background-color: white !important` anyway would strip a publisher's own dark
     * plate for no benefit while being indistinguishable from a reader who chose it.
     *
     * Every dark answer passes through [NovelTypography.sanitized] and
     * [NovelTypography.effectiveFontColor] downstream exactly as an appearance-tab choice would,
     * so there is no second colour rule to keep honest.
     */
    fun backgroundHex(readerBackground: Int): String? {
        if (readerBackground == WHITE) return null
        return String.format(Locale.ROOT, "#%06x", readerBackground and 0xFFFFFF)
    }
}
// KMK <--
