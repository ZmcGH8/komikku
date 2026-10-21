package eu.kanade.tachiyomi.data.cache

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
@Inject
@SingleIn(AppScope::class)
class CoverCache(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "covers"
        private const val CUSTOM_COVERS_DIR = "covers/custom"

        /**
         * Slack allowed when reading a stamped time back.
         *
         * Filesystems that round rather than truncate would report a value a little under the one
         * asked for, and demanding an exact match would send every one of those down the rewrite
         * path. Large against the coarsest granularity a filesystem plausibly uses (a second, or
         * even two seconds for FAT-derived ones) and tiny against a retention floor of one day.
         */
        private const val STAMP_TOLERANCE_MS = 2_000L
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir = getCacheDir(COVERS_DIR)

    private val customCoverCacheDir = getCacheDir(CUSTOM_COVERS_DIR)

    /**
     * Returns the cover from cache.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return cover image.
     */
    fun getCoverFile(mangaThumbnailUrl: String?): File? {
        return mangaThumbnailUrl?.let {
            File(cacheDir, DiskUtil.hashKeyForDisk(it))
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param mangaId the manga id.
     * @return cover image.
     */
    fun getCustomCoverFile(mangaId: Long?): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(mangaId.toString()))
    }

    /**
     * Saves the given stream as the manga's custom cover to cache.
     *
     * @param manga the manga.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(manga: Manga, inputStream: InputStream) {
        getCustomCoverFile(manga.id).outputStream().use {
            inputStream.copyTo(it)
        }
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * Not what a library removal wants any more - see [hasRetainedCover]. Kept for the paths that
     * genuinely mean "this image should not be on disk", such as a source replacing a cover URL.
     *
     * @param manga the manga.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        getCoverFile(manga.thumbnailUrl)?.let {
            if (it.exists() && it.delete()) ++deleted
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(manga.id)) ++deleted
        }

        return deleted
    }

    /**
     * Whether a cover for [manga] is on disk, whether or not it is still in the library.
     *
     * Used on removal to decide whether anything is worth keeping, and whether the caller needs to
     * bump `coverLastModified` so a visible cover redraws.
     */
    fun hasRetainedCover(manga: Manga): Boolean =
        getCoverFile(manga.thumbnailUrl)?.exists() == true || getCustomCoverFile(manga.id).exists()

    /**
     * Stamps this manga's cached covers with [timestamp] so their retention window starts now.
     *
     * [pruneOrphanedCovers] has only the file to go on, so the file's mtime *is* the clock the
     * window is measured against. Without this stamp a cover that was written months before the
     * manga left the library is already "too old" the moment it is retained, and every duration
     * from one day to thirty behaves exactly like deleting immediately.
     *
     * Only files that already exist are touched: a missing cover must not be conjured into an
     * empty file just to carry a timestamp.
     *
     * [File.setLastModified] is verified rather than trusted, and falls back to rewriting the file
     * when the stamp did not take. App-specific external storage is FUSE-backed, where setting an
     * mtime can fail and return false while leaving the old one in place - which is invisible from
     * here and means the very next prune sees the cover as overdue, i.e. the retention setting
     * silently behaving as IMMEDIATE. Rewriting is the portable way to set a modification time,
     * since the write itself always stamps it.
     *
     * @return whether any cover now carries [timestamp].
     */
    fun markRetained(manga: Manga, timestamp: Long = System.currentTimeMillis()): Boolean {
        var stamped = false
        getCoverFile(manga.thumbnailUrl)?.let { file ->
            if (file.exists() && stampModified(file, timestamp)) stamped = true
        }
        getCustomCoverFile(manga.id).let { file ->
            if (file.exists() && stampModified(file, timestamp)) stamped = true
        }
        if (!stamped) {
            logcat(LogPriority.WARN) {
                "Could not stamp a removed cover for manga ${manga.id}; its retention window " +
                    "will start from the cover's download time instead"
            }
        }
        return stamped
    }

    /**
     * Puts [timestamp] on [file]'s modification time, confirming it actually took.
     *
     * The read-back is the point: a filesystem that rejects the call reports nothing, and a cover
     * left carrying its download time is deleted on the next prune whatever the setting says. A
     * rewrite is the fallback, done through a sibling temp file so a failure part-way cannot leave
     * the cover truncated.
     *
     * Confirmation is by [STAMP_TOLERANCE_MS] rather than equality: the requested time carries
     * milliseconds and a second-granularity filesystem rounds it, so demanding an exact match
     * would send every stamp down the rewrite path.
     */
    private fun stampModified(file: File, timestamp: Long): Boolean {
        if (touch(file, timestamp)) return true

        val rewritten = runCatching {
            val temp = File(file.parentFile, "${file.name}.retention")
            temp.outputStream().use { output -> file.inputStream().use { input -> input.copyTo(output) } }
            // Length-checked before the swap, so a copy that fell short cannot be promoted.
            val complete = temp.length() == file.length()
            if (complete && temp.renameTo(file)) {
                file.setLastModified(timestamp)
            } else {
                temp.delete()
            }
            complete
        }.getOrDefault(false)

        // The rewrite itself stamped it, so this only has to rule out a filesystem that is still
        // reporting something older than the window we asked for.
        return rewritten && file.lastModified() >= timestamp - STAMP_TOLERANCE_MS
    }

    private fun touch(file: File, timestamp: Long): Boolean =
        file.setLastModified(timestamp) && file.lastModified() >= timestamp - STAMP_TOLERANCE_MS

    /** Cache key a library cover is stored under, for handing to [pruneOrphanedCovers]. */
    fun libraryCoverKey(manga: Manga): String? = manga.thumbnailUrl?.let { DiskUtil.hashKeyForDisk(it) }

    /** Cache key a custom cover is stored under, for handing to [pruneOrphanedCovers]. */
    fun customCoverKey(mangaId: Long): String = DiskUtil.hashKeyForDisk(mangaId.toString())

    /**
     * Deletes covers that are both older than [retentionMillis] and named in neither [referencedKeys]
     * nor anything the library still points at.
     *
     * The membership test is the whole point. Age alone cannot tell an orphan from a cover that has
     * simply been on disk for months, and pruning by age would quietly re-download the library's own
     * covers. [referencedKeys] is built from the library by the caller, which is the only place that
     * knows it.
     *
     * @return how many files were deleted.
     */
    fun pruneOrphanedCovers(referencedKeys: Set<String>, retentionMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - retentionMillis
        var deleted = 0

        // The custom directory is nested inside the library one, so it is walked separately: a
        // custom cover is keyed by manga id rather than by URL and would never appear in the
        // library directory anyway, but listing the parent would walk it a second time.
        cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in referencedKeys) return@forEach
            if (file.lastModified() >= cutoff) return@forEach
            if (file.delete()) deleted++
        }
        customCoverCacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in referencedKeys) return@forEach
            if (file.lastModified() >= cutoff) return@forEach
            if (file.delete()) deleted++
        }

        return deleted
    }

    /**
     * Delete custom cover of the manga from the cache
     *
     * @param mangaId the manga id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(mangaId: Long?): Boolean {
        return getCustomCoverFile(mangaId).let {
            it.exists() && it.delete()
        }
    }

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
