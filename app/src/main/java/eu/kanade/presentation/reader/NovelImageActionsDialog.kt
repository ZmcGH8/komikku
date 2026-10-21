// KMK -->
package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Actions for one illustration of the open book (EPUB / novel text reader).
 *
 * Reached by long-pressing the illustration, which the WebView's own hit test is the only thing
 * that can answer — a book's pages are text documents and carry no image stream, which is why the
 * ordinary page-actions sheet cannot count them among its subjects. The action reuses the ordinary
 * cover pipeline: whichever file the artwork lives in, it was drawn from the same archive the
 * reader is holding open, and [eu.kanade.tachiyomi.ui.reader.ReaderViewModel.setBookImageAsCover]
 * resolves it to a stream from the loader the moment the user confirms.
 */
@Composable
fun NovelImageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.set_as_cover),
                    icon = Icons.Outlined.Photo,
                    onClick = { showSetCoverDialog = true },
                )
            }
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                // Applied on the model's own scope; the sheet leaves ahead of the toast that the
                // result event produces.
                showSetCoverDialog = false
                onSetAsCover()
                onDismissRequest()
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}
// KMK <--
