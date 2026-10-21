package tachiyomi.data

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import kotlinx.coroutines.flow.Flow

interface DatabaseHandler {

    /**
     * Folds the write-ahead log back into the database file.
     *
     * Committed work is durable either way, but until it is checkpointed it lives in the `-wal`
     * sidecar. That matters when the process is about to be killed abruptly - the in-app updater
     * handing off to the package installer is the one place the app does that on purpose - so this
     * is called before handing off.
     */
    suspend fun checkpoint()

    suspend fun <T> await(inTransaction: Boolean = false, block: suspend Database.() -> T): T

    suspend fun <T : Any> awaitList(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): List<T>

    // SY -->
    suspend fun <T : Any> awaitListExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T>
    // SY <--

    suspend fun <T : Any> awaitOne(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): T

    suspend fun <T : Any> awaitOneExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T

    suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean = false,
        block: suspend Database.() -> Query<T>,
    ): T?

    suspend fun <T : Any> awaitOneOrNullExecutable(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T?

    fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>>

    fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T>

    fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?>

    fun <T : Any> subscribeToPagingSource(
        countQuery: Database.() -> Query<Long>,
        queryProvider: Database.(Long, Long) -> Query<T>,
    ): PagingSource<Long, T>
}
