package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrawlerTraversalTest {
    @Test
    fun blacklist_parser_marks_label_resource_class_and_checkable_matches() {
        val blacklist = CrawlBlacklistLoader.parse(
            """
            {
              "labelTokens": ["search"],
              "resourceIdTokens": ["install"],
              "classNameTokens": ["switch"],
              "skipCheckable": true
            }
            """.trimIndent()
        )

        assertEquals(
            "blacklist-label",
            blacklist.skipReason(
                PressableElement(
                    label = "Search settings",
                    resourceId = null,
                    bounds = "[0,0][10,10]",
                    className = "android.widget.Button",
                    isListItem = false,
                )
            )
        )
        assertEquals(
            "blacklist-resource-id",
            blacklist.skipReason(
                PressableElement(
                    label = "Install now",
                    resourceId = "com.example:id/install_button",
                    bounds = "[0,0][10,10]",
                    className = "android.widget.Button",
                    isListItem = false,
                )
            )
        )
        assertEquals(
            "blacklist-class",
            blacklist.skipReason(
                PressableElement(
                    label = "Wifi",
                    resourceId = "com.example:id/wifi",
                    bounds = "[0,0][10,10]",
                    className = "android.widget.Switch",
                    isListItem = false,
                )
            )
        )
        assertEquals(
            "blacklist-checkable",
            blacklist.skipReason(
                testElement(
                    label = "Notifications",
                    resourceId = "com.example:id/notifications",
                    className = "android.widget.TextView",
                    checkable = true,
                )
            )
        )
    }

    @Test
    fun default_blacklist_blocks_representative_risky_actions_from_each_category() {
        val blacklist = loadDefaultBlacklist()

        assertEquals(
            "blacklist-label",
            blacklist.skipReason(testElement(label = "Switch account"))
        )
        assertEquals(
            "blacklist-label",
            blacklist.skipReason(testElement(label = "Factory reset"))
        )
        assertEquals(
            "blacklist-resource-id",
            blacklist.skipReason(testElement(resourceId = "com.example:id/start_trial_button"))
        )
        assertEquals(
            "blacklist-label",
            blacklist.skipReason(testElement(label = "Submit"))
        )
        assertEquals(
            "blacklist-label",
            blacklist.skipReason(testElement(label = "Allow all the time"))
        )
        assertEquals(
            "blacklist-resource-id",
            blacklist.skipReason(testElement(resourceId = "com.example:id/open_in_browser_button"))
        )
        assertEquals(
            "blacklist-label",
            blacklist.skipReason(testElement(label = "Download"))
        )
        assertEquals(
            "blacklist-resource-id",
            blacklist.skipReason(testElement(resourceId = "com.example:id/take_photo_button"))
        )
        assertEquals(
            "blacklist-class",
            blacklist.skipReason(testElement(className = "android.widget.RadioButton"))
        )
        assertEquals(
            "blacklist-checkable",
            blacklist.skipReason(testElement(checkable = true))
        )
    }

    @Test
    fun traversalPlanner_keeps_safe_navigation_targets_and_skips_risky_ones_from_default_blacklist() {
        val blacklist = loadDefaultBlacklist()
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(
                PressableElement(
                    label = "Details",
                    resourceId = "com.example:id/details",
                    bounds = "[0,500][100,550]",
                    className = "android.widget.Button",
                    isListItem = false,
                    firstSeenStep = 0,
                ),
                PressableElement(
                    label = "Delete account",
                    resourceId = "com.example:id/delete_account",
                    bounds = "[0,100][100,150]",
                    className = "android.widget.Button",
                    isListItem = false,
                    firstSeenStep = 0,
                ),
                PressableElement(
                    label = "Profile",
                    resourceId = "com.example:id/profile",
                    bounds = "[0,50][100,100]",
                    className = "android.widget.Button",
                    isListItem = false,
                    firstSeenStep = 0,
                ),
                PressableElement(
                    label = "Send feedback",
                    resourceId = "com.example:id/send_feedback",
                    bounds = "[0,40][100,90]",
                    className = "android.widget.Button",
                    isListItem = false,
                    firstSeenStep = 2,
                ),
            ),
            xmlDump = "<screen />",
        )

        val plan = TraversalPlanner.planRootTraversal(snapshot, blacklist)

        assertEquals(listOf("Profile", "Details"), plan.eligibleElements.map { it.label })
        assertEquals(listOf("Delete account", "Send feedback"), plan.skippedElements.map { it.element.label })
        assertNull(plan.eligibleElements.firstOrNull { it.label == "Delete account" })
        assertNull(plan.eligibleElements.firstOrNull { it.label == "Send feedback" })
    }

    @Test
    fun captureStore_persists_multiple_screens_and_manifest_in_one_session_directory() {
        val baseDir = Files.createTempDirectory("crawl-session-test").toFile()
        try {
            val session = CrawlSessionDirectory(
                sessionId = "crawl_test",
                directory = File(baseDir, "crawl_test").apply { mkdirs() },
                manifestFile = File(baseDir, "crawl_test/crawl-index.json"),
            )
            val tracker = CrawlRunTracker(
                sessionId = session.sessionId,
                packageName = "com.example.target",
                startedAt = 123L,
            )

            val rootSequence = tracker.nextScreenSequenceNumber()
            val rootSnapshot = testSnapshot("Home Screen", "Open Details")
            val rootFiles = CaptureFileStore.saveScreen(session, rootSnapshot, rootSequence, "root")
            tracker.addScreen(
                screenId = "screen_000",
                snapshot = rootSnapshot,
                files = rootFiles,
                parentScreenId = null,
                triggerElement = null,
                depth = 0,
            )

            val childSequence = tracker.nextScreenSequenceNumber()
            val trigger = rootSnapshot.elements.first()
            val childSnapshot = testSnapshot("Details Screen", "Done")
            val childFiles = CaptureFileStore.saveScreen(session, childSnapshot, childSequence, "child")
            tracker.addScreen(
                screenId = "screen_001",
                snapshot = childSnapshot,
                files = childFiles,
                parentScreenId = "screen_000",
                triggerElement = trigger,
                depth = 1,
            )
            tracker.addEdge(
                parentScreenId = "screen_000",
                childScreenId = "screen_001",
                element = trigger,
                status = CrawlEdgeStatus.CAPTURED,
                message = "Captured child screen.",
            )
            CaptureFileStore.rewriteScreenHtml(
                files = rootFiles,
                snapshot = rootSnapshot,
                resolvedChildLinks = mapOf(trigger.toLinkKey() to childFiles.htmlFile.name),
            )

            val manifestFile = CaptureFileStore.saveManifest(
                session = session,
                manifest = tracker.buildManifest(CrawlRunStatus.COMPLETED, finishedAt = 456L),
            )

            assertTrue(rootFiles.htmlFile.exists())
            assertTrue(rootFiles.xmlFile.exists())
            assertTrue(rootFiles.mergedXmlFile?.exists() == true)
            assertTrue(childFiles.htmlFile.exists())
            assertTrue(childFiles.xmlFile.exists())
            assertTrue(childFiles.mergedXmlFile?.exists() == true)
            assertTrue(manifestFile.exists())
            assertEquals("000_root_home_screen.html", rootFiles.htmlFile.name)
            assertEquals("001_child_details_screen.html", childFiles.htmlFile.name)
            assertEquals("000_root_home_screen_merged_accessibility.xml", rootFiles.mergedXmlFile?.name)
            val rootHtml = rootFiles.htmlFile.readText()
            assertTrue(rootHtml.contains("""href="001_child_details_screen.html""""))
            assertTrue(rootHtml.contains(">Open Details</a>"))

            val manifestJson = manifestFile.readText()
            assertTrue(manifestJson.contains("screen_000"))
            assertTrue(manifestJson.contains("screen_001"))
            assertTrue(manifestJson.contains("captured"))
            assertTrue(manifestJson.contains("mergedXmlPath"))
            assertTrue(CrawlManifestStore.toJson(tracker.buildManifest(CrawlRunStatus.COMPLETED)).contains(""""screens": ["""))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private fun testSnapshot(screenName: String, label: String): ScreenSnapshot {
        val root = AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.example.target",
            viewIdResourceName = null,
            text = null,
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
                    viewIdResourceName = "com.example.target:id/button",
                    text = label,
                    contentDescription = null,
                    clickable = true,
                    supportsClickAction = true,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = "[0,0][100,50]",
                    children = emptyList(),
                    childIndexPath = listOf(0),
                )
            ),
        )
        return AccessibilityTreeSnapshotter.buildMergedScreenSnapshot(
            selectedApp = com.example.apptohtml.model.SelectedAppRef(
                packageName = "com.example.target",
                appName = "Target",
                launcherActivity = "com.example.target.MainActivity",
                selectedAt = 123L,
            ),
            eventClassName = "com.example.target.MainActivity",
            stepRoots = listOf(root),
        ).copy(screenName = screenName)
    }

    private fun loadDefaultBlacklist(): CrawlBlacklist {
        val candidates = listOf(
            File("src/main/res/raw/crawl_blacklist.json"),
            File("app/src/main/res/raw/crawl_blacklist.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate crawl_blacklist.json from test working directory.")
        return CrawlBlacklistLoader.parse(file.readText())
    }

    private fun testElement(
        label: String = "Safe target",
        resourceId: String? = "com.example:id/safe_target",
        className: String = "android.widget.Button",
        checkable: Boolean = false,
    ): PressableElement {
        return PressableElement(
            label = label,
            resourceId = resourceId,
            bounds = "[0,0][10,10]",
            className = className,
            isListItem = false,
            checkable = checkable,
        )
    }
}
