package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException

/**
 * Reads an encoded [Backup] one entry at a time. Every other field is set aside for [readMetadata],
 * wherever it appears.
 *
 * ### Why this exists
 *
 * A restore used to decode the whole file into one `List<BackupManga>` before it wrote anything.
 * For a library of tens of thousands of series that object graph - every series with all of its
 * chapters, history rows and tracker entries - is several times the size of the file itself, and
 * it had to fit in the heap *alongside* the decoded byte array it was decoded from. On a large
 * backup the decode was both the long pole and an OOM risk, and nothing reached the database until
 * it had finished.
 *
 * The wire format does not have to change: `Backup.backupManga` is field 1, and every element of
 * that repeated field is already written as its own tag/length-delimited pair. So the same bytes
 * can be walked one element at a time, which is what this does. Entries the caller does not want
 * are skipped by length, never decoded, so a pass that wants a third of the entries costs a third
 * of the decoding.
 *
 * Malformed or truncated input throws an [IOException].
 */
class BackupReader(
    private val source: BufferedSource,
    private val parser: ProtoBuf,
) {
    private val metadata = Buffer()
    private var entryLength = NO_ENTRY

    /**
     * Moves to the next entry, which [readEntry] or [skipEntry] then consumes.
     *
     * @return false once the input is exhausted.
     */
    fun nextEntry(): Boolean {
        check(entryLength == NO_ENTRY)
        while (!source.exhausted()) {
            val tag = source.readVarint()
            val fieldNumber = tag ushr 3
            val wireType = (tag and 0x7).toInt()
            if (fieldNumber == 0L) throw IOException("Invalid field number 0")

            if (tag == ENTRY_TAG) {
                entryLength = source.readLength()
                return true
            }

            // Anything that is not an entry is kept verbatim, so readMetadata can hand the whole
            // message back to the real serializer later. Re-encoding field by field is not an
            // option: the bytes are copied as-is and the tag is rewritten on the way past.
            metadata.writeVarint(tag)
            when (wireType) {
                WIRE_TYPE_VARINT -> metadata.writeVarint(source.readVarint())
                WIRE_TYPE_FIXED64 -> metadata.write(source, 8)
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val length = source.readLength()
                    metadata.writeVarint(length)
                    metadata.write(source, length)
                }
                WIRE_TYPE_FIXED32 -> metadata.write(source, 4)
                else -> throw IOException("Unsupported wire type $wireType")
            }
        }
        return false
    }

    fun readEntry(): BackupManga {
        val bytes = source.readByteArray(takeEntryLength())
        return decode(BackupManga.serializer(), bytes)
    }

    fun skipEntry() {
        source.skip(takeEntryLength())
    }

    /**
     * Skips the remaining entries and decodes everything else as a [Backup] without entries.
     */
    fun readMetadata(): Backup {
        if (entryLength != NO_ENTRY) skipEntry()
        while (nextEntry()) skipEntry()
        return decode(Backup.serializer(), metadata.readByteArray())
    }

    private fun takeEntryLength(): Long {
        check(entryLength != NO_ENTRY)
        return entryLength.also { entryLength = NO_ENTRY }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return try {
            parser.decodeFromByteArray(deserializer, bytes)
        } catch (e: SerializationException) {
            throw IOException(e)
        }
    }
}

private fun BufferedSource.readVarint(): Long {
    var result = 0L
    for (shift in 0 until Long.SIZE_BITS step 7) {
        val byte = readByte().toInt()
        result = result or ((byte and 0x7F).toLong() shl shift)
        if ((byte and 0x80) == 0) return result
    }
    throw IOException("Malformed varint")
}

private fun BufferedSource.readLength(): Long {
    val length = readVarint()
    if (length !in 0..Int.MAX_VALUE) throw IOException("Invalid length $length")
    return length
}

/**
 * Writes [value] back out as a varint, so a tag or length read off the source can be copied into
 * the metadata buffer unchanged.
 *
 * Only the reader needs this, but it is the writing half of [readVarint] and belongs beside it: the
 * metadata buffer has to hold bytes that are byte-for-byte what the file held, so it re-emits the
 * tag verbatim rather than re-encoding a field it deliberately knows nothing about.
 */
private fun BufferedSink.writeVarint(value: Long) {
    var remaining = value
    while ((remaining and 0x7FL.inv()) != 0L) {
        writeByte((remaining and 0x7F).toInt() or 0x80)
        remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
}

private const val WIRE_TYPE_VARINT = 0
private const val WIRE_TYPE_FIXED64 = 1
private const val WIRE_TYPE_LENGTH_DELIMITED = 2
private const val WIRE_TYPE_FIXED32 = 5

// Backup.backupManga is field 1, and each entry is length-delimited
private const val ENTRY_TAG = 1L shl 3 or WIRE_TYPE_LENGTH_DELIMITED.toLong()

private const val NO_ENTRY = -1L
