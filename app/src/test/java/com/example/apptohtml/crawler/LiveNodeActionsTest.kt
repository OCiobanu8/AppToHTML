package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the live-tree action layer extracted from
 * `AppToHtmlAccessibilityService`.
 *
 * These were written against the *pre-extraction* service source, because the moved code had no
 * unit coverage at all — so "the existing suite is still green" could not have detected a
 * regression in it. If one of these disagrees with the implementation, the implementation is wrong.
 *
 * Synthetic nodes only; no Android framework types and no mocking, per `factory/FUNCTION.md`.
 */
class LiveNodeActionsTest {

    // Deliberately arbitrary distinct ints: the production ids are injected, so nothing here
    // depends on the framework's actual constant values.
    private val ids = LiveActionIds(
        scrollForward = 4096,
        scrollBackward = 8192,
        click = 16,
        scrollDown = 1001,
        scrollUp = 1002,
        pageDown = 1003,
        pageUp = 1004,
    )

    private class FakeNode(
        val attrs: LiveNodeAttributes = LiveNodeAttributes(),
        val advertisedActions: Set<Int> = emptySet(),
        val acceptedActions: Set<Int> = emptySet(),
        val children: List<FakeNode> = emptyList(),
        val name: String = "",
    )

    private class Harness(private val ids: LiveActionIds) {
        val performedOrder = mutableListOf<Pair<String, Int>>()
        val diagnostics = mutableListOf<String>()

        fun actions(): LiveNodeActions<FakeNode> = LiveNodeActions(
            childCount = { node -> node.children.size },
            childAt = { node, index -> node.children.getOrNull(index) },
            attributes = { node -> node.attrs },
            supportedActionIds = { node -> node.advertisedActions },
            performAction = { node, actionId ->
                performedOrder += node.name to actionId
                actionId in node.acceptedActions
            },
            actionIds = ids,
            logger = { null },
            diagnostics = { message -> diagnostics += message },
        )
    }

    private fun scrollable(
        name: String,
        className: String,
        children: List<FakeNode> = emptyList(),
        visible: Boolean = true,
        advertised: Set<Int> = emptySet(),
        accepted: Set<Int> = emptySet(),
    ) = FakeNode(
        attrs = LiveNodeAttributes(
            className = className,
            visibleToUser = visible,
            enabled = true,
            scrollable = true,
        ),
        advertisedActions = advertised,
        acceptedActions = accepted,
        children = children,
        name = name,
    )

    // ---------------------------------------------------------------- preferredActionIds

    @Test
    fun `preferredActionIds appends preferred ids the node never advertised`() {
        val harness = Harness(ids)
        val node = FakeNode(advertisedActions = setOf(ids.pageDown), name = "n")

        val result = harness.actions().preferredActionIds(node, ids.scrollForward)

        // The trailing append is load-bearing: some views honor a scroll action they do not list.
        // Filtering unsupported ids out here would silently drop working fallbacks.
        assertEquals(listOf(ids.pageDown, ids.scrollForward, ids.scrollDown), result)
    }

    @Test
    fun `preferredActionIds keeps preferred order when everything is advertised`() {
        val harness = Harness(ids)
        val node = FakeNode(
            advertisedActions = setOf(ids.scrollForward, ids.scrollDown, ids.pageDown),
            name = "n",
        )

        assertEquals(
            listOf(ids.scrollForward, ids.scrollDown, ids.pageDown),
            harness.actions().preferredActionIds(node, ids.scrollForward),
        )
    }

    @Test
    fun `preferredActionIds maps scroll backward to its own family`() {
        val harness = Harness(ids)
        val node = FakeNode(advertisedActions = setOf(ids.pageUp), name = "n")

        assertEquals(
            listOf(ids.pageUp, ids.scrollBackward, ids.scrollUp),
            harness.actions().preferredActionIds(node, ids.scrollBackward),
        )
    }

    @Test
    fun `preferredActionIds falls through to the requested action for an unknown id`() {
        val harness = Harness(ids)
        val node = FakeNode(name = "n")

        assertEquals(listOf(99), harness.actions().preferredActionIds(node, 99))
    }

