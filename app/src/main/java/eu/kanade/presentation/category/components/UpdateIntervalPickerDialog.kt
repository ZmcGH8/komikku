package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** Day counts offered for a per-category interval. One week is the slowest, matching the issue. */
private val INTERVAL_CHOICES = listOf(1, 2, 3, 7, 14, 30)

@Composable
fun UpdateIntervalPickerDialog(
    currentIntervalDays: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.category_update_interval)) },
        text = {
            Column {
                Text(
                    text = stringResource(KMR.strings.category_update_interval_body),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = currentIntervalDays == null, onClick = { onSelect(null) })
                    Text(
                        text = stringResource(KMR.strings.category_update_interval_default),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                for (days in INTERVAL_CHOICES) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(days) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentIntervalDays == days, onClick = { onSelect(days) })
                        Text(
                            text = pluralStringResource(
                                KMR.plurals.category_update_interval_days,
                                days,
                                days,
                            ),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}
