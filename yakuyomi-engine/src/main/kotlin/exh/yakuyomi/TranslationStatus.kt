package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
/**
 * Runtime status of the MTL (Machine Translation) pipeline, keyed per chapter.
 *
 * The reader UI observes [chapters] to surface "is this chapter translating?" and
 * "how far along / did it fail?" without reaching into the viewer internals.
 * State is process-lifetime only; cache persistence is handled by [TranslationCache].
 */
@SingleIn(AppScope::class)
@Inject
class TranslationStatus {

    enum class PageState {
        /** Translation request is in-flight for this page. */
        TRANSLATING,

        /** Page was translated and baked successfully. */
        DONE,

        /** Translated image was served from cache. */
        CACHED,

        /** No actionable text detected; page passed through unchanged. */
        SKIPPED,

        /** Translation failed for this page. */
        ERROR,
    }

    /**
     * Which part of the native pipeline is currently working on a page.
     *
     * Reported rather than inferred: the UI used to derive a stage from page counts, which said
     * nothing about the page actually in flight (a single slow page read as "Translating" forever).
     * [IDLE] means no page is mid-flight, so the last [value] is kept for display after completion.
     */
    enum class PipelineStage {
        IDLE,
        DETECTING,
        OCR,
        TRANSLATING,
        INPAINTING,
        TYPESETTING,
    }

    data class PageStatus(
        val pageIndex: Int,
        val state: PageState,
        val error: String? = null,
        /** Stage this page reached. Retained after completion so a finished page can explain itself. */
        val stage: PipelineStage = PipelineStage.IDLE,
        /** Engine error code for [PageState.ERROR], or for a [PageState.SKIPPED] that failed a stage. */
        val code: String? = null,
        /** Why the engine skipped the page, when it did. */
        val skipReason: String? = null,
    ) {

        /**
         * Whether attempting this page again could plausibly produce a translation.
         *
         * A skip is not one outcome. The engine reports the same state for "this page genuinely has
         * no translatable text" and for "the provider never answered and the offline fallback
         * handed back the source lines" — and only the second is worth retrying. [code] is what
         * separates them: detection and OCR produced nothing about the image, so re-running the
         * identical inputs will produce nothing again, whereas a filter/translate rejection can
         * clear once the provider is reachable.
         *
         * Without this distinction a single dropped connection permanently skips every remaining
         * page of the chapter: nothing is ever marked as failed, so there is no error to retry and
         * no red chapter to prompt one.
         */
        val isRetryable: Boolean
            get() = when (state) {
                PageState.ERROR -> true
                PageState.SKIPPED -> code != null && code in RETRYABLE_SKIP_CODES
                else -> false
            }

        private companion object {
            /** Engine codes where the provider, not the page, was the obstacle. */
            val RETRYABLE_SKIP_CODES = setOf("ALL_FILTERED", "TRANSLATE_FAILED", "TIMEOUT")
        }
    }

    data class ChapterStatus(
        val mangaId: Long,
        val chapterId: Long,
        val totalPages: Int = 0,
        val pages: Map<Int, PageStatus> = emptyMap(),
        val lastError: String? = null,
        val lastCompletedAt: Long = 0L,
        val lastUpdated: Long = 0L,
        /**
         * Stage of the page currently being worked on, or [PipelineStage.IDLE] when none is.
         *
         * Kept at chapter level rather than derived from [pages] because several pages can be in
         * flight at once; this is the most recently started one.
         */
        val activeStage: PipelineStage = PipelineStage.IDLE,
    ) {
        val isTranslating: Boolean
            get() = pages.values.any { it.state == PageState.TRANSLATING }

        /** Pages that actually received a translated/rendered image (DONE + CACHED). */
        val translatedCount: Int
            get() = pages.values.count { it.state in TRANSLATED_STATES }

        /** Pages with no actionable text (kept original; not an error, not a translation). */
        val skippedCount: Int
            get() = pages.values.count { it.state == PageState.SKIPPED }

        /** Legacy alias: everything processed that "didn't error", including skipped pages. */
        val doneCount: Int
            get() = pages.values.count { it.state in DONE_STATES }

        val errorCount: Int
            get() = pages.values.count { it.state == PageState.ERROR }

        val hasTranslatedPages: Boolean
            get() = translatedCount > 0

        /** Number of pages that still need a translated (or cached) result. */
        val pendingCount: Int
            get() = (totalPages - translatedCount).coerceAtLeast(0)

        companion object {
            private val TRANSLATED_STATES = setOf(PageState.DONE, PageState.CACHED)
            private val DONE_STATES = setOf(PageState.DONE, PageState.CACHED, PageState.SKIPPED)
        }
    }

