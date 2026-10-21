package exh.yakuyomi

import android.graphics.Bitmap
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Translator
import mihon.core.concurrency.AppDispatchersHolder

/**
 * Breadcrumb-aware translation stage backed by the on-device ("local") GGUF provider built on the
 * vendored llama.cpp runtime. Vision-capable models additionally receive the page bitmap as
 * context; the line protocol itself belongs to [TextTranslationProtocol].
 */
class LocalLlmTranslator(
    private val manager: LocalLlmManager,
    private val sourceLang: String,
    private val targetLang: String,
    private val breadcrumb: String,
    private val mangaContext: String,
    private val pageBitmap: Bitmap?,
    private val offlineFallback: Boolean,
    private val glossary: Map<String, String> = emptyMap(),
    private val policy: TranslationPromptPolicy = TranslationPromptPolicy.DEFAULT,
) : Translator {

    override suspend fun translate(queries: List<String>): List<String> = withContext(AppDispatchersHolder.get().io) {
        if (queries.isEmpty()) return@withContext emptyList()
        val request = TranslationRequest(
            lines = queries,
            sourceLang = sourceLang,
            targetLang = targetLang,
            breadcrumb = breadcrumb,
            mangaContext = mangaContext,
            glossary = glossary,
            policy = policy,
            imageContext = if (pageBitmap != null) TextTranslationProtocol.VISION_CONTEXT_HINT else "",
        )
        val imageBytes = pageBitmap?.let { PageImageEncoder.toJpeg(it) }
        TextTranslationProtocol.run(
            request = request,
            offlineFallback = offlineFallback,
            failureMessage = "Local LLM translation failed",
            emptyMessage = "Local LLM returned no usable translation (is the model downloaded?)",
        ) { prompt ->
            manager.generate(prompt, imageBytes)
        }
    }
}
