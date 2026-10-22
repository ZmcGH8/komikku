package eu.kanade.tachiyomi.source.online.all

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.copy
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import exh.source.isMergedSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mihon.app.di.globalAppGraph
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.MergedChapterDefaults
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.service.SourceManager

class MergedSource : HttpSource() {
    private val getManga: GetManga by lazy { globalAppGraph.getManga }
    private val getMergedReferencesById: GetMergedReferencesById by lazy { globalAppGraph.getMergedReferencesById }
    private val networkToLocalManga: NetworkToLocalManga by lazy { globalAppGraph.networkToLocalManga }
    private val updateMangaFromRemote: UpdateMangaFromRemote by lazy { globalAppGraph.updateMangaFromRemote }
    private val sourceManager: SourceManager by lazy { globalAppGraph.sourceManager }
    private val downloadManager: DownloadManager by lazy { globalAppGraph.downloadManager }
    private val filterChaptersForDownload: FilterChaptersForDownload by lazy { globalAppGraph.filterChaptersForDownload }

    // KMK --> Houri+
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId by lazy { globalAppGraph.getMergedChaptersByMangaId }
    private val updateChapter: UpdateChapter by lazy { globalAppGraph.updateChapter }
    private val getChaptersByMangaId: GetChaptersByMangaId by lazy { globalAppGraph.getChaptersByMangaId }
    private val updateManga by lazy { globalAppGraph.updateManga }
    // KMK <--

    override val id: Long = MERGED_SOURCE_ID

