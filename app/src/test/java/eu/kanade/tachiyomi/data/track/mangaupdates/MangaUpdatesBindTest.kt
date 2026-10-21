package eu.kanade.tachiyomi.data.track.mangaupdates

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates.Companion.READING_LIST
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates.Companion.WISH_LIST
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUListItem
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURecord
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUStatus
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.copyTo
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.totalChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MangaUpdatesBindTest {

    private fun track() = Track.create(serviceId = 1L)

    @Test
    fun `S1 - copyTo maps list id to status and chapter to last chapter read`() {
        val item = MUListItem(
            listId = READING_LIST,
            status = MUStatus(chapter = 3),
        )

        val result = item.copyTo(track())

        result.status shouldBe READING_LIST
        result.last_chapter_read shouldBe 3.0
    }

    @Test
    fun `S2 - copyTo zeroes last chapter read for wish list`() {
        val item = MUListItem(
            listId = WISH_LIST,
            status = MUStatus(chapter = 5),
        )

        val result = item.copyTo(track())

        result.status shouldBe WISH_LIST
        result.last_chapter_read shouldBe 0.0
    }

    @Test
    fun `S3 - copyTo defaults to reading list when list id is missing`() {
        val item = MUListItem(status = MUStatus(chapter = 2))

        val result = item.copyTo(track())

        result.status shouldBe READING_LIST
        result.last_chapter_read shouldBe 2.0
    }

    // toTrackSearch cannot be unit tested: it calls htmlDecode, which needs a
    // real Context. Its chapter mapping is covered by S6/S7 instead.

    @Test
    fun `S6 - totalChapters maps latest chapter to long`() {
        MURecord(latestChapter = 7).totalChapters() shouldBe 7L
    }

    @Test
    fun `S7 - totalChapters falls back to zero when latest chapter is missing`() {
        MURecord(latestChapter = null).totalChapters() shouldBe 0L
    }
}
