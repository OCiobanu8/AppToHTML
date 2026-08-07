package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/** What the host asked for. */
internal data class SnapshotCaptureRequest(
    val token: String,
    val nameOverride: String? = null,
    val scroll: Boolean = true,
)

internal enum class SnapshotRejectionReason(val wireValue: String) {
    CRAWL_IN_PROGRESS("crawl_in_progress"),
    NO_FOREGROUND_WINDOW("no_foreground_window"),
    OWN_UI_IN_FOREGROUND("own_ui_in_foreground"),
    SYSTEM_UI_IN_FOREGROUND("system_ui_in_foreground"),
}

internal sealed class SnapshotCaptureOutcome {
    data class Captured(val result: SnapshotResult) : SnapshotCaptureOutcome()
    data class Rejected(val reason: SnapshotRejectionReason, val directory: File?) : SnapshotCaptureOutcome()
    data class Failed(val reason: String, val directory: File?) : SnapshotCaptureOutcome()
}

/**
 * Captures the currently-visible screen without launching anything, without navigating back, and
 * without bringing AppToHTML to the foreground.
 *
 * The no-launch/no-back guarantee is **structural**: [Host] exposes no way to launch an app, press
 * global back, or foreground this app. There is no method to call, so no future edit inside this
 * class can violate the guarantee — it does not depend on a test noticing.
 *
 * Shaped like `DeepCrawlCoordinator.Host` so it is unit-testable with fakes in the same way.
 */
