package mihon.core.archive

import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves resources out of the EPUB archives this host currently has open, and owns their lifetime.
 *
 * ### What it owns
 *
 * A mapped archive is a lease, and something has to release it. A reading session that opens fifty
 * books and never gives any of them back holds fifty mmaps and fifty file descriptors until the
 * process dies — the user is the only thing that frees them, by killing the app.
 *
 * ### Why the lease is refcounted
 *
 * The obvious release is `ArchiveReader.close()`, and it is wrong in a way that crashes rather than
 * misbehaves. A renderer does not read a resource on the thread that asked for it: it asks here from
 * its own threads, gets a live `InputStream` positioned inside a memory-mapped archive, and reads
 * from it over the following milliseconds. `close()` is an `Os.munmap` — it does not wait, it does
 * not refcount, and nothing in libarchive knows a stream is still open. Closing a book while one of
 * its streams is mid-read turns the next read into a read of unmapped memory: a `SIGSEGV` in native
 * code, no catchable exception, no log line, on a device, in a book, while the user is reading.
 *
 * So [HeldReader] counts the streams it has handed out and unmaps when the last one closes —
 * whether that is right now or after the renderer is finished. [release] only marks an entry
 * retired; it never tears down memory another thread can still reach.
 *
 * The count is taken *before* the stream is opened, not after. The alternative leaves a window in
 * which [release] observes zero outstanding streams, unmaps, and the thread halfway through opening
 * one then returns a stream pointing into freed memory. Claiming first means an unmap can never
 * overtake a handout.
 *
 * ### Why this lives in `core:archive`
 *
 * The manga local source serves books, and so does anything else that ever grows a book half — and
 * a source and the reader cannot depend on each other. The lease and the URL space are identical for
 * every one of them and must stay identical, so all build on this.
 */
