// Offline compile stubs - never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")
package org.jsoup

import org.jsoup.nodes.Document
import java.io.InputStream

object Jsoup {
    @JvmStatic
    fun parse(input: InputStream, baseUri: String?): Document = Document()
    @JvmStatic
    fun parse(input: InputStream, charsetName: String?, baseUri: String): Document = Document()
    @JvmStatic
    fun parse(html: String): Document = Document()
    @JvmStatic
    fun parse(input: InputStream, charsetName: String?, baseUri: String, parser: Any): Document = Document()
}
