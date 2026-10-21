package eu.kanade.tachiyomi.source.text

import java.io.InputStream

/**
 * A [TextSource] whose pages reference resources that are not inline — images, stylesheets, fonts.
 *
 * ### Why this is separate from [TextSource]
 *
 * [TextPage.text] is markup, and markup is full of `src` and `href` pointing at things the host
 * cannot reach on its own. An EPUB's `<img src="../images/plate.jpg">` refers to a file inside a
 * zip; a web novel's `<img src="/static/cover.png">` refers to a web server. Inlining every image
 * as a `data:` URI would work and is deliberately *not* what this does: a single illustrated
 * chapter becomes tens of megabytes of base64 held in memory for as long as the chapter is open,
 * and a whole illustrated novel becomes unusable.
 *
 * So the host streams them on demand instead, and this interface is how it asks for one.
 *
 * ### How a host uses it
 *
 * Give the document a base URL on a host the app controls, then intercept requests to it:
 *
 * ```kotlin
 * webView.webViewClient = object : WebViewClient() {
 *     override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
 *         source.openResource(request.url.toString())   // null lets non-archive URLs through
 * }
 * ```
 *
 * Returning null for a URL that is not an archive path is important — it is what lets an
 * absolutely-linked image or an external stylesheet load normally instead of failing.
 *
 * @since komikku/extensions-lib 1.6
 */
interface TextResourceSource {

    /**
     * Opens the resource [absoluteUrl] refers to, or returns null when this source does not serve
     * it.
     *
     * @param absoluteUrl a URL as it appears in a [TextPage.text] resource reference, resolved
     *   against the page's [TextPage.baseHref] by the host before being passed here.
     * @return an open stream the host reads to completion and closes, or null to let the platform
     *   handle the URL itself.
     */
    fun openResource(absoluteUrl: String): InputStream?

    /**
     * Content type for [absoluteUrl], for the response headers.
     *
     * Return null to let the host guess from the file extension. Returning a wrong type is worse
     * than returning null: a CSS file served as `text/plain` is silently ignored by the engine, and
     * the book renders with the publisher's styles missing and no error anywhere.
     */
    fun contentTypeFor(absoluteUrl: String): String? = null
}
