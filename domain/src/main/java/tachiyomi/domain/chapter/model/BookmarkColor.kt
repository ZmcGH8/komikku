package tachiyomi.domain.chapter.model

/**
 * KMK --> Why a chapter was bookmarked. Purely a local annotation: it is not
 * part of the sync version trigger, so recolouring never re-pushes the chapter.
 */
enum class BookmarkColor(val value: Int) {
    NONE(0),
    RED(1),
    ORANGE(2),
    YELLOW(3),
    GREEN(4),
    BLUE(5),
    PURPLE(6),
    ;

    companion object {
        fun fromValue(value: Long): BookmarkColor {
            return entries.firstOrNull { it.value == value.toInt() } ?: NONE
        }
    }
}
// KMK <--
