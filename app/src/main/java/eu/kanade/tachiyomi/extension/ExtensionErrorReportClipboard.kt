package eu.kanade.tachiyomi.extension

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.copyToClipboard
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

/**
 * Formats an extension failure as a report an extension maintainer can act on.
 *
 * A bare error message rarely identifies its cause, so the report carries what a maintainer would
 * otherwise have to ask for: the exact version, the source and repository it came from, the app build,
 * the device, and the full stack trace.
 */
internal fun ExtensionErrorReport.toClipboardReport(
    appName: String = BuildConfig.APPLICATION_ID,
    appVersion: String = BuildConfig.VERSION_NAME,
    debug: Boolean = BuildConfig.DEBUG,
): String = buildString {
    appendLine("Extension error report")
    appendLine()
    appendLine("Extension: ${extensionName.ifBlank { pkgName }}")
    appendLine("Package: $pkgName")
    appendLine("Version: $versionName (code $versionCode)")
    sourceName?.let { appendLine("Source: $it") }
    repoName?.let { appendLine("Repository: $it") }
    appendLine()
    appendLine("Error")
    appendLine("-----")
    appendLine(reason)
    stackTrace?.let {
        appendLine()
        appendLine("Stack trace")
        appendLine("----------")
        appendLine(it)
    }
    appendLine()
    appendLine("Environment")
    appendLine("-----------")
    appendLine("App: $appName $appVersion (code ${BuildConfig.VERSION_CODE}, ${if (debug) "debug" else "release"})")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Abi: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
    appendLine("Locale: ${Locale.getDefault()}")
}

/**
 * Copies the report to the clipboard.
 *
 * Android 13+ shows its own copy confirmation, so a second toast would double up; the shared helper
 * already handles that, along with the no-clipboard-service and failure cases.
 */
internal fun Context.copyExtensionErrorToClipboard(report: ExtensionErrorReport) {
    val text = runCatching { report.toClipboardReport() }
        .onFailure { logcat(LogPriority.ERROR, it) { "Failed to format extension error report" } }
        .getOrDefault(report.reason)

    copyToClipboard(
        label = "${report.extensionName.ifBlank { report.pkgName }} error report",
        content = text,
    )
}

/** Stack trace text for an error the user is being asked to report. */
internal fun Throwable.stackTraceText(): String = runCatching {
    stackTraceToString()
}.getOrElse { "${this::class.java.name}: $message" }
