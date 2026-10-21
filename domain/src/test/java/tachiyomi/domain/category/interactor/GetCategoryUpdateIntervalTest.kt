package tachiyomi.domain.category.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class GetCategoryUpdateIntervalTest {

    private fun days(categories: Set<Long>, intervals: Map<Long, Int>) =
        GetCategoryUpdateInterval.intervalDaysFor(intervals, categories)

    @Test
    fun `no categories means nothing narrows the schedule`() {
        days(emptySet(), mapOf(1L to 7)) shouldBe null
    }

    @Test
    fun `a category without an interval leaves the schedule alone`() {
        // The entry is in category 1, which carries no interval. The global setting governs, so
        // this must report null rather than some fallback value.
        days(setOf(1L), mapOf(2L to 7)) shouldBe null
    }

    @Test
    fun `an empty interval map leaves the schedule alone`() {
        days(setOf(1L, 2L), emptyMap()) shouldBe null
    }

    @Test
    fun `the bound interval applies`() {
        days(setOf(1L), mapOf(1L to 14)) shouldBe 14
    }

    @Test
    fun `the shortest interval wins across categories`() {
        // Filed under a monthly one as well: the daily request must not be overridden.
        days(setOf(1L, 2L), mapOf(1L to 1, 2L to 30)) shouldBe 1
        days(setOf(1L, 2L), mapOf(1L to 30, 2L to 7)) shouldBe 7
    }

    @Test
    fun `unrelated categories do not contribute their interval`() {
        days(setOf(1L), mapOf(1L to 2, 9L to 30)) shouldBe 2
    }

    @Test
    fun `a category the entry is not in is ignored`() {
        days(setOf(1L), mapOf(1L to 7, 5L to 1)) shouldBe 7
    }
}
