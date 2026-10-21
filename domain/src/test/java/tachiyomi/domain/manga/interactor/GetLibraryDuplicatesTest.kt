package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

/**
 * The duplicate scan asks one question per entry, and that question is symmetric — two entries that
 * duplicate each other both answer yes. These pin the grouping that keeps the review screen from
 * listing the same finding once per side.
 */
@Execution(ExecutionMode.CONCURRENT)
class GetLibraryDuplicatesTest {

    private val getLibraryManga: GetLibraryManga = mockk()
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = mockk()
    private val getDuplicates = GetLibraryDuplicates(getLibraryManga, getDuplicateLibraryManga)

    // `ogTitle`, not `title`: the display title is a computed property over the stored one and whatever
    // custom edits apply, so it is not a constructor parameter and `copy(title = …)` does not exist.
    private fun entry(id: Long) = Manga.create().copy(id = id, ogTitle = "title $id")

    private fun libraryItem(id: Long) = LibraryManga(
        manga = entry(id),
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        bookmarkReadCount = 0L,
        chapterFlags = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    /**
     * Wires the symmetric query: whoever you ask, you get the other side(s).
     *
     * `duplicatesById` is the whole truth table, so the fake answers from it rather than computing a
     * match — the symmetry under test is the interactor's responsibility to collapse, not something
     * the test double should quietly help it with.
     */
    private fun givenLibrary(duplicatesById: Map<Long, List<Long>>) {
        coEvery { getLibraryManga.await() } returns duplicatesById.keys.map(::libraryItem)
        coEvery { getDuplicateLibraryManga(any()) } answers {
            val asked = firstArg<Manga>().id
            duplicatesById[asked].orEmpty().map { MangaWithChapterCount(entry(it), 0L) }
        }
    }

    @Test
    fun `a pair is listed once, not once per side`() = runTest {
        givenLibrary(mapOf(1L to listOf(2L), 2L to listOf(1L)))

        val result = getDuplicates.await()

        result.size shouldBe 1
        result.single().manga.id shouldBe 1L
        result.single().duplicates.map { it.manga.id } shouldBe listOf(2L)
    }

    @Test
    fun `three mutually duplicated entries collapse to one group`() = runTest {
        givenLibrary(mapOf(1L to listOf(2L, 3L), 2L to listOf(1L, 3L), 3L to listOf(1L, 2L)))

        val result = getDuplicates.await()

        result.size shouldBe 1
        result.single().duplicates.map { it.manga.id }.sorted() shouldBe listOf(2L, 3L)
    }

    @Test
    fun `two separate pairs stay two separate groups`() = runTest {
        givenLibrary(
            mapOf(1L to listOf(2L), 2L to listOf(1L), 3L to listOf(4L), 4L to listOf(3L)),
        )

        val result = getDuplicates.await()

        result.map { it.manga.id } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `an entry with no duplicates is not listed`() = runTest {
        givenLibrary(mapOf(1L to listOf(2L), 2L to listOf(1L), 3L to emptyList()))

        val result = getDuplicates.await()

        result.map { it.manga.id } shouldBe listOf(1L)
    }

    @Test
    fun `a library with no duplicates reports nothing`() = runTest {
        givenLibrary(mapOf(1L to emptyList(), 2L to emptyList()))

        getDuplicates.await() shouldBe emptyList()
    }
}
