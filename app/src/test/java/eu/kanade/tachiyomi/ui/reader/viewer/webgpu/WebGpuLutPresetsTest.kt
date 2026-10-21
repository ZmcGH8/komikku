package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Path
import kotlin.math.abs

/**
 * The colour grading tables the WebGPU reader can apply to every page.
 *
 * Two halves with opposite failure modes. A built-in preset that computes the wrong coefficient
 * is a *wrong picture* - a sepia that leans green, a warm filter that cools the skin - and nothing
 * on screen says the table is bad, so the coefficients are pinned here rather than eyeballed. The
 * custom-file half fails the other way: a path that cannot be turned into a table has to come back
 * as null so the reader carries on unfiltered, and every one of those refusals is a test.
 *
 * The built-ins deliberately overshoot and clamp rather than scale down to fit. Sepia at white is
 * the case that matters: 1.35x red would blow out, and the tests assert the clamp lands on exactly
 * 1.0 instead of wrapping or going flat.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuLutPresetsTest {

    /** The table's mapped output for one grid point, found by walking red-fastest like the builder does. */
    private fun lutEntry(preset: String, r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val lut = webgpuBuiltInLut(preset).shouldNotBeNull()
        val size = lut.size
        var i = 0
        for (bb in 0 until size) {
            for (gg in 0 until size) {
                for (rr in 0 until size) {
                    if (rr == r && gg == g && bb == b) return Triple(lut.data[i], lut.data[i + 1], lut.data[i + 2])
                    i += 3
                }
            }
        }
        error("no entry for $r,$g,$b in $preset")
    }

    private fun assertClose(expected: Float, actual: Float, clue: String, delta: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= delta, "$clue: expected about $expected but was $actual")
    }

    @Test
    fun `every built-in is a 32 cubed table of clamped floats`() {
        for (preset in PRESETS) {
            val lut = webgpuBuiltInLut(preset).shouldNotBeNull()
            lut.size shouldBe 32
            lut.data.size shouldBe 32 * 32 * 32 * 3
            assertTrue(lut.data.all { it in 0f..1f }, "$preset produced a value outside 0..1")
            // The built-ins are authored for full-range input, unlike a TV-range .3dlut.
            lut.limitedRange shouldBe false
        }
    }

    @Test
    fun `grayscale writes one value into all three channels and it rises with brightness`() {
        var previous = -1f
        for (v in 0..31) {
            val (r, g, b) = lutEntry("grayscale", v, v, v)
            g shouldBe r
            b shouldBe r
            assertTrue(r >= previous, "luma fell at $v: $r after $previous")
            previous = r
        }
        lutEntry("grayscale", 0, 0, 0).first shouldBe 0f
        assertClose(1f, lutEntry("grayscale", 31, 31, 31).first, "grayscale at white")
    }

    @Test
    fun `grayscale weights green most and blue least`() {
        // 0.7152 against 0.2126: a mid green has to land well above the same mid red.
        val (fromGreen) = lutEntry("grayscale", 0, 16, 0)
        val (fromRed) = lutEntry("grayscale", 16, 0, 0)
        val (fromBlue) = lutEntry("grayscale", 0, 0, 16)
        assertTrue(fromGreen > fromRed, "green must outweigh red")
        assertTrue(fromRed > fromBlue, "red must outweigh blue")
    }

    @Test
    fun `sepia warms the mid tones`() {
        val (r, g, b) = lutEntry("sepia", 16, 16, 16)
        assertTrue(r > g, "sepia red must lead green, got $r vs $g")
        assertTrue(g > b, "sepia green must lead blue, got $g vs $b")
    }

    @Test
    fun `sepia clamps its overshoot at white instead of blowing out`() {
        val (r, g, b) = lutEntry("sepia", 31, 31, 31)
        // 1.35x and 1.20x red and green saturate; blue at 0.94 does not.
        r shouldBe 1f
        g shouldBe 1f
        assertClose(0.937f, b, "sepia blue at white")
    }

    @Test
    fun `warm lifts red and cools blue`() {
        val (r, g, b) = lutEntry("warm", 16, 16, 16)
        assertTrue(r > g, "warm red must lead, got $r vs $g")
        assertTrue(g > b, "warm blue must trail, got $g vs $b")
    }

    @Test
    fun `cool is warm's mirror`() {
        val (r, g, b) = lutEntry("cool", 16, 16, 16)
        assertTrue(g > r, "cool green must lead, got $g vs $r")
        assertTrue(b > g, "cool blue must lead, got $b vs $g")
    }

    @Test
    fun `warm and cool saturate at white and hold the other channel exactly`() {
        val (warmR, warmG, warmB) = lutEntry("warm", 31, 31, 31)
        warmR shouldBe 1f
        warmG shouldBe 1f
        warmB shouldBe 0.92f

        val (coolR, coolG, coolB) = lutEntry("cool", 31, 31, 31)
        coolR shouldBe 0.92f
        coolG shouldBe 1f
        coolB shouldBe 1f
    }

    @Test
    fun `black stays black under every preset`() {
        for (preset in PRESETS) {
            val (r, g, b) = lutEntry(preset, 0, 0, 0)
            r shouldBe 0f
            g shouldBe 0f
            b shouldBe 0f
        }
    }

    @Test
    fun `presets are matched exactly, so a stray space or capital falls through to no filter`() {
        webgpuBuiltInLut(WEBGPU_LUT_PRESET_NONE).shouldBeNull()
        webgpuBuiltInLut("").shouldBeNull()
        webgpuBuiltInLut("Grayscale").shouldBeNull()
        webgpuBuiltInLut(" grayscale").shouldBeNull()
        webgpuBuiltInLut("grayscale ").shouldBeNull()
        webgpuBuiltInLut("unknown").shouldBeNull()
    }

    @Test
    fun `the none and custom sentinels are distinct`() {
        WEBGPU_LUT_PRESET_NONE shouldBe "none"
        WEBGPU_LUT_PRESET_CUSTOM shouldBe "custom"
        // Neither sentinel names a built-in table.
        webgpuBuiltInLut(WEBGPU_LUT_PRESET_CUSTOM).shouldBeNull()
    }

    @Test
    fun `paths that are blank, missing or a directory are refused`(@TempDir dir: Path) {
        webgpuParseCustomLut("").shouldBeNull()
        webgpuParseCustomLut("   ").shouldBeNull()
        webgpuParseCustomLut(dir.toString()).shouldBeNull()
        webgpuParseCustomLut(dir.resolve("nowhere.cube").toString()).shouldBeNull()
    }

    @Test
    fun `an empty file is refused before it is parsed`(@TempDir dir: Path) {
        val file = dir.resolve("empty.cube").toFile()
        file.writeText("")
        webgpuParseCustomLut(file.toString()).shouldBeNull()
    }

    @Test
    fun `a cube with no size is refused`(@TempDir dir: Path) {
        val file = dir.resolve("no-size.cube").toFile()
        file.writeText("TITLE \"nothing here\"\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        webgpuParseCustomLut(file.toString()).shouldBeNull()
    }

    @Test
    fun `a cube whose value count disagrees with its declared size is refused`(@TempDir dir: Path) {
        val file = dir.resolve("truncated.cube").toFile()
        file.writeText("LUT_3D_SIZE 2\n0.0 0.0 0.0\n1.0 0.0 0.0\n")
        webgpuParseCustomLut(file.toString()).shouldBeNull()
    }

    @Test
    fun `a cube file is parsed into a red-fastest identity table`(@TempDir dir: Path) {
        val file = dir.resolve("identity.cube").toFile()
        file.writeText(CUBE_2)
        val lut = webgpuParseCustomLut(file.toString()).shouldNotBeNull()
        lut.size shouldBe 2
        lut.data.size shouldBe 2 * 2 * 2 * 3
        for (b in 0 until 2) {
            for (g in 0 until 2) {
                for (r in 0 until 2) {
                    val i = ((b * 2 + g) * 2 + r) * 3
                    assertClose(r.toFloat(), lut.data[i], "red at $r,$g,$b")
                    assertClose(g.toFloat(), lut.data[i + 1], "green at $r,$g,$b")
                    assertClose(b.toFloat(), lut.data[i + 2], "blue at $r,$g,$b")
                }
            }
        }
    }

    @Test
    fun `a cube domain is rescaled into zero to one`(@TempDir dir: Path) {
        // Authored over 16..235 (studio swing) instead of 0..1.
        val file = dir.resolve("swing.cube").toFile()
        file.writeText(
            """
            LUT_3D_SIZE 2
            DOMAIN_MIN 16.0 16.0 16.0
            DOMAIN_MAX 235.0 235.0 235.0
            16.0 16.0 16.0
            235.0 16.0 16.0
            16.0 235.0 16.0
            235.0 235.0 16.0
            16.0 16.0 235.0
            235.0 16.0 235.0
            16.0 235.0 235.0
            235.0 235.0 235.0
            """.trimIndent(),
        )
        val lut = webgpuParseCustomLut(file.toString()).shouldNotBeNull()
        lut.data[0] shouldBe 0f
        lut.data[3] shouldBe 1f
        lut.data[6] shouldBe 0f
        lut.data[7] shouldBe 1f
    }

    @Test
    fun `the extension picks the parser, so cube bytes under a 3dlut name are refused`(@TempDir dir: Path) {
        val upperCube = dir.resolve("identity.CUBE").toFile()
        upperCube.writeText(CUBE_2)
        webgpuParseCustomLut(upperCube.toString()).shouldNotBeNull()

        // The same bytes under a .3dlut name go to the binary parser, which skips 16KB of header
        // and runs out of file - so the reader must fall back to no filter rather than show noise.
        val misnamed = dir.resolve("identity.3dlut").toFile()
        misnamed.writeText(CUBE_2)
        webgpuParseCustomLut(misnamed.toString()).shouldBeNull()
    }

    private companion object {
        val PRESETS = listOf("grayscale", "sepia", "warm", "cool")

        val CUBE_2 = """
            TITLE "identity"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
    }
}
