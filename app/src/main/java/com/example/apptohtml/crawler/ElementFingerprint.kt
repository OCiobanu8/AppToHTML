package com.example.apptohtml.crawler

/**
 * Purely semantic identity of a pressable element.
 *
 * This is the single definition of "is this the same button?" used across scan dedup,
 * viewport/loop-detection fingerprints, the persisted replay fingerprint, crawl-edge
 * equality, and the live click-replay matcher.
 *
 * Intentionally carries **no geometry** and **no structural/index path** — button order and
 * pixel bounds can differ between the capture pass and replay, so they are not part of identity.
 *
 * `checked` (transient on/off state) is excluded; `checkable` (the element's type/affordance) is
 * kept, so a toggle never collapses into a plain button with the same label.
 */
data class ElementFingerprint(
    val resourceId: String?,   // viewIdResourceName, or null if blank
    val label: String,         // normalized (see normalizeLabel)
    val className: String?,    // null if blank
    val isListItem: Boolean,
    val checkable: Boolean,    // element type/affordance — KEPT (checked is NOT)
    val editable: Boolean,
) {
    /** Canonical serialized form, used as the `fingerprint="..."` attribute in XML/HTML. */
    val encoded: String = listOf(
        resourceId.orEmpty(),
        label,
        className.orEmpty(),
        isListItem.toString(),
        checkable.toString(),
        editable.toString(),
    ).joinToString("|")

    companion object {
        fun of(element: PressableElement): ElementFingerprint =
            ofFields(
                label = element.label,
                resourceId = element.resourceId,
                className = element.className,
                isListItem = element.isListItem,
                checkable = element.checkable,
                editable = element.editable,
            )

        fun of(edge: CrawlEdgeRecord): ElementFingerprint =
            ofFields(
                label = edge.label,
                resourceId = edge.resourceId,
                className = edge.className,
                isListItem = edge.isListItem,
                checkable = edge.checkable,
                editable = edge.editable,
            )

        fun ofFields(
            label: String,
            resourceId: String?,
            className: String?,
            isListItem: Boolean,
            checkable: Boolean,
            editable: Boolean,
        ): ElementFingerprint = ElementFingerprint(
            resourceId = resourceId?.takeIf { it.isNotBlank() },
            label = normalizeLabel(label),
            className = className?.takeIf { it.isNotBlank() },
            isListItem = isListItem,
            checkable = checkable,
            editable = editable,
        )

        private val TRAILING_PAREN_COUNT = Regex("""\s*\(\d+\)\s*$""")
        private val TRAILING_BARE_NUMBER = Regex("""\s+\d+$""")

        /**
         * trim → lowercase → strip trailing count/badge.
         *
         * Strips both a parenthesized badge ("Messages (3)" → "messages") and a trailing bare
         * number ("Inbox 12" → "inbox"). A trailing bare number cannot be distinguished from
         * meaningful trailing digits, so "Room 101" and "Room 202" both normalize to "room" and
         * merge — an accepted trade-off. Genuinely internal numbers ("Level 3 settings") are
         * preserved because they are not trailing.
         */
        fun normalizeLabel(raw: String): String =
            raw.trim()
                .lowercase()
                .replace(TRAILING_PAREN_COUNT, "")
                .replace(TRAILING_BARE_NUMBER, "")
                .trim()
    }
}
