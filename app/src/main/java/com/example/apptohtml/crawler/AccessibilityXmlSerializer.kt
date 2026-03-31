package com.example.apptohtml.crawler

object AccessibilityXmlSerializer {
    fun serialize(snapshot: ScreenSnapshot): String {
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="utf-8"?>""")
        builder.append('\n')
        builder.append("<screen")
        builder.append(""" name="${escape(snapshot.screenName)}"""")
        builder.append(""" package="${escape(snapshot.packageName)}"""")
        builder.append(""" scroll-steps="${snapshot.scrollStepCount}"""")
        builder.append(">")
        builder.append('\n')
        appendMergedElements(builder, snapshot.elements, depth = 1)
        appendScrollSteps(builder, snapshot.stepSnapshots, depth = 1)
        builder.append("</screen>")
        builder.append('\n')
        return builder.toString()
    }

    fun serialize(
        screenName: String,
        packageName: String,
        root: AccessibilityNodeSnapshot,
    ): String {
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="utf-8"?>""")
        builder.append('\n')
        builder.append("<screen")
        builder.append(""" name="${escape(screenName)}"""")
        builder.append(""" package="${escape(packageName)}"""")
        builder.append(">")
        builder.append('\n')
        appendNode(builder, root, depth = 1)
        builder.append("</screen>")
        builder.append('\n')
        return builder.toString()
    }

    private fun appendNode(builder: StringBuilder, node: AccessibilityNodeSnapshot, depth: Int) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<node")
        builder.append(""" class="${escape(node.className.orEmpty())}"""")
        builder.append(""" package="${escape(node.packageName.orEmpty())}"""")
        builder.append(""" resource-id="${escape(node.viewIdResourceName.orEmpty())}"""")
        builder.append(""" text="${escape(node.text.orEmpty())}"""")
        builder.append(""" content-description="${escape(node.contentDescription.orEmpty())}"""")
        builder.append(""" clickable="${node.clickable}"""")
        builder.append(""" click-action="${node.supportsClickAction}"""")
        builder.append(""" scrollable="${node.scrollable}"""")
        builder.append(""" checkable="${node.checkable}"""")
        builder.append(""" checked="${node.checked}"""")
        builder.append(""" enabled="${node.enabled}"""")
        builder.append(""" visible-to-user="${node.visibleToUser}"""")
        builder.append(""" bounds="${escape(node.bounds)}"""")
        if (node.children.isEmpty()) {
            builder.append(" />")
            builder.append('\n')
            return
        }

        builder.append(">")
        builder.append('\n')
        node.children.forEach { child ->
            appendNode(builder, child, depth + 1)
        }
        builder.append(indent)
        builder.append("</node>")
        builder.append('\n')
    }

    private fun appendMergedElements(
        builder: StringBuilder,
        elements: List<PressableElement>,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        if (elements.isEmpty()) {
            builder.append(indent)
            builder.append("<merged-elements />")
            builder.append('\n')
            return
        }

        builder.append(indent)
        builder.append("<merged-elements>")
        builder.append('\n')
        elements.forEach { element ->
            builder.append(indent)
            builder.append("  <element")
            builder.append(""" label="${escape(element.label)}"""")
            builder.append(""" resource-id="${escape(element.resourceId.orEmpty())}"""")
            builder.append(""" class="${escape(element.className.orEmpty())}"""")
            builder.append(""" bounds="${escape(element.bounds)}"""")
            builder.append(""" list-item="${element.isListItem}"""")
            builder.append(""" child-index-path="${escape(element.childIndexPath.joinToString("."))}"""")
            builder.append(""" checkable="${element.checkable}"""")
            builder.append(""" checked="${element.checked}"""")
            builder.append(""" first-seen-step="${element.firstSeenStep}"""")
            builder.append(" />")
            builder.append('\n')
        }
        builder.append(indent)
        builder.append("</merged-elements>")
        builder.append('\n')
    }

    private fun appendScrollSteps(
        builder: StringBuilder,
        steps: List<ScrollCaptureStep>,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<scroll-steps>")
        builder.append('\n')
        steps.forEach { step ->
            builder.append(indent)
            builder.append("  <step")
            builder.append(""" index="${step.stepIndex}"""")
            builder.append(""" new-elements="${step.newElementCount}"""")
            builder.append(">")
            builder.append('\n')
            appendNode(builder, step.root, depth + 2)
            builder.append(indent)
            builder.append("  </step>")
            builder.append('\n')
        }
        builder.append(indent)
        builder.append("</scroll-steps>")
        builder.append('\n')
    }

    private fun escape(input: String): String = buildString(input.length) {
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
}
