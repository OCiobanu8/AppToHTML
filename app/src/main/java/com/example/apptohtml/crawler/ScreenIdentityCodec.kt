package com.example.apptohtml.crawler

internal object ScreenIdentityCodec {
    private const val PREFIX = "v2"
    private const val PKG = "pkg"
    private const val TITLE = "title"
    private const val HINT = "hint"
    private const val UNKNOWN_PACKAGE = "unknown"
    private const val UNNAMED_TITLE = "unnamed"
    private const val NO_HINT = "none"

    fun encode(packageName: String, title: String, hints: List<String>): String {
        val normalizedTitle = ScreenNaming.normalizeIdentityToken(title).ifBlank { UNNAMED_TITLE }
        val normalizedPackage = ScreenNaming.normalizeIdentityToken(packageName).ifBlank { UNKNOWN_PACKAGE }
        val normalizedHints = hints
            .map(ScreenNaming::normalizeIdentityToken)
            .filter { hint -> hint.isNotBlank() && hint != normalizedTitle }
            .distinct()
            .take(2)
        return buildString {
            append(PREFIX).append(':')
            append(PKG).append(':').append(normalizedPackage).append(':')
            append(TITLE).append(':').append(normalizedTitle).append(':')
            append(HINT).append(':')
            append(normalizedHints.ifEmpty { listOf(NO_HINT) }.joinToString("|"))
        }
    }

    fun decode(fingerprint: String): ScreenIdentityFields? {
        val parts = fingerprint.split(':')
        if (parts.size != 7) return null
        if (parts[0] != PREFIX) return null
        if (parts[1] != PKG) return null
        if (parts[3] != TITLE) return null
        if (parts[5] != HINT) return null

        val packageName = parts[2]
        val title = parts[4]
        val hintsRaw = parts[6]
        val hints = if (hintsRaw == NO_HINT || hintsRaw.isBlank()) {
            emptyList()
        } else {
            hintsRaw.split('|').filter { it.isNotBlank() && it != NO_HINT }
        }
        return ScreenIdentityFields(
            packageName = packageName,
            title = title,
            hints = hints,
        )
    }
}
