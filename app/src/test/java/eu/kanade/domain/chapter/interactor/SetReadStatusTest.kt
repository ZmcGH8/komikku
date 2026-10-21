package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.manga.interactor.CompleteRereadIfNeeded
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * What "mark as unread" has to leave behind, and what it must not.
 *
 * Every entry point funnels through here - the chapter list's button and swipe, the library's bulk
 * action, the Updates tab and the start of a reread - so this is the only place that decides whether
 * a chapter keeps the page it was stopped on.
 */
@Execution(ExecutionMode.CONCURRENT)
class SetReadStatusTest {

    // A real store, not a relaxed mock: `removeAfterMarkedAsRead()` hands back a `Preference<Boolean>`,
    // and a relaxed mock returns a generic placeholder for it that cannot be cast to Boolean. Real
    // defaults also put "delete downloads after marking read" off, which is not what these cover.
    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    private val deleteDownload: DeleteDownload = mockk(relaxed = true)
    private val mangaRepository: MangaRepository = mockk(relaxed = true)
    private val chapterRepository: ChapterRepository = mockk()
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = mockk(relaxed = true)
    private val completeRereadIfNeeded: CompleteRereadIfNeeded = mockk(relaxed = true)

    private val interactor = SetReadStatus(
        downloadPreferences,
        deleteDownload,
        mangaRepository,
        chapterRepository,
        getMergedChaptersByMangaId,
        completeRereadIfNeeded,
    )

    private fun chapter(id: Long, read: Boolean = false, lastPageRead: Long = 0) = Chapter.create().copy(
        id = id,
        mangaId = 10L,
        read = read,
        lastPageRead = lastPageRead,
    )

    private fun givenChapters(vararg chapters: Chapter) {
        coEvery { chapterRepository.getChapterByMangaId(10L) } returns chapters.toList()
    }

    @BeforeEach
    fun stubRepositoryWrites() {
        // A strict mock, so the write under test needs an answer of its own. `coVerify` below
        // still sees exactly which updates were made - stubbing is not a call.
        coEvery { chapterRepository.updateAll(any()) } returns Unit
    }

    @Test
    fun `marking unread wipes the saved page position`() = runTest {
        givenChapters(chapter(1L, read = true, lastPageRead = 12))

        interactor.await(read = false, chapters = arrayOf(chapter(1L, read = true, lastPageRead = 12))) shouldBe
            SetReadStatus.Result.Success

        coVerify(exactly = 1) {
            chapterRepository.updateAll(listOf(ChapterUpdate(id = 1L, read = false, lastPageRead = 0)))
        }
    }

    @Test
    fun `marking unread also catches a chapter that is unread but part-way through`() = runTest {
        // The reader saves the page on every page change, so an unread chapter part-way through is
        // the normal case, not the exotic one - and it is the one a naive `read` check would skip.
        val started = chapter(2L, read = false, lastPageRead = 7)
        givenChapters(started)

        interactor.await(read = false, chapters = arrayOf(started)) shouldBe SetReadStatus.Result.Success

        coVerify(exactly = 1) {
            chapterRepository.updateAll(listOf(ChapterUpdate(id = 2L, read = false, lastPageRead = 0)))
        }
    }

    @Test
    fun `marking unread leaves an untouched chapter alone`() = runTest {
        val untouched = chapter(3L, read = false, lastPageRead = 0)
        givenChapters(untouched)

        interactor.await(read = false, chapters = arrayOf(untouched)) shouldBe SetReadStatus.Result.NoChapters

        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `marking read keeps the saved page position`() = runTest {
        // The other half of the contract: the reader resumes from the saved page, so a chapter
        // marked read must not lose its position to the change above.
        val read = chapter(4L, read = false, lastPageRead = 9)
        givenChapters(read)

        interactor.await(read = true, chapters = arrayOf(read)) shouldBe SetReadStatus.Result.Success

        coVerify(exactly = 1) {
            chapterRepository.updateAll(listOf(ChapterUpdate(id = 4L, read = true, lastPageRead = null)))
        }
    }

    @Test
    fun `marking a manga unread wipes every chapter it has touched`() = runTest {
        givenChapters(
            chapter(5L, read = true, lastPageRead = 3),
            chapter(6L, read = true, lastPageRead = 0),
            chapter(7L, read = false, lastPageRead = 0),
        )

        interactor.await(mangaId = 10L, read = false) shouldBe SetReadStatus.Result.Success

        coVerify(exactly = 1) {
            chapterRepository.updateAll(
                listOf(
                    ChapterUpdate(id = 5L, read = false, lastPageRead = 0),
                    ChapterUpdate(id = 6L, read = false, lastPageRead = 0),
                ),
            )
        }
    }
}
