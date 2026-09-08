package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityXmlSerializerTest {
    @Test
    fun serialize_with_crawl_state_emits_crawl_block_with_identity_parent_and_route() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val identity = ScreenIdentityFields(
            packageName = "com_example_target",
            title = "home",
            hints = listOf("welcome"),
        )
        val parent = ParentEdgeRef(
            screenId = "screen_00000",
            triggerLabel = "Open",
            triggerResourceId = "com.example.target:id/open",
        )
        val route = CrawlRoute(steps = listOf(routeStep()))
        val crawlState = ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = false,
            screenIdentity = identity,
            parent = parent,
            route = route,
            runLevel = null,
            edgesByElement = emptyMap(),
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)

        assertTrue(
            xml.contains("""<crawl schema="v1" screen-id="screen_00001" depth="1" expansion-status="in_progress" is-root="false">""")
        )
        assertTrue(
            xml.contains("""<screen-identity package="com_example_target" title="home" hint-1="welcome" />""")
        )
        assertTrue(
            xml.contains(
                """<parent screen-id="screen_00000" trigger-label="Open" trigger-resource-id="com.example.target:id/open" />"""
            )
        )
        assertTrue(xml.contains("<route>"))
        assertTrue(xml.contains("<step"))
        assertTrue(xml.contains("</crawl>"))
        assertFalse("non-root XML omits run-level attributes", xml.contains("session-id="))
    }

    @Test
    fun root_screen_xml_includes_run_level_attributes() {
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = emptyList(),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = ScreenCrawlState(
            screenId = "screen_00000",
            depth = 0,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = true,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "home",
                hints = emptyList(),
            ),
            parent = null,
            route = CrawlRoute(),
            runLevel = RunLevelState(
                sessionId = "session-1",
                startedAt = 1700000000000L,
                finishedAt = 1700000005000L,
                status = CrawlRunStatus.COMPLETED,
                maxDepthReached = 2,
            ),
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)

        assertTrue(xml.contains("""is-root="true""""))
        assertTrue(xml.contains("""session-id="session-1""""))
        assertTrue(xml.contains("""started-at="1700000000000""""))
        assertTrue(xml.contains("""finished-at="1700000005000""""))
        assertTrue(xml.contains("""status="completed""""))
        assertTrue(xml.contains("""max-depth-reached="2""""))
        assertFalse(xml.contains("<parent"))
    }

    @Test
    fun element_with_pending_edge_emits_edge_child() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = baseCrawlState(
            edges = mapOf(
                element.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_004",
                    status = CrawlEdgeStatus.PENDING,
                )
            )
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)

        assertTrue(xml.contains("""<edge id="edge_004" status="pending" />"""))
        assertTrue(xml.contains("<element"))
        assertTrue(xml.contains("</element>"))
    }

    @Test
    fun element_with_captured_edge_emits_child_attributes() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = baseCrawlState(
            edges = mapOf(
                element.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.CAPTURED,
                    childScreenId = "screen_00007",
                    childScreenName = "Detail",
                    externalPackage = "com.example.other",
                )
            )
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)

        assertTrue(
            xml.contains(
                """<edge id="edge_001" status="captured" child-screen-id="screen_00007" child-screen-name="Detail" external-package="com.example.other" />"""
            )
        )
    }

    @Test
    fun element_with_no_edge_emits_self_closing() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = baseCrawlState(edges = emptyMap())

        val xml = AccessibilityXmlSerializer.serialize(snapshot, crawlState)

        // first-seen-step is now followed by the fingerprint attribute, then self-closes.
        assertTrue(
            xml.contains(
                "first-seen-step=\"0\" fingerprint=\"com.example.target:id/open|open|android.widget.Button|false|false|false\" />"
            )
        )
        assertFalse(xml.contains("<edge"))
        assertFalse(xml.contains("</element>"))
    }

    @Test
    fun serialize_without_crawl_state_matches_legacy_output() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot)

        assertFalse(xml.contains("<crawl"))
        assertTrue(xml.contains("<merged-elements>"))
    }

    @Test
    fun screenIdentityCodec_roundtrips_with_two_hints() {
        val encoded = ScreenIdentityCodec.encode(
            packageName = "com.example.app",
            title = "Settings",
            hints = listOf("Notifications", "Privacy"),
        )
        val decoded = ScreenIdentityCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("com_example_app", decoded!!.packageName)
        assertEquals("settings", decoded.title)
        assertEquals(listOf("notifications", "privacy"), decoded.hints)
    }

    @Test
    fun screenIdentityCodec_roundtrips_with_zero_hints_using_none_placeholder() {
        val encoded = ScreenIdentityCodec.encode(
            packageName = "com.example.app",
            title = "Settings",
            hints = emptyList(),
        )
        assertTrue(encoded.endsWith(":hint:none"))
        val decoded = ScreenIdentityCodec.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded!!.hints.isEmpty())
    }

    @Test
    fun screenIdentityCodec_round_trip_matches_screenNaming_fingerprint() {
        val name = "Account Settings"
        val pkg = "com.example.app"
        val expected = ScreenNaming.dedupFingerprint(screenName = name, packageName = pkg)
        val encoded = ScreenIdentityCodec.encode(packageName = pkg, title = name, hints = emptyList())
        assertEquals(expected, encoded)
    }

    @Test
    fun replayFingerprintCodec_decodes_element_with_empty_resource_id() {
        val rootClass = "android.widget.FrameLayout"
        val elements = listOf(
            ReplayFingerprintCodec.ElementFields(
                label = "Open",
                resourceId = "",
                className = "android.widget.Button",
                isListItem = "false",
                checkable = "false",
                editable = "false",
            ),
            ReplayFingerprintCodec.ElementFields(
                label = "Close",
                resourceId = "com.example:id/close",
                className = "android.widget.Button",
                isListItem = "false",
                checkable = "false",
                editable = "false",
            ),
        )
        val encoded = ReplayFingerprintCodec.encode(rootClass, elements)
        val decoded = ReplayFingerprintCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(rootClass, decoded!!.rootClass)
        assertEquals(2, decoded.elements.size)
        assertEquals("", decoded.elements[0].resourceId)
        assertEquals("com.example:id/close", decoded.elements[1].resourceId)
    }

    @Test
    fun replayFingerprintCodec_decodes_empty_payload() {
        val decoded = ReplayFingerprintCodec.decode("android.widget.FrameLayout::")
        assertNotNull(decoded)
        assertEquals("android.widget.FrameLayout", decoded!!.rootClass)
        assertTrue(decoded.elements.isEmpty())
    }

    @Test
    fun replayFingerprintCodec_returns_null_for_malformed_input() {
        assertNull(ReplayFingerprintCodec.decode("no-separator"))
    }

    @Test
    fun merged_element_carries_fingerprint_attribute() {
        val element = pressable(label = "Open", resourceId = "com.example.target:id/open")
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )

        val xml = AccessibilityXmlSerializer.serialize(snapshot)

        assertTrue(xml.contains("""fingerprint="${ElementFingerprint.of(element).encoded}""""))
    }

    @Test
    fun pressable_node_and_merged_element_share_one_fingerprint_and_non_pressable_nodes_have_none() {
        val expected = ElementFingerprint.ofFields(
            label = "Open",
            resourceId = "com.example.target:id/open",
            className = "android.widget.Button",
            isListItem = false,
            checkable = false,
            editable = false,
        ).encoded

        val button = node(
            className = "android.widget.Button",
            resourceId = "com.example.target:id/open",
            text = "Open",
            clickable = true,
            childIndexPath = listOf(0),
        )
        val container = node(
            className = "android.widget.FrameLayout",
            resourceId = null,
            text = null,
            clickable = false,
            children = listOf(button),
        )

        // serialize(screenName, packageName, root) is the synthetic _merged_accessibility.xml path.
        val nodeXml = AccessibilityXmlSerializer.serialize(
            screenName = "Home",
            packageName = "com.example.target",
            root = container,
        )
        val elementXml = AccessibilityXmlSerializer.serialize(
            ScreenSnapshot(
                screenName = "Home",
                packageName = "com.example.target",
                elements = listOf(
                    pressable(label = "Open", resourceId = "com.example.target:id/open"),
                ),
                xmlDump = "",
                scrollStepCount = 1,
            )
        )

        // The pressable node carries the fingerprint; the non-pressable container does not (exactly one).
        assertTrue(nodeXml.contains("""fingerprint="$expected""""))
        assertEquals(1, nodeXml.split("fingerprint=").size - 1)
        // Same button -> byte-identical fingerprint in the merged <element>.
        assertTrue(elementXml.contains("""fingerprint="$expected""""))
    }

    private fun node(
        className: String?,
        resourceId: String?,
        text: String?,
        clickable: Boolean,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
        childIndexPath: List<Int> = emptyList(),
    ): AccessibilityNodeSnapshot = AccessibilityNodeSnapshot(
        className = className,
        packageName = "com.example.target",
        viewIdResourceName = resourceId,
        text = text,
        contentDescription = null,
        clickable = clickable,
        supportsClickAction = clickable,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,0][100,100]",
        children = children,
        childIndexPath = childIndexPath,
    )

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

    private fun routeStep(): CrawlRouteStep {
        return CrawlRouteStep(
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
            expectedDestinationFingerprint = ScreenIdentityCodec.encode(
                packageName = "com.example.target",
                title = "Detail",
                hints = listOf("Account"),
            ),
            expectedReplayFingerprint = ReplayFingerprintCodec.encode(
                rootClass = "android.widget.FrameLayout",
                elements = listOf(
                    ReplayFingerprintCodec.ElementFields(
                        label = "Open",
                        resourceId = "",
                        className = "android.widget.Button",
                        isListItem = "false",
                        checkable = "false",
                        editable = "false",
                    )
                ),
            ),
            expectedReplayScreenName = "Detail",
        )
    }

    private fun baseCrawlState(
        edges: Map<PressableElementLinkKey, EdgeXmlView>,
    ): ScreenCrawlState {
        return ScreenCrawlState(
            screenId = "screen_00001",
            depth = 1,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = false,
            screenIdentity = ScreenIdentityFields(
                packageName = "com_example_target",
                title = "home",
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
    }
}
