package eu.kanade.tachiyomi.data.database

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import exh.log.xLogE
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mihon.core.archive.CbzCrypto
import mihon.core.concurrency.AppDispatchersHolder

/**
 * Writes a self-contained copy of the library database to a location the user picks.
 *
 * All of the correctness lives in [DatabaseMaintenanceManager.prepareSnapshot]; this class only
 * decides which database is live and performs the copy. Keeping the two apart means the WAL
 * checkpoint and the pre-export integrity check have exactly one implementation, and neither
 * has to reach into SQLDelight to run a pragma.
 */
@SingleIn(AppScope::class)
@Inject
class DatabaseExporter(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchersHolder.get().io,
) {

    sealed interface Result {
        data object Success : Result

        /** The checkpoint could not complete, so exporting would silently drop recent writes. */
        data object SnapshotUnavailable : Result

        data class Failed(val cause: Throwable) : Result
    }

    /** File name offered to the document picker, derived from the live database's name. */
    val suggestedFileName: String
        get() = liveDatabaseName() + EXPORT_SUFFIX

    suspend fun export(uri: Uri): Result = withContext(ioDispatcher) {
        val manager = try {
            maintenanceManager()
        } catch (e: Throwable) {
            xLogE("Database is not available to export", e)
            return@withContext Result.Failed(e)
        }

        val source = manager.prepareSnapshot() ?: return@withContext Result.SnapshotUnavailable
        val expectedBytes = source.length()

        runCatching {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("The chosen location could not be opened for writing")
            val copied = output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }

            check(copied == expectedBytes) { "Only $copied of $expectedBytes bytes were copied" }
            verifyDestinationSize(uri, expectedBytes)
        }.fold(
            onSuccess = { Result.Success },
            onFailure = { Result.Failed(it) },
        )
    }

    /**
     * Cross-checks the destination against the source, but only when the provider actually
     * reports a size.
     *
     * `statSize` is documented as unreliable and returns -1 for anything not backed by a real
     * file - a cloud provider, or any `DocumentsProvider` handing back a pipe. Treating that as
     * a short write made a perfectly good export report itself as a failure, so an unknown size
     * has to pass and only a *known-wrong* size may fail.
     */
    private fun verifyDestinationSize(uri: Uri, expectedBytes: Long) {
        val written = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()

        if (written == null || written < 0) return

        check(written == expectedBytes) {
            "Only $written of $expectedBytes bytes were written"
        }
    }

    private fun maintenanceManager(): DatabaseMaintenanceManager {
        val encrypted = isEncrypted()
        if (encrypted) System.loadLibrary("sqlcipher")
        return DatabaseMaintenanceManager(
            context = context,
            databaseName = liveDatabaseName(encrypted),
            passphrase = if (encrypted) CbzCrypto.getDecryptedPasswordSql() else null,
        )
    }

    private fun isEncrypted(): Boolean = securityPreferences.encryptDatabase().get()

    private fun liveDatabaseName(encrypted: Boolean = isEncrypted()): String =
        if (encrypted) CbzCrypto.DATABASE_NAME else PLAINTEXT_DATABASE_NAME

    private companion object {
        const val PLAINTEXT_DATABASE_NAME = "tachiyomi.db"
        const val EXPORT_SUFFIX = "-export.db"
    }
}
