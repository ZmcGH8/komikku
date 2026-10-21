package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import kotlin.time.Duration.Companion.seconds

class ExtensionsScreenModel(
    preferences: SourcePreferences = globalAppGraph.sourcePreferences,
    basePreferences: BasePreferences = globalAppGraph.basePreferences,
    private val extensionManager: ExtensionManager = globalAppGraph.extensionManager,
    private val getExtensions: GetExtensionsByType = globalAppGraph.getExtensionsByType,
) : StateScreenModel<ExtensionsScreenModel.State>(State()) {

    private val currentDownloads = MutableStateFlow<Map<String, InstallStep>>(hashMapOf())

    // KMK -->
    private val installFailureReasons = globalAppGraph.extensionManager.installerFailureReasons

    /**
     * Failure text per package, for the row subtext.
     *
     * The installer's keys are `pkgName_signatureHash` while the reporter keys by package alone, so
     * install reasons are re-keyed here and allowed to stand on their own. An install failure usually
     * has no matching load or runtime failure to pair with, and merging only into an existing entry
     * dropped exactly that case.
     *
     * Split on the *last* underscore: a package name may itself contain one, but the signature hash
     * is hex and never does, so the last separator is always the reliable one.
     */
    private val extensionErrors = combine(
        globalAppGraph.extensionErrorReporter.errorsByPkg,
        installFailureReasons,
        preferences.extensionErrorTextEnabled().changes(),
    ) { loadErrors, installErrors, showText ->
        // Recorded either way: the setting hides the label, it does not decide what happened.
        if (!showText) return@combine emptyMap()
        buildMap<String, String> {
            loadErrors.forEach { (pkgName, report) -> put(pkgName, report.reason) }
            installErrors.forEach { (key, reason) -> put(key.substringBeforeLast("_"), reason) }
        }
    }
    // KMK <--

    init {
        val context = globalAppGraph.context
        val extensionMapper: (
            Map<String, InstallStep>,
            Map<String, String>,
        ) -> ((Extension) -> ExtensionUiModel.Item) = { downloads, errors ->
            {
                ExtensionUiModel.Item(
                    it,
                    downloads[
                        it.pkgName +
                            // KMK -->
                            "_${it.signatureHash}",
                        // KMK <--
                    ] ?: InstallStep.Idle,
                    // KMK -->
                    errors[it.pkgName],
                    // KMK <--
                )
            }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }
                    .distinctUntilChanged()
                    .debounce(SEARCH_DEBOUNCE_MILLIS)
                    .map { searchQueryPredicate(it ?: "") },
                // KMK -->
                state.map { it.nsfwOnly }
                    .distinctUntilChanged()
                    .debounce(SEARCH_DEBOUNCE_MILLIS),
                // KMK <--
                currentDownloads,
                getExtensions.subscribe(),
                // KMK -->
                extensionErrors,
                // KMK <--
            ) { predicate, nsfwOnly, downloads, (_updates, _installed, _available, _untrusted), errors ->
                buildMap {
                    val updates = _updates.filter(predicate).map(extensionMapper(downloads, errors))
                        // KMK -->
                        .filter { !nsfwOnly || it.extension.isNsfw }
                    // KMK <--
                    if (updates.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updates)
                    }

                    val installed = _installed.filter(predicate).map(extensionMapper(downloads, errors))
                        // KMK -->
                        .filter { !nsfwOnly || it.extension.isNsfw }
                    // KMK <--
                    val untrusted = _untrusted.filter(predicate).map(extensionMapper(downloads, errors))
                        // KMK -->
                        .filter { !nsfwOnly || it.extension.isNsfw }
                    // KMK <--
                    if (installed.isNotEmpty() || untrusted.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), installed + untrusted)
                    }

                    val languagesWithExtensions = _available
                        .filter(predicate)
                        // KMK -->
                        .filter { !nsfwOnly || it.isNsfw }
                        // KMK <--
                        .groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, exts) ->
                            ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                                exts.map(extensionMapper(downloads, errors))
                        }
                    if (languagesWithExtensions.isNotEmpty()) {
                        putAll(languagesWithExtensions)
                    }

                    // KMK -->
                    // Show "More..." header if no available extensions
                    if (_available.isEmpty()) {
                        put(ExtensionUiModel.Header.Resource(KMR.strings.extensions_page_more), emptyList())
                    }
                    // KMK <--
                }
            }
                .collectLatest { items ->
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            items = items,
                        )
                    }
                }
        }

        screenModelScope.launchIO { findAvailableExtensions() }

        preferences.extensionUpdatesCount().changes()
            .onEach { mutableState.update { state -> state.copy(updates = it) } }
            .launchIn(screenModelScope)

        basePreferences.extensionInstaller().changes()
            .onEach { mutableState.update { state -> state.copy(installer = it) } }
            .launchIn(screenModelScope)
    }

    fun searchQueryPredicate(query: String): (Extension) -> Boolean {
        val subqueries = query.split(",")
            .map { it.trim() }
            .filterNot { it.isBlank() }

        if (subqueries.isEmpty()) return { true }

        return { extension ->
            subqueries.any { subquery ->
                if (extension.name.contains(subquery, ignoreCase = true)) return@any true

                when (extension) {
                    is Extension.Installed -> extension.sources.any { source ->
                        source.name.contains(subquery, ignoreCase = true) ||
                            (source as? HttpSource)?.getHomeUrl()?.contains(subquery, ignoreCase = true) == true ||
                            source.id == subquery.toLongOrNull()
                    }

                    is Extension.Available -> extension.sources.any {
                        it.name.contains(subquery, ignoreCase = true) ||
                            it.baseUrl.contains(subquery, ignoreCase = true) ||
                            it.id == subquery.toLongOrNull()
                    }

                    else -> false
                }
            }
        }
    }

    fun search(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun updateAllExtensions() {
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.Available) {
        screenModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
        removeDownloadState(extension)
        // KMK -->
        // Dismissing the retained error is the user's way of saying "stop showing me this".
        globalAppGraph.extensionErrorReporter.clear(extension.pkgName)
        // KMK <--
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        currentDownloads.update {
            it + Pair(
                extension.pkgName +
                    // KMK -->
                    "_${extension.signatureHash}",
                // KMK <--
                installStep,
            )
        }
    }

    private fun removeDownloadState(extension: Extension) {
        currentDownloads.update {
            it - (
                extension.pkgName +
                    // KMK -->
                    "_${extension.signatureHash}"
                // KMK <--
                )
        }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) {
        var lastStep = InstallStep.Idle
        this
            .onEach { installStep ->
                lastStep = installStep
                addDownloadState(extension, installStep)
            }
            .takeWhile { installStep -> installStep != InstallStep.Installed }
            .onCompletion { cause ->
                // An error has to persist: the row shows a retry affordance and the reason as
                // subtext, and wiping it here made both disappear the moment the flow ended.
                if (cause !is CancellationException && lastStep != InstallStep.Error) {
                    removeDownloadState(extension)
                }
            }
            .collect()
    }

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }

            extensionManager.findAvailableExtensions()

            // Fake slower refresh so it doesn't seem like it's not doing anything
            delay(1.seconds)

            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    // KMK -->
    fun toggleNsfwOnly() {
        mutableState.update {
            it.copy(nsfwOnly = !it.nsfwOnly)
        }
    }
    // KMK <--

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val items: ItemGroups = mutableMapOf(),
        val updates: Int = 0,
        val installer: BasePreferences.ExtensionInstaller? = null,
        val searchQuery: String? = null,
        // KMK -->
        val nsfwOnly: Boolean = false,
        // KMK <--
    ) {
        val isEmpty = items.isEmpty()
    }
}

typealias ItemGroups = Map<ExtensionUiModel.Header, List<ExtensionUiModel.Item>>

object ExtensionUiModel {
    sealed interface Header {
        data class Resource(val textRes: StringResource) : Header
        data class Text(val text: String) : Header
    }

    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
        // KMK -->
        /** Reason this extension last failed, shown as subtext under the name. */
        val errorReason: String? = null,
        // KMK <--
    )
}
