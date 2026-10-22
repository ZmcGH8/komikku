package eu.kanade.domain.source.interactor

import eu.kanade.domain.ai.HouriPlusAi
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.library.model.creditsFromDescription
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * Houri+ (custom build): decides whether an entry shown by a source is a title that is already in the library,
 * even when it comes from a different source than the library entry.
 *
 * An entry is hidden only when:
 * 1. it is the same source entry as a library entry, or as one of the sources merged into a library entry;
 * 2. the user confirmed it (or it was matched before) and its library entry is still in the library; or
 * 3. its whole title is exactly one of the library entry's names (display title, source title, merged
 *    sources' titles, or the "Alternative Names" in its description) after ignoring case, accents and
 *    punctuation, AND its author or artist is one of the library entry's creators - spelled the same or as
 *    another romanization of the same name ("Yu Jin-Sung" / "Jinseong Yu", "Gamtwiiii" / "Gamtwieii").
 *    When the source's list doesn't include the author, the entry's details are fetched once to get it;
 * 4. or the source names no author at all (many only give a title), and the title is a distinctive name of
 *    exactly one library entry (three or more meaningful words, or a long name), so it can't be another
 *    series with a common title.
 *
 * Matches remembered by earlier, looser versions of these rules are checked again: one is kept only when its
 * title is still a distinctive name of that library entry. Matches the user confirmed always stay.
 *
 * Entries that look like the same title but don't match on both name and author go to a review list
 * instead, and stay visible until the user confirms them:
 * - the same name with a different (or no) author;
 * - a similar name (most words shared) with the same author, or that the AI (Settings → Translation)
 *   says is the same series.
 *
 * Entries the user marked "not the same" or "show again" are never hidden.
 */
