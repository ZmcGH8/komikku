package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.core.archive.EpubReader
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fills manga and chapter metadata from this epub file's own metadata.
 *
 * ### Why this reads [EpubReader.metadata] and not the OPF again
 *
 * The engine has already parsed the package document — namespace-aware, so a book that binds
 * Dublin Core to `dcterms:` instead of the usual `dc:` prefix reads the same as any other. The
 * version this replaces re-parsed the OPF with Jsoup and matched elements by their literal tag
 * prefix (`getElementsByTag("dc:title")`), which is exactly the producer variation
 * `core/archive/AGENTS.md` records the engine as having been built to handle: for such a book
 * every lookup here silently yielded nothing, and the file name stood in as the chapter title
 * with no author or description anywhere. Reading the one parsed answer is cheaper than
 * opening the archive a second way, and it is the only way, so the two cannot disagree.
 *
 * ### Why every step here is nullable
 *
 * This runs against whatever `.epub` happens to sit in a folder, and a fair number of those are
 * not really books: a partial download, a file an extractor renamed, something with no OPF at
 * all. Every lookup below therefore degrades to "leave the field as it was" rather than
 * throwing, because a library refresh that dies on one malformed file takes the whole refresh —
 * and every other folder — with it.
 */
fun EpubReader.fillMetadata(manga: SManga, chapter: SChapter) {
    val metadata = this.metadata

    // Multiple authors are joined rather than first-picked: the OPF allows any number of
    // dc:creator elements, and dropping all but the first is information the book did carry.
    metadata.authors.takeIf { it.isNotEmpty() }?.let { manga.author = it.joinToString(", ") }
    metadata.description?.takeIf { it.isNotBlank() }?.let { manga.description = it }

    // KMK --> The chapter name is the book's own title. `displayTitle` joins every `dc:title` the
    // OPF declares, so a title the producer wrote as two elements ("Harry Potter" / "and the
    // Prisoner of Azkaban") reads whole instead of reading as its bare first part.
    //
    // The series prefix is the second repair. Producers filing a numbered series as one folder
    // keep the series title on the folder and the OPF holds only the volume's half — a "Harry
    // Potter" folder whose books are all titled "and the Prisoner of Azkaban" — and a bare
    // fragment cannot tell one row from another. When the book's title does not already open with
    // the series title and the file name carried a recognised volume number — the two facts every
    // file of a numbered series has, and no folder of separate works does — the series title
    // leads, and the row again reads as a book. The lowercase test keeps the prefix away from
    // whole titles that merely share a folder with numbered files: a real book title starts with
    // a capital.
    metadata.title?.takeIf { it.isNotBlank() }?.let {
        val seriesTitle = manga.title.trim()
        val bookTitle = metadata.displayTitle.orEmpty().trim()
        val isSeriesFragment = seriesTitle.isNotEmpty() &&
            chapter.chapter_number >= 0f &&
            !bookTitle.startsWith(seriesTitle, ignoreCase = true) &&
            bookTitle.firstOrNull()?.isLowerCase() == true

        chapter.name = if (isSeriesFragment) "$seriesTitle $bookTitle" else bookTitle
    }
    // KMK <--

    // The publisher is the scanlator-shaped field an epub actually has; a creator stands in
    // when the book names none, which is the same precedence the old OPF walk used.
    chapter.scanlator = metadata.publisher?.takeIf { it.isNotBlank() }
        ?: metadata.authors.firstOrNull()

    // The engine already falls back from dc:date to the file's dcterms:modified when a book
    // carries no publication date, so one field is the whole answer here.
    metadata.date?.let { parseEpubDate(it) }?.let { chapter.date_upload = it }
}

/**
 * Parses an EPUB `dc:date`, or null when it is not one this understands.
 *
 * `Locale.ROOT` rather than the default locale, and that is not pedantry: `SimpleDateFormat` picks
 * its calendar from the locale, so on a Thai device the Buddhist calendar shifts every year by 543
 * and every chapter's date lands seven centuries away. The pattern itself is fixed-width and has no
 * localised text, so there is nothing for a locale to disambiguate.
 *
 * The era in `Z` is also ignored deliberately — `Z` matches both `+02:00` and `Z`, and an offset is
 * an hour-scale detail that has never mattered for "when was this chapter added".
 */
private fun parseEpubDate(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.length < MIN_DATE_LENGTH) return null
    return try {
        SimpleDateFormat(DATE_PATTERN, Locale.ROOT).apply { isLenient = false }.parse(trimmed)?.time
    } catch (e: ParseException) {
        null
    }
}

/** `yyyy-MM-dd'T'HH:mm:ss` plus the offset or `Z` that producers append. */
private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"

/** The shortest date worth trying: `yyyy-MM-dd`. Anything shorter is not parseable by the pattern. */
private const val MIN_DATE_LENGTH = 10
