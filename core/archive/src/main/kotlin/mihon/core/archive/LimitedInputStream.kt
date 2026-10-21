package mihon.core.archive

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Hands out at most [limit] bytes and no more.
 *
 * Truncates rather than failing, because a resource stream has no way to report an error the way a
 * function return does: the reader is mid-`getInputStream` and a partial image is a broken image
 * either way. What the ceiling buys is that the *allocation* stays bounded, which is the half of the
 * problem that kills the process.
 *
 * Never reads past the budget, so the archive is not asked to decompress bytes that would be
 * discarded anyway — and decompression is the expensive half of a decompression bomb.
 */
internal class LimitedInputStream(
    delegate: InputStream,
    private val limit: Long,
) : FilterInputStream(delegate) {

    private var delivered = 0L

    override fun read(): Int {
        if (delivered >= limit) return -1
        val value = super.read()
        if (value >= 0) delivered++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (delivered >= limit) return -1
        val allowed = minOf(len.toLong(), limit - delivered).coerceAtLeast(1L).toInt()
        val read = super.read(b, off, allowed)
        if (read > 0) delivered += read
        return read
    }

    override fun skip(n: Long): Long {
        if (delivered >= limit || n <= 0) return 0
        val skipped = super.skip(minOf(n, limit - delivered))
        if (skipped > 0) delivered += skipped
        return skipped
    }

    override fun available(): Int {
        val left = limit - delivered
        if (left <= 0) return 0
        return minOf(super.available().toLong(), left).toInt()
    }
}
