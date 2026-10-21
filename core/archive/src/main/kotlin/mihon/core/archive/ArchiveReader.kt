package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

/**
 * Memory-mapped archive access.
 *
 * ### Why there is a name index
 *
 * libarchive is a *sequential* format reader: to reach entry _k_ it has to read the _k-1 headers
 * before it, and each [ArchiveInputStream] is its own independent cursor over a freshly-opened
 * archive. So the obvious `getInputStream(name)` — walk until the name matches — costs O(entries)
 * per lookup, every lookup.
 *
 * That is not a micro-optimisation. A comic with 400 pages pays 400 full walks; an EPUB with two
 * thousand files and four hundred referenced resources pays two thousand walks per *image*, because
 * every name mismatch falls through to another candidate spelling and another walk. It is also the
 * cheapest possible way for a hostile file to hurt: ten thousand junk entries turn every asset
 * request into a ten-thousand-entry parse.
 *
 * So the names are collected once, during the pass that already existed to detect encryption, and
 * every lookup is answered from that set before a single header is touched. A miss — which is the
 * overwhelmingly common case, and the one the reader hits for every URL the renderer probes — costs
 * nothing at all. A hit still walks, because there is no way to seek here short of reimplementing
 * libarchive, but it walks **once** instead of once per candidate spelling.
 *
 * ### One pass, two answers
 *
 * Detecting encryption and collecting names are the same traversal. Doing them together costs
 * nothing for an unencrypted archive (which already walked every entry) and halves the cost of an
 * encrypted one.
 */
class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {

    val size = pfd.statSize
    val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    /**
     * Every entry name in the archive, or empty if the archive has none.
     *
     * Built in `init`, so it is final and immutable for the reader's lifetime and needs no
     * synchronisation on the read path.
     */
    private val entryNames: Set<String>

    // SY -->
    var encrypted: Boolean = false
        private set
    var wrongPassword: Boolean? = null
        private set
    val archiveHashCode = pfd.hashCode()
    // SY <--

    init {
        entryNames = indexEntries()
    }

    /**
     * True when the archive contains an entry called [name].
     *
     * O(1), and does not open a stream. `contains` on a path exists precisely so callers do not have
     * to pay for a lookup they are only going to throw away.
     */
    operator fun contains(name: String): Boolean = name in entryNames

    inline fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(
        address,
        size,
        // SY -->
        encrypted,
        // SY <--
    ).use { block(generateSequence { it.getNextEntry() }) }

    fun getInputStream(entryName: String): InputStream? = getInputStreamAny(entryName)

    /**
     * Opens the first of [names] the archive actually contains, in one walk.
     *
     * Callers that can express a reference several ways — an EPUB href matches its stored zip name
     * either decoded or re-encoded — used to walk the archive once per spelling. This walks once
     * and takes whichever spelling is present, which is the whole reason the candidate list exists.
     *
     * Returns null when none of them is present, without opening an archive at all.
     */
    fun getInputStreamAny(vararg names: String): InputStream? {
        val wanted = names.asSequence().filter { it in entryNames }.toSet()
        if (wanted.isEmpty()) return null
        return walkTo(wanted)
    }

    /**
     * Walks the archive and returns the cursor sitting on the first entry in [wanted], or null.
     *
     * Deliberately does not consult [entryNames]: it is also used by [isPasswordIncorrect], which
     * runs *while* the index is being built, so the set is not assigned yet at that point. Reading
     * it there would be an uninitialised-field access, and the probe is the one caller that must
     * work before construction has finished.
     */
    private fun walkTo(wanted: Set<String>): InputStream? {
        val archive = ArchiveInputStream(address, size, /* SY --> */ encrypted /* SY <-- */)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name in wanted) return archive
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // SY -->
    /**
     * Collects every entry name and, in the same pass, notices whether anything is encrypted.
     *
     * The walk no longer stops at the first encrypted entry: the names have to be complete for the
     * index to be worth anything, and finishing the traversal costs what an unencrypted archive was
     * already paying.
     */
    private fun indexEntries(): Set<String> {
        val names = LinkedHashSet<String>()
        var firstEncrypted: String? = null

        val archive = ArchiveInputStream(address, size, false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                names += entry.name
                if (firstEncrypted == null && entry.isEncrypted) firstEncrypted = entry.name
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()

        firstEncrypted?.let {
            encrypted = true
            isPasswordIncorrect(it)
        }
        return names
    }
    // SY <--

    private fun isPasswordIncorrect(entryName: String) {
        try {
            // walkTo, not getInputStream: see its comment. This runs from `init`.
            walkTo(setOf(entryName)).use { stream ->
                stream?.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        Os.munmap(address, size)
    }
}
