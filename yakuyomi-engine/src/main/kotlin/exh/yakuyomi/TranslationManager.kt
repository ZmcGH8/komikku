package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Translator
import mihon.core.concurrency.AppDispatchersHolder
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class TranslationManager(
    private val prefs: TranslationPreferences,
    private val pageCache: PageResultCache,
    private val engine: YakuyomiEngine,
    private val notes: BreadcrumbNotes,
    private val client: OkHttpClient,
    private val perMangaStore: TranslateMangaStore,
    private val preferenceStore: PreferenceStore,
    private val status: TranslationStatus,
    // KMK -->
    private val geminiNano: GeminiNanoTranslator,
    private val localLlm: LocalLlmManager,
    private val infoStore: MangaInfoTranslationStore,
    private val mangaTranslator: MangaTranslatorService,
    private val resolver: TranslationProviderResolver,
    private val mangaContextProvider: suspend (Long) -> String? = { null },
    // KMK <--
) {
    // KMK -->
    // Ordered, capped per-chapter work queue. The per-page pipeline is passed in as a lambda so the
    // queue stays free of translation concerns; it owns ordering, lifecycle and the pending cap only.
    private val pageQueue = PageQueue(
        worker = { mangaId, chapterId, pageIndex, imageBytes, sourceLangHint ->
            translatePageInternal(mangaId, chapterId, pageIndex, imageBytes, sourceLangHint)
        },
    )
    // KMK <--

    fun isEnabled(): Boolean = prefs.enabled().get()

    private val incognitoPref by lazy { preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false) }
    private val censorPref by lazy { preferenceStore.getBoolean("pref_censor_lewd_manga", false) }

    fun isGated(): Boolean = incognitoPref.get() || censorPref.get()

    suspend fun shouldTranslate(): Boolean {
        if (!isEnabled()) return false
        if (isGated()) {
            xLogD("Translation gated: incognito/censor")
            return false
        }
        return true
    }

    /**
     * The one enablement gate: on globally, not gated, and on for this manga.
     *
     * Synchronous because the reader consults it on every page it binds, where suspending would mean
     * a read per page. [shouldTranslateForManga] answers the same question and adds the log line.
     */
    fun isEnabledForManga(mangaId: Long): Boolean = isEnabled() && !isGated() && perMangaStore.isEnabled(mangaId)

    suspend fun shouldTranslateForManga(mangaId: Long): Boolean {
        if (!isEnabledForManga(mangaId)) {
            if (isEnabled() && isGated()) xLogD("Translation gated: incognito/censor")
            return false
        }
        return true
    }

    fun isPerMangaEnabled(mangaId: Long): Boolean = perMangaStore.isEnabled(mangaId)

    fun setPerMangaEnabled(mangaId: Long, enabled: Boolean) = perMangaStore.setEnabled(mangaId, enabled)

    fun cancelChapter(mangaId: Long, chapterId: Long) {
        pageQueue.cancel(mangaId, chapterId)
        status.resetChapter(mangaId, chapterId)
    }

    fun pauseChapter(mangaId: Long, chapterId: Long) = pageQueue.pause(mangaId, chapterId)

    fun resumeChapter(mangaId: Long, chapterId: Long) = pageQueue.resume(mangaId, chapterId)

    fun retryChapter(mangaId: Long, chapterId: Long) {
        val st = status.chapterStatus(mangaId, chapterId) ?: return
        // Not just ERROR: a page the provider never answered is also worth another attempt, and it
        // arrives as SKIPPED. Retrying only the red pages left the silent skips untouched.
        val retryable = st.pages.filterValues { it.isRetryable }.keys
        if (retryable.isEmpty()) return
        status.updateForRetry(mangaId, chapterId, retryable)
    }

    fun clearAllChapters() {
        pageQueue.cancelAll().forEach { (mangaId, chapterId) -> status.resetChapter(mangaId, chapterId) }
        status.clearAll()
    }

    fun clearAll() = clearAllChapters()

    /**
     * Translates a manga's metadata (title + optional description) with the active text
     * provider (on-device local LLM, or the configured cloud model). Independent of the
     * per-manga page-translation toggle: only the global MTL switch and the
     * incognito/censor gates apply. Results are cached on disk via
     * [MangaInfoTranslationStore] keyed by the full request identity. Returns null when
     * the feature is gated, no text provider is ready, or the provider returned nothing
     * usable.
     */
    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
        sourceId: Long? = null,
    ): MangaInfoTranslation? {
        if (!shouldTranslateMangaInfo()) return null
        if (mangaInfoProviderState() != MangaInfoProviderState.READY) return null
        val sourceLang = resolveMangaInfoSourceLang(sourceLangHint)
        val targetLang = resolver.targetLanguage()
        val identity = mangaInfoIdentity()
        val promptPolicy = prefs.promptPolicy()
        val promptFingerprint = promptPolicy.fingerprint()
        val lines = listOfNotNull(title.ifBlank { null }, description?.ifBlank { null })
        if (lines.isEmpty()) return null
        val glossary = prefs.glossaryMap()
        val translated = try {
            if (localLlm.isLocalProvider()) {
                val request = TranslationRequest(
                    lines = lines,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    glossary = glossary,
                    policy = promptPolicy,
                )
                // Metadata is strict: a missing or unparseable reply is a failure, never a padded
                // result, so offline fallback stays off here regardless of the page setting.
                TextTranslationProtocol.fromRawText(
                    raw = localLlm.generate(request.buildPrompt()),
                    request = request,
                    offlineFallback = false,
                    emptyMessage = "Local LLM returned no usable metadata translation",
                )
            } else {
                if (prefs.effectiveApiKey().isBlank()) return null
                YakuyomiTranslator(
                    apiKey = prefs.effectiveApiKey(),
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    breadcrumb = "",
                    provider = prefs.provider().get().lowercase(),
                    model = prefs.effectiveModel(),
                    offlineFallback = prefs.offlineFallback().get(),
                    client = client,
                    customBaseUrl = prefs.customBaseUrl().get(),
                    customHeaders = prefs.customHeaders().get(),
                    policy = promptPolicy,
                ).translate(lines)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogE("translateMangaInfo failed", e)
            null
        } ?: return null
        // KMK --> Strict per-field mapping: missing/blank output for any requested field is
        // an error (never padded with source text, never falling back to the original).
        val (newTitle, newDescription) = mapMangaInfoTranslation(title, description, translated) ?: return null
        // KMK --> Stamp the full request identity so stale entries never display as current.
        val result = MangaInfoTranslation(
            title = newTitle,
            description = newDescription,
            sourceFingerprint = buildMangaInfoFingerprint(sourceId, title, description, sourceLang),
            targetLanguage = targetLang,
            provider = identity.provider,
            model = identity.model,
            promptFingerprint = promptFingerprint,
        )
        // KMK <--
        infoStore.put(mangaId, result)
        return result
    }

    // KMK --> Independent metadata-translation eligibility (spec 2026-09-23): the global
    // MTL switch plus incognito/censor gates apply, but the per-manga page-translation
    // toggle is deliberately not consulted. Page-translation gates are untouched.
    suspend fun shouldTranslateMangaInfo(): Boolean = shouldTranslate()

    /**
     * Whether a text provider is ready for metadata translation. MangaTranslator is an
     * image service and is reported as unsupported; local LLM and cloud text providers
     * follow the same readiness rules as the page pipeline.
     */
    fun mangaInfoProviderState(): MangaInfoProviderState = resolver.metadataState()

    /** Provider/model identity stamped on metadata cache entries. Mirrors the page pipeline. */
    fun mangaInfoIdentity(): MangaInfoIdentity = resolver.metadataIdentity()

    /**
     * Validated metadata cache read: returns the entry only when its fingerprint,
     * target language, provider, and model all match the current request.
     */
    fun getValidCachedMangaInfo(
        mangaId: Long,
        sourceId: Long?,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
    ): MangaInfoTranslation? {
        val sourceLang = resolveMangaInfoSourceLang(sourceLangHint)
        val targetLang = resolver.targetLanguage()
        val identity = mangaInfoIdentity()
        return infoStore.getValidated(
            mangaId,
            buildMangaInfoFingerprint(sourceId, title, description, sourceLang),
            targetLang,
            identity.provider,
            identity.model,
            prefs.promptFingerprint(),
        )
    }
    // KMK <--

    /** Declares the page count up front so chapter-list progress is accurate while translating. */
    fun setChapterTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) =
        status.setTotalPages(mangaId, chapterId, totalPages)

    fun friendlyError(raw: String?): String = TranslationErrorMapper.toUserMessage(raw)

    /** Cache identity for the provider that would translate a page. See [TranslationProviderResolver]. */
    private suspend fun effectiveModel(): String = resolver.resolve().modelName

    /**
     * Provider, model and target language folded into one string, used as the page cache key so a
     * configuration change cannot be answered from a page saved under the previous one.
     */
    private suspend fun pageCacheIdentity(): String = resolver.pageCacheIdentity()

    /**
     * Fast path for pages already translated in this session/on disk: serves the saved page or the
     * hash cache without running the detection/OCR/LLM pipeline. Returns null when nothing is stored
     * (caller should fall back to [translatePage]).
     */
    suspend fun getTranslatedBytes(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
    ): ByteArray? = withContext(AppDispatchersHolder.get().io) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = resolver.targetLanguage()
        val model = effectiveModel()
        val promptFingerprint = prefs.promptFingerprint()
        pageCache.lookup(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            imageBytes = imageBytes,
            targetLang = targetLang,
            model = model,
            promptFingerprint = promptFingerprint,
            identity = pageCacheIdentity(),
        )?.let { return@withContext it.bytes }
        null
    }

    // KMK --> Title for the per-manga cache sidecar: first line of the manga
    // context grounding ("title\ndesc\ntags"), which the screen reads back even
    // after the manga leaves the library.
    private suspend fun mangaTitleFor(mangaId: Long): String? =
        mangaContextProvider(mangaId)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
    // KMK <--

    suspend fun translatePage(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
        sourceLangHint: String = "JA",
    ): ByteArray? = withContext(AppDispatchersHolder.get().io) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = resolver.targetLanguage()
        val model = effectiveModel()
        val promptFingerprint = prefs.promptFingerprint()
        pageCache.lookup(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            imageBytes = imageBytes,
            targetLang = targetLang,
            model = model,
            promptFingerprint = promptFingerprint,
            identity = pageCacheIdentity(),
        )?.let { hit ->
            // A content-hash hit is copied into the saved-page store so the next visit finds it there.
            if (hit.source == PageCacheSource.CONTENT_HASH && pageCache.autoPersistEnabled) {
                pageCache.storePage(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    webp = hit.bytes,
                    mangaTitle = mangaTitleFor(mangaId),
                    promptFingerprint = promptFingerprint,
                    identity = pageCacheIdentity(),
                )
            }
            status.pageCached(mangaId, chapterId, pageIndex)
            return@withContext hit.bytes
        }

        // Validated before enqueueing: the queue's overflow cap drops the oldest pending page, which
        // is no reason to throw away the page being submitted.
        PageImageValidator.sizeRejection(imageBytes)?.let { reason ->
            status.pageError(mangaId, chapterId, pageIndex, friendlyError(reason))
            return@withContext null
        }

        pageQueue.submit(mangaId, chapterId, pageIndex, imageBytes, sourceLangHint)
    }

    /** The actual pipeline for one page - only ever called in page order by [PageQueue]. */
    private suspend fun translatePageInternal(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        sourceLangHint: String,
    ): ByteArray? {
        val targetLang = resolver.targetLanguage()
        val model = effectiveModel()
        val identity = pageCacheIdentity()
        val promptPolicy = prefs.promptPolicy()
        val promptFingerprint = promptPolicy.fingerprint()
        val glossary = promptPolicy.glossary
        val localModel = if (localLlm.isLocalProvider()) localLlm.resolveModel() else null
        val breadcrumbBudget = localModel?.let { (it.contextLength * 0.25).toInt().coerceIn(500, 3000) } ?: 1000
        val rawBreadcrumb = notes.buildContextPrompt(mangaId, breadcrumbBudget)
        val breadcrumb = if (rawBreadcrumb.length > breadcrumbBudget * 1.2) {
            val trimmed = rawBreadcrumb.takeLast(breadcrumbBudget)
            val cut = trimmed.indexOf('\n')
            if (cut in 0..200) trimmed.substring(cut + 1) else trimmed
        } else {
            rawBreadcrumb
        }
        val mangaContext = mangaContextProvider(mangaId) ?: ""
        // KMK --> Reuse the already-fetched grounding for the cache sidecar (no extra lookup).
        val mangaTitle = mangaContext.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        // KMK <--

        status.pageTranslating(mangaId, chapterId, pageIndex)

        val useMangaTranslator = prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", true)
        if (useMangaTranslator) {
            try {
                val webp = mangaTranslator.translateImageToWebP(imageBytes, targetLang, prefs.effectiveModel().takeIf { it.isNotBlank() })
                if (webp != null && webp.isNotEmpty()) {
                    pageCache.store(imageBytes, targetLang, model, webp, promptFingerprint)
                    if (pageCache.persistEnabled) {
                        pageCache.storePage(mangaId, chapterId, pageIndex, webp, mangaTitle, promptFingerprint, identity)
                    }
                    try {
                        notes.appendFromTranslation(mangaId, chapterId, listOf("[mangatranslator]"))
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    return webp
                } else {
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError("MangaTranslator returned empty result"))
                    return null
                }
            } catch (e: TranslationException) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return null
            } catch (e: Exception) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return null
            }
        }

        var bitmap: Bitmap? = null
        return try {
            val sampleSize = when (val check = PageImageValidator.check(imageBytes, prefs.longPageSlicingEnabled().get())) {
                is PageImageCheck.Rejected -> {
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError(check.reason))
                    return null
                }
                is PageImageCheck.Ok -> check.sampleSize
            }
            val sampleOpts = if (sampleSize > 1) {
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            } else {
                null
            }
            val decoded = try {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampleOpts)
            } catch (e: OutOfMemoryError) {
                System.gc()
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("not enough memory"))
                return null
            } catch (e: Exception) {
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("Unable to decode image"))
                return null
            }
            if (decoded == null || decoded.isRecycled) {
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("Unable to decode image"))
                return null
            }
            bitmap = decoded

            // KMK -->
            // Gemini Nano (on-device) is the priority LLM provider when the toggle is on and
            // the device has the model available; otherwise fall back to the cloud provider.
            // Built as a factory over the slice bitmap: the engine may split a tall page into
            // overlapping slices, and each provider must see its own slice (vision context and
            // JPEG bytes are generated per slice, never from the whole page).
            val active = resolver.resolve()
            val translatorFactory: suspend (Bitmap) -> Translator = { sliceBitmap ->
                when (active.kind) {
                    TranslationProviderKind.GEMINI_NANO -> {
                        object : Translator {
                            override suspend fun translate(queries: List<String>): List<String> =
                                geminiNano.translate(
                                    queries = queries,
                                    pageBitmap = sliceBitmap,
                                    sourceLang = sourceLangHint,
                                    offlineFallback = prefs.offlineFallback().get(),
                                )
                        }
                    }
                    TranslationProviderKind.LOCAL_GGUF -> {
                        LocalLlmTranslator(
                            manager = localLlm,
                            sourceLang = sourceLangHint,
                            targetLang = targetLang,
                            breadcrumb = breadcrumb,
                            mangaContext = mangaContext,
                            pageBitmap = sliceBitmap,
                            offlineFallback = prefs.offlineFallback().get(),
                            glossary = glossary,
                            policy = promptPolicy,
                        )
                    }
                    TranslationProviderKind.CLOUD -> {
                        val jpegBytes = runCatching { PageImageEncoder.toJpeg(sliceBitmap) }
                            .getOrNull()
                            ?.takeIf { it.size in 1..3_000_000 }
                        YakuyomiTranslator(
                            apiKey = prefs.effectiveApiKey(),
                            sourceLang = sourceLangHint,
                            targetLang = targetLang,
                            breadcrumb = breadcrumb,
                            mangaContext = mangaContext,
                            provider = active.providerName,
                            model = active.modelName,
                            offlineFallback = prefs.offlineFallback().get(),
                            client = client,
                            customBaseUrl = prefs.customBaseUrl().get(),
                            customHeaders = prefs.customHeaders().get(),
                            pageImageBytes = jpegBytes,
                            glossary = glossary,
                            policy = promptPolicy,
                        )
                    }
                    // Unreachable: an image service returns from the branch above before the page
                    // is decoded. Present so the `when` stays exhaustive as providers are added.
                    TranslationProviderKind.IMAGE_SERVICE ->
                        throw TranslationException("Image service handled before page decode")
                }
            }
            // KMK <--

            val currentBitmap = checkNotNull(bitmap)
            // KMK -->
            val pageTimeoutMs = if (prefs.longPageSlicingEnabled().get() &&
                LongPageSlicer.shouldSlice(currentBitmap.width, currentBitmap.height)
            ) {
                600_000L
            } else {
                90_000L
            }
            // KMK <--
            val result = try {
                withTimeout(pageTimeoutMs) { engine.translatePage(currentBitmap, translatorFactory, targetLang) }
            } catch (e: TimeoutCancellationException) {
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("Translation timed out"))
                runCatching { currentBitmap.recycle() }
                bitmap = null
                return null
            }
            runCatching { currentBitmap.recycle() }
            bitmap = null
            when (result) {
                is PageResult.Translated -> {
                    val webp = PageImageEncoder.toWebp(result.page, quality = 85)
                    status.pageStage(mangaId, chapterId, pageIndex, lastStageReached(result.stats))
                    runCatching { result.page.recycle() }
                    pageCache.store(imageBytes, targetLang, model, webp, promptFingerprint)
                    if (pageCache.persistWhileReadingEnabled) {
                        pageCache.storePage(mangaId, chapterId, pageIndex, webp, mangaTitle, promptFingerprint, identity)
                    }
                    val translatedTexts = result.analysis?.regions?.map { it.translatedText } ?: emptyList()
                    try {
                        notes.appendFromTranslation(mangaId, chapterId, translatedTexts)
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    webp
                }
                is PageResult.Skipped -> {
                    // The engine's reason and code are what let the UI distinguish "nothing on this
                    // page" from "detection failed"; both are recorded verbatim.
                    val stage = stageForFailure(result.code)
                    if (stage != TranslationStatus.PipelineStage.IDLE) {
                        status.pageStage(mangaId, chapterId, pageIndex, stage)
                    }
                    status.pageSkipped(mangaId, chapterId, pageIndex, reason = result.reason, code = result.code.name)
                    null
                }
                is PageResult.Failed -> {
                    val stage = stageForFailure(result.code)
                    if (stage != TranslationStatus.PipelineStage.IDLE) {
                        status.pageStage(mangaId, chapterId, pageIndex, stage)
                    }
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError(result.reason), code = result.code.name)
                    null
                }
            }
        } catch (e: Exception) {
            runCatching { bitmap?.recycle() }
            bitmap = null
            xLogE("translatePage failed", e)
            status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "Unknown translation error"))
            null
        }
    }
}
