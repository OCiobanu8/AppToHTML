package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pins for `a2h-c2b.3` scope call E and PLAN Step 6: known screens' trees, read once from disk. */
class KnownScreenTreesTest {

    @Test
    fun a_readable_capture_is_read_once_and_then_served_from_the_cache() {
        val tree = LifecycleTrees.cart()
        val reads = mutableListOf<File>()
        val trees = KnownScreenTrees(read = { file -> reads += file; tree })

        val first = trees.treeFor(record("screen_00001"))
        val second = trees.treeFor(record("screen_00001"))

        assertSame(tree, first)
        assertSame(tree, second)
        assertEquals(1, reads.size)
    }

    @Test
    fun an_unreadable_capture_is_read_once_reported_once_and_then_null() {
        val reads = mutableListOf<File>()
        val reported = mutableListOf<String>()
        val trees = KnownScreenTrees(
            read = { file -> reads += file; null },
            onUnreadable = { screenId, _ -> reported += screenId },
        )

        assertNull(trees.treeFor(record("screen_00004")))
        assertNull(trees.treeFor(record("screen_00004")))

        assertEquals(1, reads.size)
        assertEquals(listOf("screen_00004"), reported)
    }

    @Test
    fun known_screens_pair_each_record_with_its_identity_and_tree() {
        val tree = LifecycleTrees.cart()
        val trees = KnownScreenTrees(read = { file -> if (file.name.startsWith("screen_00001")) tree else null })

        val known = trees.knownScreens(listOf(record("screen_00001"), record("screen_00002")))

        assertEquals(listOf("screen_00001", "screen_00002"), known.map { it.screenId })
        assertSame(tree, known[0].tree)
        assertNull(known[1].tree)
        assertEquals(record("screen_00001").identity, known[0].identity)
    }

