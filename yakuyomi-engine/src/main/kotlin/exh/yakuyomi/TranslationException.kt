package exh.yakuyomi

/**
 * Raised when a translation provider cannot produce output and offline fallback is disabled.
 *
 * Throwing (rather than returning null) is deliberate: the caller marks the page FAILED so it can
 * be retried, as opposed to SKIPPED, which would silently drop the page.
 */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
