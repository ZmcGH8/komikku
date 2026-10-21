package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * Updates installed extensions on a schedule.
 *
 * ### Why it is limited to two installers
 *
 * An update has to finish with nobody watching. [BasePreferences.ExtensionInstaller.PRIVATE]
 * installs the APK itself and `SHIZUKU` does it through the shell; both complete silently.
 * `LEGACY` and `PACKAGEINSTALLER` both raise a system install dialog, which a background worker
 * cannot answer and which would be raised at a moment the user did not ask for. With either of
 * those selected the worker does nothing at all rather than queueing work it cannot finish - see
 * [SourcePreferences.extensionAutoUpdateSupported].
 *
 * ### What it does
 *
 * Fetches the repo list first. That is also what sets each installed extension's `hasUpdate`
 * flag, so the badge on the extensions page stays correct even on a run that installs nothing.
 *
 * Then it installs every pending update, one at a time, awaiting each to completion before
 * starting the next. Sequential because the installer serialises its own queue anyway, so two in
 * flight is one of them waiting behind work the user cannot see - and because one failure should
 * not abandon the rest.
 */
class ExtensionAutoUpdateWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences
    private val extensionManager: ExtensionManager = globalAppGraph.extensionManager

    override suspend fun doWork(): Result {
        if (!sourcePreferences.extensionAutoUpdate().get()) return Result.success()
        if (!sourcePreferences.extensionAutoUpdateSupported()) {
            logcat(LogPriority.INFO) { "[ExtAutoUpdate] skipped: the selected installer cannot update in the background" }
            return Result.success()
        }
        return try {
            withIOContext { updateAll() }
            Result.success()
        } catch (e: Exception) {
            // Success, not retry: the next period runs anyway, and a retry storm against a repo
            // that is failing is worse than waiting one interval. Logged so the cause survives.
            logcat(LogPriority.ERROR, e) { "[ExtAutoUpdate] update run failed" }
            Result.success()
        }
    }

    private suspend fun updateAll() {
        extensionManager.findAvailableExtensions()

        val pending = extensionManager.installedExtensionsFlow.value.filter { it.hasUpdate }
        if (pending.isEmpty()) {
            logcat(LogPriority.INFO) { "[ExtAutoUpdate] nothing to update" }
            return
        }

        for (extension in pending) {
            // Collected once: updateExtension() *starts* the install when its flow is subscribed,
            // so calling it twice would queue the same APK twice.
            val steps = extensionManager.updateExtension(extension)
            // `completed` separates the two reasons the outcome can be null: the flow emitted
            // nothing (no matching repo entry - the extension is not in the current list, or its
            // signature does not match) versus the timeout firing first.
            var completed = false
            val finished = withTimeoutOrNull(INSTALL_TIMEOUT) {
                val step = steps.firstOrNull { it == InstallStep.Installed || it == InstallStep.Error }
                completed = true
                step
            }
            when {
                !completed -> logcat(LogPriority.WARN) {
                    "[ExtAutoUpdate] ${extension.pkgName} did not finish within $INSTALL_TIMEOUT"
                }
                finished == null -> logcat(LogPriority.WARN) {
                    "[ExtAutoUpdate] ${extension.pkgName}: no matching repo entry"
                }
                finished == InstallStep.Installed -> logcat(LogPriority.INFO) {
                    "[ExtAutoUpdate] ${extension.pkgName} updated"
                }
                else -> logcat(LogPriority.ERROR) {
                    "[ExtAutoUpdate] ${extension.pkgName} failed to update"
                }
            }
        }
    }

    companion object {
        private const val TAG = "ExtensionAutoUpdate"

        /** Ceiling on one update's download+install. Past this the next period takes over. */
        private val INSTALL_TIMEOUT = 5.minutes

        /** WorkManager rejects a period under 15 minutes; the smallest offered here is 1 hour. */
        private const val FLEX_MINUTES = 30L
        private const val MIN_INTERVAL_HOURS = 1L
        private const val MAX_INTERVAL_HOURS = 24L * 7L

        /**
         * Schedules, reschedules or cancels the periodic run to match the current settings.
         *
         * `UPDATE` rather than `KEEP` on purpose: the interval is the parameter here, and `KEEP`
         * would keep the old period after the user changed it. Cancels outright when auto-update
         * is off or the installer cannot do it, so a disabled feature stops costing a wake-up -
         * which is the difference between "off" and "off but scheduled".
         */
        fun schedule(context: Context) {
            val preferences = globalAppGraph.sourcePreferences
            if (!preferences.extensionAutoUpdate().get() || !preferences.extensionAutoUpdateSupported()) {
                cancel(context)
                return
            }
            val interval = preferences.extensionAutoUpdateInterval().get()
                .toLong()
                .coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)

            val request = PeriodicWorkRequestBuilder<ExtensionAutoUpdateWorker>(
                interval,
                TimeUnit.HOURS,
                FLEX_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
            logcat(LogPriority.INFO) { "[ExtAutoUpdate] scheduled every ${interval}h" }
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
