package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import exh.EXHMigrations
import exh.source.isMergedSourceId
import mihon.app.di.globalAppGraph
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaMapper
import tachiyomi.data.manga.MergedMangaMapper
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class MangaRestorer(
    private var isSync: Boolean = false,

    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = globalAppGraph.getMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId = globalAppGraph.getChaptersByMangaId,
    private val updateManga: UpdateManga = globalAppGraph.updateManga,
    private val getTracks: GetTracks = globalAppGraph.getTracks,
    private val insertTrack: InsertTrack = globalAppGraph.insertTrack,
    fetchInterval: FetchInterval = globalAppGraph.fetchInterval,
    // SY -->
    private val setCustomMangaInfo: SetCustomMangaInfo = globalAppGraph.setCustomMangaInfo,
    private val insertFlatMetadata: InsertFlatMetadata = globalAppGraph.insertFlatMetadata,
    private val getFlatMetadataById: GetFlatMetadataById = globalAppGraph.getFlatMetadataById,
    // SY <--
) {
    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    /**
     * Read once per restore instead of once per manga. Nothing in this class writes
     * categories, so every entry of a restore sees the same list.
     */
    private var restoredCategories: List<Category> = emptyList()

    /**
     * `mergeUrl`/`mangaUrl`/`mangaSource` of every merged row already in the database, plus every
     * row this restore inserted. Backs the "is this reference already restored?"
     * check, which used to load the whole `merged` table per merged entry and then
     * scan it linearly per reference.
     *
     * KMK --> Houri+: the source is part of the key. A merge entry takes the url of the series it was
     * made from, so its own row and that series' row have the same `mergeUrl`/`mangaUrl`; keyed on the
     * urls alone, whichever came second was taken as already restored and dropped - a restored merge
     * lost its info source (the official release) and kept only the scans. KMK <--
     */
    private val knownMergedReferences = ConcurrentHashMap.newKeySet<Triple<String, String, Long>>()

    private val pendingEdits = ArrayList<CustomMangaInfo>()
    private val pendingEditsLock = Any()

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    /**
     * Loads the state that is identical for every entry of a restore.
     *
     * Call once before fanning out to [restore]. The restore does not add or remove
     * categories, and merged rows are added to [knownMergedReferences] as they are
     * inserted, so both stay valid for the whole run.
     */
    suspend fun prepare() {
        restoredCategories = getCategories.await()
        knownMergedReferences.clear()
        knownMergedReferences.addAll(
            handler.awaitList { mergedQueries.selectAll(MergedMangaMapper::map) }
                .map { Triple(it.mergeUrl, it.mangaUrl, it.mangaSourceId) },
        )
    }

    /**
     * Publishes the manga info edits buffered by [restore] in a single batch.
     *
     * One [tachiyomi.domain.manga.interactor.SetCustomMangaInfo] call per entry
     * copied the whole cache and queued its own transaction, which is quadratic
     * once a backup carries an edit for most of the library.
     */
    fun flushPendingEdits() {
        val batch = synchronized(pendingEditsLock) {
            if (pendingEdits.isEmpty()) return
            pendingEdits.toList().also { pendingEdits.clear() }
        }
        setCustomMangaInfo.setAll(batch)
    }

    /**
     * Throws away the edits buffered since the last flush.
     *
     * The buffer is written in its own transaction, so it survives a rolled-back restore of the
     * rows it describes. A caller that rolls a batch back and retries entry by entry calls this
     * first: the retry re-buffers the same edits, and publishing them twice would attach a title
     * to a manga row that does not exist.
     */
    fun discardPendingEdits() {
        synchronized(pendingEditsLock) { pendingEdits.clear() }
    }

    // KMK --> Houri+: "Replace library" (see RestoreOptions.replaceLibrary)
    /**
     * Drops the links of the merge entries at [mergeUrls] before the backup's merge entries are restored, so
     * they end up with exactly the backup's links. A restore otherwise only adds links, and a link it already
     * has keeps its old settings (which source gives the info, priority), so a rebuilt merge kept its old
     * sources. Only the link rows go: the merged entries and their chapters stay.
     */
    suspend fun clearMergeLinks(mergeUrls: Collection<String>) {
        if (mergeUrls.isEmpty()) return
        val urls = mergeUrls.toHashSet()
        handler.await(inTransaction = true) {
            urls.forEach { mergedQueries.deleteByMergeUrl(it) }
        }
        knownMergedReferences.removeIf { it.first in urls }
    }

    /**
     * Takes every library entry that the backup does not hold as a library entry out of the library, as
     * "Remove from library" does: the entry, its chapters, history and tracking stay in the database.
     * Returns how many were taken out.
     */
    suspend fun removeFromLibraryUnless(keep: Set<Pair<Long, String>>): Int {
        val favorites = handler.awaitList { mangasQueries.getFavorites(MangaMapper::mapManga) }
        val drop = favorites.filter { (it.source to it.url) !in keep }.toMutableList()
        // Two rows for one series (left by earlier restores) are one library entry twice: only the row a
        // restore resolves the series to - the one that just received the backup's data - stays.
        favorites.filter { (it.source to it.url) in keep }
            .groupBy { it.source to it.url }
            .filterValues { it.size > 1 }
            .forEach { (key, rows) ->
                val restored = getMangaByUrlAndSourceId.await(key.second, key.first)?.id ?: rows.first().id
                drop += rows.filter { it.id != restored }
            }
        if (drop.isNotEmpty()) {
            updateManga.awaitAll(drop.map { MangaUpdate(id = it.id, favorite = false, dateAdded = 0) })
        }
        return drop.size
    }
    // KMK <--

    /**
     * Returns which pass of the restore each entry belongs in, as of right now.
     *
     * Read once per restore. A restore wants entries the database already has *before* the ones it
     * does not, so an update lands on the existing row, and merged entries last of all; ordering a
     * decoded backup to achieve that meant holding every entry in memory to sort it, which is
     * exactly what the streaming entry pass exists to avoid. The matcher lets the caller order the
     * file instead.
     *
     * `internal` rather than public because [RestorePhase] is: the phase is this app's restore
     * pipeline's business, not a type anything outside it can act on.
     */
    internal suspend fun phaseMatcher(): (BackupManga) -> RestorePhase {
        val knownUrlsBySource = handler.awaitList { mangasQueries.getAllMangaSourceAndUrl() }
            .groupBy({ it.source }, { it.url })
            .mapValues { (_, urls) -> urls.toHashSet() }

        return { restorePhaseOf(it.source, it.url, knownUrlsBySource) }
    }

    /**
     * Restores [backupMangas] together, in one transaction: either every one lands or none does.
     *
     * ### Why a batch and not one transaction per entry
     *
     * Every `handler.await(inTransaction = true)` that is not already inside a transaction acquires
     * a thread from the database writer pool, opens a real SQLite transaction, and commits it -
     * and the nested calls inside it join that one, so the count is per *entry*, not per query. A
     * library of fifteen thousand series was therefore fifteen thousand commits, each with its
     * thread handoff, against a restore that does milliseconds of actual SQL per entry.
     *
     * Batching trades one thing away: a single bad entry now rolls back its whole batch. That is
     * why the caller retries a failed batch entry by entry, and this is the transaction that
     * makes both halves atomic.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
        // KMK --> backup category id -> restored database id, from CategoriesRestorer
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
    ) {
        handler.await(inTransaction = true) {
            backupMangas.forEach { restoreEntry(it, backupCategories, restoredCategoryIds) }
        }
    }

    /**
     * Restore a single manga.
     */
    private suspend fun Database.restoreEntry(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        restoredCategoryIds: Map<Long, Long>,
    ) {
        val dbManga = findExistingManga(backupManga)
        var manga = backupManga.getMangaImpl()
        // SY -->
        manga = EXHMigrations.migrateBackupEntry(manga)
        // SY <--
        val restoredManga = if (dbManga == null) {
            restoreNewManga(manga)
        } else {
            restoreExistingManga(manga, dbManga)
        }

        restoreMangaDetails(
            manga = restoredManga,
            chapters = backupManga.chapters,
            categories = backupManga.categories,
            // KMK -->
            categoryIds = backupManga.categoryIds,
            restoredCategoryIds = restoredCategoryIds,
            // KMK <--
            backupCategories = backupCategories,
            history = backupManga.history,
            tracks = backupManga.tracking,
            excludedScanlators = backupManga.excludedScanlators,
            // SY -->
            mergedMangaReferences = backupManga.mergedMangaReferences,
            flatMetadata = backupManga.flatMetadata,
            customManga = backupManga.getCustomMangaInfo(),
            // SY <--
        )

        if (isSync) {
            mangasQueries.resetIsSyncing()
            chaptersQueries.resetIsSyncing()
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id))
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id))
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            // SY -->
            ogAuthor = newer.author,
            ogArtist = newer.artist,
            ogDescription = newer.description,
            ogGenre = newer.genre,
            ogThumbnailUrl = newer.thumbnailUrl,
            ogStatus = newer.status,
            // SY <--
            initialized = this.initialized || newer.initialized,
            version = newer.version,
        )
    }

    suspend fun updateManga(manga: Manga): Manga {
        handler.await(true) {
            mangasQueries.update(
                source = manga.source,
                url = manga.url,
                // SY -->
                artist = manga.ogArtist,
                author = manga.ogAuthor,
                description = manga.ogDescription,
                genre = manga.ogGenre?.joinToString(separator = ", "),
                title = manga.ogTitle,
                status = manga.ogStatus,
                thumbnailUrl = manga.ogThumbnailUrl,
                // SY <--
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                // KMK -->
                // Preserve the backup's update schedule when it carries one; null
                // keeps the existing row values via coalesce (issue #12).
                nextUpdate = manga.nextUpdate.takeIf { it != 0L },
                calculateInterval = manga.fetchInterval.takeIf { it != 0 }?.toLong(),
                // KMK <--
                initialized = manga.initialized,
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                mangaId = manga.id,
                updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
                version = manga.version,
                isSyncing = 1,
                notes = manga.notes,
                memo = manga.memo.let(MemoColumnAdapter::encode),
                // KMK -->
                scanlatorPriority = manga.scanlatorPriority.let(StringListColumnAdapter::encode),
                blacklistedChapters = manga.blacklistedChapters.let(StringListColumnAdapter::encode),
                rereadCount = manga.rereadCount.toLong(),
                rereading = manga.rereading,
                rereadStartedAt = manga.rereadStartedAt,
                scanlatorRangeRules = manga.scanlatorRangeRules.let(StringListColumnAdapter::encode),
                isLightNovel = manga.isLightNovel,
                // KMK <--
            )
        }
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        val newManga = manga.copy(
            id = insertManga(manga),
        )
        // KMK -->
        // Persist columns that are not part of the insert statement (rereading support).
        // Skipped for the common case where they all hold defaults — saves one
        // write per imported manga, which dominates large-library import time.
        if (manga.rereadCount != 0 ||
            manga.rereading ||
            manga.rereadStartedAt != 0L ||
            manga.scanlatorPriority.isNotEmpty() ||
            manga.blacklistedChapters.isNotEmpty() ||
            manga.scanlatorRangeRules.isNotEmpty() ||
            manga.isLightNovel
        ) {
            updateManga(newManga)
        }
        // KMK <--
        return newManga
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull { backupChapter ->
                val chapter = backupChapter.toChapterImpl().copy(mangaId = manga.id)
                val dbChapter = dbChaptersByUrl[chapter.url]

                when {
                    dbChapter == null -> chapter // New chapter
                    chapter.forComparison() == dbChapter.forComparison() -> null // Same state; skip
                    else -> updateChapterBasedOnSyncState(chapter, dbChapter) // Update existed chapter
                }
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    private fun updateChapterBasedOnSyncState(chapter: Chapter, dbChapter: Chapter): Chapter {
        return if (isSync) {
            chapter.copy(
                id = dbChapter.id,
                bookmark = chapter.bookmark || dbChapter.bookmark,
                read = chapter.read,
                lastPageRead = chapter.lastPageRead,
                // KMK -->
                sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                // KMK <--
            )
        } else {
            chapter.copyFrom(dbChapter)
                // KMK -->
                .copy(
                    id = dbChapter.id,
                    bookmark = chapter.bookmark || dbChapter.bookmark,
                    sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                    dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                )
                // KMK <--
                .let {
                    when {
                        dbChapter.read && !it.read -> it.copy(read = true, lastPageRead = dbChapter.lastPageRead)
                        it.lastPageRead == 0L && dbChapter.lastPageRead != 0L -> it.copy(
                            lastPageRead = dbChapter.lastPageRead,
                        )
                        else -> it
                    }
                }
        }
    }

    private fun Chapter.forComparison() =
        this.copy(
            id = 0L,
            mangaId = 0L,
            dateFetch = 0L,
            // KMK -->
            // dateUpload = 0L, some time source loses dateUpload so we overwrite with backup
            // sourceOrder = 0L, although sourceOrder will be updated on refresh, we want to avoid order mixed up anyway
            // KMK <--
            lastModifiedAt = 0L,
            version = 0L,
        )

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    // KMK -->
                    chapter.bookmarkColor.value.toLong(),
                    // KMK <--
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.version,
                    chapter.memo,
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    dateFetch = null,
                    // KMK -->
                    sourceOrder = chapter.sourceOrder,
                    dateUpload = chapter.dateUpload,
                    // KMK <--
                    chapterId = chapter.id,
                    version = chapter.version,
                    isSyncing = 1,
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                    // KMK -->
                    bookmarkColor = chapter.bookmarkColor.value.toLong(),
                    // KMK <--
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = manga.source,
                url = manga.url,
                // SY -->
                artist = manga.ogArtist,
                author = manga.ogAuthor,
                description = manga.ogDescription,
                genre = manga.ogGenre,
                title = manga.ogTitle,
                status = manga.ogStatus,
                thumbnailUrl = manga.ogThumbnailUrl,
                // SY <--
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                // KMK -->
                // Preserve the backup's update schedule (0 for legacy backups = today's behavior).
                nextUpdate = manga.nextUpdate,
                calculateInterval = manga.fetchInterval.toLong(),
                // KMK <--
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                updateStrategy = manga.updateStrategy,
                version = manga.version,
                notes = manga.notes,
                memo = manga.memo,
            )
            mangasQueries.selectLastInsertedRowId()
        }
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        // KMK -->
        categoryIds: List<Long> = emptyList(),
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
        // SY -->
        mergedMangaReferences: List<BackupMergedMangaReference>,
        flatMetadata: BackupFlatMetadata?,
        customManga: CustomMangaInfo?,
        // SY <--
    ): Manga {
        restoreCategories(manga, categories, categoryIds, backupCategories, restoredCategoryIds)
        restoreChapters(manga, chapters)
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        // KMK -->
        // Trust the backup's update schedule when it carries one. Recalculating here
        // collapses every title's predicted date to today + interval (issue #12).
        if (manga.nextUpdate == 0L || manga.fetchInterval == 0) {
            updateManga.awaitUpdateFetchInterval(manga, now, currentFetchWindow)
        }
        // KMK <--
        // SY -->
        restoreMergedMangaReferencesForManga(manga.id, mergedMangaReferences)
        flatMetadata?.let { restoreFlatMetadata(manga.id, it) }
        restoreEditedInfo(customManga?.copy(id = manga.id))
        // SY <--

        return manga
    }

    /**
     * Restores the categories a manga is in.
     * Only if [backupCategories] is provided and user chooses to restore it.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        // KMK -->
        categoryIds: List<Long>,
        backupCategories: List<BackupCategory>,
        restoredCategoryIds: Map<Long, Long>,
        // KMK <--
    ) {
        // KMK --> categoryIds is the only unambiguous link: `sort` is numbered per parent, so
        // a root and its first child are both sort 0 and the order path below cannot tell
        // them apart. Pre-resolve those ids; older backups, and runs that did not restore
        // categories, resolve none and fall through to sort rather than dropping the manga's
        // categories on the floor.
        val resolvedIds = categoryIds.mapNotNull { restoredCategoryIds[it] }.distinct()
        val useIds = resolvedIds.isNotEmpty()
        val dbCategories = if (useIds) emptyList() else restoredCategories
        // KMK <--

        // KMK --> Same name can exist at two levels now, so a plain associateBy would silently
        // keep whichever came last. Prefer the restored root when a backup entry is a subcategory.
        val dbCategoriesByName = dbCategories
            .groupBy { it.name }
            .mapValues { (_, matches) -> matches.firstOrNull { it.parentId == 0L } ?: matches.first() }

        // KMK --> Order stays last: it is the pre-subcategory encoding, still needed for backups
        // written before categoryIds existed, and for runs that did not restore categories.
        val backupCategoriesByOrder = if (useIds) {
            emptyMap()
        } else {
            backupCategories.associateBy { it.order }
        }
        // KMK <--

        // KMK --> distinct() stops a legacy duplicate sort value inserting the same category twice.
        val mangaCategoriesToUpdate = if (useIds) {
            resolvedIds
        } else {
            categories.mapNotNull { backupCategoryOrder ->
                backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                    dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                        dbCategory.id
                    }
                }
            }.distinct()
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { categoryId ->
                    mangas_categoriesQueries.insert(manga.id, categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        if (backupHistory.isEmpty()) return

        // Two queries for the whole manga rather than two per history row. Every row
        // needs its chapter's id, and the chapter list is read after [restoreChapters]
        // so it also covers the chapters this restore just inserted.
        val chapterIdsByUrl = getChaptersByMangaId.await(manga.id).associate { it.url to it.id }
        if (chapterIdsByUrl.isEmpty()) return
        val dbHistoryByChapterId = handler.awaitList { historyQueries.getHistoryByMangaId(manga.id) }
            .associateBy { it.chapter_id }

        val toUpdate = backupHistory.mapNotNull { history ->
            // KMK -->
            val chapterId = chapterIdsByUrl[history.url]
                ?: return@mapNotNull null // Chapter doesn't exist; skip
            val dbHistory = dbHistoryByChapterId[chapterId]
            // KMK <--
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                // New history entry
                item.copy(chapterId = chapterId)
            } else {
                // Update history entry
                item.copy(
                    id = dbHistory._id,
                    chapterId = dbHistory.chapter_id,
                    readAt = max(item.readAt?.time ?: 0L, dbHistory.last_read?.time ?: 0L)
                        .takeIf { it > 0L }
                        ?.let { Date(it) },
                    readDuration = max(item.readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
            }
        }

        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach {
                    historyQueries.upsert(
                        it.chapterId,
                        it.readAt,
                        it.readDuration,
                    )
                }
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                    // KMK -->
                    rereadCount = max(dbTrack.rereadCount, track.rereadCount),
                    // KMK <--
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }
        if (existingTracks.isNotEmpty()) {
            handler.await(true) {
                existingTracks.forEach { track ->
                    manga_syncQueries.update(
                        track.mangaId,
                        track.trackerId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastChapterRead,
                        track.totalChapters,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                        track.private,
                        // KMK -->
                        track.rereadCount.toLong(),
                        // KMK <--
                        track.id,
                    )
                }
            }
        }
    }

    // SY -->
    /**
     * Restore the categories from Json
     *
     * @param mergeMangaId the merge manga for the references
     * @param backupMergedMangaReferences the list of backup manga references for the merged manga
     */
    private suspend fun restoreMergedMangaReferencesForManga(
        mergeMangaId: Long,
        backupMergedMangaReferences: List<BackupMergedMangaReference>,
    ) {
        if (backupMergedMangaReferences.isEmpty()) return

        backupMergedMangaReferences
            // KMK -->
            .map { EXHMigrations.migrateBackupMergedMangaReference(it) }
            // KMK <--
            .forEach { backupMergedMangaReference ->
                // If the backupMergedMangaReference isn't in the db,
                // remove the id and insert a new backupMergedMangaReference
                // Store the inserted id in the backupMergedMangaReference
                val refKey = Triple(
                    backupMergedMangaReference.mergeUrl,
                    backupMergedMangaReference.mangaUrl,
                    backupMergedMangaReference.mangaSourceId,
                )
                if (!knownMergedReferences.contains(refKey)) {
                    // Let the db assign the id
                    // KMK -->
                    val mergedManga = handler.awaitList {
                        // KMK <--
                        mangasQueries.getMangaByUrlAndSource(
                            backupMergedMangaReference.mangaUrl,
                            backupMergedMangaReference.mangaSourceId,
                            MangaMapper::mapManga,
                        )
                        // KMK -->
                    }.firstOrNull()
                        // KMK <--
                        ?: return@forEach
                    backupMergedMangaReference.getMergedMangaReference().run {
                        handler.await {
                            mergedQueries.insert(
                                infoManga = isInfoManga,
                                getChapterUpdates = getChapterUpdates,
                                chapterSortMode = chapterSortMode.toLong(),
                                chapterPriority = chapterPriority.toLong(),
                                downloadChapters = downloadChapters,
                                mergeId = mergeMangaId,
                                mergeUrl = mergeUrl,
                                mangaId = mergedManga.id,
                                mangaUrl = mangaUrl,
                                mangaSource = mangaSourceId,
                            )
                        }
                    }
                    knownMergedReferences.add(refKey)
                }
            }
    }

    private suspend fun restoreFlatMetadata(mangaId: Long, backupFlatMetadata: BackupFlatMetadata) {
        if (getFlatMetadataById.await(mangaId) == null) {
            insertFlatMetadata.await(backupFlatMetadata.getFlatMetadata(mangaId))
        }
    }

    private fun restoreEditedInfo(mangaJson: CustomMangaInfo?) {
        mangaJson ?: return
        synchronized(pendingEditsLock) { pendingEdits += mangaJson }
    }

    private fun BackupManga.getCustomMangaInfo(): CustomMangaInfo? {
        if (customTitle != null ||
            customArtist != null ||
            customAuthor != null ||
            customThumbnailUrl != null ||
            customDescription != null ||
            customGenre != null ||
            customStatus != 0
        ) {
            return CustomMangaInfo(
                id = 0L,
                title = customTitle,
                author = customAuthor,
                artist = customArtist,
                thumbnailUrl = customThumbnailUrl,
                description = customDescription,
                genre = customGenre,
                status = customStatus.takeUnless { it == 0 }?.toLong(),
            )
        }
        return null
    }
    // SY <--

    private fun Track.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = handler.awaitList {
            excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(manga.id)
            // KMK -->
        }.toSet()
        val toInsert = excludedScanlators.toSet().subtract(existingExcludedScanlators)
        if (toInsert.isNotEmpty()) {
            handler.await(inTransaction = true) {
                // KMK <--
                toInsert.forEach {
                    excluded_scanlatorsQueries.insert(manga.id, it)
                }
            }
        }
    }
}

