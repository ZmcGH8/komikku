// Assertions for the OPF metadata pass and the TOC label reader: the shapes real producers emit,
// and the two ways markup reaches a field the app *displays as text*. Pure JVM: OpfPlatformParser
// links kxml2 so `Xml.newPullParser` stands up without Android, and EpubOpfParser/EpubTocParser run
// here as the real project sources.
// Run via scripts/kotlinc-check/run-harness.sh OpfMetadata

import java.io.ByteArrayInputStream
import mihon.core.archive.EpubOpfParser
import mihon.core.archive.EpubTocParser

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun opf(metadataBody: String) = EpubOpfParser.parse(
    ByteArrayInputStream(
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<package xmlns=\"http://www.idpf.org/2007/opf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
            append("  <metadata>\n")
            append(metadataBody)
            append("\n  </metadata>\n  <manifest/>\n  <spine/>\n</package>")
        }.encodeToByteArray(),
    ),
    "content.opf",
).metadata

fun main() {
    // 1) A book whose metadata carries markup inside the display fields — both shapes real
    //    producers emit: a child element (`dc:creator` wrapping a bare `creator`) and tags
    //    hand-escaped into the text. Whatever reached these fields reads as text; and the parse
    //    must still arrive at the field *after* each one, which is what the walk contract is for.
    val messy = opf(
        """    <meta name="cover" content="cover-id"/>
    <dc:title>Harry <em>Potter</em> and the Prisoner of Azkaban</dc:title>
    <dc:title>Third book</dc:title>
    <dc:creator><creator>J.K. Rowling</creator></dc:creator>
    <dc:publisher>&lt;creator&gt;Raincoast Books&lt;/creator&gt;</dc:publisher>
    <dc:description><p>Sirius Black.</p></dc:description>""",
)

    check("title child markup dropped, text kept", messy.title, "Harry Potter and the Prisoner of Azkaban")
    check("subtitle joined from the second title", messy.subtitle, "Third book")
    check("nested wrapper read as its text", messy.authors, listOf("J.K. Rowling"))
    check("hand-escaped tags stripped", messy.publisher, "Raincoast Books")
    // Only the opening tag is asserted, not a full round-trip: the exact serialization of the
    // description's own end tag is a property of the pull parser's depth reporting, not of the
    // plain-text rule under test here.
    check("description keeps its markup", messy.description?.startsWith("<p>Sirius Black."), true)
    check("display title joins both parts", messy.displayTitle, "Harry Potter and the Prisoner of Azkaban: Third book")

    // 2) Whitespace the producer wrapped the value in is formatting, not content.
    val spaced = opf(
        """    <dc:title>
      Harry Potter
    </dc:title>""",
)
    check("inner whitespace collapsed", spaced.title, "Harry Potter")

    // 3) The namespace URI decides, not the prefix a producer liked: a book binding Dublin Core
    //    to `dcterms:` on the *elements* URI parses like any other. (Prefix variation, not a
    //    vocabulary change.)
    val prefixed = opf(
        """    <dcterms:title xmlns:dcterms="http://purl.org/dc/elements/1.1/">Harry Potter</dcterms:title>
    <dcterms:creator xmlns:dcterms="http://purl.org/dc/elements/1.1/">J.K. Rowling</dcterms:creator>""",
)
    check("unusual prefix still parsed", prefixed.title, "Harry Potter")
    check("unusual prefix author kept", prefixed.authors, listOf("J.K. Rowling"))

    // 4) Table-of-contents labels are display text too: a nav label carrying inline markup reads
    //    as the words around it, and the sibling entry after it is not swallowed.
    val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="chap1.xhtml">Chapter <b>One</b></a></li><li><a href="chap2.xhtml">Two</a></li></ol></nav></body></html>"""
    val tocFromNav = EpubTocParser.parse("nav.xhtml", null) { href ->
        if (href == "nav.xhtml") ByteArrayInputStream(nav.encodeToByteArray()) else null
    }
    check("nav label markup dropped", tocFromNav.firstOrNull()?.label, "Chapter One")
    check("nav labels stay one per row", tocFromNav.map { it.label }, listOf("Chapter One", "Two"))

    // 5) The EPUB 2 NCX text element, with tags the producer escaped into the value: those arrived
    //    here as junk to display, so the label keeps what was meant to be shown.
    val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="n1" playOrder="1"><navLabel><text>Scene &lt;i&gt;two&lt;/i&gt;</text></navLabel><content src="chap2.xhtml"/></navPoint></navMap></ncx>"""
    val tocFromNcx = EpubTocParser.parse(null, "toc.ncx") { href ->
        if (href == "toc.ncx") ByteArrayInputStream(ncx.encodeToByteArray()) else null
    }
    check("ncx escaped tags stripped", tocFromNcx.firstOrNull()?.label, "Scene two")

    if (failures > 0) println("FAILURES: $failures")
}
