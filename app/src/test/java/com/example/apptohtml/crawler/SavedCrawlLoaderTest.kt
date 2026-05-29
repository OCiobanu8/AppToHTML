package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedCrawlLoaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val targetPackage = "com.example.target"
    private val externalPackage = "com.example.other"

    @Test
    fun load_returns_null_for_missing_directory() {
        val dir = File(tempFolder.root, "missing")
        assertNull(SavedCrawlLoader.load(dir))
    }

    @Test
    fun load_returns_null_when_directory_has_no_xml_files() {
        val dir = tempFolder.newFolder("empty")
        File(dir, "crawl-graph.json").writeText("{}", Charsets.UTF_8)
        assertNull(SavedCrawlLoader.load(dir))
    }

    @Test
    fun load_rebuilds_screens_edges_and_indexes() {
        val dir = tempFolder.newFolder("crawl")

        writeRootScreen(dir)
        writeChildScreen(
            dir = dir,
            screenId = "screen_00001",
            screenName = "Detail",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
        )
        writeChildScreen(
            dir = dir,
            screenId = "screen_00002",
            screenName = "Settings",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            pendingEdge = true,
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        assertEquals(3, loaded!!.screens.size)
        assertEquals("screen_00000", loaded.rootScreenId)
        assertEquals(3, loaded.nextScreenSequence)

        val statuses = loaded.screens.associate { it.screenId to it.expansionStatus }
        assertEquals(ScreenExpansionStatus.COMPLETE, statuses["screen_00000"])
        assertEquals(ScreenExpansionStatus.COMPLETE, statuses["screen_00001"])
        assertEquals(ScreenExpansionStatus.IN_PROGRESS, statuses["screen_00002"])

        assertNotNull(loaded.runLevel)
        assertEquals("session-1", loaded.runLevel!!.sessionId)
        assertEquals(CrawlRunStatus.IN_PROGRESS, loaded.runLevel!!.status)

        val rootFingerprint = ScreenIdentityCodec.encode(
            packageName = targetPackage,
            title = "Home",
            hints = emptyList(),
        )
        assertEquals("screen_00000", loaded.screenFingerprintToId[rootFingerprint])
    }

    @Test
    fun load_normalizes_failed_edges_to_pending_and_drops_message() {
        val dir = tempFolder.newFolder("crawl")

        writeRootScreen(
            dir = dir,
            edgesOverride = mapOf(
                rootTriggerElement().toLinkKey() to EdgeXmlView(
                    edgeId = "edge_005",
                    status = CrawlEdgeStatus.FAILED,
                    message = "boom",
                ),
            ),
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        val edge = loaded!!.edges.firstOrNull { it.edgeId == "edge_005" }
        assertNotNull(edge)
        assertEquals(CrawlEdgeStatus.PENDING, edge!!.status)
        assertNull(edge.message)
        assertEquals(6, loaded.nextEdgeSequence)
    }

    @Test
    fun load_collects_explicit_approval_packages() {
        val dir = tempFolder.newFolder("crawl")

        val trigger = rootTriggerElement()
        writeRootScreen(
            dir = dir,
            edgesOverride = mapOf(
                trigger.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.CAPTURED,
                    childScreenId = "screen_00001",
                    childScreenName = "External",
                    approval = CrawlEdgeApproval.EXPLICIT,
                ),
            ),
        )
        writeChildScreen(
            dir = dir,
            screenId = "screen_00001",
            screenName = "External",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            packageNameOverride = externalPackage,
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        assertTrue(loaded!!.allowedPackages.contains(externalPackage))
    }

    @Test
    fun load_hydrates_edge_external_package() {
        val dir = tempFolder.newFolder("crawl")

        val trigger = rootTriggerElement()
        writeRootScreen(
            dir = dir,
            edgesOverride = mapOf(
                trigger.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE,
                    externalPackage = externalPackage,
                ),
            ),
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        val edge = loaded!!.edges.firstOrNull { it.edgeId == "edge_001" }
        assertNotNull(edge)
        assertEquals(externalPackage, edge!!.externalPackage)
    }

    @Test
    fun load_collects_explicit_approval_package_from_edge_without_child_screen() {
        val dir = tempFolder.newFolder("crawl")

        val trigger = rootTriggerElement()
        writeRootScreen(
            dir = dir,
            edgesOverride = mapOf(
                trigger.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.IN_PROGRESS,
                    approval = CrawlEdgeApproval.EXPLICIT,
                    externalPackage = externalPackage,
                ),
            ),
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        assertTrue(loaded!!.allowedPackages.contains(externalPackage))
    }

    @Test
    fun load_rebuilds_resolved_links_for_captured_edges() {
        val dir = tempFolder.newFolder("crawl")

        val trigger = rootTriggerElement()
        writeRootScreen(
            dir = dir,
            edgesOverride = mapOf(
                trigger.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.CAPTURED,
                    childScreenId = "screen_00001",
                    childScreenName = "Detail",
                ),
            ),
        )
        writeChildScreen(
            dir = dir,
            screenId = "screen_00001",
            screenName = "Detail",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        val rootLinks = loaded!!.resolvedLinks["screen_00000"]
        assertNotNull(rootLinks)
        val htmlName = rootLinks!![trigger.toLinkKey()]
        assertEquals("screen_00001_detail.html", htmlName)
    }

    @Test
    fun load_tracker_hydration_round_trips() {
        val dir = tempFolder.newFolder("crawl")
        writeRootScreen(dir)
        writeChildScreen(
            dir = dir,
            screenId = "screen_00001",
            screenName = "Detail",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.NOT_STARTED,
        )

        val loaded = SavedCrawlLoader.load(dir)
        assertNotNull(loaded)
        val tracker = CrawlRunTracker.fromExistingState(
            sessionId = "session-2",
            packageName = targetPackage,
            startedAt = 2_000L,
            screens = loaded!!.screens,
            edges = loaded.edges,
            screenFingerprintToId = loaded.screenFingerprintToId,
            rootScreenId = loaded.rootScreenId,
            nextScreenSequence = loaded.nextScreenSequence,
            nextEdgeSequence = loaded.nextEdgeSequence,
        )
        assertEquals("screen_00000", tracker.currentRootScreenId)
        assertNotNull(tracker.findScreen("screen_00001"))
        assertEquals(2, tracker.capturedScreenCount())
        val rootFingerprint = ScreenIdentityCodec.encode(
            packageName = targetPackage,
            title = "Home",
            hints = emptyList(),
        )
        assertEquals("screen_00000", tracker.findScreenIdByFingerprint(rootFingerprint))
        assertEquals(loaded.nextScreenSequence, tracker.nextScreenSequenceNumber())
        // Confirm legacy merged xml files are ignored when scanning.
        assertFalse(loaded.screens.any { it.xmlPath.endsWith("_merged_accessibility.xml") })
    }

    private fun writeRootScreen(
        dir: File,
        edgesOverride: Map<PressableElementLinkKey, EdgeXmlView>? = null,
    ) {
        val trigger = rootTriggerElement()
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = targetPackage,
            elements = listOf(trigger),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00000",
            depth = 0,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = true,
            screenIdentity = ScreenIdentityFields(
                packageName = ScreenNaming.normalizeIdentityToken(targetPackage),
                title = ScreenNaming.normalizeIdentityToken("Home"),
                hints = emptyList(),
            ),
            parent = null,
            route = CrawlRoute(),
            runLevel = RunLevelState(
                sessionId = "session-1",
                startedAt = 1_000L,
                finishedAt = null,
                status = CrawlRunStatus.IN_PROGRESS,
                maxDepthReached = 1,
            ),
            edgesByElement = edgesOverride ?: emptyMap(),
        )
        val baseName = "screen_00000_home"
        File(dir, "$baseName.html").writeText("<html></html>", Charsets.UTF_8)
        File(dir, "$baseName.xml").writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState),
            Charsets.UTF_8,
        )
    }

    private fun writeChildScreen(
        dir: File,
        screenId: String,
        screenName: String,
        depth: Int,
        expansionStatus: ScreenExpansionStatus,
        pendingEdge: Boolean = false,
        packageNameOverride: String? = null,
    ) {
        val packageName = packageNameOverride ?: targetPackage
        val elements = if (pendingEdge) listOf(rootTriggerElement(label = "Subaction")) else emptyList()
        val snapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = packageName,
            elements = elements,
            xmlDump = "",
            scrollStepCount = 1,
        )
        val edges = if (pendingEdge) {
            mapOf(
                elements[0].toLinkKey() to EdgeXmlView(
                    edgeId = "edge_010",
                    status = CrawlEdgeStatus.PENDING,
                )
            )
        } else {
            emptyMap()
        }
        val crawlState = ScreenCrawlState(
            screenId = screenId,
            depth = depth,
            expansionStatus = expansionStatus,
            isRoot = false,
            screenIdentity = ScreenIdentityFields(
                packageName = ScreenNaming.normalizeIdentityToken(packageName),
                title = ScreenNaming.normalizeIdentityToken(screenName),
                hints = emptyList(),
            ),
            parent = ParentEdgeRef(
                screenId = "screen_00000",
                triggerLabel = "Open",
                triggerResourceId = null,
            ),
            route = CrawlRoute(),
            runLevel = null,
            edgesByElement = edges,
        )
        val baseName = "${screenId}_${ScreenNaming.toFileBase(screenName)}"
        File(dir, "$baseName.html").writeText("<html></html>", Charsets.UTF_8)
        File(dir, "$baseName.xml").writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState),
            Charsets.UTF_8,
        )
    }

    private fun rootTriggerElement(label: String = "Open"): PressableElement {
        return PressableElement(
            label = label,
            resourceId = "$targetPackage:id/open",
            bounds = "[0,0][100,100]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(0, 1),
            checkable = false,
            checked = false,
            editable = false,
            firstSeenStep = 0,
        )
    }
}
