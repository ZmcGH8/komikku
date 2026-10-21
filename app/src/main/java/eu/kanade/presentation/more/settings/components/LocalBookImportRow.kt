package eu.kanade.presentation.more.settings.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.io.LocalBookImporter

/**
 * Picks EPUBs off the device and files them into the local source under a name the reader chooses.
 *
 * ### Why the name comes second and not first
 *
 * Asking before the picker would mean asking about files the reader has not chosen yet, and the
 * answer would be wrong half the time. Picking first also lets the dialog say how many books are
 * about to arrive, which is the thing actually worth confirming before a copy.
 *
 * ### Why one folder per import
 *
 * The local source lists folders, not files. Four EPUBs copied in as four entries would be four
 * books, and neither the picker nor the local source has any way to know they are one series —
 * only the reader does, which is why this asks. One name, one folder.
 */
@Composable
fun LocalBookImportRow(
    importer: LocalBookImporter = globalAppGraph.localBookImporter,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Deliberately not remembered across process death: this is a flag about work in flight, and a
    // restored `true` after the process was killed mid-copy would leave the button disabled for good.
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pending = uris
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(KMR.strings.local_book_import_summary),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { picker.launch(arrayOf(EPUB_MIME, ANY_MIME)) },
            enabled = !busy,
        ) {
            Text(stringResource(KMR.strings.local_book_import))
        }
    }

    if (pending.isNotEmpty()) {
        LocalBookImportDialog(
            count = pending.size,
            onDismiss = { pending = emptyList() },
            onConfirm = { name ->
                val uris = pending
                pending = emptyList()
                busy = true
                // Launch on this scope's own (main) dispatcher and dip into IO only for the copy.
                // Toasts need a Looper-prepared thread: launching the whole block with launchIO put
                // context.toast on an IO worker and crashed
                // "Can't toast on a thread that has not called Looper.prepare()".
                scope.launch {
                    when (val result = withIOContext { importer.import(name, uris) }) {
                        is LocalBookImporter.Result.Imported -> {
                            context.toast(
                                if (result.skipped.isEmpty()) {
                                    context.stringResource(
                                        KMR.strings.local_book_import_done,
                                        result.folder,
                                        result.copied.size,
                                    )
                                } else {
                                    context.stringResource(
                                        KMR.strings.local_book_import_done_partial,
                                        result.folder,
                                        result.copied.size,
                                        result.skipped.size,
                                    )
                                },
                            )
                        }

                        is LocalBookImporter.Result.Failed -> context.toast(result.error.message.orEmpty())
                    }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun LocalBookImportDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.local_book_import_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Text(stringResource(KMR.strings.local_book_import_name_summary, count))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(KMR.strings.local_book_import_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                // The same two names the importer refuses, checked here so the button cannot offer an
                // action that is going to fail. The importer is still the one that decides.
                enabled = name.isNotBlank() && name.trim() != "." && name.trim() != "..",
                onClick = { onConfirm(name) },
            ) {
                Text(stringResource(KMR.strings.local_book_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/** The registered type for EPUB, which is what a book on the device usually declares. */
private const val EPUB_MIME = "application/epub+zip"

/**
 * Alongside the EPUB type, because plenty of `.epub` files are served as
 * `application/octet-stream` and a picker offering only the registered type shows an empty folder.
 * What is not a book is dropped by the importer on its extension, and reported as skipped.
 */
private const val ANY_MIME = "*/*"