    @Test
    fun `preferredActionIds returns a single click id`() {
        val harness = Harness(ids)
        val node = FakeNode(advertisedActions = setOf(ids.click), name = "n")

        assertEquals(listOf(ids.click), harness.actions().preferredActionIds(node, ids.click))
    }

    // ---------------------------------------------------------- scrollable candidate ordering

    @Test
    fun `scrollable candidates are ranked by class then depth`() {
        val harness = Harness(ids)
        val deepList = scrollable("recycler", "androidx.recyclerview.widget.RecyclerView")
        val scrollView = scrollable("scrollView", "android.widget.ScrollView", listOf(deepList))
        val root = scrollable("root", "android.widget.FrameLayout", listOf(scrollView))

        val ordered = harness.actions().collectScrollableCandidates(root).map { it.name }

        // recycler = 600 + depth 2 = 602; scrollView = 500 + 1 = 501; root = 250 + 0 = 250.
        assertEquals(listOf("recycler", "scrollView", "root"), ordered)
    }

    @Test
    fun `deeper node wins when two candidates share a class score`() {
        val harness = Harness(ids)
        val inner = scrollable("inner", "android.widget.ScrollView")
        val outer = scrollable("outer", "android.widget.ScrollView", listOf(inner))

        assertEquals(
            listOf("inner", "outer"),
            harness.actions().collectScrollableCandidates(outer).map { it.name },
        )
    }

    @Test
    fun `invisible and non-scrollable nodes are not scroll candidates`() {
        val harness = Harness(ids)
        val hidden = scrollable("hidden", "android.widget.ScrollView", visible = false)
        val plain = FakeNode(
            attrs = LiveNodeAttributes(className = "android.widget.TextView", visibleToUser = true),
            name = "plain",
        )
        val root = scrollable("root", "android.widget.ScrollView", listOf(hidden, plain))

        assertEquals(
            listOf("root"),
            harness.actions().collectScrollableCandidates(root).map { it.name },
        )
    }

    @Test
    fun `LinearLayout only scores above the default when it ends the class name`() {
        val harness = Harness(ids)
        val actions = harness.actions()

        assertEquals(
            350,
            actions.scrollableCandidateScore(
                LiveNodeAttributes(className = "android.widget.LinearLayout"),
                depth = 0,
            ),
        )
        assertEquals(
            250,
            actions.scrollableCandidateScore(
                LiveNodeAttributes(className = "android.widget.LinearLayoutCompat"),
                depth = 0,
            ),
        )
    }

    // ------------------------------------------------------------------- attempt ordering

    @Test
    fun `path candidates are attempted deepest-first`() {
        val harness = Harness(ids)
        val child = scrollable("child", "android.widget.ScrollView")
        val root = scrollable("root", "android.widget.FrameLayout", listOf(child))

        val accepted = harness.actions().performScroll(root, listOf(0), ids.scrollForward)

        assertFalse(accepted)
        // Path resolves to [root, child] and is reversed before attempting: the deepest node on the
        // recorded path is the one the crawl actually meant to scroll.
        assertEquals(listOf("child", "root"), harness.performedOrder.map { it.first }.distinct())
    }

    @Test
    fun `fallback candidates are attempted in score order, not reversed`() {
        val harness = Harness(ids)
        val recycler = scrollable("recycler", "androidx.recyclerview.widget.RecyclerView")
        val plainScroll = scrollable("scrollView", "android.widget.ScrollView")
        val root = FakeNode(
            attrs = LiveNodeAttributes(className = "android.widget.FrameLayout", visibleToUser = true, enabled = true),
            children = listOf(plainScroll, recycler),
            name = "root",
        )

        // Empty path -> path candidates are just [root], which is not scrollable and accepts
        // nothing, so every remaining attempt comes from the fallback sweep.
        harness.actions().performScroll(root, emptyList(), ids.scrollForward)

        val fallbackOrder = harness.performedOrder.map { it.first }.distinct().filter { it != "root" }
        // Score order (recycler 601 > scrollView 500+1=501), applied forwards.
        assertEquals(listOf("recycler", "scrollView"), fallbackOrder)
    }

