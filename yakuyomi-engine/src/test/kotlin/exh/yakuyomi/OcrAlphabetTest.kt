package exh.yakuyomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

/**
 * Invariants for the OCR alphabets shipped as assets.
 *
 * A CTC alphabet is matched to the model's output by index, so a mismatch is silent: OCR keeps
 * returning text, just the wrong text. Nothing downstream can catch a vocabulary that is off by even
 * one entry, which is why the layout is pinned here rather than only checked on a device.
 *
 * The PP-OCRv5 layout is PaddleOCR's: the model maps `ppocrv5_dict.txt[i]` to logits id `i`, so the
 * dictionary sits **verbatim** in the asset with `<blank>` prepended and the inserted space
 * **appended**. Prepending the space instead shifts every character by one and yields
 * fluent-looking garbage - fluent *and* wrong, which is the whole hazard, since confidence stayed
 * near 1.0 while every line was a near-miss.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrAlphabetTest {

    private val assets: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "src/main/assets") }
        .firstOrNull { File(it, PP_OCR_ASSET).isFile }
        ?: error("could not locate src/main/assets/$PP_OCR_ASSET from ${System.getProperty("user.dir")}")

    private fun lines(name: String): List<String> =
        File(assets, name).readLines().filter { it.isNotEmpty() }

    private val ppocr = lines(PP_OCR_ASSET)
    private val bundled = lines(BUNDLED_ASSET)

    @Test
    fun `ppocrv5 vocabulary matches the model logits width`() {
        // 1 blank + 18383 entries of PaddleOCR's ppocrv5_dict.txt + 1 inserted space. The model
        // emits 18385 logits, so this count is a contract with the weights, not a snapshot.
        ppocr.size shouldBe 18385
    }

    @Test
    fun `bundled vocabulary is untouched by adding the alternative`() {
        bundled.size shouldBe 19264
    }

    @Test
    fun `ppocrv5 reserves blank first and the inserted space last`() {
        // PaddleOCR's label map is ['blank'] + dict + [' ']. Putting the space at index 1 instead is
        // the exact bug this file exists to prevent.
        ppocr.first() shouldBe "<blank>"
        ppocr.last() shouldBe "<SP>"
    }

    @Test
    fun `ppocrv5 dictionary is verbatim from index one`() {
        // Index 1 is the dictionary's own first entry (U+3000), not an inserted placeholder. This
        // pins the alignment: logits id i must read ppocrv5_dict[i - 1].
        ppocr[1] shouldBe "　"
        ppocr[2] shouldBe "一"
    }

    @Test
    fun `only the ideographic space may be dropped as whitespace`() {
        // loadAlphabet filters on isNotEmpty, so no other entry may be silently removable. U+3000
        // is here deliberately: it opens PaddleOCR's dictionary and Kotlin counts it as whitespace.
        ppocr.indices.filter { ppocr[it].isBlank() } shouldBe listOf(1)
    }

    @Test
    fun `the two alphabets differ so filename selection matters`() {
        // YakuyomiEngine picks between them by OCR model filename; identical files would mean the
        // choice was untested and one of the two models was always wrong.
        (ppocr == bundled) shouldBe false
    }

    @Test
    fun `ppocrv5 has no duplicate entries`() {
        // A duplicate leaves one dictionary entry unreachable and renders two ids as the same glyph.
        ppocr.size shouldBe ppocr.distinct().size
    }

    private companion object {
        const val PP_OCR_ASSET = "yakuyomi_alphabet_ppocrv5.txt"
        const val BUNDLED_ASSET = "yakuyomi_alphabet.txt"
    }
}
