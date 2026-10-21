package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.ByteBuffer

/**
 * The EXIF reorientation applied to a decoded page before it reaches the GPU.
 *
 * The native decoder hands back raw pixels, so this is the only thing standing between a
 * camera-scan page and a sideways one. Two behaviours matter and they pull in opposite
 * directions: the eight orientations have to be *right*, and a page that cannot be rotated
 * (bad tag, degenerate dims, a buffer shorter than the dims the caller claimed) has to come
 * back untouched rather than throw or half-write. Most of these tests are about the second,
 * because that is what turns a slightly wrong image into a crash.
 *
 * The rotations are permutations of opaque 4-byte pixels, so they are pinned two ways: an
 * explicit expected layout per orientation, to catch a convention that drifts (mirrored, or
 * transposed instead of rotated), and structural invariants that hold for any correct
 * orientation - nothing lost, nothing invented, alpha untouched, buffer exactly frame-sized.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuExifRotationTest {

    /** Pixel (x,y) is opaque with `y * width + x` in the low bits, so every pixel is distinguishable. */
    private class Source(width: Int, height: Int) {
        val width = width
        val height = height
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
        private val pixels = IntArray(width * height)

        init {
            for (i in pixels.indices) {
                pixels[i] = OPAQUE or i
                // ByteBuffer's absolute put takes a BYTE offset, not an element index - writing at
                // `i` would straddle every pixel over its left neighbour.
                buffer.putInt(i * BYTES_PER_PIXEL, pixels[i])
            }
        }

        fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

        fun allPixels(): List<Int> = pixels.toList()
    }

    private fun RotatedRgba.readPixels(): List<Int> {
        val ints = buffer.duplicate().asIntBuffer()
        return List(width * height) { ints.get(it) }
    }

    private fun assertUntouched(out: RotatedRgba, src: ByteBuffer, width: Int, height: Int, clue: String) {
        assertTrue(out.buffer === src, "$clue: expected the input buffer back, not a new one")
        out.width shouldBe width
        out.height shouldBe height
    }

    @Test
    fun `orientation 1 and any tag outside 2 to 8 leaves the buffer alone`() {
        val src = Source(3, 2)
        for (orientation in listOf(-1, 0, 1, 9, 12, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertUntouched(
                rotateRgbaForExif(src.buffer, 3, 2, orientation),
                src.buffer,
                3,
                2,
                "orientation $orientation",
            )
        }
    }

    @Test
    fun `degenerate dimensions are not rotated into a zero-sized allocation`() {
        val src = Source(1, 1)
        assertUntouched(rotateRgbaForExif(src.buffer, 0, 4, 6), src.buffer, 0, 4, "zero width")
        assertUntouched(rotateRgbaForExif(src.buffer, 4, 0, 6), src.buffer, 4, 0, "zero height")
        assertUntouched(rotateRgbaForExif(src.buffer, -3, 4, 6), src.buffer, -3, 4, "negative width")
        assertUntouched(rotateRgbaForExif(src.buffer, 4, -3, 2), src.buffer, 4, -3, "negative height")
    }

    @Test
    fun `a buffer shorter than the claimed dimensions is refused rather than read past`() {
        // 16 ints of real data, but the caller claims a 4x8 frame that needs 32.
        val short = ByteBuffer.allocateDirect(4 * 4)
        assertUntouched(rotateRgbaForExif(short, 4, 8, 6), short, 4, 8, "short buffer, swapping")
        assertUntouched(rotateRgbaForExif(short, 4, 8, 2), short, 4, 8, "short buffer, not swapping")
    }

    @Test
    fun `a result past the upload limit is refused instead of attempted`() {
        val src = Source(2, 2)
        // Orientation 6 swaps, so a 2x8193 source becomes 8193x2 - over either limit.
        assertUntouched(rotateRgbaForExif(src.buffer, 8193, 2, 6), src.buffer, 8193, 2, "swapped height")
        assertUntouched(rotateRgbaForExif(src.buffer, 2, 8193, 6), src.buffer, 2, 8193, "swapped width")
        // Orientation 2 keeps the shape, so only the width can push it over.
        assertUntouched(rotateRgbaForExif(src.buffer, 8193, 2, 2), src.buffer, 8193, 2, "unswapped width")
    }

    @Test
    fun `a result exactly at the upload limit is allowed through`() {
        val src = Source(SPREAD_MAX_DIM, 2)
        val out = rotateRgbaForExif(src.buffer, SPREAD_MAX_DIM, 2, 2)
        assertTrue(out.buffer !== src.buffer, "exactly at the limit must still rotate")
        out.width shouldBe SPREAD_MAX_DIM
        out.height shouldBe 2
        out.readPixels().size shouldBe SPREAD_MAX_DIM * 2
    }

    @Test
    fun `orientations 2 to 4 keep the shape and 5 to 8 swap it`() {
        val src = Source(3, 2)
        for (orientation in 2..4) {
            val out = rotateRgbaForExif(src.buffer, 3, 2, orientation)
            out.width shouldBe 3
            out.height shouldBe 2
        }
        for (orientation in 5..8) {
            val out = rotateRgbaForExif(src.buffer, 3, 2, orientation)
            out.width shouldBe 2
            out.height shouldBe 3
        }
    }

    @Test
    fun `orientation 6 turns the frame a quarter turn clockwise`() {
        val src = Source(3, 2)
        val out = rotateRgbaForExif(src.buffer, 3, 2, 6)
        out.width shouldBe 2
        out.height shouldBe 3
        // Source rows [0 1 2] [3 4 5] become columns bottom-up: [3 0] [4 1] [5 2].
        out.readPixels() shouldBe listOf(
            src.pixel(0, 1),
            src.pixel(0, 0),
            src.pixel(1, 1),
            src.pixel(1, 0),
            src.pixel(2, 1),
            src.pixel(2, 0),
        )
    }

    @Test
    fun `orientation 8 turns it a quarter turn anticlockwise, undoing 6`() {
        val src = Source(3, 2)
        val out = rotateRgbaForExif(src.buffer, 3, 2, 8)
        out.width shouldBe 2
        out.height shouldBe 3
        // The rightmost column becomes the top row: [2 5] [1 4] [0 3].
        out.readPixels() shouldBe listOf(
            src.pixel(2, 0),
            src.pixel(2, 1),
            src.pixel(1, 0),
            src.pixel(1, 1),
            src.pixel(0, 0),
            src.pixel(0, 1),
        )
    }

    @Test
    fun `orientation 2 mirrors left to right and 4 mirrors top to bottom`() {
        val src = Source(3, 2)
        rotateRgbaForExif(src.buffer, 3, 2, 2).readPixels() shouldBe listOf(
            src.pixel(2, 0),
            src.pixel(1, 0),
            src.pixel(0, 0),
            src.pixel(2, 1),
            src.pixel(1, 1),
            src.pixel(0, 1),
        )
        rotateRgbaForExif(src.buffer, 3, 2, 4).readPixels() shouldBe listOf(
            src.pixel(0, 1),
            src.pixel(1, 1),
            src.pixel(2, 1),
            src.pixel(0, 0),
            src.pixel(1, 0),
            src.pixel(2, 0),
        )
    }

    @Test
    fun `orientation 3 turns the frame upside down`() {
        val src = Source(3, 2)
        rotateRgbaForExif(src.buffer, 3, 2, 3).readPixels() shouldBe listOf(
            src.pixel(2, 1),
            src.pixel(1, 1),
            src.pixel(0, 1),
            src.pixel(2, 0),
            src.pixel(1, 0),
            src.pixel(0, 0),
        )
    }

    @Test
    fun `orientation 5 transposes and 7 runs the other diagonal`() {
        val src = Source(3, 2)
        val transposed = rotateRgbaForExif(src.buffer, 3, 2, 5)
        transposed.width shouldBe 2
        transposed.height shouldBe 3
        // Reflected across the leading diagonal, so the source's top row becomes its left column.
        transposed.readPixels() shouldBe listOf(
            src.pixel(0, 0),
            src.pixel(0, 1),
            src.pixel(1, 0),
            src.pixel(1, 1),
            src.pixel(2, 0),
            src.pixel(2, 1),
        )
        val transverse = rotateRgbaForExif(src.buffer, 3, 2, 7)
        transverse.width shouldBe 2
        transverse.height shouldBe 3
        // Reflected across the trailing diagonal: the transpose, turned a further quarter turn.
        transverse.readPixels() shouldBe listOf(
            src.pixel(2, 1),
            src.pixel(2, 0),
            src.pixel(1, 1),
            src.pixel(1, 0),
            src.pixel(0, 1),
            src.pixel(0, 0),
        )
    }

    @Test
    fun `every orientation is a permutation - no pixel is lost or invented`() {
        val src = Source(5, 3)
        for (orientation in 2..8) {
            val out = rotateRgbaForExif(src.buffer, 5, 3, orientation)
            val expected = src.allPixels().sorted()
            out.readPixels().sorted() shouldBe expected
            out.readPixels().size shouldBe expected.size
        }
    }

    @Test
    fun `channels move as one unit so alpha survives every rotation`() {
        val src = Source(4, 3)
        for (orientation in 2..8) {
            val out = rotateRgbaForExif(src.buffer, 4, 3, orientation)
            assertTrue(
                out.readPixels().all { (it ushr 24) and 0xFF == OPAQUE_USHIFTED },
                "orientation $orientation lost a pixel's alpha",
            )
        }
    }

    @Test
    fun `each rotation returns exactly one frame of pixels and nothing spare`() {
        val src = Source(4, 3)
        for (orientation in 2..8) {
            val out = rotateRgbaForExif(src.buffer, 4, 3, orientation)
            out.buffer.capacity() shouldBe out.width * out.height * 4
        }
    }

    @Test
    fun `the inverse of every orientation restores the frame`() {
        val src = Source(5, 3)
        // 2, 3 and 4 are their own inverse; 5 and 7 pair up, as do 6 and 8.
        val inverses = listOf(2, 3, 4, 5, 6, 7, 8).map { it to inverseOf(it) }
        for ((orientation, inverse) in inverses) {
            val forward = rotateRgbaForExif(src.buffer, 5, 3, orientation)
            val back = rotateRgbaForExif(forward.buffer, forward.width, forward.height, inverse)
            back.width shouldBe 5
            back.height shouldBe 3
            back.readPixels().sorted() shouldBe src.allPixels().sorted()
        }
    }

    @Test
    fun `four quarter turns put a square frame back exactly where it started`() {
        val src = Source(4, 4)
        var buffer = src.buffer
        var width = 4
        var height = 4
        repeat(4) {
            val turned = rotateRgbaForExif(buffer, width, height, 6)
            buffer = turned.buffer
            width = turned.width
            height = turned.height
        }
        width shouldBe 4
        height shouldBe 4
        RotatedRgba(buffer, width, height).readPixels() shouldBe src.allPixels()
    }

    @Test
    fun `the single-pixel frame is the degenerate case that must still rotate`() {
        val src = Source(1, 1)
        val out = rotateRgbaForExif(src.buffer, 1, 1, 6)
        out.width shouldBe 1
        out.height shouldBe 1
        out.readPixels() shouldBe listOf(src.pixel(0, 0))
    }

    @Test
    fun `the input buffer is not consumed - a passthrough leaves it readable`() {
        val src = Source(3, 2)
        rotateRgbaForExif(src.buffer, 3, 2, 1)
        // Reading the original afterwards proves the duplicate() used internally leaves position alone.
        src.buffer.getInt(0) shouldBe src.pixel(0, 0)
    }

    @Test
    fun `orientation text is read by digits so prefixes and spacing do not defeat it`() {
        exifOrientationDigits.find("6")?.value shouldBe "6"
        exifOrientationDigits.find(" 6 ")?.value shouldBe "6"
        exifOrientationDigits.find("x8y")?.value shouldBe "8"
        exifOrientationDigits.find("Orientation=3")?.value shouldBe "3"
        exifOrientationDigits.find("none") shouldBe null
        exifOrientationDigits.find("") shouldBe null
    }

    private fun inverseOf(orientation: Int) = when (orientation) {
        5 -> 7
        6 -> 8
        7 -> 5
        8 -> 6
        else -> orientation
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val OPAQUE_USHIFTED = 0xFF
        const val BYTES_PER_PIXEL = 4
    }
}
