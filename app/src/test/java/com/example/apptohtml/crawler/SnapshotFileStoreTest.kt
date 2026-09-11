package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val capturedAt = 1_700_000_000_000L
    private val finishedAt = 1_700_000_004_000L

    // ------------------------------------------------------------------ write ordering

    @Test
    fun `done marker is the last artifact written`() {
        val order = mutableListOf<String>()

        write(recordingWriter(order))

        // Not an mtime comparison on purpose: a sub-millisecond write burst gives every file the
        // same timestamp, so an mtime assertion would pass no matter what order they landed in.
        assertEquals(SnapshotFileStore.DONE_MARKER, order.last())
        assertTrue(
            "every artifact must precede the done marker, got $order",
            order.dropLast(1).containsAll(
                listOf(
                    "screen_00000_home.html",
                    "screen_00000_home.xml",
                    "snapshot.json",
                    "capture.log",
                ),
            ),
        )
    }

    @Test
    fun `every artifact is present on disk once the done marker exists`() {
        val result = write()

        assertTrue(File(result.directory, SnapshotFileStore.DONE_MARKER).exists())
        assertTrue(result.files.htmlFile.exists())
        assertTrue(result.files.xmlFile.exists())
        assertTrue(result.metadataFile.exists())
        assertTrue(result.logFile.exists())
    }

    @Test
    fun `failure writes the reason into a failed marker and never a done marker`() {
        val directory = SnapshotFileStore.writeFailure(
            baseDir = temporaryFolder.root,
            packageName = "com.example.target",
            token = "tok123",
            capturedAt = capturedAt,
            reason = "crawl_in_progress",
        )

        assertEquals(
            "crawl_in_progress",
            File(directory, SnapshotFileStore.FAILED_MARKER).readText(),
        )
        assertFalse(File(directory, SnapshotFileStore.DONE_MARKER).exists())
    }

    // ------------------------------------------------------------- crawl/ must be untouched

    @Test
    fun `a snapshot write leaves a sibling crawl directory byte-identical`() {
        val packageDir = File(temporaryFolder.root, "html/com.example.target")
        val crawlDir = File(packageDir, "crawl").apply { mkdirs() }
        val existing = mapOf(
            "crawl-index.json" to """{"sessionId":"crawl_20260101_000000"}""",
            "screen_001_home.html" to "<html>crawl root</html>",
            "screen_001_home.xml" to "<screen />",
        )
        existing.forEach { (name, contents) -> File(crawlDir, name).writeText(contents) }
        val before = snapshotOfDirectory(crawlDir)

        write()

        assertEquals(before, snapshotOfDirectory(crawlDir))
        assertTrue(
            "snapshots must be a sibling of crawl/, never inside it",
            File(packageDir, "snapshots").isDirectory,
        )
    }

    @Test
    fun `snapshots land in a package-scoped snapshots directory keyed by token`() {
        val result = write(token = "abc789")

        assertEquals("snapshots", result.directory.parentFile!!.name)
        assertEquals("com.example.target", result.directory.parentFile!!.parentFile!!.name)
        assertTrue(result.directory.name.contains("abc789"))
    }

    @Test
    fun `two captures of the same screen land in distinct directories`() {
        val first = write(token = "tok-1")
        val second = write(token = "tok-2")

        assertFalse(first.directory.canonicalPath == second.directory.canonicalPath)
    }

    // --------------------------------------------------------------------- XML fidelity

    @Test
    fun `snapshot xml parses with ScreenXmlReader as a root screen with no edges`() {
        val result = write()

        val payload = ScreenXmlReader.readFull(result.files.xmlFile)

        assertNotNull("snapshot XML must be readable by the crawl's own reader", payload)
        val head = payload!!.head
        assertEquals(0, head.depth)
        assertTrue(head.isRoot)
        assertEquals(SnapshotFileStore.SNAPSHOT_SCREEN_ID, head.screenId)
        assertTrue("a snapshot follows nothing, so it has no edges", payload.edgeByElement.isEmpty())
        assertEquals(1, payload.elements.size)
    }

    @Test
    fun `snapshot run level records a snapshot session id, never a crawl one`() {
        val result = write()

        val head = ScreenXmlReader.readHead(result.files.xmlFile)

        assertNotNull(head)
        val sessionId = head!!.runLevel?.sessionId
        assertNotNull("root screens carry run-level state", sessionId)
        assertTrue(
            "provenance must stay honest: $sessionId",
            sessionId!!.startsWith("snapshot_"),
        )
        assertFalse(sessionId.startsWith("crawl_"))
    }

    @Test
    fun `snapshot xml is structurally identical to a crawl root screen's xml`() {
        val snapshot = screenSnapshot()
        val snapshotXml = AccessibilityXmlSerializer.serialize(
            snapshot,
            SnapshotCrawlState.build(
                snapshot = snapshot,
                root = null,
                sessionId = "snapshot_20260807_120000",
                startedAt = capturedAt,
                finishedAt = finishedAt,
            ),
        )

        // Built the way DeepCrawlCoordinator builds a *root* screen: same identity derivation,
        // same run-level shape, depth 0, no parent, empty route.
        val crawlRootXml = AccessibilityXmlSerializer.serialize(
            snapshot,
            ScreenCrawlState(
                screenId = SnapshotFileStore.SNAPSHOT_SCREEN_ID,
                depth = 0,
                expansionStatus = ScreenExpansionStatus.NOT_STARTED,
                isRoot = true,
                screenIdentity = ScreenNaming.buildScreenNameIdentity(
                        screenName = snapshot.screenName,
                        packageName = snapshot.packageName,
                        root = null,
                    ).let {
                        ScreenIdentityFields(it.packageName, it.screenName, it.titleDisambiguators)
                    },
                parent = null,
                route = CrawlRoute(),
                runLevel = RunLevelState(
                    sessionId = "crawl_20260807_120000",
                    startedAt = capturedAt,
                    finishedAt = finishedAt,
                    status = CrawlRunStatus.COMPLETED,
                    maxDepthReached = 0,
                ),
                edgesByElement = emptyMap(),
            ),
        )

        // Whole-document comparison, not a spot-check: a serializer that quietly dropped
        // attributes would still satisfy an assertion that merely looked for depth="0".
        assertEquals(
            crawlRootXml.replace("crawl_20260807_120000", "SESSION"),
            snapshotXml.replace("snapshot_20260807_120000", "SESSION"),
        )
    }

    @Test
    fun `synthesized identity matches the crawl's two-step derivation`() {
        val snapshot = screenSnapshot()

        val state = SnapshotCrawlState.build(
            snapshot = snapshot,
            root = null,
            sessionId = "snapshot_x",
            startedAt = capturedAt,
            finishedAt = finishedAt,
        )

        val expected = ScreenNaming.buildScreenNameIdentity(
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            root = null,
        ).let { ScreenIdentityFields(it.packageName, it.screenName, it.titleDisambiguators) }
        assertEquals(expected, state.screenIdentity)
    }

    // ------------------------------------------------------------------------- metadata

    @Test
    fun `metadata records token, package, screen name and counts`() {
        val result = write(token = "tok-meta")

        val json = result.metadataFile.readText()

        assertTrue(json.contains(""""token": "tok-meta""""))
        assertTrue(json.contains(""""packageName": "com.example.target""""))
        assertTrue(json.contains(""""screenName": "Home""""))
        assertTrue(json.contains(""""elementCount": 1"""))
        assertTrue(json.contains(""""scrollEnabled": true"""))
    }

    @Test
    fun `metadata escapes characters that would break the json`() {
        val snapshot = screenSnapshot(screenName = """Say "hi"\now""")

        val result = SnapshotFileStore.write(
            baseDir = temporaryFolder.root,
            snapshot = snapshot,
            crawlState = crawlStateFor(snapshot),
            token = "tok",
            capturedAt = capturedAt,
            scrollEnabled = true,
            logContents = "",
        )

        assertTrue(result.metadataFile.readText().contains("""\"hi\""""))
    }

    // ---------------------------------------------------------------------------- helpers

    private fun write(
        writer: SnapshotFileStore.ArtifactWriter? = null,
        token: String = "tok123",
    ): SnapshotResult {
        val snapshot = screenSnapshot()
        return if (writer == null) {
            SnapshotFileStore.write(
                baseDir = temporaryFolder.root,
                snapshot = snapshot,
                crawlState = crawlStateFor(snapshot),
                token = token,
                capturedAt = capturedAt,
                scrollEnabled = true,
                logContents = "capture log line\n",
            )
        } else {
            SnapshotFileStore.write(
                baseDir = temporaryFolder.root,
                snapshot = snapshot,
                crawlState = crawlStateFor(snapshot),
                token = token,
                capturedAt = capturedAt,
                scrollEnabled = true,
                logContents = "capture log line\n",
                writer = writer,
            )
        }
    }

    private fun recordingWriter(order: MutableList<String>) =
        SnapshotFileStore.ArtifactWriter { file, contents ->
            order += file.name
            file.writeText(contents, Charsets.UTF_8)
        }

    private fun crawlStateFor(snapshot: ScreenSnapshot) = SnapshotCrawlState.build(
        snapshot = snapshot,
        root = null,
        sessionId = SnapshotFileStore.sessionId(capturedAt),
        startedAt = capturedAt,
        finishedAt = finishedAt,
    )

    private fun screenSnapshot(screenName: String = "Home") = ScreenSnapshot(
        screenName = screenName,
        packageName = "com.example.target",
        elements = listOf(
            PressableElement(
                label = "Open",
                resourceId = "com.example.target:id/open",
                bounds = "[0,0][100,50]",
                className = "android.widget.Button",
                isListItem = false,
                childIndexPath = listOf(0),
            ),
        ),
        xmlDump = "<raw />",
        scrollStepCount = 3,
    )

    @Test
    fun `snapshot screen id matches the id a crawl gives its root screen`() {
        // Verified against a real device capture: CrawlRunTracker emits `screen_00000` for the
        // crawl root, so a snapshot's file base and <crawl screen-id> are indistinguishable from
        // one. Provenance lives in snapshot.json and the snapshot_ session id instead.
        assertEquals("screen_00000", SnapshotFileStore.SNAPSHOT_SCREEN_ID)
    }

    private fun snapshotOfDirectory(directory: File): Map<String, String> =
        directory.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(directory).path to it.readText() }
}
