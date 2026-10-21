package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

@Execution(ExecutionMode.CONCURRENT)
class GetNextChaptersTest {

    private val getChaptersByMangaId: GetChaptersByMangaId = mockk()
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = mockk()
    private val getManga: GetManga = mockk()
    private val historyRepository: HistoryRepository = mockk()

    private val interactor = GetNextChapters(
        getChaptersByMangaId,
        getMergedChaptersByMangaId,
        getManga,
        historyRepository,
    )

    private fun chapter(
        id: Long,
        mangaId: Long,
        read: Boolean,
        number: Double,
        scanlator: String? = null,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        read = read,
        chapterNumber = number,
        scanlator = scanlator,
    )

    private fun history(mangaId: Long, chapterId: Long) = HistoryWithRelations(
        id = mangaId,
        chapterId = chapterId,
        mangaId = mangaId,
        ogTitle = "Manga $mangaId",
        chapterNumber = 1.0,
        read = true,
        lastPageRead = 5,
        totalCountCalculated = 2,
        readCountCalculated = 1,
        readAt = Date(0),
        readDuration = 0,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = 1,
            isMangaFavorite = true,
            ogUrl = null,
            lastModified = 0,
        ),
    )

    private fun givenManga(id: Long) {
        coEvery { getManga.await(id) } returns Manga.create().copy(id = id, source = 1)
    }

    private fun givenMangaWithPriority(id: Long, priority: List<String>) {
        coEvery { getManga.await(id) } returns
            Manga.create().copy(id = id, source = 1, scanlatorPriority = priority)
    }

    @Test
    fun `returns the chapter from the newest history entry`() = runTest {
        val newest = chapter(1L, 10L, read = false, number = 2.0)
        givenManga(10L)
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns
            listOf(chapter(0L, 10L, read = true, number = 1.0), newest)
        coEvery { historyRepository.getRecentHistory(25) } returns listOf(history(10L, 0L))

        interactor.awaitFirstReadable() shouldBe newest
    }

    // KMK --> The bug: a fully caught-up newest entry must not end the search.
    @Test
    fun `falls back to an older entry when the newest is fully read`() = runTest {
        val older = chapter(3L, 20L, read = false, number = 5.0)
        givenManga(10L)
        givenManga(20L)
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns
            listOf(chapter(1L, 10L, read = true, number = 1.0))
        coEvery { getChaptersByMangaId.await(20L, applyFilter = true) } returns
            listOf(chapter(2L, 20L, read = true, number = 4.0), older)
        coEvery { historyRepository.getRecentHistory(25) } returns
            listOf(history(10L, 1L), history(20L, 2L))

        interactor.awaitFirstReadable() shouldBe older
    }

    @Test
    fun `returns null only when no history entry has a chapter left`() = runTest {
        givenManga(10L)
        givenManga(20L)
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns
            listOf(chapter(1L, 10L, read = true, number = 1.0))
        coEvery { getChaptersByMangaId.await(20L, applyFilter = true) } returns
            listOf(chapter(2L, 20L, read = true, number = 4.0))
        coEvery { historyRepository.getRecentHistory(25) } returns
            listOf(history(10L, 1L), history(20L, 2L))

        interactor.awaitFirstReadable() shouldBe null
    }

    @Test
    fun `stops at the scan limit rather than reading all of history`() = runTest {
        coEvery { historyRepository.getRecentHistory(3) } returns emptyList()

        interactor.awaitFirstReadable(scanLimit = 3) shouldBe null
    }

    // KMK --> Regression: History holds one row per chapter, not per series, so a finished series
    // contributes a row per chapter. The second row down used to resolve to the final chapter
    // already read, which ended the walk and reopened the series the user had just finished
    // instead of moving to the next one with chapters left.
    @Test
    fun `skips an already-read chapter reached through an older history row of the same series`() = runTest {
        val startedIrregulars = chapter(5L, 20L, read = false, number = 5.0)
        givenManga(10L)
        givenManga(20L)
        // Moriarty: every chapter read, and History has a row for each of them, newest first.
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns listOf(
            chapter(1L, 10L, read = true, number = 1.0),
            chapter(2L, 10L, read = true, number = 1.1),
            chapter(3L, 10L, read = true, number = 1.2),
        )
        // Irregulars: next chapter started but not finished, so it is what should be resumed.
        coEvery { getChaptersByMangaId.await(20L, applyFilter = true) } returns listOf(
            chapter(4L, 20L, read = true, number = 4.0),
            startedIrregulars,
        )
        coEvery { historyRepository.getRecentHistory(25) } returns listOf(
            history(10L, 3L),
            history(10L, 2L),
            history(20L, 4L),
        )

        interactor.awaitFirstReadable() shouldBe startedIrregulars
    }

    @Test
    fun `still resumes a started chapter of the newest series`() = runTest {
        val started = chapter(2L, 10L, read = false, number = 2.0)
        givenManga(10L)
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns listOf(
            chapter(1L, 10L, read = true, number = 1.0),
            started,
        )
        coEvery { historyRepository.getRecentHistory(25) } returns listOf(history(10L, 2L))

        interactor.awaitFirstReadable() shouldBe started
    }
    // KMK <--

    // KMK --> Regression: resuming from History used to filter the blacklist and nothing else, so it
    // worked on the unmerged list. The details list shows one upload per chapter number once the
    // merge is on, and the walk then landed on a number the reader had already moved past.
    @Test
    fun `resume skips an upload the merge has collapsed`() = runTest {
        // Scanlator A holds 1-2 and is preferred, B holds 1-3. History's newest row for this series
        // points at A's chapter 1, which is read, so the walk should continue at B's chapter 3.
        val b3 = chapter(5L, 10L, read = false, number = 3.0, scanlator = "B")
        givenMangaWithPriority(10L, listOf("A"))
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns listOf(
            chapter(1L, 10L, read = true, number = 1.0, scanlator = "A"),
            chapter(2L, 10L, read = false, number = 1.0, scanlator = "B"),
            chapter(3L, 10L, read = true, number = 2.0, scanlator = "A"),
            chapter(4L, 10L, read = false, number = 2.0, scanlator = "B"),
            b3,
        )
        coEvery { historyRepository.getRecentHistory(25) } returns listOf(history(10L, 1L))

        interactor.awaitFirstReadable(mergeEnabled = true) shouldBe b3
    }

    @Test
    fun `resume follows the unmerged list when the merge is off`() = runTest {
        // The same series with the merge off: B's chapter 1 is a chapter again, and it is what comes
        // next. Both lists are what the surface asked for, so neither is a failure of its own.
        val b1 = chapter(2L, 10L, read = false, number = 1.0, scanlator = "B")
        givenMangaWithPriority(10L, listOf("A"))
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns listOf(
            chapter(1L, 10L, read = true, number = 1.0, scanlator = "A"),
            b1,
            chapter(3L, 10L, read = true, number = 2.0, scanlator = "A"),
            chapter(4L, 10L, read = false, number = 2.0, scanlator = "B"),
            chapter(5L, 10L, read = false, number = 3.0, scanlator = "B"),
        )
        coEvery { historyRepository.getRecentHistory(25) } returns listOf(history(10L, 1L))

        interactor.awaitFirstReadable(mergeEnabled = false) shouldBe b1
    }

    @Test
    fun `resume for one manga skips a hidden upload of its own`() = runTest {
        val b2 = chapter(4L, 10L, read = false, number = 2.0, scanlator = "B")
        val b3 = chapter(5L, 10L, read = false, number = 3.0, scanlator = "B")
        givenMangaWithPriority(10L, listOf("A"))
        coEvery { getChaptersByMangaId.await(10L, applyFilter = true) } returns listOf(
            chapter(1L, 10L, read = true, number = 1.0, scanlator = "A"),
            chapter(2L, 10L, read = false, number = 1.0, scanlator = "B"),
            chapter(3L, 10L, read = true, number = 2.0, scanlator = "A"),
            b2,
            b3,
        )

        // History's row for this series is A's chapter 2. Past it the merged list holds only B's
        // chapter 3, while the unmerged list holds B's chapter 2 first - the upload the details
        // screen hides, and what this used to open.
        interactor.await(10L, 3L, onlyUnread = false, mergeEnabled = true).first() shouldBe b3
        interactor.await(10L, 3L, onlyUnread = false, mergeEnabled = false).first() shouldBe b2
    }
    // KMK <--
}
