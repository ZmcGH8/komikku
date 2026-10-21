package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.logFileDir
import java.io.File

/**
 * Single-responsibility file writer for crash reports. Extracted from
 * `CrashLogUtil.dumpLogs()` which did `Runtime.exec("logcat ... -f file")`
 * inline (blocking, Android 13+ logcat permission fragile) and had no
 * rotation, size cap, or crash-loop guard.
 *
 * - Writes the structured [CrashReport] as human-readable text.
 * - Keeps the last 5 crash files (`houri_crash_*.log`) with 2MB cap each.
 * - Returns the written file so callers can share or launch CrashActivity with it.
 */
object CrashLogWriter {

    private const val MAX_FILES = 5
    private const val MAX_BYTES_PER_FILE = 2L * 1024 * 1024
    private const val FILE_PREFIX = "houri_crash_"
    private const val FILE_SUFFIX = ".log"

    fun write(context: Context, report: CrashReport): File {
        val file = context.createFileInCacheDir("$FILE_PREFIX${report.timestampMs}$FILE_SUFFIX")
        file.writeText(render(report))
        if (file.length() > MAX_BYTES_PER_FILE) {
            val truncated = file.readText().take((MAX_BYTES_PER_FILE).toInt())
            file.writeText(truncated + "\n\n— truncated —\n")
        }
        pruneOld(context)
        appendLogcatSnapshot(context, file)
        return file
    }

    private fun render(r: CrashReport): String = buildString {
        appendLine(r.debugInfo)
        appendLine()
        r.extensionsInfo?.let {
            appendLine(it)
            appendLine()
        }
        appendLine("Thread: ${r.threadName} @ ${r.timestampMs}")
        appendLine("Exception: ${r.type}: ${r.message ?: ""}")
        appendLine(r.stackTrace)
        r.cause?.let {
            appendLine("Caused by: ${it.type}: ${it.message ?: ""}")
            appendLine(it.stackTrace)
        }
        if (r.suppressedCount > 0) appendLine("Suppressed: ${r.suppressedCount}")
    }

    private fun crashFiles(context: Context): List<File> {
        val files = context.logFileDir().listFiles { f ->
            f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }
        return files?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun pruneOld(context: Context) {
        try {
            crashFiles(context).drop(MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun deleteAll(context: Context): Int = try {
        crashFiles(context).count { it.delete() }
    } catch (_: Exception) {
        0
    }

    private fun appendLogcatSnapshot(context: Context, file: File) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "*:E", "-d", "-t", "800", "-v", "brief"))
            val out = proc.inputStream.bufferedReader().readText().take(200_000)
            proc.waitFor()
            if (out.isNotBlank()) {
                file.appendText("\n\n— logcat (last 800 E) —\n$out")
            }
        } catch (_: Exception) {
        }
    }
}
