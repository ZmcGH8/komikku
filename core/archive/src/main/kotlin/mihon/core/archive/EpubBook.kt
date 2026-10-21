package mihon.core.archive

/**
 * A parsed EPUB: everything the reader and the host need, resolved up front so that neither has to
 * re-parse the container document on every access.
 *
 * All [href][EpubSpineItem.href] values in this model are **archive-relative paths** using `/`
 * separators and no leading slash, which is the form [EpubReader.getInputStream] accepts. That
 * normalisation happens exactly once, at parse time — see [EpubPaths.resolve].
 */
data class EpubBook(
    val metadata: EpubMetadata,
    val manifest: Map<String, EpubResource>,
    val spine: List<EpubSpineItem>,
    val toc: List<EpubTocNode>,
    val encryption: EpubEncryption,
) {
    /**
     * Spine items a reader should actually show, in reading order.
     *
     * Excludes non-linear items (`linear="no"`: publisher ads, footnotes) and anything that is not
     * a content document. This is the list a chapter list is built from.
     */
    val readableSpine: List<EpubSpineItem>
        get() = spine.filter { it.linear && it.isContentDocument }

    /** The table of contents flattened depth-first, for a flat navigation list. */
    val flatToc: List<EpubTocNode> get() = toc.flatMap { it.flatten() }

    /**
     * Href of the cover image, resolved from the manifest.
     *
     * Checked in the order the spec prefers: an explicit `cover-image` property, then the legacy
     * `<meta name="cover" content="…">` indirection that EPUB 2 producers emit, then an item whose
     * id or file name says "cover" — the last one is a heuristic and is only reached for books that
     * declare neither.
     */
    val coverHref: String?
        get() {
            manifest.values.firstOrNull { EpubResource.PROPERTY_COVER_IMAGE in it.properties }?.let {
                return it.href
            }
            metadata.coverManifestId?.let { id -> manifest[id]?.let { return it.href } }
            return manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") &&
                    (it.id.equals("cover", true) || it.href.substringAfterLast('/').startsWith("cover", true))
            }?.href
        }
}

/** Dublin Core and package metadata read from an OPF `<metadata>` block. */
data class EpubMetadata(
    val title: String? = null,
    /** Additional title parts, usually the subtitle. */
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val description: String? = null,
    /** Raw `dc:date`, unparsed. Publishing dates in EPUBs are not reliably ISO-8601. */
    val date: String? = null,
    val rights: String? = null,
    val subjects: List<String> = emptyList(),
    /** Manifest id referenced by `<meta name="cover">`, or null. */
    val coverManifestId: String? = null,
    /** Manifest id of the NCX, from `<spine toc="…">`. */
    val ncxId: String? = null,
    val identifier: String? = null,
) {
    /** Title and subtitle joined for display, falling back to the title alone. */
    val displayTitle: String? = listOfNotNull(title, subtitle)
        .filter { it.isNotBlank() }
        .joinToString(": ")
        .ifBlank { null }
}

/** One `<item>` in the OPF manifest. */
data class EpubResource(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: List<String> = emptyList(),
) {
    val isNavigation: Boolean get() = PROPERTY_NAV in properties

    companion object {
        const val PROPERTY_COVER_IMAGE = "cover-image"
        const val PROPERTY_NAV = "nav"
    }
}

/** One `<itemref>` in the OPF spine, resolved against the manifest. */
data class EpubSpineItem(
    val id: String,
    /** Archive path of the document, fragment removed. */
    val href: String,
    val mediaType: String,
    /** False for `linear="no"` — supplementary content the publisher excluded from the flow. */
    val linear: Boolean = true,
    val properties: List<String> = emptyList(),
    /** `<title>` of the item's own document, when it has one. */
    val title: String? = null,
    /** Per-item language override, for the rare book that switches mid-file. */
    val language: String? = null,
) {
    val isContentDocument: Boolean get() = mediaType in CONTENT_MEDIA_TYPES

    companion object {
        val CONTENT_MEDIA_TYPES = setOf(
            "application/xhtml+xml",
            "text/html",
        )
    }
}

/** A table-of-contents entry. Mirrors [eu.kanade.tachiyomi.source.text.TocEntry] but stays in
 *  core so that `core:archive` does not depend on the extension API. */
data class EpubTocNode(
    val label: String,
    /** Archive path the entry points at. */
    val href: String,
    /** Fragment within [href], without the `#`. Null when the entry targets the whole file. */
    val fragment: String? = null,
    val children: List<EpubTocNode> = emptyList(),
) {
    /** This node and every descendant, depth-first. */
    fun flatten(): List<EpubTocNode> = buildList {
        fun walk(node: EpubTocNode) {
            add(node)
            node.children.forEach(::walk)
        }
        walk(this@EpubTocNode)
    }

    /** The `href#fragment` form, which is how a reader addresses a position inside a document. */
    val fullHref: String get() = if (fragment != null) "$href#$fragment" else href
}

/**
 * What `META-INF/encryption.xml` declares.
 *
 * Most books with an `encryption.xml` use it only to obfuscate embedded **fonts** (the
 * `http://www.idpf.org/2007/embed` algorithm), which is a copy-protection measure a reader can
 * ignore. Real DRM encrypts content documents with a key the reader cannot obtain, and cannot be
 * read at all.
 */
data class EpubEncryption(
    /** Entries encrypted with font obfuscation — ignorable. */
    val obfuscatedFonts: List<String> = emptyList(),
    /** Entries encrypted with a real DRM scheme — the book cannot be read. */
    val drmProtected: List<String> = emptyList(),
) {
    val hasDrm: Boolean get() = drmProtected.isNotEmpty()
    val isEmpty: Boolean get() = obfuscatedFonts.isEmpty() && drmProtected.isEmpty()

    companion object {
        val NONE = EpubEncryption()
    }
}
