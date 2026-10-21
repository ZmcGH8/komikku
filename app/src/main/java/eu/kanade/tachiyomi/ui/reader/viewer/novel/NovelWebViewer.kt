package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerProvider
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import exh.log.xLogE
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubMimeSniff
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders a text chapter in a WebView.
 *
 * ### Why a WebView
 *
 * An EPUB is an XHTML document carrying the publisher's own stylesheet — drop caps, ruby annotations,
 * columns, vertical writing, tables, footnotes. Re-implementing that in Compose means re-implementing
 * a browser, badly. So the document keeps its own markup and the reader's preferences are layered on as
 * an injected stylesheet (see [NovelTypographyCss]).
 *
 * The corollary: layout is the engine's business. What this viewer owns is which document is showing,
 * what the reader's typography is, where in the document they are, and how they move between documents
 * and chapters.
 *
 * ### Resource resolution
 *
 * Each document is loaded at the base URL its source asks for
 * ([eu.kanade.tachiyomi.source.text.TextBaseUrlSource]) — for an EPUB that is the **directory the
 * document sits in**, not the root of the archive. Requests to that origin are intercepted and streamed
 * out of the archive, which is what makes `<img src="../images/plate.jpg">` resolve inside a zip file.
 * Requests to any other host are deliberately **not** intercepted, so an absolutely-linked image or an
 * external stylesheet still loads normally.
 *
 * The origin is `https://book.internal/` — a *real* scheme on a reserved host, because the engine only
 * dispatches subresource requests for a document whose base URL is http(s). An invented scheme here
 * was why illustrations, stylesheets and embedded fonts never arrived: the document rendered, and
 * every file it asked for was refused before interception was ever consulted. See
 * [mihon.core.archive.EpubArchiveUrls] for the whole account.
 *
 * Getting that base wrong is invisible and total: every relative reference lands one or two levels from
 * where the publisher meant it, so the stylesheets, illustrations and embedded fonts all 404 and the
 * book renders as bare text with the reader's own styling and nothing else. Which is why the base URL
 * comes from the page rather than from one constant.
 *
 * ### Where each concern lives
 *
 * | Concern | Owner |
 * |---|---|
 * | the markup handed to the engine | [NovelDocument] |
 * | turning a screen at a tap, and deciding when a document has run out of room | this class, natively — `scrollBy`/`scrollY`, no scripting involved |
 * | fractions and anchors — the measurements only the laid-out document can answer | [NovelScrollBridge] |
 * | typography and per-chapter positions | [NovelTypographyPreferences] |
 * | the general reader theme's background, as the book sees it | [NovelReaderTheme] |
 * | the stylesheet itself | [NovelTypographyCss] |
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
@SuppressLint("SetJavaScriptEnabled")
internal class NovelWebViewer(
    private val activity: ReaderActivity,
    private val resources: TextResourceSource?,
    private val loader: TextPageLoader?,
    private val readerPrefs: ReaderPreferences,
    private val novelPrefs: NovelTypographyPreferences,
) : Viewer {

    override val isVertical: Boolean get() = true

    override val currentReaderPage: ReaderPage? get() = currentPage

    private var chapters: ViewerChapters? = null
    private var currentPage: ReaderPage? = null

    /**
     * The document asked for but perhaps not yet read.
     *
     * Separate from [currentPage] because the two answer different questions while a document is in
     * flight: `currentPage` is what is on the screen, and it is what the reading position belongs to.
     * Navigation needs the one the reader is heading for, or a second tap while the first is still
     * loading would resolve its neighbour against the document they just left and send them back.
     */
    private var pendingPage: ReaderPage? = null

    /**
     * Monotonic id of the newest [showDocument] request.
     *
     * A document's markup may arrive after the reader has turned past it. The id is how the stale
     * read recognises itself: only the newest request is allowed to render, so the reader's place is
     * decided by their last press rather than by whichever read happened to finish last.
     */
    private var documentRequest = 0

    /** The in-flight document read, cancelled by the next [showDocument]. */
    private var documentLoadJob: Job? = null

    /**
     * Character length of each document, for [NovelProgress.chapterPercent] and for turning a scroll
     * fraction back into a stored offset.
     *
     * Read from the loader on every use rather than cached at chapter open, because the loader fills
     * these in as documents are read and a snapshot taken before the first one would weight every
     * chapter by nothing at all. A document that has not been read has no length yet and contributes
     * zero, which is the "unknown, do not divide by it" case the progress code already handles.
     */
    private fun documentLengths(): List<Int> =
        (chapters?.currChapter?.pageLoader as? TextPageLoader)?.documentLengths().orEmpty()

    /** Scroll fraction to restore once the document now loading has laid out. */
    private var pendingRestoreFraction: Float? = null

    /**
     * Element to scroll to once the document now loading has laid out, from a contents entry.
     *
     * Separate from [pendingRestoreFraction] because the two are different *requests*: one is "put
     * me back", the other is "take me to this section". An intent belongs to the [showDocument]
     * request it was asked with — set when the request starts, consumed by [applyPendingPosition]
     * at the finish that comes with the document. It must never be written **after** the call:
     * for an unread document the render runs later than it, and a jump that set these after
     * [showDocument] returned had them wiped by the very render the jump caused — every jump
     * into a section the reader had not opened yet landed wherever the document was last left.
     */
    private var pendingAnchor: String? = null

    /** Fraction a search result asked for. See [jumpToFraction]. */
    private var pendingFraction: Float? = null

    /**
     * How far above an anchored element to stop.
     *
     * The reader's own top bar and bottom bar overlay the document when the menus are open, so an
     * element scrolled to the very top is hidden behind them. One line's worth of inset keeps the
     * heading visible without leaving a gap at the start of a section.
     */
    private val anchorInsetPx: Int get() = (webView.resources.displayMetrics.density * 32).toInt()

    /** Last fraction written to the position store, so a drag does not write a preference per frame. */
    private var lastSavedFraction = -1f

    /**
     * Archive path of the document on screen, or null before the first one loads.
     *
     * The contents sheet marks the entry for this document and opens scrolled to it. Read off the
     * live page rather than off the stored position, so it follows the reader as they move rather than
     * reporting where they last stopped.
     */
    val currentDocument: String? get() = currentPage?.textPage?.url

    /**
     * Base URL of the document currently loaded, or null before the first one is.
     *
     * The answer to "did `onPageFinished` fire for the document I asked for?". The engine reports
     * every top-level navigation, including ones a WebViewClient refused, so without this a stray
     * finish would apply a pending restore or an anchor against a layout it was never measured on.
     */
    private var currentBaseUrl: String? = null

    // ---------------------------------------------------------------- continuous reading

    /**
     * The live document is a window of the book, not one section: while the reader scrolls, the
     * sections either side are appended into and prepended out of it. [windowFirst] and
     * [windowLast] name the page-list positions the DOM currently spans, and are reset to the
     * one loaded document by every [renderDocument] — a tap, a button or a contents jump starts a
     * fresh window deliberately, the same way a manga reader's page jump resets its neighbourhood.
     */
    private var windowFirst = -1
    private var windowLast = -1

    /** The section the viewport is over, as the probe last reported it. */
    private var currentSegment = -1

    /** One append/prepend at a time; further probes queue behind the next scroll event. */
    private var windowJob: Job? = null

    /**
     * When the scroll position was last probed, so a fling does not run one per frame.
     */
    private var lastProbeAt = 0L

    /**
     * Where the reader is inside the section under the viewport, 0..1 — the value the bottom
     * navigator's slider shows. Kept as compose state so the bar follows the reader live.
     */
    var scrollFraction by mutableStateOf(0f)
        private set

    /** True while that slider is being dragged: its seeks drive the state; the scroll must not. */
    private var fractionDragActive = false

    /** The scroll position the gesture tracker last saw, to tell "moved" from "stuck at an edge". */
    private var lastObservedScrollY = 0

    /** Consecutive forward/backward drag steps that produced no scroll, and when a stuck turn last fired. */
    private var stuckForward = 0
    private var stuckBackward = 0
    private var lastStuckTurnAt = 0L

    /**
     * Serves [NovelInline] from the same archive the streaming path reads: one reader, one lease,
     * one release path.
     */
    private val inlineFetcher = NovelInline.Fetcher { url ->
        val stream = runCatching { resources?.openResource(url) }
            .onFailure { xLogE("Failed to open archive resource for inlining: $url", it) }
            .getOrNull() ?: return@Fetcher null
        val bytes = try {
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            xLogE("Failed to read archive resource for inlining: $url", e)
            return@Fetcher null
        }
        NovelInline.Resource(
            bytes = bytes,
            mimeType = runCatching { resources?.contentTypeFor(url) }.getOrNull()
                ?: EpubMimeSniff.of(bytes)
                ?: OCTET_STREAM,
        )
    }

    /** The reader font's `@font-face`, when the family chosen is one the reader brought. */
    private fun readerFontCss(): String {
        val family = typography.fontFamily
        if (!family.startsWith(NovelFonts.PREFIX)) return ""
        val name = family.removePrefix(NovelFonts.PREFIX)
        if (name.isBlank()) return ""
        val face = readerFontCache.getOrPut(family) {
            NovelFonts.load(activity, family) ?: return ""
        }
        return NovelFonts.faceCss(family, face)
    }

    /** The chosen font, read once per family rather than once per document. */
    private val readerFontCache = mutableMapOf<String, NovelFonts.Face>()

    /** Cancelled in [destroy]: the typography subscription outlives the viewer otherwise. */
    private var typographyJob: Job? = null

    /**
     * Typography, republished from settings. Changing it re-applies in place, no reload.
     *
     * Seeded at construction with [withReaderThemeBackground] rather than plain
     * [NovelTypographyPreferences.typography], because the first document renders with whatever this
     * holds — a seed taken without the reader theme would paint the first page with the reader's own
     * `black` background and the publisher's own `black` text standing on it, which is exactly the
     * unreadable combination the merge exists to prevent.
     */
    var typography by mutableStateOf(withReaderThemeBackground(currentTypography()))

    /**
     * The typography the settings hold, with the general reader theme's background standing in when
     * the appearance tab chose none.
     *
     * The general tab's background (black / gray / automatic) is a preference the novel viewer used
     * to ignore entirely: it painted the *container* behind the WebView but left the document to the
     * publisher's own colours, so choosing `black` there left the book's own black text standing on
     * a black page — reported as "the text disappears". A dark theme background therefore flows into
     * the document as a `background-color`, and the stylesheet's existing
     * [NovelTypography.effectiveFontColor] rule derives a contrasting text colour from it — no new
     * colour logic, the one that already existed is simply allowed to see the background.
     *
     * A white theme stays null on purpose: white is the absence of a reader opinion (the transparent
     * WebView over the white container already reads as white), and forcing `background-color: white
     * !important` for it would override a publisher's dark plate for no benefit. An appearance-tab
     * background always wins — it is the more specific choice, made in the tab that shows a preview.
     */
    private fun withReaderThemeBackground(value: NovelTypography): NovelTypography {
        if (value.backgroundColor != null) return value
        val fromTheme = NovelReaderTheme.backgroundHex(
            activity.readerBackgroundColor(readerPrefs.readerTheme().get()),
        ) ?: return value
        return value.copy(backgroundColor = fromTheme)
    }

    /** The typography the settings hold right now, sanitized, never null. */
    private fun currentTypography(): NovelTypography =
        runCatching { novelPrefs.typography() }.getOrDefault(NovelTypography.DEFAULT)

    /**
     * Reader-controlled background, applied to the WebView itself as well as the document.
     *
     * A WebView whose content is black but whose overscroll glow is white reads as a rendering bug, so
     * this goes on both. Follows [typography], so the general reader theme reaches it through the
     * merge above.
     */
    private val backgroundColor: Int
        get() = typography.backgroundColor?.toColorIntOrNull() ?: Color.TRANSPARENT

    /**
     * Taps, long-presses and scrolls.
     *
     * Nothing here consumes the event, so selecting and copying text, pinch-zoom and the WebView's own
     * scrolling all keep working — this decides what a tap *means*, never whether it arrives.
     */
    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x)
                return true
            }

            // KMK --> Deliberately no `onLongPress` here. In an image chapter a long press opens the
            // page-actions sheet — Set as cover, Share, Save — because that is the only thing a long
            // press can mean there. In a book, long-pressing *text* means "select text", and the
            // WebView already answers it with its own selection toolbar; opening our sheet as well
            // put an image toolbar on top of the selection one, and every action in it then
            // crashed: those actions act on `ReaderPage.stream`, and a document has no image
            // stream, so the stream was null. So a book's action sheet arrives only through the
            // WebView's own long-click hit test below (setOnLongClickListener) — offered when, and
            // only when, the finger is on an illustration, and acting on that image's own stream,
            // never on the page's.
            // KMK <--

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (abs(dy) > MIN_SCROLL_TO_HIDE_MENU && activity.viewModel.state.value.menuVisible) {
                    activity.hideMenu()
                }
                trackStuckScroll(dy)
                return false
            }
        },
    )

    private val webView: WebView = WebView(activity).apply {
        setBackgroundColor(backgroundColor)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            // The scroll bridge is how the reader measures the document; nothing else needs scripting.
            javaScriptEnabled = true
            // The publisher's layout has to survive: a viewport wider than the device would let the
            // engine pick a line length the reader never asked for.
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            hardenSettings()
        }
        webViewClient = ArchiveWebViewClient()
        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        // Every scroll of the document — finger, fling, seek or an append's compensation — is the
        // moment to ask where the reader now is: the probe updates the page counter, the saved
        // position and the progress slider, and decides whether the window should grow.
        setOnScrollChangeListener { _, _, _, _, _ -> scheduleContinuousProbe() }
        // KMK --> The long press is split on what the finger is on, which only the engine answers.
        // Anything that is not an illustration answers false here, and the WebView's own text
        // selection follows exactly as it did before this existed. `IMAGE_TYPE` carries the image's
        // src in `extra` — either a `https://book.internal/…` reference this archive still streams
        // or a `data:` URI the inliner embedded into the document, both resolvable from the
        // chapter's lease later, when the user confirms. `SRC_IMAGE_ANCHOR_TYPE` is a *linked*
        // illustration, where `extra` names the link's target rather than the picture; the async
        // `requestFocusNodeHref` reply carries the src instead. An external image — a CDN plate the
        // publisher linked in — is not this book's art, so it opens no sheet at all.
        setOnLongClickListener { _ ->
            when (hitTestResult.type) {
                WebView.HitTestResult.IMAGE_TYPE -> hitTestResult.extra?.let(::openImageSheet) ?: false
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // Consumed even when the reply names no image: a selection dragging across a
                    // linked picture would otherwise start underneath the same gesture.
                    requestFocusNodeHref(Message.obtain(focusNodeReply))
                    true
                }
                else -> false
            }
        }
        // KMK <--
    }

    override fun getView(): View = webView

    // KMK -->
    // ---------------------------------------------------------------- image actions

    /**
     * Replies to `requestFocusNodeHref` for a long press that landed on a **linked** illustration.
     *
     * The WebView answers that call asynchronously, on this handler, bundling the focus node's
     * `src` — the picture itself, which the synchronous hit test's `extra` does not carry for an
     * image inside a link (there it holds the link's target). A blank or absent src means nothing
     * worth acting on: the press simply did nothing, the same outcome a hit test that could not
     * name an image produces.
     */
    private val focusNodeReply = Handler(Looper.getMainLooper()) { message ->
        message.data?.getString("src")?.let(::openImageSheet)
        true
    }

    /**
     * Opens the actions sheet for [imageUrl] — the long-pressed illustration — when it is one this
     * feature can act on.
     *
     * Two spellings count, and both resolve later from the chapter's own lease: the reserved
     * [EpubArchiveUrls] space, and the `data:` URIs [NovelInline] embedded into the markup. An
     * external image is the publisher's business and its action would only ever fail, so refusing
     * here keeps the sheet off a press that could never do anything.
     *
     * @return whether the press was claimed, so a selection does not also start underneath it.
     */
    private fun openImageSheet(imageUrl: String): Boolean {
        if (!EpubArchiveUrls.isArchiveUrl(imageUrl) &&
            !imageUrl.startsWith(INLINED_DATA_IMAGE, ignoreCase = true)
        ) {
            return false
        }
        activity.viewModel.openNovelImageDialog(imageUrl)
        return true
    }

    // KMK <--

    // ---------------------------------------------------------------- containment

    /**
     * Locks the renderer down to what a book actually needs.
     *
     * ### Why this is not paranoia
     *
     * The document in this WebView is a file the user downloaded, and it runs the publisher's own
     * `<script>`. Every setting below has a default that is fine for a page the app wrote and unsafe
     * for a page it did not — this WebView is otherwise configured as though we authored the HTML it
     * is displaying.
     *
     * | Setting | Default | Why it matters here |
     * |---|---|---|
     * | `allowFileAccess` | **true** | `file://` reaches the app's own storage. A book whose script fetches `file:///data/data/…/databases/…db` would otherwise read the user's library. |
     * | `allowContentAccess` | **true** | The same through `content://` — the whole document-provider surface, which is where the user's books are. |
     * | `allowUniversalAccessFromFileURLs` | false | Must stay false: it is the switch that discards the origin, letting a `file://` page read any other origin. |
     * | `mixedContentMode` | **ALWAYS_ALLOW** | An `https` page may pull `http` subresources. Nothing here needs an insecure one; permitting it is a downgrade, not a feature. |
     * | `javaScriptCanOpenWindowsAutomatically` | true | Popups the reader cannot see and cannot dismiss. |
     * | `mediaPlaybackRequiresUserGesture` | **false** | Without it a chapter autoplays audio and keeps playing as the reader turns the page. |
     * | `setGeolocationEnabled` | false | Stated rather than assumed: a book has no business asking where the reader is. |
     *
     * `setAllowFileAccessFromFileURLs` is deliberately absent. It has defaulted to false since API 16
     * and is deprecated, and `allowFileAccess = false` already closes the file scheme outright.
     * Restating a deprecated flag to the default it already had is the kind of thing that reads as
     * hardening while protecting nothing.
     */
    private fun WebSettings.hardenSettings() {
        allowFileAccess = false
        allowContentAccess = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        safeBrowsingEnabled = true
    }

    override fun setChapters(chapters: ViewerChapters) {
        this.chapters = chapters

        val pages = chapters.currChapter.pages ?: return
        if (pages.isEmpty()) return

        // Prefer the stored document over the chapter's resume index: this viewer can restore the
        // position *within* a document, which is strictly better information than "page 3 of 12".
        // Both are coerced, because a chapter can have grown shorter since it was saved — a deleted
        // file, a re-import.
        val stored = novelPrefs.position(chapters.currChapter.chapter.url)?.documentIndex
        val target = (stored ?: chapters.currChapter.requestedPage).coerceIn(0, pages.lastIndex)
        showDocument(pages[target])
    }

    override fun moveToPage(page: ReaderPage) {
        showDocument(page)
    }

    /** Advances one screen. Also the auto-scroll controller's step. */
    override fun moveToNext() {
        step(volumeForward = true)
    }

    override fun destroy() {
        typographyJob?.cancel()
        typographyJob = null
        // A read in flight must not outlive the viewer: it would finish against a destroyed WebView.
        documentLoadJob?.cancel()
        documentLoadJob = null
        // The window grows on the activity's scope; it has no business outliving the view it feeds.
        windowJob?.cancel()
        windowJob = null
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        // Action-up only, so a held key does not flip direction mid-hold.
        if (event.action != KeyEvent.ACTION_UP) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Left to the platform when the reader is not set up for volume keys, so the keys still
                // change the volume.
                if (!readerPrefs.readWithVolumeKeys().get()) return false
                val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                val inverted = readerPrefs.readWithVolumeKeysInverted().get()
                step(volumeForward = up != inverted)
                return true
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                activity.toggleMenu()
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                step(volumeForward = true)
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                step(volumeForward = false)
                return true
            }

            else -> return false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * Re-applies typography to the document already on screen.
     *
     * Reloading would throw the scroll position away on every slider drag, so this swaps the stylesheet
     * in the live DOM instead of calling [moveToPage] again.
     */
    private fun applyTypography() {
        webView.setBackgroundColor(backgroundColor)
        webView.evaluateJavascript(liveTypographyJs(), null)
        webView.invalidate()
    }

    // ---------------------------------------------------------------- documents

    /**
     * Shows [page], reading its markup first if the source did not hand it over with the page list.
     *
     * Split in two because they are not the same moment. A source that answers
     * [eu.kanade.tachiyomi.source.text.TextIncrementalSource] has already given the reader the shape
     * of the book and nothing else, so the markup has to be fetched before there is anything to
     * render; a source that does not has it in hand already and this is one hop.
     *
     * The request token is what keeps the two honest. Turns are the reader pressing faster than a
     * document can be read, so without it a slow read for the document they passed finishes last and
     * puts them back where they left — the one failure mode a reading position cannot recover from,
     * because the position was saved for where they were, not where they went.
     *
     * [anchor] and [fraction] are the *destination* this request was asked for — a contents entry or
     * a search hit. They travel with the request rather than being set after it returns, because for
     * an unread document the render happens at the end of an asynchronous read: an intent written
     * after the call was wiped by the render the jump itself caused (see [pendingAnchor]). A plain
     * turn passes neither and clears both, so a destination left over from an older request cannot
     * fire on a document it was never meant for.
     */
    private fun showDocument(page: ReaderPage, anchor: String? = null, fraction: Float? = null) {
        val textPage = page.textPage ?: return
        val request = ++documentRequest
        pendingPage = page

        if (anchor == null && fraction == null) {
            pendingAnchor = null
            pendingFraction = null
        } else {
            pendingAnchor = anchor
            pendingFraction = fraction
        }

        if (textPage.text.isNotEmpty()) {
            renderDocument(page)
            return
        }

        val loader = loader ?: return
        documentLoadJob?.cancel()
        documentLoadJob = activity.lifecycleScope.launch {
            loader.ensureText(page)
            // A newer turn won while this document was being read; its own request will render.
            if (request != documentRequest) return@launch
            renderDocument(page)
        }
    }

    /** Hands a document to the engine. Every measurement the reader asks for happens after this. */
    private fun renderDocument(page: ReaderPage) {
        val textPage = page.textPage ?: return
        currentPage = page

        // The base URL decides where every relative reference in this document points, and it is a
        // property of the document, not of the book. EpubArchiveUrls.BASE_URL is the right answer
        // only for a spine item that sits at the archive root.
        val baseUrl = baseUrlFor(textPage)

        // The document's own illustrations and stylesheets travel inside it, as data: URIs — the
        // one channel that has never failed to reach the engine. Anything over the ceiling stays a
        // URL and keeps the streaming path.
        val inlined = NovelInline.inline(textPage.text, baseUrl, inlineFetcher)

        val position = chapters?.currChapter?.positionOf(page) ?: page.index
        // A fresh document is a fresh window: the continuous layer grows it from here.
        windowFirst = position
        windowLast = position
        currentSegment = position
        lastSavedFraction = -1f
        scrollFraction = 0f
        stuckForward = 0
        stuckBackward = 0
        lastObservedScrollY = 0
        // ... and no growth of the old window may land in it. An append or prepend still being read
        // belongs to a DOM that just ended; [appendAhead] stamps what already left for the renderer
        // (see the epoch there), and the cancel here retires the read that has not.
        windowJob?.cancel()
        windowJob = null

        // Set before the load, because onPageFinished for it can arrive before this function returns
        // on a warm engine, and a null here would make the finish look like somebody else's.
        //
        // The load URL carries this request's own fragment. The engine keeps a page's scroll where
        // the address it is given is one it already holds, and every document in the same book
        // folder loads at the *same* directory base — so an untagged load looked like the old page
        // continuing, and a fresh document could start from the scroll offset of the one it
        // replaced: halfway through it, at its end, or past it into the section that the window
        // then grew below the clamped viewport. No document and no book names a `kmk-load`
        // fragment, so every load is a different address. The finish guard below ignores the tag —
        // a WebView has one finish in flight, and it is always this load's.
        currentBaseUrl = "$baseUrl#kmk-load-$documentRequest"
        val html = NovelDocument.build(
            inlined,
            typography,
            baseUrl,
            segment = position to textPage.url,
            extraHead = readerFontCss(),
            epoch = documentRequest,
        )
        webView.loadDataWithBaseURL(currentBaseUrl, html, "text/html", "UTF-8", null)

        pendingRestoreFraction = restoreFractionFor(page)
        lastSavedFraction = -1f
        activity.onPageSelected(page)
    }

    /** The URL a document must be loaded at so its own relative references resolve. */
    private fun baseUrlFor(textPage: TextPage): String =
        (resources as? TextBaseUrlSource)?.baseUrlFor(textPage) ?: EpubArchiveUrls.BASE_URL

    /** The fraction to restore for [page], or null when this document has no stored position. */
    private fun restoreFractionFor(page: ReaderPage): Float? {
        val stored = novelPrefs.position(page.chapter.chapter.url) ?: return null
        // Only a position *inside this document* is restorable here. A position elsewhere in the
        // chapter has already been acted on, by setChapters choosing which document to open.
        if (stored.documentIndex != page.index) return null
        return stored.fractionInDocument
    }

    /**
     * Jumps to a table-of-contents entry: the named document, and the named element inside it.
     *
     * Replaces any restore in progress — arriving at a contents entry means "the top of this section",
     * not "where you happened to stop reading it last time", and honouring the stored position would
     * silently land somewhere else in the chapter.
     *
     * The scroll happens in `onPageFinished`, after the document has laid out: asking for a position
     * before then measures an empty page and lands at the top.
     */
    fun jumpTo(document: String, fragment: String?) {
        val page = chapters?.currChapter?.pages?.firstOrNull { it.textPage?.url == document } ?: return

        // The intent travels *with* the request (see [showDocument]). For an unread document the
        // render happens at the end of an asynchronous read, so an intent set after the call was
        // wiped by the render it caused, and the entry silently landed wherever the document was
        // last left instead of where it pointed. The scroll still happens in `onPageFinished`,
        // after the document has laid out: asking for a position before then measures an empty
        // page and lands at the top. Reloading even when the document is already on screen is
        // deliberate: one path, and the caller asked to go somewhere.
        if (fragment != null) {
            showDocument(page, anchor = fragment)
        } else {
            // A contents entry without a fragment means "the top of this document".
            showDocument(page, fraction = 0f)
        }
    }

    /**
     * Jumps to a fraction of the document with [documentIndex].
     *
     * The position inside a document is approximate — the source reports offsets in the page's plain
     * text while the engine lays out the markup — so this is deliberately expressed as a ratio. See
     * [toSearchResults] for why a character offset cannot cross over intact.
     *
     * [documentIndex] is a *text-page* index, matching what `TextSearchHit.pageIndex` carries, and not
     * a position in the reader's own page list. See the comment on the lookup below for why the two
     * are not interchangeable.
     */
    fun jumpToFraction(documentIndex: Int, fraction: Float) {
        val pages = chapters?.currChapter?.pages
        // Matched on `textPage.index`, which is what `TextSearchHit.pageIndex` is defined to carry —
        // not on the page's position in the list. The two agree only when every spine document read
        // cleanly: a skipped document (missing, corrupt, over the size ceiling) leaves a hole in the
        // text-page indexes while the reader's own list stays dense, so a positional lookup would
        // scroll to the wrong document — or to none — for every hit after the hole. See
        // `TextSearchHit.pageIndex`.
        val page = pages?.firstOrNull { it.textPage?.index == documentIndex } ?: return

        // Same reasoning as [jumpTo]: the destination belongs to the request, not to after it —
        // an unread document renders at the end of an asynchronous read, and an intent written
        // after the call was wiped by the render the jump itself caused.
        showDocument(page, fraction = fraction.coerceIn(0f, 1f))
    }

    private fun handleTap(x: Float) {
        val width = webView.width
        if (width <= 0) return

        // A figure answers its own tap, so ask before doing anything else with it. One round trip,
        // and the whole tap waits on it - which is the trade for not scrolling a screen away from a
        // picture the reader was trying to look at. The answer is false whenever the document cannot
        // answer at all, and the tap then falls through to exactly what it did before.
        webView.evaluateJavascript(NovelScrollBridge.TAKE_IMAGE_TAP) { answer ->
            if (answer == "true") return@evaluateJavascript
            scrollTap(x)
        }
    }

    /**
     * A tap that was not a figure: menu in the middle, scroll at either edge.
     *
     * Split out of [handleTap] so the figure check can sit in front of it without either half
     * having to know the other exists.
     */
    private fun scrollTap(x: Float) {
        val width = webView.width
        if (width <= 0) return

        val zone = (if (readerPrefs.smallerTapZone().get()) SMALL_TAP_ZONE else TAP_ZONE) * width
        val forward = when {
            x >= width - zone -> true
            x <= zone -> false
            else -> {
                activity.toggleMenu()
                return
            }
        }

        step(volumeForward = forward != readerPrefs.pagerNavInverted().get().shouldInvertHorizontal)
    }

    // ---------------------------------------------------------------- turning

    /**
     * One unit of forward or backward reading: scroll a screen, or turn the document when there is no
     * screen left to scroll.
     *
     * ### Why the scroll and the verdict are the host's, not the document's
     *
     * This used to be two `evaluateJavascript` round trips — scroll through the bridge's
     * `scrollViewport`, then ask the bridge's `edge()` which way the document had run out of room —
     * which made turning depend on the document's own `<script>` having run and on the engine
     * answering host evaluations. Where either did not happen, every tap did nothing: no scroll
     * mid-document, no turn at either end, and the page navigator was left the only way from one
     * chapter to the next. A WebView's scroll position is not a secret the document holds —
     * [WebView.scrollY] can be read and [WebView.scrollBy] driven from here, neither of which
     * involves scripting — so turning is decided natively now. The bridge remains for what only the
     * document can know: fractions for the reading position and anchors from the contents.
     *
     * The verdict is taken **after** the scroll, from a `post`, not on the line after it: a scroll
     * that has not settled yet reads as "did not move" and would turn the document on a tap that was
     * about to scroll.
     *
     * | Where the document is | Backward tap | Forward tap |
     * |---|---|---|
     * | room to scroll this way | scroll | scroll |
     * | nowhere left this way | previous document | next document |
     * | fits on one screen | previous document | next document |
     * | not laid out yet | nothing | nothing |
     *
     * "Not laid out yet" is guarded rather than inferred: a WebView with no content answers every
     * scroll with "did not move", and reading that as an edge turned the document on the first tap of
     * a freshly-opened book — which is how a book got skipped before its first screen was ever seen.
     */
    private fun step(volumeForward: Boolean) {
        if (webView.height <= 0 || webView.contentHeight <= 0) return

        val before = webView.scrollY
        val by = (webView.height * 0.9f).toInt() * if (volumeForward) 1 else -1
        webView.scrollBy(0, by)

        webView.post {
            if (abs(webView.scrollY - before) < MIN_MOVEMENT_PX) {
                // The tap ran out of room. Inside the book that room is one append away — a tap
                // grows the window rather than jumping the reader out of the chapter — and only
                // the book's own edges fall through to a volume turn.
                if (volumeForward) {
                    if (growWindowForward()) return@post
                    advanceDocument()
                } else {
                    if (growWindowBackward()) return@post
                    retreatDocument()
                }
            }
        }
    }

    /** Next document, or next chapter. */
    private fun advanceDocument() {
        neighbour(1)?.let {
            showDocument(it)
            return
        }

        val chapters = chapters ?: return
        val nextChapter = chapters.nextChapter ?: run {
            // Past the last document of the last chapter. There is nowhere to go, so offer the menu —
            // that is where "next chapter" and the chapter list live — instead of swallowing the tap.
            activity.showMenu()
            return
        }

        // Both are requested: the preload is what makes the switch instant for a network source, and the
        // switch itself is what makes it happen for a local one, where preloading is a no-op.
        activity.requestPreloadChapter(nextChapter)
        activity.viewModel.goToAdjacentChapter(nextChapter)
    }

    /** Previous document, or previous chapter. */
    private fun retreatDocument() {
        neighbour(-1)?.let {
            showDocument(it)
            return
        }

        val chapters = chapters ?: return
        val previousChapter = chapters.prevChapter ?: return
        activity.requestPreloadChapter(previousChapter)
        activity.viewModel.goToAdjacentChapter(previousChapter)
    }

    /**
     * The document [offset] places from the one the reader is on, or null when there is none.
     *
     * Through [ReaderChapter.positionOf], which is by identity rather than by equality and returns `-1`
     * for a page the list no longer contains. The `-1` is the point: a chapter swap that has landed
     * while this viewer is still holding the old page used to compute `-1 + 1`, which is zero, and a
     * forward tap from there opened the *first* document of the book. Reading "no such neighbour" as
     * "the end of the chapter" sends the tap to the chapter boundary, which is where the reader was
     * actually trying to go.
     *
     * The page asked for rather than the page on screen, so a second tap during a read resolves its
     * neighbour against where the reader is heading and cannot send them back.
     */
    private fun neighbour(offset: Int): ReaderPage? {
        val chapter = chapters?.currChapter ?: return null
        val pages = chapter.pages ?: return null
        val page = pendingPage ?: currentPage ?: return null
        val at = chapter.positionOf(page)
        if (at < 0) return null
        return pages.getOrNull(at + offset)
    }

    // ---------------------------------------------------------------- chapter navigation

    /**
     * The bottom navigator's next button, while a book is on screen: the next *section* of the book,
     * or the next library chapter at its end.
     *
     * A book is one library chapter, so the button's manga meaning — "next library chapter" — would
     * skip the entire book and land in another volume. For a book, its sections are its chapters:
     * the button steps them the same way a tap at the bottom edge does, and falls through to the
     * adjacent volume at either end of the book. Non-sequential jumps belong to the contents list,
     * which is what the chapter selector renders for a book.
     *
     * Deliberately the same code as the tap, so the button and the gesture cannot disagree about
     * what "next" means.
     */
    fun nextSection() = advanceDocument()

    /** [nextSection], backwards: the previous section, or the previous volume at the book's start. */
    fun previousSection() = retreatDocument()

    /** Whether there is a next section or volume for [nextSection] to go to. */
    val hasNextSection: Boolean
        get() = neighbour(1) != null || chapters?.nextChapter != null

    /** Whether there is a previous section or volume for [previousSection] to go to. */
    val hasPreviousSection: Boolean
        get() = neighbour(-1) != null || chapters?.prevChapter != null

    // ---------------------------------------------------------------- continuous reading

    /**
     * Grows the window forward if there is a section left to grow into, and reports whether it
     * did (or already had one in flight) — the caller falls through to a volume turn only when the
     * book itself has run out.
     */
    private fun growWindowForward(): Boolean {
        if (windowJob?.isActive == true) return true
        val pages = chapters?.currChapter?.pages ?: return false
        if (windowLast >= pages.lastIndex) return false
        appendAhead()
        return true
    }

    /** [growWindowForward], backwards. */
    private fun growWindowBackward(): Boolean {
        if (windowJob?.isActive == true) return true
        if (windowFirst <= 0) return false
        prependBehind()
        return true
    }

    /**
     * Asks the document where the reader is, at most once per [PROBE_INTERVAL_MS]: a fling fires
     * the scroll listener on every frame, and the answer cannot change faster than the document
     * can reflow.
     */
    private fun scheduleContinuousProbe() {
        if (fractionDragActive) return
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < PROBE_INTERVAL_MS) return
        lastProbeAt = now
        webView.evaluateJavascript(NovelContinuous.PROBE) { raw -> onProbe(raw) }
    }

    /** The probe's answer, once parsed. Drives everything that follows the reader as they read. */
    private fun onProbe(raw: String?) {
        val probe = parseProbe(raw) ?: return

        if (!fractionDragActive &&
            (probe.fraction * 100f).roundToInt() != (scrollFraction * 100f).roundToInt()
        ) {
            scrollFraction = probe.fraction
        }

        if (probe.position != currentSegment) {
            currentSegment = probe.position
            lastSavedFraction = -1f
            chapters?.currChapter?.pages?.getOrNull(probe.position)?.let { page ->
                // The page under the viewport is the page the reader is reading: the counter, the
                // history and the contents sheet's marker all follow it, exactly as they follow
                // the page on screen in the continuous manga viewer.
                currentPage = page
                activity.onPageSelected(page)
            }
            trimToWindow()
        }

        savePosition(probe)
        if (probe.nearBottom) growWindowForward()
        if (probe.nearTop) growWindowBackward()
    }

    /**
     * The next section, appended into the live document.
     *
     * A *body fragment*, not a document: the live page already has its charset, its base and the
     * reader's rules, and the fragment's own references were resolved against its own base at
     * inline time — data: URIs have no base to be wrong about.
     */
    private fun appendAhead() {
        val position = windowLast + 1
        val page = chapters?.currChapter?.pages?.getOrNull(position) ?: return
        val loader = loader ?: return
        // Stamped against the request that built the document this append is grown into. A jump or
        // a turn re-renders fresh while this read is in flight, and the render cancels the job from
        // its side; the stamp is the second half of the guard, for a job that already left for the
        // renderer and cannot be told it was cancelled (see renderDocument and NovelContinuous).
        val epoch = documentRequest
        windowJob = activity.lifecycleScope.launch {
            if (!loader.ensureText(page)) {
                logcat(LogPriority.WARN) { "NovelViewer: could not append section $position; leaving the window" }
                return@launch
            }
            // A newer request took the document over while this read was running: its render wiped
            // the window this append was grown into, so the fragment must not land in it.
            if (epoch != documentRequest) return@launch
            val textPage = page.textPage ?: return@launch
            val fragment = NovelInline.inline(
                NovelContinuous.bodyFragment(textPage.text),
                baseUrlFor(textPage),
                inlineFetcher,
            )
            webView.evaluateJavascript(
                NovelContinuous.appendScript(fragment, position, textPage.url, epoch),
            ) { answer ->
                // The script answers "false" when it ran against a document that was replaced
                // underneath it; only a fresh answer may move the window bookkeeping.
                if (NovelContinuous.isStale(answer)) return@evaluateJavascript
                windowLast = position
                trimToWindow()
            }
        }
    }

    /** The previous section, prepended with the scroll held still. See [NovelContinuous.prependScript]. */
    private fun prependBehind() {
        val position = windowFirst - 1
        if (position < 0) return
        val page = chapters?.currChapter?.pages?.getOrNull(position) ?: return
        val loader = loader ?: return
        // Stamped against the request that built the document this prepend is grown into; see
        // [appendAhead] for why a cancelled read needs a second guard, and what a stale script
        // answer is.
        val epoch = documentRequest
        windowJob = activity.lifecycleScope.launch {
            if (!loader.ensureText(page)) {
                logcat(LogPriority.WARN) { "NovelViewer: could not prepend section $position; leaving the window" }
                return@launch
            }
            // A newer request took the document over while this read was running: its render wiped
            // the window this prepend was grown into, so the fragment must not land in it — and a
            // stale prepend is the worse of the two, because it scrolls the reader with its
            // compensation instead of only adding content below.
            if (epoch != documentRequest) return@launch
            val textPage = page.textPage ?: return@launch
            val fragment = NovelInline.inline(
                NovelContinuous.bodyFragment(textPage.text),
                baseUrlFor(textPage),
                inlineFetcher,
            )
            webView.evaluateJavascript(
                NovelContinuous.prependScript(fragment, position, textPage.url, epoch),
            ) { answer ->
                if (NovelContinuous.isStale(answer)) return@evaluateJavascript
                windowFirst = position
                trimToWindow()
            }
        }
    }

    /**
     * Keeps the window to [NovelContinuous.WINDOW_EDGE] sections either side of the one under the
     * viewport, so a long session's DOM stays bounded the way the continuous manga viewer's
     * chapter neighbourhood does. One section per call; the next probe trims the rest.
     */
    private fun trimToWindow() {
        val edge = NovelContinuous.WINDOW_EDGE
        if (currentSegment - windowFirst > edge && windowFirst < windowLast) {
            webView.evaluateJavascript(
                NovelContinuous.trimScript(windowFirst, compensate = true, epoch = documentRequest),
                null,
            )
            windowFirst++
        }
        if (windowLast - currentSegment > edge && windowFirst < windowLast) {
            webView.evaluateJavascript(
                NovelContinuous.trimScript(windowLast, compensate = false, epoch = documentRequest),
                null,
            )
            windowLast--
        }
    }

    /**
     * A drag that keeps trying to scroll past the end of the *book* — where no append can answer —
     * turns to the next volume, the way the continuous manga reader runs off the last page into
     * the next chapter. Only at the book's own edges: mid-book, "nowhere left to scroll" means an
     * append has not landed yet, not that the book is over.
     */
    private fun trackStuckScroll(dy: Float) {
        val y = webView.scrollY
        val moved = y - lastObservedScrollY
        lastObservedScrollY = y

        if (dy > STUCK_SCROLL_DELTA) {
            if (moved < MIN_MOVEMENT_PX) stuckForward++ else stuckForward = 0
            if (stuckForward >= STUCK_SCROLL_EVENTS && canTurnAtStuckEdge(forward = true)) {
                stuckForward = 0
                lastStuckTurnAt = System.currentTimeMillis()
                advanceDocument()
            }
        } else if (dy < -STUCK_SCROLL_DELTA) {
            if (moved > -MIN_MOVEMENT_PX) stuckBackward++ else stuckBackward = 0
            if (stuckBackward >= STUCK_SCROLL_EVENTS && canTurnAtStuckEdge(forward = false)) {
                stuckBackward = 0
                lastStuckTurnAt = System.currentTimeMillis()
                retreatDocument()
            }
        }
    }

    /** A stuck edge is a volume turn only at the book's ends, once per push. */
    private fun canTurnAtStuckEdge(forward: Boolean): Boolean {
        if (windowJob?.isActive == true) return false
        if (System.currentTimeMillis() - lastStuckTurnAt < STUCK_TURN_COOLDOWN_MS) return false
        val pages = chapters?.currChapter?.pages ?: return false
        return if (forward) windowLast >= pages.lastIndex else windowFirst <= 0
    }

    /**
     * The bottom navigator's slider moved: seek to [fraction] of the section under the viewport.
     *
     * The slider state follows the thumb immediately, and the document's own scroll reports are
     * muted until the drag ends — a seek that scrolled the document underneath a live drag would
     * fight the thumb for the position.
     */
    fun seekFraction(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        scrollFraction = target
        fractionDragActive = true
        webView.evaluateJavascript(NovelContinuous.seekScript(target), null)
    }

    /** The drag ended: the document's scroll takes the thumb back over. */
    fun seekFractionEnd() {
        fractionDragActive = false
    }

    /**
     * Persists where the reader is inside the section under the viewport.
     *
     * Throttled on a fraction delta rather than per probe: this is a preference write, and a fling
     * probes more often than it scrolls far.
     */
    private fun savePosition(probe: Probe) {
        val page = chapters?.currChapter?.pages?.getOrNull(probe.position) ?: return
        val length = documentLengths().getOrNull(page.index) ?: return
        if (abs(probe.fraction - lastSavedFraction) < SAVE_THRESHOLD) return
        lastSavedFraction = probe.fraction
        novelPrefs.setPosition(
            page.chapter.chapter.url,
            NovelProgress.Position(
                documentIndex = page.index,
                characterOffset = NovelProgress.characterOffsetFor(probe.fraction, length),
                characterLength = length,
            ),
        )
    }

    /** The probe's reply: which section, how far in, and whether the edges of the window are near. */
    private data class Probe(
        val position: Int,
        val fraction: Float,
        val nearBottom: Boolean,
        val nearTop: Boolean,
    )

    /**
     * Parses the probe out of the `evaluateJavascript` reply.
     *
     * The reply is the JSON encoding of the script's return object, so the fields arrive in the
     * order the script wrote them and nowhere else; a document with no segments replies `null`,
     * which is the moment before the first load and not an error.
     */
    private fun parseProbe(raw: String?): Probe? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        val position = PROBE_POSITION.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val fraction = PROBE_FRACTION.find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return Probe(
            position = position,
            fraction = fraction.toFloat().coerceIn(0f, 1f),
            nearBottom = PROBE_NEAR_BOTTOM.find(raw)?.groupValues?.get(1) == "1",
            nearTop = PROBE_NEAR_TOP.find(raw)?.groupValues?.get(1) == "1",
        )
    }

    // ---------------------------------------------------------------- resources

    /**
     * Serves archive-internal URLs out of the open book and lets everything else through.
     *
     * Returning null for a non-archive URL is load-bearing: it is what stops this class breaking a book
     * that links to an image on a real web server.
     *
     * A resource the archive does not contain answers with an explicit 404 rather than with null. A null
     * return hands the URL back to the platform, which then waits on a request to a scheme no handler
     * exists for — so one missing plate holds the document's layout hostage instead of showing a broken
     * image and getting out of the way.
     */
    private inner class ArchiveWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (!EpubArchiveUrls.isArchiveUrl(url)) return null

            val path = EpubArchiveUrls.archivePathOf(url)
            if (path == null) {
                logcat { "NovelViewer: archive url with no path, refused: $url" }
                return notFound()
            }

            val stream = runCatching { resources?.openResource(url) }
                .onFailure { xLogE("Failed to open archive resource: $url", it) }
                .getOrNull()
            if (stream == null) {
                // The single most useful line in the whole EPUB path. "Images are not displayed" has
                // no other explanation available from the UI — the document still asks for the plate
                // by name and nothing anywhere says it was refused — so every refusal names the file.
                logcat(LogPriority.WARN) {
                    "NovelViewer: archive resource not found: $path (chapter=${chapters?.currChapter?.chapter?.url})"
                }
                return notFound()
            }

            val mime = resolveMimeType(url, path)

            // Text responses must declare an encoding or the engine guesses and mangles CJK. Binary ones
            // must not: declaring UTF-8 over a font or an image truncates the body at the first byte
            // that is not valid UTF-8, which is a resource that fails to load with no error at all.
            val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) {
                "UTF-8"
            } else {
                null
            }
            logcat { "NovelViewer: serving $path as $mime" }
            return WebResourceResponse(mime, encoding, stream)
        }

        /**
         * The content type to serve [path] under, never null and never a lie.
         *
         * Three answers in order of authority: the source, which knows its own archive; the shared
         * extension table; and finally `application/octet-stream`. The last one is a last resort
         * rather than a guess because a renderer asked for an octet stream declines to decode it
         * exactly as it declines to decode a wrongly-typed image — so the only thing it buys is that
         * the request fails fast instead of being sniffed.
         *
         * The middle step is fed the **archive path**, not the URL. The URL is what the old code
         * passed, so a query string or fragment survived into the extension lookup and a `.jpg?v=2`
         * stopped being a JPEG — which is a plate that silently never draws.
         */
        private fun resolveMimeType(url: String, path: String): String =
            runCatching { resources?.contentTypeFor(url) }.getOrNull()
                ?: EpubArchiveUrls.contentTypeForPath(path)
                ?: OCTET_STREAM

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // An archive-internal link is one of the book's own documents. The platform has no
            // handler for the scheme, so handing it back would simply fail — it has to be turned
            // into a page turn here instead.
            if (EpubArchiveUrls.isArchiveUrl(url)) {
                openArchiveLink(url)
                return true
            }

            // Everything else is a link out of the book: a footnote to a site, a "where to buy".
            //
            // Returning false would load it *in this WebView*, inside the reader's own chrome, on top
            // of the document the reader was part-way through, with a JavaScript context and the
            // reader's trust pointed at whatever the book chose. The comment that used to sit here
            // said this "belongs to the browser… so returning false lets it navigate" — it did not.
            // Handing it to the system is what was meant, and it is what happens now.
            openExternally(url)
            return true
        }

        /**
         * Follows a link inside the book, when it names a document this chapter actually loaded.
         *
         * The URL is mapped back with [EpubArchiveUrls.archivePathOf] — the same decoding and
         * normalisation the resource loader applies — so it lines up with the `TextPage.url` values
         * the page list is built from. Anything that does not name one of them is refused: a
         * whole book's links span files this chapter never read, and showing a document the loader
         * has no page for would leave the viewer with nothing to display.
         */
        private fun openArchiveLink(url: String) {
            val pages = chapters?.currChapter?.pages
            val document = EpubArchiveUrls.archivePathOf(url)
            val page = pages?.firstOrNull { it.textPage?.url == document }
            if (page != null) {
                showDocument(page)
            } else {
                xLogE("Refusing in-book link to a document this chapter does not contain: $url")
            }
        }

        /**
         * Hands a URL to whatever the system uses for links.
         *
         * Failure is swallowed: a footnote with no handler must not take the reader out of the book,
         * and the alternative to doing nothing is a crash.
         */
        private fun openExternally(url: String) {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { xLogE("No handler for out-of-book link: $url", it) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            val askedFor = currentBaseUrl ?: return
            // Only the document that was asked for counts. Applying a pending position to some other
            // document would scroll to an offset measured against a completely different layout, and
            // the reader would land mid-sentence with no way to explain it.
            //
            // Compared percent-decoded: a directory the book's own producer named in non-ASCII comes
            // back from the engine percent-encoded, and a raw string comparison would then mistake
            // the document that was asked for somebody else's - silently skipping the restore, the
            // contents jump and the position save that all hang off this check.
            //
            // The comparison ignores the fragment (`#kmk-load-N`, see renderDocument): the load URL
            // carries the request tag, and an engine that normalises it away must not make this
            // finish look like somebody else's. Two documents cannot collide on the directory base
            // alone because a WebView has at most one finish in flight, and it is always this
            // load's.
            if (EpubArchiveUrls.percentDecode(url).substringBefore('#') !=
                EpubArchiveUrls.percentDecode(askedFor).substringBefore('#')
            ) {
                return
            }
            applyPendingPosition()
            // Re-assert typography in the live DOM too, so the injected stylesheet and the running
            // document cannot drift apart after a self-navigation.
            view.evaluateJavascript(liveTypographyJs(), null)
            // The probe is where the page counter, the saved position and the progress slider all
            // start from: run one now rather than waiting for the reader's first scroll.
            scheduleContinuousProbe()
        }

        private fun notFound(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * The current typography as a script that installs it into the running document.
     *
     * The stylesheet is encoded with [NovelScrollBridge.quoteForJs] rather than hand-escaped here.
     * The hand-rolled version replaced `\`, `'` and `\n` and stopped there — which left `\r`, U+2028
     * and U+2029 unescaped. Those are line terminators to a JavaScript parser, so a font family
     * containing one produced a `SyntaxError`: the whole script failed, the stylesheet swap silently
     * did nothing, and the reader kept the styling it had before the setting changed. A font name is
     * reader-chosen, so this was reachable by the reader and by nothing else — which is why it
     * presented as "the font setting does nothing" rather than as a crash.
     *
     * A reader-chosen font's `@font-face` rides along, for the same reason it is in the built
     * document's head: a live style swap that changed the family without redeclaring the face
     * would fall back to the publisher's font until the next document.
     */
    private fun liveTypographyJs(): String {
        val css = readerFontCss() + NovelTypographyCss.stylesheet(typography)
        return """
            (function(){
              var old = document.getElementById('kmk-typography-live');
              if (old && old.parentNode) old.parentNode.removeChild(old);
              var s = document.createElement('style');
              s.id = 'kmk-typography-live';
              s.textContent = ${NovelScrollBridge.quoteForJs(css)};
              if (document.head) document.head.appendChild(s);
            })();
        """.trimIndent()
    }

    /**
     * Applies whichever position was asked for, if any.
     *
     * Runs from `onPageFinished`, because every one of these measures the document: the engine has
     * laid nothing out before then, so an anchor resolves to the top of an empty page and a fraction
     * to zero.
     */
    private fun applyPendingPosition() {
        val anchor = pendingAnchor
        if (anchor != null) {
            pendingAnchor = null
            pendingFraction = null
            // A jump is "take me here", not "put me back": the anchor's scroll stands, and a
            // restore left standing would scroll it away on whatever finish came next.
            pendingRestoreFraction = null
            webView.evaluateJavascript(
                NovelScrollBridge.scrollToAnchor(anchor, anchorInsetPx),
                null,
            )
            return
        }

        val fraction = pendingFraction ?: pendingRestoreFraction ?: return
        pendingFraction = null
        pendingRestoreFraction = null
        lastSavedFraction = fraction
        webView.evaluateJavascript(
            NovelScrollBridge.SET_FRACTION.replace("%f", fraction.toString()),
            null,
        )
    }

    init {
        // Typography is read once at construction (into the seed of [typography], before anything
        // renders) and then observed from two places, because two settings pages can move it:
        //
        //  - the Reading appearance tab, which hands over the whole new value;
        //  - the general tab's reader theme, which only chooses a background — so the current
        //    appearance value is re-read and re-merged, and the two can never disagree about
        //    which background a page wears.
        //
        // Without the second subscription the theme choice applied to the *next* document only,
        // which for a reader who opened the settings mid-book is "the setting did nothing".
        //
        // Started here rather than handed in, so the subscription ends with the viewer: the activity's
        // scope outlives a viewer that a reading-mode switch has already destroyed.
        typographyJob = activity.lifecycleScope.launch {
            merge(
                novelPrefs.changes().map(::withReaderThemeBackground),
                readerPrefs.readerTheme().changes().drop(1).map {
                    withReaderThemeBackground(currentTypography())
                },
            ).collect { value ->
                typography = value
                applyTypography()
            }
        }
    }

    private companion object {
        /** Fraction of the width, at each edge, that turns a page. */
        const val TAP_ZONE = 0.30f

        const val SMALL_TAP_ZONE = 0.15f

        /** Pixels of scroll before a drag counts as "the reader is reading", and hides the menu. */
        const val MIN_SCROLL_TO_HIDE_MENU = 8f

        /**
         * Below this, a "scroll" is the engine clamping at a document edge, not real movement.
         *
         * In view pixels, read against `WebView.scrollY` before and after a [step]: one rather
         * than zero because a clamped scroll can still settle a fraction of a pixel away from the
         * position it started from.
         */
        const val MIN_MOVEMENT_PX = 1

        /** How far the reader must travel before the position is written again. */
        const val SAVE_THRESHOLD = 0.02f

        /** Last resort for a resource no extension table and no signature could classify. */
        const val OCTET_STREAM = "application/octet-stream"

        /** What a `data:` URI the [NovelInline] inliner embeds always opens with. */
        private const val INLINED_DATA_IMAGE = "data:image"

        /**
         * How often the document may be asked where the reader is.
         *
         * A fling fires the scroll listener per frame; the answer cannot change faster than the
         * document can reflow, and every probe is a script round trip.
         */
        const val PROBE_INTERVAL_MS = 150L

        /**
         * How hard a drag must push before "nowhere left to scroll" counts as trying to get past
         * the end, and how many such pushes in a row it takes.
         *
         * The threshold filters the glide of a drag arriving at the edge; the count asks for a
         * *continued* push — a reader who merely reaches the end of the book does not turn.
         */
        const val STUCK_SCROLL_DELTA = 8f

        /** See [STUCK_SCROLL_DELTA]. */
        const val STUCK_SCROLL_EVENTS = 3

        /** One stuck-edge turn per push: a continuing drag must not machine-gun volume turns. */
        const val STUCK_TURN_COOLDOWN_MS = 750L

        /** The probe's fields, read out of its JSON encoding. */
        val PROBE_POSITION = Regex(""""i":(-?\d+)""")
        val PROBE_FRACTION = Regex(""""f":([0-9.]+)""")
        val PROBE_NEAR_BOTTOM = Regex(""""nb":(\d+)""")
        val PROBE_NEAR_TOP = Regex(""""nt":(\d+)""")
    }
}

/** `#rrggbb` / `#rgb` to a packed colour int, or null when it is not a hex colour. */
internal fun String.toColorIntOrNull(): Int? {
    val hex = trim().removePrefix("#")
    if (hex.length != 3 && hex.length != 6) return null
    val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    val value = expanded.toLongOrNull(16) ?: return null
    return Color.rgb(
        ((value shr 16) and 0xFF).toInt(),
        ((value shr 8) and 0xFF).toInt(),
        (value and 0xFF).toInt(),
    )
}

/**
 * Chooses the novel viewer for text chapters.
 *
 * ### Decided from the loader, not from the page content
 *
 * The question is "is this chapter text?", and [TextPageLoader] is the authoritative answer: it is
 * what [PageLoaderRegistry] selects for a [eu.kanade.tachiyomi.source.text.TextSource] and for nothing
 * else. Deciding from `page.textPage` instead was an indirect signal about the same fact, and it was
 * wrong for two reasons — it needs the page list to exist first, and it reports null for a chapter whose
 * pages have not loaded, which is indistinguishable from "not a text chapter". Both mistakes hand an
 * EPUB to the pager.
 *
 * ### It never declines a text chapter
 *
 * There is no `return null` on the text path, and there must not be one. A null here means the pager
 * gets the chapter, and the pager cannot render text: it would show an empty reader with no error,
 * which is precisely the failure this provider exists to prevent. If the viewer cannot be built, the
 * honest outcome is a crash the user reports, not a blank page that looks like a bug in their book.
 *
 * Returning null is reserved for "this is not a text chapter", which is a different answer entirely.
 */
object NovelViewerProvider : ViewerProvider {
    override val order: Int = -10

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        val chapters = runCatching { activity.viewModel.state.value.viewerChapters }.getOrNull()
        if (chapters == null) {
            logcat {
                "NovelViewerProvider: DECLINED - viewerChapters is null " +
                    "(chapter=${activity.viewModel.state.value.currentChapter?.chapter?.url})"
            }
            return null
        }
        val rawLoader = chapters.currChapter.pageLoader
        val loader = rawLoader as? TextPageLoader
        if (loader == null) {
            // The other decline path, and the one a "why is my book a comic?" report lives or dies by:
            // this provider only ever claims a TextPageLoader, so anything else here means the chapter
            // was routed to the image chain upstream and there is nothing this viewer can do about it.
            logcat {
                "NovelViewerProvider: DECLINED - chapter loader is " +
                    "${rawLoader?.let { it::class.simpleName } ?: "null"}, not a TextPageLoader"
            }
            return null
        }
        logcat {
            "NovelViewerProvider: CLAIMED chapter=${chapters.currChapter.chapter.url} " +
                "resources=${loader.resourceSource?.let { it::class.simpleName } ?: "none"}"
        }

        // Images, stylesheets and fonts are served by the loader that read the chapter, so the archive
        // stays open for exactly as long as the chapter is on screen.
        val resources = loader.resourceSource

        return NovelWebViewer(
            activity = activity,
            resources = resources,
            loader = loader,
            readerPrefs = globalAppGraph.readerPreferences,
            novelPrefs = globalAppGraph.novelTypographyPreferences,
        )
        // KMK --> No typography is handed in here any more: the viewer seeds its own state at
        // construction, because the seed has to merge the general reader theme's background in
        // (see NovelWebViewer.typography) and the provider does not know that rule. Seeding in two
        // places would have meant one of them painting the first page without the merge - the
        // unreadable combination the merge exists to prevent. The first document still renders
        // styled: the seed is set before the viewer is returned to the reader.
        // KMK <--
    }
}
