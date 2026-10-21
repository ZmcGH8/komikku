package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class BackupMangaScanlatorTest {

    // The scanlator controls and the chapter blacklist lived only on the manga row, so a backup
    // dropped all of them and a restore handed back a series with its priority, range rules and
    // blacklist reset. The restore path already read these fields; nothing ever wrote them.
    @Test
    fun `scanlator controls and chapter blacklist survive a backup round trip`() {
        val original = BackupManga(
            source = 1L,
            url = "/manga/1",
            title = "Moriarty",
            scanlatorPriority = listOf("Scans", "Alternative"),
            blacklistedChapters = listOf("1.0@Bad", "2.0@Worse"),
            scanlatorRangeRules = listOf("1.0:5.0:Good"),
            isLightNovel = true,
        )

        val restored = original.getMangaImpl()

        restored.scanlatorPriority shouldBe listOf("Scans", "Alternative")
        restored.blacklistedChapters shouldBe listOf("1.0@Bad", "2.0@Worse")
        restored.scanlatorRangeRules shouldBe listOf("1.0:5.0:Good")
        restored.isLightNovel shouldBe true
    }

    @Test
    fun `an older backup without these fields restores as empty rather than failing`() {
        val restored = BackupManga(source = 1L, url = "/manga/1", title = "Irregulars").getMangaImpl()

        restored.scanlatorPriority shouldBe emptyList()
        restored.blacklistedChapters shouldBe emptyList()
        restored.scanlatorRangeRules shouldBe emptyList()
        restored.isLightNovel shouldBe false
    }
}
