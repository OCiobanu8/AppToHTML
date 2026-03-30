package com.example.apptohtml.crawler

object HtmlRenderer {
    fun render(snapshot: ScreenSnapshot): String {
        val title = escape(snapshot.screenName)
        val packageName = escape(snapshot.packageName)
        val elementMarkup = if (snapshot.elements.isEmpty()) {
            "    <p>No pressable elements were found on this screen.</p>"
        } else {
            buildElementMarkup(snapshot.elements)
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$title</title>
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

    private fun buildElementMarkup(elements: List<PressableElement>): String {
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

            val linkMarkup = renderAnchor(element)
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

    private fun renderAnchor(element: PressableElement): String {
        val resourceId = escapeAttribute(element.resourceId.orEmpty())
        val className = escapeAttribute(element.className.orEmpty())
        val bounds = escapeAttribute(element.bounds)
        val label = escape(element.label)
        return """<a href="#" data-resource-id="$resourceId" data-class-name="$className" data-bounds="$bounds">$label</a>"""
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
