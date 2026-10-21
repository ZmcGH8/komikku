package exh.yakuyomi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Covers the decision logic the pipeline refactor introduced, where a mistake is silent rather than
 * loud: which pages are worth decoding, and whether a provider failure falls back or throws.
 */
@Execution(ExecutionMode.CONCURRENT)
class PageImageValidatorTest {

    @Test
    fun `a page below the minimum byte count is rejected as corrupt`() {
        PageImageValidator.sizeRejection(ByteArray(1023)) shouldBe "Image too small or corrupted"
    }

    @Test
    fun `exactly the minimum byte count is accepted`() {
        PageImageValidator.sizeRejection(ByteArray(1024)) shouldBe null
    }

    @Test
    fun `exactly the maximum byte count is accepted`() {
        PageImageValidator.sizeRejection(ByteArray(30 * 1024 * 1024)) shouldBe null
    }

    @Test
    fun `one byte over the maximum is rejected as too large`() {
        PageImageValidator.sizeRejection(ByteArray(30 * 1024 * 1024 + 1)) shouldBe "Image too large"
    }

    @Test
    fun `an ordinary page passes the size guard`() {
        PageImageValidator.sizeRejection(ByteArray(2 * 1024 * 1024)) shouldBe null
    }
}

@Execution(ExecutionMode.CONCURRENT)
class TranslationProtocolFallbackTest {

    private fun request(vararg lines: String) = TranslationRequest(
        lines = lines.toList(),
        sourceLang = "JA",
        targetLang = "en",
    )

    @Test
    fun `english to english is a grammar fix, case-insensitively`() {
        TranslationRequest(listOf("x"), "en", "EN").isEnglishFix shouldBe true
        TranslationRequest(listOf("x"), "JA", "en").isEnglishFix shouldBe false
    }

    @Test
    fun `empty output falls back to the source lines when fallback is on`() {
        val req = request("犬だ", "こんにちは")
        TextTranslationProtocol.fromRawText(
            raw = null,
            request = req,
            offlineFallback = true,
            emptyMessage = "empty",
        ) shouldBe listOf("犬だ", "こんにちは")
    }

    @Test
    fun `empty output throws the empty message when fallback is off`() {
        val thrown = runCatching {
            TextTranslationProtocol.fromRawText(
                raw = "   ",
                request = request("犬だ"),
                offlineFallback = false,
                emptyMessage = "the model is not downloaded",
            )
        }.exceptionOrNull()
        thrown.shouldBeInstanceOf<TranslationException>()
        thrown?.message shouldBe "the model is not downloaded"
    }

    @Test
    fun `a thrown generation reports the failure message, not the empty one`() = runTest {
        val thrown = runCatching {
            TextTranslationProtocol.run(
                request = request("犬だ"),
                offlineFallback = false,
                failureMessage = "the request crashed",
                emptyMessage = "the model is not downloaded",
            ) { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        thrown.shouldBeInstanceOf<TranslationException>()
        thrown?.message shouldBe "the request crashed: boom"
    }

    @Test
    fun `a thrown generation still falls back when fallback is on`() = runTest {
        val out = TextTranslationProtocol.run(
            request = request("犬だ", "こんにちは"),
            offlineFallback = true,
            failureMessage = "the request crashed",
            emptyMessage = "the model is not downloaded",
        ) { throw IllegalStateException("boom") }
        out shouldBe listOf("犬だ", "こんにちは")
    }

    @Test
    fun `parsed lines are aligned back onto the request order`() {
        val out = TextTranslationProtocol.fromParsedLines(
            parsed = listOf("<|2|> Hello!", "<|1|> A dog!"),
            request = request("犬だ", "こんにちは"),
            offlineFallback = false,
            emptyMessage = "empty",
        )
        out shouldBe listOf("A dog!", "Hello!")
    }

    @Test
    fun `a request with no lines short-circuits instead of calling the provider`() = runTest {
        var called = false
        val out = TextTranslationProtocol.run(
            request = request(),
            offlineFallback = false,
            failureMessage = "unused",
            emptyMessage = "unused",
        ) {
            called = true
            "should not be reached"
        }
        out shouldBe emptyList()
        called shouldBe false
    }
}

@Execution(ExecutionMode.CONCURRENT)
class TranslationErrorMapperImageTest {

    @Test
    fun `image problems get actionable text rather than a generic prefix`() {
        TranslationErrorMapper.toUserMessage("Image too small or corrupted") shouldBe
            "Page image is too small or corrupted — skipping page"
        TranslationErrorMapper.toUserMessage("Invalid image dimensions 0x0") shouldBe
            "Page image dimensions are not supported — skipping page"
        TranslationErrorMapper.toUserMessage("Unable to decode image bounds") shouldBe
            "Page image could not be decoded — skipping page"
    }

    @Test
    fun `a crash and a missing model still get different advice`() {
        val crash = TranslationErrorMapper.toUserMessage("Local LLM translation failed: boom")
        val missing = TranslationErrorMapper.toUserMessage(
            "Local LLM returned no usable translation (is the model downloaded?)",
        )
        crash shouldBe "Translation failed: Local LLM translation failed: boom"
        missing shouldBe "On-device model not downloaded — download it in Settings → Translation (Local)"
        (crash == missing) shouldBe false
    }
}
