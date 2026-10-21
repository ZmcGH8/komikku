package eu.kanade.tachiyomi.extension

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects extension failures and decides which one the user should be asked to report.
 *
 * A failing extension keeps failing, so every rule here exists to stop the prompt from repeating:
 * a shown-prompt set keyed by the exact failure, a suppression set keyed by package and version
 * code, and the user's own switch for the whole system. A new version of the same extension is a
 * new thing that can break a new way, so it clears neither set implicitly.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionErrorReporter(
    private val sourcePreferences: SourcePreferences,
) {

    private val _pendingPrompt = MutableStateFlow<ExtensionErrorReport?>(null)
    val pendingPrompt: StateFlow<ExtensionErrorReport?> = _pendingPrompt.asStateFlow()

    /**
     * Latest failure per package, for subtext on the extensions list. Keyed by package name alone
     * because that is what the list row knows; install and load errors both land here.
     */
    private val _errorsByPkg = MutableStateFlow<Map<String, ExtensionErrorReport>>(emptyMap())
    val errorsByPkg: StateFlow<Map<String, ExtensionErrorReport>> = _errorsByPkg.asStateFlow()

    /**
     * Records a failure. Safe to call from any thread and for the same failure repeatedly: only a
     * failure that has not been prompted for yet can raise a prompt.
     *
     * The failure is always recorded in [errorsByPkg] even when no prompt may follow, so the
     * extensions list keeps explaining why a source returns nothing. Turning the watchdog off, or
     * [prompt] being false, is about interrupting the user - not about hiding the evidence.
     *
     * @param prompt false to record the failure without interrupting. For the global search, which
     * hits every source at once and would otherwise raise a burst of popups over the results.
     */
    fun report(report: ExtensionErrorReport, prompt: Boolean = true) {
        _errorsByPkg.update { it + (report.pkgName to report) }

        if (!prompt) return
        if (!sourcePreferences.extensionWatchdogEnabled().get()) return
        if (isSuppressed(report) || hasPrompted(report)) return

        // A prompt is already on screen; queuing a second would drop the first silently.
        if (_pendingPrompt.value != null) return

        _pendingPrompt.value = report
        markPrompted(report)
    }

    /** The user acknowledged. The failure stays visible as subtext and may prompt again later. */
    fun dismissPrompt() {
        _pendingPrompt.value = null
    }

    /**
     * The user asked not to be told about this again until the extension is updated.
     *
     * Takes the report rather than reading it off [pendingPrompt], because the popup that offered
     * this is dismissed before the confirmation is answered - the report has to survive that.
     */
    fun suppressCurrentVersion(report: ExtensionErrorReport) {
        if (_pendingPrompt.value?.dedupeKey == report.dedupeKey) _pendingPrompt.value = null
        val suppressed = sourcePreferences.extensionErrorPromptsSuppressed()
        suppressed.set(suppressed.get() + report.versionKey)
    }

    /**
     * The user turned the watchdog off for good, not just for this extension.
     *
     * Covers every extension rather than one, because the thing prompting is the system as a whole:
     * an extension that fails silently is normal, and a user who has seen three of these popups in a
     * row wants them to stop, not to keep being told about each source separately. Re-enabling lives
     * in settings.
     */
    fun disableSystem() {
        _pendingPrompt.value = null
        sourcePreferences.extensionWatchdogEnabled().set(false)
    }

    fun errorFor(pkgName: String): ExtensionErrorReport? = _errorsByPkg.value[pkgName]

    /** Drops stored failures for an extension that is gone, so they cannot resurface. */
    fun clear(pkgName: String) {
        _errorsByPkg.update { it - pkgName }
        if (_pendingPrompt.value?.pkgName == pkgName) {
            _pendingPrompt.value = null
        }
    }

    private fun hasPrompted(report: ExtensionErrorReport): Boolean {
        return report.dedupeKey in sourcePreferences.extensionErrorPromptsShown().get()
    }

    private fun markPrompted(report: ExtensionErrorReport) {
        val shown = sourcePreferences.extensionErrorPromptsShown()
        shown.set(shown.get() + report.dedupeKey)
    }

    private fun isSuppressed(report: ExtensionErrorReport): Boolean {
        return report.versionKey in sourcePreferences.extensionErrorPromptsSuppressed().get()
    }
}

/**
 * A single extension failure, with enough identity to name the repository it came from so the user
 * reports it to that repository rather than to the app's own support channels.
 */
data class ExtensionErrorReport(
    val pkgName: String,
    val extensionName: String,
    val versionName: String,
    val versionCode: Long,
    val reason: String,
    val repoName: String? = null,
    val repoWebsiteUrl: String? = null,
    val sourceName: String? = null,
    /** Full trace, kept out of the prompt text but included in a copied report. */
    val stackTrace: String? = null,
    /** Issue search scoped to the repository that ships this extension, not to this app. */
    val searchUrl: String,
) {
    /** Suppression granularity: one version of one extension. */
    val versionKey: String get() = "$pkgName:$versionCode"

    /** Prompt granularity: one version failing one particular way. */
    val dedupeKey: String get() = "$pkgName:$versionCode:$reason"
}
