package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Which store a cached page came from. */
enum class PageCacheSource {
    /** The per-series saved-page store, keyed by chapter and page index. */
    SAVED_PAGE,

    /** The content-addressed store, keyed by a hash of the page plus the translation policy. */
    CONTENT_HASH,
}

data class PageCacheHit(val bytes: ByteArray, val source: PageCacheSource)

/**
 * The single read/write path for translated pages, covering both the saved-page store and the
 * content-addressed cache.
 *
 * Two callers read a cached page and two write one, and the pairs had drifted: one reader promoted
 * a cache hit into the saved-page store and the other did not, and the two writers disagreed about
 * when a page should be persisted at all. This class owns the storage mechanics and exposes the two
 * persistence policies as named predicates so a call site states which one it means rather than
 * re-deriving a boolean expression.
 */
@SingleIn(AppScope::class)
@Inject
class PageResultCache(
    private val prefs: TranslationPreferences,
    private val cache: TranslationCache,
    private val pageStore: TranslatedPageStore,
) {
    val isEnabled: Boolean
        get() = prefs.cacheEnabled().get()

    /**
     * Whether pages are eligible for the saved-page store at all: either the user asked to keep
     * translated pages, or the image service keeps them permanently.
     */
    val persistEnabled: Boolean
        get() = prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()

    /**
     * Whether a page found in the content-addressed cache should be copied into the saved-page
     * store, so it is found there next time.
     */
    val autoPersistEnabled: Boolean
        get() = (prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) ||
            prefs.mangaTranslatorCachePermanent().get()

    /**
     * Whether a freshly translated page is persisted. Deliberately narrower than
     * [persistEnabled]: the main pipeline only keeps pages when the user asked to save them *and*
     * auto-save is on, whereas the image service persists whenever either applies. These two
     * policies are not the same and both are intentional, so they are named separately rather than
     * folded into one predicate.
     */
    val persistWhileReadingEnabled: Boolean
        get() = prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()

    /**
     * Saved-page store first, then the content-addressed cache. The page hash is only computed when
     * the cache is enabled, since hashing the whole page is not free.
     */
    fun lookup(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        targetLang: String,
        model: String,
        promptFingerprint: String,
        identity: String,
    ): PageCacheHit? {
        if (persistEnabled) {
            pageStore.loadIfExists(
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex,
                expectedPolicyFingerprint = promptFingerprint,
                expectedIdentity = identity,
            )?.let { bytes ->
                if (bytes.isNotEmpty()) return PageCacheHit(bytes, PageCacheSource.SAVED_PAGE)
            }
        }
        if (!isEnabled) return null
        val file = cache.getIfExists(cache.pageHash(imageBytes), targetLang, model, promptFingerprint)
            ?: return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        return if (bytes.isEmpty()) null else PageCacheHit(bytes, PageCacheSource.CONTENT_HASH)
    }

    /**
     * Writes to the content-addressed cache. Takes the original page bytes rather than a precomputed
     * hash so the caller never pays for hashing a page it may not cache. Storage failures are
     * swallowed: a page that cannot be cached is still worth showing.
     */
    fun store(imageBytes: ByteArray, targetLang: String, model: String, webp: ByteArray, promptFingerprint: String) {
        if (!isEnabled) return
        runCatching { cache.put(cache.pageHash(imageBytes), targetLang, model, webp, promptFingerprint) }
    }

    /** Writes to the saved-page store. Callers decide which [persistEnabled] / [autoPersistEnabled]
     *  policy applies; see the note on the class about the two policies currently differing. */
    fun storePage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        webp: ByteArray,
        mangaTitle: String?,
        promptFingerprint: String,
        identity: String,
    ) {
        runCatching {
            pageStore.save(mangaId, chapterId, pageIndex, webp, mangaTitle, promptFingerprint, identity)
        }
    }
}
