package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenXmlReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * The zero-element branch, which the retired `replayFingerprintCodec_decodes_empty_payload`
     * used to cover. A destination screen with no pressables is reachable in production, and the
     * writer takes a self-closing path for it.
     */
    @Test
    fun readFull_round_trips_a_step_identity_with_no_elements() {
        val empty = ScreenIdentity(
            packageName = "com.example.target",
            rootClassName = "android.widget.FrameLayout",
            elements = emptySet(),
        )
        val step = CrawlRouteStep(
            childIndexPath = listOf(0),
            bounds = "[0,0][100,100]",
            resourceId = null,
            className = null,
            label = "Open",
            checkable = false,
            checked = false,
            editable = false,
            firstSeenStep = 0,
            expectedPackageName = "com.example.target",
            expectedDestinationIdentity = null,
            expectedReplayIdentity = empty,
            expectedReplayScreenName = "Empty",
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = false,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "empty",
                titleDisambiguators = emptyList(),
            ),
            parent = ParentEdgeRef(screenId = "screen_00000", triggerLabel = "Open", triggerResourceId = null),
            route = CrawlRoute(steps = listOf(step)),
            runLevel = null,
            edgesByElement = emptyMap(),
        )
        val snapshot = ScreenSnapshot(
            screenName = "Empty",
            packageName = "com.example.target",
            elements = emptyList(),
            xmlDump = "",
        )

        val file = File.createTempFile("screen_empty_roundtrip", ".xml")
        try {
            file.writeText(AccessibilityXmlSerializer.serialize(snapshot, crawlState))
            val payload = ScreenXmlReader.readFull(file)

            assertNotNull(payload)
            assertEquals(empty, payload!!.head.route.steps.single().expectedReplayIdentity)
        } finally {
            file.delete()
        }
    }

    /**
     * The persistence surface SCOPE requires to round-trip: a step identity carrying two title
     * disambiguators on the screen and a flagged back affordance in its element set must survive
     * write -> read unchanged. Exercises the real writer/reader pair, not just the string codec.
     */
    @Test
    fun readFull_round_trips_a_step_identity_with_a_flagged_back_affordance() {
        val backElement = ScreenElementIdentity(
            fingerprint = ElementFingerprint.ofFields(
                label = "Navigate up",
                resourceId = "com.example.target:id/back_button",
                className = "android.widget.ImageButton",
                isListItem = false,
                checkable = false,
                editable = false,
            ),
            isBackAffordance = true,
        )
        val plainElement = ScreenElementIdentity(
            fingerprint = ElementFingerprint.ofFields(
                label = "Open",
                resourceId = "",
                className = "android.widget.Button",
                isListItem = false,
                checkable = false,
                editable = false,
            ),
            isBackAffordance = false,
        )
        // Round-7 R03: a pressable with no class name. The writer emits `class=""`; the reader must
        // map that back to null, or the element never equals its live counterpart after resume.
        val classlessElement = ScreenElementIdentity(
            fingerprint = ElementFingerprint.ofFields(
                label = "Details",
                resourceId = "com.example.target:id/details",
                className = "",
                isListItem = false,
                checkable = false,
                editable = false,
            ),
            isBackAffordance = false,
        )
        val stepIdentity = ScreenIdentity(
            packageName = "com.example.target",
            rootClassName = "android.widget.FrameLayout",
            elements = setOf(backElement, plainElement, classlessElement),
        )
        val step = CrawlRouteStep(
            childIndexPath = listOf(0),
            bounds = "[0,0][100,100]",
            resourceId = "com.example.target:id/open",
            className = "android.widget.Button",
            label = "Open",
            checkable = false,
            checked = false,
            editable = false,
            firstSeenStep = 0,
            expectedPackageName = "com.example.target",
            expectedDestinationIdentity = stepIdentity,
            expectedReplayIdentity = stepIdentity,
            expectedReplayScreenName = "Detail",
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = false,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "detail",
                titleDisambiguators = listOf("account", "billing"),
            ),
            parent = ParentEdgeRef(screenId = "screen_00000", triggerLabel = "Open", triggerResourceId = null),
            route = CrawlRoute(steps = listOf(step)),
            runLevel = null,
            edgesByElement = emptyMap(),
        )
        val snapshot = ScreenSnapshot(
            screenName = "Detail",
            packageName = "com.example.target",
            elements = emptyList(),
            xmlDump = "",
        )

        val file = File.createTempFile("screen_roundtrip", ".xml")
        try {
            file.writeText(AccessibilityXmlSerializer.serialize(snapshot, crawlState))
            val payload = ScreenXmlReader.readFull(file)

            assertNotNull(payload)
            val head = payload!!.head
            assertEquals(listOf("account", "billing"), head.screenIdentity.titleDisambiguators)
            val parsedStep = head.route.steps.single()
            assertEquals(stepIdentity, parsedStep.expectedReplayIdentity)
            assertEquals(stepIdentity, parsedStep.expectedDestinationIdentity)
            assertTrue(
                parsedStep.expectedReplayIdentity!!.elements.single { it.isBackAffordance }
                    .fingerprint.label == "navigate up"
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun readFull_round_trips_serialized_xml() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Detail",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 3,
        )
        val identity = ScreenIdentityFields(
            packageName = "com_example_target",
            title = "detail",
            titleDisambiguators = listOf("account"),
        )
        val parent = ParentEdgeRef(
            screenId = "screen_00000",
            triggerLabel = "Open",
            triggerResourceId = "com.example.target:id/open",
        )
        val step = CrawlRouteStep(
            childIndexPath = listOf(0, 1),
            bounds = "[0,0][100,100]",
            resourceId = "com.example.target:id/open",
            className = "android.widget.Button",
            label = "Open",
            checkable = false,
            checked = false,
            editable = false,
            firstSeenStep = 0,
            expectedPackageName = "com.example.target",
            expectedDestinationIdentity = ScreenIdentity(
                packageName = "com.example.target",
                rootClassName = "android.widget.FrameLayout",
                elements = emptySet(),
            ),
            expectedReplayIdentity = null,
            expectedReplayScreenName = "Detail",
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = false,
            screenIdentity = identity,
            parent = parent,
            route = CrawlRoute(steps = listOf(step)),
            runLevel = null,
            edgesByElement = mapOf(
                element.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_007",
                    status = CrawlEdgeStatus.CAPTURED,
                    childScreenId = "screen_00002",
                    childScreenName = "Account",
                    externalPackage = "com.example.external",
                ),
            ),
        )
        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)
        val file = writeXml("screen_00001_detail.xml", xml)

        val payload = ScreenXmlReader.readFull(file)
        assertNotNull(payload)
        val head = payload!!.head
        assertEquals("Detail", head.screenName)
        assertEquals("com.example.target", head.screenPackage)
        assertEquals(3, head.scrollStepCount)
        assertEquals("screen_00001", head.screenId)
        assertEquals(1, head.depth)
        assertEquals(ScreenExpansionStatus.IN_PROGRESS, head.expansionStatus)
        assertEquals(false, head.isRoot)
        assertEquals("com_example_target", head.screenIdentity.packageName)
        assertEquals("detail", head.screenIdentity.title)
        assertEquals(listOf("account"), head.screenIdentity.titleDisambiguators)
        assertEquals(parent, head.parent)
        assertEquals(1, head.route.steps.size)
        val parsedStep = head.route.steps[0]
        assertEquals("Open", parsedStep.label)
        assertEquals("Detail", parsedStep.expectedReplayScreenName)
        assertEquals(step.expectedDestinationIdentity, parsedStep.expectedDestinationIdentity)
        assertNull(head.runLevel)
        assertEquals(1, payload.elements.size)
        assertEquals("Open", payload.elements[0].label)
        val edge = payload.edgeByElement[element.toLinkKey()]
        assertNotNull(edge)
        assertEquals("edge_007", edge!!.edgeId)
        assertEquals(CrawlEdgeStatus.CAPTURED, edge.status)
        assertEquals("screen_00002", edge.childScreenId)
        assertEquals("Account", edge.childScreenName)
        assertEquals("com.example.external", edge.externalPackage)
    }

    @Test
    fun readHead_returns_run_level_state_for_root_screen() {
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = emptyList(),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val runLevel = RunLevelState(
            sessionId = "session-1",
            startedAt = 1700000000000L,
            finishedAt = 1700000005000L,
            status = CrawlRunStatus.COMPLETED,
            maxDepthReached = 2,
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00000",
            depth = 0,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = true,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "home",
                titleDisambiguators = emptyList(),
            ),
            parent = null,
            route = CrawlRoute(),
            runLevel = runLevel,
        )
        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)
        val file = writeXml("screen_00000_home.xml", xml)

        val head = ScreenXmlReader.readHead(file)
        assertNotNull(head)
        assertEquals(true, head!!.isRoot)
        assertEquals(runLevel, head.runLevel)
        assertNull(head.parent)
    }

    @Test
    fun readFull_returns_null_for_malformed_xml() {
        val file = writeXml("malformed.xml", "<not-a-screen>")
        assertNull(ScreenXmlReader.readFull(file))
    }

    @Test
    fun readFull_returns_null_for_missing_file() {
        val file = File(tempFolder.root, "nonexistent.xml")
        assertNull(ScreenXmlReader.readFull(file))
    }

    @Test
    fun readFull_handles_element_without_edge() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.NOT_STARTED,
            isRoot = false,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "home",
                titleDisambiguators = emptyList(),
            ),
            parent = ParentEdgeRef(
                screenId = "screen_00000",
                triggerLabel = null,
                triggerResourceId = null,
            ),
            route = CrawlRoute(),
            runLevel = null,
            edgesByElement = emptyMap(),
        )
        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)
        val file = writeXml("screen_00001_home.xml", xml)

        val payload = ScreenXmlReader.readFull(file)
        assertNotNull(payload)
        assertEquals(1, payload!!.elements.size)
        assertTrue(payload.edgeByElement.isEmpty())
    }

    private fun writeXml(name: String, content: String): File {
        val file = File(tempFolder.root, name)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun pressable(
        label: String,
        resourceId: String,
        bounds: String = "[0,0][100,100]",
    ): PressableElement {
        return PressableElement(
            label = label,
            resourceId = resourceId,
            bounds = bounds,
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
