package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Execution(ExecutionMode.CONCURRENT)
class ChapterPreloadGuardTest {

    @Test
    fun `S1 - only the first begin for a chapter wins`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        // Render frames, gestures and preload walks all hit the prev/next getters while a
        // chapter is loading; every duplicate must be rejected so only one retry loop runs.
        guard.tryBegin("chapter-1") shouldBe false
        guard.tryBegin("chapter-1") shouldBe false
    }

    @Test
    fun `S2 - end releases the chapter so a later retry can begin again`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        guard.end("chapter-1")

        guard.tryBegin("chapter-1") shouldBe true
    }

    @Test
    fun `S3 - different chapters never block each other`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        guard.tryBegin("chapter-2") shouldBe true
        guard.tryBegin("chapter-3") shouldBe true
    }

    @Test
    fun `S4 - end is idempotent and unknown keys are harmless`() {
        val guard = ChapterPreloadGuard()

        guard.end("never-begun")
        guard.tryBegin("chapter-1") shouldBe true
        guard.end("chapter-1")
        guard.end("chapter-1")

        guard.tryBegin("chapter-1") shouldBe true
    }

    @Test
    fun `S5 - concurrent begins across threads elect exactly one winner`() {
        val guard = ChapterPreloadGuard()
        val threads = 8
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val winners = (0 until threads).map {
                pool.submit<Boolean> {
                    latch.await()
                    guard.tryBegin("chapter-race")
                }
            }

            latch.countDown()
            val results = winners.map { it.get() }
            results.count { it } shouldBe 1

            guard.end("chapter-race")
            guard.tryBegin("chapter-race") shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `S6 - a blank or oversized key is refused rather than tracked`() {
        val guard = ChapterPreloadGuard()

        // The key is a chapter URL, so it is untrusted input as far as this map is concerned: an
        // empty one would collide with every other empty one, and a huge one is a way to make the
        // set grow without ever being eligible for the expiry sweep to reclaim.
        guard.tryBegin("") shouldBe false
        guard.tryBegin("   ") shouldBe false
        guard.tryBegin("x".repeat(513)) shouldBe false
    }

    @Test
    fun `S7 - the key set stays bounded when keys are never released`() {
        val guard = ChapterPreloadGuard()

        // Nothing releases these, which is exactly the leak the cap exists for. Without it the set
        // grows with every chapter the reader ever approached.
        repeat(500) { guard.tryBegin("leaked-$it") }

        // Purged rather than refused: a fresh chapter must still be able to preload even after the
        // cap has been hit, which a hard refusal at 32 would prevent.
        guard.tryBegin("after-the-cap") shouldBe true
    }

    @Test
    fun `S8 - the most recent keys survive a cap purge`() {
        val guard = ChapterPreloadGuard()

        repeat(40) { guard.tryBegin("chapter-$it") }

        // The newest are the ones still loading, so the purge has to drop the oldest. Nothing is
        // older than the expiry window here, so this is the clear-everything fallback - and the
        // point is that a chapter begun most recently is still tracked after it.
        guard.isInFlight("chapter-39") shouldBe true
        guard.isInFlight("chapter-0") shouldBe false
    }

    // ---------- the stale-requeue entry point ----------

    @Test
    fun `S9 - a stale key is released and begun again`() {
        val guard = ChapterPreloadGuard()
        guard.tryBegin("chapter-1") shouldBe true

        // The holder gave up but left the key marked, so this is the one case where being in
        // flight must not be the final word.
        guard.tryBeginOrRequeueIfStale("chapter-1") { true } shouldBe true
        guard.isInFlight("chapter-1") shouldBe true
    }

    @Test
    fun `S10 - a key that is not stale keeps blocking duplicates`() {
        val guard = ChapterPreloadGuard()
        guard.tryBegin("chapter-1") shouldBe true

        guard.tryBeginOrRequeueIfStale("chapter-1") { false } shouldBe false
    }

    @Test
    fun `S11 - the stale-requeue path elects one winner like the plain one`() {
        val guard = ChapterPreloadGuard()

        // Not in flight yet, so this is just a begin - and a second caller must still lose.
        guard.tryBeginOrRequeueIfStale("chapter-1") { false } shouldBe true
        guard.tryBeginOrRequeueIfStale("chapter-1") { false } shouldBe false
    }

    @Test
    fun `S12 - the stale-requeue path refuses an unusable key without consulting staleness`() {
        val guard = ChapterPreloadGuard()
        var asked = false

        guard.tryBeginOrRequeueIfStale("") {
            asked = true
            false
        } shouldBe false
        guard.tryBeginOrRequeueIfStale("x".repeat(513)) {
            asked = true
            false
        } shouldBe false

        // A key that can never be stored must not run a caller-supplied lambda either - that
        // lambda is a cache probe on the render path.
        asked shouldBe false
    }

    @Test
    fun `S13 - the stale-requeue path is bounded like the plain one`() {
        val guard = ChapterPreloadGuard()

        // The reason this used to be two methods rather than one: the requeue twin carried no cap,
        // so reaching for it would have reintroduced unbounded growth. Both go through one step now.
        repeat(500) { guard.tryBeginOrRequeueIfStale("leaked-$it") { false } }
        guard.tryBeginOrRequeueIfStale("after-the-cap") { false } shouldBe true
    }
}