/**
 * Which pass of a restore an entry belongs to. The passes run in declaration order.
 *
 * This is the ordering policy, expressed as a classification rather than a `Comparator`, because
 * the restore no longer holds the entries to sort: it streams them out of the backup file one at a
 * time and walks the file more than once, so "restore these first" has to be answered per entry
 * instead of by sorting a list that was never materialised.
 */
internal enum class RestorePhase {
    /** Entries the database already has, so an update lands on the existing row. */
    EXISTING,

    /** Entries it does not, which the restore inserts. */
    NEW,

    /** Merged entries, which are resolved against the real entries that precede them. */
    MERGED,
}

/**
 * The pass [source]/[url] is restored in.
 *
 * [knownUrlsBySource] must hold sets: a per-entry lookup against a larger collection is what made
 * ordering a library-sized backup expensive, and the lookup now happens once per entry rather than
 * once per comparison.
 */
internal fun restorePhaseOf(
    source: Long,
    url: String,
    knownUrlsBySource: Map<Long, Set<String>>,
): RestorePhase = when {
    // KMK -->
    isMergedSourceId(source) -> RestorePhase.MERGED
    // KMK <--
    // Known is checked against the entry's *own* source: the same url under two sources is two
    // different series, so a hit elsewhere in the map means nothing.
    knownUrlsBySource[source]?.contains(url) == true -> RestorePhase.EXISTING
    else -> RestorePhase.NEW
}