    @Test
    fun the_default_reader_reads_the_first_viewport_from_the_saved_xml() {
        val dir = Files.createTempDirectory("known-trees").toFile()
        try {
            val root = LifecycleTrees.cart()
            val xml = File(dir, "screen_00001_cart.xml")
            xml.writeText(
                AccessibilityXmlSerializer.serialize(
                    ScreenSnapshot(
                        screenName = "Cart",
                        packageName = LifecycleTrees.PKG,
                        elements = emptyList(),
                        xmlDump = "",
                        stepSnapshots = listOf(ScrollCaptureStep(stepIndex = 0, root = root, newElementCount = 0)),
                        scrollStepCount = 1,
                    ),
                ),
            )

            val read = KnownScreenTrees().treeFor(record("screen_00001", xml.absolutePath))!!

            assertEquals(ScreenIdentity.fromRoot(root), ScreenIdentity.fromRoot(read))
            assertEquals(TraitProposer.propose(root), TraitProposer.propose(read))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Round-3 D1. A label that spans lines (or carries a tab) must come back from the saved XML exactly
     * as captured — otherwise the uniqueness check reads a different screen from disk than the one the
     * crawler settled, and a settled identity stops holding on its own capture.
     */
    @Test
    fun a_multi_line_label_survives_the_saved_xml_and_the_settled_identity_still_holds_on_it() {
        val dir = Files.createTempDirectory("known-trees-lines").toFile()
        try {
            val root = LifecycleTrees.build(
                LifecycleTrees.NodeSpec(
                    className = "android.widget.FrameLayout",
                    resourceId = null,
                    bounds = "[0,0][1080,2400]",
                    children = listOf(
                        LifecycleTrees.NodeSpec(
                            className = "android.widget.Button",
                            resourceId = "${LifecycleTrees.PKG}:id/track",
                            text = "Track\norder",
                            clickable = true,
                            bounds = "[0,300][1080,380]",
                        ),
                        LifecycleTrees.NodeSpec(
                            className = "android.widget.Button",
                            resourceId = "${LifecycleTrees.PKG}:id/help",
                            contentDescription = "Help\twith\r\nthis",
                            clickable = true,
                            bounds = "[0,400][1080,480]",
                        ),
                    ),
                ),
            )
            val xml = File(dir, "screen_00001_order.xml")
            xml.writeText(
                AccessibilityXmlSerializer.serialize(
                    ScreenSnapshot(
                        screenName = "Order",
                        packageName = LifecycleTrees.PKG,
                        elements = emptyList(),
                        xmlDump = "",
                        stepSnapshots = listOf(ScrollCaptureStep(stepIndex = 0, root = root, newElementCount = 0)),
                        scrollStepCount = 1,
                    ),
                ),
            )

            val reread = KnownScreenTrees().treeFor(record("screen_00001", xml.absolutePath))!!
            val settled = Lifecycle.settled(root, Lifecycle.name("Order"))

            assertEquals(ScreenIdentity.fromRoot(root), ScreenIdentity.fromRoot(reread))
            assertEquals(
                TraitVerdict.HOLDS,
                TraitEvaluator.verdict(settled, TraitEvaluationContext.of(reread, Lifecycle.name("Order"))),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Round-4 D4. A label carrying a character XML 1.0 cannot represent — a vertical tab, half of a
     * surrogate pair — must not make the saved screen unreadable. It is written as U+FFFD; the file
     * stays well-formed, and a genuine pair (an emoji) survives intact.
     */
    @Test
    fun a_label_with_characters_xml_cannot_carry_still_saves_a_readable_screen() {
        val dir = Files.createTempDirectory("known-trees-invalid").toFile()
        try {
            val root = LifecycleTrees.buttons("plain").let { base ->
                base.copy(
                    children = base.children + base.children.single().copy(
                        viewIdResourceName = "${LifecycleTrees.PKG}:id/odd",
                        text = "Tab\u000Bbed \uD83D\uDE00 half\uD800",
                        bounds = "[0,500][1080,580]",
                        childIndexPath = listOf(1),
                    ),
                )
            }
            val xml = File(dir, "screen_00001_odd.xml")
            xml.writeText(
                AccessibilityXmlSerializer.serialize(
                    ScreenSnapshot(
                        screenName = "Odd",
                        packageName = LifecycleTrees.PKG,
                        elements = emptyList(),
                        xmlDump = "",
                        stepSnapshots = listOf(ScrollCaptureStep(stepIndex = 0, root = root, newElementCount = 0)),
                        scrollStepCount = 1,
                    ),
                ),
            )

            val reread = KnownScreenTrees().treeFor(record("screen_00001", xml.absolutePath))

            assertTrue("the saved screen is no longer readable", reread != null)
            val texts = reread!!.children.mapNotNull { it.text }
            assertTrue(texts.toString(), texts.contains("Tab\uFFFDbed \uD83D\uDE00 half\uFFFD"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Round-5 D1: the one rule for text XML cannot carry. Applied at the device boundary as well as by
     * the writer, so the crawler's own view of a screen and its saved capture are the same text.
     */
    @Test
    fun text_xml_cannot_carry_is_replaced_and_everything_else_kept() {
        assertEquals("bell\uFFFD not\uFFFD half\uFFFD", xmlRepresentable("bell\u0007 not\uFFFE half\uD800"))
        assertEquals("keep\n\r\t \uD83D\uDE00 & <x>", xmlRepresentable("keep\n\r\t \uD83D\uDE00 & <x>"))
        val tree = LifecycleTrees.buttons("plain").let { root ->
            root.copy(children = root.children.map { it.copy(text = "Reply\u0007", contentDescription = "x\u0001") })
        }
        val cleaned = tree.xmlRepresentable().children.single()
        assertEquals("Reply\uFFFD", cleaned.text)
        assertEquals("x\uFFFD", cleaned.contentDescription)
    }

    @Test
    fun the_crawl_loader_never_takes_the_case_file_for_a_screen() {
        assertFalse(SavedCrawlLoader.isCrawlScreenXml(File(IdentityCaseStore.FILE_NAME)))
        assertFalse(SavedCrawlLoader.isCrawlScreenXml(File("screen_00001_cart_merged_accessibility.xml")))
        assertTrue(SavedCrawlLoader.isCrawlScreenXml(File("screen_00001_cart.xml")))
    }

    private fun record(screenId: String, xmlPath: String = "$screenId.xml") = CrawlScreenRecord(
        screenId = screenId,
        screenName = "Cart",
        packageName = LifecycleTrees.PKG,
        identity = Lifecycle.settled(LifecycleTrees.cart(), Lifecycle.name("Cart")),
        htmlPath = xmlPath.removeSuffix(".xml") + ".html",
        xmlPath = xmlPath,
        scrollStepCount = 1,
        parentScreenId = null,
        triggerLabel = null,
        triggerResourceId = null,
        depth = 0,
    )
}
