package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File
import java.nio.file.Path

/**
 * The upscaled-page cache, exercised almost entirely through its failure paths.
 *
 * This is the reader's most fault-tolerant component by design: it is written to
 * `try { } catch (_: Exception) {}` on nearly every disk touch, because a cache that throws
 * turns a page-load into a crash. That tolerance is exactly what makes it hard to test by
 * reading - there are no exceptions to assert on, only return values that quietly mean
 * "unavailable". So these tests drive it with the states a real device produces: a write that
 * died half way, a file the OS truncated, a clock that ran forward, a key from an older version,
 * and a caller that hands it a key it never minted.
 *
 * The invariants that matter: a corrupt or expired entry is *deleted*, not just skipped (a
 * half-decoded page shown until the TTL sweeps it is the bug this guards), a key that did not
 * come from this cache can never escape the cache directory, and the key derivation cannot
 * collapse two different pages onto one entry.
 */
@Execution(ExecutionMode.CONCURRENT)
class UpscaleCacheManagerTest {

    private val now: Long get() = System.currentTimeMillis()
    private val dayMillis = 24L * 60 * 60 * 1000

    private fun cacheDir(root: Path): File = File(root.toFile(), "upscale_cache")

    /** A byte array that passes the JPEG magic check, so it survives a read-back. */
    private fun jpegPayload(size: Int = 64): ByteArray =
        ByteArray(size).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
            it[2] = 0xFF.toByte()
        }

    private fun webpPayload(size: Int = 64): ByteArray = ByteArray(size).also {
        val magic = "RIFF????WEBP".toByteArray(Charsets.ISO_8859_1)
        magic.copyInto(it)
    }

    // ---------- key validity: the only thing standing between a caller and the filesystem ----------

    @Test
    fun `a key that was not minted here is refused rather than turned into a path`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        // Too short, and containing separators and traversal, so none of these may become a file.
        for (bad in listOf("", "x", "../../etc/passwd", "..%2F..%2Fetc", "/absolute/path")) {
            manager.putCached(bad, jpegPayload())
            manager.getCached(bad).shouldBeNull()
        }
        cacheDir(root).listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `uppercase hex is not a valid key even though it is the same digest`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val real = manager.cacheKey(jpegPayload(), 2f, "model")
        real shouldBe real.lowercase()
        manager.putCached(real.uppercase(), jpegPayload())
        cacheDir(root).listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `a sixty four character key with a non hex character in it is refused`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val real = manager.cacheKey(jpegPayload(), 2f, "model")
        val poisoned = real.dropLast(1) + "z"
        manager.putCached(poisoned, jpegPayload())
        manager.getCached(poisoned).shouldBeNull()
        cacheDir(root).listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `a sixty four character all-hex key is accepted`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "a".repeat(64)
        val payload = jpegPayload()
        manager.putCached(key, payload)
        manager.getCached(key) shouldBe payload
    }

    // ---------- reads: missing, empty, corrupt and expired entries ----------

    @Test
    fun `a key with no file behind it reads as unavailable`(@TempDir root: Path) {
        UpscaleCacheManager(root.toFile()).getCached("b".repeat(64)).shouldBeNull()
    }

    @Test
    fun `an empty cache file is deleted instead of returned as a finished page`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "c".repeat(64)
        val file = File(cacheDir(root), "$key.webp")
        file.writeBytes(ByteArray(0))

        manager.getCached(key).shouldBeNull()
        assertTrue(!file.exists(), "a zero-length entry must be dropped, not left to be re-read")
    }

    @Test
    fun `an entry with neither a JPEG nor a WebP header is deleted`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "d".repeat(64)
        val file = File(cacheDir(root), "$key.webp")
        // Long enough to pass the size check, but it is not an image: the write died in compress.
        file.writeBytes(ByteArray(256) { 0x42 })

        manager.getCached(key).shouldBeNull()
        assertTrue(!file.exists(), "a half-written entry must be dropped, not served as a page")
    }

    @Test
    fun `a truncated WebP shorter than its own magic is deleted`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "e".repeat(64)
        val file = File(cacheDir(root), "$key.webp")
        file.writeBytes("RIFF".toByteArray()) // only the first four magic bytes landed

        manager.getCached(key).shouldBeNull()
        assertTrue(!file.exists(), "a truncated header must be dropped")
    }

    @Test
    fun `both stored formats are accepted because the encoder picks by size`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val jpeg = jpegPayload()
        val webp = webpPayload()
        manager.putCached("1".repeat(64), jpeg)
        manager.putCached("2".repeat(64), webp)
        manager.getCached("1".repeat(64)) shouldBe jpeg
        manager.getCached("2".repeat(64)) shouldBe webp
    }

    @Test
    fun `an entry older than the TTL is deleted on read`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "2".repeat(64)
        val payload = jpegPayload()
        manager.putCached(key, payload)

        val file = File(cacheDir(root), "$key.webp")
        assertTrue(file.setLastModified(now - (UpscaleCacheManager.TTL_DAYS + 1) * dayMillis), "clock stub failed")

        manager.getCached(key).shouldBeNull()
        assertTrue(!file.exists(), "an entry past its TTL must be dropped")
    }

    @Test
    fun `reading an entry refreshes its timestamp so live pages outlive the sweep`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "3".repeat(64)
        val payload = jpegPayload()
        manager.putCached(key, payload)

        val file = File(cacheDir(root), "$key.webp")
        val aged = now - (UpscaleCacheManager.TTL_DAYS - 1) * dayMillis
        assertTrue(file.setLastModified(aged), "clock stub failed")

        manager.getCached(key) shouldBe payload
        assertTrue(file.lastModified() > aged, "a hit must push the entry's expiry out")
    }

    // ---------- writes: the guards and the temp-file dance ----------

    @Test
    fun `an empty payload is refused rather than cached`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        manager.putCached("4".repeat(64), ByteArray(0))
        cacheDir(root).listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `a successful write leaves the entry and no temp file behind`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val payload = jpegPayload()
        manager.putCached("5".repeat(64), payload)

        val files = cacheDir(root).listFiles().orEmpty()
        files.size shouldBe 1
        files.single().name shouldBe "${"5".repeat(64)}.webp"
        assertTrue(files.none { ".tmp." in it.name }, "a temp file survived a completed write")
    }

    @Test
    fun `writing the same key twice overwrites rather than accumulating`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = "6".repeat(64)
        manager.putCached(key, jpegPayload(64))
        manager.putCached(key, jpegPayload(128))
        manager.fileCount() shouldBe 1
        manager.getCached(key)!!.size shouldBe 128
    }

    // ---------- accounting and pruning ----------

    @Test
    fun `a fresh cache reports nothing stored`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        manager.fileCount() shouldBe 0
        manager.sizeBytes() shouldBe 0L
    }

    @Test
    fun `size and count add up across a mix of entries`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        manager.putCached("7".repeat(64), jpegPayload(100))
        manager.putCached("8".repeat(64), jpegPayload(250))
        manager.fileCount() shouldBe 2
        manager.sizeBytes() shouldBe 350L
    }

    @Test
    fun `pruning drops expired entries and keeps fresh ones`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val stale = "9".repeat(64)
        val fresh = "0".repeat(64)
        manager.putCached(stale, jpegPayload())
        manager.putCached(fresh, jpegPayload())

        val staleFile = File(cacheDir(root), "$stale.webp")
        assertTrue(staleFile.setLastModified(now - (UpscaleCacheManager.TTL_DAYS + 2) * dayMillis), "clock stub failed")

        manager.pruneExpired()
        assertTrue(!staleFile.exists(), "prune must drop the expired entry")
        assertTrue(File(cacheDir(root), "$fresh.webp").exists(), "prune must keep the live entry")
        manager.getCached(fresh).shouldNotBeNull()
    }

    @Test
    fun `pruning sweeps an abandoned temp file but spares a recent one`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val dir = cacheDir(root)
        val old = File(dir, "abandoned.tmp.111")
        val recent = File(dir, "inflight.tmp.222")
        old.writeBytes(jpegPayload())
        recent.writeBytes(jpegPayload())
        assertTrue(old.setLastModified(now - 3 * 60 * 60 * 1000L), "clock stub failed")

        manager.pruneExpired()
        assertTrue(!old.exists(), "a temp file older than the grace window must be swept")
        assertTrue(recent.exists(), "a temp file from a write still in flight must survive")
    }

    @Test
    fun `a completed write leaves no temp file that could be mistaken for an entry`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        manager.putCached("a".repeat(64), jpegPayload())
        assertTrue(
            cacheDir(root).listFiles().orEmpty().none { ".tmp." in it.name },
            "no temp file may survive, since pruning would treat it as an abandoned write",
        )
    }

    @Test
    fun `clear empties the cache and stays safe to call twice`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        manager.putCached("b".repeat(64), jpegPayload())
        manager.fileCount() shouldBe 1

        manager.clear()
        manager.fileCount() shouldBe 0
        manager.sizeBytes() shouldBe 0L

        manager.clear()
        manager.fileCount() shouldBe 0
    }

    // ---------- key derivation: the thing that must never collapse ----------

    @Test
    fun `keys are sixty four lowercase hex characters`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = manager.cacheKey(jpegPayload(), 2f, "model")
        key.length shouldBe 64
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' }, "key is not hex: $key")
    }

    @Test
    fun `the same input always derives the same key`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val first = manager.cacheKey(jpegPayload(), 2f, "waifu2x")
        val second = manager.cacheKey(jpegPayload(), 2f, "waifu2x")
        first shouldBe second
    }

    @Test
    fun `different content, factor or model never share a key`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val base = manager.cacheKey(jpegPayload(64), 2f, "waifu2x")

        assertTrue(manager.cacheKey(jpegPayload(65), 2f, "waifu2x") != base, "length must change the key")
        assertTrue(manager.cacheKey(jpegPayload(64), 3f, "waifu2x") != base, "factor must change the key")
        assertTrue(manager.cacheKey(jpegPayload(64), 2f, "realesrgan") != base, "model must change the key")
    }

    @Test
    fun `an empty page still derives a usable key rather than an empty one`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val key = manager.cacheKey(ByteArray(0), 2f, "waifu2x")
        key.length shouldBe 64
        // Two empty pages are the same page as far as the cache is concerned, which is fine:
        // the byte count is folded in, so an empty one cannot collide with a real one.
        assertTrue(manager.cacheKey(ByteArray(0), 2f, "waifu2x") == key, "empty pages are stable")
        assertTrue(manager.cacheKey(jpegPayload(), 2f, "waifu2x") != key, "empty must not collide with real")
    }

    @Test
    fun `a non finite factor falls back to a fixed value instead of poisoning the digest`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val bytes = jpegPayload()
        val normal = manager.cacheKey(bytes, 2f, "waifu2x")
        assertTrue(manager.cacheKey(bytes, Float.NaN, "waifu2x") == normal, "NaN must normalise to 2")
        assertTrue(manager.cacheKey(bytes, Float.POSITIVE_INFINITY, "waifu2x") == normal, "+inf must normalise")
        assertTrue(manager.cacheKey(bytes, Float.NEGATIVE_INFINITY, "waifu2x") == normal, "-inf must normalise")
        assertTrue(manager.cacheKey(bytes, 2.5f, "waifu2x") != normal, "a real factor must still differ")
    }

    @Test
    fun `model names are truncated to sixty four characters`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val shared = "m".repeat(64)
        // Everything past the cut is dropped, so these two are the same model as far as the key knows.
        assertTrue(
            manager.cacheKey(jpegPayload(), 2f, shared + "-a") == manager.cacheKey(jpegPayload(), 2f, shared + "-b"),
            "names differing only past the truncation must collide",
        )
        assertTrue(
            manager.cacheKey(jpegPayload(), 2f, shared) != manager.cacheKey(jpegPayload(), 2f, "m".repeat(63)),
            "names differing inside the cut must differ",
        )
    }

    @Test
    fun `blank extras are the same as passing none at all`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val bytes = jpegPayload()
        val first = manager.cacheKeyDetailed(bytes, 2f, "m", "", "")
        assertTrue(manager.cacheKey(bytes, 2f, "m") == first, "blank extras must be the same as no extras")
        assertTrue(manager.cacheKeyDetailed(bytes, 2f, "m", "", "") == first, "repeat call must be stable")
        assertTrue(manager.cacheKeyDetailed(bytes, 2f, "m", "x", "") != first, "extra1 must matter")
        assertTrue(manager.cacheKeyDetailed(bytes, 2f, "m", "", "x") != first, "extra2 must matter")
    }

    @Test
    fun `the two extra slots are not positional - swapping them collides`(@TempDir root: Path) {
        // Empty slots are dropped from the digest instead of kept as placeholders, so a value in
        // the second slot cannot be told from the same value in the first. Pinned so the collision
        // is visible if the two slots are ever given values that must not be confused.
        val manager = UpscaleCacheManager(root.toFile())
        val bytes = jpegPayload()
        manager.cacheKeyDetailed(bytes, 2f, "m", "x", "") shouldBe
            manager.cacheKeyDetailed(bytes, 2f, "m", "", "x")
    }

    @Test
    fun `a page larger than the full-hash limit still keys on its middle`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        // Past 256 KB the digest stops reading everything and samples instead, which is where a
        // single-prefix hash used to collide two distinct pages from the same chapter.
        val shared = 300 * 1024
        val a = ByteArray(shared) { 0x11 }
        val b = a.copyOf()
        b[shared / 2] = 0x22 // lands inside the middle sample

        val keyA = manager.cacheKey(a, 2f, "waifu2x")
        val keyB = manager.cacheKey(b, 2f, "waifu2x")
        assertTrue(keyA != keyB, "two large pages differing in the middle must not share a key")
        assertTrue(keyA.length == 64 && keyB.length == 64, "sampled keys stay the same shape")
    }

    @Test
    fun `a page past the hash limit keys on its tail as well as its body`(@TempDir root: Path) {
        val manager = UpscaleCacheManager(root.toFile())
        val shared = 300 * 1024
        val a = ByteArray(shared) { 0x33 }
        val b = a.copyOf()
        b[shared - 1] = 0x44 // the very last byte, past every sample window

        assertTrue(manager.cacheKey(a, 2f, "m") != manager.cacheKey(b, 2f, "m"), "the tail must be part of the key")
    }

    // ---------- formatting ----------

    @Test
    fun `byte formatting floors instead of rounding`() {
        UpscaleCacheManager.formatBytes(0) shouldBe "0 MB"
        UpscaleCacheManager.formatBytes(-1) shouldBe "0 MB"
        UpscaleCacheManager.formatBytes(-Long.MAX_VALUE) shouldBe "0 MB"
        UpscaleCacheManager.formatBytes(1) shouldBe "0 KB"
        UpscaleCacheManager.formatBytes(1024) shouldBe "1 KB"
        UpscaleCacheManager.formatBytes(1023 * 1024) shouldBe "1023 KB"
    }

    @Test
    fun `the megabyte fraction is truncated so it never rounds up past the real value`() {
        // 200 KB of 1024 KB is 0.195 MB, and the formatter truncates rather than rounding.
        UpscaleCacheManager.formatBytes(1024 * 1024 + 200 * 1024) shouldBe "1.1 MB"
        UpscaleCacheManager.formatBytes(1536 * 1024) shouldBe "1.5 MB"
        UpscaleCacheManager.formatBytes(1024 * 1024) shouldBe "1.0 MB"
    }

    @Test
    fun `the summary reports the quota rather than the live size`() {
        UpscaleCacheManager.formatSummary(0, 0) shouldBe
            "0 MB of ${UpscaleCacheManager.formatBytes(UpscaleCacheManager.MAX_CACHE_BYTES)} • 0 files • " +
            "${UpscaleCacheManager.TTL_DAYS}-day TTL"
        assertTrue(UpscaleCacheManager.formatSummary(0, 0).contains("200.0 MB"), "quota should read 200 MB")
    }
}
