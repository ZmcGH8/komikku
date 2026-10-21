package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter

@Execution(ExecutionMode.CONCURRENT)
class ChapterVisibilityTest {

    private fun chapter(
        id: Long,
        number: Double,
        scanlator: String?,
        read: Boolean = false,
        bookmark: Boolean = false,
    ) = Chapter.create().copy(
        id = id,
        chapterNumber = number,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
    )

    private fun series(scanlator: String, vararg numbers: Double, startId: Long = 1) =
        numbers.mapIndexed { index, number -> chapter(startId + index, number, scanlator) }

    @Test
    fun `unconfigured manga merges duplicates by derived order`() {
        val chapters = listOf(
            chapter(1, 1.0, "GroupA"),
            chapter(2, 1.0, "GroupB"),
            chapter(3, 2.0, "GroupA"),
            chapter(4, 2.0, "GroupB"),
        )

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = true)

        result.map { it.id } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `merge disabled keeps every scanlator chapter`() {
        val chapters = listOf(chapter(1, 1.0, "GroupA"), chapter(2, 1.0, "GroupB"))

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = false)

        result.map { it.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `blacklist still applies when merge is disabled`() {
        val chapters = listOf(chapter(1, 5.0, "GroupA"), chapter(2, 5.0, "GroupB"))
        val blacklist = setOf(scanlatorBlacklistKey(5.0, "GroupA"))

        val result = chapters.applyScanlatorVisibility(emptyList(), blacklist, mergeEnabled = false)

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `explicit priority overrides the derived order`() {
        val chapters = listOf(chapter(1, 1.0, "GroupA"), chapter(2, 1.0, "GroupB"))

        val result = chapters.applyScanlatorVisibility(
            listOf("GroupB"),
            emptySet(),
            mergeEnabled = true,
        )

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `split coverage keeps every chapter number`() {
        val chapters = listOf(
            chapter(1, 1.0, "GroupA"),
            chapter(2, 2.0, "GroupA"),
            chapter(3, 3.0, "GroupB"),
            chapter(4, 4.0, "GroupB"),
        )

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = true)

        result.map { it.chapterNumber } shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `chapters without a number are never merged`() {
        val chapters = listOf(
            chapter(1, 0.0, "GroupA"),
            chapter(2, 0.0, "GroupB"),
            chapter(3, -1.0, "GroupA"),
        )

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = true)

        result.map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `chapters without a scanlator name are never merged`() {
        val chapters = listOf(chapter(1, 4.0, null), chapter(2, 4.0, null))

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = true)

        result.map { it.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `an unbroken run outranks a bigger but fragmented one`() {
        val complete = series("Complete", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        val gapped = series("Gapped", 1.0, 2.0, 3.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0)

        (complete + gapped).deriveScanlatorPriority() shouldBe listOf("Complete", "Gapped")
    }

    @Test
    fun `more distinct numbers breaks a tie on run length`() {
        // Both have a longest run of 2, and "Base" sorts ahead by name, so only the distinct
        // number count can put Extra first.
        val base = series("Base", 1.0, 2.0)
        val extra = series("Extra", 1.0, 2.0, 20.0, 21.0)

        (base + extra).deriveScanlatorPriority() shouldBe listOf("Extra", "Base")
    }

    @Test
    fun `equal coverage is ordered by name so the choice is stable`() {
        val chapters = listOf(chapter(1, 1.0, "Zeta"), chapter(2, 1.0, "Alpha"))

        chapters.deriveScanlatorPriority() shouldBe listOf("Alpha", "Zeta")
    }

    @Test
    fun `an extra half chapter does not fragment the whole run`() {
        val extra = series("Extra", 0.5, 1.0, 2.0, 3.0)
        val plain = series("Plain", 1.0, 2.0)

        (extra + plain).deriveScanlatorPriority() shouldBe listOf("Extra", "Plain")
    }

    @Test
    fun `half chapters alone do not outrank a whole run`() {
        val halves = series("Halves", 1.5, 2.5, 3.5)
        val whole = series("Whole", 1.0, 2.0)

        (halves + whole).deriveScanlatorPriority() shouldBe listOf("Whole", "Halves")
    }

    @Test
    fun `float noise does not fragment the run`() {
        val noisy = series("Noisy", 1.0000001, 2.0000001, 3.0000001)
        val clean = series("Clean", 1.0, 2.0)

        (noisy + clean).deriveScanlatorPriority() shouldBe listOf("Noisy", "Clean")
    }

    @Test
    fun `a scanlator with no recognised numbers ranks last`() {
        val chapters = listOf(
            chapter(1, 0.0, "Extras"),
            chapter(2, 0.0, "Extras"),
            chapter(3, 1.0, "Real"),
        )

        chapters.deriveScanlatorPriority() shouldBe listOf("Real", "Extras")
    }

    @Test
    fun `derived order prefers the scanlator covering more of the story`() {
        val complete = series("Complete", 1.0, 2.0, 3.0)
        val partial = series("Partial", 1.0, 2.0, 1.0, startId = 10)

        val result = (complete + partial).applyScanlatorVisibility(
            emptyList(),
            emptySet(),
            mergeEnabled = true,
        )

        result.map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `the opened chapter survives the blacklist without un-hiding the others`() {
        val chapters = listOf(chapter(1, 5.0, "GroupA"), chapter(2, 9.0, "GroupA"))
        val blacklist = setOf(scanlatorBlacklistKey(5.0, "GroupA"), scanlatorBlacklistKey(9.0, "GroupA"))

        val result = chapters.applyScanlatorVisibility(
            priority = emptyList(),
            blacklistedChapters = blacklist,
            mergeEnabled = false,
            alwaysVisibleChapterId = 1L,
        )

        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `the opened chapter survives a merge that picked a different upload`() {
        val chapters = listOf(chapter(1, 5.0, "GroupA"), chapter(2, 5.0, "GroupB"))

        val result = chapters.applyScanlatorVisibility(
            priority = listOf("GroupA"),
            blacklistedChapters = emptySet(),
            mergeEnabled = true,
            alwaysVisibleChapterId = 2L,
        )

        result.map { it.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a read upload wins its number over a better ranked scanlator`() {
        // libraryView counts a chapter number as read when any of its uploads is read, so the
        // merge has to show that same upload. Picking the coverage winner instead showed the
        // chapter as unread in the list while the badge had already subtracted it.
        val chapters = listOf(
            chapter(1, 5.0, "Complete", read = true),
            chapter(2, 5.0, "Frag"),
        )

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = true)

        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `a read upload wins an explicitly ranked scanlator`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA", read = true),
            chapter(2, 5.0, "GroupB"),
        )

        val result = chapters.applyScanlatorVisibility(
            listOf("GroupB"),
            emptySet(),
            mergeEnabled = true,
        )

        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `a bookmarked upload wins over an unread one of the same number`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA"),
            chapter(2, 5.0, "GroupB", bookmark = true),
        )

        val result = chapters.applyScanlatorVisibility(
            listOf("GroupA"),
            emptySet(),
            mergeEnabled = true,
        )

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a range rule still outranks read state`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA", read = true),
            chapter(2, 5.0, "GroupB"),
        )

        val result = chapters.applyScanlatorVisibility(
            priority = emptyList(),
            blacklistedChapters = emptySet(),
            rangeRules = listOf(encodeScanlatorRangeRule(ScanlatorRangeRule(1.0, 10.0, "GroupB"))),
            mergeEnabled = true,
        )

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `read state does not collapse a number when the merge is disabled`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA", read = true),
            chapter(2, 5.0, "GroupB"),
        )

        val result = chapters.applyScanlatorVisibility(emptyList(), emptySet(), mergeEnabled = false)

        result.map { it.id } shouldBe listOf(1L, 2L)
    }
}
