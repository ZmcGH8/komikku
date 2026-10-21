package eu.kanade.tachiyomi.source.text

/**
 * A [TextSource] that keeps its books mapped and therefore has to be told when a chapter is over.
 *
 * ### Why this exists
 *
 * An EPUB cannot be re-opened and re-parsed for every one of its images, so a reader holds the
 * archive mapped — a memory map and a file descriptor per open book. That is a lease, and a lease
 * needs a release. Without one, a session that reads fifty books holds fifty maps for the lifetime
 * of the process, and the user is the only thing that ever frees them, by killing the app.
 *
 * The release has to come from somewhere with a real answer to "is this chapter finished?", and there
 * is exactly one: the loader, whose lifetime *is* the chapter's. A viewer cannot supply it — a
 * viewer is rebuilt on an orientation change while the chapter stays open — and the source cannot,
 * because it is asked for pages rather than told when they are no longer wanted.
 *
 * ### Why an interface and not a cast to a built-in type
 *
 * The loader holds a [TextSource], because that is all the extension API promises. Narrowing it to a
 * concrete class would make the built-in EPUB library the only text chapter the reader can open,
 * which is exactly the mistake the `TextBaseUrlSource` rule exists to prevent. A narrow optional
 * interface lets any source that maps its own archives opt in, and leaves every extension that does
 * not completely unaffected.
 *
 * ### Safe to call at any time
 *
 * Both methods may be called while a renderer is still streaming resources out of the book. An
 * implementation must defer the unmap until the last open stream closes rather than closing
 * immediately — reading unmapped memory is a `SIGSEGV` in native code, with no catchable exception
 * and no log line, on a device, in a book, while the user is reading.
 *
 * @since komikku/extensions-lib 1.7
 */
interface EpubArchiveSource {

    /**
     * Retires the archive backing [chapterUrl].
     *
     * Idempotent, and safe to call for a chapter that was never opened.
     */
    fun releaseResourceReader(chapterUrl: String)

    /** Retires every archive this source holds open. */
    fun releaseAllResourceReaders()
}
