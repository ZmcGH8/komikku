package exh.yakuyomi

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import mihon.core.concurrency.AppDispatchersHolder
import java.io.File

/** Result of importing a custom GGUF: the model to select and whether it already existed. */
data class GgufImportResult(
    val model: LocalLlmModel,
    val duplicate: Boolean,
)

/**
 * Owns the lifecycle of the on-device ("local") LLM provider: resolves the selected model from
 * [LocalLlmCatalog] (best-fit presented, only RAM enforced) or a user-supplied custom GGUF,
 * lazily builds and caches the llama.cpp backend for that model, and runs generations.
 *
 * This class is the facade. The work is split three ways and each part has one owner:
 * [ImportedGgufModels] for user-imported GGUFs, [LocalLlmSampling] for per-model sampling
 * overrides, and [LocalLlmSession] for the loaded backend and running/loading state. Model
 * *selection* stays here, because it is the one decision all three read from.
 */
@SingleIn(AppScope::class)
@Inject
class LocalLlmManager(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val downloadManager: LocalLlmDownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)

    private val imports = ImportedGgufModels(context, scope) { prefs.localModel().set(it.id) }
    private val sampling = LocalLlmSampling(prefs)
    private val session = LocalLlmSession(
        context = context,
        prefs = prefs,
        downloadManager = downloadManager,
        scope = scope,
        sampling = sampling,
        resolveModel = { resolveModel() },
    )

    fun isLocalProvider(): Boolean = prefs.provider().get().equals("local", ignoreCase = true)

    // ------------------------------------------------------------------ session

    val running: StateFlow<Boolean> get() = session.running

    val loading: StateFlow<Boolean> get() = session.loading

    fun isRunning(): Boolean = session.isRunning()

    fun start() = session.start()

    fun stop() = session.stop()

    fun clearModel() = session.clearModel()

    fun activeBackendType(): LocalLlmBackendType? = session.activeBackendType()

    fun isRuntimeAvailable(): Boolean = session.isRuntimeAvailable()

    fun accelerator(): LocalLlmAcceleratorInfo = session.accelerator()

    suspend fun generate(prompt: String, imageBytes: ByteArray? = null): String? =
        session.generate(prompt, imageBytes)

    fun closeAll() = session.closeAll()

    // ------------------------------------------------------------------ custom GGUF imports

    val importing: StateFlow<Boolean> get() = imports.importing

    fun importedModels(): List<LocalLlmModel> = imports.importedModels()

    fun importGguf(uri: Uri, onResult: (GgufImportResult?, error: String?) -> Unit) =
        imports.importGguf(uri, onResult)

    // ------------------------------------------------------------------ sampling

    fun samplingFor(model: LocalLlmModel): LocalLlmSamplingConfig = sampling.samplingFor(model)

    fun setSampling(modelId: String, config: LocalLlmSamplingConfig) = sampling.setSampling(modelId, config)

    fun resetSampling(modelId: String) = sampling.resetSampling(modelId)
    // ------------------------------------------------------------------

    /** Look up any selectable model: catalog first, then imported custom GGUFs. */
    fun modelById(id: String): LocalLlmModel? {
        LocalLlmCatalog.byId(id)?.let { return it }
        return importedModels().find { it.id == id }
    }

    /** Resolves the selected model; custom imports win, then the catalog preference, then best-fit. */
    fun resolveModel(): LocalLlmModel? {
        val selected = prefs.localModel().get()
        if (selected.startsWith("custom:")) {
            val file = File(imports.customDir, selected.removePrefix("custom:"))
            if (file.exists() && file.length() > 1_000_000L) return imports.customModelFor(file)
            // The imported file is gone; clear the selection and fall through.
            prefs.localModel().set("")
        }
        LocalLlmCatalog.byId(selected)?.let { return it }
        // One-time migration from the old single-custom importer (path stored in localModelFile).
        if (selected.isBlank()) {
            val legacy = prefs.localModelFile().get()
            if (legacy.isNotBlank()) {
                val file = File(legacy)
                if (file.exists() && file.length() > 1_000_000L) {
                    val model = imports.customModelFor(file)
                    prefs.localModelFile().set("")
                    prefs.localModel().set(model.id)
                    return model
                }
            }
        }
        return LocalLlmCatalog.bestForDevice(DeviceMemory.totalRamBytes(context))
    }

    /** Whether the resolved model's weights are downloaded (custom files count as ready). */
    fun isModelReady(): Boolean {
        val model = resolveModel() ?: return false
        return downloadManager.isDownloaded(model)
    }

    fun status(): LocalLlmDownloadManager.Status = downloadManager.status.value

    fun startDownload() {
        val model = resolveModel() ?: return
        downloadManager.startDownload(model)
    }

    fun cancelDownload() = downloadManager.cancelDownload()
}
