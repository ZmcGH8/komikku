package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
) {

    // KMK -->
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()

    private val reasonKeys = ConcurrentHashMap<Long, String>()

    private val failureReasons = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Latest failure text per `pkgName_signatureHash`, used as subtext on the list row. */
    val failureReasonsFlow: StateFlow<Map<String, String>> = failureReasons.asStateFlow()

    private fun setFailureReason(key: String, reason: String) {
        failureReasons.value = failureReasons.value + (key to reason)
    }

    private fun clearFailureReason(key: String) {
        if (failureReasons.value.containsKey(key)) {
            failureReasons.value = failureReasons.value - key
        }
    }
    // KMK <--
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: Extension): Flow<InstallStep> {
        val pkgName = extension.pkgName +
            // KMK -->
            "_${extension.signatureHash}"
        val downloadId = pkgName.toDownloadId()
        // KMK <--
        cancelInstall(pkgName)

        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step
        reasonKeys[downloadId] = pkgName
        // A retry must not inherit the reason from the attempt it is replacing.
        clearFailureReason(pkgName)

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "extension_$pkgName.apk")
            try {
                step.value = InstallStep.Downloading
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute()
                    // KMK -->
                    .use { response ->
                        // KMK <--

                        if (!response.isSuccessful) {
                            throw Exception("Failed to download extension")
                        }
                        // KMK -->
                        tmpFile.outputStream().use { output ->
                            response.body.byteStream().use { input ->
                                // KMK <--
                                input.copyTo(output)
                            }
                        }
                    }

                step.value = InstallStep.Installing
                installApk(downloadId, tmpFile)
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    // Canceled
                } else {
                    logcat(LogPriority.ERROR, e)
                    step.value = InstallStep.Error
                    setFailureReason(pkgName, e.friendlyInstallReason())
                }
                // KMK -->
                tmpFile.delete()
                // KMK <--
            }
        }

        activeJobs[pkgName] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(pkgName)
                activeSteps.remove(downloadId)
                reasonKeys.remove(downloadId)
                job.cancel()
            }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install. Delete after use.
     */
    private fun installApk(downloadId: Long, tempFile: File) {
        logcat(LogPriority.INFO) { "[ExtInstall] installApk downloadId=$downloadId installer=${extensionInstaller.get()}" }
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                try {
                    if (ExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                        updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        updateInstallStep(
                            downloadId,
                            InstallStep.Error,
                            "The file was rejected as an extension. It may be damaged, not signed, " +
                                "or from a different repository.",
                        )
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    updateInstallStep(downloadId, InstallStep.Error, e.friendlyInstallReason())
                }

                tempFile.delete()
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, /* KMK --> */ pkgName.toDownloadId() /* KMK <-- */)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep, reason: String? = null) {
        activeSteps[downloadId]?.let { it.value = step }
        if (step == InstallStep.Error && reason != null) {
            reasonKeys[downloadId]?.let { setFailureReason(it, reason) }
        }
    }

    /**
     * Install failures are shown to the user as text, and a raw exception message is frequently a
     * bare host name or null. This keeps it readable while still carrying the underlying cause.
     */
    private fun Throwable.friendlyInstallReason(): String {
        val detail = localizedMessage?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
        return detail?.let { "Download or install failed: $it" } ?: "Download or install failed."
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"

        // KMK -->
        /** Convert packageName to download ID avoiding negative number */
        private fun String.toDownloadId(): Long = hashCode().toLong() and 0xFFFFFFFFL
        // KMK <--
    }
}