    override val baseUrl = ""

    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    override fun fetchChapterList(manga: SManga) = throw UnsupportedOperationException()
    override suspend fun getImage(page: Page): Response = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getImageUrl"))
    override fun fetchImageUrl(page: Page) = throw UnsupportedOperationException()
    override suspend fun getImageUrl(page: Page) = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter) = throw UnsupportedOperationException()
    override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int) = throw UnsupportedOperationException()
    override suspend fun getPopularManga(page: Int) = throw UnsupportedOperationException()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // KMK -->
        val asyncManga = if (fetchDetails) getMangaDetails(manga) else null
        return SMangaUpdate(asyncManga ?: manga, chapters)
        // KMK <--
    }

    private suspend fun getMangaDetails(manga: SManga): SManga {
        return withIOContext {
            val mergedManga = requireNotNull(getManga.await(manga.url, id)) { "merged manga not in db" }
            val mangaReferences = getMergedReferencesById.await(mergedManga.id)
                .apply {
                    require(isNotEmpty()) { "Manga references are empty, info unavailable, merge is likely corrupted" }
                    require(!(size == 1 && isMergedSourceId(first().mangaSourceId))) {
                        "Manga references contain only the merged reference, merge is likely corrupted"
                    }
                }

            val mangaInfoReference = mangaReferences.firstOrNull { it.isInfoManga }
                ?: mangaReferences.firstOrNull { it.mangaId != it.mergeId }
            val dbManga = mangaInfoReference?.run {
                getManga.await(mangaUrl, mangaSourceId)?.toSManga()
            }
            // KMK --> Houri+: take the status from the merged source that has reached the highest chapter,
            // so an official source's hiatus doesn't override a scan that is still releasing
            val infoManga = dbManga ?: mergedManga.toSManga()
            val furthestSource = mangaReferences
                .filter { !isMergedSourceId(it.mangaSourceId) }
                .mapNotNull { getManga.await(it.mangaUrl, it.mangaSourceId) }
                .maxByOrNull { child ->
                    getChaptersByMangaId.await(child.id).maxOfOrNull { it.chapterNumber } ?: -1.0
                }
            // Refresh that source's details so its status is current
            val statusFromFurthestSource = furthestSource?.let { child ->
                val refreshed = try {
                    updateMangaFromRemote(
                        source = sourceManager.getOrStub(child.source),
                        manga = child,
                        fetchDetails = true,
                    ).getOrThrow().manga
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                refreshed ?: child
            }
                ?.status
                ?.toInt()
            infoManga.copy(
                url = manga.url,
                status = statusFromFurthestSource ?: infoManga.status,
            )
            // KMK <--
        }
    }

    suspend fun fetchChaptersAndSync(manga: Manga, downloadChapters: Boolean = true): List<Chapter> {
        val mangaReferences = getMergedReferencesById.await(manga.id)
        require(mangaReferences.isNotEmpty()) {
            "Manga references are empty, chapters unavailable, merge is likely corrupted"
        }

        val semaphore = Semaphore(5)
        var exception: Exception? = null
        return supervisorScope {
            mangaReferences
                .groupBy(MergedMangaReference::mangaSourceId)
                .minus(MERGED_SOURCE_ID)
                .map { (_, values) ->
                    async {
                        semaphore.withPermit {
                            values.flatMap {
                                try {
                                    val (source, loadedManga, reference) = it.load()
                                    if (loadedManga != null && reference.getChapterUpdates) {
                                        val results = updateMangaFromRemote(
                                            source = source,
                                            manga = loadedManga,
                                            fetchChapters = true,
                                        ).getOrThrow().newChapters

                                        if (downloadChapters && reference.downloadChapters) {
                                            val chaptersToDownload = filterChaptersForDownload.await(manga, results)
                                            if (chaptersToDownload.isNotEmpty()) {
                                                downloadManager.downloadChapters(
                                                    loadedManga,
                                                    chaptersToDownload,
                                                )
                                            }
                                        }
                                        results
                                    } else {
                                        emptyList()
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    exception = e
                                    emptyList()
                                }
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }.also {
            // KMK --> Houri+: keep read chapters read when another source's copy takes their place
            if (MergedChapterDefaults.onePerChapter) {
                try {
                    markReadAcrossSources(manga.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            // KMK <--
            exception?.let { throw it }
        }
    }

    // KMK -->
    /**
     * Houri+: with one source per chapter, a chapter read on one source (e.g. the scan) is shown from another
     * once that one has it (e.g. the official release). Mark those copies read too so progress isn't lost.
     */
    private suspend fun markReadAcrossSources(mergeId: Long) {
        val chapters = getMergedChaptersByMangaId.await(mergeId, dedupe = false)
        val readNumbers = chapters.filter { it.read && it.isRecognizedNumber }.mapTo(HashSet()) { it.chapterNumber }
        val updates = chapters
            .filter { !it.read && it.isRecognizedNumber && it.chapterNumber in readNumbers }
            .map { ChapterUpdate(id = it.id, read = true) }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
    }
    // KMK <--

    /**
     * Houri+: read progress carried in by a backup. When a merge is rebuilt without the old library copy
     * (e.g. an aggregator), the backup stores the chapter numbers that were read in the merged entry's memo
     * ("1-154,156.5"). After a sync, chapters with those numbers are marked read; numbers that were found are
     * dropped and the key is removed once every number has turned up. [raw] is read by the caller before the
     * info source's details replace the memo.
     */
    suspend fun applyCarriedReadProgress(mergeId: Long, raw: String) {
        val head = getManga.await(mergeId) ?: return
        val wanted = parseChapterRanges(raw)
        val chapters = getMergedChaptersByMangaId.await(mergeId, dedupe = false)
        val updates = chapters
            .filter { !it.read && it.isRecognizedNumber && it.chapterNumber in wanted }
            .map { ChapterUpdate(id = it.id, read = true) }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
        val found = chapters.filter { it.isRecognizedNumber }.mapTo(HashSet()) { it.chapterNumber }
        val left = wanted - found
        val memo = buildJsonObject {
            head.memo.forEach { (key, value) -> if (key != CARRIED_READ_MEMO_KEY) put(key, value) }
            if (left.isNotEmpty()) put(CARRIED_READ_MEMO_KEY, JsonPrimitive(formatChapterRanges(left)))
        }
        updateManga.await(MangaUpdate(id = mergeId, memo = memo))
    }

    suspend fun MergedMangaReference.load(): LoadedMangaSource {
        var manga = getManga.await(mangaUrl, mangaSourceId)
        val source = sourceManager.getOrStub(manga?.source ?: mangaSourceId)
        if (manga == null) {
            val newManga = networkToLocalManga(
                Manga.create().copy(
                    source = mangaSourceId,
                    url = mangaUrl,
                ),
            )
            manga = updateMangaFromRemote(
                source = source,
                manga = newManga,
                fetchDetails = true,
            ).getOrThrow().manga
        }
        return LoadedMangaSource(source, manga, this)
    }

    data class LoadedMangaSource(val source: Source, val manga: Manga?, val reference: MergedMangaReference)

    override val lang = "all"
    override val supportsLatest = false
    override val name = "MergedSource"
}

// KMK --> Houri+: chapter numbers read before a merge was rebuilt from a backup
const val CARRIED_READ_MEMO_KEY = "houri.readChapters"

/** "1-3,5.5,8" -> {1, 2, 3, 5.5, 8}; ranges cover whole chapters only. */
internal fun parseChapterRanges(text: String): Set<Double> {
    val out = HashSet<Double>()
    for (part in text.split(',')) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val dash = p.indexOf('-', startIndex = 1)
        if (dash > 0) {
            val from = p.substring(0, dash).trim().toIntOrNull() ?: continue
            val to = p.substring(dash + 1).trim().toIntOrNull() ?: continue
            if (to - from > 20000) continue
            for (n in from..to) out.add(n.toDouble())
        } else {
            p.toDoubleOrNull()?.let(out::add)
        }
    }
    return out
}

internal fun formatChapterRanges(numbers: Set<Double>): String {
    val sorted = numbers.sorted()
    val parts = mutableListOf<String>()
    var i = 0
    while (i < sorted.size) {
        val start = sorted[i]
        if (start % 1.0 != 0.0) {
            parts.add(start.toString())
            i++
            continue
        }
        var end = start
        while (i + 1 < sorted.size && sorted[i + 1] == end + 1.0) {
            end = sorted[i + 1]
            i++
        }
        parts.add(if (end == start) start.toLong().toString() else "${start.toLong()}-${end.toLong()}")
        i++
    }
    return parts.joinToString(",")
}
// KMK <--
