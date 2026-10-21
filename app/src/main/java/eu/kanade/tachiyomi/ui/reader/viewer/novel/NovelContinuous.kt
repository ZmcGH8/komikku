// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The book as one continuous scroll: the pieces of it that are pure strings and string surgery.
 *
 * ### What "continuous" means here, and why
 *
 * A book is one library chapter whose *sections* are its spine documents, and a reader moving
 * through it does not experience "next section" as a jump: the manga reader they are used to is
 * the continuous one, where scrolling never stops at a chapter boundary. So the live document
 * grows: while the reader is still a screen or so from the bottom, the *next* section's content is
 * appended into the same DOM; scrolling up past the top prepends the previous section, with the
 * scroll position compensated inside the same synchronous script so no frame ever shows the
 * shift. A document is a window around the section being read — trimmed at both ends as the
 * reader moves — and everything else (taps, the buttons, the contents sheet, seeking) still works
 * exactly as it does on a single document.
 *
 * ### The segment marker
 *
 * Every section in the DOM — including the one the document was loaded with — sits inside one
 * `kmk-seg` element carrying its position in the reader's page list (`data-i`) and its archive
 * path (`data-u`). The probe walks those markers to answer the only question the host cannot
 * answer from its own state: *which* section is under the viewport, and how far into it. That is
 * what drives the page counter, the saved position, the chapter-progress slider, and where the
 * next append/prepend happens.
 *
 * ### What only the engine can measure
 *
 * The probe and the seek are `evaluateJavascript` round trips on purpose: segment bounds live in
 * laid-out CSS pixels, and this folder has already learned (see `step`) not to guess the engine's
 * pixel arithmetic from outside. Both degrade to no-ops when scripting is unavailable — the reader
 * loses live progress and the continuous appends, never the text.
 *
 * Every string here is pure; `DocumentHarness` asserts the marker format and the fragment
 * extraction. The orchestration — when to append, what to trim — lives in [NovelWebViewer].
 */
object NovelContinuous {

    /** The class every segment wrapper carries. */
    const val SEGMENT_CLASS = "kmk-seg"

    /**
     * How far ahead of the reader a segment may sit before it is trimmed from the window.
     *
     * Two in each direction: far enough that a fast fling through a short section does not run off
     * the end before the next append lands, near enough that a long session's DOM stays bounded.
     */
    const val WINDOW_EDGE = 2

    /** Marks [inner] — one section's body content — as the segment at [position]. */
    fun wrap(position: Int, url: String, inner: String): String =
        openTag(position, url) + inner + CLOSE

    /** The opening half of [wrap], for the document [NovelDocument.build] loads directly. */
    fun openTag(position: Int, url: String): String =
        """<div class="$SEGMENT_CLASS" data-i="$position" data-u="${escapeAttribute(url)}">"""

    /** The closing half of [wrap]. */
    const val CLOSE = "</div>"

