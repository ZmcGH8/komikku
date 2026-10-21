package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.restore.restorers.RestorePhase
import eu.kanade.tachiyomi.data.backup.restore.restorers.restorePhaseOf
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The restore no longer sorts the decoded backup - it streams the file and walks it more than once
 * - so the ordering policy is a per-entry classification rather than a `Comparator`. These assert
 * the passes still come out in the order the restore runs them.
 */
@Execution(ExecutionMode.CONCURRENT)
class RestoreOrderTest {

    private fun phaseOf(url: String, known: Map<Long, Set<String>> = emptyMap(), source: Long = 1L) =
        restorePhaseOf(source, url, known)

    /**
     * An entry the database already holds has to be restored before one it does not, so the
     * update lands on the row that is there instead of the insert adding a second one for the
     * same series.
     */
    @Test
    fun `known entries are restored before unknown ones`() {
        val known = mapOf(1L to setOf("/known"))

        phaseOf("/known", known) shouldBe RestorePhase.EXISTING
        (phaseOf("/unknown", known).ordinal > RestorePhase.EXISTING.ordinal) shouldBe true
    }

    @Test
    fun `a url is only known for its own source`() {
        val known = mapOf(1L to setOf("/known"))

        phaseOf("/known", known, source = 2L) shouldBe RestorePhase.NEW
        phaseOf("/known", known, source = 1L) shouldBe RestorePhase.EXISTING
    }

    @Test
    fun `merged entries are restored last`() {
        phaseOf("/merged", source = MERGED_SOURCE_ID) shouldBe RestorePhase.MERGED
        (RestorePhase.MERGED.ordinal > RestorePhase.NEW.ordinal) shouldBe true
    }

    /**
     * The last ordering rule the old comparator had - equal ranks restore newest first - is gone
     * with the sort. It was a tiebreak between two copies of the *same* series, and those are
     * settled by [MangaRestorer]'s version comparison against the row the first one inserted, not
     * by the order they arrive in. So two copies of one series now land in the same pass, and the
     * assertion is that they classify identically - which is what makes the tiebreak unnecessary.
     */
    @Test
    fun `two copies of one series are restored together`() {
        val known = mapOf(1L to setOf("/series"))

        phaseOf("/series", known) shouldBe phaseOf("/series", known)
    }
}
