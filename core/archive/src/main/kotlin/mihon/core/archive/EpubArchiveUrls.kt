package mihon.core.archive

/**
 * The reserved URL space EPUB documents are loaded in, and the pure translation between it and
 * in-archive paths.
 *
 * ### Why a host does not get a browser and still needs URLs
 *
 * A renderer is handed a base URL and resolves a document's relative `src` and `href` against it.
 * Those references name files *inside the archive* — `../images/plate.jpg` — which the renderer
 * cannot open on its own. So the host invents a URL space, hands it to the renderer, and intercepts
 * requests back into the archive. That is what this namespace is.
 *
 * `book.internal` is reserved by RFC 6761 and can never collide with a real host, so a book that
 * links to a genuine external image is never mistaken for an archive one.
 *
 * ### Why the scheme is `https` and not something invented
 *
 * It was `epub-archive` once, and that was the whole reason "images are not rendered" outlived every
 * other fix in the book path. A WebView document is loaded with `loadDataWithBaseURL`, whose base
 * URL the platform documents as **must be an HTTP(S) URL**, and the same documentation warns that
 * WebView "applies restrictions when requesting resources… that use a custom URL scheme" and only
 * invokes the interception callbacks for well-formed URLs. In practice that meant the inline
 * document rendered — it is handed over as a string — while every `<img>`, `<link>` and `@font-face`
 * it asked for was refused before `shouldInterceptRequest` was ever consulted: the book rendered as
 * bare text wearing only the reader's own styling, with no error anywhere. Serving the same reserved
 * host under `https` is the pattern `WebViewAssetLoader` itself uses (`appassets.androidplatform.net`),
 * and no request to it ever leaves the device because the viewer intercepts every one.
 *
 * ### Why this is here and not in a source
 *
 * The manga local source serves books, and so does anything else that ever grows a book half — and a
 * source and the reader cannot depend on each other. The translation is identical for every one of
 * them and has to stay identical — the per-document base URL is the single most consequential detail
 * in the whole EPUB path, and it silently fails as bare text when it is wrong. One implementation,
 * in the module all of them already depend on, with no source-API types in sight.
 *
 * Every function here is pure and asserted by `scripts/kotlinc-check`.
 */
object EpubArchiveUrls {

    /**
     * The scheme of the reserved space. Plain `https`, deliberately: a custom scheme was the bug,
     * not the safety. See the class doc for what it cost.
     */
    const val SCHEME = "https"

    /** Reserved host. Never resolvable on a real network. */
    const val HOST = "book.internal"

    /**
     * The root of the archive in URL form.
     *
     * A valid base URL only for a document that already sits at the archive root. For anything below
     * it this is the wrong answer and [documentBaseUrl] is right — see its comment for what goes
     * wrong when it is used anyway.
     */
    const val BASE_URL = "$SCHEME://$HOST/"

    /**
     * The URL a document must be **loaded at** so its own relative references resolve.
     *
     * ### The one thing that is easy to get wrong
     *
     * A spine item at `OEBPS/Text/chapter1.xhtml` links to `../styles/main.css`. Loaded against
     * the **archive root** that resolves to `styles/main.css` — not `OEBPS/styles/main.css` — so
     * the stylesheet is never found and the book renders with the publisher's typography, all of its
     * illustrations and its ruby annotations silently missing. The text still shows, which is
     * exactly what makes it read as "the reader is broken" rather than "one file is missing".
     *
     * So the base URL is the **directory of the document**, and the trailing slash is load-bearing:
     * without it the renderer treats the last segment as a file and every sibling reference resolves
     * one level up.
     *
     * Normalised the same way an href is before a lookup, so a `..` in a producer's href cannot walk
     * the base URL off the reserved host and leave the renderer's relative resolution pointing at
     * something this namespace does not serve.
     */
    fun documentBaseUrl(baseHref: String?): String {
        val path = baseHref?.trim()?.substringBefore('#')?.substringBefore('?')?.trim('/').orEmpty()
        if (path.isEmpty()) return BASE_URL
        val slash = path.lastIndexOf('/')
        if (slash < 0) return BASE_URL
        val dir = normalizeArchivePath(path.substring(0, slash))
        if (dir.isEmpty()) return BASE_URL
        return BASE_URL + dir + "/"
    }

