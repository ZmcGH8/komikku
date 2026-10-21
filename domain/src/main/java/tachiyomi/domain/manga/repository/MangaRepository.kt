package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface MangaRepository {

    suspend fun getMangaById(id: Long): Manga

    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    /**
     * How many series are in the library, without building them.
     *
     * [getLibraryManga] runs the full `libraryView` query - chapter counts, category aggregation
     * and all - and allocates a [LibraryManga] per series. That is the right call to *read* the
     * library and the wrong one to answer "how many are there", which is what the restore asked
     * after writing thousands of entries.
     */
    suspend fun getLibraryCount(): Long

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    // KMK -->
    /** Overwrites [chapterFlags] on every library entry in one statement. */
    suspend fun updateLibraryChapterFlags(chapterFlags: Long): Boolean
    // KMK <--

    suspend fun insertNetworkManga(
        manga: List<Manga>,
        // KMK -->
        updateInfo: Boolean = true,
        // KMK <--
    ): List<Manga>

    // SY -->
    suspend fun getMangaBySourceId(sourceId: Long): List<Manga>

    suspend fun getAll(): List<Manga>

    suspend fun deleteManga(mangaId: Long)

    suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean)

    suspend fun getReadMangaNotInLibraryView(): List<LibraryManga>
    // SY <--
}