    private val _chapters = MutableStateFlow<Map<Pair<Long, Long>, ChapterStatus>>(emptyMap())
    val chapters: StateFlow<Map<Pair<Long, Long>, ChapterStatus>> = _chapters.asStateFlow()

    fun chapterStatus(mangaId: Long, chapterId: Long): ChapterStatus? = _chapters.value[mangaId to chapterId]

    /**
     * Declares the known page count for a chapter before translation starts. Without this,
     * [ChapterStatus.totalPages] stays 0 and UI progress clamps to 0%.
     */
    fun setTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) = update(mangaId, chapterId) {
        it.copy(
            totalPages = totalPages.coerceAtLeast(it.pages.size),
            lastUpdated = now(),
        )
    }

    fun pageTranslating(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastUpdated = now(),
            activeStage = PipelineStage.DETECTING,
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.TRANSLATING)),
        )
    }

    /**
     * Reports that [pageIndex] has reached [stage]. Call this as the pipeline advances; it is the
     * only way the UI learns what a page is actually doing rather than inferring it from counts.
     */
    fun pageStage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        stage: PipelineStage,
    ) = update(mangaId, chapterId) {
        it.copy(
            lastUpdated = now(),
            activeStage = stage,
            pages = it.pages + (pageIndex to (it.pages[pageIndex] ?: PageStatus(pageIndex, PageState.TRANSLATING)).copy(stage = stage)),
        )
    }

    fun pageDone(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        val prev = it.pages[pageIndex]
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = null,
            lastCompletedAt = now(),
            lastUpdated = now(),
            activeStage = nextActiveStage(it, pageIndex),
            pages = it.pages + (pageIndex to (prev ?: PageStatus(pageIndex, PageState.DONE)).copy(state = PageState.DONE, error = null)),
        )
    }

    fun pageCached(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        val prev = it.pages[pageIndex]
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = null,
            lastCompletedAt = now(),
            lastUpdated = now(),
            activeStage = nextActiveStage(it, pageIndex),
            pages = it.pages + (pageIndex to (prev ?: PageStatus(pageIndex, PageState.CACHED)).copy(state = PageState.CACHED, error = null)),
        )
    }

    /**
     * Records a page the engine chose not to translate. [reason] and [code] are what let the UI say
     * "no text on this page" rather than a generic failure, so they are not optional in practice.
     */
    fun pageSkipped(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        reason: String? = null,
        code: String? = null,
    ) = update(mangaId, chapterId) {
        val prev = it.pages[pageIndex]
        it.copy(
            lastUpdated = now(),
            activeStage = nextActiveStage(it, pageIndex),
            pages = it.pages + (
                pageIndex to (prev ?: PageStatus(pageIndex, PageState.SKIPPED)).copy(
                    state = PageState.SKIPPED,
                    error = null,
                    skipReason = reason,
                    code = code,
                )
                ),
        )
    }

    fun pageError(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        error: String,
        totalPages: Int = 0,
        code: String? = null,
    ) = update(mangaId, chapterId) {
        val prev = it.pages[pageIndex]
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = error,
            lastUpdated = now(),
            activeStage = nextActiveStage(it, pageIndex),
            pages = it.pages + (
                pageIndex to (prev ?: PageStatus(pageIndex, PageState.ERROR)).copy(
                    state = PageState.ERROR,
                    error = error,
                    code = code,
                )
                ),
        )
    }

    /**
     * The chapter is only idle once no page is in flight. When [finished] is one of several
     * translating pages, the stage falls back to the most recently started of the rest, so the
     * overlay keeps moving instead of flickering to idle between pages.
     */
    private fun nextActiveStage(status: ChapterStatus, finished: Int): PipelineStage =
        status.pages.entries
            .firstOrNull { (index, page) -> index != finished && page.state == PageState.TRANSLATING }
            ?.value?.stage
            ?: PipelineStage.IDLE

    fun resetChapter(mangaId: Long, chapterId: Long) {
        _chapters.update { it - (mangaId to chapterId) }
    }

    fun updateForRetry(mangaId: Long, chapterId: Long, pages: Set<Int>) {
        _chapters.update { map ->
            val key = mangaId to chapterId
            val cur = map[key] ?: return@update map
            val newPages = cur.pages.filterKeys { it !in pages }
            map + (key to cur.copy(pages = newPages, lastError = null, lastUpdated = now()))
        }
    }

    fun clearAll() {
        _chapters.value = emptyMap()
    }

    private fun update(mangaId: Long, chapterId: Long, transform: (ChapterStatus) -> ChapterStatus) {
        _chapters.update { map ->
            val key = mangaId to chapterId
            val current = map[key] ?: ChapterStatus(mangaId = mangaId, chapterId = chapterId)
            map + (key to transform(current))
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
