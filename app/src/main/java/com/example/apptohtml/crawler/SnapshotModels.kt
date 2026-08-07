package com.example.apptohtml.crawler

import java.util.UUID

enum class SnapshotStatus {
    IDLE,
    CAPTURING,
    CAPTURED,
    FAILED,
}

/**
 * Snapshot progress, deliberately kept **separate** from [CrawlerUiState].
 *
 * A snapshot is not a crawl. Folding it into [CrawlerPhase] would make the crawl state machine
 * look busy while a snapshot runs, which would in turn make [SnapshotCaptureCoordinator]'s own
 * "reject while a crawl is active" guard reject the very captures it is meant to allow.
 */
data class SnapshotUiState(
    val status: SnapshotStatus = SnapshotStatus.IDLE,
    val screenName: String? = null,
    val packageName: String? = null,
    val directoryPath: String? = null,
    val elementCount: Int = 0,
    val scrollStepCount: Int = 0,
    val message: String? = null,
)

/**
 * Turns the broadcast's extras into a [SnapshotCaptureRequest].
 *
 * Split out as a pure function so malformed and absent extras are unit-testable without a device.
 */
internal object SnapshotRequestParser {

    const val ACTION_CAPTURE_SCREEN = "com.example.apptohtml.CAPTURE_SCREEN"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_NAME = "name"
    const val EXTRA_SCROLL = "scroll"

    /**
     * [scrollBoolean] comes from `am broadcast --ez scroll false`; [scrollString] from
     * `--es scroll false`. Operators reach for `--es` constantly, and a string extra silently
     * ignored would make `scroll=false` look broken, so both are honored — boolean first.
     */
    fun parse(
        token: String?,
        name: String?,
        scrollBoolean: Boolean?,
        scrollString: String?,
        tokenFallback: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
    ): SnapshotCaptureRequest {
        return SnapshotCaptureRequest(
            token = token?.trim()?.takeIf { it.isNotEmpty() }?.let(::sanitizeToken) ?: tokenFallback(),
            nameOverride = name?.trim()?.takeIf { it.isNotEmpty() },
            scroll = scrollBoolean ?: scrollString?.trim()?.lowercase()?.let {
                when (it) {
                    "false", "0", "no" -> false
                    "true", "1", "yes" -> true
                    else -> null
                }
            } ?: true,
        )
    }

    /** The token becomes part of a directory name, so it must not be able to escape one. */
    private fun sanitizeToken(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_-]"), "").take(64).ifEmpty { "token" }
}