    @Test
    fun `a successful action stops further attempts`() {
        val harness = Harness(ids)
        val child = scrollable(
            "child",
            "android.widget.ScrollView",
            advertised = setOf(ids.scrollForward),
            accepted = setOf(ids.scrollForward),
        )
        val root = scrollable("root", "android.widget.FrameLayout", listOf(child))

        assertTrue(harness.actions().performScroll(root, listOf(0), ids.scrollForward))
        assertEquals(listOf("child" to ids.scrollForward), harness.performedOrder)
    }

    @Test
    fun `every preferred action id is tried before moving to the next candidate`() {
        val harness = Harness(ids)
        val node = scrollable("only", "android.widget.ScrollView", advertised = setOf(ids.scrollDown))

        harness.actions().performScroll(node, emptyList(), ids.scrollForward)

        assertEquals(
            listOf(ids.scrollDown, ids.scrollForward, ids.pageDown),
            harness.performedOrder.filter { it.first == "only" }.map { it.second }.distinct(),
        )
    }

    // -------------------------------------------------------------------- label resolution

    @Test
    fun `label resolution prefers direct text`() {
        val harness = Harness(ids)
        val node = FakeNode(
            attrs = LiveNodeAttributes(text = "  Save  ", contentDescription = "ignored"),
            name = "n",
        )

        assertEquals("Save", harness.actions().resolveLiveElementLabel(node))
    }

    @Test
    fun `label resolution falls back to content description`() {
        val harness = Harness(ids)
        val node = FakeNode(attrs = LiveNodeAttributes(text = "   ", contentDescription = "Close"), name = "n")

        assertEquals("Close", harness.actions().resolveLiveElementLabel(node))
    }

    @Test
    fun `label resolution prefers a nested title over an earlier nested text`() {
        val harness = Harness(ids)
        val summary = FakeNode(
            attrs = LiveNodeAttributes(viewIdResourceName = "android:id/summary", text = "Summary text"),
            name = "summary",
        )
        val title = FakeNode(
            attrs = LiveNodeAttributes(viewIdResourceName = "android:id/title", text = "Wi-Fi"),
            name = "title",
        )
        val node = FakeNode(attrs = LiveNodeAttributes(), children = listOf(summary, title), name = "n")

        // The nested-title sweep runs to completion before the generic nested-text sweep starts,
        // so a title later in the child order still wins over an earlier plain text node.
        assertEquals("Wi-Fi", harness.actions().resolveLiveElementLabel(node))
    }

    @Test
    fun `label resolution falls back to nested text when no title exists`() {
        val harness = Harness(ids)
        val child = FakeNode(attrs = LiveNodeAttributes(text = "Only text"), name = "c")
        val node = FakeNode(attrs = LiveNodeAttributes(), children = listOf(child), name = "n")

        assertEquals("Only text", harness.actions().resolveLiveElementLabel(node))
    }

    @Test
    fun `label resolution falls back to the resource id segment`() {
        val harness = Harness(ids)
        val node = FakeNode(
            attrs = LiveNodeAttributes(viewIdResourceName = "com.app:id/submit_button", boundsShortString = "[0,0][1,1]"),
            name = "n",
        )

        assertEquals("submit button", harness.actions().resolveLiveElementLabel(node))
    }

    @Test
    fun `label resolution falls back to a bounds placeholder`() {
        val harness = Harness(ids)
        val node = FakeNode(attrs = LiveNodeAttributes(boundsShortString = "[0,0][100,50]"), name = "n")

        assertEquals("Tap target [0,0][100,50]", harness.actions().resolveLiveElementLabel(node))
    }

    // ----------------------------------------------------------------------- click candidates

    @Test
    fun `click candidates include nodes that only advertise the click action`() {
        val harness = Harness(ids)
        val node = FakeNode(
            attrs = LiveNodeAttributes(text = "Tap", visibleToUser = true, enabled = true, clickable = false),
            advertisedActions = setOf(ids.click),
            name = "advertised",
        )
        val root = FakeNode(
            attrs = LiveNodeAttributes(visibleToUser = true, enabled = true),
            children = listOf(node),
            name = "root",
        )

        val names = harness.actions().collectClickFallbackCandidates(root).map { it.node.name }

        assertEquals(listOf("advertised"), names)
    }

