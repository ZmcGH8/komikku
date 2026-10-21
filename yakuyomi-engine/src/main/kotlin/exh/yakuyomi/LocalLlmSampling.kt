package exh.yakuyomi

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-model sampling overrides for the local llama.cpp provider.
 *
 * The override map is stored as one JSON blob under a single preference key rather than a key per
 * model, so adding a model does not need a new preference and removing one cannot leave a stale
 * entry behind.
 */
internal class LocalLlmSampling(
    private val prefs: TranslationPreferences,
) {
    private val samplingJson = Json { ignoreUnknownKeys = true }
    private val samplingMapSerializer = MapSerializer(String.serializer(), LocalLlmSamplingConfig.serializer())

    private fun samplingOverrides(): Map<String, LocalLlmSamplingConfig> {
        val raw = prefs.localLlmSamplingOverrides().get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { samplingJson.decodeFromString(samplingMapSerializer, raw) }.getOrDefault(emptyMap())
    }

    private fun persistSampling(overrides: Map<String, LocalLlmSamplingConfig>) {
        prefs.localLlmSamplingOverrides().set(
            if (overrides.isEmpty()) "" else samplingJson.encodeToString(samplingMapSerializer, overrides),
        )
    }

    /** Effective llama.cpp sampling config for [model]: stored override, else the model's own
     *  recommended defaults, else sensible generics. */
    fun samplingFor(model: LocalLlmModel): LocalLlmSamplingConfig {
        samplingOverrides()[model.id]?.let { return it }
        model.defaultSampling?.let { return it }
        return LocalLlmSamplingConfig(
            temperature = if (model.isTranslationFinetune) 0.0f else 0.3f,
            contextLength = model.contextLength,
        )
    }

    /** Persist a per-model sampling override (all fields; [LocalLlmSamplingConfig] is a value object). */
    fun setSampling(modelId: String, config: LocalLlmSamplingConfig) {
        persistSampling(samplingOverrides() + (modelId to config))
    }

    /** Drop the per-model override so the model falls back to defaults. */
    fun resetSampling(modelId: String) {
        persistSampling(samplingOverrides() - modelId)
    }
}
