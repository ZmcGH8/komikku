package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.InputStream

/**
 * A parsed EPUB file.
 *
 * Wraps [ArchiveReader] and adds the three things a reader needs that a plain zip does not give
 * you: a package document that says what the files mean, a spine that fixes the reading order, and
 * a table of contents.
 *
 * ### What changed from the original implementation
 *
 * The previous version parsed the OPF with Jsoup's XML mode and looked elements up **by tag name** —
 * `doc.getElementsByTag("dc:title")`. That couples correctness to the producer's namespace prefix: a
 * book that binds Dublin Core to `dcterms:` instead of `dc:` produced an empty title, an empty
 * author and an empty description, silently. It also read only four metadata fields, had no TOC, no
 * `encryption.xml` check, and detected the path separator by looking for a backslash inside
 * `container.xml` — which is never there, so the result was always `/` by accident rather than by
 * design.
 *
 * Parsing is now namespace-aware (see [EpubXmlReader]) and produces a complete [EpubBook].
 *
 * ### Laziness
 *
 * [book] is parsed once on first access and cached. Every accessor after that is a field read. The
 * underlying [ArchiveReader] stays open for the lifetime of this object — [close] releases it — so
 * content documents can be streamed on demand rather than held in memory, which is what keeps a
 * 50 MB illustrated EPUB from becoming a 50 MB heap allocation.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /** Path of the OPF package document, as recorded in `container.xml`. */
    private val opfPath: String by lazy { findPackageHref() }

    /** The parsed book: metadata, manifest, spine, TOC and encryption state. */
    val book: EpubBook by lazy { parseBook() }

    /**
     * The spine items a reader should show, in order.
     *
     * Non-linear items and non-content documents are excluded, so this is directly usable as a
     * chapter list.
     */
    val contentDocuments: List<EpubSpineItem> get() = book.readableSpine

    /** Dublin Core metadata. */
    val metadata: EpubMetadata get() = book.metadata

    /** The book's table of contents, flattened depth-first. Empty when it has none. */
    val toc: List<EpubTocNode> get() = book.flatToc

    /** Archive path of the cover image, or null when the book has none. */
    val coverHref: String? get() = book.coverHref

    /**
     * True when the book is DRM-protected and therefore unreadable.
     *
     * Font obfuscation — which most "protected" books actually use — does not set this; it leaves
     * the text perfectly readable.
     */
    val hasDrm: Boolean get() = book.encryption.hasDrm

    // SY/Upstream kept this narrow API; it still serves the image-oriented local source, which
    // treats an EPUB as a comic and renders its illustrations.
    // KMK -->

    /** Returns the path of the package document. */
    fun getPackageHref(): String = opfPath

    /**
     * Returns the package document as a Jsoup tree, or null when it cannot be opened.
     *
     * Retained for the image-based local source. New code should read [metadata] instead: this
     * exposes namespace prefixes as literal tag names, so every selector against it has to hardcode
     * `dc:`.
     *
     * Nullable rather than `!!`-and-hope: the ref comes out of `container.xml`, and a book pointing
     * it at a file the archive does not contain is malformed rather than exceptional — but it used
     * to take the reader down with a `NullPointerException` instead of showing a book with no
     * metadata.
     */
    fun getPackageDocument(ref: String): Document? =
        getInputStream(ref)?.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }

    /**
     * Returns the path of all the images found in the epub file.
     *
     * Per-page, not per-document: the original walked the spine and collected `img` and `image`
     * references from each page's markup. That is the right answer for "render this EPUB as a
     * comic", where each illustration is a page. [textDocuments] is the right answer for reading it.
     *
     * Capped at [MAX_IMAGES]. Both inputs to the count are attacker-controlled — a document may
     * name any number of `img` elements and the spine is unbounded — and the caller turns the result
     * straight into pages, so a book declaring a million of them must not be able to allocate a
     * million of them here first.
     */
    fun getImagesFromPages(): List<String> {
        val result = mutableListOf<String>()

        for (spineItem in book.spine) {
            val markup = readText(spineItem.href) ?: continue
            val pageDir = EpubPaths.parentOf(spineItem.href)
            val document = Jsoup.parse(markup)

            for (element in document.allElements) {
                when (element.tagName()) {
                    "img" -> element.attr("src")
                    "image" -> element.attr("xlink:href").ifEmpty { element.attr("href") }
                    else -> continue
                }.takeIf { it.isNotBlank() }?.let { result += EpubPaths.resolve(pageDir, it) }
                if (result.size >= MAX_IMAGES) return result
            }
        }

        return result
    }
    // KMK <--

    /**
     * Opens an entry by archive path.
     *
     * Tries the path as given, then its percent-decoded and percent-encoded spellings, because
     * producers disagree about whether the name stored in the zip matches the `href` in the
     * manifest byte for byte. Without that fallback roughly half the library 404s on its own images.
     *
     * All three spellings go to [ArchiveReader.getInputStreamAny] together, so a reference that
     * needs the fallback costs one walk of the archive instead of up to three.
     */
    fun getInputStream(entryName: String): InputStream? {
        // A control character is never a real file name, and NUL in particular truncates the C
        // string libarchive hands the name back through — so it would silently resolve to whatever
        // precedes it. Refused outright rather than sanitised: there is no legitimate spelling of it.
        if (entryName.any { it.code < 0x20 || it.code == 0x7F }) return null
        return reader.getInputStreamAny(*EpubPaths.lookupCandidates(entryName).toTypedArray())
    }

    /**
     * True when [path] names an entry that exists.
     *
     * Answered from the archive's entry index, so it costs nothing rather than the open-and-discard
     * this used to do — which is what lets the image and cover paths probe freely.
     */
    fun contains(path: String): Boolean =
        EpubPaths.lookupCandidates(path).any { it in reader }

    /**
     * Reads an entry as text, or null when it does not exist or is over [MAX_DOCUMENT_BYTES].
     *
     * Charset comes from the XML declaration when there is one, because EPUB content files may be
     * UTF-16 or a legacy encoding; defaulting to UTF-8 renders mojibake for those. The whole entry
     * is read once and decoded afterwards — sniffing has to inspect the same bytes that are then
     * decoded, so the prolog cannot be consumed separately.
     *
     * Null for an oversized entry rather than a truncated string: half a document is not a document,
     * and the callers treat null as "skip this file", so one hostile file costs one page instead of
     * the whole book.
     */
    fun readText(path: String): String? {
        val bytes = readBytes(path) ?: return null
        return String(bytes, charsetOf(bytes))
    }

    /**
     * Reads an entry into memory, or null when it is missing or larger than [maxBytes].
     *
     * The cap is the reason this exists. A zip entry's declared size is attacker-controlled and its
     * compression ratio is unbounded, so `readBytes()` on an arbitrary entry is an out-of-memory
     * primitive handed to anyone who can get a file into the library — which is anyone who can send
     * the user a book.
     */
    fun readBytes(path: String, maxBytes: Long = MAX_DOCUMENT_BYTES): ByteArray? =
        getInputStream(path)?.use { it.readAtMost(maxBytes) }

    /**
     * Copies at most [limit] bytes out of this stream, or returns null when there are more.
     *
     * Refuses rather than truncating, so a caller never mistakes a clipped file for a whole one.
     */
    private fun InputStream.readAtMost(limit: Long): ByteArray? {
        if (limit <= 0) return null
        val out = java.io.ByteArrayOutputStream(minOf(limit, COPY_BUFFER_BYTES.toLong()).toInt())
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * The documents that make up the book's text, in reading order.
     *
     * This is the spine a reader paginates over — as opposed to [getImagesFromPages], which is the
     * spine treated as illustrations.
     */
    fun textDocuments(): List<EpubSpineItem> = book.readableSpine

    /**
     * Raw markup of one content document, suitable for handing straight to a WebView.
     *
     * Returned untouched on purpose. Publishers ship their own CSS, and the stripping that makes
     * text extraction easier is exactly what destroys the book — tables, drop caps, ruby
     * annotations, vertical writing. Cleanup belongs in the renderer, which at least can see the
     * document.
     */
    fun readDocument(item: EpubSpineItem): String? = readText(item.href)

    /**
     * Whether this book reads as prose or as a strip of illustrations.
     *
     * Decides whether an `.epub` in a manga library is a comic or a novel, which nothing upstream
     * knows and which every heuristic will occasionally get wrong — so the answer is a hint the host
     * makes overridable, never a verdict. See
     * `docs/superpowers/specs/2026-10-17-library-entry-types-design.md`.
     *
     * ### What it counts
     *
     * Both signals, because either alone is misleading:
     *
     * - **characters of prose** vs **image references**, accumulated across the readable spine. A
     *   novel has orders of magnitude more text than pictures; an art book is nearly all `<img>`.
     * - **a table of contents with real entries.** Publishers build one for prose and rarely bother
     *   for a plate sequence, and it is cheap to read since the TOC is already parsed for the reader.
     *
     * Text weight wins because it is the more reliable of the two: an art book can carry a title page
     * and captions, so it is never *zero* prose, while a novel can carry three illustrations in five
     * hundred documents and still be overwhelmingly prose. The TOC corroborates but cannot decide —
     * plenty of real books ship none.
     *
     * Bounded by [PROSE_SCAN_DOCUMENTS] and [PROSE_SAMPLE_CHARS] so that asking this of a hostile
     * file costs a fixed amount rather than a whole spine read. Sampling the first documents is the
     * right shape for the question: a book's character is established by its opening, and a
     * pathological book cannot make this expensive.
     */
    fun readsAsProse(): Boolean {
        val documents = textDocuments()
        if (documents.isEmpty()) return false

        var prose = 0
        var images = 0
        for (item in documents.take(PROSE_SCAN_DOCUMENTS)) {
            val markup = readText(item.href) ?: continue
            images += IMG_TAG.findAll(markup).count()
            prose += markup
                .replace(SCRIPT_OR_STYLE, " ")
                .replace(TAG, " ")
                .replace(WHITESPACE, " ")
                .count { !it.isWhitespace() }
            if (prose > PROSE_SAMPLE_CHARS) break
        }

        // More text than references by a wide margin is prose. The threshold is deliberately far from
        // 1:1, because the interesting cases are mixed (an illustrated novel has both in every
        // chapter) and only an art book approaches parity.
        return prose > images * PROSE_TEXT_PER_IMAGE && (prose > PROSE_MIN_CHARS || toc.isNotEmpty())
    }

    /** Best-effort encoding detection from the XML prolog, falling back to UTF-8. */
    private fun charsetOf(bytes: ByteArray): java.nio.charset.Charset {
        val prolog = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val match = ENCODING_PATTERN.find(prolog) ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }
            .getOrDefault(Charsets.UTF_8)
    }

    /**
     * Locates the OPF through `META-INF/container.xml`, falling back to the conventional paths.
     *
     * The fallback is not paranoia: files produced by tools that skip `container.xml` still open
     * fine in every other reader, so refusing them would be a regression for no benefit.
     */
    private fun findPackageHref(): String {
        getInputStream("META-INF/container.xml")?.use { stream ->
            val href = EpubContainerParser.parseRootfilePath(stream)
            if (!href.isNullOrBlank()) return EpubPaths.normalize(href)
        }
        return FALLBACK_PACKAGE_PATHS.firstOrNull { contains(it) } ?: FALLBACK_PACKAGE_PATHS.first()
    }

    private fun parseBook(): EpubBook {
        val opf = getInputStream(opfPath)?.use { EpubOpfParser.parse(it, opfPath) }
            ?: OpfDocument(EpubMetadata(), emptyMap(), emptyList())

        val manifest = opf.manifest

        val navHref = manifest.values.firstOrNull { it.isNavigation }?.href
        val ncxHref = opf.metadata.ncxId?.let(manifest::get)?.href
        val toc = EpubTocParser.parse(navHref, ncxHref) { getInputStream(it) }

        val encryption = getInputStream("META-INF/encryption.xml")
            ?.use { EpubEncryptionParser.parse(it) }
            ?: EpubEncryption.NONE

        return EpubBook(
            metadata = opf.metadata,
            manifest = manifest,
            spine = opf.spine,
            toc = toc,
            encryption = encryption,
        )
    }

    override fun toString(): String = "EpubReader(package=$opfPath, spine=${book.spine.size})"

    private companion object {
        val ENCODING_PATTERN = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']")

        /** Conventional locations, tried in order when `container.xml` is missing or unreadable. */
        val FALLBACK_PACKAGE_PATHS = listOf(
            "OEBPS/content.opf",
            "EPUB/content.opf",
            "content.opf",
        )

        /** Copy buffer for the bounded read. Large enough that the loop is not the bottleneck. */
        const val COPY_BUFFER_BYTES = 16 * 1024

        /**
         * Ceiling on any single entry read fully into memory.
         *
         * Comfortably above a real chapter — the largest text document in a commercial light novel
         * is well under a megabyte — and far below the heap a low-end device gives away before the
         * app is killed. A zip entry claiming more than this is not a document.
         */
        const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024

        /** Ceiling on the illustration list [getImagesFromPages] will build. */
        const val MAX_IMAGES = 20_000

        /**
         * Documents [readsAsProse] looks at before it has enough to decide.
         *
         * A book's character is established by its opening. Twenty is well past the point where more
         * documents change the answer, and it bounds the cost of asking this of a hostile spine that
         * declares fifty thousand items.
         */
        const val PROSE_SCAN_DOCUMENTS = 20

        /**
         * Non-whitespace characters [readsAsProse] accumulates before it stops reading.
         *
         * A detection pass should be near-invisible next to opening the book, and no real book needs
         * a megabyte of its own text to be recognised as prose.
         */
        const val PROSE_SAMPLE_CHARS = 1_000_000

        /**
         * How many characters of prose one image reference is worth in [readsAsProse].
         *
         * An art book approaches 1:1 and must fall below this to be called a comic; an illustrated
         * novel sits far above it even when every chapter opens with a full-page plate.
         */
        const val PROSE_TEXT_PER_IMAGE = 8

        /**
         * Prose characters below which a book is only prose if it has a table of contents.
         *
         * Catches the degenerate near-empty cases — a title page, a copyright leaf — that would
         * otherwise be called a novel on the strength of a handful of characters.
         */
        const val PROSE_MIN_CHARS = 500

        val IMG_TAG = Regex("(?i)<img\\b[^>]*>")
        val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        val TAG = Regex("(?s)<[^>]*>")
        val WHITESPACE = Regex("[ \\t\\r\\n\\u00A0]+")
    }
}

/** Reads `META-INF/container.xml` and returns the `full-path` of the first `rootfile`. */
internal object EpubContainerParser {

    fun parseRootfilePath(input: InputStream): String? {
        val xml = EpubXmlReader(input)
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.matches(EpubXmlReader.NS_CONTAINER, "rootfile")) {
                xml.attr("full-path")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}
