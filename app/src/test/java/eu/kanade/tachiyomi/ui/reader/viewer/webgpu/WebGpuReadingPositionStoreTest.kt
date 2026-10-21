package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.CURRENT_VERSION
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.TTL_MS
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.parsePosition
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.sanitizeDocumentY
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.sanitizeFraction
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.sanitizeOffsetX
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.Companion.sanitizeZoom
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The reading-position store's sanitizers and its on-disk parser.
 *
 * These are the only pure part of the store - everything else goes through SharedPreferences - and
 * they are the part that decides whether a reader gets their zoom, their page and their place in a
 * page back. Being wrong here is invisible: the chapter still opens, just not where it was left,
 * so a clamp that is too tight is indistinguishable from the app forgetting.
 *
 * The reachability that matters is the viewer's, not this file's: continuous and webtoon lock
 * `homeScale` to a slider that runs 1..100 percent, and `ImagePage` falls back to 0.01 when it has
 * no parent size. Anything the reader can actually zoom to has to survive a round trip here.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuReadingPositionStoreTest {

    private val now = 1_700_000_000_000L

    // ---------- zoom: the range the viewer can actually reach ----------

    @Test
    fun `a zoom the continuous viewer allows round trips unchanged`() {
        // The zoom-out slider runs 1..100 percent, so 0.2 is an ordinary reading zoom for a
        // continuous or webtoon reader - not an edge case.
        sanitizeZoom(0.2f) shouldBe 0.2f
        sanitizeZoom(0.35f) shouldBe 0.35f
        sanitizeZoom(1f) shouldBe 1f
        sanitizeZoom(3.5f) shouldBe 3.5f
    }

    @Test
    fun `the smallest zoom the viewer can reach is not rounded up`() {
        // ImagePage.homeScale falls back to 0.01 with no parent size, and the continuous homeScale
        // setter clamps to 0.01 as well, so this is the floor a gesture can land on.
        sanitizeZoom(0.01f) shouldBe 0.01f
        sanitizeZoom(0.05f) shouldBe 0.05f
    }

    @Test
    fun `a zoom the viewer cannot reach is still refused`() {
        sanitizeZoom(0f) shouldBe 0.01f
        sanitizeZoom(-2f) shouldBe 0.01f
    }

    @Test
    fun `a paged page can exceed the old eight-times ceiling`() {
        // maxScale defaults to max(minScale, homeScale) * 2, and a small cover fitted to a tall
        // screen has a home scale well past four - so its zoom-out ceiling lands above eight.
        sanitizeZoom(10.8f) shouldBe 10.8f
    }

    @Test
    fun `an absurd zoom is still bounded`() {
        sanitizeZoom(1e9f) shouldBe 32f
        sanitizeZoom(-1e9f) shouldBe 0.01f
    }

    @Test
    fun `a non-finite zoom falls back to fit`() {
        sanitizeZoom(Float.NaN) shouldBe 1f
        sanitizeZoom(Float.POSITIVE_INFINITY) shouldBe 1f
        sanitizeZoom(Float.NEGATIVE_INFINITY) shouldBe 1f
    }

    // ---------- the other sanitizers ----------

    @Test
    fun `document Y is a pixel offset, so it keeps a large value`() {
        sanitizeDocumentY(0f) shouldBe 0f
        sanitizeDocumentY(4_000_000f) shouldBe 4_000_000f
        sanitizeDocumentY(1e7f) shouldBe 1e7f
        sanitizeDocumentY(-1f) shouldBe 0f
        sanitizeDocumentY(1e12f) shouldBe 1e7f
        sanitizeDocumentY(Float.NaN) shouldBe 0f
    }

    @Test
    fun `offset X is normalized, so it is clamped to plus or minus one`() {
        sanitizeOffsetX(0f) shouldBe 0f
        sanitizeOffsetX(0.25f) shouldBe 0.25f
        sanitizeOffsetX(-0.25f) shouldBe -0.25f
        sanitizeOffsetX(5f) shouldBe 1f
        sanitizeOffsetX(-5f) shouldBe -1f
        sanitizeOffsetX(Float.NaN) shouldBe 0f
    }

    @Test
    fun `fraction within a page is a zero to one share`() {
        sanitizeFraction(0f) shouldBe 0f
        sanitizeFraction(0.5f) shouldBe 0.5f
        sanitizeFraction(1f) shouldBe 1f
        sanitizeFraction(1.4f) shouldBe 1f
        sanitizeFraction(-0.4f) shouldBe 0f
        sanitizeFraction(Float.NaN) shouldBe 0f
    }

    // ---------- the v3 record the store writes today ----------

    private fun v3(
        pageIndex: Int = 7,
        documentY: Float = 1234f,
        offsetX: Float = 0.1f,
        zoom: Float = 0.2f,
        fraction: Float = 0.6f,
        timestamp: Long = now - 1000L,
    ) = record(CURRENT_VERSION, pageIndex, documentY, offsetX, zoom, fraction, timestamp)

    /** Raw fields as strings, so a corrupt one can be written as itself rather than substituted in. */
    private fun record(vararg fields: Any) = fields.joinToString("|")

    @Test
    fun `a v3 record reads back every field it was given`() {
        val parsed = parsePosition(v3(), now)!!
        parsed.pageIndex shouldBe 7
        parsed.documentY shouldBe 1234f
        parsed.zoom shouldBe 0.2f
        parsed.offsetX shouldBe 0.1f
        parsed.fraction shouldBe 0.6f
    }

    @Test
    fun `the writer's own field order round trips`() {
        // Guards the format itself: seven fields in this exact order is the only thing the 7-field
        // branch can read back, so a reorder here would silently fall into the legacy parser.
        val written = v3()
        written.split("|").size shouldBe 7
        parsePosition(written, now)!!.pageIndex shouldBe 7
    }

    @Test
    fun `a v3 record past its ttl is dropped`() {
        parsePosition(v3(timestamp = now - TTL_MS - 1), now).shouldBeNull()
    }

    @Test
    fun `a v3 record inside its ttl survives`() {
        parsePosition(v3(timestamp = now - TTL_MS + 1), now)!!.pageIndex shouldBe 7
    }

    @Test
    fun `a v3 record with an unusable page index is dropped`() {
        parsePosition(v3(pageIndex = -1), now)!!.pageIndex shouldBe 0
        parsePosition(record(CURRENT_VERSION, "notanumber", 1234f, 0.1f, 0.2f, 0.6f, now - 1), now)
            .shouldBeNull()
    }

    @Test
    fun `a v3 record with a non-finite field is sanitized rather than dropped`() {
        val parsed = parsePosition(record(CURRENT_VERSION, 3, "NaN", "NaN", "NaN", "NaN", now - 1), now)!!
        parsed.documentY shouldBe 0f
        parsed.zoom shouldBe 1f
        parsed.offsetX shouldBe 0f
        parsed.fraction shouldBe 0f
    }

    @Test
    fun `a v3 record with a junk timestamp is dropped rather than trusted forever`() {
        // An unparseable timestamp reads as zero, which is far outside the ttl - so a record that
        // lost its clock is discarded instead of being kept for ever. The tagless shape below is the
        // one exception, because there a zero genuinely means "never written".
        parsePosition(record(CURRENT_VERSION, 7, 1234f, 0.1f, 0.2f, 0.6f, "junk"), now).shouldBeNull()
    }

    // ---------- the v2 records still on disk ----------

    @Test
    fun `the six-field v2 record reads its five stored fields`() {
        val raw = listOf("v2", 4, 900f, 0.05f, 0.3f, now - 1000).joinToString("|")
        val parsed = parsePosition(raw, now)!!
        parsed.pageIndex shouldBe 4
        parsed.documentY shouldBe 900f
        parsed.zoom shouldBe 0.3f
        parsed.offsetX shouldBe 0.05f
    }

    @Test
    fun `the six-field v2 record derives a fraction only from a small document Y`() {
        // This is the ambiguity the version tag exists to end: in the six-field shape documentY and
        // fraction share a slot, so a value that could be either is read as a fraction only when it
        // is small enough to be one.
        val small = parsePosition(listOf("v2", 1, 0.5f, 0f, 1f, now - 1000).joinToString("|"), now)!!
        small.fraction shouldBe 0.5f
        val large = parsePosition(listOf("v2", 1, 5000f, 0f, 1f, now - 1000).joinToString("|"), now)!!
        large.fraction shouldBe 0f
    }

    @Test
    fun `the seven-field v2 record keeps its own fraction`() {
        val raw = listOf("v2", 5, 2000f, 0f, 1f, 0.25f, now - 1000).joinToString("|")
        parsePosition(raw, now)!!.fraction shouldBe 0.25f
    }

    @Test
    fun `a v2 record past its ttl is dropped`() {
        parsePosition(listOf("v2", 1, 900f, 0f, 1f, now - TTL_MS - 1).joinToString("|"), now).shouldBeNull()
    }

    // ---------- the tagless legacy record ----------

    @Test
    fun `the legacy record reads its ratio as a fraction and claims no document offset`() {
        val raw = listOf(9, 0.4f, 2f, now - 1000).joinToString("|")
        val parsed = parsePosition(raw, now)!!
        parsed.pageIndex shouldBe 9
        // The tagless shape has no document coordinate - its second field was a share of the page -
        // so it carries the position in fraction and reports no pixel offset. Reading that share as
        // documentY would restore the reader a fraction of a pixel into the chapter, and it is the
        // only value that degrades safely: the state falls back to the top rather than to a
        // random spot mid-document.
        parsed.documentY shouldBe 0f
        parsed.fraction shouldBe 0.4f
        parsed.zoom shouldBe 2f
        parsed.offsetX shouldBe 0f
    }

    @Test
    fun `the legacy record survives a zero timestamp`() {
        // Zero means "no timestamp was written", not "written in 1970" - expiring it would drop
        // every pre-timestamp record the moment the first read happened.
        parsePosition(listOf(9, 0.4f, 1f, 0L).joinToString("|"), now)!!.pageIndex shouldBe 9
    }

    @Test
    fun `the legacy record past its ttl is dropped`() {
        parsePosition(listOf(9, 0.4f, 1f, now - TTL_MS - 1).joinToString("|"), now).shouldBeNull()
    }

    @Test
    fun `a legacy record too short to hold a position is dropped`() {
        parsePosition("9|0.4", now).shouldBeNull()
        parsePosition("", now).shouldBeNull()
    }

    @Test
    fun `a record with no parseable page index is dropped`() {
        parsePosition("junk|0.4|1|0", now).shouldBeNull()
        parsePosition(record(CURRENT_VERSION, "-", 1234f, 0.1f, 0.2f, 0.6f, now - 1), now).shouldBeNull()
    }
}
