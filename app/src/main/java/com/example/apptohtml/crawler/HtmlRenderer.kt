package com.example.apptohtml.crawler

object HtmlRenderer {
    /** The id the identity block is published under, and the one a reader looks it up by. */
    const val SCREEN_IDENTITY_ELEMENT_ID = "screen-identity"

    /**
     * Renders the screen page, embedding [identity] when there is one.
     *
     * The identity travels as an XML data block rather than as HTML markup so both files carry one
     * syntax with one parser: an operator edits the same text in either place. Rendering it as
     * `data-*` attributes would read better in a browser at the cost of a second shape for the same
     * fact, with its own way to drift.
     *
     * A null [identity] writes no block. That is the non-crawl save path, which has no crawl state
     * and so has no identity to publish — not a screen whose identity is empty.
     */
    fun render(
        snapshot: ScreenSnapshot,
        resolvedChildLinks: Map<PressableElementLinkKey, String> = emptyMap(),
        identity: ScreenIdentity? = null,
    ): String {
        val title = escape(snapshot.screenName)
        val packageName = escape(snapshot.packageName)
        val elementMarkup = if (snapshot.elements.isEmpty()) {
            "    <p>No pressable elements were found on this screen.</p>"
        } else {
            buildElementMarkup(snapshot.elements, resolvedChildLinks)
        }
        val identityBlock = identity?.let { renderIdentityBlock(it) }.orEmpty()

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$title</title>$identityBlock
            </head>
            <body>
              <h1>$title</h1>
              <p>Package: $packageName</p>
              <section>
$elementMarkup
              </section>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * The identity block, indented into `<head>`.
     *
     * The text comes from the XML serializer, so the screen's two files publish one identity from
     * one producer. Only the indentation added here differs between them.
     *
     * `</` inside the payload would close the script element early. It cannot occur — every value
     * is XML-escaped before it reaches this point — but the check is here rather than assumed,
     * because the failure mode is a silently truncated identity rather than an error.
     */
    private fun renderIdentityBlock(identity: ScreenIdentity): String {
        val block = AccessibilityXmlSerializer.screenIdentityBlock(identity)
        check(!block.contains("</script", ignoreCase = true)) {
            "Screen identity would terminate its own script block: $block"
        }
        val indented = block.lines().joinToString("\n") { line -> "    $line" }
        return "\n  <script type=\"application/xml\" id=\"$SCREEN_IDENTITY_ELEMENT_ID\">\n" +
            indented +
            "\n  </script>"
    }

    private fun buildElementMarkup(
        elements: List<PressableElement>,
        resolvedChildLinks: Map<PressableElementLinkKey, String>,
    ): String {
        val lines = mutableListOf<String>()
        var openList = false

        elements.forEach { element ->
            if (element.isListItem && !openList) {
                lines += "    <ul>"
                openList = true
            } else if (!element.isListItem && openList) {
                lines += "    </ul>"
                openList = false
            }

            val linkMarkup = renderAnchor(
                element = element,
                href = resolvedChildLinks[element.toLinkKey()]?.takeIf { it.isNotBlank() } ?: "#",
            )
            if (element.isListItem) {
                lines += "      <li>$linkMarkup</li>"
            } else {
                lines += "    $linkMarkup"
            }
        }

        if (openList) {
            lines += "    </ul>"
        }

        return lines.joinToString(separator = "\n")
    }

    private fun renderAnchor(
        element: PressableElement,
        href: String,
    ): String {
        val resolvedHref = escapeAttribute(href)
        val resourceId = escapeAttribute(element.resourceId.orEmpty())
        val className = escapeAttribute(element.className.orEmpty())
        val bounds = escapeAttribute(element.bounds)
        val fingerprint = escapeAttribute(ElementFingerprint.of(element).encoded)
        val label = escape(element.label)
        return """<a href="$resolvedHref" data-resource-id="$resourceId" data-class-name="$className" data-bounds="$bounds" fingerprint="$fingerprint">$label</a>"""
    }

    private fun escape(input: String): String = buildString(input.length) {
        input.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> char
                }
            )
        }
    }

    private fun escapeAttribute(input: String): String = escape(input)
}
