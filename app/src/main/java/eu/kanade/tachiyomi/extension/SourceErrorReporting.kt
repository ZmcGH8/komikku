package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph

/**
 * Runs a source operation, reporting any failure to the extension watchdog.
 *
 * Failures from an extension are nearly always a site having changed underneath a scraper, which
 * only that extension's own maintainers can fix. Every source call site swallows its exception and
 * shows a generic error, so without this the user cannot tell whose problem it is.
 *
 * A success clears the extension's recorded failure, since the far likelier reading of a source that
 * answers now is that whatever broke it has since been fixed.
 */
suspend inline fun <T> reportingSourceErrors(
    source: Source?,
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block()).onSuccess {
        globalAppGraph.extensionManager.clearSourceError(source)
    }
} catch (e: Throwable) {
    if (e is CancellationException) throw e
    globalAppGraph.extensionManager.reportSourceError(source, e)
    Result.failure(e)
}

/**
 * [reportingSourceErrors] for an operation that signals failure by returning null, so callers keep
 * their existing null-handling instead of unwrapping a [Result].
 *
 * Null is not taken as a recovery: it means this call produced nothing, which is not evidence that
 * the extension is healthy, so a recorded failure is left standing until a call actually returns.
 */
suspend inline fun <T : Any> reportingSourceErrorsOrNull(
    source: Source?,
    crossinline block: suspend () -> T?,
): T? = try {
    block()?.also { globalAppGraph.extensionManager.clearSourceError(source) }
} catch (e: Throwable) {
    if (e is CancellationException) throw e
    globalAppGraph.extensionManager.reportSourceError(source, e)
    null
}
