package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@Inject
class TranslatedPageStore(
    private val context: Context,
) {
    companion object {
        // Keep disk usage bounded: these are per-chapter saved WEBPs, separate from the hash cache.
        private const val MAX_SAVED_BYTES = 256L * 1024 * 1024
        private const val MAX_SAVED_CHAPTERS = 40

        /** Highest index that gets a readable filename; beyond this the index is hashed. */
        private const val MAX_READABLE_PAGE_INDEX = 5000

        // KMK --> Sidecar holding the manga title next to its cached pages, so the
        // per-manga cache screen can name entries after the manga leaves the library.
        const val TITLE_FILE = "title.txt"
        const val POLICY_FILE_PREFIX = "prompt_policy_"
        // KMK <--
    }

    /** What a saved page was produced with, so a configuration change can invalidate it. */
    private data class Stamp(val policy: String?, val identity: String?)

    // KMK --> Titles already on disk this process: skips re-reading on every save.
    private val lastTitles = ConcurrentHashMap<Long, String>()
    // KMK <--

    private fun baseDir(): File = File(context.filesDir, "yakuyomi_saved")

    private fun chapterDir(mangaId: Long, chapterId: Long): File =
        File(baseDir(), "$mangaId/$chapterId").apply { mkdirs() }

    /** Same path as [chapterDir] but without creating it, for read paths. */
    private fun existingChapterDir(mangaId: Long, chapterId: Long): File =
        File(File(context.filesDir, "yakuyomi_saved"), "$mangaId/$chapterId")

    fun pageFile(mangaId: Long, chapterId: Long, pageIndex: Int): File =
        File(chapterDir(mangaId, chapterId), pageFileName(pageIndex))

    /** [pageFile] without the directory side effect, for reads. */
    private fun pageFileIfPresent(mangaId: Long, chapterId: Long, pageIndex: Int): File =
        File(existingChapterDir(mangaId, chapterId), pageFileName(pageIndex))

    /**
     * Filename for [pageIndex].
     *
     * Source pages keep their readable name. A tall page split by the reader produces synthetic
     * segment indices above 2^28, which [save] used to reject outright - so a long strip was
     * re-detected, re-OCR'd and re-translated (paid for again by the LLM) on every single visit.
     * Hashing the out-of-range index gives those pages a stable, bounded name instead. Nothing was
     * ever written under the old rejected range, so this adds capability rather than orphaning
     * files.
     */
    private fun pageFileName(pageIndex: Int): String =
        if (pageIndex in 0..MAX_READABLE_PAGE_INDEX) {
            "page_$pageIndex.webp"
        } else {
            "page_h${sha1(pageIndex.toString())}.webp"
        }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)

    fun loadIfExists(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        expectedPolicyFingerprint: String? = null,
        expectedIdentity: String? = null,
    ): ByteArray? {
        // The read path must not create anything: this runs for every page the reader binds, and
        // mkdirs on a cache lookup means a read-only miss still touches the filesystem.
        val f = pageFileIfPresent(mangaId, chapterId, pageIndex)
        if (!f.isFile || f.length() == 0L) return null
        if (expectedPolicyFingerprint != null || expectedIdentity != null) {
            // A missing stamp cannot be validated against anything, so it counts as stale the
            // moment a caller asks for a fingerprint or an identity.
            val stamp = loadStamp(mangaId, chapterId, pageIndex)
            if (stamp?.policy != expectedPolicyFingerprint) return null
            if (expectedIdentity != null && stamp?.identity != expectedIdentity) return null
        }
        return try {
            f.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        webpBytes: ByteArray,
        mangaTitle: String? = null,
        policyFingerprint: String? = null,
        identity: String? = null,
    ) {
        if (webpBytes.isEmpty() || webpBytes.size > 5 * 1024 * 1024) return
        if (pageIndex < 0) return
        val f = pageFile(mangaId, chapterId, pageIndex)
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(webpBytes)
            if (tmp.length() != webpBytes.size.toLong()) throw IllegalStateException("tmp incomplete")
            if (f.exists() && !f.delete()) throw IllegalStateException("cannot replace")
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {}
        saveStamp(mangaId, chapterId, pageIndex, policyFingerprint, identity)
        saveTitle(mangaId, mangaTitle)
        pruneIfNeeded()
    }

    // KMK --> Persists the manga title beside its cache (see TITLE_FILE). Written only
    // when missing or changed, so steady-state page saves cost a map lookup.
    fun saveTitle(mangaId: Long, title: String?) {
        val clean = title?.takeIf { it.isNotBlank() }?.take(200) ?: return
        if (lastTitles[mangaId] == clean) return
        try {
            val dir = File(baseDir(), "$mangaId").apply { mkdirs() }
            val f = File(dir, TITLE_FILE)
            if (!f.isFile || f.readText() != clean) {
                f.writeText(clean)
            }
            lastTitles[mangaId] = clean
        } catch (_: Exception) {}
    }

    private fun policyFile(mangaId: Long, chapterId: Long, pageIndex: Int): File =
        File(chapterDir(mangaId, chapterId), "$POLICY_FILE_PREFIX${pageFileName(pageIndex)}")

    /** [policyFile] without the directory side effect, for reads. */
    private fun policyFileIfPresent(mangaId: Long, chapterId: Long, pageIndex: Int): File =
        File(existingChapterDir(mangaId, chapterId), "$POLICY_FILE_PREFIX${pageFileName(pageIndex)}")

    /**
     * Reads the stamp, tolerating the pre-identity format where the file held only the policy
     * fingerprint. Such an entry reports a null identity, so it is treated as stale the moment a
     * caller asks for one - which is every caller now.
     */
    private fun loadStamp(mangaId: Long, chapterId: Long, pageIndex: Int): Stamp? = runCatching {
        val f = policyFileIfPresent(mangaId, chapterId, pageIndex)
        if (!f.isFile) return@runCatching null
        val lines = f.readText().split('\n')
        Stamp(
            policy = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            identity = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    private fun saveStamp(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        policyFingerprint: String?,
        identity: String?,
    ) {
        if (policyFingerprint.isNullOrBlank() && identity.isNullOrBlank()) return
        runCatching {
            policyFile(mangaId, chapterId, pageIndex).writeText(
                "${policyFingerprint.orEmpty().take(160)}\n${identity.orEmpty().take(160)}",
            )
        }
    }

    fun loadTitle(mangaId: Long): String? {
        lastTitles[mangaId]?.let { return it }
        return try {
            File(baseDir(), "$mangaId/$TITLE_FILE")
                .takeIf { it.isFile }
                ?.readText()
                ?.takeIf { it.isNotBlank() }
                ?.also { lastTitles[mangaId] = it }
        } catch (_: Exception) {
            null
        }
    }
    // KMK <--

    fun clearForChapter(mangaId: Long, chapterId: Long) {
        try {
            chapterDir(mangaId, chapterId).deleteRecursively()
        } catch (_: Exception) {}
    }

    fun clearAll() {
        try {
            baseDir().listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun pruneIfNeeded() {
        try {
            val base = baseDir()
            val chapters = base.listFiles()?.filter { it.isDirectory }?.flatMap { manga ->
                manga.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } ?: return
            var total = chapters.sumOf { dir -> dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }
            if (chapters.size <= MAX_SAVED_CHAPTERS && total <= MAX_SAVED_BYTES) return
            val ordered = chapters.sortedBy { it.lastModified() }
            for (dir in ordered) {
                if (chapters.size - ordered.indexOf(dir) <= MAX_SAVED_CHAPTERS && total <= MAX_SAVED_BYTES) break
                val size = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                if (dir.deleteRecursively()) {
                    total -= size
                }
                if (ordered.size - ordered.indexOf(dir) <= MAX_SAVED_CHAPTERS / 2 && total <= MAX_SAVED_BYTES / 2) break
            }
        } catch (_: Exception) {}
    }
}
