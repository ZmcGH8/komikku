// Stub for the libarchive-backed ArchiveReader, which cannot compile without the native library on
// the classpath. Only used by /tmp/opencode/synchk/check.sh.
//
// Mirrors the *public* surface of the real class and nothing else — enough for EpubReader to compile
// against it. If you add a method here, add it to the real ArchiveReader too, or this stub quietly
// stops proving anything.
@file:Suppress("unused")

package mihon.core.archive

import java.io.Closeable
import java.io.InputStream

class ArchiveReader : Closeable {
    val encrypted: Boolean = false

    fun getInputStream(name: String): InputStream? = null

    /** Multi-candidate lookup: opens the first of [names] the archive holds, in one walk. */
    fun getInputStreamAny(vararg names: String): InputStream? = null

    /** Entry-name membership, answered from the index rather than by opening anything. */
    operator fun contains(name: String): Boolean = false

    override fun close() = Unit
}
