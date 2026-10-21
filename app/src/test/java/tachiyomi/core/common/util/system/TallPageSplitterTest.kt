package tachiyomi.core.common.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class TallPageSplitterTest {

    @Test
    fun `an image within the limit is not split`() {
        TallPageSplitter.segmentBounds(8000, 8192) shouldBe listOf(0 until 8000)
    }

    @Test
    fun `an image exactly at the limit is not split`() {
        TallPageSplitter.segmentBounds(8192, 8192) shouldBe listOf(0 until 8192)
    }

    @Test
    fun `a 32k strip becomes four 8k segments`() {
        // The reader decodes a 32k strip today only if it is cut; 8192 is the cap.
        TallPageSplitter.segmentBounds(32000, 8192) shouldBe listOf(
            0 until 8000,
            8000 until 16000,
            16000 until 24000,
            24000 until 32000,
        )
    }

    @Test
    fun `segments are cut until each fits, halving at every step`() {
        TallPageSplitter.segmentBounds(20000, 8192) shouldBe listOf(
            0 until 5000,
            5000 until 10000,
            10000 until 15000,
            15000 until 20000,
        )
    }

    @Test
    fun `a barely oversized strip is cut in two`() {
        TallPageSplitter.segmentBounds(9000, 8192) shouldBe listOf(
            0 until 4500,
            4500 until 9000,
        )
    }

    @Test
    fun `segments tile the image with no gap and no overlap`() {
        val height = 47_231
        val segments = TallPageSplitter.segmentBounds(height, 8192)

        segments.first().first shouldBe 0
        segments.last().last + 1 shouldBe height
        segments.zipWithNext().forEach { (a, b) ->
            (a.last + 1) shouldBe b.first
        }
    }

    @Test
    fun `every segment fits the limit for heights that are not a clean multiple`() {
        val limit = 8192
        listOf(8193, 12_345, 32_000, 47_231, 100_001).forEach { height ->
            TallPageSplitter.segmentBounds(height, limit).forEach { range ->
                (range.count() <= limit) shouldBe true
            }
        }
    }

    @Test
    fun `a remainder is never dropped`() {
        TallPageSplitter.segmentBounds(8192 + 1, 8192).sumOf { it.count() } shouldBe 8193
    }

    @Test
    fun `the segment count is a power of two, since each cut halves`() {
        listOf(8193, 20_000, 32_000, 47_231, 100_001).forEach { height ->
            val count = TallPageSplitter.segmentBounds(height, 8192).size
            (count and (count - 1)) shouldBe 0
        }
    }

    @Test
    fun `bisecting stays within twice the smallest possible segment count`() {
        val limit = 8192
        listOf(8193, 20_000, 32_000, 47_231, 100_001).forEach { height ->
            val count = TallPageSplitter.segmentBounds(height, limit).size
            val smallest = (height + limit - 1) / limit
            (count <= smallest * 2) shouldBe true
        }
    }

    @Test
    fun `degenerate inputs produce nothing to split`() {
        TallPageSplitter.segmentBounds(0, 8192) shouldBe emptyList()
        TallPageSplitter.segmentBounds(-10, 8192) shouldBe emptyList()
        TallPageSplitter.segmentBounds(1000, 0) shouldBe emptyList()
        TallPageSplitter.segmentBounds(1000, -1) shouldBe emptyList()
    }
}
