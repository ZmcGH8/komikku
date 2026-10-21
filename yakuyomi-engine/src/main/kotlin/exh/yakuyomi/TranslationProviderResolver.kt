package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Which backend will actually service a translation request.
 *
 * The order here is the resolution order: an enabled image service replaces the whole text
 * pipeline, then the on-device Gemini Nano model, then a local GGUF model, then the cloud.
 */
enum class TranslationProviderKind {
    /** Whole-page image translation service; not a text provider. */
    IMAGE_SERVICE,
    GEMINI_NANO,
    LOCAL_GGUF,
    CLOUD,
}

/**
 * The single answer to "who is translating right now", including the identity stamped onto cache
 * entries.
 *
 * [providerName] and [modelName] must be stable for a given configuration: they are part of every
 * page and metadata cache key, so two code paths deriving them differently would read and write
 * the same cache under different names.
 */
data class ActiveTranslationProvider(
    val kind: TranslationProviderKind,
    val providerName: String,
    val modelName: String,
    /** False when the selected provider cannot run yet (e.g. a cloud provider with no API key). */
    val isReady: Boolean,
    val unavailableReason: String? = null,
) {
    val isImageService: Boolean get() = kind == TranslationProviderKind.IMAGE_SERVICE

    /** True for backends that translate text lines, i.e. everything except the image service. */
    val isTextProvider: Boolean get() = kind != TranslationProviderKind.IMAGE_SERVICE
}

/**
 * The one place the active provider is decided.
 *
 * Previously the page pipeline, the metadata path and the cache identity each re-derived this
 * independently, and they had already drifted: metadata identity ignored Gemini Nano, so a
 * metadata entry could be stamped with the cloud model while pages were being translated on
 * device. Adding a provider meant editing four separate decision sites.
 */
@SingleIn(AppScope::class)
@Inject
class TranslationProviderResolver(
    private val prefs: TranslationPreferences,
    private val geminiNano: GeminiNanoTranslator,
    private val localLlm: LocalLlmManager,
) {

    suspend fun resolve(): ActiveTranslationProvider {
        val providerPref = prefs.provider().get()
        val wantsImageService = prefs.mangaTranslatorEnabled().get() ||
            providerPref.equals(PROVIDER_IMAGE_SERVICE, ignoreCase = true)
        if (wantsImageService) {
            return ActiveTranslationProvider(
                kind = TranslationProviderKind.IMAGE_SERVICE,
                providerName = PROVIDER_IMAGE_SERVICE,
                modelName = PROVIDER_IMAGE_SERVICE,
                isReady = true,
            )
        }

        if (prefs.geminiNanoEnabled().get() && geminiNano.isAvailable()) {
            return ActiveTranslationProvider(
                kind = TranslationProviderKind.GEMINI_NANO,
                providerName = PROVIDER_GEMINI_NANO,
                modelName = PROVIDER_GEMINI_NANO,
                isReady = true,
            )
        }

        if (localLlm.isLocalProvider()) {
            return ActiveTranslationProvider(
                kind = TranslationProviderKind.LOCAL_GGUF,
                providerName = PROVIDER_LOCAL,
                modelName = "$PROVIDER_LOCAL:${localLlm.resolveModel()?.id ?: "auto"}",
                isReady = true,
            )
        }

        val provider = providerPref.lowercase().ifBlank { DEFAULT_CLOUD_PROVIDER }
        val model = prefs.effectiveModel().ifBlank { DEFAULT_CLOUD_MODEL }
        val hasKey = prefs.effectiveApiKey().isNotBlank()
        return ActiveTranslationProvider(
            kind = TranslationProviderKind.CLOUD,
            providerName = provider,
            modelName = model,
            isReady = hasKey,
            unavailableReason = if (hasKey) null else "no API key configured for $provider",
        )
    }

    /** Target language, defaulted the same way for every caller. */
    fun targetLanguage(): String = prefs.targetLang().get().ifBlank { DEFAULT_TARGET_LANG }

    /**
     * Everything about the current configuration that changes what a translated page *is*.
     *
     * Both page caches are keyed on this instead of on the model name alone. Target language and
     * provider were previously absent from the saved-page key entirely, so switching either served
     * the previously saved page — the user changes the target language and every page stays in the
     * old one, with nothing to indicate the setting had been ignored. The content-addressed cache
     * folded the language in but not the provider, so the same model reached through two different
     * providers shared one cache entry.
     *
     * Cost of getting this wrong is not a stale read, it is a permanent one: a page saved once is
     * re-served for as long as it survives pruning, and the identity is the only thing that can
     * invalidate it.
     */
    suspend fun pageCacheIdentity(): String {
        val active = resolve()
        return "${active.providerName}|${active.modelName}|${targetLanguage()}"
    }

    // The metadata path below is deliberately synchronous. Its readiness is read from the UI on a
    // hot path (MangaInfoTranslationController) and is stubbed in tests, so it must not depend on
    // the suspending Gemini Nano probe. Metadata does not use Gemini Nano today - it needs no page
    // image - so this is a real difference in capability, not a second copy of the same decision.

    /**
     * Metadata readiness. The image service cannot translate bare text, so it reports unsupported.
     */
    fun metadataState(): MangaInfoProviderState {
        if (prefs.mangaTranslatorEnabled().get() ||
            prefs.provider().get().equals(PROVIDER_IMAGE_SERVICE, ignoreCase = true)
        ) {
            return MangaInfoProviderState.MANGA_TRANSLATOR_UNSUPPORTED
        }
        if (localLlm.isLocalProvider()) return MangaInfoProviderState.READY
        return if (prefs.effectiveApiKey().isNotBlank()) {
            MangaInfoProviderState.READY
        } else {
            MangaInfoProviderState.NOT_CONFIGURED
        }
    }

    /** Provider/model identity stamped on metadata cache entries. */
    fun metadataIdentity(): MangaInfoIdentity = if (localLlm.isLocalProvider()) {
        MangaInfoIdentity(
            provider = PROVIDER_LOCAL,
            model = "$PROVIDER_LOCAL:${localLlm.resolveModel()?.id ?: "auto"}",
        )
    } else {
        MangaInfoIdentity(provider = prefs.provider().get().lowercase(), model = prefs.effectiveModel())
    }

    companion object {
        const val PROVIDER_IMAGE_SERVICE = "mangatranslator"
        const val PROVIDER_GEMINI_NANO = "gemini-nano"
        const val PROVIDER_LOCAL = "local"
        const val DEFAULT_CLOUD_PROVIDER = "openrouter"
        const val DEFAULT_CLOUD_MODEL = "google/gemma-2-9b-it:free"
        const val DEFAULT_TARGET_LANG = "en"
    }
}
