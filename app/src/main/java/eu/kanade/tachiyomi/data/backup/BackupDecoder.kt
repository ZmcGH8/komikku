package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.globalAppGraph
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException

@Inject
@SingleIn(AppScope::class)
class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = globalAppGraph.protoBuf,
) {
    /**
     * Decode a potentially-gzipped backup whole.
     *
     * Only for callers that genuinely need every entry at once - the integrity check, which has to
     * see every tracker in the file. A restore must not: it walks the file with [decodeMetadata]
     * and [decodeEntries] instead, so a library-sized backup is never held in memory in full.
     */
    fun decode(uri: Uri): Backup {
        return read(uri) { reader ->
            orInvalid {
                while (reader.nextEntry()) reader.skipEntry()
                reader.readMetadata()
            }
        }
    }

    /**
     * Decode everything but the entries of a potentially-gzipped backup, counting the entries
     * without decoding them.
     *
     * A restore needs the total before it can report progress, and it needs the rest of the
     * message - categories, preferences, sources - before it restores anything. Neither needs the
     * entries, so they are walked by length and counted.
     */
    fun decodeMetadata(uri: Uri): Metadata {
        return read(uri) { reader ->
            orInvalid {
                var entryCount = 0
                while (reader.nextEntry()) {
                    reader.skipEntry()
                    entryCount++
                }
                Metadata(entryCount, reader.readMetadata())
            }
        }
    }

    /**
     * Decode the entries of a potentially-gzipped backup one at a time, skipping those whose index
     * [include] rejects.
     *
     * Rejected entries are skipped by length rather than decoded, so a selective pass pays a seek
     * and not a parse. They still have to be *walked* to find that length, so the file is read
     * once per pass - which is why a restore makes two ordered passes (new entries, then existing
     * ones) instead of materialising the lot and sorting it.
     */
    fun decodeEntries(uri: Uri, include: (index: Int) -> Boolean = { true }): Flow<BackupManga> = flow {
        read(uri) { reader ->
            var index = 0
            while (orInvalid { reader.nextEntry() }) {
                if (include(index++)) {
                    emit(orInvalid { reader.readEntry() })
                } else {
                    orInvalid { reader.skipEntry() }
                }
            }
        }
    }

    private inline fun <T> read(uri: Uri, block: (BackupReader) -> T): T {
        return (
            context.contentResolver.openInputStream(uri)
                ?: throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            ).use { inputStream ->
            val source = inputStream.source().buffer()

            val peeked = source.peek().apply {
                require(2)
            }
            val id1id2 = peeked.readShort()
            val backupSource: BufferedSource = when (id1id2.toInt()) {
                0x1f8b -> source.gzip().buffer() // 0x1f8b is gzip magic bytes
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                }
                else -> source
            }

            backupSource.use { block(BackupReader(it, parser)) }
        }
    }

    // Only around reading, so the JSON error above and anything thrown downstream of emit keep their own messages
    private inline fun <T> orInvalid(block: () -> T): T {
        return try {
            block()
        } catch (e: IOException) {
            throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown), e)
        }
    }

    /**
     * A backup's non-entry fields, plus how many entries the file holds.
     */
    data class Metadata(
        val entryCount: Int,
        val backup: Backup,
    )

    companion object {
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`
    }
}
