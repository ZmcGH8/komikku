# viewer/webgpu/ — WebGPU reader pipeline

Debugging-oriented knowledge base. Read this before changing anything in this folder; the
state machines here are spread over nine files and the invariants live in the comments of
the ones that own them.

---

## ⚠ KEEP THIS FILE CURRENT — it is written by its readers

**Every new observation about this pipeline belongs here, in the same commit that made it.**
This file is only as good as the last person who learned something and wrote it down; the
alternative is re-deriving it from a logcat paste on a phone, which is what it exists to
prevent.

Update it whenever — not only when you fix a bug:

- You diagnose a bug, or **fail to** diagnose one and had to work it out by bisection.
- A logcat symptom turns out to mean something other than it appears to.
- You learn that a log line, field or flag does **not** work the way its name suggests.
- A fix here removes an assumption that turned out to be false.
- You add a state, a thread, a recovery path, a cache tier, or a counter.
- You burn real time on something avoidable.

Where it goes:

| Observation | Section |
|---|---|
| A log line and what it actually means | **Symptom table** |
| A rule that is not obvious and breaking it is costly | **Known landmines** |
| New state/thread/cache machinery | the section it belongs to, plus the state diagram |
| A gotcha that cost an afternoon | **Known landmines**, one bullet per incident |
| Anything that would have saved time before you found it | the table it fits in, else a new one |

Two rules that keep it useful:

- **Record what was wrong, not what you changed.** `grep` and `git log` already track the
  diff. Write the thing that is true forever ("an adapter is consumed and cannot be reused")
  rather than the thing that was true for one afternoon ("the spinner returns null now").
- **Don't delete a landmine because it is fixed.** Move it to the landmines list with the
  invariant it protects. The next person will try the same simplification.

If you cannot explain *why* something is true, write the observation anyway and mark it
`UNVERIFIED:` — a labelled unknown is worth more than a confident guess, and it tells the
next person which belief to check first.

---

## THE TWO HALVES

| Half | Where | Owns |
|---|---|---|
| App | `app/.../ui/reader/viewer/webgpu/` | page shells, residency, decode scheduling, GPU upload |
| Library | `external/webgpuviewer-houri` (`:webgpuviewer`) | device, render thread, tile grids, transitions, filters |

The app **never decodes or draws** — it hands the library an `ImagePage` per page and the
library draws it. So "the page is blank" is a question about *which* ImagePage the shell
currently holds, and "nothing renders at all" is almost always the device, not the app.

## THREADS — the mental model everything else hangs off

| Thread | Name | What runs there |
|---|---|---|
| Render | `WebGPU-Render-Thread` | `WebGpuRenderer.dispatcher`. `render()`, `renderPass`, `buildSpreadPage`, tile grids. Guarded by `WebGpuRenderer.mutex`. |
| Decode | `WebGpuViewer-Decode` | `decodeExecutor`. Single thread: one page at a time, all decodes/preloads/byte transforms. |
| Main | `MainScope` | stuck sweep, progress spinner, device-loss handler, `onTrimMemory` → `shrinkCacheOnTrim`, and the **memory monitor** (the fourth coroutine, `startMemoryMonitor`). |
| Default | — | chapter preload/retry only. |

**A field read by both the render thread and main must be `@Volatile`.** `ViewerReaderPage`
gets this right for everything *except* what went wrong — see "Known landmines".

The viewer's `lock` (`Object`) covers `pageCache`, `decodeQueue`, `stuckRecords` and the
`currentPage` swap. It does **not** cover the shell fields, which is why those are volatile.
Never call `cleanup()`-bearing code while holding it — `releasePageResources` is deliberately
lock-free for exactly that reason.

## PAGE STATE MACHINE

```
IDLE ──queueForDecode──▶ QUEUED ──worker pops──▶ DECODING ──▶ (imagePage = ImageSingle)
  ▲                        │                        │
  │                        │                        ├─ fails ─▶ ErrorPage, back to IDLE
  └────────────────────────┴────────────────────────┘
                     LOOPING (bytes absent) ──startPageLoad──▶ LOADING ──Ready──▶ QUEUED
```

**The one invariant:** a page is never in a state `queueForDecode` treats as "someone is
already on it" while nothing actually is. `ensureDecoding` is the one producer (the renderer
asked for it). `requeueStuckPage` is the *only* thing allowed to break that tie.

### `ensureDecoding` is one-shot

```kotlin
if (page.wantedByRender) return
page.wantedByRender = true
```

It queues at most once per shell. `fetchPage` runs every frame for every page in the render
window, so this is what stops a failing page becoming a frame-rate retry loop (each pass
allocating decoder and network buffers). The consequence, and it is load-bearing:

> **A shell that fails is never re-queued by the render walk.** `wantedByRender` is cleared
> only by `releasePageResources` (destroys the shell) or by `requeueStuckPage` giving up.
> An `ErrorPage` is recovered by **tap** (`WebGpuViewer.onTap`), not by re-demand.

