package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The keep-or-evict half of the page cache.
 *
 * Distance is computed from chapter/index math rather than by walking the cache, which makes it
 * gap-proof but also means the *reference* matters: when a page's chapter no longer lists it - which
 * is exactly what a split does to the page it replaces - it has no position to measure from. These
 * tests pin how that case is classified.
 */
@Execution(ExecutionMode.CONCURRENT)
class PageCacheEvictionWindowTest {

    @Test
    fun `the anchor and its immediate neighbours stay inside`() {
        val ahead = 4
        val behind = 2
        isInsidePreloadWindow(0, ahead, behind) shouldBe true
        isInsidePreloadWindow(1, ahead, behind) shouldBe true
        isInsidePreloadWindow(-1, ahead, behind) shouldBe true
    }

    @Test
    fun `one page of slack each way covers a spread partner and a transition page`() {
        val ahead = 4
        val behind = 2
        isInsidePreloadWindow(ahead + 1, ahead, behind) shouldBe true
        isInsidePreloadWindow(-(behind + 1), ahead, behind) shouldBe true
        isInsidePreloadWindow(ahead + 2, ahead, behind) shouldBe false
        isInsidePreloadWindow(-(behind + 2), ahead, behind) shouldBe false
    }

    @Test
    fun `past the window is a victim`() {
        val ahead = 3
        val behind = 3
        isInsidePreloadWindow(5, ahead, behind) shouldBe false
        isInsidePreloadWindow(-5, ahead, behind) shouldBe false
    }

    @Test
    fun `a page with no chapter relation is never inside`() {
        // This is the case that matters after a split: once the reference page is no longer in its
        // chapter's list it has no position, so every same-chapter distance from it is meaningless.
        // Reporting null here sorts those pages to the far end, so the ones a split just invalidated
        // are the first shed - rather than being measured against a -1 and silently kept.
        isInsidePreloadWindow(null, ahead = 6, behind = 6) shouldBe false
    }

    @Test
    fun `a zero preload window still keeps the anchor and one slack slot each side`() {
        // The slack is unconditional: it is what protects a spread partner or a transition page,
        // which occupies the neighbouring slot whatever the reach is asked to be. A reach of zero
        // therefore still keeps +-1, and only distance 2 is a victim.
        isInsidePreloadWindow(0, ahead = 0, behind = 0) shouldBe true
        isInsidePreloadWindow(1, ahead = 0, behind = 0) shouldBe true
        isInsidePreloadWindow(-1, ahead = 0, behind = 0) shouldBe true
        isInsidePreloadWindow(2, ahead = 0, behind = 0) shouldBe false
        isInsidePreloadWindow(-2, ahead = 0, behind = 0) shouldBe false
    }

    @Test
    fun `a negative window does not invert the comparison`() {
        // The reach is clamped upstream, so this only has to not throw or produce nonsense.
        isInsidePreloadWindow(1, ahead = -2, behind = -2) shouldBe false
        isInsidePreloadWindow(0, ahead = -2, behind = -2) shouldBe true
    }
}
