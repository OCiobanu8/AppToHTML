package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CrawlRunTrackerTest {
    @Test
    fun addPendingEdge_mints_edge_id_and_stores_pending_status() {
        val tracker = CrawlRunTracker(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
        )

        val edgeId = tracker.addPendingEdge(
            parentScreenId = "screen_00000",
            element = sampleElement(),
        )

        assertEquals("edge_000", edgeId)
        val edge = tracker.findEdge(edgeId)
        assertNotNull(edge)
        assertEquals(CrawlEdgeStatus.PENDING, edge!!.status)
        assertEquals("screen_00000", edge.parentScreenId)
        assertNull(edge.childScreenId)
        assertNull(edge.childScreenName)
    }

    @Test
    fun updateEdgeStatus_preserves_edge_id_across_pending_to_captured() {
        val tracker = CrawlRunTracker(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
        )
        val edgeId = tracker.addPendingEdge(
            parentScreenId = "screen_00000",
            element = sampleElement(),
        )

        tracker.updateEdgeStatus(
            edgeId = edgeId,
            status = CrawlEdgeStatus.CAPTURED,
            childScreenId = "screen_00001",
            childScreenName = "Settings",
        )

        val edge = tracker.findEdge(edgeId)
        assertNotNull(edge)
        assertEquals(edgeId, edge!!.edgeId)
        assertEquals(CrawlEdgeStatus.CAPTURED, edge.status)
        assertEquals("screen_00001", edge.childScreenId)
        assertEquals("Settings", edge.childScreenName)
    }

    @Test
    fun updateEdgeStatus_stamps_external_package_and_preserves_it() {
        val tracker = CrawlRunTracker(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
        )
        val edgeId = tracker.addPendingEdge(
            parentScreenId = "screen_00000",
            element = sampleElement(),
        )

        tracker.updateEdgeStatus(
            edgeId = edgeId,
            status = CrawlEdgeStatus.IN_PROGRESS,
            externalPackage = "com.external.app",
        )

        assertEquals("com.external.app", tracker.findEdge(edgeId)!!.externalPackage)

        tracker.updateEdgeStatus(
            edgeId = edgeId,
            status = CrawlEdgeStatus.CAPTURED,
            childScreenId = "screen_00001",
        )

        val edge = tracker.findEdge(edgeId)
        assertNotNull(edge)
        assertEquals(CrawlEdgeStatus.CAPTURED, edge!!.status)
        assertEquals("screen_00001", edge.childScreenId)
        assertEquals("com.external.app", edge.externalPackage)
    }

    @Test
    fun setScreenExpansionStatus_updates_record_in_place() {
        val tracker = CrawlRunTracker(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
        )
        addRootScreen(tracker, screenId = "screen_00000")

        tracker.setScreenExpansionStatus(
            screenId = "screen_00000",
            status = ScreenExpansionStatus.IN_PROGRESS,
        )

        val screen = tracker.findScreen("screen_00000")
        assertNotNull(screen)
        assertEquals(ScreenExpansionStatus.IN_PROGRESS, screen!!.expansionStatus)

        tracker.setScreenExpansionStatus(
            screenId = "screen_00000",
            status = ScreenExpansionStatus.COMPLETE,
        )
        assertEquals(
            ScreenExpansionStatus.COMPLETE,
            tracker.findScreen("screen_00000")!!.expansionStatus,
        )
    }

    @Test
    fun outboundEdges_filters_by_parent_screen_id() {
        val tracker = CrawlRunTracker(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
        )
        val edgeA = tracker.addPendingEdge("screen_00000", sampleElement(label = "A"))
        val edgeB = tracker.addPendingEdge("screen_00000", sampleElement(label = "B"))
        tracker.addPendingEdge("screen_00001", sampleElement(label = "C"))

        val outbound = tracker.outboundEdges("screen_00000").map { it.edgeId }
        assertEquals(listOf(edgeA, edgeB), outbound)
    }

    @Test
    fun fromExistingState_exposes_screens_and_fingerprint_index() {
        val rootScreen = sampleScreen(
            screenId = "screen_00000",
            fingerprint = "v2:pkg:com.example:title:Root:hint:none|none",
            depth = 0,
        )
        val childScreen = sampleScreen(
            screenId = "screen_00001",
            fingerprint = "v2:pkg:com.example:title:Settings:hint:none|none",
            depth = 1,
            parentScreenId = "screen_00000",
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
        )
        val edge = CrawlEdgeRecord(
            edgeId = "edge_000",
            parentScreenId = "screen_00000",
            childScreenId = "screen_00001",
            label = "Settings",
            resourceId = null,
            className = "Button",
            bounds = "[0,0][10,10]",
            childIndexPath = listOf(0, 1),
            firstSeenStep = 0,
            status = CrawlEdgeStatus.CAPTURED,
        )

        val tracker = CrawlRunTracker.fromExistingState(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
            screens = listOf(rootScreen, childScreen),
            edges = listOf(edge),
            screenFingerprintToId = linkedMapOf(
                rootScreen.screenFingerprint to rootScreen.screenId,
                childScreen.screenFingerprint to childScreen.screenId,
            ),
            rootScreenId = "screen_00000",
            nextScreenSequence = 2,
            nextEdgeSequence = 1,
        )

        assertEquals("screen_00000", tracker.findScreen("screen_00000")?.screenId)
        assertEquals(
            ScreenExpansionStatus.IN_PROGRESS,
            tracker.findScreen("screen_00001")?.expansionStatus,
        )
        assertEquals(
            "screen_00000",
            tracker.findScreenIdByFingerprint(rootScreen.screenFingerprint),
        )
        assertEquals(
            "screen_00001",
            tracker.findScreenIdByFingerprint(childScreen.screenFingerprint),
        )
        assertEquals(listOf("edge_000"), tracker.outboundEdges("screen_00000").map { it.edgeId })
    }

    @Test
    fun nextScreenSequenceNumber_after_fromExistingState_returns_max_plus_one() {
        val rootScreen = sampleScreen(
            screenId = "screen_00000",
            fingerprint = "v2:pkg:com.example:title:Root:hint:none|none",
            depth = 0,
        )
        val laterScreen = sampleScreen(
            screenId = "screen_00007",
            fingerprint = "v2:pkg:com.example:title:Seven:hint:none|none",
            depth = 1,
            parentScreenId = "screen_00000",
        )

        val tracker = CrawlRunTracker.fromExistingState(
            sessionId = "session",
            packageName = "com.example.app",
            startedAt = 1L,
            screens = listOf(rootScreen, laterScreen),
            edges = emptyList(),
            screenFingerprintToId = linkedMapOf(
                rootScreen.screenFingerprint to rootScreen.screenId,
                laterScreen.screenFingerprint to laterScreen.screenId,
            ),
            rootScreenId = "screen_00000",
            nextScreenSequence = 8,
            nextEdgeSequence = 0,
        )

        assertEquals(8, tracker.nextScreenSequenceNumber())
        assertEquals(9, tracker.nextScreenSequenceNumber())
    }

    @Test
    fun fromExistingState_rejects_sequence_counter_below_max() {
        val screen = sampleScreen(
            screenId = "screen_00005",
            fingerprint = "v2:pkg:com.example:title:Five:hint:none|none",
            depth = 0,
        )

        try {
            CrawlRunTracker.fromExistingState(
                sessionId = "session",
                packageName = "com.example.app",
                startedAt = 1L,
                screens = listOf(screen),
                edges = emptyList(),
                screenFingerprintToId = linkedMapOf(screen.screenFingerprint to screen.screenId),
                rootScreenId = screen.screenId,
                nextScreenSequence = 5,
                nextEdgeSequence = 0,
            )
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("nextScreenSequence"))
        }
    }

    @Test
    fun fromExistingState_rejects_multiple_in_progress_screens() {
        val s0 = sampleScreen(
            screenId = "screen_00000",
            fingerprint = "v2:pkg:com.example:title:A:hint:none|none",
            depth = 0,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
        )
        val s1 = sampleScreen(
            screenId = "screen_00001",
            fingerprint = "v2:pkg:com.example:title:B:hint:none|none",
            depth = 1,
            parentScreenId = "screen_00000",
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
        )

        try {
            CrawlRunTracker.fromExistingState(
                sessionId = "session",
                packageName = "com.example.app",
                startedAt = 1L,
                screens = listOf(s0, s1),
                edges = emptyList(),
                screenFingerprintToId = linkedMapOf(
                    s0.screenFingerprint to s0.screenId,
                    s1.screenFingerprint to s1.screenId,
                ),
                rootScreenId = s0.screenId,
                nextScreenSequence = 2,
                nextEdgeSequence = 0,
            )
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("IN_PROGRESS"))
        }
    }

    @Test
    fun parseScreenSequence_extracts_index_from_id() {
        assertEquals(0, CrawlRunTracker.parseScreenSequence("screen_00000"))
        assertEquals(42, CrawlRunTracker.parseScreenSequence("screen_00042"))
        assertNull(CrawlRunTracker.parseScreenSequence("not_a_screen"))
    }

    private fun sampleElement(label: String = "Settings"): PressableElement {
        return PressableElement(
            label = label,
            resourceId = null,
            bounds = "[0,0][10,10]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(0, 1),
        )
    }

    private fun addRootScreen(tracker: CrawlRunTracker, screenId: String) {
        tracker.nextScreenSequenceNumber()
        tracker.addScreen(
            screenId = screenId,
            snapshot = ScreenSnapshot(
                screenName = "Root",
                packageName = "com.example.app",
                elements = emptyList(),
                xmlDump = "<screen/>",
            ),
            screenFingerprint = "v2:pkg:com.example.app:title:Root:hint:none|none",
            replayFingerprint = "",
            files = CapturedScreenFiles(
                htmlFile = File("/tmp/${screenId}.html"),
                xmlFile = File("/tmp/${screenId}.xml"),
            ),
            parentScreenId = null,
            triggerElement = null,
            route = CrawlRoute(),
            depth = 0,
        )
    }

    private fun sampleScreen(
        screenId: String,
        fingerprint: String,
        depth: Int,
        parentScreenId: String? = null,
        expansionStatus: ScreenExpansionStatus = ScreenExpansionStatus.NOT_STARTED,
    ): CrawlScreenRecord {
        return CrawlScreenRecord(
            screenId = screenId,
            screenName = "Screen $screenId",
            packageName = "com.example.app",
            screenFingerprint = fingerprint,
            replayFingerprint = "",
            htmlPath = "/tmp/${screenId}.html",
            xmlPath = "/tmp/${screenId}.xml",
            scrollStepCount = 1,
            parentScreenId = parentScreenId,
            triggerLabel = null,
            triggerResourceId = null,
            depth = depth,
            expansionStatus = expansionStatus,
        )
    }
}
