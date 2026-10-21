package mihon.core.archive

/**
 * Identifies an in-archive resource from its first bytes, for the ones its name cannot classify.
 *
 * ### Why the extension is not enough
 *
 * [EpubArchiveUrls.contentTypeForPath] decides a resource's type from its extension, which is right
 * whenever a publisher used one. It is not always right, and the failure mode is invisible: the
 * reader hands the renderer a type it did not ask for, and the renderer declines to decode it. A
 * plate served as `application/octet-stream` is a broken image with no error, no log line and no
 * entry in the book's markup — the document still asks for it by name, it simply never arrives. That
 * was reported as "images are not displayed" and nothing in the UI could tell a book with no
 * illustrations from a book whose illustrations were all rejected.
 *
 * Two things reach this function that the extension table could not answer:
 *
 *  - **an extension nobody has a row for.** Real archives ship `.avif`, `.jfif`, `.apng`, `.tif`,
 *    `.heic`, and files with no extension at all. The manifest's own `media-type` is the correct
 *    answer for these, but a manifest is frequently incomplete or wrong, and the renderer cannot be
 *    handed a guess.
 *  - **a name that is not the resource's.** An EPUB is allowed to name a JPEG `.bin`, and producers
 *    that rewrite a book for compatibility do exactly that.
 *
 * ### Why it is a magic-byte table and not a real sniffer
 *
 * It answers one question — "is this an image, and which one" — from a prefix a decoder would
 * recognise. Anything longer needs the whole file, and the whole file is what a streaming resource is
 * specifically built to avoid materialising. The table is ordered and first-match-wins, so a longer
 * signature is listed before a shorter one that is a prefix of it (`\u0000\u0000\u0000jP  ` before the
 * bare `\u0000\u0000\u0000\u0010jP  ` for JPEG XL, whose container prefix is shared with JPEG 2000).
 *
 * Pure, and therefore assertable off-device: `DocumentHarness` runs the whole table. Public
 * because the book renderer's inlining path (see `NovelInline`) needs the same answer the
 * streaming path gets, from the same table rather than a second copy of it.
 */
object EpubMimeSniff {

    /** How many leading bytes a signature may need. */
    private const val MAX_SIGNATURE = 12

    /**
     * The MIME type [header] begins with, or null when it is not something this knows.
     *
     * Null is the honest answer for "I do not know", which leaves the caller's own fallback in
     * place. Guessing `application/octet-stream` here instead would move the failure rather than fix
     * it, because a renderer asked for an octet stream declines to decode it exactly as it declines
     * to decode a wrongly-typed image.
     */
    fun of(header: ByteArray): String? {
        if (header.size < MIN_SIGNATURE) return null
        return SIGNATURES.firstOrNull { matches(header, it) }?.type
    }

    /**
     * The MIME type for the first [MAX_SIGNATURE] bytes of [stream], or null.
     *
     * Marks and then restores, because the stream belongs to whoever is reading the resource next:
     * handing the renderer a stream already advanced by a sniff is a resource that decodes as
     * garbage, which looks identical to the bug this exists to fix. Buffered rather than a raw
     * read-and-mark so the bytes are only ever copied once.
     */
    fun ofStream(stream: java.io.InputStream): String? {
        val buffered = stream.markSupported().let { supportsMark ->
            if (supportsMark) stream else java.io.BufferedInputStream(stream)
        }
        return try {
            buffered.mark(MAX_SIGNATURE)
            val header = ByteArray(MAX_SIGNATURE)
            val read = buffered.read(header)
            buffered.reset()
            if (read < MIN_SIGNATURE) null else of(header)
        } catch (_: java.io.IOException) {
            // A stream that cannot even be peeked at is the caller's problem to report; sniffing is
            // strictly an improvement over not knowing, so a failure here must not become one.
            null
        }
    }

    /** Shortest prefix any signature in the table needs. */
    private const val MIN_SIGNATURE = 3

    private data class Signature(val magic: ByteArray, val type: String, val wildcard: IntRange)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /**
     * No byte of the signature is allowed to differ.
     *
     * Declared before [SIGNATURES] because an object's properties initialise in declaration order and
     * every row below reads it.
     */
    private val NO_WILDCARD = IntRange.EMPTY

