package com.example.apptohtml.crawler

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a completed snapshot landed, and what it contains. */
internal data class SnapshotResult(
    val directory: File,
    val files: CapturedScreenFiles,
    val metadataFile: File,
    val logFile: File,
    val screenName: String,
    val packageName: String,
    val elementCount: Int,
    val scrollStepCount: Int,
)

/**
 * Writes a single-screen snapshot to `html/<package>/snapshots/<timestamp>_<token>_<label>/`.
 *
 * Deliberately **not** built on [CaptureFileStore]:
 *  - `createSession` is coupled to crawl-session semantics and can wipe `crawl/` wholesale;
 *  - `save` calls `preparePackageDirectory`, which deletes the package directory's contents.
 *
 * Snapshots are a sibling of `crawl/` and must never disturb it, so this store only ever creates
 * and writes inside its own timestamped directory.
 */
internal object SnapshotFileStore {
    private const val SNAPSHOTS_DIR_NAME = "snapshots"
    internal const val DONE_MARKER = ".done"
    internal const val FAILED_MARKER = ".failed"

    /**
     * Snapshot output uses the same screen id the crawl gives its root screen, so a snapshot's file
     * base name is indistinguishable from a crawl root's and `ScreenXmlReader` needs no special
     * case. Provenance is recorded in `snapshot.json` and the run-level session id instead.
     */
    internal const val SNAPSHOT_SCREEN_ID = "screen_00000"

    fun snapshotsDirectory(baseDir: File, packageName: String): File =
        File(File(baseDir, "html/$packageName"), SNAPSHOTS_DIR_NAME)

    fun sessionId(capturedAt: Long): String =
        "snapshot_${timestamp(capturedAt)}"

    /** Every byte this store puts on disk goes through here, so write *order* is observable. */
    internal fun interface ArtifactWriter {
        fun write(file: File, contents: String)
    }

    private val defaultWriter = ArtifactWriter { file, contents ->
        file.writeText(contents, Charsets.UTF_8)
    }

    /**
     * Writes every artifact, then the `.done` marker **last**. A host-side poller that sees
     * `.done` is therefore guaranteed a complete directory.
     *
     * [writer] exists so tests can assert that ordering directly. Comparing file mtimes would not
     * work: filesystem timestamp granularity is coarse enough that every file in a sub-millisecond
     * write burst shares a timestamp, so an mtime-based assertion passes regardless of real order.
     */
    fun write(
        baseDir: File,
        snapshot: ScreenSnapshot,
        crawlState: ScreenCrawlState,
        token: String,
        capturedAt: Long,
        scrollEnabled: Boolean,
        logContents: String,
        nameOverride: String? = null,
        writer: ArtifactWriter = defaultWriter,
    ): SnapshotResult {
        val label = ScreenNaming.toFileBase(nameOverride?.takeIf { it.isNotBlank() } ?: snapshot.screenName)
        val directory = File(
            snapshotsDirectory(baseDir, snapshot.packageName),
            "${timestamp(capturedAt)}_${token}_$label",
        )
        directory.mkdirs()

        val baseName = "${SNAPSHOT_SCREEN_ID}_$label"
        val htmlFile = File(directory, "$baseName.html")
        val xmlFile = File(directory, "$baseName.xml")
        val mergedXmlFile = snapshot.mergedXmlDump?.let { File(directory, "${baseName}_merged_accessibility.xml") }
        val metadataFile = File(directory, "snapshot.json")
        val logFile = File(directory, "capture.log")

        writer.write(htmlFile, HtmlRenderer.render(snapshot, emptyMap(), crawlState.screenIdentity))
        writer.write(xmlFile, AccessibilityXmlSerializer.serialize(snapshot, crawlState))
        mergedXmlFile?.let { writer.write(it, snapshot.mergedXmlDump.orEmpty()) }
        writer.write(
            metadataFile,
            renderMetadata(
                token = token,
                snapshot = snapshot,
                capturedAt = capturedAt,
                scrollEnabled = scrollEnabled,
                sessionId = crawlState.runLevel?.sessionId ?: sessionId(capturedAt),
            ),
        )
        writer.write(logFile, logContents)

        // Strictly last — this is the whole completion contract with the host script.
        writer.write(File(directory, DONE_MARKER), "")

        return SnapshotResult(
            directory = directory,
            files = CapturedScreenFiles(htmlFile = htmlFile, xmlFile = xmlFile, mergedXmlFile = mergedXmlFile),
            metadataFile = metadataFile,
            logFile = logFile,
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            elementCount = snapshot.elements.size,
            scrollStepCount = snapshot.scrollStepCount,
        )
    }

    /**
     * Records a failure the host script can read. Written into a directory that never receives a
     * `.done`, so the two markers can never both exist for one capture.
     */
    fun writeFailure(
        baseDir: File,
        packageName: String,
        token: String,
        capturedAt: Long,
        reason: String,
        logContents: String = "",
        writer: ArtifactWriter = defaultWriter,
    ): File {
        val directory = File(
            snapshotsDirectory(baseDir, packageName),
            "${timestamp(capturedAt)}_${token}_failed",
        )
        directory.mkdirs()
        if (logContents.isNotEmpty()) {
            writer.write(File(directory, "capture.log"), logContents)
        }
        writer.write(File(directory, FAILED_MARKER), reason)
        return directory
    }

    private fun renderMetadata(
        token: String,
        snapshot: ScreenSnapshot,
        capturedAt: Long,
        scrollEnabled: Boolean,
        sessionId: String,
    ): String {
        return buildString {
            append("{\n")
            append("  \"token\": \"").append(escapeJson(token)).append("\",\n")
            append("  \"sessionId\": \"").append(escapeJson(sessionId)).append("\",\n")
            append("  \"packageName\": \"").append(escapeJson(snapshot.packageName)).append("\",\n")
            append("  \"screenName\": \"").append(escapeJson(snapshot.screenName)).append("\",\n")
            append("  \"capturedAt\": ").append(capturedAt).append(",\n")
            append("  \"scrollStepCount\": ").append(snapshot.scrollStepCount).append(",\n")
            append("  \"elementCount\": ").append(snapshot.elements.size).append(",\n")
            append("  \"scrollEnabled\": ").append(scrollEnabled).append("\n")
            append("}\n")
        }
    }

    private fun escapeJson(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))
}
