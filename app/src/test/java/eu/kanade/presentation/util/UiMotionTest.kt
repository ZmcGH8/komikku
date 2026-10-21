package eu.kanade.presentation.util

import androidx.compose.animation.core.CubicBezierEasing
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class UiMotionTest {

    @Test
    fun `emphasized easing matches material 3 control points`() {
        // Compared against a reference instance rather than reading a/b/c/d: those
        // are private on CubicBezierEasing, and equality is what actually matters here.
        UiMotion.EMPHASIZED shouldBe CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    @Test
    fun `emphasized decelerate and accelerate match material 3 control points`() {
        UiMotion.EMPHASIZED_DECELERATE shouldBe CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        UiMotion.EMPHASIZED_ACCELERATE shouldBe CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }

    @Test
    fun `durations are positive and enter outlasts exit`() {
        (UiMotion.SCREEN_ENTER > 0) shouldBe true
        (UiMotion.SCREEN_EXIT > 0) shouldBe true
        (UiMotion.TAB_DURATION > 0) shouldBe true
        (UiMotion.SCREEN_ENTER > UiMotion.SCREEN_EXIT) shouldBe true
    }
}
