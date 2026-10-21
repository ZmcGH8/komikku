package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The reader's half of the contract with the document it is showing.
 *
 * A reflowable document has no pages, so the host cannot ask "which page am I on" — it can only ask
 * "how far through this document am I", and that answer has to come from the engine. These are the
 * two scripts that make the conversation possible: [BRIDGE_SCRIPT] installs `window.KmkReader` in
 * every document, and the snippets below are what the host calls into it with.
 *
 * ### What deliberately is not here any more
 *
 * The bridge once also scrolled the document (`scrollViewport`) and reported which end it had run
 * out of room at (`edge`), and the host turned documents on those answers. That made turning depend
 * on the document's own `<script>` having run and on the engine answering host evaluations — and
 * where either did not happen, every tap silently did nothing and the page navigator was the only
 * way from one chapter to the next. Turning is decided natively by the viewer now
 * (`WebView.scrollBy` plus `scrollY`, no scripting involved), and what remains here is only what the
 * *laid-out document* alone can answer: the fraction a reading position is stored as, and the
 * element a contents entry names. Both degrade to nothing when the bridge is absent, which is
 * survivable — a book opens at the top of a section instead of where it was left.
 *
 * ### Scroll fraction, not pixel offset
 *
 * Everything is expressed as a fraction of the scrollable range so a position survives a font-size
 * change, an orientation flip and a page-count change. That is what makes [NovelProgress.Position]
 * restorable — see its own comment for why a page index or a pixel offset is not.
 *
 * ### Kept out of the viewer deliberately
 *
 * The viewer owns *what* is on screen; this owns *how the document is measured*. Every string here is
 * pure, so the parse side — which is where the bugs live, because `evaluateJavascript` hands back JSON
 * rather than a number — can be asserted off-device.
 *
 * No Android imports on purpose.
 */
object NovelScrollBridge {

    /**
     * The bridge itself.
     *
     * `maxScroll` is the whole scrollable range, recomputed on every call: a document reflows while
     * images load, so a value cached at load time is wrong within a second of the book opening.
     *
     * Declared before [BRIDGE_SCRIPT] on purpose — an object's properties initialise in declaration
     * order, so a reader below it would otherwise take this one before it exists.
     */
    private val BRIDGE_SCRIPT_BODY: String =
        """
        (function () {
          if (window.KmkReader) return;
          var d = document;
          var self = {
            maxScroll: function () {
              var el = d.documentElement;
              var body = d.body;
              var height = Math.max(
                el ? el.scrollHeight : 0,
                body ? body.scrollHeight : 0
              );
              return Math.max(0, height - window.innerHeight);
            },
            top: function () {
              var el = d.documentElement;
              var y = window.pageYOffset;
              if (typeof y !== 'number') y = el ? el.scrollTop : 0;
              return y || 0;
            },
            fraction: function () {
              var max = self.maxScroll();
              if (max <= 0) return 0;
              return Math.min(1, Math.max(0, self.top() / max));
            },
            setFraction: function (f) {
              var max = self.maxScroll();
              var target = isFinite(f) ? f : 0;
              target = Math.min(1, Math.max(0, target));
              window.scrollTo(0, Math.round(max * target));
              return true;
            },
            /*
             * Zooming a figure, and the flag the host reads to know the tap was not its own.
             *
             * In place rather than in a viewer of its own, because the engine already holds the
             * decoded bitmap: a CSS transform costs nothing, cannot run out of memory on a long
             * illustrated book, and keeps the reader's place in the text. It also works for an inline
             * `<svg>`, which an extract-and-decode path would have to render first.
             *
             * The transform origin is the point that was tapped, so the pixel under the finger stays
             * put — zooming to the element's own top-left instead throws away whatever the reader was
             * looking at, which for a tall plate is most of it.
             */
            zoomImage: function (img, cx, cy) {
              var box = img.getBoundingClientRect();
              var ox = ((cx - box.left) / Math.max(1, box.width)) * 100;
              var oy = ((cy - box.top) / Math.max(1, box.height)) * 100;
              if (img.classList.contains('${ZOOMED_CLASS}')) {
                img.classList.remove('${ZOOMED_CLASS}');
                img.style.transform = '';
                img.style.transformOrigin = '';
                return false;
              }
              img.style.transformOrigin = ox + '% ' + oy + '%';
              img.style.transform = 'scale(${ZOOM_SCALE})';
              img.classList.add('${ZOOMED_CLASS}');
              return true;
            },
            /*
             * Consumes the "a figure answered this tap" flag.
             *
             * Read-and-cleared rather than just read, because the host asks once per tap and a flag
             * left set would make the *next* tap scroll nothing. Returns false when the bridge is
             * absent, which is the host's signal to carry on and scroll as it always did.
             */
            takeImageTap: function () {
              var hit = self.imageTap;
              self.imageTap = false;
              return hit === true;
            }
          };
          window.KmkReader = self;
          /*
           * Capture phase, so a publisher listener on the same element cannot claim the tap first
           * and leave the reader tapping a figure that does nothing.
           *
           * Only an `<img>` is zoomed. The document is still fully scrollable and text is still
           * selectable, so this is one extra meaning for one element rather than a mode the reader
           * has to enter and leave.
           */
          d.addEventListener('click', function (e) {
            var el = e.target;
            if (!el || el.nodeName !== 'IMG') return;
            e.preventDefault();
            e.stopPropagation();
            self.imageTap = true;
            self.zoomImage(el, e.clientX, e.clientY);
          }, true);
        })();
        """

