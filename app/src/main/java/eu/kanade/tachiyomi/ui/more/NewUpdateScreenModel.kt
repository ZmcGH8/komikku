package eu.kanade.tachiyomi.ui.more

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.updater.UpdateApkStore
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

class NewUpdateScreenModel(
    private val changelogInfo: String,
    private val downloadLink: String,
) : ScreenModel {

    private val context: Context by lazy { globalAppGraph.context }
    private val network: NetworkHelper by lazy { globalAppGraph.networkHelper }

    private val _state = MutableStateFlow(State(changelogInfo = changelogInfo))
    val state: StateFlow<State> = _state

    private val apkFile: File?
        get() = UpdateApkStore.apkFile(context)

    private var downloadJob: Job? = null

    fun startDownload() {
        if (downloadJob?.isActive == true) return

        downloadJob = screenModelScope.launch {
            _state.update { it.copy(downloadProgress = 0, stage = Stage.Downloading) }
            try {
                withIOContext { downloadApk() }
                _state.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                apkFile?.delete()
                _state.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    private suspend fun downloadApk() {
        val target = apkFile ?: throw IOException("No external cache directory to download the update into")
        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last update sent to avoid updating the state too often.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    _state.update { it.copy(downloadProgress = progress) }
                }
            }
        }

        val response = network.client.newCachelessCallWithProgress(GET(downloadLink), progressListener).awaitSuccess()
        response.body.source().saveTo(target)
    }

    /**
     * Hands off to the package installer, which replaces this app and kills the process.
     *
     * The database is checkpointed first, and waited on. Reading progress is written as the reader turns
     * pages, so a write can still be sitting in the write-ahead log when the user taps install - and the
     * installer gives the app no chance to finish. Anything that reaches the installer is already past
     * this point, which is why it is not fire-and-forget.
     */
    fun installUpdate() {
        screenModelScope.launch {
            runCatching { globalAppGraph.databaseHandler.checkpoint() }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not checkpoint the database before updating" } }

            try {
                val file = apkFile
                if (file == null || !file.exists()) {
                    logcat(LogPriority.WARN) { "APK file not found, re-downloading" }
                    _state.update { it.copy(stage = Stage.Available) }
                    startDownload()
                    return@launch
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(file.getUriCompat(context), ExtensionInstaller.APK_MIME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to install update" }
                _state.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    @Immutable
    data class State(
        val changelogInfo: String,
        val downloadProgress: Int = 0,
        val stage: Stage = Stage.Available,
    )

    enum class Stage {
        Available,
        Downloading,
        Downloaded,
        Failed,
    }
}