If you are looking for "why doesn't this page retry itself", that is the answer — it is by
design, and tap is the escape hatch. `rebuildPageCache` / `resetDecodedPagesAfterDeviceLoss`
/ chapter swap clear the flag en masse.

## STUCK-PAGE LADDER

`startStuckPageSweep` runs off `stuckSignal` (conflated) — not a timer. The orphan condition
cannot arise on its own; it needs a queue/cache mutation, and only a few sites do one.

| Branch | Meaning |
|---|---|
| `queued-but-absent` | QUEUED but not in `decodeQueue` — nothing will ever pop it |
| `loading-done` | LOADING but the bytes already arrived |
| `idle-placeholder` | IDLE + `wantedByRender` + still a `ProgressPage` — the common one |

`requeueStuckPage` then, per page:

- `attempts <= 3` → reset to IDLE and re-queue (`REDRIVEN`)
- `attempts <= 9` → **escalate**: `tearDownStuckPage` drops the shell and its tiles entirely (`REBUILT`)
- `attempts > 9` → give up: clear `wantedByRender`, hand it back to the render walk (`GONE`)
- anything inside the 1500 ms cooldown → `DEFERRED` (re-arm, no log)

The `last=<outcome>` in the log is `decodeOutcomes[pageKey]` — *how the previous decode
ended*, not why the page is stuck. Outcomes: `no-stream`, `no-bytes`, `rejected:*`,
`abandoned`, `decoded`, `threw`, `unfinished`. **`no-stream` means `page.stream` returned
null, not that the decoder failed** — see "Symptom table".

## DEVICE LIFECYCLE — read this before touching recovery

`WebGpuRenderer` is a companion-object singleton: `instance` → `adapter` → `device`, plus a
single render `dispatcher` and `mutex`.

### NEVER reuse an adapter to rebuild a device

A `GPUAdapter` is **consumed** by the first `requestDevice` that succeeds on it. Dawn rejects
a second one outright:

```
adapter is "consumed": it has already been used to create a device
```

So after a device loss you must `instance.requestAdapter(...)` **again** and only then call
`requestDevice`. `recreateDeviceLocked` did reuse the old adapter; nothing else re-requested
one, so `initError` latched and `isAvailable` stayed false **for the life of the process**.
Symptom: one page decodes, the device is lost, and from then on every single decode throws
`IllegalStateException: WebGPU not available` forever.

`deviceGeneration` is bumped on every successful recreation. Anything caching device-bound
objects (filters, mipmaps, the shared pineapple texture) keys on it or on `device` identity.

### What is alive after a loss

`recoverFromDeviceLoss()` → device rebuilt → filters cleaned → swapchain rebuilt from the
retained platform surface. The app's `deviceLostListener` then calls
`resetDecodedPagesAfterDeviceLoss()`, which destroys **every** decoded page, clears the tile
grids, and re-anchors on the current page. Everything device-bound is gone by design; nothing
cached survives.

**Recovery retries, bounded.** `recoverDeviceWithRetries` makes 3 attempts with linear backoff
(250/500/750 ms) before giving up. A loss on a phone is usually backgrounding, and the first
`requestAdapter` frequently fails because the adapter is still being reclaimed — single-shot
recovery meant one unlucky attempt latched `initError` for the life of the process. Bounded, not
indefinite: an unrecoverable device must stop trying rather than spin for the session.

**A requery must evict the segment key, not just its parent.** A split segment's slice is cached
under its own derived key; evicting only the parent re-downloads the parent and then
`ensureSegment` finds the stale slice still cached and hands the same bad bytes back.

## RESOURCE OWNERSHIP

`releasePageResources(page)` is the single owner and the only place that releases a
`ViewerReaderPage`. It frees: spread image, height-match retry job, comparison originals,
retained source bytes, rescale-in-flight flag, `wantedByRender`, and the image page itself.

If you add a field holding a GPU resource or a coroutine **to `ViewerReaderPage`**, add it
here too. Six hand-copied subsets used to exist and had already drifted — that drift is how
`wantedByRender` ended up cleared on the eviction path but not the device-loss path.

Cleanup order matters: the spread and comparison pages are released *before* the image page,
because their cleanup compares their contents against the current one.

## SPLIT RECONCILIATION

A page too tall for the decoder is replaced by segments mid-session, **after** the viewer
built its page graph.

- `ReaderChapter.pageListVersion` is the change signal; `PageListWatch` holds all three
  chapter slots (prev/current/next), not just the current one.
- `syncPageList` is called from `preloadPages` **and** from `continuousPage` — scrolling
  inside a page crosses no chapter, so the preload path never runs, and without the
  `continuousPage` call the strip draws a stale shell under the page being read.
- `supersededBySplit` marks the dead parent. `evictReplacedPages` drops it — nothing else
  will, it is neither idle nor farthest and its key still looks valid.
