// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Watches live memory and moves the reader's caches to match, shrinking and recovering on its own.
 *
 * This is the fourth long-lived coroutine, and it exists because `onTrimMemory` is not enough.
 * The system only sends a trim callback when it is already deciding about *some* process, which
 * means by the time one arrives the reader has usually already paid for the spike. The failure
 * this prevents is concrete: one webtoon segment costs ~47MB of texture, and cutting eight of them
 * back to back is a ~380MB swing that no trim signal necessarily precedes.
 *
 * ### Why it shrinks fast and recovers slowly
 *
 * Shrinking costs a rebuild that tiles refill out of, so it is cheap enough to do eagerly. Growing
 * costs the same rebuild, and a reader that grows and shrinks on alternating samples oscillates -
 * every transition a visible flash of un-tiled page. So recovery is gated behind both a cooldown
 * and a genuinely better pressure reading ([RECOVER_PRESSURE]), not merely "not TIGHT any more".
 * The two dials are the same split the stuck-page ladder uses: cooldown and escalation are
 * separate concerns, and resetting one must not reset the other.
 *
 * ### What it deliberately does not do
 *
 * It never touches the decode queue. Retracting work a page was already promised is how you get a
 * page stuck with `wantedByRender` set and nothing queued behind it - see the stuck-page ladder.
 * It only ever *drops* decoded shells and resizes the renderer, both of which are recoverable by
 * re-decode.
 */
internal fun WebGpuViewer.startMemoryMonitor() {
    // First sample immediately: a reader opened into an already-shrunken process must not run a
    // whole chapter at the device ceiling before the first tick.
    applyMemoryBudget("open")
    scope.launch {
        try {
            while (!isDestroyed) {
                val pressure = memoryBudget.budget().pressure
                // Tighter pressure means sampling harder, because that is when the answer changes.
                delay(if (pressure == WebGpuMemoryBudget.Pressure.ROOMY) ROOMY_INTERVAL_MS else TIGHT_INTERVAL_MS)
                if (isDestroyed) break
                applyMemoryBudget("poll")
            }
        } catch (_: CancellationException) {
            // Normal teardown.
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Memory monitor stopped" }
        }
    }
}

/**
 * Samples the device once and moves the caches toward the answer.
 *
 * Idempotent by construction - every action below is "make the cache match this budget", so
 * running it when nothing changed is a few volatile reads and no GPU work. That matters because it
 * is also the path `onTrimMemory` takes, and a trim callback can arrive at any time.
 */
internal fun WebGpuViewer.applyMemoryBudget(reason: String) {
    if (isDestroyed) return
    val budget = memoryBudget.sample()

    try {
        // The renderer's half. Always pushed, every time - it is a single volatile assignment and
        // the renderer ignores a value equal to the one it already has, so there is no churn in
        // having it react to everything rather than only to the tiers.
        pager.state.setCacheByteBudget(budget.tileCacheBytes)
    } catch (_: Exception) {
        // The surface may not exist yet, or may be mid-teardown. The budget is re-pushed next tick.
    }

    val underPressure = memoryBudget.shouldShrinkDecodedPages()
    val previous = memoryPressureLast
    memoryPressureLast = budget.pressure

    if (underPressure) {
        memoryLastShrinkAt = SystemClock.elapsedRealtime()
        shrinkCacheOnTrim()
        if (previous != budget.pressure) logMemoryBudget("$reason -> ${budget.describe()}")
        return
    }

    // Recovery. Both gates, and the cooldown is the one that is easy to forget: without it a
    // device hovering on the TIGHT boundary rebuilds its whole page cache several times a minute.
    val sinceShrink = SystemClock.elapsedRealtime() - memoryLastShrinkAt
    if (budget.pressure <= RECOVER_PRESSURE && sinceShrink > RECOVER_COOLDOWN_MS) {
        try {
            pager.state.restoreMemoryBudget()
        } catch (_: Exception) {
        }
        if (previous != budget.pressure) logMemoryBudget("$reason recovered -> ${budget.describe()}")
    }
}

private const val ROOMY_INTERVAL_MS = 4_000L

/** Half the roomy cadence - tight memory changes fast enough to be worth the extra wakeups. */
private const val TIGHT_INTERVAL_MS = 2_000L

/**
 * How long after a shrink the reader waits before growing anything back.
 *
 * Long enough that a pressure spike with a normal recovery inside it never triggers one. Six
 * samples at the tight cadence.
 */
private const val RECOVER_COOLDOWN_MS = 12_000L

/** Recovery also needs pressure to actually be good, not merely no longer tight. */
private val RECOVER_PRESSURE = WebGpuMemoryBudget.Pressure.ROOMY
// KMK <--
