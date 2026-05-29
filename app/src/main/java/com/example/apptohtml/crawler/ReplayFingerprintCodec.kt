package com.example.apptohtml.crawler

internal object ReplayFingerprintCodec {
    private const val ELEMENT_FIELD_COUNT = 7

    data class ElementFields(
        val label: String,
        val resourceId: String,
        val className: String,
        val isListItem: String,
        val checkable: String,
        val checked: String,
        val editable: String,
    )

    data class ReplayFingerprintFields(
        val rootClass: String,
        val elements: List<ElementFields>,
    )

    fun encode(rootClass: String, elements: List<ElementFields>): String {
        val encodedElements = elements.joinToString("||") { e ->
            listOf(
                e.label,
                e.resourceId,
                e.className,
                e.isListItem,
                e.checkable,
                e.checked,
                e.editable,
            ).joinToString("|")
        }
        return buildString {
            append(rootClass)
            append("::")
            append(encodedElements)
        }
    }

    fun decode(fingerprint: String): ReplayFingerprintFields? {
        val sepIndex = fingerprint.indexOf("::")
        if (sepIndex < 0) return null
        val rootClass = fingerprint.substring(0, sepIndex)
        val payload = fingerprint.substring(sepIndex + 2)
        if (payload.isEmpty()) {
            return ReplayFingerprintFields(rootClass = rootClass, elements = emptyList())
        }

        val tokens = payload.split('|')
        if (tokens.isEmpty()) return null

        val elements = mutableListOf<ElementFields>()
        var index = 0
        while (index < tokens.size) {
            if (index + ELEMENT_FIELD_COUNT > tokens.size) return null
            val fields = tokens.subList(index, index + ELEMENT_FIELD_COUNT)
            elements += ElementFields(
                label = fields[0],
                resourceId = fields[1],
                className = fields[2],
                isListItem = fields[3],
                checkable = fields[4],
                checked = fields[5],
                editable = fields[6],
            )
            index += ELEMENT_FIELD_COUNT
            if (index < tokens.size) {
                if (tokens[index] != "") return null
                index += 1
            }
        }
        return ReplayFingerprintFields(rootClass = rootClass, elements = elements)
    }
}
