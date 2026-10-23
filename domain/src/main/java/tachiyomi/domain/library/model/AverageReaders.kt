package tachiyomi.domain.library.model

// KMK -->
private val AVERAGE_READERS = Regex("""avg\.?\s+readers\W*([\d.,]+)\s*([kmb])?""", RegexOption.IGNORE_CASE)

/**
 * Custom build: the "Avg Readers" value written in an entry's description
 * (e.g. "**Avg Readers**: 36.1K per chapter" -> 36100.0), or null when the description has none.
 */
fun averageReadersFromDescription(description: String?): Double? {
    if (description.isNullOrEmpty()) return null
    val match = AVERAGE_READERS.find(description) ?: return null
    val number = match.groupValues[1].replace(",", "").trimEnd('.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "k" -> 1_000.0
        "m" -> 1_000_000.0
        "b" -> 1_000_000_000.0
        else -> 1.0
    }
    return number * multiplier
}

private val AVERAGE_RATING = Regex("""avg\.?\s+rating\W*([\d.,]+)\s*(?:/\s*([\d.,]+))?""", RegexOption.IGNORE_CASE)

/**
 * Custom build: the "Avg Rating" written in an entry's description, on a 10-point scale
 * (e.g. "**Avg Rating**: 7.84/10" -> 7.84, "4.2/5" -> 8.4), or null when the description has none.
 */
fun averageRatingFromDescription(description: String?): Double? {
    if (description.isNullOrEmpty()) return null
    val match = AVERAGE_RATING.find(description) ?: return null
    val value = match.groupValues[1].replace(",", ".").trimEnd('.').toDoubleOrNull() ?: return null
    val scale = match.groupValues[2].replace(",", ".").trimEnd('.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
    return value * 10.0 / scale
}
// KMK <--
