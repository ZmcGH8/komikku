package exh.yakuyomi

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.concurrency.AppDispatchersHolder
import tachiyomi.core.common.util.system.logcat

/**
 * On-device LLM translation via Google's Gemini Nano (AICore / ML Kit GenAI Prompt API).
 *
 * Used as the priority LLM provider for the MTL engine when the device supports it: the
 * toggle (default on) gates the *attempt*, and [isAvailable] reports whether the model is
 * actually ready on this device. Only the LLM translation stage runs on Gemini Nano — the
 * detector/OCR/inpainter models stay on their NCNN/ONNX engines.
 *
 * Vision: when the caller passes the full page bitmap, it is included in the request as
 * extra context (speakers, layout, onomatopoeia, scene) alongside the OCR'd text lines.
 * The line-by-line translation protocol is unchanged — vision augments, never replaces,
 * the detected text.
 */
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class GeminiNanoTranslator(
    private val prefs: TranslationPreferences,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)

    private val model: GenerativeModel? by lazy {
        try {
            Generation.getClient()
        } catch (e: Exception) {
            logcat { "Gemini Nano client unavailable: ${e.message}" }
            null
        }
    }

    // checkStatus() returns an Int status code (FeatureStatus is an @IntDef annotation).
    @Volatile
    private var status: Int? = null

    @Volatile
    private var statusError: String? = null

    @Volatile
    private var lastStatusCheckMs = 0L

    @Volatile
    private var downloadStarted = false

    // checkStatus() queries AICore; the result only changes on download events, so cache it
    // briefly to keep per-page translation from hammering the system call.
    private val statusTtlMs = 30_000L

    /** Current device/model availability: FeatureStatus code or null before the first check. */
    fun statusCode(): Int? = status

    /** Last error from the availability check (AICore provisioning failure, etc.), or null. */
    fun statusError(): String? = statusError

    /** Whether Gemini Nano is currently usable for inference on this device. */
    suspend fun isAvailable(): Boolean = refreshStatus() == FeatureStatus.AVAILABLE

    private suspend fun refreshStatus(): Int {
        val m = model ?: return FeatureStatus.UNAVAILABLE.also { status = it }
        val cached = status
        val now = System.currentTimeMillis()
        if (cached != null && now - lastStatusCheckMs < statusTtlMs) {
            return cached
        }
        return try {
            val s = m.checkStatus()
            status = s
            statusError = null
            lastStatusCheckMs = now
            if (s == FeatureStatus.DOWNLOADABLE || s == FeatureStatus.DOWNLOADING) {
                ensureDownloaded(m)
            }
            s
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            logcat { "Gemini Nano status check GenAiException: ${e.message}" }
            statusError = e.message
            lastStatusCheckMs = now
            FeatureStatus.UNAVAILABLE.also { status = it }
        } catch (e: Exception) {
            logcat { "Gemini Nano status check failed: ${e.message}" }
            statusError = e.message
            lastStatusCheckMs = now
            FeatureStatus.UNAVAILABLE.also { status = it }
        }
    }

    /**
     * Kicks off (once) the AICore-managed download of the Gemini Nano model when the
     * device supports it but the model isn't installed yet. The download runs in the
     * background; translation falls back to the cloud provider until the status flips to
     * AVAILABLE on completion.
     */
    private fun ensureDownloaded(m: GenerativeModel) {
        if (downloadStarted) return
        downloadStarted = true
        scope.launch {
            try {
                m.download().collectLatest { s ->
                    when (s) {
                        is DownloadStatus.DownloadCompleted -> {
                            status = FeatureStatus.AVAILABLE
                            downloadStarted = false
                            logcat { "Gemini Nano download completed" }
                        }
                        is DownloadStatus.DownloadFailed -> {
                            downloadStarted = false
                            logcat { "Gemini Nano download failed: ${s.e.message}" }
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadStarted = false
                logcat { "Gemini Nano download error: ${e.message}" }
            }
        }
    }

    /**
     * Translates [queries] with Gemini Nano, applying the shared line protocol.
     *
     * Throws [TranslationException] when the device cannot serve the request and
     * [offlineFallback] is off, so the page is marked FAILED (retryable) rather than silently
     * skipped — the same contract the cloud and local backends honour.
     */
    suspend fun translate(
        queries: List<String>,
        pageBitmap: Bitmap?,
        sourceLang: String,
        offlineFallback: Boolean,
    ): List<String> = withContext(AppDispatchersHolder.get().default) {
        if (queries.isEmpty()) return@withContext emptyList()

        val capped = queries.map { it.take(500) }.take(30)
        val invalidBitmap = pageBitmap != null &&
            (pageBitmap.isRecycled || pageBitmap.width < 16 || pageBitmap.height < 16)
        if (invalidBitmap) {
            logcat { "Gemini Nano: invalid bitmap passed" }
            if (offlineFallback) return@withContext capped.map { it.trim() }
            throw TranslationException("Gemini Nano was given an unusable page image")
        }
        val unavailable = refreshStatus() != FeatureStatus.AVAILABLE || model == null

        val request = TranslationRequest(
            lines = capped,
            sourceLang = sourceLang,
            targetLang = prefs.targetLang().get().ifBlank { "en" },
            policy = prefs.promptPolicy(),
            imageContext = if (pageBitmap != null) {
                "The attached image is the manga page being processed. Use it for context " +
                    "(speakers, layout, onomatopoeia) but do not invent text. "
            } else {
                ""
            },
        )

        if (unavailable) {
            if (offlineFallback) return@withContext request.originalLines()
            throw TranslationException(
                "Gemini Nano is unavailable on this device — enable a cloud provider in Settings → Translation",
            )
        }

        TextTranslationProtocol.run(
            request = request,
            offlineFallback = offlineFallback,
            failureMessage = "Gemini Nano request failed",
            emptyMessage = "Gemini Nano returned no usable translation",
        ) { prompt -> generateRaw(model!!, prompt, pageBitmap) }
    }

    /** Issues the ML Kit request and returns the candidate text, or null on failure. */
    private suspend fun generateRaw(
        m: GenerativeModel,
        prompt: String,
        pageBitmap: Bitmap?,
    ): String? {
        val request = if (pageBitmap != null) {
            generateContentRequest(ImagePart(pageBitmap), TextPart(prompt)) {
                temperature = 0.3f
                maxOutputTokens = 2048
            }
        } else {
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.3f
                maxOutputTokens = 2048
            }
        }
        return m.generateContent(request).candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
    }

    fun close() {
        scope.cancel()
    }
}
