package mihon.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.CARRIED_READ_MEMO_KEY
import eu.kanade.tachiyomi.source.online.all.READ_UP_TO_MEMO_KEY
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.source.online.all.formatChapterRanges
import eu.kanade.tachiyomi.source.online.all.parseChapterRanges
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.time.Instant

@Inject
class UpdateMangaFromRemote(
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val coverCache: CoverCache,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
) {
    /**
     * @param manualFetch Default: `false`. If `true`, it will update cover's last modified date & fetchInterval.
     * It will also mean `downloadChapters = true` if source is [MergedSource].
     *
     * If neither [fetchDetails] nor [fetchChapters] is `true`, it will only update cover's last modified date & fetchInterval,
     * and also perform renaming downloaded folders.
     */
    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        // SY -->
        throttleFunc: suspend () -> Unit = {},
        // SY <--
    ): Result<RemoteMangaUpdate> {
        val source = sourceManager.getOrStub(manga.source)
        return invoke(
            source = source,
            manga = manga,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            manualFetch = manualFetch,
            // KMK -->
            fetchWindow = fetchWindow,
            // KMK <--
            // SY -->
            throttleFunc = throttleFunc,
            // SY <--
        )
    }

    /**
     * @param source Manually specify a source instead of getting it from `manga.source`.
     *
     * @param manualFetch Default: `false`. If `true`, it will update cover's last modified date & fetchInterval.
     * It will also mean `downloadChapters = true` if source is [MergedSource].
     *
     * If neither [fetchDetails] nor [fetchChapters] is `true`, it will only update cover's last modified date & fetchInterval,
     * and also perform renaming downloaded folders.
     */
    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        // SY -->
        throttleFunc: suspend () -> Unit = {},
        // SY <--
    ): Result<RemoteMangaUpdate> {
        return try {
            // KMK --> Houri+: read before the source's details overwrite the memo
            val carriedRead = manga.memo[CARRIED_READ_MEMO_KEY]?.jsonPrimitive?.contentOrNull
            val readUpTo = manga.memo[READ_UP_TO_MEMO_KEY]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            // KMK <--
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
                .sortedBy { it.sourceOrder }
            val update = withIOContext {
                // SY -->
                if (source is EHentai) {
                    source.getMangaUpdate(
                        manga = manga.toSManga(),
                        chapters = chapters.map(Chapter::toSChapter),
                        fetchDetails = fetchDetails,
                        fetchChapters = fetchChapters,
                        throttleFunc = throttleFunc,
                    )
                } else {
                    // SY <--
                    source.getMangaUpdate(
                        manga = manga.toSManga(),
                        chapters = chapters.map(Chapter::toSChapter),
                        fetchDetails = fetchDetails,
                        fetchChapters = fetchChapters,
                    )
                }
            }
            // KMK -->
            val updateResult = withIOContext {
                // KMK <--
                awaitUpdateFromSource(manga, update.manga, manualFetch)
                // SY -->
                val newChapters = if (source is MergedSource) {
                    source.fetchChaptersAndSync(manga, downloadChapters = manualFetch)
                } else {
                    // SY <--
                    syncChaptersWithSource.await(
                        rawSourceChapters = update.chapters,
                        manga = manga,
                        source = source,
                        manualFetch = manualFetch,
                        fetchWindow = fetchWindow,
                    )
                }
                // KMK --> Houri+: read progress carried in by a backup (entry migrated to another source)
                if (fetchChapters && carriedRead != null) {
                    try {
                        if (source is MergedSource) {
                            source.applyCarriedReadProgress(manga.id, carriedRead)
                        } else {
                            applyCarriedReadProgress(manga.id, carriedRead)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e)
                    }
                }
                if (fetchChapters && readUpTo != null) {
                    try {
                        if (source is MergedSource) {
                            source.applyReadUpTo(manga.id, readUpTo)
                        } else {
                            applyReadUpTo(manga.id, readUpTo)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e)
                    }
                }
                // KMK <--
                val updatedManga = mangaRepository.getMangaById(manga.id)
                // KMK -->
                RemoteMangaUpdate(manga = updatedManga, newChapters = newChapters)
            }
            Result.success(updateResult)
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    // KMK -->
    /**
     * Houri+: a backup can move an entry to another source (e.g. from an aggregator to the scan group) and store the
     * chapter numbers that were read in the entry's memo ("1-154,156.5"). Once the new source's chapters are in,
     * mark those read; numbers that were found are dropped and the key goes away when all have turned up.
     */
    /** Houri+: exact read progress from a backup - see [READ_UP_TO_MEMO_KEY]. */
    private suspend fun applyReadUpTo(mangaId: Long, upTo: Double) {
        val chapters = chapterRepository.getChapterByMangaId(mangaId).filter { it.isRecognizedNumber }
        if (chapters.isEmpty()) return
        val updates = chapters.mapNotNull {
            val read = it.chapterNumber <= upTo + 1e-9
            when {
                read && !it.read -> ChapterUpdate(id = it.id, read = true)
                !read && it.read -> ChapterUpdate(id = it.id, read = false, lastPageRead = 0)
                else -> null
            }
        }
        if (updates.isNotEmpty()) chapterRepository.updateAll(updates)
        val current = mangaRepository.getMangaById(mangaId)
        val memo = buildJsonObject { current.memo.forEach { (key, value) -> if (key != READ_UP_TO_MEMO_KEY) put(key, value) } }
        mangaRepository.update(MangaUpdate(id = mangaId, memo = memo))
    }

    private suspend fun applyCarriedReadProgress(mangaId: Long, raw: String) {
        val current = mangaRepository.getMangaById(mangaId)
        val wanted = parseChapterRanges(raw)
        val chapters = chapterRepository.getChapterByMangaId(mangaId)
        val updates = chapters
            .filter { !it.read && it.isRecognizedNumber && it.chapterNumber in wanted }
            .map { ChapterUpdate(id = it.id, read = true) }
        if (updates.isNotEmpty()) chapterRepository.updateAll(updates)
        val found = chapters.filter { it.isRecognizedNumber }.mapTo(HashSet()) { it.chapterNumber }
        val left = wanted - found
        val memo = buildJsonObject {
            current.memo.forEach { (key, value) -> if (key != CARRIED_READ_MEMO_KEY) put(key, value) }
            if (left.isNotEmpty()) put(CARRIED_READ_MEMO_KEY, JsonPrimitive(formatChapterRanges(left)))
        }
        mangaRepository.update(MangaUpdate(id = mangaId, memo = memo))
    }
    // KMK <--

    private suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite (or 'update titles' preference is enabled), set its title from source and update in db
        val title =
            if (remoteTitle.isNotEmpty() && (!localManga.favorite || libraryPreferences.updateMangaTitles().get())) {
                remoteTitle
            } else {
                null
            }

        val coverLastModified = when {
            // Never refresh covers if the url is empty to avoid "losing" existing covers
            remoteManga.thumbnail_url.isNullOrEmpty() -> null
            !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
            localManga.isLocal() -> Instant.now().toEpochMilli()
            // Not in the library, so there is no fetch that will put the cover back: deleting it
            // here simply drops a cover the retention window is holding, and the reader is told a
            // new one exists while browsing a title that is only being kept around on disk. This
            // interactor is reachable for a removed manga - the library refresh it started before
            // the removal lands, or an update of a details screen still open.
            !localManga.favorite -> null
            localManga.hasCustomCover(coverCache) -> {
                coverCache.deleteFromCache(localManga, false)
                null
            }
            else -> {
                coverCache.deleteFromCache(localManga, false)
                Instant.now().toEpochMilli()
            }
        }

        val thumbnailUrl = remoteManga.thumbnail_url?.takeIf { it.isNotEmpty() }

        val success = mangaRepository.update(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author,
                artist = remoteManga.artist,
                description = remoteManga.description,
                genre = remoteManga.getGenres(),
                thumbnailUrl = thumbnailUrl,
                status = remoteManga.status.toLong(),
                updateStrategy = remoteManga.update_strategy,
                initialized = true,
                memo = remoteManga.memo,
            ),
        )
        if (success && title != null) {
            downloadManager.renameManga(localManga, title)
        }
        return success
    }
}
