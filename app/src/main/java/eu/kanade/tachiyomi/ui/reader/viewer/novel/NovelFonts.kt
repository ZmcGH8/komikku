// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Base64
import java.util.Locale

/**
 * Fonts the reader brought themselves, kept in the app's own storage and offered next to the
 * built-in families.
 *
 * ### Why the files are copied rather than referenced
 *
 * A document in this reader is a string handed to the engine, and a `file://` reference in it is
 * worth nothing here: the reader's own hardening closes the file scheme, and a SAF permission
 * granted to a picker is not the document's to inherit. So the picked file is copied once into
 * private storage — the same trust boundary the book covers live behind — and the font reaches the
 * document the same way every other byte of it does: inside the markup, as a `data:` URI the
 * engine cannot refuse (see [NovelInline] for why that channel in particular).
 *
 * ### The family name
 *
 * A stored family value of `"user/My Font"` names the font file `My Font.ttf`: the prefix keeps
 * user fonts from colliding with CSS's generic keywords (`serif`, `monospace`…), and the whole
 * value is also the CSS family name, quoted by [NovelTypographyCss.quoteFamily] — a CSS family
 * name is an arbitrary string, so the prefix costs nothing and the `@font-face` declaration and
 * the `font-family` rule cannot disagree about what the chosen font is called.
 */
object NovelFonts {

    /** Marks a font family value as one of the reader's own files. */
    const val PREFIX = "user/"

    /** A font file larger than this is refused; the largest real font is a few megabytes. */
    const val MAX_FONT_BYTES = 16L * 1024 * 1024

    private fun directory(context: Context): File =
        File(context.filesDir, "novel-fonts").apply { mkdirs() }

    /**
     * The reader's fonts, as family values — [PREFIX] plus the file's name without its extension.
     */
    fun list(context: Context): List<String> =
        directory(context).listFiles()
            ?.filter { it.isFile }
            ?.map { PREFIX + it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    /**
     * Copies the picked [uri] in, named after the document it names.
     *
     * The name is sanitised rather than trusted: it came from another app, and it is about to
     * become both a file name and part of a CSS string. False — not an exception — for anything
     * the picker handed over that cannot be read within the ceiling: a failed import is a toast,
     * not a crash.
     */
    fun import(context: Context, uri: Uri): Boolean {
        val display = displayName(context, uri) ?: "font"
        val safe = display.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (safe.isBlank()) return false
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_FONT_BYTES) return false
        return try {
            File(directory(context), safe).writeBytes(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** One imported font: its bytes and the MIME type to declare them under. */
    data class Face(val bytes: ByteArray, val mimeType: String)

    /**
     * The font named by a family value, or null when it is gone or unreadable.
     *
     * The stored family value carries no extension — it is a name, not a file — so the file is
     * found by that name with or without its own extension, and the MIME type comes from whatever
     * extension the file kept.
     */
    fun load(context: Context, family: String): Face? {
        val name = family.removePrefix(PREFIX)
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        return try {
            val directory = directory(context)
            val file = File(directory, name).takeIf { it.isFile }
                ?: directory.listFiles()?.firstOrNull { it.nameWithoutExtension == name }
                ?: return null
            Face(
                bytes = file.readBytes(),
                mimeType = when (file.extension.lowercase(Locale.US)) {
                    "woff2" -> "font/woff2"
                    "woff" -> "font/woff"
                    "otf", "otc" -> "font/otf"
                    else -> "font/ttf"
                },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The `@font-face` that teaches the document the font [family] names.
     *
     * The family name is quoted — it is an arbitrary reader-chosen string, and CSS family names
     * in `@font-face` and in `font-family` must be spelled identically to find each other.
     */
    fun faceCss(family: String, face: Face): String {
        val encoded = Base64.getEncoder().encodeToString(face.bytes)
        return "@font-face { font-family: \"${family.replace("\"", "")}\"; " +
            "src: url(data:${face.mimeType};base64,$encoded); }"
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}
// KMK <--