    /** Installed into every document's head; defines `window.KmkReader`. */
    val BRIDGE_SCRIPT: String = BRIDGE_SCRIPT_BODY.trimIndent()

    /** Marks a figure the reader has zoomed; the stylesheet gives it the rules that come with it. */
    const val ZOOMED_CLASS: String = "kmk-zoomed"

    /**
     * How far a tapped figure grows.
     *
     * Fixed rather than a pinch gesture, deliberately: the WebView already owns pinch for the
     * viewport, and a book that is 3× further away is still a book. Tapping the same figure again
     * puts it back, which is why there is no way to end up zoomed into something you cannot leave.
     */
    private const val ZOOM_SCALE = 3

    /**
     * Whether a tap was a figure answering it.
     *
     * The host asks this before scrolling, and `false` — the answer when the bridge is missing, when
     * scripting is off, or when the document cannot answer — sends the tap on to the scroll path
     * unchanged. Every failure mode of the zoom therefore lands on the behaviour that already
     * worked, which is the only acceptable direction for this to fail in.
     */
    const val TAKE_IMAGE_TAP: String =
        "(window.KmkReader && window.KmkReader.takeImageTap) ? window.KmkReader.takeImageTap() : false"

    /** Current scroll position as a fraction of the scrollable range, 0..1. */
    const val FRACTION: String = "(window.KmkReader && window.KmkReader.fraction()) || 0"

    /** Restores a previously reported fraction. Ignored until the document has laid out. */
    const val SET_FRACTION: String =
        "(function(){var r=window.KmkReader;if(!r)return false;r.setFraction(%f);return true;})()"

    /**
     * Scrolls to the element a table-of-contents entry names.
     *
     * Both spellings are checked because publishers use both and the href says which is which in
     * practice only by the book being well made: `id` is XHTML, `name` on an `<a>` is the HTML 4
     * habit that a great deal of EPUB2-era content still uses.
     *
     * The measurement is taken **twice**. `onPageFinished` fires before layout settles for a document
     * whose illustrations are still decoding, and [NovelInline] hands the engine `<img>` tags with a
     * `data:` URI and no `width`/`height` — zero height until decoded. A section below an
     * illustration therefore measures short, and a scroll landed once would sit *before* the entry
     * as the images above it inflate underneath a fixed scroll offset. So the anchor scrolls as soon
     * as it can (the reader must not watch an unscrolled document while images decode), and again
     * once the document's images have all finished, re-measuring against the layout that then exists.
     * A doc that answers and then settles over a couple of hundred milliseconds is imperceptible; an
     * entry that lands a screen away from where it pointed is not.
     */
    const val SCROLL_TO_ANCHOR: String =
        "(function(id,offset){" +
            "function go(){" +
            "var el=null;try{el=document.getElementById(id);}catch(e){el=null;}" +
            "if(!el){var as=document.getElementsByTagName('a');" +
            "for(var i=0;i<as.length;i++){if(as[i].getAttribute('name')===id){el=as[i];break;}}}" +
            "if(!el){window.scrollTo(0,0);return false;}" +
            "var top=el.getBoundingClientRect().top+window.pageYOffset-(offset||0);" +
            "window.scrollTo(0,Math.max(0,top));return true;}" +
            "if(!go())return false;" +
            "var imgs=document.images,w=[];" +
            "for(var i=0;i<imgs.length;i++){if(imgs[i].decode)w.push(imgs[i].decode());}" +
            "Promise.all(w).then(go,go);" +
            "return true;})(%s,%d)"

    /**
     * [SCROLL_TO_ANCHOR] with the fragment id already quoted as a JS string literal.
     *
     * `%d` is substituted **first**, and the order is load-bearing rather than cosmetic. These are
     * sequential literal replaces, and the second one scans the string the first already produced — so
     * doing `%s` first would rewrite the untrusted fragment text itself. A TOC id containing `%d`
     * would come out altered (`a%db` becomes `a32b`), the `getElementById` lookup misses, and the
     * reader silently lands at the top of the document with nothing to explain it.
     *
     * Doing `%d` first is safe because at that moment the string contains only the template's own
     * placeholder: the fragment has not been inserted yet, and the substituted digits cannot be
     * re-scanned afterwards because `%s` is a single replacement that does not rescan its result.
     */
    fun scrollToAnchor(fragment: String, topInsetPx: Int): String =
        SCROLL_TO_ANCHOR.replace("%d", topInsetPx.toString()).replace("%s", quoteForJs(fragment))

    /**
     * Encodes [value] as a JavaScript string literal.
     *
     * Hand-built rather than delegated to a JSON library because this runs on the host, where the
     * only JSON dependency is the extension API's serializer — and because the value is *not
     * trusted*: it comes from the book's own table of contents, so a fragment named
     * `"); alert(1); //` is a real input, not a hypothetical one.
     *
     * Also escapes the two sequences that can close an enclosing element, `</` and U+2028/U+2029,
     * which are line terminators to a JS parser but not to [String.trimIndent]'s block handling —
     * getting either wrong turns a contents entry into a script injection.
     */
    fun quoteForJs(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c.code < 0x20) {
                    append("\\u").append(String.format("%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * Parses a value out of an `evaluateJavascript` callback.
     *
     * The callback receives the JSON encoding of the script's return value, so a script returning the
     * number 0 arrives as the four characters `0` and one returning `NaN` arrives as the five
     * `null`. Both must read as "no movement", or the reader turns the page every time a tap lands on
     * a document that is not scrollable.
     */
    fun number(raw: String?): Double {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return 0.0
        return trimmed.toDoubleOrNull() ?: 0.0
    }
}
