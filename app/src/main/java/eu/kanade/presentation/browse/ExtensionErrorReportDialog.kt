package eu.kanade.presentation.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.browse.components.ExtensionIcon
import eu.kanade.tachiyomi.extension.ExtensionErrorReport
import eu.kanade.tachiyomi.extension.model.Extension
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Which opt-out the error popup is asking to confirm, if any.
 *
 * Held by the caller rather than derived from [ExtensionErrorReportDialog]'s own state so the
 * popup and its confirmation cannot both be composed at once - two dialogs would compete for the
 * same dismiss gesture.
 */
sealed interface ExtensionErrorConfirm {
    data class Suppress(val report: ExtensionErrorReport) : ExtensionErrorConfirm
    data object DisableSystem : ExtensionErrorConfirm
}

/**
 * Asks the user to report a broken extension to whoever maintains it.
 *
 * The wording is deliberately explicit about where a report belongs: an extension that fails because a
 * site changed cannot be fixed by this app, so a report sent to the app's own support channels is
 * both pointless and slow for everyone.
 *
 * The action is a GitHub issue search scoped to the extension's own repository, rather than a link to
 * a chat server. Pointing every user at one invite floods its maintainers with identical reports and
 * buries anything else they are trying to read; a scoped search lets them find the issue tracker,
 * see whether it is already reported, and add to an existing report instead of duplicating it.
 *
 * [extension] is looked up separately from [report] so this can show the icon; null renders without
 * one, which is what an extension that has since been uninstalled resolves to.
 */
@Composable
fun ExtensionErrorReportDialog(
    report: ExtensionErrorReport,
    extension: Extension?,
    onOk: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableSystem: () -> Unit,
    onSearchGitHub: (String) -> Unit,
    onCopyReport: () -> Unit,
) {
    val extensionName = report.extensionName.ifBlank { report.pkgName }
    val repoName = report.repoName

    AlertDialog(
        onDismissRequest = onOk,
        properties = DialogProperties(dismissOnClickOutside = true),
        icon = {
            extension?.let {
                ExtensionIcon(extension = it, modifier = Modifier.size(40.dp))
            }
        },
        title = {
            Text(
                text = stringResource(KMR.strings.ext_error_report_title, extensionName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (repoName.isNullOrBlank()) {
                        stringResource(
                            KMR.strings.ext_error_report_no_repo,
                            extensionName,
                            report.reason,
                        )
                    } else {
                        stringResource(
                            KMR.strings.ext_error_report_body,
                            extensionName,
                            report.versionName,
                            repoName,
                            report.reason,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The identity and version as chips rather than another sentence: the paragraph
                // above already names them, and a user scanning the popup wants the version at a
                // glance to know whether the "don't show again" they are about to press applies.
                ExtensionErrorFacts(report)

                Spacer(Modifier.size(4.dp))
                HorizontalDivider()
                Spacer(Modifier.size(4.dp))

                ExtensionErrorAction(
                    label = stringResource(KMR.strings.ext_error_search_github),
                    onClick = { onSearchGitHub(report.searchUrl) },
                )
                ExtensionErrorAction(
                    label = stringResource(KMR.strings.ext_error_copy_report),
                    onClick = onCopyReport,
                )

                Spacer(Modifier.size(4.dp))
                HorizontalDivider()

                TextButton(
                    onClick = onDontShowAgain,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(KMR.strings.ext_error_version_confirm_dont_show),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOk) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        // Both opt-outs sit in the body rather than as dialog buttons: AlertDialog has room for two
        // actions, and these two have to sit together as "stop asking me" next to the reason it is
        // being asked. The destructive one is worded and coloured as such.
        dismissButton = {
            TextButton(onClick = onDisableSystem) {
                Text(
                    text = stringResource(KMR.strings.ext_error_disable_system),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** Version, repository and failing source, as a compact fact list. */
@Composable
private fun ExtensionErrorFacts(report: ExtensionErrorReport) {
    val chip: @Composable (String) -> Unit = { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (report.versionName.isNotBlank()) {
            chip(stringResource(KMR.strings.ext_error_version_label, report.versionName))
        }
        report.repoName?.takeIf { it.isNotBlank() }?.let { repo ->
            chip(stringResource(KMR.strings.ext_error_repo_label, repo))
        }
        // Only when the extension covers several sources: the popup title already named the
        // extension, and the source is what distinguishes which one actually broke.
        report.sourceName?.takeIf { it.isNotBlank() }?.let { source ->
            chip(stringResource(KMR.strings.ext_error_source_label, source))
        }
    }
}

/**
 * One tappable line in the dialog body.
 *
 * Not a [TextButton]: those carry their own minimum touch target and padding, which stacked two of
 * them reads as a second row of dialog buttons and crowds the actual text above.
 */
@Composable
private fun ExtensionErrorAction(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
