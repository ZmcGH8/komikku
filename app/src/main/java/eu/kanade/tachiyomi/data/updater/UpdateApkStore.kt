package eu.kanade.tachiyomi.data.updater

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single home for the downloaded update APK and for deciding when it is safe to drop.
 *
 * The download, the install and the cleanup all have to agree on the path, and they used to each
 * build it themselves - so a change to where it lives silently breaks the other two.
 */
object UpdateApkStore {

    private const val APK_NAME = "update.apk"

    /**
     * How long an uninstalled APK is kept.
     *
     * Only bounds the "nobody ever installed it" case; an APK that has been installed is dropped
     * immediately instead. Set well beyond any install window: the package installer has to be
     * handed this file by URI, and deleting it out from under a prompt the user is still looking
     * at would turn an update into a failure.
     */
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

    fun apkFile(context: Context): File? = context.externalCacheDir?.let { File(it, APK_NAME) }

    /**
     * Deletes the downloaded APK when nothing needs it any more, and leaves it alone otherwise.
     *
     * Two independent reasons to delete:
     *
     * - **Already installed.** The archive's version code is at or below the running one, so the
     *   update it represents is either live or superseded. This is the case that matters: it is
     *   why the APK used to sit there after every successful update.
     * - **Expired.** Nobody installed it within [MAX_AGE_MS]. A download the user abandoned is
     *   just as dead weight as an installed one, and it is the larger of the two - a release APK
     *   runs to tens of megabytes.
     *
     * Deliberately *not* deleted the moment the installer is launched: the package installer
     * reads the file through the URI grant afterwards, and the process is killed before it
     * returns anyway, so there is nothing to clean up at that point.
     */
    fun cleanUpIfUnneeded(context: Context) {
        val file = apkFile(context) ?: return
        if (!file.exists()) return

        val reason = obsoleteReason(context, file) ?: expiredReason(file) ?: return
        if (file.delete()) {
            logcat(LogPriority.INFO) { "Removed the downloaded update APK ($reason)" }
        }
    }

    /** Null when the APK is still worth keeping - including when its version cannot be read. */
    private fun obsoleteReason(context: Context, file: File): String? {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null
        // A file that is not this app is not evidence that this app was updated.
        if (archive.packageName != context.packageName) return null

        val running = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrNull() ?: return null

        val downloaded = runCatching { PackageInfoCompat.getLongVersionCode(archive) }.getOrNull() ?: return null
        return "already on $downloaded, running $running".takeIf { downloaded <= running }
    }

    private fun expiredReason(file: File): String? {
        val age = System.currentTimeMillis() - file.lastModified()
        return "uninstalled for ${TimeUnit.MILLISECONDS.toDays(age)} day(s)".takeIf { age > MAX_AGE_MS }
    }
}
