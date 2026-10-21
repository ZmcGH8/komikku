package exh.eh

import androidx.core.util.AtomicFile
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.core.concurrency.AppDispatchersHolder
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * In memory Int -> Obj lookup table implementation that
 * automatically persists itself to disk atomically and asynchronously.
 *
 * Bounded: the oldest inserted entry is evicted once [maxEntries] is reached, so
 * neither the heap nor the backing file can grow without limit.
 *
 * Thread safe
 *
 * @author nulldev
 */
class MemAutoFlushingLookupTable<T>(
    file: File,
    private val serializer: EntrySerializer<T>,
    private val debounceTimeMs: Long = 3000,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : CoroutineScope by CoroutineScope(AppDispatchersHolder.get().io + SupervisorJob()), Closeable {

    // Insertion ordered rather than access ordered: eviction drops the oldest
    // insert, which keeps get() non-mutating so it only needs the read lock.
    private val table = object : LinkedHashMap<Int, T>(INITIAL_SIZE, LOAD_FACTOR, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, T>?): Boolean =
            this.size > maxEntries
    }

    private val rwLock = ReentrantReadWriteLock()
    private val loadGate = CompletableDeferred<Unit>()

    // Used to debounce
    @Volatile
    private var writeCounter = 0L

    @Volatile
    private var flushed = true

    private val atomicFile = AtomicFile(file)

    private val shutdownHook = thread(start = false) {
        if (!flushed) runCatching { writeSnapshot() }
    }

    init {
        initialLoad()

        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    private fun BufferedSource.requireBytes(targetArray: ByteArray, byteCount: Int): Boolean {
        var readIter = 0
        while (true) {
            val readThisIter = read(targetArray, readIter, byteCount - readIter)
            if (readThisIter <= 0) return false // No more data to read
            readIter += readThisIter
            if (readIter == byteCount) return true
        }
    }

    private fun initialLoad() {
        launch {
            var skipped = 0
            var firstFailure: Throwable? = null
            try {
                atomicFile.openRead().source().buffer().use { input ->
                    val bb = ByteBuffer.allocate(ENTRY_HEADER_BYTES)

                    while (true) {
                        if (!input.requireBytes(bb.array(), ENTRY_HEADER_BYTES)) break
                        val k = bb.getInt(0)
                        val size = bb.getInt(4)
                        // A corrupt length would otherwise allocate an arbitrary array
                        if (size < 0 || size > MAX_ENTRY_BYTES) {
                            skipped++
                            break
                        }
                        val strBArr = ByteArray(size)
                        if (!input.requireBytes(strBArr, size)) break
                        val value = try {
                            serializer.read(strBArr.decodeToString())
                        } catch (e: Throwable) {
                            skipped++
                            if (firstFailure == null) firstFailure = e
                            null
                        }
                        if (value != null) table[k] = value
                    }
                }
            } catch (e: FileNotFoundException) {
                this@MemAutoFlushingLookupTable.xLogD("Lookup table not found!", e)
                // Ignored
            } catch (e: Throwable) {
                this@MemAutoFlushingLookupTable.xLogE("Failed to read lookup table", e)
            } finally {
                loadGate.complete(Unit)
            }
            if (skipped > 0) {
                this@MemAutoFlushingLookupTable.xLogE(
                    "Skipped $skipped unreadable lookup table entries: ${firstFailure?.message.orEmpty()}",
                )
            }
        }
    }

    private fun tryWrite() {
        val id = ++writeCounter
        flushed = false
        launch {
            delay(debounceTimeMs)
            if (id != writeCounter) return@launch

            // The snapshot and the disk write both run outside the lock. Holding a
            // ReentrantReadWriteLock across withContext would release it on whichever
            // thread resumes, which throws and strands the lock for every later reader.
            withContext(NonCancellable) {
                try {
                    writeSnapshot()
                    if (id == writeCounter) flushed = true
                } catch (e: Throwable) {
                    // Uncaught here would reach the SupervisorJob's default handler
                    // and take the process down on any disk write failure.
                    this@MemAutoFlushingLookupTable.xLogE("Failed to persist lookup table", e)
                }
            }
        }
    }

    private fun writeSnapshot() {
        val snapshot = rwLock.read { table.entries.map { it.key to it.value } }
        writeToDisk(snapshot)
    }

    private fun writeToDisk(snapshot: List<Pair<Int, T>>) {
        val bb = ByteBuffer.allocate(ENTRY_HEADER_BYTES)

        val fos = atomicFile.startWrite()
        try {
            val out = fos.sink().buffer()
            snapshot.forEach { (key, value) ->
                val v = serializer.write(value).encodeToByteArray()
                bb.putInt(0, key)
                bb.putInt(4, v.size)
                out.write(bb.array())
                out.write(v)
            }
            out.flush()
            atomicFile.finishWrite(fos)
        } catch (t: Throwable) {
            atomicFile.failWrite(fos)
            throw t
        }
    }

    suspend fun put(key: Int, value: T) {
        loadGate.await()
        rwLock.write { table[key] = value }
        tryWrite()
    }

    suspend fun get(key: Int): T? {
        loadGate.await()
        return rwLock.read { table[key] }
    }

    suspend fun size(): Int {
        loadGate.await()
        return rwLock.read { table.size }
    }

    override fun close() {
        runBlocking { coroutineContext.job.cancelAndJoin() }
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        if (!flushed) runCatching { writeSnapshot() }
    }

    interface EntrySerializer<T> {
        /**
         * Serialize an entry as a String.
         */
        fun write(entry: T): String

        /**
         * Read an entry from a String.
         */
        fun read(string: String): T
    }

    companion object {
        private const val INITIAL_SIZE = 1000
        private const val LOAD_FACTOR = 0.75f
        private const val ENTRY_HEADER_BYTES = 8
        private const val MAX_ENTRY_BYTES = 64 * 1024
        private const val DEFAULT_MAX_ENTRIES = 5000
    }
}
