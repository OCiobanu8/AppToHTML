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

/**
 * Escapes [input] for use in an XML attribute value or text node.
 *
 * Line breaks and tabs are written as character references because an XML parser normalises a raw
 * one inside an attribute value to a space: a two-line label would otherwise come back as one line,
 * and an identity settled on it would no longer hold on its own reloaded capture.
 *
 * Text is passed through [xmlRepresentable] first, so a character XML 1.0 cannot carry is written as
 * U+FFFD rather than making the whole file unreadable.
 */
internal fun escapeXml(input: String): String = buildString(input.length) {
    xmlRepresentable(input).forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            '\t' -> append("&#9;")
            else -> append(char)
        }
    }
}

/**
 * [input] with every character XML 1.0 cannot carry at all — a control character other than tab, line
 * feed and carriage return, U+FFFE or U+FFFF, or half of a surrogate pair — replaced by U+FFFD. A
 * complete surrogate pair (an emoji) is kept.
 *
 * The one home of that rule. It is applied wherever text enters the crawler from the device — every
 * tree the crawl receives, and the live attributes a click is matched against — as well as by
 * [escapeXml]. That is what keeps a screen's saved capture and the crawler's own view of it the same
 * text: if only the writer replaced these characters, a fresh crawl and a resumed one would judge
 * different labels.
 */
internal fun xmlRepresentable(input: String): String {
    if (input.none(::isUnrepresentableCandidate)) return input
    return buildString(input.length) {
        var index = 0
        while (index < input.length) {
            val char = input[index]
            val pairedSurrogate = char.isHighSurrogate() &&
                index + 1 < input.length &&
                input[index + 1].isLowSurrogate()
            when {
                pairedSurrogate -> {
                    append(char).append(input[index + 1])
                    index += 1
                }
                char == '\n' || char == '\r' || char == '\t' -> append(char)
                isUnrepresentableCandidate(char) -> append('\uFFFD')
                else -> append(char)
            }
            index += 1
        }
    }
}

/** This tree with [xmlRepresentable] applied to every text a node carries. */
internal fun AccessibilityNodeSnapshot.xmlRepresentable(): AccessibilityNodeSnapshot = copy(
    className = className?.let(::xmlRepresentable),
    packageName = packageName?.let(::xmlRepresentable),
    viewIdResourceName = viewIdResourceName?.let(::xmlRepresentable),
    text = text?.let(::xmlRepresentable),
    contentDescription = contentDescription?.let(::xmlRepresentable),
    children = children.map { child -> child.xmlRepresentable() },
)

/**
 * A character XML 1.0 cannot carry on its own. A surrogate may still be half of a valid pair, which
 * [xmlRepresentable] keeps.
 */
private fun isUnrepresentableCandidate(char: Char): Boolean =
    (char < ' ' && char != '\n' && char != '\r' && char != '\t') ||
        char.isSurrogate() ||
        char == '\uFFFE' ||
        char == '\uFFFF'

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