internal class SnapshotCaptureCoordinator(
    private val host: Host,
    private val scrollScanCoordinator: ScrollScanCoordinator = ScrollScanCoordinator(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val captureTimeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS,
) {

    internal interface Host {
        /** Read, never written: a snapshot must not touch the crawl state machine. */
        fun currentCrawlPhase(): CrawlerPhase
        fun foregroundPackageName(): String?
        fun appLabelFor(packageName: String): String
        suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot?
        fun scrollForward(childIndexPath: List<Int>): Boolean
        fun scrollBackward(childIndexPath: List<Int>): Boolean
        fun baseDirectory(): File
        fun publishProgress(message: String)
    }

    suspend fun capture(request: SnapshotCaptureRequest): SnapshotCaptureOutcome {
        val startedAt = timeProvider()
        val log = mutableListOf<String>()
        fun log(line: String) {
            log += "${timeProvider()} $line"
        }

        log("snapshot_start token=${request.token} scroll=${request.scroll}")

        if (host.currentCrawlPhase() !in IDLE_PHASES) {
            return reject(SnapshotRejectionReason.CRAWL_IN_PROGRESS, request, startedAt, null, log)
        }

        val foregroundPackage = host.foregroundPackageName()
        if (foregroundPackage.isNullOrBlank()) {
            return reject(SnapshotRejectionReason.NO_FOREGROUND_WINDOW, request, startedAt, null, log)
        }
        when (foregroundPackage) {
            OWN_PACKAGE_NAME ->
                return reject(SnapshotRejectionReason.OWN_UI_IN_FOREGROUND, request, startedAt, foregroundPackage, log)

            SYSTEM_UI_PACKAGE_NAME ->
                return reject(SnapshotRejectionReason.SYSTEM_UI_IN_FOREGROUND, request, startedAt, foregroundPackage, log)
        }

        // Synthetic, and never persisted: `scan` reads only packageName (to locate the primary
        // scrollable) and appName (for naming), so a snapshot works on apps never selected in the
        // UI and never consults SelectedAppRepository.
        val selectedApp = SelectedAppRef(
            packageName = foregroundPackage,
            appName = host.appLabelFor(foregroundPackage),
            launcherActivity = "",
            selectedAt = startedAt,
        )
        log("snapshot_target package=$foregroundPackage appName=${selectedApp.appName}")

        return try {
            val initialRoot = host.captureCurrentRootSnapshot(foregroundPackage)
                ?: return fail("foreground window disappeared before capture", request, startedAt, foregroundPackage, log)

            val snapshot = withTimeout(captureTimeoutMillis) {
                scrollScanCoordinator.scan(
                    selectedApp = selectedApp,
                    eventClassName = null,
                    initialRoot = initialRoot,
                    // With scroll disabled the scan still runs — same merge, naming, and element
                    // extraction — but every scroll request is refused, so it settles into exactly
                    // one step and issues no gestures at all.
                    tryScrollForward = { path -> request.scroll && host.scrollForward(path) },
                    tryScrollBackward = { path -> request.scroll && host.scrollBackward(path) },
                    captureCurrentRoot = { host.captureCurrentRootSnapshot(foregroundPackage) },
                    onProgress = { message -> host.publishProgress(message) },
                    preferredName = request.nameOverride?.takeIf { it.isNotBlank() },
                )
            }
            log("snapshot_scanned steps=${snapshot.scrollStepCount} elements=${snapshot.elements.size}")

            if (request.scroll) {
                host.captureCurrentRootSnapshot(foregroundPackage)?.let { currentRoot ->
                    scrollScanCoordinator.rewindToTop(
                        selectedApp = selectedApp,
                        initialRoot = currentRoot,
                        tryScrollBackward = { path -> host.scrollBackward(path) },
                        captureCurrentRoot = { host.captureCurrentRootSnapshot(foregroundPackage) },
                        onProgress = { message -> host.publishProgress(message) },
                    )
                }
                log("snapshot_rewound")
            }

            val finishedAt = timeProvider()
            val result = SnapshotFileStore.write(
                baseDir = host.baseDirectory(),
                snapshot = snapshot,
                crawlState = SnapshotCrawlState.build(
                    snapshot = snapshot,
                    root = snapshot.mergedRoot ?: initialRoot,
                    sessionId = SnapshotFileStore.sessionId(startedAt),
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                ),
                token = request.token,
                capturedAt = startedAt,
                scrollEnabled = request.scroll,
                logContents = (log + "snapshot_done").joinToString("\n", postfix = "\n"),
                nameOverride = request.nameOverride,
            )
            SnapshotCaptureOutcome.Captured(result)
        } catch (timeout: TimeoutCancellationException) {
            fail("capture timed out after ${captureTimeoutMillis}ms", request, startedAt, foregroundPackage, log)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            fail(error.message ?: "unknown error", request, startedAt, foregroundPackage, log)
        }
    }

    private fun reject(
        reason: SnapshotRejectionReason,
        request: SnapshotCaptureRequest,
        startedAt: Long,
        packageName: String?,
        log: MutableList<String>,
    ): SnapshotCaptureOutcome {
        log += "snapshot_rejected reason=${reason.wireValue}"
        val directory = SnapshotFileStore.writeFailure(
            baseDir = host.baseDirectory(),
            // A rejection that never reaches a foreground package still has to leave a terminal
            // marker somewhere the host script's token glob can find it, or the script degrades
            // from a one-line diagnosis into a full timeout.
            packageName = packageName ?: UNKNOWN_PACKAGE_BUCKET,
            token = request.token,
            capturedAt = startedAt,
            reason = reason.wireValue,
            logContents = log.joinToString("\n", postfix = "\n"),
        )
        return SnapshotCaptureOutcome.Rejected(reason, directory)
    }

    private fun fail(
        reason: String,
        request: SnapshotCaptureRequest,
        startedAt: Long,
        packageName: String?,
        log: MutableList<String>,
    ): SnapshotCaptureOutcome {
        log += "snapshot_failed reason=$reason"
        val directory = SnapshotFileStore.writeFailure(
            baseDir = host.baseDirectory(),
            packageName = packageName ?: UNKNOWN_PACKAGE_BUCKET,
            token = request.token,
            capturedAt = startedAt,
            reason = reason,
            logContents = log.joinToString("\n", postfix = "\n"),
        )
        return SnapshotCaptureOutcome.Failed(reason, directory)
    }

    companion object {
        const val OWN_PACKAGE_NAME = "com.example.apptohtml"
        const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
        const val DEFAULT_CAPTURE_TIMEOUT_MILLIS = 60_000L

        /** Where failure markers go when the capture never got as far as naming a target. */
        const val UNKNOWN_PACKAGE_BUCKET = "_unknown"

        private val IDLE_PHASES = setOf(
            CrawlerPhase.IDLE,
            CrawlerPhase.CAPTURED,
            CrawlerPhase.ABORTED,
            CrawlerPhase.FAILED,
        )
    }
}
