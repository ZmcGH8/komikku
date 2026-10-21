package exh.yakuyomi

import android.content.Context
import android.graphics.Bitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.PipelineErrorCode
import li.joye.yakuyomi.engine.Translator
import mihon.core.concurrency.AppDispatchersHolder
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Owns the native detector / OCR / inpainter sessions and runs the library pipeline per page.
 *
 * This class is deliberately only about *session lifetime and dispatch*: turning preferences into a
 * config is [EngineConfigFactory]'s job, and slicing a tall page across several pipeline runs and
 * stitching the results is [LongPageStitcher]'s. The translation stage is injected by the caller so
 * Komikku's breadcrumb-aware LLM stays in the loop.
 */
@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val modelManager: ModelManager,
) {
    private val configFactory = EngineConfigFactory(prefs)
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)

    /**
     * Serializes native-session access (build/close/translate) because the NCNN backends are not
     * thread-safe. MUST be declared before the [init] block: the status collector launched there
     * may run on a worker thread while the constructor is still executing the later field
     * initializers, and [invalidateComponents] reads this field — a not-yet-initialized mutex
     * crashes with NPE on `MutexImpl.lock`.
     */
    private val pipelineMutex = Mutex()

    init {
        // Keep the cached native sessions aligned with the on-disk model state: rebuild (or
        // drop) them when models are (re)downloaded or cleared, so the engine never serves
        // sessions loaded from deleted files and honestly reports "not ready" when they're gone.
        modelManager.status
            .onEach { s ->
                if (s.state == ModelManager.State.READY || s.state == ModelManager.State.NOT_INSTALLED) {
                    invalidateComponents()
                }
            }
            .launchIn(scope)
        // Tuning changes require rebuilding native sessions constructed with the old config. The
        // list of knobs that require it lives with the config mapping, not here.
        scope.launch {
            configFactory.configChanges().collect { invalidateComponents() }
        }
    }

    /** Human-readable reason reported when the device has too little RAM for the native pipeline. */
    val notEnoughMemoryReason: String
        get() = "not enough memory on this device — AI translation requires at least 3GB of RAM"

    fun isHardwareSupported(): Boolean = DeviceMemory.isMtlSupported(context)

    fun isStorageSupported(): Boolean {
        val dir = modelsDir()
        val usable = try {
            dir.usableSpace
        } catch (_: Exception) {
            -1L
        }
        if (usable in 1..(150L * 1024 * 1024)) return false
        return true
    }

    private fun modelsDir(): File = File(context.filesDir, "yakuyomi_models")

    private fun libModelSet(): ModelSet? {
        val dir = modelsDir()
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 1024 }?.map { it.name to it.absolutePath } ?: return null
        if (files.isEmpty()) return null
        val set = ModelSet.resolve(files) ?: return null
        val params = listOfNotNull(set.detectorNcnn, set.aotInpainterNcnn)
        if (params.any { p ->
                val bin = File(p.removeSuffix(".param") + ".bin")
                val ok = bin.isFile && bin.length() >= 1_000_000L
                if (!ok) logcat { "Model missing bin for $p" }
                !ok
            }
        ) {
            return null
        }
        if (!File(set.ocr).let { it.isFile && it.length() > 1_000_000L }) {
            logcat { "Model missing ocr ${set.ocr}" }
            return null
        }
        val totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (totalBytes > 0) logcat { "Models ready total ${totalBytes / (1024 * 1024)} MB" }
        return set
    }

    @Volatile
    private var components: NativeComponents? = null

    @Volatile
    private var lastBuildFailureMs = 0L

    private fun buildComponents(): NativeComponents? {
        if (!isHardwareSupported()) {
            logcat { "Yakuyomi engine skipped: device has too little RAM" }
            return null
        }
        // A failed build is usually a missing model file; retrying immediately on every page turn
        // would rebuild the same broken state over and over.
        if (System.currentTimeMillis() - lastBuildFailureMs < BUILD_FAILURE_BACKOFF_MS) return null
        val set = libModelSet() ?: run {
            logcat { "Yakuyomi models not ready" }
            return null
        }
        return try {
            val alphabet = loadAlphabet(set.ocr)
            if (alphabet.size < 10) {
                logcat { "Yakuyomi alphabet load failed, size=${alphabet.size}" }
                return null
            }
            val cfg = configFactory.defaultConfig()
            val detector = Detector(set.detectorNcnn ?: error("missing detector .param"), cfg.detector)
            val ocr = Ocr(set.ocr, alphabet, cfg.ocr)
            val inpainter = Inpainter(set.aotInpainterNcnn ?: error("missing inpainter .param"), cfg.inpainter)
            try {
                detector.warmUp()
                ocr.warmUp()
                inpainter.warmUp()
            } catch (e: Throwable) {
                // Never leak two of three sessions because the third failed to warm up.
                runCatching { detector.close() }
                runCatching { ocr.close() }
                runCatching { inpainter.close() }
                throw e
            }
            NativeComponents(detector, ocr, inpainter)
        } catch (e: Throwable) {
            lastBuildFailureMs = System.currentTimeMillis()
            logcat { "Yakuyomi engine init failed: ${e.message}" }
            null
        }
    }

    /**
     * Loads the CTC alphabet for whichever OCR model is installed.
     *
     * The two models disagree on vocabulary, and the dictionary is matched to the logits by index,
     * so the wrong list garbles every line rather than failing visibly. PP-OCRv5 is selected by
     * filename; anything else keeps the bundled 19,264-entry list.
     *
     * Blank-looking lines are kept deliberately. PP-OCRv5's dictionary begins with U+3000
     * (ideographic space), which Kotlin counts as whitespace, so an isNotBlank() filter would drop
     * index 2 and shift every later character by one.
     */
    private fun loadAlphabet(ocrModelPath: String?): List<String> {
        val asset = if (ocrModelPath != null && Regex("ppocr|ocrv5", RegexOption.IGNORE_CASE).containsMatchIn(ocrModelPath)) {
            "yakuyomi_alphabet_ppocrv5.txt"
        } else {
            "yakuyomi_alphabet.txt"
        }
        return runCatching {
            context.assets.open(asset).bufferedReader().use { it.readLines().filter { l -> l.isNotEmpty() } }
        }.getOrElse { emptyList() }.takeIf { it.isNotEmpty() } ?: listOf(" ")
    }

    /**
     * Returns the cached sessions, building them on first use. Not suspend: callers hold
     * [pipelineMutex] when the build must not race, and [prewarm] builds off-lock by design.
     */
    private fun buildIfNeeded(): NativeComponents? {
        components?.let { return it }
        return buildComponents()?.also { components = it }
    }

    private fun invalidateComponents() {
        scope.launch {
            pipelineMutex.withLock {
                val old = components.also { components = null }
                if (old != null) old.closeAll()
            }
        }
    }

    /**
     * Best-effort warm-up: builds the native components (downloading nothing) so the first page
     * translation does not pay the cold-start cost. Idempotent and cheap after first build.
     */
    fun prewarm(): Boolean = buildIfNeeded() != null

    /**
     * Runs the full library pipeline (detect → OCR → group → translate → inpaint → render) on one
     * page. [translator] is the breadcrumb-aware LLM stage; pass null to skip translation (debug).
     * Serialized via Mutex because NCNN native backends are not thread-safe.
     */
    suspend fun translatePage(bitmap: Bitmap, translator: Translator?, targetLang: String? = null): PageResult =
        translatePage(bitmap, { _: Bitmap -> translator }, targetLang)

    /**
     * Tall-page entry point. [translatorFactory] receives each slice bitmap rather than the whole
     * page, so vision-capable providers get per-slice context. See [LongPageStitcher] for the
     * all-or-nothing failure policy.
     */
    suspend fun translatePage(
        bitmap: Bitmap,
        translatorFactory: suspend (Bitmap) -> Translator?,
        targetLang: String? = null,
    ): PageResult = withContext(AppDispatchersHolder.get().default) {
        if (!isHardwareSupported()) {
            return@withContext PageResult.Failed(notEnoughMemoryReason, PipelineErrorCode.INVALID_BITMAP)
        }
        if (!isStorageSupported()) {
            return@withContext PageResult.Failed("not enough storage for translation", PipelineErrorCode.UNKNOWN)
        }
        pipelineMutex.withLock {
            val c = buildIfNeeded() ?: return@withContext PageResult.Failed("models not ready", PipelineErrorCode.UNKNOWN)
            val cfg = configFor(targetLang)
            val errs = cfg.validate()
            if (errs.isNotEmpty()) {
                return@withContext PageResult.Failed("invalid config: ${errs.first()}", PipelineErrorCode.UNKNOWN)
            }
            if (!prefs.longPageSlicingEnabled().get() || !LongPageSlicer.shouldSlice(bitmap.width, bitmap.height)) {
                return@withLock Pipeline(
                    c.detector,
                    c.ocr,
                    translatorFactory(bitmap),
                    c.inpainter,
                    cfg,
                    configFactory.resolveTypeface(),
                ).translatePage(bitmap)
            }
            LongPageStitcher(c, configFactory).translate(bitmap, cfg, translatorFactory)
        }
    }

    private fun configFor(targetLang: String?): EngineConfig =
        if (configFactory.shouldForceHorizontal(targetLang)) {
            configFactory.horizontalConfig()
        } else {
            configFactory.defaultConfig()
        }

    private companion object {
        const val BUILD_FAILURE_BACKOFF_MS = 5000L
    }
}
