package exh.yakuyomi

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * The custom-GGUF half of [LocalLlmManager]: the models a user has imported by hand, and the copy
 * that brings a new one in.
 *
 * An import stages into a `.tmp` and drops it on any failure. That matters because a partial file
 * is invisible to [importedModels] - which lists only `.gguf` - while still occupying space the
 * manager requires before it will start another import, so a leaked partial would strand the user
 * with no way to recover and no way to see the cause.
 */
internal class ImportedGgufModels(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onImported: (LocalLlmModel) -> Unit,
) {
    val customDir = File(context.filesDir, "local_llm_models/custom")

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Imported custom GGUFs (one [LocalLlmModel] per file in the custom dir). */
    fun importedModels(): List<LocalLlmModel> {
        val dir = customDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 1_000_000L && it.extension.equals("gguf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { customModelFor(it) }
    }

    fun customModelFor(file: File): LocalLlmModel {
        val mmprojCandidate = File(file.parentFile, "${file.nameWithoutExtension}.mmproj")
            .takeIf { it.exists() && it.length() > 1_000_000L }
            ?: File(file.parentFile, "${file.nameWithoutExtension}_mmproj.gguf")
                .takeIf { it.exists() && it.length() > 1_000_000L }
        return LocalLlmModel(
            id = "custom:${file.name}",
            displayName = file.nameWithoutExtension,
            description = "User-imported GGUF" + if (mmprojCandidate != null) " + vision" else "",
            paramsB = "?",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = mmprojCandidate != null,
            sizeBytes = file.length() + (mmprojCandidate?.length() ?: 0L),
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufFile = file.absolutePath,
            mmprojFile = mmprojCandidate?.absolutePath,
            mmprojRepo = if (mmprojCandidate != null) "custom" else null,
            isCustom = true,
        )
    }

    private fun sanitizeGgufName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "model.gguf" }
        val withoutTraversal = base.replace("..", "_")
        val cleaned = withoutTraversal.replace(Regex("[^A-Za-z0-9._-]"), "_").take(128)
        val withExt = if (cleaned.lowercase().endsWith(".gguf")) cleaned else "$cleaned.gguf"
        return withExt.ifBlank { "model.gguf" }
    }

    private fun fileHashPrefix(file: File, maxBytes: Long = 4 * 1024 * 1024): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read == -1) break
                digest.update(buf, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("unknown")

    private fun uniquifyGgufName(dir: File, name: String): File {
        val stem = name.replace(Regex("\\.gguf$"), "")
        var i = 2
        while (true) {
            val candidate = File(dir, "$stem-$i.gguf")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "model.gguf"

    fun importGguf(uri: Uri, onResult: (GgufImportResult?, error: String?) -> Unit) {
        if (_importing.value) {
            Handler(Looper.getMainLooper()).post { onResult(null, "Import already in progress") }
            return
        }
        _importing.value = true
        scope.launch {
            val result = runCatching {
                val name = sanitizeGgufName(displayName(uri))
                val dir = customDir.apply { mkdirs() }
                if (dir.usableSpace < 500 * 1024 * 1024) throw IllegalStateException("Not enough storage for import")
                val target = File(dir, name)
                if (target.exists() && target.length() > 1_000_000L) {
                    val targetHash = fileHashPrefix(target)
                    // Verify it's really the same file by comparing size + hash prefix before reusing.
                    val probeTmp = File(dir, "$name.probe.tmp")
                    var isSame = false
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            probeTmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var copied = 0L
                                while (copied < 4 * 1024 * 1024) {
                                    val r = input.read(buf)
                                    if (r == -1) break
                                    out.write(buf, 0, r)
                                    copied += r
                                }
                            }
                        }
                        if (probeTmp.exists() && probeTmp.length() > 0) {
                            val probeHash = fileHashPrefix(probeTmp)
                            val targetSize = target.length()
                            // Also check total size via openAssetFileDescriptor when available.
                            val totalSize = runCatching {
                                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                            }.getOrNull() ?: -1L
                            isSame = probeHash == targetHash && (totalSize == -1L || totalSize == targetSize)
                        }
                    } finally {
                        probeTmp.delete()
                    }
                    if (isSame) return@runCatching GgufImportResult(customModelFor(target), duplicate = true)
                }
                val tmp = File(dir, "$name.tmp")
                try {
                    var totalCopied = 0L
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r == -1) break
                                out.write(buf, 0, r)
                                totalCopied += r
                                if (totalCopied > 20L * 1024 * 1024 * 1024) throw IllegalStateException("File too large (>20GB)")
                            }
                        }
                    } ?: throw IllegalStateException("Cannot open the selected file")
                    if (tmp.length() < 1_000_000L) throw IllegalStateException("Not a valid GGUF (file too small)")
                    // tmp never carries the .gguf extension, so this always runs: the first 4 bytes must be "GGUF".
                    val magic = tmp.inputStream().use { it.readNBytes(4) }
                    if (magic.size == 4 && String(magic) != "GGUF") throw IllegalStateException("Not a GGUF file (bad magic)")
                    val final = if (target.exists()) uniquifyGgufName(dir, name) else target
                    if (!tmp.renameTo(final)) {
                        tmp.copyTo(final, overwrite = true)
                        tmp.delete()
                    }
                    GgufImportResult(customModelFor(final), duplicate = false)
                } finally {
                    // A partial copy must not survive: it is invisible to importedModels(), which only
                    // lists .gguf, yet it still counts against the 500MB import gate above.
                    if (tmp.exists()) tmp.delete()
                }
            }.onFailure { e ->
                logcat { "GGUF import failed: ${e.message}" }
            }
            _importing.value = false
            val ok = result.getOrNull()
            ok?.let { onImported(it.model) }
            Handler(Looper.getMainLooper()).post {
                onResult(ok, result.exceptionOrNull()?.message)
            }
        }
    }
}
