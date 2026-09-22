package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A capture read back has to answer exactly what the live tree would.
 *
 * What is pinned is not field equality but *agreement on the questions that decide a verdict*: the
 * identity built from it, the trait verdicts evaluated against it, and the click candidates
 * collected from it. A reader that dropped a field no rule consults would be harmless; one that
 * dropped `visible-to-user` would silently change every identity.
 */
class CaptureTreeReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a round-tripped tree yields the same identity`() {
        val original = positioned(awkwardTree())

        val reread = roundTrip(original)

        assertNotNull(reread)
        assertEquals(
            "The identity is what every screen-level comparison runs on.",
            ScreenIdentity.fromRoot(original),
            ScreenIdentity.fromRoot(reread!!),
        )
    }

    @Test
    fun `a round-tripped tree yields the same trait verdicts`() {
        val original = positioned(awkwardTree())
        val reread = roundTrip(original)!!

        val traits = listOf(
            HasList(
                containerResourceId = "$PACKAGE:id/list",
                minRows = 2,
                rowChildResourceIds = setOf("$PACKAGE:id/title"),
            ),
            HasControl(
                ScreenIdentity.fromRoot(original).elements.first { it.fingerprint.editable }
            ),
        )
        val identity = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = emptySet(),
            traits = traits,
        )

        assertEquals(
            "A trait must reach the same answer on the file as on the screen, or settling by hand " +
                "means nothing.",
            TraitEvaluator.verdict(identity, TraitEvaluationContext.of(original)),
            TraitEvaluator.verdict(identity, TraitEvaluationContext.of(reread)),
        )
        assertEquals(
            TraitVerdict.HOLDS,
            TraitEvaluator.verdict(identity, TraitEvaluationContext.of(reread)),
        )
    }

    @Test
    fun `a round-tripped tree yields the same click candidates`() {
        val original = positioned(awkwardTree())
        val reread = roundTrip(original)!!

        assertEquals(
            "Re-clickability is reported per element; the candidates must be the same set.",
            AccessibilityTreeSnapshotter.collectPressableElements(original),
            AccessibilityTreeSnapshotter.collectPressableElements(reread),
        )
    }

    @Test
    fun `invisible and disabled nodes survive as invisible and disabled`() {
        val original = positioned(awkwardTree())
        val reread = roundTrip(original)!!

        val invisible = flatten(reread).filter { !it.visibleToUser }
        val disabled = flatten(reread).filter { !it.enabled }
        assertTrue("the fixture carries an invisible node", invisible.isNotEmpty())
        assertTrue("the fixture carries a disabled node", disabled.isNotEmpty())
        assertEquals(
            "An invisible node is excluded from identity; losing the flag would invent elements.",
            flatten(original).count { !it.visibleToUser },
            invisible.size,
        )
        assertEquals(flatten(original).count { !it.enabled }, disabled.size)
    }

    @Test
    fun `child index paths are rebuilt from position`() {
        val original = positioned(awkwardTree())
        val reread = roundTrip(original)!!

        assertEquals(
            "Not serialized, so rebuilt — exactly as AccessibilityTreeSnapshotter assigns it.",
            flatten(original).map { it.childIndexPath },
            flatten(reread).map { it.childIndexPath },
        )
    }

    @Test
    fun `the first viewport is read, not a later one`() {
        val first = singlePressableTree(label = "First viewport")
        val second = singlePressableTree(label = "Second viewport")
        val file = write(steps = listOf(first, second))

        val reread = CaptureTreeReader.readFirstViewport(file)!!

        assertTrue(
            "Validating against a later viewport would certify what the crawler cannot see on " +
                "arrival without scrolling.",
            ScreenIdentity.fromRoot(reread).elements.any { it.fingerprint.label == "first viewport" },
        )
    }

    @Test
    fun `steps out of order still yield step zero`() {
        val zero = singlePressableTree(label = "Step zero")
        val one = singlePressableTree(label = "Step one")
        val file = write(steps = listOf(one, zero), indices = listOf(1, 0))

        val reread = CaptureTreeReader.readFirstViewport(file)!!

        assertTrue(
            ScreenIdentity.fromRoot(reread).elements.any { it.fingerprint.label == "step zero" },
        )
    }

    @Test
    fun `a capture with no steps reads as nothing, not as an empty screen`() {
        val file = write(steps = emptyList())

        assertNull(
            "An empty screen and an unreadable one are different answers; conflating them would " +
                "let a broken capture report a clean verdict.",
            CaptureTreeReader.readFirstViewport(file),
        )
    }

    @Test
    fun `a missing file reads as nothing`() {
        assertNull(CaptureTreeReader.readFirstViewport(File(tempFolder.root, "absent.xml")))
    }

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * One tree carrying every shape that has its own decoding rule: escaped text, a nested list,
     * checkable and editable nodes, a click-action-only node, an invisible node and a disabled one.
     */
    private fun awkwardTree(): AccessibilityNodeSnapshot = node(
        className = ROOT_CLASS,
        resourceId = "$PACKAGE:id/root",
        bounds = "[0,0][1080,2400]",
        children = listOf(
            node(
                className = "android.widget.Button",
                resourceId = "$PACKAGE:id/quote",
                text = """Tom & "Jerry" <b>go</b> 'home'""",
                bounds = "[0,100][1080,200]",
                clickable = true,
            ),
            node(
                className = "android.widget.CheckBox",
                resourceId = "$PACKAGE:id/agree",
                text = "Agree",
                bounds = "[0,200][1080,300]",
                clickable = true,
                checkable = true,
                checked = true,
            ),
            node(
                className = "android.widget.EditText",
                resourceId = "$PACKAGE:id/search",
                text = "Search",
                bounds = "[0,300][1080,400]",
                clickable = true,
                editable = true,
            ),
            node(
                className = "android.widget.TextView",
                resourceId = "$PACKAGE:id/action_only",
                text = "Action only",
                bounds = "[0,400][1080,500]",
                clickable = false,
                supportsClickAction = true,
            ),
            node(
                className = "android.widget.TextView",
                resourceId = "$PACKAGE:id/hidden",
                text = "Hidden",
                bounds = "[0,500][1080,600]",
                clickable = true,
                visibleToUser = false,
            ),
            node(
                className = "android.widget.Button",
                resourceId = "$PACKAGE:id/disabled",
                text = "Disabled",
                bounds = "[0,600][1080,700]",
                clickable = true,
                enabled = false,
            ),
            node(
                className = "androidx.recyclerview.widget.RecyclerView",
                resourceId = "$PACKAGE:id/list",
                bounds = "[0,700][1080,2000]",
                scrollable = true,
                children = (0 until 3).map { index ->
                    node(
                        className = "android.widget.LinearLayout",
                        resourceId = "$PACKAGE:id/row",
                        bounds = "[0,${700 + index * 100}][1080,${700 + index * 100 + 90}]",
                        clickable = true,
                        children = listOf(
                            node(
                                className = "android.widget.TextView",
                                resourceId = "$PACKAGE:id/title",
                                text = "Row $index",
                                bounds = "[0,${700 + index * 100}][540,${700 + index * 100 + 90}]",
                            )
                        ),
                    )
                },
            ),
        ),
    )

    private fun singlePressableTree(label: String) = node(
        className = ROOT_CLASS,
        resourceId = "$PACKAGE:id/root",
        bounds = "[0,0][1080,2400]",
        children = listOf(
            node(
                className = "android.widget.Button",
                resourceId = "$PACKAGE:id/only",
                text = label,
                bounds = "[0,100][1080,200]",
                clickable = true,
            )
        ),
    )

    private fun node(
        className: String,
        resourceId: String,
        bounds: String,
        text: String? = null,
        clickable: Boolean = false,
        supportsClickAction: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visibleToUser: Boolean = true,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ) = AccessibilityNodeSnapshot(
        className = className,
        packageName = PACKAGE,
        viewIdResourceName = resourceId,
        text = text,
        contentDescription = null,
        clickable = clickable,
        supportsClickAction = supportsClickAction,
        scrollable = scrollable,
        checkable = checkable,
        checked = checked,
        editable = editable,
        enabled = enabled,
        visibleToUser = visibleToUser,
        bounds = bounds,
        children = children,
    )

    private fun flatten(node: AccessibilityNodeSnapshot): List<AccessibilityNodeSnapshot> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun roundTrip(root: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot? =
        CaptureTreeReader.readFirstViewport(write(steps = listOf(root)))

    /**
     * Gives [root] the child index paths a captured tree always carries.
     *
     * `AccessibilityTreeSnapshotter` assigns them as it walks a live tree, so every real capture has
     * them; a tree built by hand in a test does not. Positioning the fixture is what makes it stand
     * for a capture rather than for a synthetic shape no device would produce.
     */
    private fun positioned(
        root: AccessibilityNodeSnapshot,
        path: List<Int> = emptyList(),
    ): AccessibilityNodeSnapshot = root.copy(
        childIndexPath = path,
        children = root.children.mapIndexed { index, child -> positioned(child, path + index) },
    )

    /** A screen file whose `<scroll-steps>` carries [steps], written by the production serializer. */
    private fun write(
        steps: List<AccessibilityNodeSnapshot>,
        indices: List<Int> = steps.indices.toList(),
    ): File {
        val snapshot = ScreenSnapshot(
            screenName = "Fixture",
            packageName = PACKAGE,
            elements = emptyList(),
            xmlDump = "",
            scrollStepCount = steps.size,
            stepSnapshots = steps.mapIndexed { position, root ->
                ScrollCaptureStep(
                    stepIndex = indices[position],
                    root = root,
                    newElementCount = 0,
                )
            },
        )
        val file = File(tempFolder.newFolder(), "capture_${steps.size}_${indices.hashCode()}.xml")
        file.writeText(AccessibilityXmlSerializer.serialize(snapshot, crawlState()), Charsets.UTF_8)
        return file
    }

    private fun crawlState() = ScreenCrawlState(
        screenId = "screen_00000",
        depth = 0,
        expansionStatus = ScreenExpansionStatus.COMPLETE,
        isRoot = true,
        screenIdentity = ScreenIdentity.EMPTY,
        parent = null,
        route = CrawlRoute(),
        runLevel = null,
        edgesByElement = emptyMap(),
    )

    private companion object {
        const val PACKAGE = "com.example.target"
        const val ROOT_CLASS = "android.widget.FrameLayout"
    }
}