class EpubResourceServer<K : Any>(
    /**
     * The stable identity of a book, used as the map key.
     *
     * A separate function rather than keying on [K] directly because both hosts hold an `SChapter`
     * while the release path only ever has its URL — the chapter URL is the identity that survives a
     * library refresh, so it is the thing both sides agree on.
     */
    private val keyOf: (K) -> String,
    private val bookProvider: (K) -> EpubReader?,
) : Closeable {

    private val readers = ConcurrentHashMap<String, HeldReader>()

    /**
     * The reader backing [key], opening it on first use.
     *
     * `putIfAbsent` rather than `getOrPut`: `getOrPut` is not atomic, so two threads arriving at a
     * cold entry would each open a book and one of the two readers would be discarded without ever
     * being closed — leaking an mmap and a file descriptor per race. `putIfAbsent` hands the loser
     * the winner's instance, which it then retires.
     *
     * Returns null when the book cannot be opened, and does not cache that: a file that appears
     * later (a sync finishing mid-session) should still be picked up.
     */
    fun readerFor(key: K): EpubReader? {
        val id = keyOf(key)
        readers[id]?.let { return it.reader }

        val opened = bookProvider(key) ?: return null
        val fresh = HeldReader(opened)
        val existing = readers.putIfAbsent(id, fresh)
        if (existing != null) {
            // Lost the race; retire ours so its mmap is not leaked.
            fresh.retire()
            return existing.reader
        }
        return opened
    }

    /**
     * Hands an already-open reader to this server to own.
     *
     * For the cold path: a caller that needs a book before the loader has asked for one (the contents
     * sheet can be opened before the first page loads) opens it itself and adopts it here. The obvious
     * alternative — open it privately, use it, close it in a `use` block — is wrong for the shared case,
     * because the reader may be the very instance the renderer is streaming images out of, and closing
     * it unmaps the archive out from under a chapter already on screen.
     *
     * Adopting means there is exactly one owner for every mapped archive in the process and one release
     * path for all of them, which is the property the crash described on this class exists to protect.
     * The loser of a race retires its own reader, exactly as in [readerFor].
     */
    fun adopt(key: String, reader: EpubReader): EpubReader {
        val fresh = HeldReader(reader)
        val existing = readers.putIfAbsent(key, fresh)
        if (existing != null) {
            // Someone else got there first; their instance is the one in use, so ours is surplus.
            fresh.retire()
            return existing.reader
        }
        return reader
    }

    /**
     * Retires the reader for [key].
     *
     * Called when the host is done with that chapter. Returns as soon as the book is unreachable;
     * the unmap itself happens when the last stream handed out for it is closed, so this is safe to
     * call from anywhere, at any time, including while a renderer is still reading out of it.
     */
    fun release(key: String) {
        readers.remove(key)?.retire()
    }

    /** Retires every reader. */
    fun releaseAll() {
        readers.keys.toList().forEach(::release)
    }

    /**
     * Opens an in-archive resource named by [archivePath].
     *
     * A single active reader is enough and is what a renderer sees in practice: one chapter on screen
     * at a time. When more than one is registered (a chapter opened for preview while another is
     * being read) the first match wins, which is the one the visible page came from.
     *
     * The result is capped at [MAX_RESOURCE_BYTES]. A zip entry's declared size is whatever the file
     * claims and its compression ratio is unbounded, so streaming one into a renderer with no ceiling
     * turns a 40 KB entry into an allocation it cannot survive. Capping here rather than in the
     * renderer means the bound travels with the archive: every host that streams from one gets it.
     */
    fun openArchivePath(archivePath: String): InputStream? {
        for (held in readers.values) {
            val raw = held.open { reader.getInputStream(archivePath) } ?: continue
            return LimitedInputStream(raw, MAX_RESOURCE_BYTES)
        }
        return null
    }

    /** Opens the resource [absoluteUrl] names, or null when it is not in the archive namespace. */
    fun openResource(absoluteUrl: String): InputStream? {
        val path = EpubArchiveUrls.archivePathOf(absoluteUrl) ?: return null
        return openArchivePath(path)
    }

    /**
     * Content type for [absoluteUrl], or null when nothing is known about it.
     *
     * ### Why the extension is only the first answer
     *
     * [EpubArchiveUrls.contentTypeForPath] decides by extension, which is right until a book uses one
     * nobody has a row for or names a JPEG `.bin`. The renderer is then handed a type it did not ask
     * for and declines to decode it, so the illustration never arrives — with no error anywhere,
     * because the document's request was perfectly well formed.
     *
     * So an unrecognised extension falls through to [EpubMimeSniff] on the first bytes of the
     * entry. That is a second `getInputStream` on the same archive rather than a cached guess, which
     * is affordable because it happens at most once per resource and only for the ones the table
     * cannot answer.
     *
     * Null when neither can answer. The host's own fallback is then responsible, and it is a
     * deliberate one: a renderer asked for `application/octet-stream` declines to decode it exactly
     * as it declines to decode a wrongly-typed image, so a wrong guess buys nothing over saying so.
     */
    fun contentTypeFor(absoluteUrl: String): String? {
        val path = EpubArchiveUrls.archivePathOf(absoluteUrl) ?: return null
        EpubArchiveUrls.contentTypeForPath(path)?.let { return it }
        for (held in readers.values) {
            val stream = held.open { reader.getInputStream(path) } ?: continue
            return try {
                EpubMimeSniff.ofStream(stream)
            } finally {
                stream.close()
            }
        }
        return null
    }

    override fun close() = releaseAll()

    /**
     * One open book, and the streams currently being read out of it.
     *
     * The invariant, in one line: **the archive is unmapped if and only if no stream handed out for
     * it is still open.** Everything else here exists to make that true when [retire] races an open.
     */
    private class HeldReader(val reader: EpubReader) {

        private val lock = Any()

        /** Streams handed out and not yet closed. */
        private var outstanding = 0

        /** Set by [retire]; no new stream is opened after this. */
        private var retired = false

        /** Set once the unmap has actually happened, so it can never happen twice. */
        private var unmapped = false

        /**
         * Opens a stream from this book, counted from before the open.
         *
         * Null once retired, and null when the block finds nothing, so a caller can simply try the
         * next book.
         *
         * [block] is a receiver lambda rather than an argument-taking one so that `reader` inside it
         * resolves to this book's reader and not to anything in scope at the call site.
         */
        fun open(block: HeldReader.() -> InputStream?): InputStream? {
            synchronized(lock) {
                if (retired) return null
                outstanding++
            }

            val stream = try {
                this.block()
            } catch (e: Throwable) {
                release()
                throw e
            }
            if (stream == null) {
                release()
                return null
            }
            return TrackedInputStream(stream)
        }

        /** Retires this book. The unmap is deferred to the last outstanding stream's close. */
        fun retire() {
            val unmapNow = synchronized(lock) {
                retired = true
                claimUnmap()
            }
            if (unmapNow) unmap()
        }

        /** One stream finished. The last one to leave unmaps, if the book was already retired. */
        private fun release() {
            val unmapNow = synchronized(lock) {
                outstanding--
                claimUnmap()
            }
            if (unmapNow) unmap()
        }

        /** Caller must hold [lock]. */
        private fun claimUnmap(): Boolean {
            if (!retired || outstanding > 0 || unmapped) return false
            unmapped = true
            return true
        }

        private fun unmap() {
            // Nothing here is worth propagating: the caller is a renderer thread whose only useful
            // move on failure is to show the resource as missing.
            runCatching { reader.close() }
        }

        /** Closes the underlying stream once, then hands the count back. */
        private inner class TrackedInputStream(delegate: InputStream) : FilterInputStream(delegate) {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    release()
                }
            }
        }
    }

    companion object {
        /**
         * Ceiling on one streamed resource.
         *
         * Far above any real illustration, stylesheet or font, and far below the heap a low-end
         * device gives away before the process is killed. A zip entry claiming more is not a
         * resource this reader is willing to materialise.
         */
        const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024
    }
}
