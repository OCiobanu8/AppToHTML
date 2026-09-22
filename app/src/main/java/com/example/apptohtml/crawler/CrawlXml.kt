package com.example.apptohtml.crawler

import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The XML primitives shared by the crawl serializer and reader.
 *
 * These existed as private copies on both sides. They are here so the *same* escaping and the
 * *same* child lookup back every element the two halves round-trip through — a divergence between
 * them would be invisible in either file alone and would only surface as a value that survives a
 * write but not the following read.
 */

/** Escapes [input] for use in an XML attribute value or text node. */
internal fun escapeXml(input: String): String = buildString(input.length) {
    input.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            }
        )
    }
}

/** The attribute [name] when present and non-empty, else null. */
internal fun Element.optionalAttributeOrNull(name: String): String? {
    if (!hasAttribute(name)) return null
    return getAttribute(name).takeIf { it.isNotEmpty() }
}

/** The first direct child element named [tag], or null. */
internal fun Element.firstChildElement(tag: String): Element? {
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tag) {
            return node
        }
    }
    return null
}

/** Every direct child element named [tag], in document order. */
internal fun Element.childElementsNamed(tag: String): List<Element> {
    val result = mutableListOf<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tag) {
            result += node
        }
    }
    return result
}

/** Every direct child element, in document order, whatever its tag. */
internal fun Element.childElements(): List<Element> {
    val result = mutableListOf<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            result += node as Element
        }
    }
    return result
}

/**
 * A persisted screen identity that cannot be rebuilt as written.
 *
 * Distinct from a parse failure: the XML is well-formed, but something it asserts is not a thing an
 * identity can be. Carries where, because "invalid" without a location is not actionable by the
 * operator who hand-edited the file.
 */
internal class MalformedScreenIdentity(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