    /**
     * The archive path [absoluteUrl] names, or null when it is not one of ours.
     *
     * Null is the host's signal to let the URL load normally, which is what keeps a book that links
     * to an external stylesheet working.
     *
     * Matched on the scheme **and the reserved host**, not on the scheme alone: the scheme is real
     * `https` now, so a scheme-only test would swallow every genuinely external image, stylesheet
     * and footnote the book points at. The host is matched loosely rather than against one exact
     * prefix: the path is whatever follows the first `/` after the authority, so a document loaded
     * at a different reserved host still resolves. The path is percent-decoded because the renderer
     * hands it back encoded (`My%20Image.jpg`) while the archive stores it decoded (`My Image.jpg`).
     */
    fun archivePathOf(absoluteUrl: String): String? {
        if (!isArchiveUrl(absoluteUrl)) return null
        val afterAuthority = absoluteUrl.substringAfter("$SCHEME://")
        val path = afterAuthority.substringAfter('/', "")
            .substringBefore('#')
            .substringBefore('?')
        return percentDecode(path)
            .takeIf { it.isNotBlank() }
            ?.let(::normalizeArchivePath)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * True when [url] is in this namespace.
     *
     * The reserved host is what makes this a test rather than a guess: `book.internal` cannot belong
     * to a real site, so claiming every request to it costs nothing, while any other `https` host
     * stays untouched and loads normally.
     */
    fun isArchiveUrl(url: String): Boolean =
        url.startsWith("$SCHEME://$HOST/", ignoreCase = true)

    /**
     * MIME type for an in-archive [path], or null when the extension is not recognised.
     *
     * Null rather than a guess: the host falls back to sniffing the bytes (see [EpubMimeSniff]), and
     * a wrong type is worse than none — a stylesheet served as `text/plain` is ignored by the
     * renderer and the book loses its own appearance with no error anywhere, while an image served as
     * `application/octet-stream` simply never draws. Returning null keeps the better of the two
     * answers available; returning a default forecloses it.
     *
     * The table is carried over verbatim from the implementation this replaced. These strings are
     * load-bearing for the renderer rather than merely conventional — `image/svg+xml` and
     * `image/svg` differ to it, `text/html` is what makes XHTML parse — so "tidying" the mapping is
     * a rendering change wearing a cleanup's clothes.
     */
    fun contentTypeForPath(path: String): String? =
        when (path.substringAfterLast('.', "").substringBefore('?').lowercase()) {
            "xhtml", "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg", "jpe", "pjpeg", "jfif" -> "image/jpeg"
            "png", "apng" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "ico" -> "image/x-icon"
            "jp2", "j2k", "jpf", "jpx" -> "image/jp2"
            "jxl" -> "image/jxl"
            "ttf", "ttc" -> "font/ttf"
            "otf", "otc" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "opf" -> "application/oebps-package+xml"
            "ncx" -> "application/x-dtbncx+xml"
            "xml" -> "application/xml"
            "mp3", "m4a", "m4b" -> "audio/mpeg"
            "mp4", "m4v", "m4p" -> "video/mp4"
            "webm" -> "video/webm"
            else -> null
        }

    /**
     * Collapses `.` and `..`, clamping at the archive root.
     *
     * The same rule `EpubPaths.normalize` applies to a manifest href, reached through a copy
     * because a renderer hands this an already-percent-decoded URL rather than an href — but the two
     * converge on one spelling, which is what makes a `..` mean the same thing wherever it arrives
     * from.
     *
     * Surviving normalisation is still only ever looked up as a literal entry name inside the open
     * archive; there is no filesystem beneath it. What the traversal buys is that a URL the host
     * generated is not the thing deciding where a request lands.
     */
    fun normalizeArchivePath(path: String): String {
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    /** Percent-decoding that does not throw on the malformed escapes a book's markup can contain. */
    fun percentDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
