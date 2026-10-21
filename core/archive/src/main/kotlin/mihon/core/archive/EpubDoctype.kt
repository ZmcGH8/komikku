package mihon.core.archive

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Removes a `<!DOCTYPE …>` declaration from an XML document on its way to a parser.
 *
 * ### Why this exists, and why it is not a parser setting
 *
 * Every structural document in a book — `container.xml`, `content.opf`, the nav and NCX files — is
 * attacker-controlled markup that this app parses. An external entity in one is the classic route to
 * reading a local file or making a network request on behalf of the reader, and it arrives in the
 * document itself rather than in anything the user typed.
 *
 * The conventional close is to ask the parser not to resolve entities, via
 * `FEATURE_EXTERNAL_GENERAL_ENTITIES = false` and `FEATURE_EXTERNAL_PARAMETER_ENTITIES = false`. Those
 * constants **are not declared on `org.xmlpull.v1.XmlPullParser`** — they exist only on particular
 * implementations, so the call does not compile against the interface the parser is held as. Worth
 * recording, because the tempting workaround (cast to whatever implementation is present) is the
 * fragile thing to avoid: it fails at runtime rather than at compile time.
 *
 * The alternative that *is* portable is to not hand the parser a declaration in the first place. If
 * there is no doctype in the bytes, there is no SYSTEM identifier for any parser to act on, and the
 * guarantee holds for the platform parser, a vendor's replacement, and whatever is on the classpath
 * five years from now. It is also the only version of this control that can be **tested**: an
 * assertion about which implementation we happened to get cannot fail in the direction that matters.
 *
 * `EpubXmlReader` keeps `FEATURE_PROCESS_DOCDECL = false` as well. Belt to this braces; neither half
 * is load-bearing alone.
 *
 * ### Scope and cost
 *
 * A doctype may only appear before the root element, so the search is bounded to a prefix of the
 * document rather than to the whole file — [SCAN_BYTES]. The bounded prefix is read and the remainder
 * is streamed through, so a large `content.opf` is never materialised in order to remove a few dozen
 * bytes from the top of it.
 *
 * A doctype beyond the ceiling is left in place, which is safe: it could not have been terminated
 * inside the region scanned, so it cannot carry a complete SYSTEM identifier, and
 * `FEATURE_PROCESS_DOCDECL = false` rejects the document anyway. That is the correct outcome for a
 * malformed declaration.
 *
 * Its own file so it carries no Android imports — the harness in `scripts/kotlinc-check` compiles this
 * directly and asserts the behaviour. A security control nothing can reach is not a control.
 */
internal object EpubDoctype {

    /** Reads [input] with any doctype declaration removed. Never closes [input]. */
    fun strip(input: InputStream): InputStream {
        val head = ByteArray(SCAN_BYTES)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        if (read == 0) return input

        // ISO-8859-1 round-trips every byte to exactly one char, so an offset found in this string is
        // also a byte offset into the prefix and the slices below line up with the original bytes.
        val match = DECLARATION.find(String(head, 0, read, Charsets.ISO_8859_1))
            ?: return SequenceInputStream(ByteArrayInputStream(head.copyOf(read)), input)

        val before = ByteArrayInputStream(head.copyOfRange(0, match.range.first))
        val after = ByteArrayInputStream(head.copyOfRange(minOf(match.range.last + 1, read), read))
        return SequenceInputStream(before, SequenceInputStream(after, input))
    }

    /**
     * A doctype, including the internal subset form.
     *
     * The alternation rather than a plain `[^>\[]*` so a `>` inside a **quoted** public identifier
     * cannot end the match early: XHTML doctypes quote one, and nothing guarantees it contains no
     * `>`. Matching any quoted run as a unit is what makes the trailing `>` mean the end of the
     * declaration — without it, `<!DOCTYPE r PUBLIC "a>b" "http://…">` matches up to the `>` inside
     * the quotes, half-removes the declaration, and leaves a SYSTEM identifier in the bytes for the
     * parser. Bare `[^>\[]` would reintroduce that.
     */
    private val DECLARATION = Regex(
        """<!DOCTYPE(?:[^>"'\[]|"[^"]*"|'[^']*')*(?:\[[\s\S]*?\])?\s*>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * How far into a document a doctype is looked for.
     *
     * A doctype precedes the root element, so in practice it lives in the first few hundred bytes.
     * 64 KB sits far enough behind any BOM, prolog or comment preamble to be comfortable.
     */
    private const val SCAN_BYTES = 64 * 1024
}
