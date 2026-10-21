package eu.kanade.tachiyomi.ui.reader.setting

import java.io.File
import java.security.MessageDigest

/**
 * Cache manager for upscaled pages. Extracted from [UpscaleEngine] to isolate
 * I/O, hashing, and eviction concerns (single-responsibility).
 *
 * Fixes:
 * - B2: Hash covers the full content for small pages and five spread samples
 *   for large ones (manga pages share JPEG headers/backgrounds, so a single
 *   prefix sample collides across distinct pages).
 * - B3/B9: Prune and read/write are synchronized to avoid concurrent `listFiles` races.
 */
class UpscaleCacheManager(cacheRoot: File) {

    companion object {
        private const val CACHE_KEY_VERSION = "upscale-cache-v3"
        private const val FULL_HASH_MAX_BYTES = 256 * 1024
        private const val TMP_GRACE_MILLIS = 60L * 60 * 1000
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        const val TTL_DAYS = 30L
        private const val JPEG_SOI_0 = 0xFF
        private const val JPEG_SOI_1 = 0xD8
        private const val JPEG_MARKER_3 = 0xFF

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 MB"
            val mb = bytes / (1024 * 1024)
            val kb = (bytes % (1024 * 1024)) / 1024
            return if (mb == 0L) "$kb KB" else "$mb.${(kb / 102.4).toInt()} MB"
        }

