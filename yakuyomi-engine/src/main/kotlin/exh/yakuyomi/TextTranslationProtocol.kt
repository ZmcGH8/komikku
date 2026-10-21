package exh.yakuyomi

import kotlinx.coroutines.CancellationException

/**
 * The one place the line-by-line text translation protocol is implemented: build the prompt, send
 * it, parse the reply, and map the reply back onto the input lines.
 *
 * Every backend used to assemble this sequence itself, which meant a change to the prompt or to
 * line alignment had to be applied once per provider. A backend now supplies only a `generate`
 * function returning raw model text and inherits the rest.
 */
object TextTranslationProtocol {

    /** Hints appended to the prompt when a page image travels with the request. */
    const val VISION_CONTEXT_HINT =
        "Image context: the attached page is visual context only; do not invent or replace OCR text."

    /**
     * Runs a provider's raw-text generation through the full protocol.
     *
     * @param generate raw completion text, or null/blank when the provider produced nothing.
     * @param failureMessage reported when generation threw. Kept distinct from [emptyMessage]
     *   because the two mean different things to the user: a thrown generation is usually a crash
     *   or a transport problem, while empty output usually means the model is missing or produced
     *   nothing usable.
     * @param emptyMessage reported when generation succeeded but returned nothing usable.
     * @throws TranslationException when generation fails and [offlineFallback] is off, so the
     *   page is marked FAILED (retryable) instead of silently SKIPPED.
     */
    suspend fun run(
        request: TranslationRequest,
        offlineFallback: Boolean,
        failureMessage: String,
        emptyMessage: String,
        generate: suspend (prompt: String) -> String?,
    ): List<String> {
        if (request.isEmpty) return emptyList()
        val raw = try {
            generate(request.buildPrompt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            if (offlineFallback) return request.originalLines()
            throw e
        } catch (e: Exception) {
            if (offlineFallback) return request.originalLines()
            throw TranslationException("$failureMessage: ${e.message}", e)
        }
        return fromRawText(raw, request, offlineFallback, emptyMessage)
    }

    /** Parses and aligns a raw completion, honouring [offlineFallback] on empty output. */
    fun fromRawText(
        raw: String?,
        request: TranslationRequest,
        offlineFallback: Boolean,
        emptyMessage: String,
    ): List<String> {
        if (raw.isNullOrBlank()) {
            if (offlineFallback) return request.originalLines()
            throw TranslationException(emptyMessage)
        }
        val parsed = parseTranslationLines(raw)
            ?: raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        return fromParsedLines(parsed, request, offlineFallback, emptyMessage)
    }

    /**
     * Aligns an already-parsed reply onto the request's lines. Used by backends whose transport
     * yields structured lines rather than raw text (the cloud JSON responses).
     */
    fun fromParsedLines(
        parsed: List<String>?,
        request: TranslationRequest,
        offlineFallback: Boolean,
        emptyMessage: String,
    ): List<String> {
        if (parsed.isNullOrEmpty()) {
            if (offlineFallback) return request.originalLines()
            throw TranslationException(emptyMessage)
        }
        return alignTranslationLines(parsed, request.lines)
    }
}
