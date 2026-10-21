package eu.kanade.tachiyomi.ui.reader.viewer.novel

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Backs the reader's *contents* sheet.
 *
 * Loads once on open rather than per scroll: a book's table of contents is small, it does not change
 * while the book is open, and a source is allowed to read the archive to produce it.
 *
 * Entries are flattened with their nesting depth and marked reachable or not against
 * [availableDocuments] — see [flattenToc] for why a whole-book contents produces untappable rows
 * rather than a shorter list.
 */
class NovelTocScreenModel(
    private val source: TextTocSource,
    private val chapter: SChapter,
    private val availableDocuments: Set<String>,
    /**
     * Archive path of the document on screen, or null.
     *
     * Marking and scrolling to the reader's current section is the whole reason this exists, so the
     * document has to arrive *here* rather than be inferred inside: a sheet built without it still
     * renders a correct contents list, which is exactly why the omission was invisible — the list is
     * right and only the position within it is wrong. See [flattenToc].
     */
    private val currentDocument: String? = null,
) : ScreenModel {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * This model's own job, cancelled in [onDestroy].
     *
     * Not [ioCoroutineScope], and the difference is the whole fix. That extension stores its scope in
     * Voyager's `ScreenModelStore`, which is keyed on the instance and disposed by the **navigator**
     * when a screen is popped. Neither of these models is ever popped — they are built by `remember`
     * inside a dialog branch and vanish when that branch leaves composition — so nothing ever calls
     * `ScreenModelStore.remove`, and the store kept the scope, the debounce collector, the
     * `SChapter` and the source alive for the life of the process. One leaked scope per sheet opened.
     */
    private val job = SupervisorJob()

    private val scope = CoroutineScope(Dispatchers.IO + job)

    init {
        scope.launch {
            // try/catch rather than runCatching: its block is not a suspend lambda, so it cannot wrap
            // getTableOfContents at all. The failure is a whole sheet's worth of nothing, so it has to
            // be caught here rather than tear down the reader's scope.
            _state.value = try {
                State.Loaded(flattenToc(source.getTableOfContents(chapter), availableDocuments, currentDocument))
            } catch (e: Throwable) {
                State.Error(e)
            }
        }
    }

    /**
     * Stops this model's work. Idempotent.
     *
     * Named `dispose` and not `onDestroy` on purpose: `ScreenModel` has no teardown hook, because
     * Voyager normally cancels a screen model's `coroutineScope` for it — which is exactly what does
     * not happen here. See [job].
     */
    fun dispose() {
        job.cancel()
    }

    sealed interface State {
        data object Loading : State
        data class Loaded(val rows: List<NovelTocEntry>) : State {
            /** False when nothing in the contents points into the chapter on screen. */
            val hasReachable: Boolean get() = rows.any { it.reachable }
        }
        data class Error(val error: Throwable) : State
    }
}

/**
 * Backs the reader's *find in chapter* sheet.
 *
 * ### Why the query is a flow and not a button
 *
 * Typing is the interaction, so the search has to follow typing, or the reader presses a button after
 * every word. Debounced at [QUERY_DEBOUNCE_MS]: long enough that a fast typist triggers one search
 * rather than one per keystroke, short enough that a pause reads as thinking rather than as stuck.
 *
 * `collectLatest`, not `collect`: a search over a long chapter is not instant, and without it a slow
 * search for an old query lands after a fast one for the query the reader actually typed last, and
 * the results describe a word that is no longer in the box.
 *
 * A blank query clears the results rather than searching for nothing.
 */
class NovelSearchScreenModel(
    private val source: TextSearchSource,
    private val chapter: SChapter,
) : ScreenModel {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<Results>(Results.Idle)
    val results: StateFlow<Results> = _results.asStateFlow()

    /**
     * This model's own job, cancelled in [onDestroy].
     *
     * The same reasoning as [NovelTocScreenModel.job], and here it matters more: this model's scope
     * carries a `debounce` collector that holds the source and the chapter alive and is still parked
     * on the flow after the reader has closed the sheet. Collected nowhere, it would sit there until
     * the process died — which is also the leak that would keep a book's archive mapped.
     */
    private val job = SupervisorJob()

    private val scope = CoroutineScope(Dispatchers.IO + job)

    init {
        scope.launch {
            _query
                // The initial empty string is not a search the reader asked for.
                .drop(1)
                .debounce(QUERY_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _results.value = Results.Idle
                        return@collectLatest
                    }
                    _results.value = Results.Searching
                    // try/catch, not runCatching: its block is not a suspend lambda, so a suspend
                    // searchInChapter cannot go inside one.
                    _results.value = try {
                        Results.Loaded(toSearchResults(source.searchInChapter(chapter, query)))
                    } catch (e: Throwable) {
                        Results.Error(e)
                    }
                }
        }
    }

    /** Stops this model's work. Idempotent. See [job]. */
    fun dispose() {
        job.cancel()
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    sealed interface Results {
        data object Idle : Results
        data object Searching : Results
        data class Loaded(val rows: List<NovelSearchResult>) : Results
        data class Error(val error: Throwable) : Results
    }

    companion object {
        const val QUERY_DEBOUNCE_MS = 250L
    }
}
