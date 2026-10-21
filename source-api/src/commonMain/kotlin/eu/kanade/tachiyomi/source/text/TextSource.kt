package eu.kanade.tachiyomi.source.text

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A [Source] whose chapters are read as **text** rather than as images.
 *
 * This is the extension point for EPUBs and web novels. It sits beside
 * [eu.kanade.tachiyomi.source.model.Page]-based image sources rather than replacing them: an image
 * source implements [eu.kanade.tachiyomi.source.Source.getPageList], a text source implements
 * [getTextPageList], and hosts are expected to check which one applies before building a renderer.
 *
 * Everything else in the manga contract — catalogue browsing, chapter lists, metadata, library
 * updates — is inherited unchanged. A text source is a normal source that happens to serve text, so
 * a webnovel extension implements [eu.kanade.tachiyomi.source.CatalogueSource] and gets the same
 * browse and search UI a manga source gets.
 *
 * ### Implementing
 *
 * ```kotlin
 * class MyNovelSource : CatalogueSource(), TextSource {
 *     override val capabilities = TextCapabilities(TOC = true, IMAGES = true)
 *
 *     override suspend fun getTextPageList(chapter: SChapter): List<TextPage> {
 *         val html = client.newCall(Request.Builder().url(chapter.url).build()).awaitBody()
 *         return listOf(TextPage(index = 0, url = chapter.url, text = html, baseHref = chapter.url))
 *     }
 * }
 * ```
 *
 * ### What a source must guarantee
 *
 * - **Stable [TextPage.url].** It is the identity used for reading position and bookmarks. A URL
 *   that changes between refreshes silently resets the reader's place in the book.
 * - **Self-contained [TextPage.text].** Hosts hand it straight to a WebView. Inline the styles a
 *   page needs; do not assume host-supplied CSS, because a page must look the same wherever it is
 *   opened.
 * - **[TextPage.baseHref] whenever pages reference external resources.** Without it an EPUB's
 *   images resolve against the host app's own URL space and all of them break.
 *
 * ### Content beyond the primary text
 *
 * A source that can do more than serve chapters opts in by implementing [TextTocSource],
 * [TextSearchSource] or [TextAnnotationSource]. These are separate interfaces rather than members so
 * that a host can test for exactly the capability it wants, and so implementing one never forces an
 * extension to implement the other two.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextSource : Source {

    /**
     * What this source can actually do, so hosts can hide controls that would not work.
     *
     * Declared rather than detected: a host cannot reliably tell that a source has no table of
     * contents until after the user has opened a chapter and found nothing there. Defaults to
     * nothing supported, which is the safe answer — a host that believes a capability exists and
     * then gets an empty result is a worse failure than a hidden button.
     */
    val textCapabilities: TextCapabilities get() = TextCapabilities()

    /**
     * The readable text of [chapter], in reading order.
     *
     * Return the natural units the format provides — EPUB spine items, one webnovel chapter per
     * entry — rather than pre-paginating. Hosts re-flow text to the screen, so a source that splits
     * by a fixed character count produces text that is cut mid-sentence at most screen sizes.
     *
     * Throws on failure; the host surfaces the exception as a load error rather than showing an
     * empty chapter.
     *
     * @param chapter the chapter to read.
     * @return the pages of the chapter, in reading order. Never empty for a valid chapter.
     * @since komikku/extensions-lib 1.6
     */
    suspend fun getTextPageList(chapter: SChapter): List<TextPage>
}
