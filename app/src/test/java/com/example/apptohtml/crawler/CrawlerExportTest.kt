package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class CrawlerExportTest {
    @Test
    fun fileBase_slugifies_screen_name() {
        assertEquals("home_screen", ScreenNaming.toFileBase("Home Screen"))
        assertEquals("captured_screen", ScreenNaming.toFileBase("!!!"))
    }

    @Test
    fun deriveScreenName_prefers_semantic_resource_ids_over_generic_recycler_view_class() {
        val selectedApp = SelectedAppRef(
            packageName = "com.android.settings",
            appName = "Settings",
            launcherActivity = "com.android.settings.Settings",
            selectedAt = 123L,
        )
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.settings",
            viewIdResourceName = null,
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
                    className = "android.widget.ScrollView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/settings_homepage_container",
                    text = null,
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = true,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,0][1080,2337]",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            className = "androidx.recyclerview.widget.RecyclerView",
                            packageName = "com.android.settings",
                            viewIdResourceName = "com.android.settings:id/recycler_view",
                            text = null,
                            contentDescription = null,
                            clickable = false,
                            supportsClickAction = false,
                            scrollable = true,
                            enabled = true,
                            visibleToUser = true,
                            bounds = "[0,388][1080,2337]",
                            children = emptyList(),
                        )
                    ),
                )
            ),
        )

        val screenName = ScreenNaming.deriveScreenName(
            eventClassName = "androidx.recyclerview.widget.RecyclerView",
            selectedApp = selectedApp,
            root = root,
        )

        assertEquals("Settings Homepage", screenName)
    }

    @Test
    fun deriveScreenName_prefers_top_visible_title_text_for_settings_child_screen() {
        val selectedApp = SelectedAppRef(
            packageName = "com.android.settings",
            appName = "Settings",
            launcherActivity = "com.android.settings.Settings",
            selectedAt = 123L,
        )
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.settings",
            viewIdResourceName = null,
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
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/action_bar_title",
                    text = "Sound & vibration",
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[48,72][640,148]",
                    children = emptyList(),
                ),
                AccessibilityNodeSnapshot(
                    className = "android.widget.ScrollView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/main_content_scrollable_container",
                    text = null,
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = true,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,180][1080,2337]",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            className = "android.widget.TextView",
                            packageName = "com.android.settings",
                            viewIdResourceName = "android:id/title",
                            text = "Media volume",
                            contentDescription = null,
                            clickable = false,
                            supportsClickAction = false,
                            scrollable = false,
                            enabled = true,
                            visibleToUser = true,
                            bounds = "[144,240][480,286]",
                            children = emptyList(),
                        )
                    ),
                )
            ),
        )

        val screenName = ScreenNaming.deriveScreenName(
            eventClassName = "android.widget.FrameLayout",
            selectedApp = selectedApp,
            root = root,
        )

        assertEquals("Sound & vibration", screenName)
    }

    @Test
    fun deriveScreenName_does_not_use_scrollable_row_text_as_screen_title() {
        val selectedApp = SelectedAppRef(
            packageName = "com.android.settings",
            appName = "Settings",
            launcherActivity = "com.android.settings.Settings",
            selectedAt = 123L,
        )
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.settings",
            viewIdResourceName = null,
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
                    className = "android.widget.ScrollView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/settings_homepage_container",
                    text = null,
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = true,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,0][1080,2337]",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            className = "android.widget.TextView",
                            packageName = "com.android.settings",
                            viewIdResourceName = "android:id/title",
                            text = "Search settings",
                            contentDescription = null,
                            clickable = false,
                            supportsClickAction = false,
                            scrollable = false,
                            enabled = true,
                            visibleToUser = true,
                            bounds = "[42,157][500,220]",
                            children = emptyList(),
                        )
                    ),
                )
            ),
        )

        val screenName = ScreenNaming.deriveScreenName(
            eventClassName = "android.widget.FrameLayout",
            selectedApp = selectedApp,
            root = root,
        )

        assertEquals("Settings Homepage", screenName)
    }

    @Test
    fun deriveScreenName_ignores_malformed_visible_text_and_falls_back_safely() {
        val selectedApp = SelectedAppRef(
            packageName = "com.android.settings",
            appName = "Settings",
            launcherActivity = "com.android.settings.Settings",
            selectedAt = 123L,
        )
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.android.settings",
            viewIdResourceName = null,
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
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/action_bar_title",
                    text = "\uD83D",
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[48,72][640,148]",
                    children = emptyList(),
                ),
                AccessibilityNodeSnapshot(
                    className = "android.widget.ScrollView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "com.android.settings:id/settings_homepage_container",
                    text = null,
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = true,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,0][1080,2337]",
                    children = emptyList(),
                ),
            ),
        )

        val screenName = ScreenNaming.deriveScreenName(
            eventClassName = "android.widget.FrameLayout",
            selectedApp = selectedApp,
            root = root,
        )

        assertEquals("Settings Homepage", screenName)
        assertEquals("captured_screen", ScreenNaming.toFileBase("\uD83D"))
    }

    @Test
    fun chooseElementLabel_uses_expected_fallback_order() {
        assertEquals(
            "Primary CTA",
            ScreenNaming.chooseElementLabel(
                text = "Primary CTA",
                contentDescription = "Description",
                viewIdResourceName = "com.example:id/cta_button",
                bounds = "[0,0][10,10]",
            )
        )
        assertEquals(
            "Description",
            ScreenNaming.chooseElementLabel(
                text = " ",
                contentDescription = "Description",
                viewIdResourceName = "com.example:id/cta_button",
                bounds = "[0,0][10,10]",
            )
        )
        assertEquals(
            "cta button",
            ScreenNaming.chooseElementLabel(
                text = null,
                contentDescription = null,
                viewIdResourceName = "com.example:id/cta_button",
                bounds = "[0,0][10,10]",
            )
        )
        assertEquals(
            "Tap target [0,0][10,10]",
            ScreenNaming.chooseElementLabel(
                text = null,
                contentDescription = null,
                viewIdResourceName = null,
                bounds = "[0,0][10,10]",
            )
        )
    }

    @Test
    fun htmlRenderer_uses_screen_name_and_exact_anchor_labels() {
        val snapshot = ScreenSnapshot(
            screenName = "Home Screen",
            packageName = "com.example.target",
            elements = listOf(
                PressableElement(
                    label = "Continue",
                    resourceId = "com.example.target:id/continue_button",
                    bounds = "[0,0][100,50]",
                    className = "android.widget.Button",
                    isListItem = false,
                )
            ),
            xmlDump = "<screen />",
        )

        val html = HtmlRenderer.render(snapshot)

        assertTrue(html.contains("<title>Home Screen</title>"))
        assertTrue(html.contains("<h1>Home Screen</h1>"))
        assertTrue(html.contains("""<a href="#" """))
        assertTrue(html.contains(">Continue</a>"))
    }

    @Test
    fun htmlRenderer_resolves_child_links_by_element_key() {
        val element = PressableElement(
            label = "Continue",
            resourceId = "com.example.target:id/continue_button",
            bounds = "[0,0][100,50]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(0),
            firstSeenStep = 1,
        )
        val snapshot = ScreenSnapshot(
            screenName = "Home Screen",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "<screen />",
        )

        val html = HtmlRenderer.render(
            snapshot = snapshot,
            resolvedChildLinks = mapOf(element.toLinkKey() to "screen_00001_details_screen.html"),
        )

        assertTrue(html.contains("""<a href="screen_00001_details_screen.html""""))
        assertTrue(html.contains(">Continue</a>"))
    }

    @Test
    fun htmlRenderer_wraps_list_items_in_unordered_list() {
        val snapshot = ScreenSnapshot(
            screenName = "RecyclerView",
            packageName = "com.android.settings",
            elements = listOf(
                PressableElement(
                    label = "Search Settings",
                    resourceId = "com.android.settings:id/search_action_bar",
                    bounds = "[42,157][1038,346]",
                    className = "android.widget.LinearLayout",
                    isListItem = false,
                ),
                PressableElement(
                    label = "Google",
                    resourceId = null,
                    bounds = "[0,388][1080,636]",
                    className = "android.widget.LinearLayout",
                    isListItem = true,
                ),
                PressableElement(
                    label = "Network & internet",
                    resourceId = null,
                    bounds = "[0,636][1080,842]",
                    className = "android.widget.LinearLayout",
                    isListItem = true,
                ),
            ),
            xmlDump = "<screen />",
        )

        val html = HtmlRenderer.render(snapshot)

        assertTrue(html.contains("""<a href="#" data-resource-id="com.android.settings:id/search_action_bar""""))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li><a href=\"#\""))
        assertTrue(html.contains(">Google</a></li>"))
        assertTrue(html.contains(">Network &amp; internet</a></li>"))
    }

    @Test
    fun collectPressableElements_uses_nested_title_text_for_clickable_container() {
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.LinearLayout",
            packageName = "com.android.settings",
            viewIdResourceName = null,
            text = null,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,388][1080,636]",
            children = listOf(
                AccessibilityNodeSnapshot(
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "android:id/title",
                    text = "Google",
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[210,430][377,501]",
                    children = emptyList(),
                ),
                AccessibilityNodeSnapshot(
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    viewIdResourceName = "android:id/summary",
                    text = "Services & preferences",
                    contentDescription = null,
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[210,501][589,552]",
                    children = emptyList(),
                ),
            ),
        )

        val elements = AccessibilityTreeSnapshotter.collectPressableElements(root)

        assertEquals(1, elements.size)
        assertEquals("Google", elements.first().label)
        assertEquals(false, elements.first().isListItem)
    }

    @Test
    fun collectPressableElements_marks_items_inside_recycler_view_as_list_items() {
        val root = AccessibilityNodeSnapshot(
            className = "androidx.recyclerview.widget.RecyclerView",
            packageName = "com.android.settings",
            viewIdResourceName = "com.android.settings:id/recycler_view",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = true,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,388][1080,2337]",
            children = listOf(
                AccessibilityNodeSnapshot(
                    className = "android.widget.LinearLayout",
                    packageName = "com.android.settings",
                    viewIdResourceName = null,
                    text = null,
                    contentDescription = null,
                    clickable = true,
                    supportsClickAction = true,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,388][1080,636]",
                    children = listOf(
                        AccessibilityNodeSnapshot(
                            className = "android.widget.TextView",
                            packageName = "com.android.settings",
                            viewIdResourceName = "android:id/title",
                            text = "Google",
                            contentDescription = null,
                            clickable = false,
                            supportsClickAction = false,
                            scrollable = false,
                            enabled = true,
                            visibleToUser = true,
                            bounds = "[210,430][377,501]",
                            children = emptyList(),
                        )
                    ),
                )
            ),
        )

        val elements = AccessibilityTreeSnapshotter.collectPressableElements(root)

        assertEquals(1, elements.size)
        assertEquals("Google", elements.first().label)
        assertEquals(true, elements.first().isListItem)
    }

    @Test
    fun xmlSerializer_escapes_content_and_preserves_hierarchy() {
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.example.target",
            viewIdResourceName = null,
            text = "Root & Screen",
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][100,100]",
            children = listOf(
                AccessibilityNodeSnapshot(
                    className = "android.widget.Button",
                    packageName = "com.example.target",
                    viewIdResourceName = "com.example.target:id/continue_button",
                    text = "Continue <Now>",
                    contentDescription = "Continue button",
                    clickable = true,
                    supportsClickAction = true,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,50][100,100]",
                    children = emptyList(),
                )
            ),
        )

        val xml = AccessibilityXmlSerializer.serialize(
            screenName = "Home Screen",
            packageName = "com.example.target",
            root = root,
        )

        assertTrue(xml.contains("""<screen name="Home Screen" package="com.example.target">"""))
        assertTrue(xml.contains("""text="Root &amp; Screen""""))
        assertTrue(xml.contains("""text="Continue &lt;Now&gt;""""))
        assertTrue(xml.contains("""resource-id="com.example.target:id/continue_button""""))
        assertTrue(xml.contains("""scrollable="false""""))
        assertTrue(xml.contains("</node>"))
    }

    @Test
    fun mergedScreenSnapshot_for_non_scrollable_screen_keeps_single_step_output() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(
                rootNode(
                    className = "android.widget.FrameLayout",
                    scrollable = false,
                    children = listOf(
                        clickableNode(
                            text = "Continue",
                            resourceId = "com.example.target:id/continue_button",
                            bounds = "[0,0][100,50]",
                            childIndexPath = listOf(0),
                        )
                    ),
                )
            ),
        )

        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(1, snapshot.stepSnapshots.size)
        assertEquals(1, snapshot.elements.size)
        assertTrue(snapshot.xmlDump.contains("""scroll-steps="1""""))
        assertTrue(snapshot.xmlDump.contains("""first-seen-step="0""""))
    }

    @Test
    fun findPrimaryScrollableNodePath_prefers_recycler_view_over_generic_layout() {
        val root = rootNode(
            children = listOf(
                node(
                    className = "android.widget.LinearLayout",
                    packageName = "com.example.target",
                    scrollable = true,
                    childIndexPath = listOf(0),
                ),
                node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    packageName = "com.example.target",
                    scrollable = true,
                    childIndexPath = listOf(1),
                ),
            ),
        )

        val path = AccessibilityTreeSnapshotter.findPrimaryScrollableNodePath(
            root = root,
            targetPackageName = "com.example.target",
        )

        assertEquals(listOf(1), path)
    }

    @Test
    fun mergedScreenSnapshot_dedupes_logical_elements_across_scroll_steps() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(
                scrollableRoot(
                    clickableNode(
                        text = "Google",
                        resourceId = "com.example.target:id/row_title",
                        bounds = "[0,100][100,150]",
                        childIndexPath = listOf(0, 0),
                    )
                ),
                scrollableRoot(
                    clickableNode(
                        text = "Google",
                        resourceId = "com.example.target:id/row_title",
                        bounds = "[0,300][100,350]",
                        childIndexPath = listOf(0, 0),
                    ),
                    clickableNode(
                        text = "Advanced",
                        resourceId = "com.example.target:id/advanced_button",
                        bounds = "[0,360][100,420]",
                        childIndexPath = listOf(0, 1),
                    ),
                ),
            ),
        )

        assertEquals(2, snapshot.elements.size)
        assertEquals("Google", snapshot.elements[0].label)
        assertEquals(0, snapshot.elements[0].firstSeenStep)
        assertEquals("Advanced", snapshot.elements[1].label)
        assertEquals(1, snapshot.elements[1].firstSeenStep)
    }

    @Test
    fun syntheticXml_includes_merged_elements_and_step_hierarchy() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(
                scrollableRoot(
                    clickableNode(
                        text = "Continue <Now>",
                        resourceId = "com.example.target:id/continue_button",
                        bounds = "[0,0][100,50]",
                        childIndexPath = listOf(0, 0),
                    )
                ),
                scrollableRoot(
                    clickableNode(
                        text = "Advanced",
                        resourceId = "com.example.target:id/advanced_button",
                        bounds = "[0,50][100,100]",
                        childIndexPath = listOf(0, 0),
                    )
                ),
            ),
        )

        val xml = snapshot.xmlDump

        assertTrue(xml.contains("""scroll-steps="2""""))
        assertTrue(xml.contains("<merged-elements>"))
        assertTrue(xml.contains("""label="Continue &lt;Now&gt;""""))
        assertTrue(xml.contains("""first-seen-step="1""""))
        assertTrue(xml.contains("<scroll-steps>"))
        assertTrue(xml.contains("""<step index="1" new-elements="1">"""))
        assertTrue(xml.contains("""resource-id="com.example.target:id/advanced_button""""))
    }

    @Test
    fun mergedScreenSnapshot_uses_actual_root_package_for_cross_package_child_screen() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.google.android.gms.SomeChildActivity",
            stepRoots = listOf(
                rootNode(
                    className = "android.widget.FrameLayout",
                    children = listOf(
                        node(
                            className = "android.widget.Button",
                            packageName = "com.google.android.gms",
                            viewIdResourceName = "com.google.android.gms:id/action_button",
                            text = "Continue",
                            clickable = true,
                            supportsClickAction = true,
                            childIndexPath = listOf(0),
                        )
                    ),
                ).copy(packageName = "com.google.android.gms")
            ),
        )

        assertEquals("com.google.android.gms", snapshot.packageName)
        assertTrue(snapshot.xmlDump.contains("""package="com.google.android.gms""""))
    }

    @Test
    fun scrollScanCoordinator_stops_when_scroll_action_fails() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 8)
        var captureCalls = 0

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(
                clickableNode(
                    text = "Continue",
                    resourceId = "com.example.target:id/continue_button",
                    bounds = "[0,0][100,50]",
                    childIndexPath = listOf(0, 0),
                )
            ),
            tryScrollForward = { false },
            tryScrollBackward = { false },
            captureCurrentRoot = {
                captureCalls += 1
                null
            },
        )

        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(1, captureCalls)
    }

    @Test
    fun scrollScanCoordinator_stops_when_downward_viewport_repeats() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 8)
        var scrollAttempts = 0
        val staleRoot = scrollableRoot(
            clickableNode(
                text = "Continue",
                resourceId = "com.example.target:id/continue_button",
                bounds = "[0,200][100,250]",
                childIndexPath = listOf(0, 0),
            )
        )

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(
                clickableNode(
                    text = "Continue",
                    resourceId = "com.example.target:id/continue_button",
                    bounds = "[0,0][100,50]",
                    childIndexPath = listOf(0, 0),
                )
            ),
            tryScrollForward = {
                scrollAttempts += 1
                true
            },
            tryScrollBackward = { false },
            captureCurrentRoot = { staleRoot },
        )

        assertEquals(2, snapshot.scrollStepCount)
        assertEquals(2, scrollAttempts)
    }

    @Test
    fun scrollScanCoordinator_respects_max_scroll_cap() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 1)
        var scrollAttempts = 0

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(
                clickableNode(
                    text = "Continue",
                    resourceId = "com.example.target:id/continue_button",
                    bounds = "[0,0][100,50]",
                    childIndexPath = listOf(0, 0),
                )
            ),
            tryScrollForward = {
                scrollAttempts += 1
                true
            },
            tryScrollBackward = { false },
            captureCurrentRoot = {
                scrollableRoot(
                    clickableNode(
                        text = "Advanced",
                        resourceId = "com.example.target:id/advanced_button",
                        bounds = "[0,200][100,250]",
                        childIndexPath = listOf(0, 0),
                    )
                )
            },
        )

        assertEquals(2, snapshot.scrollStepCount)
        assertEquals(1, scrollAttempts)
        assertEquals(2, snapshot.elements.size)
    }

    @Test
    fun scrollScanCoordinator_continues_downward_while_viewport_changes_even_without_new_elements() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 8)
        var forwardAttempts = 0
        val viewportA = scrollableRoot(
            clickableNode(
                text = "Repeated Row",
                resourceId = "com.example.target:id/repeated_row",
                bounds = "[0,0][100,50]",
                childIndexPath = listOf(0, 0),
            )
        )
        val viewportB = scrollableRoot(
            clickableNode(
                text = "Repeated Row",
                resourceId = "com.example.target:id/repeated_row",
                bounds = "[0,200][100,250]",
                childIndexPath = listOf(0, 0),
            )
        )
        val viewportC = scrollableRoot(
            clickableNode(
                text = "Repeated Row",
                resourceId = "com.example.target:id/repeated_row",
                bounds = "[0,400][100,450]",
                childIndexPath = listOf(0, 0),
            )
        )
        val captureSequence = mutableListOf<AccessibilityNodeSnapshot?>(
            null,
            viewportB,
            viewportC,
            viewportC,
        )

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = viewportA,
            tryScrollForward = {
                forwardAttempts += 1
                forwardAttempts <= 3
            },
            tryScrollBackward = { false },
            captureCurrentRoot = {
                if (captureSequence.isEmpty()) {
                    null
                } else {
                    captureSequence.removeAt(0)
                }
            },
        )

        assertEquals(3, forwardAttempts)
        assertEquals(3, snapshot.scrollStepCount)
        assertEquals(1, snapshot.elements.size)
    }

    @Test
    fun scrollScanCoordinator_prefers_settled_initial_viewport_when_it_has_more_elements() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 0)

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(),
            tryScrollForward = { false },
            tryScrollBackward = { false },
            captureCurrentRoot = {
                scrollableRoot(
                    clickableNode(
                        text = "Search",
                        resourceId = "com.example.target:id/search",
                        bounds = "[0,0][100,50]",
                        childIndexPath = listOf(0, 0),
                    ),
                    clickableNode(
                        text = "Network",
                        resourceId = "com.example.target:id/network",
                        bounds = "[0,60][100,110]",
                        childIndexPath = listOf(0, 1),
                    ),
                )
            },
        )

        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(2, snapshot.elements.size)
        assertFalse(snapshot.elements.isEmpty())
        assertEquals("Search", snapshot.elements.first().label)
    }

    @Test
    fun scrollScanCoordinator_rewinds_to_top_before_collecting_step_zero() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 0)
        var backwardAttempts = 0

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(
                clickableNode(
                    text = "Bottom Action",
                    resourceId = "com.example.target:id/bottom_action",
                    bounds = "[0,400][100,450]",
                    childIndexPath = listOf(0, 0),
                )
            ),
            tryScrollForward = { false },
            tryScrollBackward = {
                backwardAttempts += 1
                backwardAttempts == 1
            },
            captureCurrentRoot = {
                if (backwardAttempts == 1) {
                    scrollableRoot(
                        clickableNode(
                            text = "Top Search",
                            resourceId = "com.example.target:id/top_search",
                            bounds = "[0,0][100,50]",
                            childIndexPath = listOf(0, 0),
                        ),
                        clickableNode(
                            text = "Account",
                            resourceId = "com.example.target:id/account",
                            bounds = "[0,60][100,110]",
                            childIndexPath = listOf(0, 1),
                        ),
                    )
                } else {
                    null
                }
            },
        )

        assertEquals(2, backwardAttempts)
        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(listOf("Top Search", "Account"), snapshot.elements.map { it.label })
        assertFalse(snapshot.elements.any { it.label == "Bottom Action" })
    }

    @Test
    fun scrollScanCoordinator_keeps_initial_view_when_already_at_top() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 0)
        var backwardAttempts = 0

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = scrollableRoot(
                clickableNode(
                    text = "Top Search",
                    resourceId = "com.example.target:id/top_search",
                    bounds = "[0,0][100,50]",
                    childIndexPath = listOf(0, 0),
                )
            ),
            tryScrollForward = { false },
            tryScrollBackward = {
                backwardAttempts += 1
                false
            },
            captureCurrentRoot = { null },
        )

        assertEquals(1, backwardAttempts)
        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(listOf("Top Search"), snapshot.elements.map { it.label })
    }

    @Test
    fun scrollScanCoordinator_stops_rewind_when_backward_scroll_does_not_change_viewport() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxAdditionalScrolls = 0)
        var backwardAttempts = 0
        val topRoot = scrollableRoot(
            clickableNode(
                text = "Top Search",
                resourceId = "com.example.target:id/top_search",
                bounds = "[0,0][100,50]",
                childIndexPath = listOf(0, 0),
            )
        )

        val snapshot = coordinator.scan(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            initialRoot = topRoot,
            tryScrollForward = { false },
            tryScrollBackward = {
                backwardAttempts += 1
                true
            },
            captureCurrentRoot = { topRoot },
        )

        assertEquals(1, backwardAttempts)
        assertEquals(1, snapshot.scrollStepCount)
        assertEquals(listOf("Top Search"), snapshot.elements.map { it.label })
    }

    @Test
    fun entryScreenBackAffordanceDetector_detects_toolbar_back_button() {
        val root = toolbarBackRoot(
            clickableNode(
                text = "Detail Action",
                resourceId = "com.example.target:id/detail_action",
                bounds = "[0,240][100,290]",
                childIndexPath = listOf(1, 0),
            ),
        )

        assertTrue(EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(root))
    }

    @Test
    fun entryScreenBackAffordanceDetector_ignores_body_back_button() {
        val root = rootNode(
            children = listOf(
                node(
                    className = "android.widget.LinearLayout",
                    childIndexPath = listOf(0),
                    children = listOf(
                        node(
                            className = "android.widget.Button",
                            text = "Back",
                            clickable = true,
                            supportsClickAction = true,
                            bounds = "[120,900][420,1020]",
                            childIndexPath = listOf(0, 0),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(root))
    }

    @Test
    fun rewindToEntryScreen_keeps_backing_while_back_affordance_is_visible() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxBackToEntryAttempts = 4)
        var backAttempts = 0
        val detailRoot = toolbarBackRoot(
            clickableNode(
                text = "Detail Action",
                resourceId = "com.example.target:id/detail_action",
                bounds = "[0,240][100,290]",
                childIndexPath = listOf(1, 0),
            )
        )
        val middleRoot = toolbarBackRoot(
            clickableNode(
                text = "Middle Action",
                resourceId = "com.example.target:id/middle_action",
                bounds = "[0,240][100,290]",
                childIndexPath = listOf(1, 0),
            )
        )
        val homeRoot = scrollableRoot(
            clickableNode(
                text = "Home Search",
                resourceId = "com.example.target:id/home_search",
                bounds = "[0,0][100,50]",
                childIndexPath = listOf(0, 0),
            )
        )
        val captures = mutableListOf<AccessibilityNodeSnapshot?>(
            middleRoot,
            middleRoot,
            homeRoot,
            homeRoot,
        )

        val result = coordinator.rewindToEntryScreen(
            initialRoot = detailRoot,
            targetPackageName = "com.example.target",
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { captures.removeAt(0) },
        )

        assertEquals(2, backAttempts)
        assertEquals(EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY, result.outcome)
        assertTrue(result.verifiedForReplay)
        assertEquals(listOf("Home Search"), AccessibilityTreeSnapshotter.collectPressableElements(result.root).map { it.label })
    }

    @Test
    fun rewindToEntryScreen_keeps_current_root_when_no_back_affordance_is_visible() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxBackToEntryAttempts = 4)
        var backAttempts = 0
        val homeRoot = scrollableRoot(
            clickableNode(
                text = "Home Search",
                resourceId = "com.example.target:id/home_search",
                bounds = "[0,0][100,50]",
                childIndexPath = listOf(0, 0),
            )
        )

        val result = coordinator.rewindToEntryScreen(
            initialRoot = homeRoot,
            targetPackageName = "com.example.target",
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { null },
        )

        assertEquals(0, backAttempts)
        assertEquals(EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY, result.outcome)
        assertTrue(result.verifiedForReplay)
        assertEquals(listOf("Home Search"), AccessibilityTreeSnapshotter.collectPressableElements(result.root).map { it.label })
    }

    @Test
    fun rewindToEntryScreen_reports_failure_when_back_affordance_never_disappears() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxBackToEntryAttempts = 2)
        var backAttempts = 0
        val detailRoot = toolbarBackRoot(
            clickableNode(
                text = "Detail Action",
                resourceId = "com.example.target:id/detail_action",
                bounds = "[0,240][100,290]",
                childIndexPath = listOf(1, 0),
            )
        )
        val captures = mutableListOf<AccessibilityNodeSnapshot?>(
            detailRoot,
            detailRoot,
            detailRoot,
            detailRoot,
        )

        val result = coordinator.rewindToEntryScreen(
            initialRoot = detailRoot,
            targetPackageName = "com.example.target",
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { captures.removeAt(0) },
        )

        assertEquals(2, backAttempts)
        assertEquals(EntryScreenResetOutcome.MAX_ATTEMPTS_REACHED, result.outcome)
        assertFalse(result.verifiedForReplay)
        assertEquals(listOf("Navigate up", "Detail Action"), AccessibilityTreeSnapshotter.collectPressableElements(result.root).map { it.label })
    }

    @Test
    fun rewindToEntryScreen_reports_failure_when_backing_leaves_target_app() = runBlocking {
        val coordinator = ScrollScanCoordinator(maxBackToEntryAttempts = 4)
        var backAttempts = 0
        val detailRoot = toolbarBackRoot(
            clickableNode(
                text = "Detail Action",
                resourceId = "com.example.target:id/detail_action",
                bounds = "[0,240][100,290]",
                childIndexPath = listOf(1, 0),
            )
        )
        val launcherRoot = node(
            className = "android.widget.FrameLayout",
            packageName = "com.android.launcher",
            bounds = "[0,0][1080,2400]",
        )

        val result = coordinator.rewindToEntryScreen(
            initialRoot = detailRoot,
            targetPackageName = "com.example.target",
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { launcherRoot },
        )

        assertEquals(1, backAttempts)
        assertEquals(EntryScreenResetOutcome.LEFT_TARGET_APP, result.outcome)
        assertFalse(result.verifiedForReplay)
    }

    @Test
    fun crawlerUiState_transitions_to_launching_captured_and_failed() {
        val selectedApp = SelectedAppRef(
            packageName = "com.example.target",
            appName = "Target",
            launcherActivity = "com.example.target.MainActivity",
            selectedAt = 123L,
        )
        val files = CapturedScreenFiles(
            htmlFile = File("build/test-home.html"),
            xmlFile = File("build/test-home.xml"),
        )

        val launching = CrawlerUiState.idle().withLaunching(
            requestId = 42L,
            selectedApp = selectedApp,
            alreadyRunning = true,
        )
        val waiting = launching.withWaiting("Waiting")
        val scanning = waiting.withScanning("Scanning")
        val traversing = scanning.withTraversingChildren("Traversing")
        val captured = traversing.withCaptured(
            CrawlRunSummary(
                rootScreenName = "Home Screen",
                rootFiles = files,
                manifestFile = File("build/crawl-index.json"),
                graphJsonPath = File("build/crawl-graph.json"),
                graphHtmlPath = File("build/crawl-graph.html"),
                rootScrollStepCount = 3,
                capturedScreenCount = 2,
                capturedChildScreenCount = 1,
                skippedElementCount = 4,
                maxDepthReached = 1,
            )
        )
        val aborted = traversing.withAborted(
            summary = CrawlRunSummary(
                rootScreenName = "Home Screen",
                rootFiles = files,
                manifestFile = File("build/crawl-index.json"),
                graphJsonPath = File("build/crawl-graph.json"),
                graphHtmlPath = File("build/crawl-graph.html"),
                rootScrollStepCount = 3,
                capturedScreenCount = 1,
                capturedChildScreenCount = 0,
                skippedElementCount = 2,
                maxDepthReached = 0,
            ),
            message = "Partial save",
        )
        val failed = waiting.withFailure("Boom")

        assertEquals(CrawlerPhase.LAUNCHING, launching.phase)
        assertEquals(CrawlerPhase.WAITING_FOR_TARGET_SCREEN, waiting.phase)
        assertEquals(CrawlerPhase.SCANNING_TARGET_SCREEN, scanning.phase)
        assertEquals(CrawlerPhase.TRAVERSING_CHILD_SCREENS, traversing.phase)
        assertEquals(CrawlerPhase.CAPTURED, captured.phase)
        assertEquals("Home Screen", captured.screenName)
        assertEquals(3, captured.scrollStepCount)
        assertEquals(2, captured.capturedScreenCount)
        assertEquals(1, captured.maxDepthReached)
        assertEquals(File("build/crawl-graph.json").absolutePath, captured.graphJsonPath)
        assertEquals(File("build/crawl-graph.html").absolutePath, captured.graphHtmlPath)
        assertEquals(CrawlerPhase.ABORTED, aborted.phase)
        assertTrue(aborted.partialResult)
        assertEquals(0, aborted.maxDepthReached)
        assertEquals(File("build/crawl-graph.json").absolutePath, aborted.graphJsonPath)
        assertEquals(File("build/crawl-graph.html").absolutePath, aborted.graphHtmlPath)
        assertEquals(CrawlerPhase.FAILED, failed.phase)
        assertEquals("Boom", failed.failureMessage)
    }

    @Test
    fun preparePackageDirectory_deletes_previous_capture_files() {
        val baseDir = Files.createTempDirectory("capture-store-test").toFile()
        try {
            File(baseDir, "old_screen.html").writeText("old html")
            File(baseDir, "old_screen.xml").writeText("old xml")

            CaptureFileStore.preparePackageDirectory(baseDir)

            assertEquals(0, baseDir.listFiles().orEmpty().size)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private fun selectedApp(): SelectedAppRef {
        return SelectedAppRef(
            packageName = "com.example.target",
            appName = "Target",
            launcherActivity = "com.example.target.HomeActivity",
            selectedAt = 123L,
        )
    }

    private fun rootNode(
        className: String = "android.widget.FrameLayout",
        scrollable: Boolean = false,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ): AccessibilityNodeSnapshot {
        return node(
            className = className,
            scrollable = scrollable,
            bounds = "[0,0][1080,2400]",
            children = children,
        )
    }

    private fun scrollableRoot(vararg children: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot {
        return rootNode(
            children = listOf(
                node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true,
                    bounds = "[0,0][1080,2200]",
                    childIndexPath = listOf(0),
                    children = children.toList(),
                )
            ),
        )
    }

    private fun toolbarBackRoot(vararg bodyChildren: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot {
        return rootNode(
            children = listOf(
                node(
                    className = "androidx.appcompat.widget.Toolbar",
                    viewIdResourceName = "com.example.target:id/toolbar",
                    bounds = "[0,0][1080,180]",
                    childIndexPath = listOf(0),
                    children = listOf(
                        node(
                            className = "android.widget.ImageButton",
                            viewIdResourceName = "com.example.target:id/back_button",
                            contentDescription = "Navigate up",
                            clickable = true,
                            supportsClickAction = true,
                            bounds = "[0,0][120,120]",
                            childIndexPath = listOf(0, 0),
                        ),
                    ),
                ),
                node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true,
                    bounds = "[0,180][1080,2200]",
                    childIndexPath = listOf(1),
                    children = bodyChildren.toList(),
                ),
            ),
        )
    }

    private fun clickableNode(
        text: String,
        resourceId: String,
        bounds: String,
        childIndexPath: List<Int>,
    ): AccessibilityNodeSnapshot {
        return node(
            className = "android.widget.Button",
            viewIdResourceName = resourceId,
            text = text,
            clickable = true,
            supportsClickAction = true,
            bounds = bounds,
            childIndexPath = childIndexPath,
        )
    }

    private fun node(
        className: String,
        packageName: String = "com.example.target",
        viewIdResourceName: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        clickable: Boolean = false,
        supportsClickAction: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        visibleToUser: Boolean = true,
        bounds: String = "[0,0][100,100]",
        children: List<AccessibilityNodeSnapshot> = emptyList(),
        childIndexPath: List<Int> = emptyList(),
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            text = text,
            contentDescription = contentDescription,
            clickable = clickable,
            supportsClickAction = supportsClickAction,
            scrollable = scrollable,
            enabled = enabled,
            visibleToUser = visibleToUser,
            bounds = bounds,
            children = children,
            childIndexPath = childIndexPath,
        )
    }
}
