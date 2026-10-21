package tachiyomi.source.local.io

/**
 * The folder name a set of imported books is filed under.
 *
 * ### Why this is its own object
 *
 * The name arrives from a text field and is about to become a path segment, and the two things it
 * can be are not the same size. `..` is a folder name in a text field and a way out of the local
 * directory on disk; a name of `/` is not a folder at all. Both arrive the same way — typed, pasted
 * from a file manager, or dragged out of another app's share sheet — so the rules are stated once
 * here rather than at each place a name is accepted.
 *
 * Pure, with no platform types, so it can be asserted off-device.
 */
object LocalBookFolder {

    /**
     * Long enough for "Youngest Son is a Warlock, Vol. 1-3 (Complete)", short enough that a
     * filesystem limit is never the thing that fails the import.
     */
    const val MAX_LENGTH = 64

    /**
     * A folder name safe to create, or null when nothing usable is left of [raw].
     *
     * Separators are replaced rather than removed so `Fantasy / Epic` becomes `Fantasy  Epic`
     * rather than `Fantasy  Epic` with a hole in it — a name the user typed should survive being
     * made safe as far as it can be.
     */
    fun sanitize(raw: String): String? {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c == '/' || c == '\\' || c == ':' -> append(' ')
                    c.code < 0x20 || c.code == 0x7F -> Unit // control characters: never in a name
                    else -> append(c)
                }
            }
        }.trim()
            .replace(REPEATED_SPACES, " ")
            .trim()

        if (cleaned.isEmpty()) return null
        // `.` and `..` are the only names on any supported filesystem that do not name a folder of
        // their own; checked after cleaning, because trimming a dot off is not what makes it safe.
        if (cleaned == "." || cleaned == "..") return null
        return cleaned.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    private val REPEATED_SPACES = Regex("""\s{2,}""")
}