        fun formatSummary(bytes: Long, count: Int): String {
            return "${formatBytes(bytes)} of ${formatBytes(MAX_CACHE_BYTES)} • $count files • $TTL_DAYS-day TTL"
        }
    }

    private val cacheDir: File = File(cacheRoot, "upscale_cache").apply {
        try {
            mkdirs()
        } catch (_: Exception) {}
    }
    private val maxCacheBytes = MAX_CACHE_BYTES
    private val maxSingleFileBytes = 20L * 1024 * 1024
    private val ttlMillis = TTL_DAYS * 24 * 60 * 60 * 1000
    private val lock = Any()

    fun cacheKey(bytes: ByteArray, factor: Float, model: String): String {
        return cacheKeyDetailed(bytes, factor, model, "", "")
    }

    fun cacheKeyDetailed(
        bytes: ByteArray,
        factor: Float,
        model: String,
        extra1: String,
        extra2: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_KEY_VERSION.toByteArray())
        if (bytes.isNotEmpty()) {
            if (bytes.size <= FULL_HASH_MAX_BYTES) {
                digest.update(bytes)
            } else {
                // Five spread samples: pages from the same chapter share
                // headers and backgrounds, so clustered samples collide.
                sample(digest, bytes, 0, 64 * 1024)
                sample(digest, bytes, bytes.size / 4, 32 * 1024)
                sample(digest, bytes, bytes.size / 2, 32 * 1024)
                sample(digest, bytes, bytes.size * 3 / 4, 32 * 1024)
                val tailLen = minOf(32 * 1024, bytes.size)
                digest.update(bytes, bytes.size - tailLen, tailLen)
            }
        }
        digest.update(bytes.size.toString().toByteArray())
        val safeFactor = if (factor.isFinite()) factor else 2f
        val normalizedExtras = buildString {
            append(safeFactor)
            append('|')
            append(model.take(64))
            if (extra1.isNotEmpty()) {
                append('|')
                append(extra1.take(64))
            }
            if (extra2.isNotEmpty()) {
                append('|')
                append(extra2.take(64))
            }
        }
        digest.update(normalizedExtras.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(digest: MessageDigest, bytes: ByteArray, offset: Int, maxLen: Int) {
        if (offset < 0 || offset >= bytes.size) return
        val len = minOf(maxLen, bytes.size - offset)
        if (len > 0) digest.update(bytes, offset, len)
    }

    fun getCached(key: String): ByteArray? {
        if (!isValidKey(key)) return null
        val file = synchronized(lock) { resolveCachedFileLocked(key) } ?: return null
        return try {
            // Through a stream rather than File.readBytes(): the latter sizes its result from the
            // file length and then reallocates as it reads, so an 18 MB entry costs 18 MB plus a
            // second 18 MB array - on the reader path, where the caller's own copy of the page is
            // still live.
            val bytes = file.inputStream().buffered().use { it.readBytes() }
            if (!hasCompleteImageHeader(bytes)) {
                try {
                    file.delete()
                } catch (_: Exception) {}
                return null
            }
            try {
                synchronized(lock) { file.setLastModified(System.currentTimeMillis()) }
            } catch (_: Exception) {}
            bytes
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One key can hold either PNG or WebP, because [UpscaleEngine.compressForCache] picks the
     * format by encoded size, so the magic has to accept both. A file whose header is neither is
     * a write that never finished - process death inside `compress`, or the OS reclaiming the
     * file while the decoder still had it mapped - and handing those bytes to the reader as a
     * finished page shows a half-decoded image until the 30-day TTL evicts it. Drop it instead.
     */
    private fun hasCompleteImageHeader(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == JPEG_SOI_0.toByte() &&
            bytes[1] == JPEG_SOI_1.toByte() &&
            bytes[2] == JPEG_MARKER_3.toByte()
        ) {
            return true
        }
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    }

    private fun resolveCachedFileLocked(key: String): File? {
        val file = File(cacheDir, "$key.webp")
        return try {
            if (!file.exists()) return null
            val len = file.length()
            if (len <= 0 || len > maxSingleFileBytes) {
                try {
                    file.delete()
                } catch (_: Exception) {}
                return null
            }
            if (ttlMillis > 0 && System.currentTimeMillis() - file.lastModified() > ttlMillis) {
                try {
                    file.delete()
                } catch (_: Exception) {}
                return null
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    fun putCached(key: String, bytes: ByteArray) {
        if (!isValidKey(key)) return
        if (bytes.isEmpty() || bytes.size > maxSingleFileBytes) return
        val tmp: File
        try {
            ensureCacheDir()
            tmp = File(cacheDir, "$key.tmp.${System.nanoTime()}")
            tmp.writeBytes(bytes)
            if (tmp.length() != bytes.size.toLong()) {
                try {
                    tmp.delete()
                } catch (_: Exception) {}
                return
            }
        } catch (_: Exception) {
            return
        }
        synchronized(lock) {
            try {
                val target = File(cacheDir, "$key.webp")
                if (!tmp.renameTo(target)) {
                    try {
                        target.delete()
                    } catch (_: Exception) {}
                    if (!tmp.renameTo(target)) {
                        try {
                            tmp.copyTo(target, overwrite = true)
                        } finally {
                            try {
                                tmp.delete()
                            } catch (_: Exception) {}
                        }
                    }
                }
                pruneIfNeededLocked()
            } catch (_: Exception) {
                try {
                    tmp.delete()
                } catch (_: Exception) {}
            }
        }
    }

    fun clear() = synchronized(lock) {
        try {
            cacheDir.listFiles()?.forEach {
                try {
                    it.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun fileCount(): Int = synchronized(lock) {
        try {
            cacheDir.listFiles()?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun pruneExpired() = synchronized(lock) {
        pruneExpiredLocked()
    }

    private fun ensureCacheDir() {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
        } catch (_: Exception) {}
    }

    private fun isValidKey(key: String): Boolean {
        if (key.length != 64) return false
        for (c in key) if (c !in '0'..'9' && c !in 'a'..'f') return false
        return true
    }

    private fun pruneIfNeededLocked() {
        try {
            pruneExpiredLocked()
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= maxCacheBytes) break
                val len = f.length()
                try {
                    if (f.delete()) total -= len
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun pruneExpiredLocked() {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { f ->
                try {
                    if (isAbandonedTmpFile(f, now)) {
                        f.delete()
                    } else if (now - f.lastModified() > ttlMillis) {
                        f.delete()
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun isAbandonedTmpFile(f: File, now: Long): Boolean {
        if (!f.isFile || ".tmp." !in f.name) return false
        return try {
            now - f.lastModified() > TMP_GRACE_MILLIS
        } catch (_: Exception) {
            false
        }
    }
}
