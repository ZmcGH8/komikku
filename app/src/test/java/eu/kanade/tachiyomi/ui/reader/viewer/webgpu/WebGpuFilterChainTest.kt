package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Which GPU filter passes the reader runs at all.
 *
 * The chain is rebuilt on every settings change and attached in a fixed order, so a pass that is
 * attached but does nothing still costs a full-screen pass per frame - and it costs it *ahead* of the
 * passes that do work. These three decisions are therefore worth pinning: the e-ink contrast floor
 * (which can turn a pass on by itself), and the two "is there anything to do" gates.
 *
 * All pure. The filters themselves belong to the render library and need a device, but whether one
 * should be attached never does.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuFilterChainTest {

    // ---------- e-ink contrast floor ----------

    @Test
    fun `contrast is passed through untouched without the e-ink preset`() {
        effectiveContrast(1f, einkPreset = false) shouldBe 1f
        effectiveContrast(0.6f, einkPreset = false) shouldBe 0.6f
        effectiveContrast(2f, einkPreset = false) shouldBe 2f
    }

    @Test
    fun `the e-ink preset lifts a flat contrast to the floor`() {
        // The whole point of the preset: at 1.0 on e-ink a page reads as blank outdoors.
        effectiveContrast(1f, einkPreset = true) shouldBe EINK_MIN_CONTRAST
        effectiveContrast(0.8f, einkPreset = true) shouldBe EINK_MIN_CONTRAST
    }

    @Test
    fun `the e-ink floor raises the contrast without capping it`() {
        // A reader who deliberately pushed contrast further must not have it pulled back down.
        effectiveContrast(2.5f, einkPreset = true) shouldBe 2.5f
        effectiveContrast(EINK_MIN_CONTRAST, einkPreset = true) shouldBe EINK_MIN_CONTRAST
    }

    @Test
    fun `the floor is a floor and not a fixed value`() {
        // If this ever equals the floor, the preset is overriding the reader instead of guarding
        // them - the opposite of what it is for.
        EINK_MIN_CONTRAST shouldBe 1.15f
        effectiveContrast(1.5f, einkPreset = true) shouldBe 1.5f
    }

    // ---------- brightness / contrast gate ----------

    @Test
    fun `the untouched pair runs nothing`() {
        // Identity transform: attaching it would reproduce the input exactly, per frame.
        isBrightnessContrastActive(brightness = 0f, contrast = 1f) shouldBe false
    }

    @Test
    fun `either knob on its own is enough to attach the pass`() {
        isBrightnessContrastActive(brightness = 0.2f, contrast = 1f) shouldBe true
        isBrightnessContrastActive(brightness = 0f, contrast = 1.1f) shouldBe true
        isBrightnessContrastActive(brightness = -0.2f, contrast = 1f) shouldBe true
    }

    @Test
    fun `the e-ink floor alone switches the pass on`() {
        // The reader left the knobs alone, so only the preset's floor is moving contrast - and the
        // pass has to be attached for that floor to reach the screen at all.
        val effective = effectiveContrast(1f, einkPreset = true)
        isBrightnessContrastActive(brightness = 0f, contrast = effective) shouldBe true
    }

    // ---------- LUT gate ----------

    @Test
    fun `all three lut conditions have to hold`() {
        isLutActive(hasLut = true, intensity = 0.5f, preset = "sepia") shouldBe true
    }

    @Test
    fun `a lut that never loaded is not enough`() {
        // A custom file that is missing or malformed parses to null, and the settings screen still
        // shows the preset selected - so this is the case that must not attach a pass.
        isLutActive(hasLut = false, intensity = 0.5f, preset = WEBGPU_LUT_PRESET_CUSTOM) shouldBe false
    }

    @Test
    fun `zero intensity is not enough on its own`() {
        // The intensity slider bottoms out at zero, which is the reader's way of keeping the preset
        // while turning it off.
        isLutActive(hasLut = true, intensity = 0f, preset = "sepia") shouldBe false
        isLutActive(hasLut = true, intensity = -0.1f, preset = "sepia") shouldBe false
    }

    @Test
    fun `the none sentinel is not enough on its own`() {
        isLutActive(hasLut = true, intensity = 0.5f, preset = WEBGPU_LUT_PRESET_NONE) shouldBe false
    }

    @Test
    fun `a stale lut left over from a previous preset does not attach`() {
        // resolveLutFilter clears the table when the preset goes to none, but the gate does not rely
        // on that having happened - it is asked separately so a failure there cannot show the reader
        // a filter they switched off.
        isLutActive(
            hasLut = true,
            intensity = 0.8f,
            preset = WEBGPU_LUT_PRESET_NONE,
        ) shouldBe false
    }
}
