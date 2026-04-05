package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticAccessibilityTreeBuilderTest {
    @Test
    fun build_preserves_single_step_non_scroll_screen_shape() {
        val root = rootNode(
            children = listOf(
                node(
                    className = "android.widget.Button",
                    viewIdResourceName = "com.example.target:id/continue_button",
                    text = "Continue",
                    clickable = true,
                    supportsClickAction = true,
                    bounds = "[24,120][320,220]",
                    childIndexPath = listOf(0),
                ),
            ),
        )

        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(root),
        )

        val mergedRoot = snapshot.mergedRoot

        assertNotNull(mergedRoot)
        assertEquals(root.className, mergedRoot?.className)
        assertEquals(1, mergedRoot?.children?.size)
        assertEquals("Continue", mergedRoot?.children?.firstOrNull()?.text)
        assertTrue(snapshot.mergedXmlDump.orEmpty().contains("""synthetic="true""""))
    }

    @Test
    fun build_merges_overlapping_scroll_steps_into_one_scroll_container() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(
                scrollableRoot(
                    clickableNode("Alpha", "com.example.target:id/alpha", "[0,0][1080,120]", listOf(0, 0)),
                    clickableNode("Beta", "com.example.target:id/beta", "[0,120][1080,240]", listOf(0, 1)),
                ),
                scrollableRoot(
                    clickableNode("Beta", "com.example.target:id/beta", "[0,0][1080,120]", listOf(0, 0)),
                    clickableNode("Gamma", "com.example.target:id/gamma", "[0,120][1080,240]", listOf(0, 1)),
                ),
            ),
        )

        val mergedScrollContainer = snapshot.mergedRoot?.children?.firstOrNull()

        assertNotNull(mergedScrollContainer)
        assertTrue(mergedScrollContainer?.syntheticScrollContainer == true)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), mergedScrollContainer?.children?.map { it.text })
        assertTrue(snapshot.mergedXmlDump.orEmpty().contains("""synthetic-scroll-container="true""""))
        assertTrue(snapshot.mergedXmlDump.orEmpty().contains("""source-step-indices="0,1""""))
    }

    @Test
    fun build_keeps_same_label_nodes_separate_when_merge_signals_differ() {
        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = "com.example.target.HomeActivity",
            stepRoots = listOf(
                scrollableRoot(
                    clickableNode("More", "com.example.target:id/more_primary", "[0,0][180,100]", listOf(0, 0)),
                    clickableNode("Continue", "com.example.target:id/continue", "[0,100][180,200]", listOf(0, 1)),
                ),
                scrollableRoot(
                    clickableNode("Continue", "com.example.target:id/continue", "[0,0][180,100]", listOf(0, 0)),
                    clickableNode("More", "com.example.target:id/more_secondary", "[0,100][360,220]", listOf(0, 1)),
                ),
            ),
        )

        val mergedChildren = snapshot.mergedRoot?.children?.firstOrNull()?.children.orEmpty()

        assertEquals(3, mergedChildren.size)
        assertEquals(
            listOf(
                "com.example.target:id/more_primary",
                "com.example.target:id/continue",
                "com.example.target:id/more_secondary",
            ),
            mergedChildren.map { it.viewIdResourceName }
        )
    }

    @Test
    fun build_emits_valid_xml_for_sparse_accessibility_tree() {
        val root = rootNode(
            children = listOf(
                node(
                    className = "android.view.View",
                    bounds = "[0,0][1080,400]",
                    childIndexPath = listOf(0),
                ),
            ),
        )

        val snapshot = AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = selectedApp(),
            eventClassName = null,
            stepRoots = listOf(root),
        )

        assertTrue(snapshot.mergedXmlDump.orEmpty().startsWith("""<?xml version="1.0" encoding="utf-8"?>"""))
        assertTrue(snapshot.mergedXmlDump.orEmpty().contains("<screen"))
        assertTrue(snapshot.mergedXmlDump.orEmpty().contains("""class="android.view.View""""))
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
        children: List<AccessibilityNodeSnapshot>,
    ): AccessibilityNodeSnapshot {
        return node(
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2400]",
            children = children,
        )
    }

    private fun scrollableRoot(
        vararg children: AccessibilityNodeSnapshot,
    ): AccessibilityNodeSnapshot {
        return rootNode(
            children = listOf(
                node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true,
                    bounds = "[0,0][1080,2200]",
                    childIndexPath = listOf(0),
                    children = children.toList(),
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
        clickable: Boolean = false,
        supportsClickAction: Boolean = false,
        scrollable: Boolean = false,
        bounds: String = "[0,0][100,100]",
        children: List<AccessibilityNodeSnapshot> = emptyList(),
        childIndexPath: List<Int> = emptyList(),
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            text = text,
            contentDescription = null,
            clickable = clickable,
            supportsClickAction = supportsClickAction,
            scrollable = scrollable,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = children,
            childIndexPath = childIndexPath,
        )
    }
}