    /**
     * Every signature this recognises.
     *
     * Images first, because they are the failure being fixed, then fonts: a font served as an octet
     * stream is a book whose publisher's own typography silently disappears, which reads as "this
     * reader ignores the book's styling" rather than as one missing file.
     *
     * Order only matters where one signature is a prefix of another, and each such pair is listed
     * longest-first below.
     */
    private val SIGNATURES: List<Signature> = listOf(
        Signature(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "image/png", NO_WILDCARD),
        Signature(bytes(0xFF, 0xD8, 0xFF), "image/jpeg", NO_WILDCARD),
        Signature(bytes(0xFF, 0x4F, 0xFF, 0x51), "image/j2c", NO_WILDCARD),
        // JPEG XL's naked codestream. Listed before the two ISO-BMFF containers because it is a
        // two-byte prefix that has to be rejected on its own.
        Signature(bytes(0xFF, 0x0A), "image/jxl", NO_WILDCARD),
        Signature(ascii("GIF87a"), "image/gif", NO_WILDCARD),
        Signature(ascii("GIF89a"), "image/gif", NO_WILDCARD),
        // Two ISO-BMFF containers that agree in their first four bytes and in their tail, and differ
        // only in a brand at offset 4. Matched in full rather than on the shared `00 00 00 0C`, so
        // neither can be mistaken for the other.
        Signature(bytes(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A), "image/jxl", NO_WILDCARD),
        Signature(bytes(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A), "image/jp2", NO_WILDCARD),
        Signature(bytes(0x42, 0x4D), "image/bmp", NO_WILDCARD),
        Signature(bytes(0x49, 0x49, 0x2A, 0x00), "image/tiff", NO_WILDCARD),
        Signature(bytes(0x4D, 0x4D, 0x00, 0x2A), "image/tiff", NO_WILDCARD),
        // RIFF keeps a four-byte chunk length at offset 4 and its real type at offset 8, so this is
        // two prefixes with an unknown length between them. WebP is the only RIFF image a renderer
        // decodes and the only one a book carries.
        Signature(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP"), "image/webp", 4..7),
        Signature(bytes(0x66, 0x74, 0x79, 0x70), "image/avif", NO_WILDCARD),
        Signature(bytes(0x66, 0x74, 0x79, 0x70, 0x6D, 0x69, 0x73, 0x6F, 0x6D), "video/mp4", NO_WILDCARD),
        Signature(bytes(0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63), "video/mp4", NO_WILDCARD),
        Signature(bytes(0x73, 0x6D, 0x6F, 0x6F, 0x74, 0x68), "video/mp4", NO_WILDCARD),
        Signature(bytes(0x1A, 0x45, 0xDF, 0xA3), "video/webm", NO_WILDCARD),
        // An SVG with no extension, or named `.bin`. Not an image *format*, but an illustration the
        // renderer will decline unless it is told it is SVG.
        Signature(ascii("<svg"), "image/svg+xml", NO_WILDCARD),
        Signature(bytes(0x00, 0x01, 0x00, 0x00), "font/ttf", NO_WILDCARD),
        Signature(bytes(0x74, 0x72, 0x75, 0x65), "font/ttf", NO_WILDCARD),
        Signature(bytes(0x4F, 0x54, 0x54, 0x4F), "font/otf", NO_WILDCARD),
        Signature(bytes(0x77, 0x4F, 0x46, 0x46), "font/woff", NO_WILDCARD),
        Signature(bytes(0x77, 0x4F, 0x46, 0x32), "font/woff2", NO_WILDCARD),
    )

    /**
     * True when [header] begins with [signature]'s magic, ignoring its [Signature.wildcard] range.
     *
     * A wildcard range is for a field that is genuinely variable but fixed-width, and only for one:
     * allowing gaps everywhere would turn the table into a heuristic, and a heuristic that matches
     * loosely will eventually match a container it is not meant to.
     */
    private fun matches(header: ByteArray, signature: Signature): Boolean {
        if (header.size < signature.magic.size) return false
        for (i in signature.magic.indices) {
            if (i in signature.wildcard) continue
            if (header[i] != signature.magic[i]) return false
        }
        return true
    }
}
