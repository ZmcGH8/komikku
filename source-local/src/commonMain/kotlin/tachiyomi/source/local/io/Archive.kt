package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension

object Archive {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")

    fun isSupported(file: UniFile): Boolean {
        return file.extension?.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }

    /**
     * Whether [file] is a book file that can stand as an entry of its own.
     *
     * A comic folder is the normal shape of a local entry, but a novel is often a single `.epub`
     * sitting directly in the base directory with no folder around it. Such a file is its own
     * entry: it has one chapter, and nothing lists it unless it is recognised as one, which is why
     * this is a separate predicate from [isSupported] rather than a caller-side special case.
     *
     * `epub` is not in [SUPPORTED_ARCHIVE_TYPES] because a `.epub` is not an archive the comic
     * reader unpacks into pages - it is read through the book reader - so it is only ever a
     * candidate here, never a chapter of a comic folder.
     */
    fun isSupportedSingleFile(file: UniFile): Boolean {
        return !file.isDirectory &&
            (isSupported(file) || file.extension.equals("epub", ignoreCase = true))
    }
}
