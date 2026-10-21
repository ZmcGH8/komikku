// Offline compile stubs - never shipped. See /tmp/opencode/synchk/check2.sh
@file:Suppress("unused")
package org.jsoup.nodes

class Elements : Iterable<Element> {
    override fun iterator(): Iterator<Element> = emptyList<Element>().iterator()
}

open class Element {
    fun tagName(): String = ""
    fun attr(name: String): String = ""
}

class Document {
    val allElements: Elements = Elements()
    fun getElementsByTag(name: String): Elements = Elements()
    fun text(): String = ""
    fun select(css: String): Elements = Elements()
    fun first(): Element? = null
}
