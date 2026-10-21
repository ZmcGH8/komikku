package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    /**
     * The folder a manga's per-series files live in, or null when the entry is not a folder.
     *
     * Null is the answer for an entry that is a single book file, and that is load-bearing rather
     * than incidental: everything that *writes* here - ComicInfo.xml, cover.jpg - resolves through
     * this, so a single-file entry gets no folder to write into instead of having one of the base
     * directory's files attributed to it.
     */
    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        val entry = getBaseDirectory()?.findFile(name) ?: return emptyList()
        // A book that is a single archive in the base directory is an entry with no folder, so it
        // has no children either - it is its own only chapter. Directories win when a name could
        // resolve to either, so an existing comic folder keeps listing its contents.
        return when {
            entry.isDirectory -> entry.listFiles().orEmpty().toList()
            Archive.isSupportedSingleFile(entry) -> listOf(entry)
            else -> emptyList()
        }
    }

    /**
     * The file a chapter URL names, or null when it names nothing.
     *
     * A chapter URL is `"<series>/<file>"`. `<series>` is normally a folder the file lives inside,
     * but an entry that is a single book file has no folder: there, the entry *is* the file, and
     * the URL repeats its name on both sides of the slash. Both shapes are resolved in one place
     * so the format lookup and the book reader cannot disagree about which file a URL names.
     */
    fun findChapterFile(series: String, file: String): UniFile? {
        val entry = getBaseDirectory()?.findFile(series) ?: return null
        return if (entry.isDirectory) entry.findFile(file) else entry.takeIf { it.name == file }
    }
}
