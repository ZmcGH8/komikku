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
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks the reading mode every manga in this category opens with.
 *
 * "None" is not "the global default" spelled out - it removes the override, so the category
 * follows whatever the reader preference says. Storing the default's value here instead would
 * pin the category to a mode that later changes globally, which is the opposite of what "unset"
 * should mean.
 *
 * [currentFlag] is a [ReadingMode.flagValue], or null when unset; [onSelect] takes the same shape.
 */
@Composable
fun ReaderModePickerDialog(
    currentFlag: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Same list the reader's own mode picker offers, so the two cannot drift apart.
    val selectable = ReadingMode.entries - ReadingMode.DEFAULT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.category_reader_mode_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(KMR.strings.category_reader_mode_dialog_body),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = currentFlag == null, onClick = { onSelect(null) })
                    Text(
                        text = stringResource(KMR.strings.category_reader_mode_none),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                for (mode in selectable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode.flagValue) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentFlag == mode.flagValue,
                            onClick = { onSelect(mode.flagValue) },
                        )
                        Text(
                            // The reader's own label for each mode, so the two pickers cannot
                            // drift apart.
                            text = stringResource(mode.stringRes),
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
