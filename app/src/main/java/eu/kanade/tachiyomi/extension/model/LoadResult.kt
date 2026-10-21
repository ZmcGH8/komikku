package eu.kanade.tachiyomi.extension.model

sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult
    data class Untrusted(val extension: Extension.Untrusted) : LoadResult

    /**
     * Identity is carried alongside the reason so a failure can be shown against its extension and
     * reported to the right repository; the reason on its own could not be attributed to anything.
     *
     * [cause] is set only where something actually threw. The loader also refuses to load an
     * extension for reasons that are not faults at all - an unsigned or absent package, an
     * unsupported lib version, NSFW content being switched off - and those only ever reach this as
     * a logged line. Distinguishing the two here is what lets the error tracker act on real
     * exceptions instead of on the mere fact that a message was written at error level.
     */
    data class Error(
        val reason: String? = null,
        val cause: Throwable? = null,
        val pkgName: String? = null,
        val extensionName: String? = null,
        val versionName: String? = null,
        val versionCode: Long = 0L,
        val signatureHash: String? = null,
        val storeName: String? = null,
    ) : LoadResult
}
