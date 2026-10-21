package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    // KMK -->
    private val mangaRepository: MangaRepository,
    // KMK <--
) {

    suspend fun await(manga: Manga) {
        withNonCancellableContext {
            setMangaChapterFlags.awaitSetAllFlags(
                mangaId = manga.id,
                unreadFilter = libraryPreferences.filterChapterByRead().get(),
                downloadedFilter = libraryPreferences.filterChapterByDownloaded().get(),
                bookmarkedFilter = libraryPreferences.filterChapterByBookmarked().get(),
                sortingMode = libraryPreferences.sortChapterBySourceOrNumber().get(),
                sortingDirection = libraryPreferences.sortChapterByAscendingOrDescending().get(),
                displayMode = libraryPreferences.displayChapterByNameOrNumber().get(),
            )
        }
    }

    // KMK -->
    /**
     * Applies the library defaults to every library entry. Keep this a single
     * statement: routing it through the per-manga path costs one transaction per
     * entry and loads the whole library, which is slow enough to look like a hang
     * and allocates enough to throw on large libraries.
     */
    suspend fun awaitAll() {
        withNonCancellableContext {
            mangaRepository.updateLibraryChapterFlags(
                setMangaChapterFlags.buildAllFlags(
                    unreadFilter = libraryPreferences.filterChapterByRead().get(),
                    downloadedFilter = libraryPreferences.filterChapterByDownloaded().get(),
                    bookmarkedFilter = libraryPreferences.filterChapterByBookmarked().get(),
                    sortingMode = libraryPreferences.sortChapterBySourceOrNumber().get(),
                    sortingDirection = libraryPreferences.sortChapterByAscendingOrDescending().get(),
                    displayMode = libraryPreferences.displayChapterByNameOrNumber().get(),
                ),
            )
        }
    }
    // KMK <--
}
