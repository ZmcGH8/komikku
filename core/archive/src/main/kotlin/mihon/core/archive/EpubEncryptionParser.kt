package mihon.core.archive

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Parser for `META-INF/encryption.xml`.
 *
 * Its presence is the only thing that distinguishes a font-obfuscated book from a DRM-protected
 * one, and the two need opposite handling:
 *
 * - **Font obfuscation** (`http://www.idpf.org/2007/embed`) XORs the first 1040 bytes of an embedded
 *   font with a key derived from its obfuscation ID. The text is untouched and the book reads
 *   normally; only the publisher's font anti-scraping is in play. Ignored.
 * - **Real DRM** (`rsa-1.5`, `aes256-cbc`, …) encrypts the content documents themselves with a key
 *   the reader is not given. Those chapters are unreadable, so the host must refuse the book with a
 *   clear message rather than opening it and showing a run of blank pages.
 *
 * A book that declares encryption but is missing `META-INF/encryption.xml` is not reported as DRM:
 * a missing declaration means no key was distributed, so the files are plain.
 */
internal object EpubEncryptionParser {

    /** The font-obfuscation algorithm URI. Everything else is treated as real DRM. */
    private const val ALGO_FONT_EMBED = "http://www.idpf.org/2007/embed"

    fun parse(input: InputStream): EpubEncryption {
        val obfuscated = mutableListOf<String>()
        val drm = mutableListOf<String>()

        val xml = EpubXmlReader(input)
        var currentAlgorithm: String? = null
        var currentUri: String? = null
        var event = xml.next()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when {
                    xml.matches(EpubXmlReader.NS_ENCRYPTION, "EncryptionMethod") ->
                        currentAlgorithm = xml.attrOrEmpty("Algorithm")

                    xml.matches(EpubXmlReader.NS_ENCRYPTION, "CipherReference") ->
                        currentUri = xml.attrOrEmpty("URI").ifEmpty { null }
                }
            } else if (event == XmlPullParser.END_TAG) {
                // The entry is complete: EncryptionMethod followed by CipherData/CipherReference.
                val uri = currentUri
                if (uri != null) {
                    val isFontOnly = currentAlgorithm?.startsWith(ALGO_FONT_EMBED) == true
                    if (isFontOnly) obfuscated += uri else drm += uri
                }
                currentAlgorithm = null
                currentUri = null
            }
            event = xml.next()
        }

        return EpubEncryption(obfuscated, drm)
    }

    /**
     * True when [encryption] locks any document in [paths].
     *
     * DRM entries are stored as URIs relative to the archive root and sometimes as a prefix with a
     * trailing wildcard — `OEBPS/text/` plus an asterisk — so a plain equality check is not enough.
     * Font obfuscation is never reported here: an obfuscated font does not stop a chapter opening.
     */
    fun affects(encryption: EpubEncryption, paths: Collection<String>): Boolean =
        encryption.drmProtected.any { protectedUri ->
            val cleaned = protectedUri.substringBefore('#').trimEnd('/')
            if (cleaned.endsWith("*")) {
                paths.any { EpubPaths.isUnder(cleaned.trimEnd('*'), it) }
            } else {
                paths.any { EpubPaths.isUnder(EpubPaths.normalize(cleaned), it) }
            }
        }
}
