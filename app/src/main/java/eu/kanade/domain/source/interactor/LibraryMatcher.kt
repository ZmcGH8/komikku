package eu.kanade.domain.source.interactor

import eu.kanade.domain.ai.HouriPlusAi
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.app.di.globalAppGraph
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * Custom build: decides whether an entry shown by a source is a title that is already in the library,
 * even when it comes from a different source than the library entry.
 *
 * Matching is strict on purpose, so unrelated titles are never hidden:
 * 1. The same source entry as a library entry, or as one of the sources merged into a library entry.
 * 2. An entry hidden earlier by a name match (remembered, so it doesn't have to be matched again),
 *    as long as its library entry is still in the library.
 * 3. The entry's whole title is exactly one of the library entry's names (display title, source title,
 *    merged sources' titles, or the "Alternative Names" in its description) after ignoring case, accents and
 *    punctuation, and that name is distinctive: four CJK characters, or at least two words and ten letters
 *    (three words and fourteen letters for alternative names, which are often loose translations).
 *    Shorter names (e.g. "Hero", "Hero Returns") only match when the author or artist matches as well.
 *
 * Entries the user marked "show again" are never hidden.
 */
class LibraryMatcher(
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getFavorites: GetFavorites = globalAppGraph.getFavorites,
    private val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences,
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
        val libraryId = index.match(manga)
        if (libraryId != null) {
            remember(manga, libraryId, index)
            return true
        }
        // Houri+: titles that nearly match a library title (another translation of the name) are checked
        // by the AI in the background; a "yes" hides them from then on
        if (!index.aiEnabled) return false
        val candidate = index.nearMatch(manga) ?: return false
        when (index.aiVerdicts[key]) {
            true -> {
                remember(manga, candidate, index)
                return true
            }
            false -> return false
            null -> checkWithAi(manga, candidate, index)
        }
        return false
    }

    private fun checkWithAi(manga: Manga, libraryId: Long, index: Index) {
        val key = key(manga.source, manga.url)
        if (!aiInFlight.add(key)) return
        aiScope.launch {
            try {
                val verdict = HouriPlusAi.askYesNo(aiPrompt(manga, libraryId, index)) ?: return@launch
                index.aiVerdicts[key] = verdict
                storeMutex.withLock {
                    val pref = sourcePreferences.aiLibraryMatchVerdicts()
                    val entry = listOf(manga.source.toString(), manga.url, libraryId.toString(), if (verdict) "Y" else "N")
                        .joinToString("\t") { it.replace('\t', ' ') }
                    pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
                }
                if (verdict) remember(manga, libraryId, index)
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
        val strongNames = HashMap<String, Long>()
        val weakNames = HashMap<String, MutableSet<Long>>()
        val creators = HashMap<Long, MutableSet<String>>()
        // Houri+: for near matches checked by the AI
        val names = HashMap<Long, MutableList<String>>()
        val authors = HashMap<Long, MutableList<String>>()
        val tokenIndex = HashMap<String, MutableList<NameTokens>>()

        fun addName(libraryId: Long, rawName: String?, alternative: Boolean = false) {
            val name = rawName ?: return
            val normalized = normalizeTitle(name)
            if (normalized.isEmpty()) return
            val nameList = names.getOrPut(libraryId) { mutableListOf() }
            if (name !in nameList) nameList.add(name)
            significantTokens(normalized).takeIf { it.size >= 2 }?.let { tokens ->
                val entry = NameTokens(libraryId, tokens)
                tokens.forEach { tokenIndex.getOrPut(it) { mutableListOf() }.add(entry) }
            }
            if (isDistinctive(normalized, alternative)) {
                strongNames.putIfAbsent(normalized, libraryId)
            } else {
                weakNames.getOrPut(normalized) { mutableSetOf() }.add(libraryId)
            }
        }

        fun addCreators(libraryId: Long, vararg values: String?) {
            val set = creators.getOrPut(libraryId) { mutableSetOf() }
            values.forEach { set.addAll(splitCreators(it)) }
            val raw = authors.getOrPut(libraryId) { mutableListOf() }
            values.filterNotNull().filter { it.isNotBlank() && it !in raw }.forEach { raw.add(it) }
        }

        favorites.forEach { manga ->
            sourceEntries[key(manga.source, manga.url)] = manga.id
            addName(manga.id, manga.title)
            addName(manga.id, manga.ogTitle)
            alternativeNames(manga.description).forEach { addName(manga.id, it, alternative = true) }
            addCreators(manga.id, manga.author, manga.artist, manga.ogAuthor, manga.ogArtist)
        }
        mergeChildren.forEach { child ->
            sourceEntries[key(child.sourceId, child.url)] = child.libraryId
            addName(child.libraryId, child.title)
            addCreators(child.libraryId, child.author, child.artist)
        }

        // Forget remembered matches whose library entry was removed from the library
        val stored = sourcePreferences.hiddenLibraryMatches().get()
        val remembered = ConcurrentHashMap<String, Long>()
        val kept = stored.filter { entry ->
            val parts = entry.split('\t')
            val libraryId = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size >= 3 && libraryId != null && libraryId in libraryTitles) {
                remembered[key(parts[0], parts[1])] = libraryId
                true
            } else {
                false
            }
        }
        if (kept.size != stored.size) {
            sourcePreferences.hiddenLibraryMatches().set(kept.toSet())
        }

        // Houri+: AI verdicts on near matches, kept while their library entry is still in the library
        val aiVerdicts = ConcurrentHashMap<String, Boolean>()
        sourcePreferences.aiLibraryMatchVerdicts().get().forEach { entry ->
            val parts = entry.split('\t')
            val libraryId = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
            if (parts.size >= 4 && libraryId in libraryTitles) aiVerdicts[key(parts[0], parts[1])] = parts[3] == "Y"
        }

        return Index(
            libraryTitles = libraryTitles,
            sourceEntries = sourceEntries,
            strongNames = strongNames,
            weakNames = weakNames,
            creators = creators,
            remembered = remembered,
            shown = sourcePreferences.shownLibraryMatches().get(),
            names = names,
            authors = authors,
            tokenIndex = tokenIndex,
            aiVerdicts = aiVerdicts,
            aiEnabled = sourcePreferences.aiLibraryMatching().get() && HouriPlusAi.isAvailable(),
        )
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
            ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            pref.set(pref.get().filterNot { it.startsWith("$key\t") }.toSet() + entry)
        }
    }

    private class Index(
        val libraryTitles: Map<Long, String>,
        val sourceEntries: Map<String, Long>,
        val strongNames: Map<String, Long>,
        val weakNames: Map<String, Set<Long>>,
        val creators: Map<Long, Set<String>>,
        val remembered: MutableMap<String, Long>,
        val shown: Set<String>,
        val names: Map<Long, List<String>>,
        val authors: Map<Long, List<String>>,
        val tokenIndex: Map<String, List<NameTokens>>,
        val aiVerdicts: MutableMap<String, Boolean>,
        val aiEnabled: Boolean,
    ) {
        /**
         * Houri+: the library entry whose name shares most words with this title (at least two words and half
         * of all the words in either name), or null. Only a candidate for the AI to confirm.
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

        fun match(manga: Manga): Long? {
            val normalized = normalizeTitle(manga.title)
            if (normalized.isEmpty()) return null
            strongNames[normalized]?.let { return it }

            val candidates = weakNames[normalized] ?: return null
            val mangaCreators = splitCreators(manga.author) + splitCreators(manga.artist)
            if (mangaCreators.isEmpty()) return null
            return candidates.firstOrNull { libraryId ->
                creators[libraryId].orEmpty().any { it in mangaCreators }
            }
        }
    }

    data class HiddenMatch(
        val sourceId: Long,
        val url: String,
        val libraryId: Long,
        val title: String,
        val libraryTitle: String,
    )

    private class NameTokens(val libraryId: Long, val tokens: Set<String>)

    companion object {
        private val storeMutex = Mutex()

        // Houri+: background AI checks
        private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val aiInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "to", "in", "on", "and", "is", "i", "im", "my", "me", "as", "with", "for", "at",
            "by", "from", "his", "her", "its", "be", "was", "become", "became", "how", "that", "this", "after",
        )

        /** Houri+: the words of a normalized name that carry meaning. */
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
        private val CJK_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
        )

        fun key(sourceId: Long, url: String) = "$sourceId\t$url"
        private fun key(sourceId: String, url: String) = "$sourceId\t$url"

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

        /**
         * Whether a name is specific enough to identify a title on its own. Alternative names (often loose
         * translations) need a longer name than real titles.
         */
        fun isDistinctive(normalized: String, alternative: Boolean = false): Boolean {
            val cjk = normalized.count { it.code.let { code -> Character.UnicodeScript.of(code) in CJK_SCRIPTS } }
            if (cjk > 0 && cjk * 2 >= normalized.count { it.isLetter() }) {
                return cjk >= 4
            }
            val words = normalized.split(' ').filter { it.isNotEmpty() }
            val letters = normalized.count { it.isLetter() }
            return if (alternative) {
                words.size >= 3 && letters >= 14
            } else {
                words.size >= 2 && letters >= 10
            }
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

        fun splitCreators(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(CREATOR_SEPARATORS)
                .map { normalizeTitle(it) }
                .filter { it.length >= 3 }
                .toSet()
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
    }
}
// KMK <--
