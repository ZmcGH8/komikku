// Assertions for the reader's contents + in-chapter search model. Pure JVM by design: the file
// under test imports only the extension API's text models, so it links directly.
// Run via scripts/kotlinc-check/run-harness.sh Navigation
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TocEntry
import eu.kanade.tachiyomi.ui.reader.viewer.novel.flattenToc
import eu.kanade.tachiyomi.ui.reader.viewer.novel.toSearchResults

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)

fun main() {
    // ---------------------------------------------------------------- table of contents
    val toc = listOf(
        TocEntry("Start", "OEBPS/ch01.xhtml"),
        TocEntry(
            "Chapter One",
            "OEBPS/ch01.xhtml",
            children = listOf(
                TocEntry("Scene one", "OEBPS/ch01.xhtml#s1"),
                TocEntry(
                    "Scene two",
                    "OEBPS/ch02.xhtml#s2",
                    children = listOf(TocEntry("Beat a", "OEBPS/ch02.xhtml#s2a")),
                ),
            ),
        ),
        // Points at a file in another chapter of the book: shown, but not tappable.
        TocEntry("Volume Two", "OEBPS/ch09.xhtml"),
    )

    val loaded = setOf("OEBPS/ch01.xhtml", "OEBPS/ch02.xhtml")
    val rows = flattenToc(toc, loaded)

    // Depth-first, parent before children - the only order a reader expects a contents in.
    check("row count", rows.size, 6)
    check(
        "labels in order",
        rows.map { it.label },
        listOf("Start", "Chapter One", "Scene one", "Scene two", "Beat a", "Volume Two"),
    )
    check("depths", rows.map { it.depth }, listOf(0, 0, 1, 1, 2, 0))

    // Fragment split from path, so a jump can address both.
    check("document split from fragment", rows[2].document, "OEBPS/ch01.xhtml")
    check("fragment captured", rows[2].fragment, "s1")
    check("whole-file entry has no fragment", rows[0].fragment, null)

    // The file in another chapter stays visible and is marked untappable rather than dropped.
    checkTrue("other-chapter entry kept", rows.any { it.label == "Volume Two" })
    check("other-chapter entry unreachable", rows[5].reachable, false)
    checkTrue("loaded entries reachable", rows.take(5).all { it.reachable })

    // "Current" only marks an entry that points at the document on screen *without* a fragment: an
    // entry for a section of the current document is not the whole of it.
    val marked = flattenToc(toc, loaded, currentDocument = "OEBPS/ch01.xhtml")
    checkTrue("whole-document entry marked current", marked[0].current)
    // Both "Start" and "Chapter One" name the whole of the current document, so both are the
    // document on screen and both are marked - bolding one and not the other would be a lie about
    // the other.
    checkTrue("every entry naming the whole current document is marked", marked.count { it.current } == 2)
    checkTrue("section entry not marked current", marked[2].current.not())
    check("nothing marked without a current document", flattenToc(toc, loaded).none { it.current }, true)

    // A nav document with an empty <a> is malformed but not rare; a blank row is worse than one
    // labelled from its destination, and tapping it still works.
    val blank = flattenToc(listOf(TocEntry("", "OEBPS/ch01.xhtml")), loaded)
    check("blank label falls back to the path", blank[0].label, "OEBPS/ch01.xhtml")

    // Empty TOC, and a TOC that is nothing but unreachable entries: both must be empty lists, not
    // crashes and not a phantom row.
    check("empty toc", flattenToc(emptyList(), loaded).size, 0)
    check("nothing loaded", flattenToc(toc, emptySet()).none { it.reachable }, true)

    // The two "nothing to show" states are genuinely different and the sheet now words them
    // differently, so they have to be distinguishable here too: an empty book-wide contents versus a
    // real one that happens to list nothing from the chapter on screen.
    check("empty toc reports no rows", flattenToc(emptyList(), loaded).isEmpty(), true)
    check(
        "unreachable-only toc still carries rows",
        flattenToc(toc, emptySet()).isNotEmpty(),
        true,
    )
    // `current` and `reachable` answer different questions and are computed independently: `current`
    // is "does this entry name the document on screen", `reachable` is "can tapping it get there".
    // A row can be current and unreachable at once — the reader is in it, but the chapter loaded
    // nothing, so there is no page to turn to. Marking such a row is right; the sheet's own
    // `hasReachable` check is what decides whether there is anything to show.
    val unreachableButCurrent = flattenToc(toc, emptySet(), currentDocument = "OEBPS/ch01.xhtml")
    checkTrue(
        "current is marked independently of reachable",
        unreachableButCurrent.count { it.current } == 2,
    )
    checkTrue("and those rows are still unreachable", unreachableButCurrent.none { it.reachable })

    // A current document that is not in the loaded set at all: the reader is between documents, or
    // the chapter turned over underneath an open sheet. Nothing may be marked rather than a guess.
    val missingCurrent = flattenToc(toc, loaded, currentDocument = "OEBPS/ch99.xhtml")
    check("unknown current document marks nothing", missingCurrent.none { it.current }, true)
    checkTrue("unknown current document still lists rows", missingCurrent.isNotEmpty())

    // ---------------------------------------------------------------- search results
    // documentOffset is in the source's plain text; documentLength is the same unit. Dividing one by
    // the other is the only conversion that does not mix markup length with plain-text offset.
    val hits = listOf(
        TextSearchHit(pageIndex = 0, snippet = "the quick brown", matchOffset = 4, documentOffset = 500, documentLength = 1000),
        TextSearchHit(pageIndex = 1, snippet = "again", matchOffset = 0, documentOffset = 50, documentLength = 200),
        // An extension predating documentOffset: kept, and lands at the top of its document.
        TextSearchHit(pageIndex = 2, snippet = "old", matchOffset = 0),
    )
    val results = toSearchResults(hits)
    check("all hits kept", results.size, 3)
    check("half way", results[0].fraction, 0.5f)
    check("quarter way", results[1].fraction, 0.25f)
    check("zero length is the top, not a division by zero", results[2].fraction, 0f)
    check("document index carried", results[1].pageIndex, 1)

    // Out-of-range values from a hand-edited or migrated hit must not produce a position off the page.
    checkTrue("fraction clamped high", toSearchResults(
        listOf(TextSearchHit(0, "x", 0, documentOffset = 5000, documentLength = 1000)),
    ).single().fraction <= 1f)
    checkTrue("fraction clamped low", toSearchResults(
        listOf(TextSearchHit(0, "x", 0, documentOffset = -50, documentLength = 1000)),
    ).single().fraction >= 0f)
    // matchOffset is a highlight position inside the snippet; past its end would draw outside it.
    checkTrue(
        "match offset clamped to the snippet",
        toSearchResults(listOf(TextSearchHit(0, "short", 99))).single().matchOffset <= 5,
    )

    check("no hits is an empty result list", toSearchResults(emptyList()).size, 0)

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}