- `linkedNeighbour` delegates a removed page's neighbour to its replacement, because
  `positionOf` returns -1 and every edge test reads that as a chapter boundary.

## CACHE & EVICTION

`cacheSize = 1 + preloadAhead + preloadBehind + (dualPage ? 3 : 1)`.

`evictFarthestPage` is distance-based (chapter/index math, never a cache chain walk, so gaps
from earlier evictions cannot mismeasure it). Two things override distance:

1. **Drawn-page immunity** — `imagePage.isOnScreen` from the last frame is ground truth.
   The continuous viewer tracks `currentPage` through the library's relative ±1 deltas, so a
   lockstep disagreement mis-centres the window and distance alone destroys visible pages.
2. **Preload-window membership** — pages inside the window survive while anything outside exists.

Ties prefer the cheaper page (`evictionCost`: undecoded < queued/in-flight < decoded), then
oldest insertion order. A page a split just superseded measures `distance == null` and sorts
farthest, which is the safe answer — it is exactly what the replacement is about to replace.

## PAGE LOADING — the half that has no timeout

`startPageLoad` owns bytes: it flips the shell to `LOADING`, launches `loader.loadPage` on
`Dispatchers.IO` **only if the status is still `Queue`**, and collects `statusFlow` until the
page leaves `Queue`/`LoadPage`/`DownloadImage`. Its `finally` resets `LOADING → IDLE` and then
dispatches on the final status (Ready → re-queue, Error → ErrorPage, anything else →
`askLoaderToResolvePage`).

### LOADING has a deadline now (`STUCK_LOAD_DEADLINE_MS`)

The sweep's LOADING branch is now three-way:

| Outcome | Meaning |
|---|---|
| `loading-done` | LOADING but already `Ready` — `queueForDecode` promotes it |
| `loading-stalled` | LOADING past the deadline without reaching `Ready` |
| (null) | genuinely fetching — leave it alone |

`loading-stalled` is the shape that used to be invisible: a page whose load coroutine ended with
the status stranded at `Queue`/`LoadPage`/`DownloadImage` was not queued-but-absent, not
idle-placeholder, and `queueForDecode` treats LOADING as in-flight — so it stayed LOADING for
the rest of the session with no log line at all. **Silence was the only symptom.**

The deadline lives in `WebGpuViewer.loadingSince`, stamped by `startPageLoad`, dropped by the
loader coroutine's `finally`, by `requeueStuckPage`, by `evictFarthestPage`, and wholesale
wherever `stuckRecords.clear()` runs. **UNVERIFIED:** whether `Page.State` can actually strand
at `LoadPage` — a cancelled load coroutine is the candidate, but nothing in this folder cancels
one. The deadline is worth keeping regardless: it turns a silent permanent wedge into a bounded
retry whatever the trigger turns out to be.

### `retryPage` is not eviction — a repeat `no-stream` escalates

`askLoaderToResolvePage` is the first `no-stream` recovery and it calls `loader.retryPage`.
Per `WebGpuPageRefetch.kt`'s header: `HttpPageLoader.internalLoadPage` **skips the download
when `chapterCache.isImageInCache` is true**. Correct for a network failure, useless when the
cached bytes are themselves the defect.

So the second `no-stream` for a page now escalates to `requerySuspectPage`, which evicts the
cache entry *before* re-queuing — the only thing that produces fresh bytes. The bound is
`MAX_SOURCE_REQUERIES = 1`, so a genuinely broken source is asked once and then left to fail
visibly rather than amplified.

Note the ordering: `lastDecodeOutcome` read inside the `no-stream` branch is the **previous**
attempt, because this attempt's outcome is recorded by the worker after `decodeReaderPage`
returns.

### `retryPage` was a no-op for exactly the pages that needed it

The recovery above is built on `loader.retryPage`, and for a **split segment** that call did
nothing at all — which is why a long strip could log `Re-driving ... last=no-stream` on the
same segment every 1.5 s for the rest of the session.

Three facts compose into the trap:

1. A segment is constructed with `status = Page.State.Ready` (`splitOversizedPage`), and its
   `stream` is a lambda reading a **cache file** the LRU evicts independently of the page
   list. So an evicted segment is *Ready but unreadable*.
2. `HttpPageLoader.retryPage` only reset `Error → Queue`.
3. The loader's collector filters on it: `.filter { it.status == Page.State.Queue }`, and
   `boostPage` checks the same thing.

So the retry offered a `Ready` page to the queue, the collector discarded it, `loadSegment`
never ran, and the segment's file was never re-cut. Every retry changed nothing while
reporting itself as an attempt. `retryPage` now resets anything not already `Queue`.

This is the same shape as the `retryPage`-is-not-eviction landmine above, one level up: the
call did not even *reach* the code that would have been a no-op.

### A re-query used to erase its own escalation ladder

