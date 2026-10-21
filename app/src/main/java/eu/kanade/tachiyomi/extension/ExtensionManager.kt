package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogD
import exh.source.BlacklistedSources
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.ExhPreferences
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = globalAppGraph.sourcePreferences,
    private val trustExtension: TrustExtension = globalAppGraph.trustExtension,
) {
    // KMK -->
    private val errorReporter: ExtensionErrorReporter = globalAppGraph.extensionErrorReporter
    // KMK <--

    val scope = CoroutineScope(SupervisorJob())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available extensions can be found.
     */
    private val api = ExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy { ExtensionInstaller(context) }

    val installerFailureReasons: StateFlow<Map<String, String>> get() = installer.failureReasonsFlow

    private val iconMap = ConcurrentHashMap<String, Drawable>()

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Installed>())
    val installedExtensionsFlow = installedExtensionMapFlow.mapExtensions(scope)

    // Source id -> owning package. Resolving this by scanning every extension's source list made
    // each lookup cost O(extensions x sources), and it runs per manga in several screens.
    private val sourcePkgIndex = installedExtensionMapFlow
        .map { extensions ->
            buildMap<Long, String> {
                extensions.values.forEach { extension ->
                    extension.sources.forEach { put(it.id, extension.pkgName) }
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Available>())

    // SY -->
    val availableExtensionsFlow = availableExtensionMapFlow.map { it.filterNotBlacklisted().values.toList() }
        .stateIn(scope, SharingStarted.Lazily, availableExtensionMapFlow.value.values.toList())
    // SY <--

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionMapFlow.mapExtensions(scope)

    init {
        scope.launch(mihon.core.concurrency.AppDispatchersHolder.get().io) {
            initExtensions()
        }
        ExtensionInstallReceiver(InstallationListener()).register(context)
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? = sourcePkgIndex.value[sourceId]

    /**
     * The installed extension owning [pkgName], for showing its icon next to an error about it.
     *
     * Read from the flow's current value rather than collected: this backs a single icon in a
     * dialog that is already up, and subscribing a flow per prompt would outlive it. Null when the
     * extension has since been uninstalled, which the caller renders without an icon.
     */
    fun extensionFor(pkgName: String): Extension.Installed? = installedExtensionMapFlow.value[pkgName]

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> =
        sourcePkgIndex.map { it[sourceId] }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = getExtensionPackage(sourceId)
        if (pkgName != null) {
            return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
                ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)
                    ?.applicationInfo
                    ?.loadIcon(context.packageManager)
                    ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!
            }
        }

        // SY -->
        return when (sourceId) {
            // KMK -->
            in EHENTAI_EXT_SOURCES -> ContextCompat.getDrawable(context, R.mipmap.ic_ehentai_source)
            in EXHENTAI_EXT_SOURCES -> ContextCompat.getDrawable(context, R.mipmap.ic_exhentai_source)
            // KMK <--
            MERGED_SOURCE_ID -> ContextCompat.getDrawable(context, R.mipmap.ic_merged_source)
            else -> null
        }
        // SY <--
    }

    private var availableExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<Extension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions.
     */
    private suspend fun initExtensions() {
        try {
            loadAndRegisterExtensions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Callers gate on isInitialized, so a throw here used to leave the whole app waiting
            // on a flag that was never set. A failed load must still complete.
            logcat(LogPriority.ERROR, e) { "[ExtInstall] initExtensions: extension load failed" }
        } finally {
            _isInitialized.value = true
        }
    }

    private suspend fun loadAndRegisterExtensions() {
        logcat(LogPriority.INFO) { "[ExtInstall] initExtensions: starting extension load" }
        val extensions = ExtensionLoader.loadExtensions(context)
        logcat(LogPriority.INFO) { "[ExtInstall] initExtensions: loaded ${extensions.size} results (${extensions.count { it is LoadResult.Success }} success, ${extensions.count { it is LoadResult.Untrusted }} untrusted, ${extensions.count { it is LoadResult.Error }} error)" }

        val successMap = extensions
            .filterIsInstance<LoadResult.Success>()
            .associate { it.extension.pkgName to it.extension }
        logcat(LogPriority.INFO) { "[ExtInstall] initExtensions: registering ${successMap.size} installed extensions: ${successMap.keys}" }
        installedExtensionMapFlow.value = successMap

        untrustedExtensionMapFlow.value = extensions
            .filterIsInstance<LoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }
            // SY -->
            .filterNotBlacklisted()
        // SY <--

        extensions.filterIsInstance<LoadResult.Error>().forEach { error ->
            logcat(LogPriority.WARN) { "[ExtInstall] initExtensions: load error — ${error.reason}" }
            // KMK -->
            reportLoadFailure(error)
            // KMK <--
        }
    }

    // KMK -->
    /**
     * A user-facing message for a failure. Extension exceptions frequently carry a bare host name,
     * a URL, or nothing at all, so this falls back to the type rather than showing an empty prompt.
     */
    private fun Throwable.readableMessage(): String {
        val message = localizedMessage?.trim()
        return when {
            !message.isNullOrEmpty() && message.length <= 300 -> message
            else -> this::class.java.simpleName.ifEmpty { "Unknown error" }
        }
    }

    /**
     * An extension that cannot load is reported as a runtime failure too, so the user is asked to
     * report it to its repository. The reason already identifies the failure; this only supplies the
     * identity that [LoadResult.Error] now carries.
     *
     * Only failures that actually threw are tracked. The loader returns an Error for a handful of
     * conditions that are not faults - an unsigned or absent package, an unsupported lib version,
     * NSFW content being switched off - and each of those is only ever a line logged at error
     * level. Tracking them marked working extensions as broken, and NSFW in particular turned a
     * deliberate setting into an error the user was then invited to report upstream. [cause] being
     * null is how those are told apart from a genuine crash while loading.
     */
    private fun reportLoadFailure(error: LoadResult.Error) {
        val cause = error.cause ?: return
        val pkgName = error.pkgName ?: return
        val store = storeFor(pkgName)
        val name = error.extensionName ?: pkgName
        errorReporter.report(
            ExtensionErrorReport(
                pkgName = pkgName,
                extensionName = name,
                versionName = error.versionName ?: "",
                versionCode = error.versionCode,
                reason = error.reason ?: cause.readableMessage(),
                repoName = store?.name ?: error.storeName,
                repoWebsiteUrl = store?.contact?.website,
                stackTrace = cause.stackTraceText(),
                searchUrl = ExtensionRepoLinks.githubIssueSearchUrl(name, store),
            ),
        )
    }

    /**
     * The repository that ships an extension. The installed copy carries its store once a repo refresh
     * has matched it, which also covers an extension whose repo is no longer listed.
     */
    private fun storeFor(pkgName: String): ExtensionStore? {
        val installed = installedExtensionMapFlow.value[pkgName]?.store
        if (installed != null) return installed
        val available = availableExtensionMapFlow.value.values.firstOrNull { it.pkgName == pkgName }
        return available?.store
    }

    /**
     * Reports a failure raised while an installed extension was in use. Resolves the extension and
     * its repository from the source that failed, so the prompt names the right repository.
     *
     * [prompt] false records the failure without raising a popup - for the global search, which hits
     * every source at once and would otherwise stack a prompt over every broken result.
     */
    fun reportSourceError(source: Source?, throwable: Throwable, prompt: Boolean = true) {
        val src = source ?: return
        val pkgName = getExtensionPackage(src.id) ?: return
        val installed = installedExtensionMapFlow.value[pkgName]
        val store = storeFor(pkgName)
        val name = installed?.name ?: src.name

        errorReporter.report(
            ExtensionErrorReport(
                pkgName = pkgName,
                extensionName = name,
                versionName = installed?.versionName ?: "",
                versionCode = installed?.versionCode ?: 0L,
                reason = throwable.readableMessage(),
                repoName = store?.name ?: installed?.storeName,
                repoWebsiteUrl = store?.contact?.website,
                sourceName = src.name.takeIf { it != name },
                stackTrace = throwable.stackTraceText(),
                searchUrl = ExtensionRepoLinks.githubIssueSearchUrl(name, store),
            ),
            prompt = prompt,
        )
    }

    /**
     * A source call for [source] completed without failing, so whatever was recorded for its
     * extension no longer holds. Cleared rather than left in place because most failures that
     * reach here are the site being briefly unavailable rather than the scraper being broken:
     * a 500, a 421, a rate limit or a dropped connection all recover on their own, and an
     * extension that keeps its error badge after it is plainly working again reads as still
     * broken on the extensions page.
     *
     * Only the recorded failure goes - the user's own "don't tell me again" choices are about
     * being interrupted and stay, so a repeat of the same failure does not start popping up again.
     */
    fun clearSourceError(source: Source?) {
        val src = source ?: return
        val pkgName = getExtensionPackage(src.id) ?: return
        errorReporter.clear(pkgName)
    }
    // KMK <--

    // EXH -->
    private fun <T : Extension> Map<String, T>.filterNotBlacklisted(): Map<String, T> {
        val blacklistEnabled = preferences.enableSourceBlacklist().get()
        return filterNot { (_, extension) ->
            extension.isBlacklisted(blacklistEnabled)
                .also {
                    if (it) this@ExtensionManager.xLogD("Removing blacklisted extension: (name: %s, pkgName: %s)!", extension.name, extension.pkgName)
                }
        }
    }

    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = preferences.enableSourceBlacklist().get(),
        // KMK -->
        isHentaiEnabled: Boolean = globalAppGraph.exhPreferences.isHentaiEnabled().get(),
        // KMK <--
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS &&
            blacklistEnabled &&
            // KMK -->
            isHentaiEnabled
        // KMK <--
    }
    // EXH <--

    /**
     * Finds the available extensions in the [api] and updates [availableExtensionMapFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<Extension.Available> = try {
            api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        enableAdditionalSubLanguages(extensions)

        availableExtensionMapFlow.value = extensions.associateBy {
            it.pkgName +
                // KMK -->
                "_${it.signatureHash}"
            // KMK <--
        }
        updatedInstalledExtensionsStatuses(extensions)
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.Source::lang)
            .map(Extension.Available.Source::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        val installedExtensionsMap = installedExtensionMapFlow.value.toMutableMap()
        var changed = false

        // Indexed once: the previous form scanned the whole repo list for every installed extension,
        // and the signature-qualified match compared against every entry rather than hashing.
        val availableByKey = availableExtensions.associateBy { "${it.pkgName}_${it.signatureHash}" }
        val availableByPkg = availableExtensions.groupBy { it.pkgName }

        for ((pkgName, extension) in installedExtensionsMap) {
            val availableForPkg = availableByPkg[pkgName]
            val availableExt = availableForPkg?.let { candidates ->
                availableByKey["${pkgName}_${extension.signatureHash}"] ?: candidates.firstOrNull()
            }

            val updated = when {
                // No longer offered by any repo, so it can never be updated again.
                availableExt == null -> extension.copy(isObsolete = true, hasUpdate = false)
                // SY -->
                extension.isBlacklisted() && !extension.isRedundant -> extension.copy(isRedundant = true)
                // SY <--
                // KMK -->
                else -> extension.copy(
                    hasUpdate = extension.updateExists(availableExt),
                    store = availableExt.store,
                    isObsolete = false,
                    storeName = extension.storeName ?: availableExt.storeName,
                )
                // KMK <--
            }

            // Every branch used to set this unconditionally, so each refresh re-emitted the entire
            // map and re-ran the notifier even when nothing had actually changed.
            if (updated != extension) {
                installedExtensionsMap[pkgName] = updated
                changed = true
            }
        }

        if (changed) {
            installedExtensionMapFlow.value = installedExtensionsMap
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(extension.apkUrl, extension)
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        val availableExt = resolveAvailableExtension(extension.pkgName, extension.signatureHash)
            ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(
            extension.pkgName +
                // KMK -->
                "_${extension.signatureHash}",
            // KMK <--
        )
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: Extension) {
        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.value -= extension.pkgName

        val result = ExtensionLoader.loadExtensionFromPkgName(context, extension.pkgName)
        when (result) {
            is LoadResult.Success -> registerNewExtension(result.extension)
            // KMK -->
            is LoadResult.Untrusted -> {
                logcat(LogPriority.WARN) { "[ExtInstall] Extension ${extension.pkgName} still untrusted after trust — re-adding to untrusted list" }
                untrustedExtensionMapFlow.value += result.extension
            }
            else -> {
                logcat(LogPriority.ERROR) { "[ExtInstall] Extension ${extension.pkgName} failed to load after trust (result: $result) — extension lost" }
            }
            // KMK <--
        }
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        // SY -->
        if (extension.isBlacklisted()) {
            xLogD("Removing blacklisted extension: (name: String, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }
        // SY <--

        installedExtensionMapFlow.value += extension
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        // SY -->
        if (extension.isBlacklisted()) {
            xLogD("Removing blacklisted extension: (name: %s, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }
        // SY <--

        installedExtensionMapFlow.value += extension
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.value -= pkgName
        untrustedExtensionMapFlow.value -= pkgName
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: Extension.Installed) {
            logcat(LogPriority.INFO) { "[ExtInstall] onExtensionInstalled: ${extension.name} (${extension.pkgName}) v${extension.versionName}" }
            iconMap.remove(extension.pkgName)
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
            // KMK --> Only on install, not onExtensionUpdated below: these tiers count sources
            // the user added, and an update to an existing source is not a new one.
            runCatching { globalAppGraph.achievementManager.incrementCounter("sources") }
            runCatching { globalAppGraph.rotatingAchievementPool.markProgress("rotating_weekly_extra_1") }
            // KMK <--
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            logcat(LogPriority.INFO) { "[ExtInstall] onExtensionUpdated: ${extension.name} (${extension.pkgName}) v${extension.versionName}" }
            iconMap.remove(extension.pkgName)
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            logcat(LogPriority.INFO) { "[ExtInstall] onExtensionUntrusted: ${extension.name} (${extension.pkgName}) v${extension.versionName}" }
            installedExtensionMapFlow.value -= extension.pkgName
            untrustedExtensionMapFlow.value += extension
            updatePendingUpdatesCount()
        }

        override fun onPackageUninstalled(pkgName: String) {
            logcat(LogPriority.INFO) { "[ExtInstall] onPackageUninstalled: $pkgName" }
            iconMap.remove(pkgName)
            // KMK -->
            errorReporter.clear(pkgName)
            // KMK <--
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    /**
     * Finds the available counterpart of an installed extension.
     *
     * The badge and the button have to agree: the update badge falls back to matching on package
     * name alone when a repo is re-signed, so a lookup that only used the signature-qualified key
     * left the row advertising an update whose button did nothing when tapped.
     */
    private fun resolveAvailableExtension(pkgName: String, signatureHash: String): Extension.Available? {
        val available = availableExtensionMapFlow.value
        return available["${pkgName}_$signatureHash"]
            ?: available.values.find { it.pkgName == pkgName && it.signatureHash == signatureHash }
            ?: available.values.find { it.pkgName == pkgName }
    }

    private fun Extension.Installed.updateExists(availableExtension: Extension.Available? = null): Boolean {
        val availableExt = availableExtension ?: resolveAvailableExtension(pkgName, signatureHash)
            ?: return false

        return (availableExt.versionCode > versionCode || availableExt.libVersion > libVersion)
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionMapFlow.value.values.count { it.hasUpdate }
        // This runs after every install event and every repo refresh, so the preference write is skipped
        // unless the number actually moved. The dismiss stays unconditional at zero: returning
        // early on an unchanged zero would leave a notification posted before a backup restore.
        if (pendingUpdateCount != preferences.extensionUpdatesCount().get()) {
            preferences.extensionUpdatesCount().set(pendingUpdateCount)
        }
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : Extension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <T : Extension> StateFlow<Map<String, T>>.mapExtensions(scope: CoroutineScope): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Lazily, value.values.toList())
    }
}
