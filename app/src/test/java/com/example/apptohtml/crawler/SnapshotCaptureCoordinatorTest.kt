package com.example.apptohtml.crawler

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fakes-only, per `DeepCrawlCoordinatorTest`'s pattern — synthetic node trees, no framework.
 *
 * The headline safety property (never launch, never navigate back, never foreground AppToHTML) is
 * enforced structurally by [SnapshotCaptureCoordinator.Host] having no such members; these tests
 * cover the behavioral half — rejections, scroll discipline, and where artifacts land.
 */
class SnapshotCaptureCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val targetPackage = "com.example.target"

    private class FakeHost(
        val baseDir: File,
        var phase: CrawlerPhase = CrawlerPhase.IDLE,
        var foreground: String? = "com.example.target",
        val root: AccessibilityNodeSnapshot,
    ) : SnapshotCaptureCoordinator.Host {
        val scrollForwardPaths = mutableListOf<List<Int>>()
        val scrollBackwardPaths = mutableListOf<List<Int>>()
        val progress = mutableListOf<String>()
        var captureCount = 0

        override fun currentCrawlPhase(): CrawlerPhase = phase
        override fun foregroundPackageName(): String? = foreground
        override fun appLabelFor(packageName: String): String = "Target App"

        override suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot? {
            captureCount += 1
            return root
        }

        override fun scrollForward(childIndexPath: List<Int>): Boolean {
            scrollForwardPaths += childIndexPath
            return false
        }

        override fun scrollBackward(childIndexPath: List<Int>): Boolean {
            scrollBackwardPaths += childIndexPath
            return false
        }

        override fun baseDirectory(): File = baseDir
        override fun publishProgress(message: String) {
            progress += message
        }
    }

    // ------------------------------------------------------------------------- rejections

    @Test
    fun `capture is rejected while a crawl is scanning`() {
        assertRejectedForPhase(CrawlerPhase.SCANNING_TARGET_SCREEN)
    }

    @Test
    fun `capture is rejected while a crawl is traversing`() {
        assertRejectedForPhase(CrawlerPhase.TRAVERSING_CHILD_SCREENS)
    }

    @Test
    fun `capture is rejected while a crawl is launching, waiting or paused`() {
        assertRejectedForPhase(CrawlerPhase.LAUNCHING)
        assertRejectedForPhase(CrawlerPhase.WAITING_FOR_TARGET_SCREEN)
        assertRejectedForPhase(CrawlerPhase.PAUSED_FOR_DECISION)
    }

    @Test
    fun `capture is allowed from every terminal crawl phase`() {
        listOf(
            CrawlerPhase.IDLE,
            CrawlerPhase.CAPTURED,
            CrawlerPhase.ABORTED,
            CrawlerPhase.FAILED,
        ).forEach { phase ->
            val host = host(phase = phase)
            val outcome = capture(host)
            assertTrue(
                "expected a capture from phase $phase but got $outcome",
                outcome is SnapshotCaptureOutcome.Captured,
            )
        }
    }

    @Test
    fun `capturing our own UI is rejected`() {
        val host = host(foreground = SnapshotCaptureCoordinator.OWN_PACKAGE_NAME)

        val outcome = capture(host)

        assertEquals(
            SnapshotRejectionReason.OWN_UI_IN_FOREGROUND,
            (outcome as SnapshotCaptureOutcome.Rejected).reason,
        )
    }

    @Test
    fun `capturing the system UI shade is rejected`() {
        val host = host(foreground = SnapshotCaptureCoordinator.SYSTEM_UI_PACKAGE_NAME)

        val outcome = capture(host)

        assertEquals(
            SnapshotRejectionReason.SYSTEM_UI_IN_FOREGROUND,
            (outcome as SnapshotCaptureOutcome.Rejected).reason,
        )
    }

    @Test
    fun `capture with no foreground window is rejected`() {
        val host = host(foreground = null)

        val outcome = capture(host)

        assertEquals(
            SnapshotRejectionReason.NO_FOREGROUND_WINDOW,
            (outcome as SnapshotCaptureOutcome.Rejected).reason,
        )
    }

    @Test
    fun `a rejection issues no gestures at all`() {
        val host = host(phase = CrawlerPhase.TRAVERSING_CHILD_SCREENS)

        capture(host)

        assertTrue(host.scrollForwardPaths.isEmpty())
        assertTrue(host.scrollBackwardPaths.isEmpty())
        assertEquals(0, host.captureCount)
    }

    @Test
    fun `a rejection with a known package writes a failed marker carrying the reason`() {
        val host = host(phase = CrawlerPhase.SCANNING_TARGET_SCREEN, foreground = targetPackage)

        val outcome = capture(host) as SnapshotCaptureOutcome.Rejected

        val directory = outcome.directory!!
        assertEquals(
            SnapshotRejectionReason.CRAWL_IN_PROGRESS.wireValue,
            File(directory, SnapshotFileStore.FAILED_MARKER).readText(),
        )
        assertFalse(File(directory, SnapshotFileStore.DONE_MARKER).exists())
    }

    // -------------------------------------------------------------------- scroll discipline

    @Test
    fun `scroll disabled issues no scroll gestures and yields a single step`() {
        val host = host(root = scrollableRoot())

        val outcome = capture(host, SnapshotCaptureRequest(token = "tok", scroll = false))

        val result = (outcome as SnapshotCaptureOutcome.Captured).result
        assertTrue(
            "scroll=false must issue no forward gestures, got ${host.scrollForwardPaths}",
            host.scrollForwardPaths.isEmpty(),
        )
        assertTrue(
            "scroll=false must issue no backward gestures, got ${host.scrollBackwardPaths}",
            host.scrollBackwardPaths.isEmpty(),
        )
        assertEquals(1, result.scrollStepCount)
    }

    @Test
    fun `scroll enabled attempts the scrollable path and rewinds afterwards`() {
        val host = host(root = scrollableRoot())

        capture(host, SnapshotCaptureRequest(token = "tok", scroll = true))

        assertTrue(
            "scroll=true must attempt to scroll the primary scrollable",
            host.scrollForwardPaths.isNotEmpty(),
        )
        assertTrue(
            "scroll=true must rewind to the top of the screen",
            host.scrollBackwardPaths.isNotEmpty(),
        )
    }

    @Test
    fun `scroll disabled records the flag in the snapshot metadata`() {
        val host = host(root = scrollableRoot())

        val outcome = capture(host, SnapshotCaptureRequest(token = "tok", scroll = false))

        val json = (outcome as SnapshotCaptureOutcome.Captured).result.metadataFile.readText()
        assertTrue(json.contains(""""scrollEnabled": false"""))
    }

    // -------------------------------------------------------------------------- artifacts

    @Test
    fun `a successful capture writes a complete snapshot directory`() {
        val host = host()

        val result = (capture(host) as SnapshotCaptureOutcome.Captured).result

        assertTrue(File(result.directory, SnapshotFileStore.DONE_MARKER).exists())
        assertTrue(result.files.htmlFile.exists())
        assertTrue(result.files.xmlFile.exists())
        assertEquals(targetPackage, result.packageName)
    }

    @Test
    fun `snapshot output never lands inside the crawl directory`() {
        val host = host()
        val crawlDir = File(temporaryFolder.root, "html/$targetPackage/crawl").apply { mkdirs() }
        File(crawlDir, "crawl-index.json").writeText("{}")

        val result = (capture(host) as SnapshotCaptureOutcome.Captured).result

        assertFalse(result.directory.canonicalPath.contains("${File.separator}crawl${File.separator}"))
        assertEquals(listOf("crawl-index.json"), crawlDir.list()!!.toList())
    }

    @Test
    fun `a name override drives the output directory label`() {
        val host = host()

        val result = (
            capture(host, SnapshotCaptureRequest(token = "tok", nameOverride = "empty cart"))
                as SnapshotCaptureOutcome.Captured
            ).result

        assertTrue(result.directory.name.endsWith("_empty_cart"))
    }

    @Test
    fun `a foreground window that disappears fails rather than throwing`() {
        val host = object : SnapshotCaptureCoordinator.Host by host() {
            override suspend fun captureCurrentRootSnapshot(expectedPackageName: String?) = null
        }

        val outcome = capture(host)

        assertTrue(outcome is SnapshotCaptureOutcome.Failed)
    }

    // ------------------------------------------------------------- structural safety guard

    @Test
    fun `the host interface exposes exactly the capability set a snapshot needs`() {
        // Pinning the exact member set, not scanning for banned substrings: `scrollBackward` is a
        // legitimate rewind and `foregroundPackageName` is a read, so a substring scan produces
        // false positives while still missing a member named innocuously. An exact set also fails
        // when anyone *adds* a capability, which is the case that would erode the guarantee.
        val actual = SnapshotCaptureCoordinator.Host::class.java.methods.map { it.name }.toSortedSet()

        val expected = sortedSetOf(
            "currentCrawlPhase",
            "foregroundPackageName",
            "appLabelFor",
            "captureCurrentRootSnapshot",
            "scrollForward",
            "scrollBackward",
            "baseDirectory",
            "publishProgress",
        )

        assertEquals(
            "SnapshotCaptureCoordinator.Host gained or lost a member. The no-launch / " +
                "no-back-navigation / no-foregrounding guarantee is structural: there must be no " +
                "method to call. Adding one is a scope decision, not a refactor.",
            expected,
            actual,
        )
    }

    // ---------------------------------------------------------------------------- helpers

    private fun assertRejectedForPhase(phase: CrawlerPhase) {
        val outcome = capture(host(phase = phase))

        assertTrue("phase $phase should have been rejected", outcome is SnapshotCaptureOutcome.Rejected)
        assertEquals(
            SnapshotRejectionReason.CRAWL_IN_PROGRESS,
            (outcome as SnapshotCaptureOutcome.Rejected).reason,
        )
    }

    private fun host(
        phase: CrawlerPhase = CrawlerPhase.IDLE,
        foreground: String? = targetPackage,
        root: AccessibilityNodeSnapshot = plainRoot(),
    ) = FakeHost(
        baseDir = temporaryFolder.root,
        phase = phase,
        foreground = foreground,
        root = root,
    )

    private fun capture(
        host: SnapshotCaptureCoordinator.Host,
        request: SnapshotCaptureRequest = SnapshotCaptureRequest(token = "tok"),
    ): SnapshotCaptureOutcome = runBlocking {
        SnapshotCaptureCoordinator(host = host, timeProvider = { 1_700_000_000_000L }).capture(request)
    }

    private fun plainRoot() = AccessibilityNodeSnapshot(
        className = "android.widget.FrameLayout",
        packageName = targetPackage,
        viewIdResourceName = "$targetPackage:id/root",
        text = null,
        contentDescription = null,
        clickable = false,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,0][1080,2400]",
        children = listOf(
            AccessibilityNodeSnapshot(
                className = "android.widget.Button",
                packageName = targetPackage,
                viewIdResourceName = "$targetPackage:id/open",
                text = "Open",
                contentDescription = null,
                clickable = true,
                supportsClickAction = true,
                scrollable = false,
                enabled = true,
                visibleToUser = true,
                bounds = "[24,220][820,340]",
                children = emptyList(),
                childIndexPath = listOf(0),
            ),
        ),
    )

    private fun scrollableRoot() = AccessibilityNodeSnapshot(
        className = "android.widget.FrameLayout",
        packageName = targetPackage,
        viewIdResourceName = "$targetPackage:id/root",
        text = null,
        contentDescription = null,
        clickable = false,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,0][1080,2400]",
        children = listOf(
            AccessibilityNodeSnapshot(
                className = "androidx.recyclerview.widget.RecyclerView",
                packageName = targetPackage,
                viewIdResourceName = "$targetPackage:id/list",
                text = null,
                contentDescription = null,
                clickable = false,
                supportsClickAction = false,
                scrollable = true,
                enabled = true,
                visibleToUser = true,
                bounds = "[0,100][1080,2400]",
                childIndexPath = listOf(0),
                children = listOf(
                    AccessibilityNodeSnapshot(
                        className = "android.widget.Button",
                        packageName = targetPackage,
                        viewIdResourceName = "$targetPackage:id/row",
                        text = "Row 1",
                        contentDescription = null,
                        clickable = true,
                        supportsClickAction = true,
                        scrollable = false,
                        enabled = true,
                        visibleToUser = true,
                        bounds = "[24,220][820,340]",
                        children = emptyList(),
                        childIndexPath = listOf(0, 0),
                    ),
                ),
            ),
        ),
    )
}
