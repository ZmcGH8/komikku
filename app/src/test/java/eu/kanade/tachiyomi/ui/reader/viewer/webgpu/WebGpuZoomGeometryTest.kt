package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The zoom geometry the viewer computes before it has a decoded page to measure.
 *
 * None of this needs a GPU, a surface or a viewer: each is the arithmetic that decides how big and
 * where a page sits, and all of it used to be inline in a function that read `pager.state` and
 * caught its own exceptions - so a mistake produced a page half a screen out rather than a crash,
 * and nothing could assert it. Wide-page zoom in particular had a shipped bug where the option did
 * nothing at all, which is exactly the kind of report that has to be pinned by a test instead.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuZoomGeometryTest {

    // ---------- zoom start side ----------

    @Test
    fun `auto zoom start follows the reading direction`() {
        // 1 is "auto", which is the only value that reads the viewer at all. Every other value names
        // a side outright and must not be flipped by reading direction.
        resolveZoomStartPosition(1, isReversed = false) shouldBe ZoomStartPosition.LEFT
        resolveZoomStartPosition(1, isReversed = true) shouldBe ZoomStartPosition.RIGHT
    }

    @Test
    fun `an explicit zoom start side ignores the reading direction`() {
        resolveZoomStartPosition(2, isReversed = false) shouldBe ZoomStartPosition.LEFT
        resolveZoomStartPosition(2, isReversed = true) shouldBe ZoomStartPosition.LEFT
        resolveZoomStartPosition(3, isReversed = false) shouldBe ZoomStartPosition.RIGHT
        resolveZoomStartPosition(3, isReversed = true) shouldBe ZoomStartPosition.RIGHT
    }

    @Test
    fun `centre and any unknown value fall through to centre`() {
        resolveZoomStartPosition(4, isReversed = false) shouldBe ZoomStartPosition.CENTER
        resolveZoomStartPosition(0, isReversed = false) shouldBe ZoomStartPosition.CENTER
        resolveZoomStartPosition(-1, isReversed = true) shouldBe ZoomStartPosition.CENTER
    }

    // ---------- fit mode scale ----------

    @Test
    fun `fit width divides the content width by the trimmed width`() {
        // Trimmed, not raw: a page with a black border should fill the screen, not overflow it.
        resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, 800f, 1200f) shouldBe 1.35f
    }

    @Test
    fun `fit height divides the content height by the trimmed height`() {
        resolveFitModeScale(FIT_SCALE_HEIGHT, 1080f, 2400f, 800f, 1200f) shouldBe 2f
    }

    @Test
    fun `original size is one to one`() {
        resolveFitModeScale(FIT_SCALE_ORIGINAL, 1080f, 2400f, 800f, 1200f) shouldBe 1f
    }

    @Test
    fun `a scale type fit mode does not handle is declined`() {
        // 1 and 2 are the library's own fit modes; touching them here would override it.
        resolveFitModeScale(1, 1080f, 2400f, 800f, 1200f).shouldBeNull()
        resolveFitModeScale(2, 1080f, 2400f, 800f, 1200f).shouldBeNull()
        resolveFitModeScale(0, 1080f, 2400f, 800f, 1200f).shouldBeNull()
        resolveFitModeScale(6, 1080f, 2400f, 800f, 1200f).shouldBeNull()
    }

    @Test
    fun `a zero or negative trim is declined rather than divided by`() {
        resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, 0f, 1200f).shouldBeNull()
        resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, 800f, 0f).shouldBeNull()
        resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, -1f, 1200f).shouldBeNull()
    }

    @Test
    fun `a degenerate content box clamps to the floor instead of going negative`() {
        // A cutout taller than the screen makes the content height negative. That has always landed
        // on the floor rather than flipping the page, so the behaviour is pinned rather than fixed.
        resolveFitModeScale(FIT_SCALE_HEIGHT, 1080f, -50f, 800f, 1200f) shouldBe MIN_HOME_SCALE
        resolveFitModeScale(FIT_SCALE_WIDTH, -1f, 2400f, 800f, 1200f) shouldBe MIN_HOME_SCALE
    }

    @Test
    fun `a vanishing trim is clamped up to the floor rather than to zero`() {
        // Wide enough to survive the floor rather than reach it, so the assertion proves the ratio
        // was computed rather than short-circuited.
        resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, 100_000f, 1200f) shouldBe 0.0108f
        // Narrower still, and the floor is what answers.
        val floor = resolveFitModeScale(FIT_SCALE_WIDTH, 1080f, 2400f, 200_000f, 1200f)!!
        floor shouldBe MIN_HOME_SCALE
        floor.isFinite() shouldBe true
    }

    // ---------- original-size minimum ----------

    @Test
    fun `the original-size floor can be width limited`() {
        // A short, wide page on a tall screen: 1080/400 = 2.7 against 2400/300 = 8, so width is
        // the binding constraint and the floor is 2.7 - the page cannot grow past the screen's
        // width without overflowing it.
        resolveOriginalSizeMinScale(1080f, 2400f, 400, 300) shouldBe 2.7f
    }

    @Test
    fun `the original-size floor can be height limited`() {
        // The other way round: 1080/100 = 10.8 against 2400/3000 = 0.8, so height binds. The
        // smaller of the two is always the answer - a floor above either one would let the page
        // overflow along that axis.
        resolveOriginalSizeMinScale(1080f, 2400f, 100, 3000) shouldBe 0.8f
    }

    @Test
    fun `a 400 by 3000 page is height limited, not width limited`() {
        // Pinned because it is the case that reads as width-limited at a glance: 1080/400 is 2.7,
        // but 2400/3000 is 0.8, and minOf takes the smaller.
        resolveOriginalSizeMinScale(1080f, 2400f, 400, 3000) shouldBe 0.8f
    }

    @Test
    fun `a zero page dimension is discarded by min rather than poisoning the result`() {
        // One term becomes Infinity, which minOf drops - so the surviving term is the answer and the
        // scale stays finite. This is why there is no explicit guard.
        val scale = resolveOriginalSizeMinScale(1080f, 2400f, 0, 3000)
        scale shouldBe 0.8f
        scale.isFinite() shouldBe true
    }

    @Test
    fun `the original-size floor never drops below the home scale`() {
        resolveOriginalSizeMinScale(10f, 10f, 400, 3000) shouldBe MIN_HOME_SCALE
    }

    // ---------- wide-page zoom ----------

    @Test
    fun `a wide page is scaled so half of it fills the screen`() {
        // 3000 wide at half is 1500, which the 1200-wide screen scales up by 0.8.
        resolveWideZoomScale(3000, 1000, 3000, 1000, 1200, 2400) shouldBe 0.8f
    }

    @Test
    fun `a page that already fits the screen is declined`() {
        // Not wide *and* not oversized - the common case, and the one that must fall through to
        // fit mode rather than being zoomed.
        resolveWideZoomScale(1000, 1400, 1000, 1400, 1200, 2400).shouldBeNull()
        resolveWideZoomScale(1200, 100, 1200, 100, 1200, 2400).shouldBeNull()
    }

    @Test
    fun `a page that is not wide enough is declined`() {
        // Taller than it is wide: the half-width trick would show it larger than life.
        resolveWideZoomScale(2000, 3000, 2000, 3000, 1200, 2400).shouldBeNull()
    }

    @Test
    fun `a page no wider than twice the screen aspect is declined`() {
        // Reachable only where the screen is wide enough for the threshold to exceed the 1.1 floor -
        // see the portrait case below. On a 4:3 tablet the threshold is ~2.67, so a 2:1 page is
        // declined while a 3:1 one is not.
        resolveWideZoomScale(2000, 1000, 2000, 1000, 1600, 1200).shouldBeNull()
        resolveWideZoomScale(3000, 1000, 3000, 1000, 1600, 1200)!!.isFinite() shouldBe true
    }

    @Test
    fun `on a portrait screen the twice-screen-aspect guard can never fire`() {
        // Not a bug claim - a documented dead zone. The guard needs aspect <= 2*w/h, and the guard
        // before it already demands aspect >= 1.1, so the pair is only satisfiable when the screen
        // is wider than 0.55 of its height. A portrait phone is nowhere near that, which means every
        // page past 1.1:1 gets the half-width zoom there, however marginally wide it is.
        val phoneThreshold = 2f * 1200f / 2400f
        phoneThreshold shouldBe 1f
        // A 1.2:1 page - barely wide - is still zoomed on a phone.
        resolveWideZoomScale(2400, 2000, 2400, 2000, 1200, 2400)!!.isFinite() shouldBe true
        // The same page on the tablet that would decline it.
        resolveWideZoomScale(2400, 2000, 2400, 2000, 1600, 1200).shouldBeNull()
    }

    @Test
    fun `cropping cannot promote a narrow page to wide`() {
        // The trimmed ratio says wide (4000/500) but the image itself is 8:1 tall - so the smaller
        // of the two wins and the page is declined.
        resolveWideZoomScale(4000, 500, 1000, 8000, 1200, 2400).shouldBeNull()
    }

    @Test
    fun `a degenerate dimension is declined instead of dividing`() {
        resolveWideZoomScale(3000, 0, 3000, 1000, 1200, 2400).shouldBeNull()
        resolveWideZoomScale(3000, 1000, 3000, 0, 1200, 2400).shouldBeNull()
        resolveWideZoomScale(0, 1000, 3000, 1000, 1200, 2400).shouldBeNull()
    }

    @Test
    fun `a screen with no size declines rather than scaling by zero`() {
        resolveWideZoomScale(3000, 1000, 3000, 1000, 0, 2400).shouldBeNull()
        resolveWideZoomScale(3000, 1000, 3000, 1000, 1200, 0).shouldBeNull()
    }

    @Test
    fun `whatever comes back is a usable finite scale`() {
        val scale = resolveWideZoomScale(3000, 1000, 3000, 1000, 1200, 2400)!!
        scale.isFinite() shouldBe true
        (scale > 0f) shouldBe true
    }

    // ---------- cutout push-down ----------

    @Test
    fun `a cutout pushes the page down by its own height`() {
        // (200 - 100) / (1 * 2400): the page moves down by the cutout, as a fraction of its own
        // drawn height, so it survives a rotation without being recomputed.
        val y = resolveCutoutHomeY(cutoutPx = 200f, trimTopY = 100f, homeScale = 1f, screenHeight = 2400f)
        (y * 2400f).shouldBe(100f)
    }

    @Test
    fun `the push-down scales with the page's own scale`() {
        val y = resolveCutoutHomeY(cutoutPx = 200f, trimTopY = 100f, homeScale = 2f, screenHeight = 2400f)
        // Divided by twice the scale, so the same cutout covers half as much of the page.
        y shouldBe 100f / (2f * 2400f)
    }

    @Test
    fun `a cutout shorter than the trim offset does not push the page up`() {
        // Without the clamp this is negative, which would slide the page out of the viewport.
        resolveCutoutHomeY(cutoutPx = 50f, trimTopY = 400f, homeScale = 1f, screenHeight = 2400f) shouldBe 0f
    }

    @Test
    fun `a degenerate scale or screen leaves the page where it was`() {
        resolveCutoutHomeY(200f, 100f, 0f, 2400f) shouldBe 0f
        resolveCutoutHomeY(200f, 100f, 1f, 0f) shouldBe 0f
    }

    // ---------- placeholder width ----------

    @Test
    fun `a placeholder claims the whole viewport`() {
        resolveViewportPageWidth(1080, half = false) shouldBe 1080
    }

    @Test
    fun `a spread side claims half the viewport`() {
        resolveViewportPageWidth(1080, half = true) shouldBe 540
    }

    @Test
    fun `an unsized surface still claims the minimum width`() {
        // These pages are built before the surface has a size, so zero is the normal first value -
        // and a zero-width page divides by zero inside the image page's own layout maths.
        resolveViewportPageWidth(0, half = false) shouldBe MIN_PAGE_WIDTH
        resolveViewportPageWidth(0, half = true) shouldBe MIN_PAGE_WIDTH
    }

    @Test
    fun `halving a narrow viewport does not fall below the minimum`() {
        resolveViewportPageWidth(10, half = true) shouldBe MIN_PAGE_WIDTH
        resolveViewportPageWidth(16, half = true) shouldBe MIN_PAGE_WIDTH
    }
}