`requerySuspectPage` cleared `stuckRecords.remove(pageKey(page))` to escape the ladder's
cooldown. Deleting the entry also reset `attempts` to 0, so a page whose re-query did not fix
it went back to attempt 1 every time and could never reach the terminal `GONE` state — an
unbounded spin, not a bounded retry, and no "Giving up on" line to show for it. It now clears
`lastAttemptAt` (the cooldown) and leaves `attempts` alone.

> **Rule:** cooldown and escalation are separate dials. Resetting a page for genuinely new
> bytes is a *fresh attempt*, never a *forgotten history*.

### A segment's slice identity must not live only in its URL suffix

`loadSegment` read which slice to re-cut from the `#segment-N` suffix on `imageUrl`, defaulting
to **0** when the suffix was absent. But `retryPage` clears `imageUrl` outright on E-H based
sources so the source re-resolves it — and a re-resolved URL has no suffix. Every segment of a
strip then resolved to slice 0, so a long strip drew as N copies of its own top segment.

`segmentNumberOfIndex` recovers the number from the page's synthetic index instead, and
`loadSegment` uses the index as the fallback. The index is `BASE + source * STRIDE + number`, so
the remainder *is* the number — which is also why the stride has to exceed the segment count.

> **Rule:** anything that can re-resolve or rewrite a page's `imageUrl` must first check whether
> the `#segment-N` suffix is load-bearing. `internalLoadPage` already guards this once
> (`if (!page.splitSegment) page.imageUrl = imageUrl`); a caller that nulls the URL upstream
> bypasses that guard entirely.

### Low memory reaches the tile cache, and comes back

`onTrimMemory` sheds page shells at `>= UI_HIDDEN`, and **separately** calls
`ImageViewerState.onLowMemory()` at `RUNNING_LOW`, `RUNNING_CRITICAL`, `MODERATE` and `COMPLETE`
only.

The split is deliberate and load-bearing: letting it ride the screen-off signal would mean a
full tile rebuild on every lock/unlock, which is why `UI_HIDDEN` stays out of the device-cache
branch. Tiles refill lazily, so shedding them costs a rebuild, not correctness.

> **The `cacheScreens = 0.8` ratchet used to be permanent, and that was the bug.**
> `TileRenderer.onLowMemory` assigned `0.8` outright, so one transient trim signal cost the
> rest of the session a permanently smaller tile budget with nothing to bring it back. It now
> *records* the pre-shrink value and `restoreMemoryBudget` puts it back. It also scales an
> explicit `cacheByteBudget` when one is set, because `budgetTiles` ignores `cacheScreens`
> entirely once the host supplies bytes — shrinking screens there did nothing at all.

> **Order is load-bearing in the trim callback:** `applyMemoryBudget` first, then
> `shedDeviceCaches`. The resample republishes the tile budget from current pressure, and
> `onLowMemory` scales whatever it finds *downward* — so the reverse order has the resample
> undo the shed on every callback, which is precisely the ratchet above.

## MEMORY BUDGET — the reader sizes itself to the device

`WebGpuMemoryBudget.kt` answers "how much may this reader hold", and `WebGpuMemoryMonitor.kt`
keeps the answer current. This replaced `isLowRamDevice`, which was `totalMem < 3GB`.

| Question | Old answer | New answer |
|---|---|---|
| Is this device small? | one boolean | `DeviceTier` — TINY…XLARGE, plus the vendor low-RAM mark |
| Is memory short *right now*? | never asked | `Pressure` — ROOMY / TIGHT / CRITICAL |
| How big is the page cache? | a page *count* | a byte budget, plus the count |
| How big is the tile cache? | fixed 16–64 MB | `cacheByteBudget`, pushed every tick |
| Does it recover? | never | after a 12 s cooldown at ROOMY |

### Headroom, not `availMem`

`availMem` counts swap, so a device that has been swapping reports plenty free and then gets
killed. The budget uses **`availMem - threshold`**, because `threshold` is where the system starts
reclaiming. That single change is what makes this react to a memory *spike* instead of to a
device's spec sheet.

### The sample/read split is not optional

`cacheSize` is read by the render walk and `maxPageBytes` by the decode thread;
`getMemoryInfo` is a binder call. So `Budget` is an immutable precomputed snapshot published by
`sample()` on the monitor's timer, and the getters are volatile reads. **Never call `sample()`
from a getter or from the render thread.**

### A page count is not a memory measurement

