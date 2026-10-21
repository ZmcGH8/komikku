package exh.yakuyomi

/**
 * Everything a text provider needs to translate one batch of OCR lines, independent of which
 * provider will run it.
 *
 * This exists so provider selection, prompt construction, response parsing and line alignment are
 * decided once in [TextTranslationProtocol] rather than re-assembled by each backend. Adding a
 * provider should never require re-deriving [isEnglishFix] or re-wiring the parse/align pair.
 */
data class TranslationRequest(
    val lines: List<String>,
    val sourceLang: String,
    val targetLang: String,
    val breadcrumb: String = "",
    val mangaContext: String = "",
    val glossary: Map<String, String> = emptyMap(),
    val policy: TranslationPromptPolicy = TranslationPromptPolicy.DEFAULT,
    /** Provider-facing hint describing the attached page. Blank when the page carries no vision. */
    val imageContext: String = "",
) {
    /** True when source and target are both English, which switches the prompt to grammar fixing. */
    val isEnglishFix: Boolean
        get() = sourceLang.equals("EN", ignoreCase = true) && targetLang.equals("EN", ignoreCase = true)

    val isEmpty: Boolean get() = lines.isEmpty()

    fun buildPrompt(): String = buildTranslationPrompt(
        texts = lines,
        sourceLang = sourceLang,
        targetLang = targetLang,
        breadcrumb = breadcrumb,
        isEnFix = isEnglishFix,
        mangaContext = mangaContext,
        glossary = glossary,
        policy = policy,
        imageContext = imageContext,
    )

    /** What to show when a provider cannot answer and offline fallback is on. */
    fun originalLines(): List<String> = lines.map { it.trim() }
}
