package tachiyomi.domain.library.model

// KMK -->
private val CREDIT_LINE = Regex("""^\s*[-*•]\s*([^:]{1,40}):\s*(.+)$""")
private val NAME_SEPARATORS = Regex("""\s*(?:[,;/&]|\band\b)\s*(?![^()]*\))""", RegexOption.IGNORE_CASE)

/**
 * Custom build: values of the role lines in a description's "Credits" list whose role matches [role]
 * (e.g. "- Studio: REDICE Studio", "- Studios: Studio LICO, Force Studio"), split into names.
 */
fun creditsFromDescription(description: String?, role: (String) -> Boolean): List<String> {
    if (description.isNullOrEmpty()) return emptyList()
    val lines = description.lines()
    val start = lines.indexOfFirst { it.contains("Credits", ignoreCase = true) && it.trim().endsWith(":") }
    if (start == -1) return emptyList()
    val names = mutableListOf<String>()
    for (line in lines.drop(start + 1)) {
        if (line.isBlank()) break
        val match = CREDIT_LINE.find(line) ?: break
        val label = match.groupValues[1].trim()
        if (!role(label)) continue
        names += match.groupValues[2].split(NAME_SEPARATORS)
    }
    return names
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        .distinctBy { it.lowercase() }
}

/** Custom build: studios credited in the description. */
fun studiosFromDescription(description: String?): List<String> =
    creditsFromDescription(description) { it.lowercase().startsWith("studio") }

/** Custom build: artists credited in the description ("Art", "Artist", "Writer & Art", ...). */
fun artistsFromDescription(description: String?): List<String> =
    creditsFromDescription(description) { label ->
        label.lowercase().split(Regex("""\s*&\s*|\s+""")).any { it == "art" || it.startsWith("artist") }
    }
// KMK <--
