package eu.kanade.tachiyomi.ui.reader.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.IdentityHashMap

/**
 * The two identity maps a chapter's page list is indexed by, and the rules that make them correct.
 *
 * A split replaces one page with its segments, so the list the viewers walk is not the list the
 * chapter was built with. These tests pin the two answers that depend on the new shape: where a page
 * sits, and which page took the place of one a split removed.
 */
@Execution(ExecutionMode.CONCURRENT)
class ReaderChapterPageIndexTest {

    private fun page(index: Int) = ReaderPage(index)

    private fun segment(source: ReaderPage, number: Int) = ReaderPage(segmentIndex(source, number)).apply {
        splitSegment = true
        splitSourcePage = source
    }

    private fun index(pages: List<ReaderPage>): Pair<IdentityHashMap<ReaderPage, Int>, IdentityHashMap<ReaderPage, ReaderPage>> {
        val positions = IdentityHashMap<ReaderPage, Int>(pageIndexCapacity(pages.size))
        val replacements = IdentityHashMap<ReaderPage, ReaderPage>(pageIndexCapacity(pages.size))
        buildPageIndexes(pages, positions, replacements)
        return positions to replacements
    }

    @Test
    fun `positions follow the list, not Page index`() {
        val pages = listOf(page(0), page(1), page(2))
        val (positions, _) = index(pages)
        positions[pages[0]] shouldBe 0
        positions[pages[1]] shouldBe 1
        positions[pages[2]] shouldBe 2
    }

    @Test
    fun `an unsplit page has no replacement`() {
        val plain = page(1)
        val (_, replacements) = index(listOf(page(0), plain))
        replacements[plain].shouldBeNull()
    }

    @Test
    fun `a split parent's replacement is its FIRST segment`() {
        val parent = page(2)
        val first = segment(parent, 0)
        val second = segment(parent, 1)
        // The split inserts the run where the parent used to sit.
        val pages = listOf(page(0), page(1), first, second, page(3))
        val (_, replacements) = index(pages)
        // Not the last one: the reader lands on the top of the strip, not its last slice.
        replacements[parent] shouldBe first
    }

    @Test
    fun `two parents sharing one image keep separate segments`() {
        // Same url and same imageUrl is not the same page - the segments record identity.
        val a = page(1).apply {
            url = "same"
            imageUrl = "same"
        }
        val b = page(4).apply {
            url = "same"
            imageUrl = "same"
        }
        val aSeg = segment(a, 0)
        val bSeg = segment(b, 0)
        val (positions, replacements) = index(listOf(page(0), a, aSeg, b, bSeg))
        replacements[a] shouldBe aSeg
        replacements[b] shouldBe bSeg
        positions[aSeg] shouldBe 2
        positions[bSeg] shouldBe 4
    }

    @Test
    fun `a page removed by a split is absent from positions`() {
        val parent = page(2)
        val pages = listOf(page(0), page(1), segment(parent, 0))
        val (positions, _) = index(pages)
        // -1 from the index, which is what every "one before the first / one after the last" test in
        // the viewers reads as a chapter edge - the reason the neighbour links have to notice it.
        positions[parent].shouldBeNull()
    }

    @Test
    fun `capacity is always positive so an empty list cannot fail the map constructor`() {
        pageIndexCapacity(0) shouldBe 2
        pageIndexCapacity(-5) shouldBe 2
        pageIndexCapacity(1) shouldBe 2
        pageIndexCapacity(100) shouldBe 200
    }

    @Test
    fun `segment indices stay inside the reserved range and do not collide`() {
        val parent = page(7)
        val first = segmentIndex(parent, 0).toLong()
        val last = segmentIndex(parent, MAX_SEGMENTS).toLong()
        val base = SEGMENT_INDEX_BASE + 7 * SEGMENT_INDEX_STRIDE
        first shouldBe base
        last shouldBe base + MAX_SEGMENTS
        // No real page index can reach the segment range, so a page cache keyed on
        // (chapterId, index) can never hand back the wrong page for one of these.
        (first >= SEGMENT_INDEX_BASE.toLong()) shouldBe true
        // The stride has to exceed the segments one page can produce, or two parents' ranges meet.
        (SEGMENT_INDEX_STRIDE > MAX_SEGMENTS) shouldBe true
        // Two parents a stride apart do not overlap.
        (segmentIndex(page(8), 0).toLong() > last) shouldBe true
    }

    @Test
    fun `parentIndexOfSegment is the exact inverse`() {
        val parent = page(42)
        for (number in listOf(0, 1, 7, MAX_SEGMENTS)) {
            parentIndexOfSegment(segmentIndex(parent, number)) shouldBe 42
        }
        // A real page index is not a segment index.
        parentIndexOfSegment(42).shouldBeNull()
        parentIndexOfSegment(0).shouldBeNull()
    }

    private companion object {
        const val MAX_SEGMENTS = (SEGMENT_INDEX_STRIDE - 1).toInt()
    }
}
