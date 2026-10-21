package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ScanlatorPriority

/**
 * Returns a copy of the list with duplicate chapters removed
 */
fun List<Chapter>.removeDuplicates(
    currentChapter: Chapter,
    // KMK -->
    scanlatorPriority: List<String> = emptyList(),
    // KMK <--
): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                // KMK -->
                // Custom build: the user's preferred scanlator for this entry wins
                ?: chapters
                    .filter { ScanlatorPriority.rank(scanlatorPriority, it.scanlator) != null }
                    .minByOrNull { ScanlatorPriority.rank(scanlatorPriority, it.scanlator)!! }
                // KMK <--
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }
}
