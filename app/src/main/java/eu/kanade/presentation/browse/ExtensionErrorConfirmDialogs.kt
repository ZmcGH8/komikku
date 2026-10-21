package eu.kanade.presentation.browse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.tachiyomi.extension.ExtensionErrorReport
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirms turning the watchdog off for one version of one extension.
 *
 * Both opt-outs on the error popup are one tap away from an interrupting dialog, and neither is
 * obvious about how far it reaches - "don't show again" reads as this page, not this extension's
 * lifetime. The version is named explicitly because that is the boundary being set.
 */
@Composable
fun ExtensionErrorVersionSuppressDialog(
    report: ExtensionErrorReport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val extensionName = report.extensionName.ifBlank { report.pkgName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(KMR.strings.ext_error_version_confirm_title),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = stringResource(
                    KMR.strings.ext_error_version_confirm_body,
                    extensionName,
                    report.versionName,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(stringResource(KMR.strings.ext_error_version_confirm_dont_show))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * Confirms turning the watchdog off for every extension.
 *
 * Separate from the version-scoped dialog because it is not reversible from the popup and does not
 * stop at one extension - a user who has dismissed several of these in a row wants them gone, and
 * the confirmation says where to get them back.
 */
@Composable
fun ExtensionErrorDisableSystemDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(KMR.strings.ext_error_system_confirm_title),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(stringResource(KMR.strings.ext_error_system_confirm_body))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    text = stringResource(KMR.strings.ext_error_system_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