Eight shells of an ordinary chapter is ~20MB. Eight shells of a split webtoon strip is ~380MB.
`ViewerReaderPage.decodedBytes` is therefore a **measurement**, set at adoption in
`WebGpuDecodePipeline` and zeroed in `releasePageResources`, and `evictDownToBudget` satisfies
**both** constraints — the count (the user's preload preference) and the bytes (the device).
Count only would be an estimate; bytes only would throw away a preference the user set.

The measurement carries the full mip chain (`4/3` of the base area). Undercounting by that third
is what lets a cache "fit" and then not.

### The byte budget cannot evict a page that is on screen

`evictFarthestPage` declines to take the drawn page or the anchor, and the loop breaks when it
makes no progress. That is deliberate: under a tight budget the cache stops at "the visible window
plus whatever fits" rather than destroying what is being read. **Do not add a hard byte cutoff
that bypasses the evictor** — that is a segfault class of bug (see the spread/`cleanup()`
landmines), not a memory one.

### Recovery is gated twice

`RECOVER_COOLDOWN_MS` (12 s) **and** `RECOVER_PRESSURE` (ROOMY). The cooldown alone still
oscillates on a device hovering at the TIGHT boundary, because every transition is a visible tile
rebuild. Same principle as the stuck-page ladder: **cooldown and escalation are separate dials.**

### `isLowRamDevice` is gone from the reader — do not bring it back

Every gate that used to ask "is this device low RAM?" now asks a question about the *budget
instead*, because the device-size answer was available before the page existed and the real
constraint is not:

| Was | Is now |
|---|---|
| `maxPageBytes = 40MB/80MB` on `isLowRamDevice` | `memoryBudget.maxPageBytes()` |
| JXL refused above a flat 16MB, but only on low-RAM devices | `memoryBudget.jxlPageBytes()`, applied on **every** device |
| spread height-match skipped on low-RAM devices | `memoryBudget.canAffordWorkingSet(bytes)` — asked per page |
| continuous slack `if (isLowRamDevice) 3 else 7` | `memoryBudget.deviceTier <= SMALL` |

The JXL gate is the clearest example of what was wrong: it refused a 20MB JXL **only** on a
small device, so a 1GB phone refused a page that a 12GB phone then OOMed on. The ceiling is now a
property of the page and the device together.

`ActivityManager.isLowRamDevice` still exists *inside* `WebGpuMemoryBudget`, where it is a
legitimate input — it folds a large-but-low-RAM device down to `MEDIUM` and raises a low headroom
threshold. `DeviceUtil.isLowRamDevice` is still used by Coil, `App` and `ImageUtil`, none of
which is the reader. Neither is the gate this section is about.

### The loader queue admits every page twice — watch for it

`HttpPageLoader` is upstream of this folder but it is where pages actually load, and its queue has
a property worth knowing before you reason about network behaviour:

- The queue is `java.util.concurrent.PriorityBlockingQueue` — **no dedup, ever**.
- `readerThreads()` defaults to **2**, so `internalLoadPage` runs concurrently.
- `preloadNextPages` is called from **inside every `loadPage`**. Page N offers N+1..N+k and page
  N+1 offers N+2..N+1+k, so **every page from the third onward is queued at least twice**.
  `aggressivePageLoading` offers all of them again, and `retryPage` offers unconditionally.

Two workers could both read `!isImageInCache` and both fetch. `internalLoadPage` now serialises
the download on `downloadLocks`, keyed on the **bare source URL** so a split parent and its
segments share one fetch, and re-checks the cache inside the lock.

**Anything else added to that queue must assume duplicates.** The runaway segment duplication
fixed in `splitOversizedPage` was a symptom of exactly this.

### A strip that outgrows the image cache churns instead of failing

The reader image cache defaults to **75 MB** (`eh_cache_size`, "Image cache size"). A long strip's
working set is its **parent image plus every segment cut from it** — roughly twice the strip. An
800 × 61 000 px strip is ~49 Mpx, so parent + 8 segments is ~90–230 MB depending on JPEG quality:
**over the default at every plausible quality**, and a strip big enough to need a 1 GB cache is not
unusual in webtoon.

The failure is not "out of space". `ensureSegment` re-cut the whole image and wrote **every**
segment, the LRU evicted the parent as it went, and the next request found the parent gone —
so `internalLoadPage` **re-downloaded the entire strip**. That is the churn: a page that never
settles, re-cut and re-fetched for as long as the reader sits on it, and it looks exactly like
defect 1's wedge from the outside.

`ensureSegment` is now adaptive:

- First cut writes every segment — strictly better when the strip fits, and how it always worked.
- If the segment asked for is **not** in the cache afterwards, the strip has proven it is larger
  than the cache. That image is remembered, and later cuts restore **only the band asked for**
  (`TallPageSplitter.split(only = …)`, which decodes one band instead of a dozen).
- `MAX_SEGMENT_RECUTS` bounds it, so a hopeless strip fails visibly (falling back to the whole
  image → decoder refuses >16384 px → ErrorPage) instead of re-downloading forever.

> **The real fix is still the cache size.** Adaptive cutting stops the loop; it does not make a
> 61 000 px strip fit in 75 MB. **More → Reader → Image cache size** is the lever — 250 MB for a
> 61 k strip, 500 MB+ past 100 k.

> **A segment's `Ready` status is not a promise the cache keeps.** That mismatch is what made
> defect 1 fatal; see the ladder section above.

### `Ready` does not mean readable

`internalLoadPage` ends with `page.stream = { cache.getImageFile(key).inputStream() }` and
`page.status = Ready` **without ever reading it**. The stream is a lambda evaluated later, and
the chapter cache is an LRU that evicts independently of the page list — so a page can go Ready,
sit through an eviction of its own bytes, and then throw `FileNotFoundException` from `stream`.

That surfaces as `no-stream` on the decode side. It is recovered from twice now: once via
`retryPage` (correct while the cache is intact) and once, on a repeat, via the cache-evicting
requery above.

### A split is all-or-nothing

`TallPageSplitter.split` is what turns a long webtoon strip into pages at all: the decoder
refuses >16384px and the renderer refuses to resize above 8192px, so a tall strip *must* be cut.

It used to skip a segment that failed to decode (`?: continue`) and still return `true`. That
is silent corruption, because the caller then builds the chapter's page list from whatever
arrived and labels each segment by **list position**, while the segment's real number is baked
into its cache key and recovered from it by `loadSegment`. One missing chunk and those two
identities diverge — a strip rendered with a hole in it, which no viewer can detect.

Both halves are now guarded: the splitter returns `false` on the first segment it cannot decode
(and recycles the bitmap in a `finally`), and `splitOversizedPage` orders by the index in the key
and requires a gapless `0..k-1` run before accepting the split. Either failure falls back to the
whole image, which is slow but correct.

OOM is the realistic cause of a failed segment — which is precisely the condition a long strip is
already under, so this path matters most exactly when memory is worst.

### A segment's height is fully determined — use that to identify one

`TallPageSplitter.segmentBounds` bisects, so **every band of a given strip has a height computable
from the strip height alone**, and the set is tiny: at 800px wide, H=61000 yields eight bands of
7625 and nothing else. Sweeping H from 8193 to 400000, **no strip produces a band of 1391** — the
bisect remainder structure makes it unreachable.

So a `Create tile 0 0 800 1391` in a log full of 7623/8142 segments is **not a segment**. It is a
page of ordinary webtoon proportion that happened to be mid-churn, and a useful tell is that it
got no `Create mipmap` line while the surrounding tiles did: the tile was allocated and discarded
before its mip chain was built.

Reach for this before theorising about the splitter when a band height looks wrong — the
arithmetic settles it in one pass, and a "weird" height is evidence *against* a split, not for a
bug in one.

### `segmentNumberOf` returns -1, not 0

A key that names no segment must fail a contiguity check rather than silently colliding with
segment 0. Do not "simplify" it back to `?: 0`.

### The contiguity check compares against a List, not `indices`

`keys.map { segmentNumberOf(it) } != keys.indices` **compiles, logs every split as
non-contiguous, and rejects all of them.** `IntRange` is an `IntProgression`, not a
`kotlin.collections.List`, so `List.equals(IntRange)` is always `false` — the guard fires
unconditionally and no tall page is ever split. The fallback hides it: the page still renders
from the whole image, so it looks like a slow reader rather than a dead code path. Compare
`keys.indices.toList()` instead. This is the same shape as the `combine`/`associateTo` traps
above — a type mismatch that a compiler will not catch.

## SYMPTOM TABLE

| Symptom in logcat | Where to look |
|---|---|
| `adapter is "consumed"` | Device is permanently dead. Nothing above it recovers. Check `recreateDeviceLocked` re-requests an adapter. |
| `WebGPU not available: ...` from `decodeReaderPage` | `requireAvailable()` failed inside an upload. Same root cause; it is a *symptom*, not the bug. |
| `Re-driving ... last=no-stream` | `page.stream` returned null — the source/loader has no bytes, not a decode failure. Follow `askLoaderToResolvePage`. **If it repeats, the page is probably wedged in LOADING** — see *Page loading*. |
| `Re-driving ... last=no-stream` forever, same page, **no "Giving up on" ever** | Both halves of the `no-stream` ladder were inert: the retry was filtered out by the loader's `status == Queue` guard, and the re-query reset `attempts` so escalation never terminated. Fixed in both — see *Page loading*. Before that fix, this was the signature of a long-strip segment whose cache file the LRU had evicted. |
| A long strip renders with a chunk missing, or scrambled, but no error anywhere | `TallPageSplitter.split` reported success with fewer segments than bounds — now impossible; if you see it, check `segmentNumberOf` and the contiguity guard. |
| Scrolling down skips several pages at once, or `getPage(0)` names a page the reader is no longer on | A placeholder resolved *smaller* than its slot. `clampScrollWithinPage` in `captureRenderState` is the only thing that keeps them on the page. |
| Page index and the index in the segment cache key disagree | They are separate identifiers; `splitOversizedPage` now orders by the key and refuses a non-contiguous run. |
| Page spins on the placeholder forever, no log line at all | Should now log `Re-driving ... loading-stalled`. If it still does not, the page is not LOADING — check `wantedByRender` and whether `imagePage` is really a `ProgressPage`. |
| `Re-driving ... last=threw` | The decode threw; read the exception above it in the log for the real reason. |
| `Re-driving ... last=unfinished` | The worker left the page mid-flight without completing a trace. Usually cancellation. |
| `DecodeTrace STALL` | The watchdog saw a page past its stage deadline. The worker cannot report its own stall. |
| `Device not available` / blank pages after backgrounding | Device loss; check the recovery path above, not the page. |
| `device lost reason=1: CreateAndroidSurfaceKHR failed with VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`, preceded by `BufferQueueProducer ... connect: already connected` and `vulkan: native_window_api_connect() failed` | A **second** swapchain was created on a window the old one still owned. Dawn can defer `vkDestroySurfaceKHR` past `close()`, so recovery must drop the old `GPUSurface` *and* release the retained `ANativeWindow` (`NativeWindow.release`) **before** it builds the replacement — see `recoverFromDeviceLoss`. The `SurfaceTexture-0-…` name means the TextureView path (API < 34), where the window is a `SurfaceTexture`'s BufferQueue. |
| Blank/garbage page in dual-page mode only | Check `spreadPage` lifecycle — see landmines. |
| Pages decode then vanish when scrolling back | Eviction. Check `isOnScreen` immunity and `pageDistance` anchoring. |
| Same page stuck forever, no ErrorPage | `wantedByRender` is still set and `imagePage` is still a `ProgressPage` — the sweep only sees that one shape. |

## LOG TAGS / GREPS

Library logs under `WebGpuRenderer` and `TileRenderer`. App logs go through `logcat {}`, so
the tag is the **coroutine or thread** name (`StandaloneCoroutine`, `VRI[Reader...y]`, …) —
filter on the message text, not the tag.

```bash
adb logcat | grep -E "WebGpuRenderer|TileRenderer"
adb logcat | grep -E "Re-driving|DecodeTrace STALL|Giving up on"
adb logcat | grep -E "WebGPU not available|adapter is"
```

**Before trusting a log to locate a bug, check the build against the submodule.** Symptoms
observed on an older APK can come from renderer code that no longer exists — a `grid LRU`
line, for instance, does not appear anywhere in the current tree.

## KNOWN LANDMINES (each one was a real bug)

- **The position store outranks `last_page_read`, so it has to be gated on it.**
  `WebGpuReadingPositionStore` keeps `pageIndex`/`documentY`/`zoom`/`offsetX` per chapter in the
  `webgpu_positions` prefs, because no DB column can express the last three, and
  `setChaptersInternal` reads it *before* `requestedPage`. That precedence is what survives a
  process kill — and what made a position reset from outside the reader invisible: marking a
  chapter unread zeroes `last_page_read` (`SetReadStatus` owns that, and has since upstream
  #7475) while the anchor stayed behind, so the chapter reopened at the page it had been stopped
  on. Only this viewer did it — pager, webtoon and novel restore from `requestedPage` alone,
  which *is* `last_page_read`. The store is now consulted only when `last_page_read > 0`.
  **Rule: a position reset from outside the reader has to overrule the store, and `last_page_read`
  is the only signal that can do it.**
- **`retryPage` is not eviction.** If the cache entry is the defect, `no-stream` → `retryPage`
  is a no-op by design and leaves the page wedged in LOADING with no timeout (see *Page
  loading*). `requerySuspectPage` is the one that evicts first.
- **The surface teardown in `recoverFromDeviceLoss` must precede the device rebuild.** The
  natural order — recreate the device, *then* drop the stale swapchain — builds the replacement
  on a window the old swapchain still owns, and Dawn defers `vkDestroySurfaceKHR` past
  `close()`, so it fails with `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` and loses the device it was
  trying to recover. Closing the `GPUSurface` is not enough; the retained `ANativeWindow` has to
  go too.
- **Never assume a queued page reached the loader.** `HttpPageLoader`'s collector is
  `.filter { it.status == Page.State.Queue }`, so offering a page in any other state is a
  silent no-op — no log, no work. Anything that re-offers a page must set `Queue` itself.
  This is invisible for an ordinary `Ready` page (its bytes are cached anyway) and fatal for a
  split segment, which is `Ready` *by construction* and whose file the LRU can evict.
- **Anything reached from `buildSpreadPage` runs once per rendered frame.** `fetchPage(0)`
  calls it every frame, so a counter charged at *request* time there is a frame counter, not a
  retry counter. The spread height-match budget is the one that already was.
- **`ViewerReaderPage.spreadPage` must stay `@Volatile`.** It is written by the render thread
  (`buildSpreadPage`) and cleared by `releasePageResources` from main (device-loss, stuck
  sweep, `onTrimMemory`). Unfixed, the render thread read back a spread whose textures were
  already released.
- **The spinner texture must not be handed back while the device is lost.**
  `WebGpuRenderer.device` keeps naming the dead device until recovery replaces it, so the
  `pineappleDevice === device` identity check cannot see the loss. `isAvailable` is the gate
  that can.
- **Never drop a spread or an `ImagePage` reference without `cleanup()`** — it owns uploaded
  textures and its tile grid. The library keys tile caches by page identity, so an abandoned
  spread keeps its whole grid resident and only a *destroyed* page sweeps it.
- **"Backgrounds first, then content" only holds inside `renderPass`.** The continuous draw splits
  content into two passes and only one of them obeys the ordering. `ImagePage.ImageSingle` pages go
  through `renderPass` and get the underlay-then-content treatment that fixed the seam line
  (`webgpuviewer-houri` @ `06d2b1001`). `ImagePage.Render` pages — `ProgressPage`, `TransitionPage`,
  `ErrorPage` — are drawn **after** that pass, each opening its own pass over the shared screen
  texture via `renderLoaded`. So they are the one kind of content in continuous mode that can still
  land on top of a neighbour's last row of real pixels, and they had **no underlay of their own**.

  The underlay loop now paints a Render page's background too, spanning its slot. The extent is the
  **slot** (`vp.docTop` + `vp.pageHeight`), never the page's own height.

- **A `Render` page must be drawn at its slot's scale, not at `s.scale * page.scale`.** This is what
  the seam line actually was, and it is intermittent for a structural reason: a `Render` page
  deliberately gets its own live pan/zoom (see `ImagePage.Render.drawLive`), so a pinch or fling
  running while a segment is still decoding is enough to move it off 1. `fillPage` paints
  `scale * page.height` tall while the document laid the next page out from
  `getPageHeight(page) * s.scale` - and those agree only at `page.scale == 1`.

  Off 1, taller repaints over the last row of **both** neighbours (it runs after all of them) and
  shorter leaves the difference unpainted. Either way: a thin line at every boundary the placeholder
  touches, on a long strip, only while a neighbour is decoding - which is why it reads as sporadic
  and shows between ordinary pages as readily as between segments.

  > **Rule:** in continuous mode the *slot* is the only size the document lays pages out from.
  > Anything drawn into it must be sized from the slot. Two code paths that size the same boundary
  > differently leave a fractional disagreement, and a fractional disagreement at a page boundary
  > *is* the seam.
- **Never reuse a GPU adapter** (above).
- **Never call `WebGpuMemoryBudget.sample()` from a getter.** `cacheSize` is read by the render
  walk; `sample()` is a binder call. The snapshot exists so hot paths only ever read a volatile
  field.
- **The byte budget cannot evict a drawn page.** `evictDownToBudget` asks the ordinary evictor
  and stops when it stops making progress. Adding a cutoff that bypasses `evictFarthestPage`
  destroys pages that are on screen — and, via the spread/`cleanup()` rules above, textures
  something else still holds.
- **`decodedBytes` is set inside the same lock that installs `imagePage`,** and zeroed only in
  `releasePageResources`. A reader that sees the new image and the old zero lets the budget admit
  one more oversized page than it can hold.
- **`registerHdrSdrRatioChangedListener` throws unless `isHdrSdrRatioAvailable`.** Gating
  registration on a non-null display alone logs `IllegalStateException: HDR/SDR ratio changed not
  available` with a full stack trace on every surface creation of an SDR-only device.
- **Do not merge `PagerPageImageProcessor` and `WebtoonPageImageProcessor` back together.**
- **Do not grow `WebGpuViewer.kt` past wiring.** A new state machine belongs in its own file
  as a `WebGpuViewer` extension.

## WHERE THINGS ARE

| Concern | File |
|---|---|
| the class: fields, wiring, navigation, lifecycle | `WebGpuViewer.kt` |
| page shells, spread slot, neighbour links | `WebGpuPageModel.kt` |
| residency, eviction, split reconciliation, render walk | `WebGpuPageCache.kt` |
| what the worker owes a page + the stuck ladder | `WebGpuDecodeQueue.kt` |
| bytes → GPU image for one page | `WebGpuDecodePipeline.kt` |
| the three long-lived coroutines + stall watchdog | `WebGpuViewerWorkers.kt` |
| progress / error / transition placeholders | `WebGpuPlaceholderPages.kt` |
| spread pairing + height-match maths | `WebGpuSpread.kt`, `WebGpuSpreadHeightMatch.kt` |
| preference → filter/transition wiring | `WebGpuViewerImageState.kt`, `WebGpuConfig.kt` |
| device profile + live byte budget | `WebGpuMemoryBudget.kt` |
| the memory watcher loop + trim wiring | `WebGpuMemoryMonitor.kt` |
| render engine | submodule `external/webgpuviewer-houri` |