    @Test
    fun `click candidates are flagged as list items from a list-like ancestor`() {
        val harness = Harness(ids)
        val item = FakeNode(
            attrs = LiveNodeAttributes(text = "Row", visibleToUser = true, enabled = true, clickable = true),
            name = "item",
        )
        val list = FakeNode(
            attrs = LiveNodeAttributes(
                className = "androidx.recyclerview.widget.RecyclerView",
                visibleToUser = true,
                enabled = true,
                scrollable = true,
            ),
            children = listOf(item),
            name = "list",
        )

        val candidate = harness.actions().collectClickFallbackCandidates(list)
            .single { it.node.name == "item" }

        assertTrue(candidate.candidate.fingerprint.isListItem)
    }

    @Test
    fun `disabled click candidates are excluded`() {
        val harness = Harness(ids)
        val disabled = FakeNode(
            attrs = LiveNodeAttributes(text = "Nope", visibleToUser = true, enabled = false, clickable = true),
            name = "disabled",
        )
        val root = FakeNode(
            attrs = LiveNodeAttributes(visibleToUser = true, enabled = true),
            children = listOf(disabled),
            name = "root",
        )

        assertTrue(harness.actions().collectClickFallbackCandidates(root).isEmpty())
    }

    // --------------------------------------------------------------------------- formatting

    @Test
    fun `describeNode renders class, resource id and bounds`() {
        val harness = Harness(ids)
        val node = FakeNode(
            attrs = LiveNodeAttributes(
                className = "android.widget.Button",
                viewIdResourceName = "com.app:id/ok",
                boundsShortString = "[0,0][10,10]",
            ),
            name = "n",
        )

        assertEquals("android.widget.Button[com.app:id/ok]@[0,0][10,10]", harness.actions().describeNode(node))
    }

    @Test
    fun `describeNode tolerates entirely absent attributes`() {
        val harness = Harness(ids)

        assertEquals("[]@", harness.actions().describeNode(FakeNode(name = "n")))
    }

    @Test
    fun `actionName maps every known id and falls back for the rest`() {
        val actions = Harness(ids).actions()

        assertEquals("ACTION_SCROLL_FORWARD", actions.actionName(ids.scrollForward))
        assertEquals("ACTION_SCROLL_BACKWARD", actions.actionName(ids.scrollBackward))
        assertEquals("ACTION_CLICK", actions.actionName(ids.click))
        assertEquals("ACTION_SCROLL_DOWN", actions.actionName(ids.scrollDown))
        assertEquals("ACTION_SCROLL_UP", actions.actionName(ids.scrollUp))
        assertEquals("ACTION_PAGE_DOWN", actions.actionName(ids.pageDown))
        assertEquals("ACTION_PAGE_UP", actions.actionName(ids.pageUp))
        assertEquals("ACTION_77", actions.actionName(77))
    }

    // -------------------------------------------------------------------------- performClick

    @Test
    fun `performClick skips path nodes that are invisible or disabled`() {
        val harness = Harness(ids)
        val hiddenChild = FakeNode(
            attrs = LiveNodeAttributes(text = "Hidden", visibleToUser = false, enabled = true, clickable = true),
            name = "hidden",
        )
        val root = FakeNode(
            attrs = LiveNodeAttributes(visibleToUser = true, enabled = true),
            children = listOf(hiddenChild),
            name = "root",
        )
        val element = PressableElement(
            label = "Hidden",
            resourceId = null,
            bounds = "[0,0][1,1]",
            className = null,
            isListItem = false,
            childIndexPath = listOf(0),
        )

        harness.actions().performClick(root, element)

        assertFalse(harness.performedOrder.any { it.first == "hidden" })
    }

    @Test
    fun `performClick reports failure through the diagnostics sink`() {
        val harness = Harness(ids)
        val root = FakeNode(attrs = LiveNodeAttributes(visibleToUser = true, enabled = true), name = "root")
        val element = PressableElement(
            label = "Missing",
            resourceId = null,
            bounds = "[0,0][1,1]",
            className = null,
            isListItem = false,
            childIndexPath = listOf(4),
        )

        assertFalse(harness.actions().performClick(root, element))
        assertTrue(harness.diagnostics.any { it.contains("Click action failed for 'Missing'") })
    }
}
