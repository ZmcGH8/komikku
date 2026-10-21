package tachiyomi.domain.category.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Execution(ExecutionMode.CONCURRENT)
class SyncCategoriesWithTrackStatusTest {

    private fun category(id: Long, trackStatus: Int?, parentId: Long = 0L) = Category(
        id = id,
        name = "cat$id",
        order = id,
        flags = 0,
        hidden = false,
        parentId = parentId,
        trackStatus = trackStatus,
    )

    private fun update(
        current: Set<Long>,
        bound: List<Category>,
        trackStatus: Int?,
    ): List<Long>? = SyncCategoriesWithTrackStatus.updateMembership(current.toMutableSet(), bound, trackStatus)

    // Matches the issue's example: wish to read -> reading.
    private val reading = 1
    private val planToRead = 3

    @Test
    fun `a category bound to the current status gains the entry`() {
        val result = update(
            current = setOf(10L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(10L, 2L)
    }

    @Test
    fun `a category bound to a different status loses the entry`() {
        val result = update(
            current = setOf(1L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `an unbound category is never touched`() {
        val plain = category(9, trackStatus = null)
        val result = update(
            current = setOf(9L),
            bound = listOf(plain, category(2, reading)),
            trackStatus = planToRead,
        )

        // Still present, even though its status does not match: nothing bound, nothing removed.
        result shouldContainExactlyInAnyOrder listOf(9L)
    }

    @Test
    fun `an unbound category is never added to either`() {
        val result = update(
            current = emptySet(),
            bound = listOf(category(9, null), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `losing tracking clears the entry out of every bound category`() {
        val result = update(
            current = setOf(1L, 2L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = null,
        )

        result shouldContainExactlyInAnyOrder emptyList<Long>()
    }

    @Test
    fun `two categories bound to the same status both gain the entry`() {
        val result = update(
            current = emptySet(),
            bound = listOf(category(2, reading), category(3, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L, 3L)
    }

    @Test
    fun `nothing bound means nothing to do`() {
        update(current = setOf(5L), bound = emptyList(), trackStatus = reading) shouldBe null
    }

    @Test
    fun `an entry already in the right category is left exactly as it was`() {
        val result = update(
            current = setOf(2L),
            bound = listOf(category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    // ---------------- subcategories are scoped to the branch the entry is in ----------------
    // The issue's scenario, verbatim: Shounen and Josei are siblings, each with Reading and
    // Completed bound to the same two statuses. Filing something under Shounen is the statement that
    // it is not under Josei, so Josei's subcategories are not candidates for it.

    private val shounen = 1L
    private val josei = 2L
    private val shounenReading = 11L
    private val shounenCompleted = 12L
    private val joseiReading = 21L
    private val joseiCompleted = 22L
    private val completed = 2

    private val twoBranches = listOf(
        category(shounen, trackStatus = null, parentId = 0L),
        category(shounenReading, reading, shounen),
        category(shounenCompleted, completed, shounen),
        category(josei, trackStatus = null, parentId = 0L),
        category(joseiReading, reading, josei),
        category(joseiCompleted, completed, josei),
    )

    @Test
    fun `a subcategory in another branch is not added`() {
        val result = update(current = setOf(shounen), bound = twoBranches, trackStatus = reading)

        // Shounen's Reading joins; Josei's does not, even though it is bound to the same status.
        result shouldContainExactlyInAnyOrder listOf(shounen, shounenReading)
    }

    @Test
    fun `both branches are updated when the entry is in both`() {
        val result = update(current = setOf(shounen, josei), bound = twoBranches, trackStatus = reading)

        result shouldContainExactlyInAnyOrder listOf(shounen, josei, shounenReading, joseiReading)
    }

    @Test
    fun `the entry is moved between subcategories of its own branch`() {
        val result = update(
            current = setOf(shounen, shounenReading),
            bound = twoBranches,
            trackStatus = completed,
        )

        // Reading out, Completed in - and Josei's Completed, matching the same status, untouched.
        result shouldContainExactlyInAnyOrder listOf(shounen, shounenCompleted)
    }

    @Test
    fun `an entry filed only in a subcategory still answers for that branch`() {
        // Not in Shounen itself, only in Shounen/Reading. The scope is the entry's *reachable* set,
        // so Shounen/Completed is still a candidate and Josei's are still not.
        val result = update(
            current = setOf(shounenReading),
            bound = twoBranches,
            trackStatus = completed,
        )

        result shouldContainExactlyInAnyOrder listOf(shounenCompleted)
    }

    @Test
    fun `an uncategorised entry reaches no subcategory at all`() {
        val result = update(current = emptySet(), bound = twoBranches, trackStatus = reading)

        // Nothing to scope against, so no branch claims it. A top-level binding still would.
        result shouldContainExactlyInAnyOrder emptyList<Long>()
    }

    @Test
    fun `a top-level binding is not scoped`() {
        val result = update(
            current = emptySet(),
            bound = listOf(category(1, reading), category(2, completed)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(1L)
    }

    @Test
    fun `a three-deep branch is scoped through its grandparent`() {
        val tree = listOf(
            category(1, trackStatus = null, parentId = 0L),
            category(2, trackStatus = null, parentId = 1L),
            category(3, reading, parentId = 2L),
            category(4, reading, parentId = 1L),
        )
        val result = update(current = setOf(4L), bound = tree, trackStatus = reading)

        // 4's parent is 1, so 3 - a sibling two levels down - is out of scope.
        result shouldContainExactlyInAnyOrder listOf(4L)
    }

    @Test
    fun `a parent cycle does not hang`() {
        // A tree that points at itself is not something the UI can build, but a restored backup can
        // carry one and this walks parents on every status change.
        val cyclic = listOf(
            category(1, reading, parentId = 2L),
            category(2, completed, parentId = 1L),
        )
        val result = update(current = setOf(1L), bound = cyclic, trackStatus = reading)
        result shouldContainExactlyInAnyOrder listOf(1L)
    }

    // The cases below drive await() rather than updateMembership(), because the early-return that
    // decides whether anything is written at all lives in await() and compares against the
    // membership as it was read. updateMembership() mutates that same set, so a comparison against
    // the mutated value is trivially equal and nothing would ever be written. updateMembership()
    // alone cannot catch that.

    private fun harness(
        bound: List<Category>,
        currentIds: List<Long>,
    ): Triple<CategoryRepository, SetMangaCategories, SyncCategoriesWithTrackStatus> {
        val repository: CategoryRepository = mockk()
        val setMangaCategories: SetMangaCategories = mockk()
        coEvery { repository.getAll() } returns bound
        coEvery { repository.getCategoriesByMangaId(any()) } returns currentIds.map { category(it, null) }
        coEvery { setMangaCategories.await(any(), any()) } just runs
        return Triple(repository, setMangaCategories, SyncCategoriesWithTrackStatus(repository, setMangaCategories))
    }

    @Test
    fun `await writes the membership when the status moved it`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(1, planToRead), category(2, reading)),
            currentIds = listOf(10L),
        )
        val written = slot<List<Long>>()
        coEvery { setMangaCategories.await(any(), capture(written)) } just runs

        interactor.await(mangaId = 10L, trackStatus = reading)

        // 10 is an unbound category the user chose, so it stays; 2 is bound to this status, so the
        // entry joins it.
        written.captured.toSet() shouldBe setOf(10L, 2L)
    }

    @Test
    fun `await does not write when the status did not change the membership`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(2, reading)),
            currentIds = listOf(2L),
        )

        interactor.await(mangaId = 10L, trackStatus = reading)

        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `await does not write when no category is bound to any status`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(9, null)),
            currentIds = listOf(9L),
        )

        interactor.await(mangaId = 10L, trackStatus = reading)

        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `await ignores an invalid manga id`() = runTest {
        val (repository, _, interactor) = harness(
            bound = listOf(category(2, reading)),
            currentIds = listOf(10L),
        )

        interactor.await(mangaId = 0L, trackStatus = reading)

        coVerify(exactly = 0) { repository.getAll() }
        coVerify(exactly = 0) { repository.getCategoriesByMangaId(any()) }
    }
}
