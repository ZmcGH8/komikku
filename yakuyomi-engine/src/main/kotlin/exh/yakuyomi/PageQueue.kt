package exh.yakuyomi

import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mihon.core.concurrency.AppDispatchersHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

private typealias ChapterKey = Pair<Long, Long>

/**
 * Runs on-the-fly translation one page at a time, in page order, per chapter.
 *
 * Pages are submitted from several places at once (reader decode workers, retries, the download
 * worker). Without ordering, page 5 can reach the model and swap in before page 2, which scrambles
 * the breadcrumb context the translator is given and reads as flicker. So each chapter gets a
 * skip-list keyed by page index and a single worker drains it in order.
 *
 * [worker] is the per-page pipeline. This class owns only ordering, lifecycle and the pending cap;
 * everything about how a page is translated stays with the caller.
 */
class PageQueue(
    private val worker: suspend (
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        sourceLangHint: String,
    ) -> ByteArray?,
    private val maxPendingPerChapter: Int = MAX_PENDING_PER_CHAPTER,
    private val timeoutMs: Long = TRANSLATE_TIMEOUT_MS,
) {
    private class PendingTranslation(
        val imageBytes: ByteArray,
        val sourceLangHint: String,
        val deferred: CompletableDeferred<ByteArray?>,
    )

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().io)
    private val pending = ConcurrentHashMap<ChapterKey, ConcurrentSkipListMap<Int, PendingTranslation>>()
    private val workers = ConcurrentHashMap<ChapterKey, Job>()

    /**
     * Chapters whose worker must not be respawned. Held as a set rather than a flag on the worker so
     * that cancelling a worker and cancelling its replacement are distinguishable.
     */
    private val paused = ConcurrentHashMap.newKeySet<ChapterKey>()

    /**
     * Queues [pageIndex] and suspends until it has been translated.
     *
     * A resubmission of the same page replaces the earlier one and cancels its waiter, since a
     * second request for a page already queued means the first result is no longer wanted. When the
     * chapter is at its cap the oldest pending page is dropped, so a stalled page cannot wedge the
     * rest of the chapter behind it.
     */
    suspend fun submit(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        sourceLangHint: String,
    ): ByteArray? {
        val key = mangaId to chapterId
        // A paused chapter must not accept work: enqueueing anyway would park the page until the
        // await timed out, so every page the reader binds after a pause would block for two
        // minutes. Returning null is the paused answer - the caller keeps the original page.
        if (key in paused) return null
        val deferred = CompletableDeferred<ByteArray?>()
        // The put happens inside compute(), under the map's lock for this key. A worker retires the
        // chapter by checking-and-removing the same key atomically, so a page can no longer land in
        // a map that has just been unregistered - which used to leave its deferred uncompleted and
        // the caller blocked for the full timeout.
        pending.compute(key) { _, current ->
            val queue = current ?: ConcurrentSkipListMap()
            if (queue.size >= maxPendingPerChapter) {
                val oldest = queue.firstKey()
                queue.remove(oldest)?.deferred?.complete(null)
                xLogE("MTL queue overflow: dropped page $oldest for chapter ${key.second} (queue size ${queue.size})")
            }
            val replaced = queue.put(pageIndex, PendingTranslation(imageBytes, sourceLangHint, deferred))
            replaced?.let { if (!it.deferred.isCompleted) it.deferred.cancel() }
            queue
        }
        ensureWorker(key)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogE("page queue await failed", e)
            null
        }
    }

    /** Drops everything queued for a chapter and stops its worker. Waiters are cancelled. */
    fun cancel(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        paused.remove(key)
        pending.remove(key)?.values?.forEach { entry -> runCatching { entry.deferred.cancel() } }
        workers.remove(key)?.cancel()
    }

    /**
     * Stops the worker but leaves queued pages, so [resume] can continue where it stopped.
     *
     * The chapter has to be recorded as paused, not just have its worker cancelled: the worker's
     * own `finally` respawns a replacement whenever pages remain queued, which is what keeps a
     * chapter draining across a page-boundary race - and it would immediately undo the pause.
     */
    fun pause(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        paused.add(key)
        workers.remove(key)?.cancel()
    }

    fun resume(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        paused.remove(key)
        if (pending[key]?.isNotEmpty() == true) ensureWorker(key)
    }

    /**
     * Cancels every chapter's work and returns the chapter keys that had anything pending, as
     * (mangaId, chapterId) pairs. The key type is file-private, so the public signature spells the
     * underlying pair out.
     */
    fun cancelAll(): List<Pair<Long, Long>> {
        val keys = pending.keys.toList()
        keys.forEach { (mangaId, chapterId) -> cancel(mangaId, chapterId) }
        return keys
    }

    private fun ensureWorker(key: ChapterKey) {
        if (key in paused) return
        workers.computeIfAbsent(key) {
            scope.launch {
                try {
                    while (true) {
                        val queue = pending[key] ?: break
                        val first = queue.firstEntry() ?: break
                        val pageIndex = first.key
                        val job = first.value
                        val result = runCatching {
                            worker(key.first, key.second, pageIndex, job.imageBytes, job.sourceLangHint)
                        }.getOrElse { e ->
                            xLogE("page queue worker failed at page $pageIndex", e)
                            null
                        }
                        if (!job.deferred.isCompleted) job.deferred.complete(result)
                        queue.remove(pageIndex)
                        if (queue.isEmpty() && retire(key, queue)) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    xLogE("page queue worker crashed", e)
                    // Unblock every waiter for this chapter rather than leaving them to time out.
                    pending.remove(key)?.values?.forEach { entry ->
                        if (!entry.deferred.isCompleted) entry.deferred.complete(null)
                    }
                } finally {
                    workers.remove(key)
                    // Work submitted while this worker was finishing still needs a worker, unless
                    // the chapter was paused in the meantime - that is the whole point of pausing.
                    if (key !in paused && pending[key]?.isNotEmpty() == true) ensureWorker(key)
                }
            }
        }
    }

    /**
     * Unregisters a drained chapter, returning true when this call is the one that retired it.
     *
     * The emptiness check and the removal share the map's lock for this key, which is what makes
     * them atomic with respect to [submit]. Checking emptiness outside the lock is what allowed a
     * page submitted in between to be registered into a queue nobody was left to drain.
     */
    private fun retire(key: ChapterKey, queue: ConcurrentSkipListMap<Int, PendingTranslation>): Boolean =
        pending.computeIfPresent(key) { _, current ->
            if (current === queue && current.isEmpty()) null else current
        } == null

    private companion object {
        const val MAX_PENDING_PER_CHAPTER = 64
        const val TRANSLATE_TIMEOUT_MS = 120_000L
    }
}