    private fun escapeAttribute(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append('&').append("amp;")
                '"' -> append('&').append("quot;")
                '<' -> append('&').append("lt;")
                '>' -> append('&').append("gt;")
                else -> append(c)
            }
        }
    }

    /**
     * One section's body content, extracted from its markup for appending.
     *
     * Head-borne rules (the section's own `<style>` blocks, stylesheets already inlined as such by
     * [NovelInline]) travel inside the fragment — a `<style>` element is valid anywhere and applies
     * to the whole document, which for sections of the same book is what their author intended.
     * Anything before `<body>` that is not a style is dropped: the live document already has its
     * charset, its base and the reader's own rules.
     *
     * ### Why this was measured wrong, and the text jumped
     *
     * The first version took the body alone, which is not what the paragraph above describes, and
     * for a book whose sections carry their own stylesheet — a web-novel source hands over one HTML
     * document per chapter, and each has its own — an appended section rendered in the *previous*
     * section's type. Different font size means a different line count for the same text, so the
     * section was not the height the scroll arithmetic had assumed, and the compensation landed the
     * reader somewhere other than where they were.
     *
     * Styles are read from the markup **before** `<body>` only, so a `<style>` the publisher put in
     * the body is not collected twice.
     */
    fun bodyFragment(markup: String): String {
        val body = BODY.find(markup) ?: return markup
        val inner = body.groupValues[1].ifBlank { return markup }
        val head = markup.substring(0, body.range.first)
        return HEAD_STYLE.findAll(head).joinToString("") { it.value } + inner
    }

    private val BODY = Regex("""<body\b[^>]*>([\s\S]*)</body\s*>""", RegexOption.IGNORE_CASE)

    /** A complete `<style>` element, closing tag included — a self-closing form is not a thing. */
    private val HEAD_STYLE = Regex("""<style\b[^>]*>[\s\S]*?</style\s*>""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- scripts

    /**
     * Reports the section under the viewport and the reading state around it.
     *
     * Returns an object the host parses as JSON — `evaluateJavascript` delivers a script's return
     * value already JSON-encoded, so an object arrives as one and a hand-built string would arrive
     * twice-encoded. Null when the document carries no segments at all, which is the moment
     * before the first load.
     *
     * Measured with `getBoundingClientRect().top + pageYOffset` rather than `offsetTop`: body
     * margins are part of the document's coordinate space and outside the offsetParent chain, and
     * the day-old lesson of `scrollToAnchor` applies to segments too.
     */
    const val PROBE: String = """
        (function(){
          var segs = document.querySelectorAll('.kmk-seg');
          if (segs.length === 0) return null;
          var y = window.pageYOffset;
          function top(el){ return el.getBoundingClientRect().top + window.pageYOffset; }
          var cur = segs[0];
          for (var i = 0; i < segs.length; i++) {
            if (top(segs[i]) <= y + 1) cur = segs[i]; else break;
          }
          var t = top(cur), h = Math.max(1, cur.offsetHeight);
          var f = Math.min(1, Math.max(0, (y - t) / h));
          var vh = window.innerHeight;
          var el = document.documentElement, b = document.body;
          var total = Math.max(el ? el.scrollHeight : 0, b ? b.scrollHeight : 0);
          return {
            i: parseInt(cur.getAttribute('data-i'), 10) || 0,
            f: f,
            nb: (total - y - vh) < vh * 1.5 ? 1 : 0,
            nt: y < vh ? 1 : 0
          };
        })()
    """

    /**
     * Appends one section's [html] as the segment at [position].
     *
     * `innerHTML` on a detached element on purpose: content inserted that way does not execute
     * scripts, which is exactly the property wanted when the content is a file the user downloaded.
     *
     * [epoch] is the request stamp the script is verified against. A growth script can outlive the
     * document it was built for — a jump or a turn re-renders while its read is in flight, and the
     * document it hands its fragment to may no longer be the one it was grown for. The document
     * declares its own epoch ([NovelDocument.build] sets `window.KmkEpoch`), and a script that finds
     * the two disagree is dropped: it answers `false` instead of inserting, rather than adding a
     * section of somebody else's chapter to a window that just ended. See [isStale] for where the
     * answer is read back.
     */
    fun appendScript(html: String, position: Int, url: String, epoch: Int = 0): String = """
        (function(epoch){
          if (window.KmkEpoch !== epoch) return false;
          var div = document.createElement('div');
          div.className = '$SEGMENT_CLASS';
          div.setAttribute('data-i', '$position');
          div.setAttribute('data-u', ${NovelScrollBridge.quoteForJs(url)});
          div.innerHTML = ${NovelScrollBridge.quoteForJs(html)};
          document.body.appendChild(div);
          return true;
        })($epoch)
    """

    /**
     * Prepends one section's [html] as the segment at [position].
     *
     * ### Why this is not a synchronous insert
     *
     * The obvious version inserts and compensates by `div.offsetHeight` in one go, and it is wrong
     * in a way that only shows in an illustrated book. [NovelInline] rewrites every `<img>` to a
     * `data:` URI and deliberately leaves no `width`/`height` behind, so an image occupies **zero
     * height until it has decoded**. Read in the same tick as the insert, a section with plates in
     * it measures short by the height of all of them.
     *
     * So the insert is compensated twice. The node goes in hidden and is measured immediately,
     * which holds the painted text exactly where it is; its images are then decoded and it is
     * measured again, and the difference is compensated too. Revealing only after that means the
     * growth the reader would otherwise watch happen above them happens behind `visibility: hidden`,
     * and by the time anything is painted the offset has been made good.
     *
     * Compensation is also forced to `scroll-behavior: auto` for its duration. A publisher
     * stylesheet that asks for smooth scrolling turns `scrollBy` into an animation, and a
     * compensation that animates is the opposite of what it is for.
     *
     * The script still returns `true` synchronously, because the host takes its callback from that
     * return value and must not wait on a book's images to update its window bookkeeping.
     */
    fun prependScript(html: String, position: Int, url: String, epoch: Int = 0): String = """
        (function(epoch){
          if (window.KmkEpoch !== epoch) return false;
          var div = document.createElement('div');
          div.className = '$SEGMENT_CLASS';
          div.setAttribute('data-i', '$position');
          div.setAttribute('data-u', ${NovelScrollBridge.quoteForJs(url)});
          div.innerHTML = ${NovelScrollBridge.quoteForJs(html)};
          div.style.visibility = 'hidden';
          document.body.insertBefore(div, document.body.firstChild);
          jump(div.offsetHeight);
          var imgs = div.querySelectorAll('img');
          var waits = [];
          for (var i = 0; i < imgs.length; i++) {
            if (imgs[i].decode) waits.push(imgs[i].decode());
          }
          Promise.all(waits).then(reveal, reveal);
          return true;

          function jump(px) {
            if (!px) return;
            var root = document.documentElement;
            var was = root.style.scrollBehavior;
            root.style.scrollBehavior = 'auto';
            window.scrollBy(0, px);
            root.style.scrollBehavior = was;
          }
          function reveal() {
            jump(div.offsetHeight);
            div.style.visibility = '';
          }
        })($epoch)
    """

    /**
     * Removes the segment at [position]; [compensate] holds the viewport still for a removal above
     * it (the content below moves up by exactly the removed height), and is false for one below,
     * where removing content cannot move what is on screen.
     *
     * [epoch] is the same request stamp [appendScript] and [prependScript] carry: a trim whose
     * document has been replaced must not remove a segment from the new one — which, for a jump
     * *to* the position being trimmed, is a segment the new window legitimately contains. The
     * host-side window bookkeeping (`windowFirst`/`windowLast`) is only ever moved by trims run
     * from the live document's own probe, so a stale trim needs no read-back here; the script
     * refusing to run is the whole guard.
     */
    fun trimScript(position: Int, compensate: Boolean, epoch: Int = 0): String = """
        (function(epoch){
          if (window.KmkEpoch !== epoch) return false;
          var el = document.querySelector('.kmk-seg[data-i="$position"]');
          if (!el || !el.parentNode) return false;
          var h = el.offsetHeight;
          el.parentNode.removeChild(el);
          if ($compensate) {
            // Same reasoning as the prepend: a publisher asking for smooth scrolling would animate
            // a jump that exists only to hold the reader still.
            var root = document.documentElement;
            var was = root.style.scrollBehavior;
            root.style.scrollBehavior = 'auto';
            window.scrollBy(0, -h);
            root.style.scrollBehavior = was;
          }
          return true;
        })($epoch)
    """

    /**
     * Scrolls to [fraction] of the section under the viewport — the chapter-progress slider's seek.
     *
     * Fraction-of-segment rather than fraction-of-document, because the document is now several
     * sections tall and "where am I in this chapter" is the question the slider is drawn to answer.
     */
    fun seekScript(fraction: Float): String = """
        (function(f){
          var segs = document.querySelectorAll('.kmk-seg');
          if (segs.length === 0) return false;
          var y = window.pageYOffset;
          function top(el){ return el.getBoundingClientRect().top + window.pageYOffset; }
          var cur = segs[0];
          for (var i = 0; i < segs.length; i++) {
            if (top(segs[i]) <= y + 1) cur = segs[i]; else break;
          }
          window.scrollTo(0, Math.round(top(cur) + f * cur.offsetHeight));
          return true;
        })(${fraction.coerceIn(0f, 1f)})
    """

    /**
     * Whether the answer an `evaluateJavascript` callback received came from a growth or trim script
     * that refused to run — the `false` a stale epoch stamps out, as the JSON string the bridge
     * hands back.
     *
     * A growth script that already left for the renderer cannot be cancelled: the job is, but the
     * script is not, and it may run after a jump has replaced the document. Its window bookkeeping
     * belongs to the window that just ended, so the callback drops it instead of moving
     * `windowFirst`/`windowLast` of a window that no longer exists.
     */
    fun isStale(answer: String?): Boolean = answer?.trim() == "false"
}
// KMK <--
