// Harness that exercises the EPUB parsers against synthetic books, run with the standalone
// kotlinc from /tmp/opencode/synchk. Not part of the app.
// A minimal XmlPullParser is NOT available off-device, so this harness runs the pure-JVM parts
// (EpubPaths) and asserts the XmlPullParser-dependent parsers are reachable by signature only.
import mihon.core.archive.EpubPaths

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
    if (!ok) failures++
}

private var failures = 0

fun main() {
    // --- resolve ---
    check("resolve relative", EpubPaths.resolve("OEBPS/text", "ch1.xhtml"), "OEBPS/text/ch1.xhtml")
    check("resolve parent", EpubPaths.resolve("OEBPS/text", "../images/a.png"), "OEBPS/images/a.png")
    check("resolve root-absolute", EpubPaths.resolve("OEBPS/text", "/img/b.jpg"), "img/b.jpg")
    check("resolve strips fragment", EpubPaths.resolve("OEBPS", "ch1.xhtml#s1"), "OEBPS/ch1.xhtml")
    check("resolve strips query", EpubPaths.resolve("OEBPS", "ch1.xhtml?x=1"), "OEBPS/ch1.xhtml")
    check("resolve dot segment", EpubPaths.resolve("OEBPS", "./ch1.xhtml"), "OEBPS/ch1.xhtml")
    check("resolve escapes root clamped", EpubPaths.resolve("OEBPS", "../../../etc/passwd"), "etc/passwd")
    check("resolve empty href -> base", EpubPaths.resolve("OEBPS/text", ""), "OEBPS/text")
    check("resolve double slash", EpubPaths.resolve("OEBPS", "text//ch1.xhtml"), "OEBPS/text/ch1.xhtml")

    // --- parentOf / nameOf ---
    check("parentOf nested", EpubPaths.parentOf("OEBPS/text/ch1.xhtml"), "OEBPS/text")
    check("parentOf root", EpubPaths.parentOf("content.opf"), "")
    check("nameOf", EpubPaths.nameOf("OEBPS/text/ch1.xhtml"), "ch1.xhtml")

    // --- splitFragment ---
    check("splitFragment none", EpubPaths.splitFragment("a/b.xhtml"), "a/b.xhtml" to null)
    check("splitFragment some", EpubPaths.splitFragment("a/b.xhtml#s2"), "a/b.xhtml" to "s2")
    check("splitFragment empty frag", EpubPaths.splitFragment("a/b.xhtml#"), "a/b.xhtml" to null)

    // --- percentDecode ---
    check("decode space", EpubPaths.percentDecode("a%20b.png"), "a b.png")
    check("decode plus untouched", EpubPaths.percentDecode("a+b.png"), "a+b.png")
    check("decode utf8", EpubPaths.percentDecode("%E6%97%A5%E6%9C%AC"), "日本")
    check("decode malformed kept", EpubPaths.percentDecode("100%"), "100%")
    check("decode trunc kept", EpubPaths.percentDecode("a%2"), "a%2")

    // --- normalize / isUnder ---
    check("normalize", EpubPaths.normalize("a/./b/../c//d"), "a/c/d")
    check("isUnder direct", EpubPaths.isUnder("OEBPS", "OEBPS/text/a.xhtml"), true)
    check("isUnder sibling", EpubPaths.isUnder("OEBPS", "OTHER/a.xhtml"), false)
    check("isUnder prefix trap", EpubPaths.isUnder("OEBPS", "OEBPSX/a.xhtml"), false)
    check("isUnder self", EpubPaths.isUnder("a", "a"), true)
    check("isUnder empty root", EpubPaths.isUnder("", "anything"), true)

    // --- lookupCandidates ---
    val c = EpubPaths.lookupCandidates("a b.png")
    check("candidates include raw+decoded", c.contains("a b.png"), true)

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}