package exh.yakuyomi

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * The loaded-model half of [LocalLlmManager]: owns the llama.cpp backend, its mutex, and the
 * running/loading state that goes with it.
 *
 * Model *selection* stays on [LocalLlmManager]; this class only ever acts on the model it is handed
 * via [resolveModel]. Closing a backend is guarded on every path - a native teardown that throws
 * must not escape [generate], whose callers all treat a failure as "try the next provider".
 */
internal class LocalLlmSession(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val downloadManager: LocalLlmDownloadManager,
    private val scope: CoroutineScope,
    private val sampling: LocalLlmSampling,
    private val resolveModel: () -> LocalLlmModel?,
) {
    private val backendMutex = Mutex()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @Volatile
    private var current: Pair<LocalLlmModel, LocalLlmBackend>? = null

    @Volatile
    private var acceleratorInfo: LocalLlmAcceleratorInfo? = null

    /** Whether a model is loaded in memory (the engine is warm). */
    fun isRunning(): Boolean = _running.value

    /** Warms the engine: eagerly loads the resolved model. No-op when it is already loaded. */
    fun start() {
        val model = resolveModel() ?: return
        if (!downloadManager.isDownloaded(model)) return
        _loading.value = true
        scope.launch {
            backendFor(model)
            _loading.value = false
        }
    }

    /** Unloads the model and frees the native memory. */
    fun stop() {
        _loading.value = false
        scope.launch {
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm stop failed: ${it.message}" } }
            }
        }
    }

    fun clearModel() {
        val model = resolveModel() ?: return
        scope.launch {
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm clear close failed: ${it.message}" } }
            }
        }
        if (model.isCustom) {
            model.ggufFile?.let { File(it).delete() }
            prefs.localModel().set("")
        } else {
            downloadManager.clearModel(model)
        }
    }

    /** Backend type actually in use for the resolved model (or null when unavailable). */
    fun activeBackendType(): LocalLlmBackendType? =
        if (resolveModel() != null) LocalLlmBackendType.LLAMACPP else null

    /** Whether the llama.cpp runtime is bundled in this build. */
    fun isRuntimeAvailable(): Boolean = LlamaCppLlmBackend.isAvailable()

    /** What the runtime can offload to. Probed once; the answer cannot change at runtime. */
    fun accelerator(): LocalLlmAcceleratorInfo =
        acceleratorInfo ?: synchronized(this) {
            acceleratorInfo ?: LocalLlmAccelerator.probe(context).also { acceleratorInfo = it }
        }

    suspend fun generate(prompt: String, imageBytes: ByteArray? = null): String? {
        val model = resolveModel() ?: return null
        if (!downloadManager.isDownloaded(model)) {
            logcat { "Local LLM ${model.id} not downloaded yet" }
            return null
        }
        if (prompt.isBlank() || prompt.length > 20000) {
            logcat { "Local LLM prompt invalid length ${prompt.length}" }
            return null
        }
        if (imageBytes != null && imageBytes.size > 8 * 1024 * 1024) {
            logcat { "Local LLM image too large ${imageBytes.size}" }
            return null
        }
        val backend = try {
            backendFor(model)
        } catch (e: Exception) {
            logcat { "Local LLM backend setup failed: ${e.message}" }
            null
        } ?: return null
        val config = sampling.samplingFor(model)
        val maxTokens = config.maxTokens.coerceIn(64, 4096)
        val contextLen = config.contextLength.coerceAtLeast(512)
        var safePrompt = prompt
        val estTokens = safePrompt.length / 3 + maxTokens + 256
        if (estTokens > contextLen) {
            val keepChars = (contextLen - maxTokens - 256).coerceAtLeast(512) * 3
            val cutAt = safePrompt.length - keepChars
            val newlineIdx = safePrompt.indexOf('\n', cutAt).takeIf { it >= 0 } ?: cutAt
            safePrompt = safePrompt.substring(newlineIdx.coerceIn(0, safePrompt.length))
            logcat { "Local LLM prompt truncated ${prompt.length} -> ${safePrompt.length} to fit $contextLen" }
        }
        return try {
            kotlinx.coroutines.withTimeout(90_000) {
                backend.generate(LocalGenerateRequest(prompt = safePrompt, maxTokens = maxTokens, temperature = config.temperature, imageBytes = imageBytes))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logcat { "Local LLM timeout for ${model.id}" }
            null
        } catch (e: Exception) {
            logcat { "Local LLM generate failed: ${e.message}" }
            null
        }
    }

    private suspend fun backendFor(model: LocalLlmModel): LocalLlmBackend? = backendMutex.withLock {
        current?.let { (m, b) -> if (m.id == model.id) return b }
        current?.second?.let { stale -> runCatching { stale.close() }.onFailure { logcat { "Local LLM backend swap close failed: ${it.message}" } } }
        current = null
        _running.value = false
        val dir = downloadManager.modelDir(model)
        val backend = LlamaCppLlmBackend.create(model, dir, sampling.samplingFor(model), accelerator()) { msg ->
            logcat { "llama.cpp: $msg" }
        }
        if (backend != null) {
            current = model to backend
            _running.value = true
        }
        backend
    }

    fun closeAll() {
        scope.launch {
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm closeAll failed: ${it.message}" } }
            }
        }
    }
}