class LibraryMatcher(
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getFavorites: GetFavorites = globalAppGraph.getFavorites,
    private val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences,
    private val sourceManager: SourceManager = globalAppGraph.sourceManager,
) {
    private val indexMutex = Mutex()
    private var index: Index? = null

    suspend fun isInLibrary(manga: Manga): Boolean {
        if (manga.favorite) return true
        val index = index()
        val key = key(manga.source, manga.url)
        if (key in index.shown) return false
        if (key in index.sourceEntries) return true
        index.remembered[key]?.let { libraryId ->
            if (libraryId in index.libraryTitles) return true
        }
        // Waiting for the user to confirm
        if (key in index.reviewKeys) return false

        val sameName = index.sameName(manga)
        if (sameName.isNotEmpty()) {
            // Started only once it is stored, so its clean-up can't run before it is stored
            val check = nameChecks.getOrPut(key) {
                checkScope.async(start = CoroutineStart.LAZY) { checkSameName(manga, sameName, index) }
            }
            check.start()
            // Usually done within a moment; when the source is slow the entry shows now and is hidden next time
            return withTimeoutOrNull(NAME_CHECK_WAIT_MS) { check.await() } ?: false
        }

        val candidate = index.nearMatch(manga) ?: return false
        val listedCreators = creatorsOf(manga.author, manga.artist)
        if (listedCreators.isNotEmpty() && index.creators[candidate].orEmpty().any { it in listedCreators }) {
            addToReview(manga, candidate, index, REASON_SIMILAR_NAME_SAME_AUTHOR, listedCreators)
            return false
        }
        if (index.aiEnabled && index.aiVerdicts[key] == null) {
            checkWithAi(manga, candidate, index)
        }
        return false
    }

    /**
     * Hidden right away when the author also matches; otherwise the entry goes to the review list.
     * Returns whether to hide it.
     */
    private suspend fun checkSameName(manga: Manga, sameName: Set<Long>, index: Index): Boolean {
        val key = key(manga.source, manga.url)
        try {
            var creators = creatorsOf(manga.author, manga.artist)
            var detailsFailed = false
            if (creators.isEmpty()) {
                // The source's list didn't include the author: read it from the entry's details (once)
                val fetched = fetchCreators(manga)
                detailsFailed = fetched == null
                creators = fetched.orEmpty()
            }
            val match = sameName.firstOrNull { libraryId -> index.creators[libraryId].orEmpty().any { it in creators } }
            if (match != null) {
                remember(manga, match, index)
                return true
            }
            // The source names nobody: a distinctive name that only one library entry has is enough
            val normalized = normalizeTitle(manga.title)
            if (creators.isEmpty() && sameName.size == 1 && isDistinctive(normalized)) {
                // Not remembered when the details couldn't be loaded, so it is checked again next time
                if (!detailsFailed) remember(manga, sameName.first(), index)
                return true
            }
            if (detailsFailed) return false
            val reason = if (creators.isEmpty()) REASON_SAME_NAME_NO_AUTHOR else REASON_SAME_NAME_OTHER_AUTHOR
            addToReview(manga, sameName.first(), index, reason, creators)
            return false
        } finally {
            // Decided (or failed): the next look comes from the stored lists, or retries after a failure
            nameChecks.remove(key)
        }
    }

    /** The source's author/artist for this entry, or null when the details couldn't be loaded. */
    private suspend fun fetchCreators(manga: Manga): Set<String>? {
        val source = sourceManager.get(manga.source) ?: return null
        return try {
            detailsSemaphore.withPermit {
                val details = source.getMangaUpdate(
                    manga = manga.toSManga(),
                    chapters = emptyList(),
                    fetchDetails = true,
                    fetchChapters = false,
                ).manga
                creatorsOf(details.author, details.artist)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Houri+: couldn't load details of ${manga.title} to compare authors" }
            null
        }
    }

    private fun checkWithAi(manga: Manga, libraryId: Long, index: Index) {
        val key = key(manga.source, manga.url)
        if (!aiInFlight.add(key)) return
        checkScope.launch {
            try {
                val verdict = HouriPlusAi.askYesNo(aiPrompt(manga, libraryId, index)) ?: return@launch
                index.aiVerdicts[key] = verdict
                storeMutex.withLock {
                    val pref = sourcePreferences.aiLibraryMatchVerdicts()
                    val entry = listOf(manga.source.toString(), manga.url, libraryId.toString(), if (verdict) "Y" else "N")
                        .joinToString("\t") { it.replace('\t', ' ') }
                    pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
                }
                if (verdict) {
                    addToReview(manga, libraryId, index, REASON_SIMILAR_NAME_AI, creatorsOf(manga.author, manga.artist))
                }
            } finally {
                aiInFlight.remove(key)
            }
        }
    }

    private fun aiPrompt(manga: Manga, libraryId: Long, index: Index): String {
        val names = index.names[libraryId].orEmpty().take(10)
        val authors = index.authors[libraryId].orEmpty().take(4)
        val other = buildString {
            append("\"${manga.title}\"")
            listOfNotNull(manga.author, manga.artist).filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }
                ?.let { append("; author/artist: ${it.joinToString()}") }
        }
        return """
            |Two comic (manga/manhwa/manhua) titles come from different websites. Decide whether they are the same series.
            |A different English translation or romanization of the title counts as the same series. A sequel, prequel,
            |spin-off, remake, the novel, or a different story with a similar name does not.
            |A: "${index.libraryTitles[libraryId]}"; other names: ${names.joinToString("; ")}${if (authors.isNotEmpty()) "; author/artist: ${authors.joinToString()}" else ""}
            |B: $other
            |Answer with exactly one word: YES or NO.
        """.trimMargin()
    }

    private suspend fun index(): Index {
        index?.let { return it }
        return indexMutex.withLock {
            index ?: buildIndex().also { index = it }
        }
    }

    private suspend fun buildIndex(): Index {
        val favorites = getFavorites.await()
        val mergeChildren = handler.awaitList { kmk_customQueries.libraryMergeChildren() }

        val libraryTitles = favorites.associate { it.id to it.title }
        val sourceEntries = HashMap<String, Long>()
        val nameIndex = HashMap<String, MutableSet<Long>>()
        val creators = HashMap<Long, MutableSet<String>>()
        // For the AI and the review list
        val names = HashMap<Long, MutableList<String>>()
        val authors = HashMap<Long, MutableList<String>>()
        val tokenIndex = HashMap<String, MutableList<NameTokens>>()

        fun addName(libraryId: Long, rawName: String?) {
            val name = rawName ?: return
            val normalized = normalizeTitle(name)
            if (normalized.isEmpty()) return
            val nameList = names.getOrPut(libraryId) { mutableListOf() }
            if (name !in nameList) nameList.add(name)
            nameIndex.getOrPut(normalized) { mutableSetOf() }.add(libraryId)
            significantTokens(normalized).takeIf { it.size >= 2 }?.let { tokens ->
                val entry = NameTokens(libraryId, tokens)
                tokens.forEach { tokenIndex.getOrPut(it) { mutableListOf() }.add(entry) }
            }
        }

        fun addCreators(libraryId: Long, values: List<String?>) {
            creators.getOrPut(libraryId) { mutableSetOf() }.addAll(creatorsOf(*values.toTypedArray()))
            val raw = authors.getOrPut(libraryId) { mutableListOf() }
            values.filterNotNull().filter { it.isNotBlank() && it !in raw }.forEach { raw.add(it) }
        }

        favorites.forEach { manga ->
            sourceEntries[key(manga.source, manga.url)] = manga.id
            addName(manga.id, manga.title)
            addName(manga.id, manga.ogTitle)
            alternativeNames(manga.description).forEach { addName(manga.id, it) }
            addCreators(manga.id, listOf(manga.author, manga.artist, manga.ogAuthor, manga.ogArtist))
            // Writers, artists and studios listed under "Credits" in the description
            addCreators(manga.id, creditsFromDescription(manga.description) { true })
        }
        mergeChildren.forEach { child ->
            sourceEntries[key(child.sourceId, child.url)] = child.libraryId
            addName(child.libraryId, child.title)
            addCreators(child.libraryId, listOf(child.author, child.artist))
        }

        // Forget remembered matches and review items whose library entry was removed from the library
        val remembered = ConcurrentHashMap<String, Long>()
        // ... and, for matches made by an earlier, looser version of the rules, ones that no longer hold
        val hiddenPref = sourcePreferences.hiddenLibraryMatches()
        val stillValid = hiddenPref.get().filter { entry ->
            val parts = entry.split('\t')
            val libraryId = parts.getOrNull(2)?.toLongOrNull() ?: return@filter true
            when (parts.getOrNull(5)) {
                MARK_USER, MARK_CHECKED -> true
                else -> {
                    val name = normalizeTitle(parts.getOrNull(3).orEmpty())
                    val owners = nameIndex[name].orEmpty()
                    libraryId !in libraryTitles || (libraryId in owners && owners.size == 1 && isDistinctive(name))
                }
            }
        }.toSet()
        if (stillValid.size != hiddenPref.get().size) hiddenPref.set(stillValid)
        prune(hiddenPref.get(), libraryTitles.keys) { kept ->
            hiddenPref.set(kept)
        }.forEach { (key, libraryId) -> remembered[key] = libraryId }
        val reviewKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
        prune(sourcePreferences.libraryMatchReview().get(), libraryTitles.keys) { kept ->
            sourcePreferences.libraryMatchReview().set(kept)
        }.forEach { (key, _) -> reviewKeys.add(key) }

        // AI answers on near matches, kept while their library entry is still in the library
        val aiVerdicts = ConcurrentHashMap<String, Boolean>()
        sourcePreferences.aiLibraryMatchVerdicts().get().forEach { entry ->
            val parts = entry.split('\t')
            val libraryId = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
            if (parts.size >= 4 && libraryId in libraryTitles) aiVerdicts[key(parts[0], parts[1])] = parts[3] == "Y"
        }

        return Index(
            libraryTitles = libraryTitles,
            sourceEntries = sourceEntries,
            nameIndex = nameIndex,
            creators = creators,
            remembered = remembered,
            reviewKeys = reviewKeys,
            shown = sourcePreferences.shownLibraryMatches().get(),
            names = names,
            authors = authors,
            tokenIndex = tokenIndex,
            aiVerdicts = aiVerdicts,
            aiEnabled = sourcePreferences.aiLibraryMatching().get() && HouriPlusAi.isAvailable(),
        )
    }

    /** Entries ("sourceId\turl\tlibraryId\t…") whose library entry still exists, as key to library id. */
    private fun prune(
        stored: Set<String>,
        libraryIds: Set<Long>,
        save: (Set<String>) -> Unit,
    ): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        val kept = stored.filter { entry ->
            val parts = entry.split('\t')
            val libraryId = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size >= 3 && libraryId != null && libraryId in libraryIds) {
                result += key(parts[0], parts[1]) to libraryId
                true
            } else {
                false
            }
        }
        if (kept.size != stored.size) save(kept.toSet())
        return result
    }

    private suspend fun remember(manga: Manga, libraryId: Long, index: Index) {
        val key = key(manga.source, manga.url)
        index.remembered[key] = libraryId
        storeMutex.withLock {
            val pref = sourcePreferences.hiddenLibraryMatches()
            val entry = listOf(
                manga.source.toString(),
                manga.url,
                libraryId.toString(),
                manga.title,
                index.libraryTitles[libraryId].orEmpty(),
                MARK_CHECKED,
            ).joinToString("\t") { it.clean() }
            pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
        }
    }

    private suspend fun addToReview(manga: Manga, libraryId: Long, index: Index, reason: String, creators: Set<String>) {
        val key = key(manga.source, manga.url)
        index.reviewKeys.add(key)
        val sourceAuthor = listOfNotNull(manga.author, manga.artist).filter { it.isNotBlank() }.distinct()
            .joinToString()
            .ifEmpty { creators.filterNot { it.startsWith("~") }.joinToString() }
        storeMutex.withLock {
            val pref = sourcePreferences.libraryMatchReview()
            val entry = listOf(
                manga.source.toString(),
                manga.url,
                libraryId.toString(),
                manga.title,
                index.libraryTitles[libraryId].orEmpty(),
                manga.id.toString(),
                reason,
                sourceAuthor,
                index.authors[libraryId].orEmpty().take(3).joinToString(),
            ).joinToString("\t") { it.clean() }
            pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
        }
    }

    private class Index(
        val libraryTitles: Map<Long, String>,
        val sourceEntries: Map<String, Long>,
        val nameIndex: Map<String, Set<Long>>,
        val creators: Map<Long, Set<String>>,
        val remembered: MutableMap<String, Long>,
        val reviewKeys: MutableSet<String>,
        val shown: Set<String>,
        val names: Map<Long, List<String>>,
        val authors: Map<Long, List<String>>,
        val tokenIndex: Map<String, List<NameTokens>>,
        val aiVerdicts: MutableMap<String, Boolean>,
        val aiEnabled: Boolean,
    ) {
        /** Library entries with a name identical to this entry's title (ignoring case, accents and punctuation). */
        fun sameName(manga: Manga): Set<Long> {
            val normalized = normalizeTitle(manga.title)
            if (normalized.isEmpty()) return emptySet()
            return nameIndex[normalized].orEmpty()
        }

        /**
         * The library entry whose name shares most words with this title (at least two words and half of all
         * the words in either name), or null. Only a candidate for the review list.
         */
        fun nearMatch(manga: Manga): Long? {
            val tokens = significantTokens(normalizeTitle(manga.title))
            if (tokens.size < 2) return null
            var best: Long? = null
            var bestScore = 0.0
            tokens.flatMap { tokenIndex[it].orEmpty() }.distinct().forEach { name ->
                val overlap = name.tokens.count { it in tokens }
                if (overlap < 2) return@forEach
                val score = overlap.toDouble() / (tokens.size + name.tokens.size - overlap)
                if (score >= 0.5 && score > bestScore) {
                    best = name.libraryId
                    bestScore = score
                }
            }
            return best
        }
    }

    data class HiddenMatch(
        val sourceId: Long,
        val url: String,
        val libraryId: Long,
        val title: String,
        val libraryTitle: String,
    )

    /** An entry waiting for the user to say whether it is the same title as a library entry. */
    data class ReviewItem(
        val sourceId: Long,
        val url: String,
        val libraryId: Long,
        val title: String,
        val libraryTitle: String,
        val mangaId: Long?,
        val reason: String,
        val sourceAuthor: String,
        val libraryAuthor: String,
    )

    private class NameTokens(val libraryId: Long, val tokens: Set<String>)

    companion object {
        private val storeMutex = Mutex()

        /** Marks a remembered match made by these rules ([MARK_CHECKED]) or confirmed by the user ([MARK_USER]). */
        private const val MARK_CHECKED = "v2"
        private const val MARK_USER = "user"

        const val REASON_SAME_NAME_OTHER_AUTHOR = "author"
        const val REASON_SAME_NAME_NO_AUTHOR = "noauthor"
        const val REASON_SIMILAR_NAME_SAME_AUTHOR = "similar"
        const val REASON_SIMILAR_NAME_AI = "ai"

        private const val NAME_CHECK_WAIT_MS = 5_000L

        // Background checks (author lookups, AI), shared by every screen so an entry is only checked once
        private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val nameChecks = ConcurrentHashMap<String, Deferred<Boolean>>()
        private val detailsSemaphore = Semaphore(2)
        private val aiInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "to", "in", "on", "and", "is", "i", "im", "my", "me", "as", "with", "for", "at",
            "by", "from", "his", "her", "its", "be", "was", "become", "became", "how", "that", "this", "after",
        )

        /** The words of a normalized name that carry meaning. */
        fun significantTokens(normalized: String): Set<String> =
            normalized.split(' ').filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

        private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")
        private val APOSTROPHES = Regex("['’‘`´ʼ]")
        private val NOISE_TAGS = Regex("""[(\[{]\s*(official|manhwa|manhua|webtoon|comics?)\s*[)\]}]""")
        private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
        private val ALTERNATIVE_HEADER = Regex(
            """^[^\p{L}]*(alternative|alternate|other|alt\.?)\s+(names?|titles?)[\s*_]*:[\s*_]*(.*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val BULLET = Regex("""^\s*([-*•–·]|\d+[.)])\s+""")
        private val INLINE_SEPARATORS = Regex("""\s*[;|•·／/]\s*""")
        private val CREATOR_SEPARATORS = Regex("""\s*(?:[,;/&]|\band\b|\(.*?\))\s*""", RegexOption.IGNORE_CASE)

        fun key(sourceId: Long, url: String) = "$sourceId\t$url"
        private fun key(sourceId: String, url: String) = "$sourceId\t$url"

        private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

        fun normalizeTitle(title: String): String {
            var text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
            text = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            text = Normalizer.normalize(text, Normalizer.Form.NFC)
            text = text.replace(APOSTROPHES, "").replace("&", " and ")
            text = text.replace(NOISE_TAGS, " ")
            text = text.replace(NON_ALPHANUMERIC, " ").trim()
            if (text.startsWith("the ")) text = text.removePrefix("the ")
            return text
        }

        /** Names listed under an "Alternative Names" / "Alternative Titles" heading in a description. */
        fun alternativeNames(description: String?): List<String> {
            if (description.isNullOrBlank()) return emptyList()
            val lines = description.lines()
            val names = mutableListOf<String>()
            lines.forEachIndexed { index, line ->
                val header = ALTERNATIVE_HEADER.find(line.trim()) ?: return@forEachIndexed
                val inline = header.groupValues[3].trim()
                if (inline.isNotEmpty()) {
                    names += inline.split(INLINE_SEPARATORS)
                }
                var next = index + 1
                var started = false
                while (next < lines.size && names.size < 60) {
                    val current = lines[next].trim()
                    when {
                        current.isEmpty() -> if (started) break
                        BULLET.containsMatchIn(lines[next]) -> {
                            started = true
                            names += current.replace(BULLET, "").trim()
                        }
                        else -> break
                    }
                    next++
                }
            }
            return names.map { it.trim().trim('*', '_').trim() }.filter { it.isNotEmpty() }
        }

        /**
         * A name only one series is likely to have: three or more meaningful words, or a long name.
         * "Player", "The Gamer" or "Reincarnator" are not.
         */
        fun isDistinctive(normalized: String): Boolean =
            significantTokens(normalized).size >= 3 || normalized.replace(" ", "").length >= 16

        private val VOWELS_AND_GLIDES = Regex("[aeiouywh]")
        private val REPEATS = Regex("(.)\\1+")
        private val LATIN_WORD = Regex("[a-z]+")

        /**
         * The consonants of a romanized word: romanizations of one Korean or Chinese name differ mostly in vowels
         * and in g/k, d/t, b/p, j/ch, r/l ("Jin-Seong" / "Jinsung", "Mun" / "Moon", "Gamtwiiii" / "Gamtwieii").
         */
        private fun skeleton(word: String): String {
            var w = word
            for ((from, to) in ROMANIZATION_PAIRS) w = w.replace(from, to)
            w = w.map { CONSONANT_CLASS[it] ?: it }.joinToString("")
            return w.replace(VOWELS_AND_GLIDES, "").replace(REPEATS, "$1")
        }

        private val ROMANIZATION_PAIRS = listOf(
            "ch" to "j", "sh" to "s", "ph" to "f", "ck" to "k", "c" to "k", "q" to "k", "x" to "ks", "z" to "j",
        )
        private val CONSONANT_CLASS = mapOf('k' to 'g', 't' to 'd', 'p' to 'b', 'l' to 'r', 'v' to 'b')

        /**
         * Creator names for comparing, ignoring case, accents, spaces and punctuation ("Yu Jin-Sung" and
         * "yu jinsung" are the same), word order ("Jin-Sung Yu") and the usual romanization differences of a
         * name ("Yu Jinseong"). Each name gives a few comparison keys; two names match when they share one.
         */
        fun creatorsOf(vararg values: String?): Set<String> {
            return values.flatMap { value ->
                if (value.isNullOrBlank()) {
                    emptyList()
                } else {
                    value.split(CREATOR_SEPARATORS).flatMap { creatorKeys(it) }
                }
            }.toSet()
        }

        private fun creatorKeys(name: String): List<String> {
            val split = normalizeTitle(name)                       // "Jin-Seong Yu" -> "jin seong yu"
            val joined = normalizeTitle(name.replace("-", ""))     // "Jin-Seong Yu" -> "jinseong yu"
            val whole = split.replace(" ", "")
            if (whole.length < 3) return emptyList()
            val keys = mutableListOf(whole)
            for (form in listOf(split, joined)) {
                val words = form.split(' ').filter { it.isNotEmpty() }
                if (words.size > 1) keys += words.sorted().joinToString("")
                if (words.isNotEmpty() && words.all { LATIN_WORD.matches(it) }) {
                    val sk = words.map { skeleton(it) }.filter { it.isNotEmpty() }.sorted().joinToString("")
                    if (sk.length >= 4) keys += "~$sk"
                }
            }
            return keys.distinct()
        }

        fun parseHiddenMatches(entries: Set<String>): List<HiddenMatch> {
            return entries.mapNotNull { entry ->
                val parts = entry.split('\t')
                HiddenMatch(
                    sourceId = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null,
                    url = parts.getOrNull(1) ?: return@mapNotNull null,
                    libraryId = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null,
                    title = parts.getOrNull(3).orEmpty(),
                    libraryTitle = parts.getOrNull(4).orEmpty(),
                )
            }
        }

        fun parseReviewItems(entries: Set<String>): List<ReviewItem> {
            return entries.mapNotNull { entry ->
                val parts = entry.split('\t')
                ReviewItem(
                    sourceId = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null,
                    url = parts.getOrNull(1) ?: return@mapNotNull null,
                    libraryId = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null,
                    title = parts.getOrNull(3).orEmpty(),
                    libraryTitle = parts.getOrNull(4).orEmpty(),
                    mangaId = parts.getOrNull(5)?.toLongOrNull(),
                    reason = parts.getOrNull(6).orEmpty(),
                    sourceAuthor = parts.getOrNull(7).orEmpty(),
                    libraryAuthor = parts.getOrNull(8).orEmpty(),
                )
            }
        }

        /** Show a hidden entry again and never hide it by name match in the future. */
        fun showAgain(sourcePreferences: SourcePreferences, match: HiddenMatch) {
            val key = key(match.sourceId, match.url)
            sourcePreferences.hiddenLibraryMatches().let { pref ->
                pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet())
            }
            sourcePreferences.shownLibraryMatches().let { pref ->
                pref.set(pref.get() + key)
            }
        }

        /** The user confirmed it's the same title: hide it from now on. */
        fun confirmReview(sourcePreferences: SourcePreferences, item: ReviewItem) {
            val key = key(item.sourceId, item.url)
            sourcePreferences.libraryMatchReview().let { pref ->
                pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet())
            }
            sourcePreferences.hiddenLibraryMatches().let { pref ->
                val entry = listOf(item.sourceId.toString(), item.url, item.libraryId.toString(), item.title, item.libraryTitle, MARK_USER)
                    .joinToString("\t") { it.clean() }
                pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
            }
        }

        /** The user said it's a different title: always show it. */
        fun rejectReview(sourcePreferences: SourcePreferences, item: ReviewItem) {
            val key = key(item.sourceId, item.url)
            sourcePreferences.libraryMatchReview().let { pref ->
                pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet())
            }
            sourcePreferences.shownLibraryMatches().let { pref ->
                pref.set(pref.get() + key)
            }
        }
    }
}
// KMK <--
