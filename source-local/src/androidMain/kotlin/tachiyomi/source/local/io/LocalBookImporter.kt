package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Files picked books are copied into, under the local source directory.
 *
 * ### Why a copy and not a reference
 *
 * The picker hands back a content URI, which is a grant the app holds until the process dies. A
 * book that referenced one would stop resolving after a reboot or a restart, and would quietly fail
 * at the moment the reader opened it rather than at the moment it was imported. Copying costs disk
 * once and makes the import a fact rather than a promise.
 *
 * ### Why a folder and not one file per book
 *
 * The local source lists folders, not files, so a loose `.epub` is an entry of its own with no way
 * to group several. One folder per import is what turns "four files I picked" into "one series".
 */
@SingleIn(AppScope::class)
@Inject
class LocalBookImporter(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    /**
     * Copies [uris] into a new folder called [name] under the local source directory.
     *
     * Names that collide are suffixed rather than overwritten: an import that quietly replaced a
     * series would lose the only copy of it, and the reader would have no way to know.
     *
     * Returns the folder on success, or null when the local directory is unreachable. Individual
     * files that cannot be read are skipped and reported, because one unreadable pick should not
     * throw away the rest of the import.
     */
    suspend fun import(name: String, uris: List<Uri>): Result {
        val folderName = LocalBookFolder.sanitize(name)
            ?: return Result.Failed(IllegalArgumentException("Nothing usable in the name"))

        val base = fileSystem.getBaseDirectory()
            ?: return Result.Failed(IllegalStateException("The local source directory is not set up"))

        val folder = uniqueFolder(base, folderName)
            ?: return Result.Failed(IllegalStateException("Could not create a folder named $folderName"))

        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (uri in uris) {
            val fileName = fileNameFor(uri) ?: run {
                skipped += uri.toString()
                continue
            }
            val target = folder.createFile(fileName)
            if (target == null) {
                skipped += fileName
                continue
            }
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.openOutputStream().use { output -> input.copyTo(output) }
                } ?: error("The picked file could not be opened")
                copied += fileName
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not import $fileName into the local source" }
                target.delete()
                skipped += fileName
            }
        }

        logcat(LogPriority.INFO) { "Imported ${copied.size} book(s) into $folderName, skipped ${skipped.size}" }
        return if (copied.isEmpty()) {
            Result.Failed(IllegalStateException("None of the picked files could be copied"))
        } else {
            Result.Imported(folderName, copied, skipped)
        }
    }

    /**
     * A folder called [name] that does not exist yet.
     *
     * Suffixed with a counter, because the alternative — writing into an existing folder — merges
     * two imports into one series and lets the newer copy of a chapter shadow the older.
     */
    private fun uniqueFolder(base: UniFile, name: String): UniFile? {
        if (base.findFile(name) == null) return base.createDirectory(name)
        for (n in 2..MAX_COLLISION_ATTEMPTS) {
            val candidate = "$name ($n)"
            if (base.findFile(candidate) == null) return base.createDirectory(candidate)
        }
        return null
    }

    /**
     * The name to file [uri] under, or null when it is not a book this app can open.
     *
     * The display name the provider offers is preferred, falling back to the last path segment —
     * some providers answer with nothing useful for a file that arrived by share intent.
     *
     * Only `.epub` is accepted, and the check is on the name rather than on the MIME type because
     * the type is whatever the sending app decided: files picked out of a file manager arrive as
     * `application/octet-stream` often enough that trusting it would reject half of them. A file
     * with no extension is *not* given one — inventing `.epub` for a file called "mybook" would
     * put something in the library that cannot be opened, and would do it silently.
     */
    private fun fileNameFor(uri: Uri): String? {
        val raw = displayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: return null
        val cleaned = raw.trim().replace('/', '_').replace('\\', '_')
        if (cleaned.isEmpty()) return null
        return cleaned.takeIf {
            // `substringAfterLast` on the name, not the `UniFile.extension` extension property: this
            // is a String and the extension function only answers for a UniFile.
            it.substringAfterLast('.', "").equals(EPUB_EXTENSION, ignoreCase = true)
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (_: Exception) {
        null
    }

    sealed interface Result {
        data class Imported(val folder: String, val copied: List<String>, val skipped: List<String>) : Result
        data class Failed(val error: Throwable) : Result
    }

    private companion object {
        const val MAX_COLLISION_ATTEMPTS = 100
        const val EPUB_EXTENSION = "epub"
    }
}
