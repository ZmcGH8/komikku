package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.EntryType
import eu.kanade.tachiyomi.source.model.EntryTypeSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MixedContentSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.text.EpubArchiveSource
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // SY -->
    private val allowHiddenFiles: () -> Boolean,
    // SY <--
) : Source,
    UnmeteredSource,
    TextSource,
    TextTocSource,
    TextIncrementalSource,
    TextResourceSource,
    TextBaseUrlSource,
    EpubArchiveSource,
    MixedContentSource,
    EntryTypeSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()

    /**
     * The book half of this source.
     *
     * Constructed lazily because it owns the archive leases and nothing should open one until a book
     * is actually read — which, for most users of the manga library, is never.
     */
    private val books: LocalEpubTextAdapter by lazy { LocalEpubTextAdapter(context, fileSystem) }

    override val textCapabilities: TextCapabilities = TextCapabilities.EPUB

    // ---------------------------------------------------------------- the book half
    //
    // Declared here and not on the adapter because `TextSource` extends `Source`: the host needs one
    // object that is both a source and text-capable, and it must also be a `MixedContentSource` so the
    // loader knows the source-level answer is not the whole story. Splitting it across two objects
    // would make that impossible to express.

    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> = books.textPages(chapter)

    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> = books.tableOfContents(chapter)

    // Documents are read one at a time, so opening a novel is bounded by the document being opened
    // rather than by the length of the book. Declared here for the same reason as the rest of this
    // section: the host asks one object, and that object is the source.
    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> =
        books.textPageIndex(chapter)

    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? =
        books.textPageText(chapter, page)

    override fun baseUrlFor(page: TextPage): String = books.baseUrlFor(page)

    override fun openResource(absoluteUrl: String): InputStream? = books.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String): String? = books.contentTypeFor(absoluteUrl)

    override fun releaseResourceReader(chapterUrl: String) = books.releaseResourceReader(chapterUrl)

    override fun releaseAllResourceReaders() = books.releaseAllResourceReaders()

    /**
     * Whether [manga] is a novel, asked before anyone has said.
     *
     * Answers BOOK only for a folder whose chapters are *all* EPUB: a mixed folder is a comic with a
     * stray file in it, and calling that a book would hand the reader a prose view of something that
     * is mostly pictures.
     */
    override fun entryTypeOf(manga: SManga): EntryType? = books.entryTypeOf(manga)

    /**
     * Reads the spine to decide.
     *
     * The last word, and the only signal that actually looks at the content: a title page and a
     * copyright leaf cannot be told from prose by shape, nor from an art book's plates — only by how
     * much text there is across the whole book.
     */
    override fun detectBookEntry(manga: SManga, chapter: SChapter): Boolean =
        books.detectBookEntry(chapter)

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        // SY -->
        val allowLocalSourceHiddenFolders = allowHiddenFiles()
        // SY <--

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // A folder of chapters, or a book that is a single archive with no folder around it.
            // The second shape was listed by nothing, so a novel dropped straight into the base
            // directory was simply invisible here.
            .filter {
                (it.isDirectory || Archive.isSupportedSingleFile(it)) &&
                    // SY -->
                    (
                        !it.name.orEmpty().startsWith('.') ||
                            allowLocalSourceHiddenFolders
                        )
                // SY <--
            }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        // `url` keeps the extension: it is what the folder/file is resolved by, and
                        // a series' folder and its loose book file are different entries that would
                        // otherwise collide. Only the display title drops it.
                        title = if (mangaDir.isDirectory) {
                            mangaDir.name.orEmpty()
                        } else {
                            mangaDir.nameWithoutExtension.orEmpty()
                        }
                        url = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(mangaDir.name.orEmpty())?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        MangasPage(mangas, false)
    }

    // SY -->
    fun updateMangaInfo(manga: SManga) {
        val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)
        val existingFile = mangaDirFiles
            .firstOrNull { it.name == COMIC_INFO_FILE }
        val comicInfoArchiveFile = mangaDirFiles.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        val comicInfoArchiveReader = comicInfoArchiveFile?.archiveReader(context)
        val existingComicInfo =
            (existingFile?.openInputStream() ?: comicInfoArchiveReader?.getInputStream(COMIC_INFO_FILE))?.use {
                AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                    xml.decodeFromReader<ComicInfo>(xmlReader)
                }
            }
        val newComicInfo = if (existingComicInfo != null) {
            manga.run {
                existingComicInfo.copy(
                    series = ComicInfo.Series(title),
                    summary = description?.let { ComicInfo.Summary(it) },
                    writer = author?.let { ComicInfo.Writer(it) },
                    penciller = artist?.let { ComicInfo.Penciller(it) },
                    genre = genre?.let { ComicInfo.Genre(it) },
                    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
                    ),
                )
            }
        } else {
            manga.getComicInfo()
        }

        fileSystem.getMangaDirectory(manga.url)?.let {
            copyComicInfoFile(
                xml.encodeToString(ComicInfo.serializer(), newComicInfo).byteInputStream(),
                it,
                comicInfoArchiveReader?.encrypted ?: false,
            )
        }
    }
    // SY <--

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir = fileSystem.getMangaDirectory(manga.url)
            if (mangaDir == null) {
                // An entry that is a single book file has no folder, so there is no ComicInfo.xml
                // beside it to read - and writing one would mean writing into the base directory,
                // where it would then describe every other entry in it as well. The chapter pass
                // fills title and author from the book's own metadata instead.
                return@withIOContext manga
            }
            val mangaDirFiles = mangaDir.listFiles().orEmpty()

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }
            // SY -->
            val comicInfoArchiveFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            // SY <--

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }
                // SY -->
                comicInfoArchiveFile != null -> {
                    noXmlFile?.delete()

                    comicInfoArchiveFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                        ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                }

                // SY <--

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val chapterArchives = mangaDirFiles.filter(Archive::isSupported)

                    val copiedFile = copyComicInfoFileFromChapters(chapterArchives, mangaDir)

                    // SY -->
                    if (copiedFile != null && copiedFile.name != COMIC_INFO_ARCHIVE) {
                        setMangaDetailsFromComicInfoFile(copiedFile.openInputStream(), manga)
                    } else if (copiedFile != null && copiedFile.name == COMIC_INFO_ARCHIVE) {
                        copiedFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                            ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                    } // SY <--
                    else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream, ArchiveReader?) -> T): T? {
        if (chapter.isDirectory) {
            return chapter.findFile(COMIC_INFO_FILE)?.let { file ->
                file.openInputStream().use { block(it, /* SY --> */ null /* SY <-- */) }
            }
        } else {
            return chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use { block(it, /* SY --> */ reader /* SY <-- */) }
            }
        }
    }

    private fun copyComicInfoFileFromChapters(chapterArchives: List<UniFile>, folder: UniFile): UniFile? {
        for (chapter in chapterArchives) {
            val file = getComicInfoForChapter(chapter) f@{ stream, /* SY --> */ reader /* SY <-- */ ->
                return@f copyComicInfoFile(stream, folder, /* SY --> */ reader?.encrypted == true /* SY <-- */)
            }
            if (file != null) return file
        }
        return null
    }

    private fun copyComicInfoFile(
        comicInfoFileStream: InputStream,
        folder: UniFile,
        // SY -->
        encrypt: Boolean,
        // SY <--
    ): UniFile? {
        // SY -->
        if (encrypt) {
            val comicInfoArchiveFile = folder.createFile(COMIC_INFO_ARCHIVE)
            comicInfoArchiveFile?.let { archive ->
                ZipWriter(context, archive, encrypt = true).use { writer ->
                    writer.write(comicInfoFileStream.use { it.readBytes() }, COMIC_INFO_FILE)
                }
            }
            return comicInfoArchiveFile
        } else {
            // SY <--
            return folder.createFile(COMIC_INFO_FILE)?.apply {
                openOutputStream().use { outputStream ->
                    comicInfoFileStream.use { it.copyTo(outputStream) }
                }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }
    }

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val chapters = fileSystem.getFilesInMangaDirectory(manga.url)
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { it.isDirectory || Archive.isSupportedSingleFile(it) }
            .map { chapterFile ->
                // The file name is sort input, not display: `fillMetadata` replaces the chapter
                // name with the book's own title, and from then on the file name exists only as
                // the comparator's fallback below.
                val fileName = (
                    if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }
                    ).orEmpty()
                val chapter = SChapter.create().apply {
                    url = "${manga.url}/${chapterFile.name}"
                    name = fileName
                    date_upload = chapterFile.lastModified()
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()

                    val format = Format.valueOf(chapterFile)
                    if (format is Format.Epub) {
                        format.file.epubReader(context).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    } else {
                        getComicInfoForChapter(chapterFile) { stream, /* SY --> */ _ /* SY <-- */ ->
                            setChapterDetailsFromComicInfoFile(stream, this)
                        }
                    }
                }
                fileName to chapter
            }
            // KMK --> The parsed volume number — recognised out of the file name above and kept
            // untouched by the syncer afterward — is the reading order for a numbered series. This
            // list used to be sorted by chapter *name*, which the EPUB pass above has already
            // replaced with each book's own dc:title: an alphabetical order of titles, which set a
            // series' volumes down out of sequence ("Prisoner of Azkaban" before "Chamber of
            // Secrets", "Half-Blood Prince" before "Goblet of Fire") with the missing-chapter
            // markers to prove it. Files whose name carries no number all share the 0 floor, where
            // the file-name order — the comparison this replaces — decides among them as before.
            // KMK <--
            .sortedWith(
                compareByDescending<Pair<String, SChapter>> { (_, chapter) ->
                    chapter.chapter_number.coerceAtLeast(0f)
                }.thenComparator { (name1, _), (name2, _) ->
                    name2.compareToCaseInsensitiveNaturalOrder(name1)
                },
            )
            .map(Pair<String, SChapter>::second)

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    fun getFormat(chapter: SChapter): Format {
        try {
            val (mangaDirName, chapterName) = chapter.url.split('/', limit = 2)
            return fileSystem.findChapterFile(mangaDirName, chapterName)
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (_: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!, reader.encrypted) }
                    }
                }
                is Format.Epub -> {
                    format.file.epubReader(context).use { epub ->
                        // KMK --> The book's own declared cover first: `coverHref` is the
                        // manifest's cover declaration as the engine resolved it, and asking for
                        // it costs nothing. `getImagesFromPages` reads and parses every spine
                        // document — the art-book fallback, not the first thing to try, and for
                        // a prose book the "first image" can be a publisher logo.
                        //
                        // DRM is refused outright: the bytes are enciphered however they are
                        // addressed, and a cover written from them would be noise. (Font
                        // obfuscation does not set hasDrm, so those books keep their covers.)
                        //
                        // `readBytes` bounds the read (the engine's per-entry ceiling) and answers
                        // null for a missing entry, which the `!!` this replaces turned into a
                        // NullPointerException whenever a manifest href named nothing the archive
                        // actually contains.
                        val entry = if (epub.hasDrm) {
                            null
                        } else {
                            epub.coverHref
                                ?: epub.getImagesFromPages().firstOrNull()
                        }
                        entry?.let { href ->
                            epub.readBytes(href)?.let { bytes ->
                                coverManager.update(manga, bytes.inputStream())
                            }
                        }
                        // KMK <--
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://komikku-app.github.io/docs/guides/local-source/"

        // SY -->
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        // SY <--

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
