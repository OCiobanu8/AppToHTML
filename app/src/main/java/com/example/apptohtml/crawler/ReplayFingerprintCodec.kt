package com.example.apptohtml.crawler

internal object ReplayFingerprintCodec {
    private const val ELEMENT_FIELD_COUNT = 6

    // Field order MUST match ElementFingerprint.encoded (resourceId, label, className, isListItem,
    // checkable, editable) — the logical replay fingerprint produced by
    // ScrollScanCoordinator.buildElementFingerprint is encoded that way, and this codec only
    // serializes/round-trips that exact string through XML. `checked` is not part of identity.
    data class ElementFields(
        val resourceId: String,
        val label: String,
        val className: String,
        val isListItem: String,
        val checkable: String,
        val editable: String,
    )

    data class ReplayFingerprintFields(
        val rootClass: String,
        val elements: List<ElementFields>,
    )

    fun encode(rootClass: String, elements: List<ElementFields>): String {
        val encodedElements = elements.joinToString("||") { e ->
            listOf(
                e.resourceId,
                e.label,
                e.className,
                e.isListItem,
                e.checkable,
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
                resourceId = fields[0],
                label = fields[1],
                className = fields[2],
                isListItem = fields[3],
                checkable = fields[4],
                editable = fields[5],
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
