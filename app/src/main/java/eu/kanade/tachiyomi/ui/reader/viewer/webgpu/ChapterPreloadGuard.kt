// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Per-chapter dedup guard for WebGPU chapter preload retries.
 *
 * The viewer's `prev`/`next` page getters are hot paths: they run from every render
 * snapshot (the pager library re-resolves page 0 and its neighbors on each invalidation),
 * from flick/have-next checks during gestures, and from every preload walk. When the
 * adjacent chapter is not loaded yet, each of those hits used to spawn its own
 * preload-and-retry loop, so a single approach toward a chapter boundary could start
 * many concurrent `ChapterLoader.loadChapter` runs (DB + download checks + network page
 * list) plus up to five seconds of polling each - visible as freezing/choppiness exactly
 * at chapter transitions.
 *
 * This guard elects exactly one retry loop per chapter key; [end] releases the key so a
 * later attempt (e.g. after a transient failure or the 5s give-up) can retry. All
 * operations are thread-safe: callers span the main thread and background dispatchers.
 *
 * [tryBegin] and [tryBeginOrRequeueIfStale] are both ways in and both funnel through the same
 * hardening, so neither can be reached for in a way that lets the key set grow without bound - the
 * expiry sweep and the cap live in one private step rather than in whichever entry point was
 * written last.
 */
class ChapterPreloadGuard {

    private val inFlight = HashSet<String>()
    private val timestamps = HashMap<String, Long>()

    /**
     * Marks [key] as being preloaded. Returns true for the caller that won the right to
     * run, false for every concurrent duplicate while the key is in flight.
     */
    @Synchronized
    fun tryBegin(key: String): Boolean {
        if (!isUsable(key)) return false
        return beginLocked(key)
    }

    /**
     * [tryBegin], except that a key already in flight is released first when [isStale] says its
     * work is no longer queued - the case where the holder gave up but left the key marked, and the
     * chapter would otherwise never preload again.
     *
     * The staleness test runs while the lock is held, so it must not call back into the guard.
     */
    @Synchronized
    fun tryBeginOrRequeueIfStale(key: String, isStale: () -> Boolean): Boolean {
        if (!isUsable(key)) return false
        if (key in inFlight && isStale()) {
            inFlight.remove(key)
            timestamps.remove(key)
        }
        return beginLocked(key)
    }

    /**
     * Adds [key], applying the expiry sweep and the cap first.
     *
     * Private and lock-held, so both entry points get the same bound on the key set. When the cap
     * is hit the expired keys are dropped first; if that frees nothing, everything is dropped rather
     * than refusing the newcomer - a chapter the reader is actually approaching has to be able to
     * preload, and the keys being discarded are ones whose holder stopped renewing them anyway.
     */
    private fun beginLocked(key: String): Boolean {
        if (inFlight.size >= MAX_TRACKED_KEYS) {
            val now = System.currentTimeMillis()
            val expired = timestamps.filter { now - it.value > STALE_KEY_MS }.keys
            expired.forEach {
                inFlight.remove(it)
                timestamps.remove(it)
            }
            if (inFlight.size >= MAX_TRACKED_KEYS) {
                inFlight.clear()
                timestamps.clear()
            }
        }
        val added = inFlight.add(key)
        if (added) timestamps[key] = System.currentTimeMillis()
        return added
    }

    private fun isUsable(key: String): Boolean = key.isNotBlank() && key.length <= MAX_KEY_LENGTH

    @Synchronized
    fun isInFlight(key: String): Boolean = key in inFlight

    /** Releases [key]. Idempotent and safe for keys that never began. */
    @Synchronized
    fun end(key: String) {
        inFlight.remove(key)
        timestamps.remove(key)
    }

    @Synchronized
    fun clear() {
        inFlight.clear()
        timestamps.clear()
    }

    companion object {
        /** The key is a chapter URL, so it is untrusted input as far as this map is concerned. */
        private const val MAX_KEY_LENGTH = 512

        /** Bound on tracked keys, for a viewer torn down without releasing them. */
        private const val MAX_TRACKED_KEYS = 32

        /** How long a key may sit before it is assumed its holder stopped renewing it. */
        private const val STALE_KEY_MS = 10_000L
    }
}
// KMK <--
