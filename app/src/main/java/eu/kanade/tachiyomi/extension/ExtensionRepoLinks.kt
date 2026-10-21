package eu.kanade.tachiyomi.extension

import mihon.domain.extension.model.ExtensionStore

/**
 * Works out where to send someone who wants to report a broken extension.
 *
 * Every repository publishes a raw index file, so its URL already names the GitHub account that owns
 * it. Deriving the search from that account is what keeps a report off this project's own tracker: a
 * bare search for an extension name would otherwise offer the app's own issues as a target, where the
 * problem cannot be fixed.
 */
internal object ExtensionRepoLinks {

    private val HOSTS = listOf("raw.githubusercontent.com", "githubusercontent.com", "github.com")

    /**
     * The GitHub account that owns the repository, taken from its index URL.
     *
     * Covers the raw host (`raw.githubusercontent.com/<owner>/<repo>/index.json`), the blob host, and a
     * plain `github.com/<owner>/<repo>` URL, since repositories are entered by hand.
     */
    fun githubOwner(store: ExtensionStore): String? =
        githubOwnerFromUrl(store.indexUrl)
            ?: store.extensionListUrl?.let(::githubOwnerFromUrl)
            ?: githubOwnerFromUrl(store.contact.website)

    private fun githubOwnerFromUrl(url: String): String? {
        val host = HOSTS.firstOrNull { url.startsWith("https://$it/", ignoreCase = true) }
            ?: return null
        val owner = url.substringAfter("//$host", "").trim('/').substringBefore('/')
        return owner.takeIf { it.isNotBlank() && it.none { c -> c.isWhitespace() || c == '.' } }
    }

    /**
     * A GitHub issue search for the extension, scoped to its owner when that is known.
     *
     * Issues rather than repositories, since reporting on the extension is the goal. With no owner to
     * scope to, the search still excludes this project's own tracker.
     */
    fun githubIssueSearchUrl(extensionName: String, store: ExtensionStore?): String {
        val owner = store?.let(::githubOwner)
        val query = buildString {
            append('"')
            append(extensionName.trim())
            append('"')
            owner?.let {
                append(" user:")
                append(it)
            }
            if (owner == null) append(" -user:PineappleTwilight")
        }
        return "https://github.com/search?q=${query.urlEncode()}&type=issues"
    }

    private fun String.urlEncode(): String = buildString {
        this@urlEncode.forEach { c ->
            when {
                c.isLetterOrDigit() && c.code < 128 -> append(c)
                c in "-_.~" -> append(c)
                c == ' ' -> append('+')
                else -> {
                    c.toString().toByteArray().forEach {
                        append('%')
                        append("%02X".format(it.toInt() and 0xFF))
                    }
                }
            }
        }
    }
}
