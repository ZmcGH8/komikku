package tachiyomi.data

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mihon.core.concurrency.AppDispatchers
import mihon.core.concurrency.AppDispatchersHolder

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDatabaseHandler(
    val db: Database,
    private val driver: SqlDriver,
    appDispatchers: AppDispatchers = AppDispatchersHolder.get(),
    val queryDispatcher: CoroutineDispatcher = appDispatchers.dbReader,
    val transactionDispatcher: CoroutineDispatcher = appDispatchers.dbWriter,
) : DatabaseHandler {

    val suspendingTransactionId = ThreadLocal<Int>()

    /**
     * Folds the write-ahead log into the database file, on the writer dispatcher so it queues
     * behind writes still in flight rather than racing them.
     *
     * `TRUNCATE` rather than `FULL` because the process is about to be replaced: leaving a partly
     * reclaimed log behind would only be reclaimed again by the next process to open it. A checkpoint
     * cannot run inside a transaction, which is why this bypasses [dispatch] and goes straight to the
     * driver.
     */
    override suspend fun checkpoint() {
        withContext(transactionDispatcher) {
            runCatching {
                // `value` is read because the statement is deferred: a QueryResult that is never
                // consumed never runs. Parameters are explicit because the driver does not default them.
                driver.execute(null, CHECKPOINT_PRAGMA, 0, null).value
            }
        }
    }

    private companion object {
        /**
         * Folds the WAL into the database and truncates it. Not a `Transaction`, so it runs straight
         * through the driver rather than through [dispatch].
         */
        const val CHECKPOINT_PRAGMA = "PRAGMA wal_checkpoint(TRUNCATE)"
    }

    override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
        return dispatch(inTransaction, block)
    }

    override suspend fun <T : Any> awaitList(
        inTransaction: Boolean,
        block: suspend Database.() -> Query<T>,
    ): List<T> {
        return dispatch(inTransaction) { block(db).executeAsList() }
    }

    // SY -->
    override suspend fun <T : Any> awaitListExecutable(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T> {
        return dispatch(inTransaction) { block(db).executeAsList() }
    }
    // SY <--

    override suspend fun <T : Any> awaitOne(
        inTransaction: Boolean,
        block: suspend Database.() -> Query<T>,
    ): T {
        return dispatch(inTransaction) { block(db).executeAsOne() }
    }

    override suspend fun <T : Any> awaitOneExecutable(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T {
        return dispatch(inTransaction) { block(db).executeAsOne() }
    }

    override suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean,
        block: suspend Database.() -> Query<T>,
    ): T? {
        return dispatch(inTransaction) { block(db).executeAsOneOrNull() }
    }

    override suspend fun <T : Any> awaitOneOrNullExecutable(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T? {
        return dispatch(inTransaction) { block(db).executeAsOneOrNull() }
    }

    override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> {
        return block(db).asFlow().mapToList(queryDispatcher)
    }

    override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> {
        return block(db).asFlow().mapToOne(queryDispatcher)
    }

    override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> {
        return block(db).asFlow().mapToOneOrNull(queryDispatcher)
    }

    override fun <T : Any> subscribeToPagingSource(
        countQuery: Database.() -> Query<Long>,
        queryProvider: Database.(Long, Long) -> Query<T>,
    ): PagingSource<Long, T> {
        return QueryPagingSource(
            handler = this,
            countQuery = countQuery,
            queryProvider = { limit, offset ->
                queryProvider.invoke(db, limit, offset)
            },
        )
    }

    private suspend fun <T> dispatch(inTransaction: Boolean, block: suspend Database.() -> T): T {
        // Create a transaction if needed and run the calling block inside it.
        if (inTransaction) {
            return withTransaction { block(db) }
        }

        // If we're currently in the transaction thread, there's no need to dispatch our query.
        if (driver.currentTransaction() != null) {
            return block(db)
        }

        // Get the current database context and run the calling block.
        val context = getCurrentDatabaseContext()
        return withContext(context) { block(db) }
    }
}
