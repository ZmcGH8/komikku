package mihon.core.archive

/**
 * Path handling for EPUB entries.
 *
 * EPUB references are URL relative references: `/` separated, possibly percent-encoded, possibly
 * containing `..`, sometimes absolute (root-relative), and frequently carrying a `#fragment` or a
 * query that is not part of the file name. `ArchiveReader` looks entries up by their literal stored
 * name, so every reference has to be normalised down to that exact string before it can be opened.
 *
 * All functions here are pure and JVM-only — no Android APIs — so the resolution rules stay
 * testable off-device.
 */
internal object EpubPaths {

    /**
     * Resolves [href] against [baseDir] to an archive-relative path.
     *
     * Strips the fragment and query, then handles absolute (`/x.html`) and relative
     * (`../images/a.png`) references and collapses `.`/`..`.
     *
     * [baseDir] is the directory of the referring document, with or without a trailing `/`.
     *
     * Percent-decoding is **not** applied here. A file literally named `%2E%2E` or `..` is a
     * filename, and decoding before the traversal collapse would turn it into a directory escape.
     * The decoded and re-encoded spellings are offered as lookup fallbacks by [lookupCandidates]
     * instead, so a mismatch between the manifest href and the stored zip name is recoverable
     * without ever letting decoding change which directory a reference points into.
     */
    fun resolve(baseDir: String, href: String): String {
        val withoutFragment = href.substringBefore('#').substringBefore('?')
        if (withoutFragment.isEmpty()) return normalize(baseDir)

        val isAbsolute = withoutFragment.startsWith("/")
        val rawPath = if (isAbsolute) withoutFragment.substring(1) else withoutFragment

        val base = if (isAbsolute) "" else baseDir
        val joined = if (base.isEmpty()) rawPath else "$base/$rawPath"

        return normalize(joined)
    }

    /** Directory portion of an archive path, or "" when there is none. */
    fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx < 0) "" else path.substring(0, idx)
    }

    /** Final path segment. */
    fun nameOf(path: String): String = path.substringAfterLast('/')

    /**
     * Collapses `.` and `..` segments and drops empty ones.
     *
     * A `..` that would escape the archive root is dropped rather than honoured: EPUB producers do
     * emit malformed references, and clamping to the root keeps a bad book open instead of
     * turning one broken href into a failure to load the whole book.
     */
    fun normalize(path: String): String {
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

    /** Splits `path#fragment` into its parts. [fragment] is null when there is none. */
    fun splitFragment(href: String): Pair<String, String?> {
        val hash = href.indexOf('#')
        return if (hash < 0) {
            href to null
        } else {
            href.substring(0, hash) to href.substring(hash + 1).ifEmpty { null }
        }
    }

    /**
     * True when [child] is [parent] or lives beneath it.
     *
     * Both must already be [normalize]d archive paths. Used to decide whether an `encryption.xml`
     * entry covers a given document.
     */
    fun isUnder(parent: String, child: String): Boolean =
        parent.isEmpty() || child == parent || child.startsWith("$parent/")

    /**
     * Percent-decodes [value].
     *
     * Deliberately not [java.net.URLDecoder]: that class implements HTML form encoding and turns a
     * literal `+` into a space, which corrupts the many EPUB files whose names contain `+`.
     *
     * A malformed `%` sequence is left as-is rather than throwing — a broken escape in one
     * filename must not stop the book from opening.
     */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value

        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = value[i + 1].hexDigit()
                val lo = value[i + 2].hexDigit()
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // Literal character, including non-ASCII: encode it as UTF-8 rather than truncating.
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun Char.hexDigit(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> -1
    }

    /**
     * Candidate archive entry names to try for [path], most likely first.
     *
     * Producers disagree on whether the name stored in the zip matches the `href` in the manifest
     * byte for byte: some store `a b.png` and reference `a%20b.png`, others do the reverse. Rather
     * than pick a winner and fail on half the library, the reader tries each spelling.
     *
     * `resolve` deliberately does not decode, so that a file genuinely named `..` stays a file — the
     * decoded spelling is offered here as a *fallback*, never as the canonical path.
     */
    fun lookupCandidates(path: String): List<String> {
        val decoded = percentDecode(path)
        val encoded = percentEncode(path)
        return listOf(path, decoded, encoded).distinct()
    }

    private fun percentEncode(value: String): String {
        if (value.none { it == ' ' || it == '(' || it == ')' || it == '\'' || it == '%' }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                ' ' -> out.append("%20")
                '(' -> out.append("%28")
                ')' -> out.append("%29")
                '\'' -> out.append("%27")
                '%' -> out.append("%25")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
