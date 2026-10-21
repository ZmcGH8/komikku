package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import mihon.core.concurrency.AppDispatchersHolder
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Class that handles the loading of the extensions. Supports two kinds of extensions:
 *
 * 1. Shared extension: This extension is installed to the system with package
 * installer, so other variants of Tachiyomi and its forks can also use this extension.
 *
 * 2. Private extension: This extension is put inside private data directory of the
 * running app, so this extension can only be used by the running app and not shared
 * with other apps.
 *
 * When both kinds of extensions are installed with a same package name, shared
 * extension will be used unless the version codes are different. In that case the
 * one with higher version code will be used.
 */
internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val trustExtension: TrustExtension by injectLazy()

    // KMK -->
    private val getExtensionStores: GetExtensionStores by injectLazy()
    // KMK <--

    private fun shouldLoadNsfwSource(): Boolean {
        return preferences.showNsfwSource().get()
    }

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    /** Staging suffix; a rename onto [PRIVATE_EXTENSION_EXTENSION] is what publishes an install. */
    private const val PRIVATE_EXTENSION_STAGING_SUFFIX = "staging"

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    fun installPrivateExtensionFile(context: Context, file: File): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) } ?: return false
        val extensionSignatures = getSignatures(extension)

        // Signing is a precondition of every private install, not only of updates. The load path
        // refuses to instantiate an unsigned extension anyway, but it used to reach that point by
        // way of the archive being written to the extensions directory first: nothing here looked
        // at the signature at all when there was no earlier version to compare against.
        if (extensionSignatures.isNullOrEmpty()) {
            logcat(LogPriority.ERROR) { "Extension to be installed is not signed." }
            return false
        }

        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)
        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                logcat(LogPriority.ERROR) { "Installed extension version is higher. Downgrading is not allowed." }
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                logcat(LogPriority.ERROR) { "Installed extension signature is not matched." }
                return false
            }
        }

        val dir = getPrivateExtensionDir(context).apply { if (!exists()) mkdirs() }
        val target = File(dir, "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        val staging = File(dir, "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION.$PRIVATE_EXTENSION_STAGING_SUFFIX")

        return try {
            staging.delete()
            file.copyAndSetReadOnlyTo(staging, overwrite = true)

            // Re-read what actually landed on disk instead of trusting the copy to have delivered
            // every byte. The archive the loader will open is this one, and a short write would
            // otherwise surface much later as an unexplained load failure.
            val published = context.packageManager
                .getPackageArchiveInfo(staging.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { it.packageName == extension.packageName && isPackageAnExtension(it) }
            if (published == null) {
                logcat(LogPriority.ERROR) { "Copied extension did not verify: ${extension.packageName}" }
                staging.delete()
                return false
            }

            // Publish by rename, after the new copy is known good. Deleting the live file first and
            // then copying left a window where a failed copy had already destroyed the working
            // extension - which during a bulk update means a library left with no extension at all.
            // Renaming within the directory is atomic, so the extension is never absent.
            if (!staging.renameTo(target)) {
                logcat(LogPriority.ERROR) { "Failed to publish extension: ${extension.packageName}" }
                staging.delete()
                return false
            }

            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to copy extension file." }
            staging.delete()
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     */
    suspend fun loadExtensions(context: Context): List<LoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        return coroutineScope {
            val extStores = getExtensionStores.get()
            val extensionsDispatcher = AppDispatchersHolder.get().extensions

            // Both package sets are resolved concurrently. Reading a private extension is a
            // manifest parse plus a scan of the whole APK for its signing block, so doing it inline
            // serialised the entire cost of a private-only install onto one thread, before the
            // parallel load below could start. Now it shares the same bounded pool.
            val sharedExtPkgs = async(extensionsDispatcher) {
                installedPkgs.filter { isPackageAnExtension(it) }
                    .map { ExtensionInfo(packageInfo = it, isShared = true) }
            }
            val privateExtPkgs = async(extensionsDispatcher) { readPrivateExtensions(context, pkgManager) }

            val privateList = privateExtPkgs.await()
            // Indexed rather than searched. A Sequence only describes work, so the previous
            // singleOrNull re-ran the parse chain for every private archive on every call: N
            // extensions cost N*N archive parses, which is what made the private installer
            // unusable on a large collection.
            val privateByName = privateList.associateBy { it.packageInfo.packageName }

            val extPkgs = (sharedExtPkgs.await() + privateList)
                // Remove duplicates. Shared takes priority than private by default
                .distinctBy { it.packageInfo.packageName }
                // Compare version number
                .mapNotNull { sharedPkg ->
                    selectExtensionPackage(sharedPkg, privateByName[sharedPkg.packageInfo.packageName])
                }

            if (extPkgs.isEmpty()) return@coroutineScope emptyList()

            extPkgs.map {
                async(extensionsDispatcher) {
                    try {
                        loadExtension(context, it, extStores)
                    } catch (e: CancellationException) {
                        // Cancellation is not a load failure. Letting it be reported as an Error made
                        // every child succeed, so awaitAll returned normally and the parent scope
                        // could not unwind.
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "[ExtInstall] Unexpected error loading extension ${it.packageInfo.packageName}" }
                        attributedError(it.packageInfo, "Unexpected: ${e.message}", cause = e)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Reads every privately installed extension archive into a [ExtensionInfo].
     *
     * Returns a list rather than a Sequence on purpose: callers index into the result by package
     * name, and a lazy sequence would redo the archive parse on every one of those lookups.
     */
    private fun readPrivateExtensions(context: Context, pkgManager: PackageManager): List<ExtensionInfo> {
        val files = getPrivateExtensionDir(context).listFiles() ?: return emptyList()

        // A staging file is only live between the copy and the rename that publishes it. A process
        // death in that window leaves one behind forever, since nothing else ever deletes it.
        files.filter { it.isFile && it.extension == PRIVATE_EXTENSION_STAGING_SUFFIX }
            .forEach { it.delete() }

        return files
            .asSequence()
            .filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            .mapNotNull {
                // Just in case, since Android 14+ requires them to be read-only
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)?.let { info ->
                    val appInfo = info.applicationInfo ?: return@let null
                    appInfo.fixBasePaths(path)
                    info
                }
            }
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = false) }
            .toList()
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): LoadResult {
        var lastError: LoadResult.Error? = null
        repeat(MAX_LOAD_RETRIES) { attempt ->
            val extensionPackage = getExtensionInfoFromPkgName(context, pkgName)
            if (extensionPackage == null) {
                logcat(LogPriority.WARN) { "[ExtInstall] Extension package is not found ($pkgName), attempt ${attempt + 1}/$MAX_LOAD_RETRIES" }
                // No PackageInfo to attribute this to: the archive or package is genuinely absent.
                lastError = LoadResult.Error(reason = "Package not found: $pkgName", pkgName = pkgName)
                if (attempt < MAX_LOAD_RETRIES - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                }
                return@repeat
            }
            return loadExtension(context, extensionPackage)
        }
        return lastError ?: LoadResult.Error(reason = "Package not found: $pkgName", pkgName = pkgName)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.let {
                    it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        // KMK -->
        extStores: List<ExtensionStore>? = null,
        // KMK <--
    ): LoadResult {
        // KMK -->
        val stores = extStores ?: getExtensionStores.get()
        // KMK <--
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName

        val extName = appInfo.metaData.getString(METADATA_NAME)
            ?: pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        logcat(LogPriority.INFO) { "[ExtInstall] loadExtension $extName ($pkgName) v$versionName code=$versionCode shared=${extensionInfo.isShared}" }

        if (versionName.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "[ExtInstall] Missing versionName for extension $extName — returning Error" }
            return attributedError(pkgInfo, "Missing versionName: $pkgName")
        }

        // Validate lib version
        val libVersion = appInfo.metaData.getFloat(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            logcat(LogPriority.WARN) {
                "[ExtInstall] Lib version is $libVersion, while only version(s) ${SUPPORTED_LIB_VERSIONS.joinToString()} are supported — returning Error"
            }
            return attributedError(pkgInfo, "Unsupported lib version $libVersion for $pkgName")
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "[ExtInstall] Package $pkgName isn't signed — returning Error" }
            return attributedError(pkgInfo, "Package not signed: $pkgName")
        } else if (!trustExtension.isTrusted(pkgInfo, signatures)) {
            val extension = Extension.Untrusted(
                extName,
                pkgName,
                versionName,
                versionCode,
                libVersion,
                signatures.last(),
                // KMK -->
                storeName = stores.firstOrNull { store ->
                    signatures.all { it == store.signingKey }
                }?.let { store ->
                    store.badgeLabel.takeIf(String::isNotBlank) ?: store.name
                },
                // KMK <--
            )
            logcat(LogPriority.WARN) { "[ExtInstall] Extension $pkgName isn't trusted" }
            return LoadResult.Untrusted(extension)
        }

        val isNsfw = appInfo.metaData.getInt(METADATA_CONTENT_WARNING) > 0 ||
            appInfo.metaData.getInt(METADATA_NSFW) == 1
        if (!shouldLoadNsfwSource() && isNsfw) {
            logcat(LogPriority.WARN) { "[ExtInstall] NSFW extension $pkgName not allowed — returning Error" }
            return attributedError(pkgInfo, "NSFW disabled: $pkgName")
        }

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[ExtInstall] Extension classloader error: $extName ($pkgName) — returning Error" }
            return attributedError(pkgInfo, "Classloader error: ${e.message}", cause = e)
        }

        val sourceClassNames = appInfo.metaData.getString(METADATA_SOURCE_CLASS)
        if (sourceClassNames.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "[ExtInstall] Missing $METADATA_SOURCE_CLASS metadata for extension $extName — returning Error" }
            return attributedError(pkgInfo, "Missing source class metadata: $pkgName")
        }

        val sources = sourceClassNames
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "[ExtInstall] Extension source load error: $extName ($it) — returning Error" }
                    return attributedError(pkgInfo, "Source class load failed: ${e.message}", cause = e)
                }
            }

        logcat(LogPriority.INFO) { "[ExtInstall] Extension loaded successfully: $extName ($pkgName) v$versionName" }

        val langs = sources.map { it.lang }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = Extension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            sources = sources,
            pkgFactory = appInfo.metaData.getString(METADATA_SOURCE_FACTORY),
            icon = appInfo.loadIcon(pkgManager),
            isShared = extensionInfo.isShared,
            // KMK -->
            signatureHash = signatures.last(),
            storeName = stores.firstOrNull { store ->
                signatures.all { it == store.signingKey }
            }?.let { store ->
                store.badgeLabel.takeIf(String::isNotBlank) ?: store.name
            },
            // KMK <--
        )
        return LoadResult.Success(extension)
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        // Every dereference below is nullable in the platform API and this is called with whatever the
        // package manager returned for a possibly malformed archive. Force-unwrapping here threw out of
        // installPrivateExtensionFile, which has no try/catch around this call; null is already the
        // handled "not signed" answer.
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return signatures?.map { Hash.sha256(it.toByteArray()) }?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private fun attributedError(pkgInfo: PackageInfo, reason: String, cause: Throwable? = null): LoadResult.Error {
        val appInfo = pkgInfo.applicationInfo
        val name = appInfo?.metaData?.getString(METADATA_NAME)
            ?: pkgInfo.packageName
        return LoadResult.Error(
            reason = reason,
            cause = cause,
            pkgName = pkgInfo.packageName,
            extensionName = name,
            versionName = pkgInfo.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            signatureHash = getSignatures(pkgInfo)?.firstOrNull(),
        )
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )

    private const val MAX_LOAD_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
}
