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
import eu.kanade.tachiyomi.data.track.TrackStatus
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TrackStatusPickerDialog(
    currentStatus: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // OTHER is what an untracked entry maps to, not a list a user can file an entry under.
    val selectable = TrackStatus.entries.filter { it != TrackStatus.OTHER }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.category_track_status_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(KMR.strings.category_track_status_dialog_body),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = currentStatus == null, onClick = { onSelect(null) })
                    Text(
                        text = stringResource(KMR.strings.category_track_status_none),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                for (status in selectable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(status.int) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentStatus == status.int, onClick = { onSelect(status.int) })
                        Text(
                            text = stringResource(status.res),
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
