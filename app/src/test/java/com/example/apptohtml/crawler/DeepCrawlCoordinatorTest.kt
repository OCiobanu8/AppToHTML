package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeepCrawlCoordinatorTest {
    @Test
    fun bfsTraversal_captures_nested_chain_and_reports_max_depth() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-chain").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Open B", 0)),
                        transitions = mapOf("Open B" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = listOf(fakeElement("Open C", 0)),
                        transitions = mapOf("Open C" to "C"),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Screen C",
                        elements = listOf(fakeElement("Open D", 0)),
                        transitions = mapOf("Open D" to "D"),
                    ),
                    "D" to fakeScreen(
                        id = "D",
                        screenName = "Screen D",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(4, summary.capturedScreenCount)
            assertEquals(3, summary.maxDepthReached)
            assertTrue(manifestJson.contains(""""maxDepthReached": 3"""))
            assertTrue(manifestJson.contains(""""screenName": "Screen D""""))
            assertTrue(manifestJson.contains(""""depth": 3"""))
            assertTrue(manifestJson.contains(""""label": "Open B""""))
            assertTrue(manifestJson.contains(""""label": "Open C""""))
            assertTrue(manifestJson.contains(""""label": "Open D""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_terminates_cycle_by_linking_existing_screen() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-cycle").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Open B", 0)),
                        transitions = mapOf("Open B" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = listOf(fakeElement("Back To A", 0)),
                        transitions = mapOf("Back To A" to "A"),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(2, summary.capturedScreenCount)
            assertEquals(1, summary.maxDepthReached)
            assertTrue(manifestJson.contains("linked_existing"))
            assertTrue(manifestJson.contains(""""screenName": "Screen A""""))
            assertTrue(manifestJson.contains(""""screenName": "Screen B""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_allows_cross_package_child_screens() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-cross-package").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google",
                        packageName = "com.google.android.googlequicksearchbox",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val googleXml = tempDir.walkTopDown()
                .firstOrNull { it.isFile && it.extension == "xml" && it.readText().contains("com.google.android.googlequicksearchbox") }

            assertEquals(2, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Google""""))
            assertTrue(googleXml != null)
            assertTrue(manifestJson.contains("captured"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_recovers_root_when_entry_screen_has_misleading_back_affordance() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-entry-restore").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Open B", 0),
                            fakeElement("Open C", 1),
                        ),
                        transitions = mapOf(
                            "Open B" to "B",
                            "Open C" to "C",
                        ),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Screen C",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                showBackAffordanceOnEntryRoot = true,
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(3, summary.capturedScreenCount)
            assertEquals(2, summary.capturedChildScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Screen B""""))
            assertTrue(manifestJson.contains(""""screenName": "Screen C""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_relaunches_when_capture_starts_on_ambiguous_non_entry_screen() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-ambiguous-entry").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                initialScreenId = "B",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Open C", 0)),
                        transitions = mapOf("Open C" to "C"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Screen C",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                screensWithoutBackAffordance = setOf("B"),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenB",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(2, summary.capturedScreenCount)
            assertEquals("Screen A", summary.rootScreenName)
            assertTrue(host.relaunchCount >= 1)
            assertTrue(manifestJson.contains(""""screenName": "Screen A""""))
            assertTrue(manifestJson.contains(""""screenName": "Screen C""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_replays_same_logical_screen_when_replay_bounds_shift() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-logical-replay").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Open B", 0),
                            fakeElement("Open C", 1),
                        ),
                        transitions = mapOf(
                            "Open B" to "B",
                            "Open C" to "C",
                        ),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Screen C",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                shiftedBoundsOnReplayScreens = setOf("A"),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(3, summary.capturedScreenCount)
            assertEquals(2, summary.capturedChildScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Screen B""""))
            assertTrue(manifestJson.contains(""""screenName": "Screen C""""))
            assertTrue(!manifestJson.contains(""""status": "failed""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_writes_crawl_log_with_frontier_and_link_entries() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-log").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Open B", 0),
                            fakeElement("Open B Again", 1),
                        ),
                        transitions = mapOf(
                            "Open B" to "B",
                            "Open B Again" to "B",
                        ),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val crawlLog = File(summary.manifestFile.parentFile, "crawl.log")

            assertTrue(crawlLog.exists())
            val crawlLogText = crawlLog.readText()
            assertTrue(crawlLogText.contains("crawl_start"))
            assertTrue(crawlLogText.contains("manifest_status_transition status=in_progress"))
            assertTrue(crawlLogText.contains("frontier_mutation mutation=enqueue_initial_root"))
            assertTrue(crawlLogText.contains("frontier_dequeue screenId=screen_000"))
            assertTrue(crawlLogText.contains("linked_existing"))
            assertTrue(crawlLogText.contains("manifest_status_transition status=completed"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_captures_distinct_children_when_generic_title_is_weak() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-weak-title").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(
                            fakeElement("Open Google", 0),
                            fakeElement("Open SIMs", 1),
                        ),
                        transitions = mapOf(
                            "Open Google" to "B",
                            "Open SIMs" to "C",
                        ),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Navigate up",
                        elements = listOf(fakeElement("Connected devices & sharing", 0)),
                        transitions = emptyMap(),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Navigate up",
                        elements = listOf(fakeElement("SIMs", 0)),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(3, summary.capturedScreenCount)
            assertEquals(2, summary.capturedChildScreenCount)
            assertTrue(!manifestJson.contains("linked_existing"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_logs_unexpected_exception_details() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-log-failure").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Explode", 0)),
                        transitions = mapOf("Explode" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                override fun click(element: PressableElement): Boolean {
                    throw IllegalStateException("boom")
                }
            }

            runCatching {
                coordinator(host, tempDir).crawl(
                    initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                    eventClassName = "ScreenA",
                )
            }

            val crawlLog = tempDir.walkTopDown()
                .firstOrNull { it.isFile && it.name == "crawl.log" }
                ?: error("crawl.log was not created")
            val crawlLogText = crawlLog.readText()

            assertTrue(crawlLogText.contains("manifest_status_transition status=failed"))
            assertTrue(crawlLogText.contains("unexpected_crawler_exception"))
            assertTrue(crawlLogText.contains("throwableClass=java.lang.IllegalStateException"))
            assertTrue(crawlLogText.contains("stacktrace=java.lang.IllegalStateException: boom"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bfsTraversal_propagates_cancellation_without_failure_manifest_or_unexpected_log() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-log-cancellation").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Cancel", 0)),
                        transitions = mapOf("Cancel" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                override fun click(element: PressableElement): Boolean {
                    throw CancellationException("test cancellation")
                }
            }

            try {
                coordinator(host, tempDir).crawl(
                    initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                    eventClassName = "ScreenA",
                )
                fail("Expected crawl cancellation to propagate.")
            } catch (cancellation: CancellationException) {
                assertEquals("test cancellation", cancellation.message)
            }

            val crawlLog = tempDir.walkTopDown()
                .firstOrNull { it.isFile && it.name == "crawl.log" }
                ?: error("crawl.log was not created")
            val crawlLogText = crawlLog.readText()

            assertTrue(crawlLogText.contains("crawl_canceled"))
            assertFalse(crawlLogText.contains("manifest_status_transition status=failed"))
            assertFalse(crawlLogText.contains("unexpected_crawler_exception"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun coordinator(
        host: FakeHost,
        tempDir: File,
    ): DeepCrawlCoordinator {
        return DeepCrawlCoordinator(
            selectedApp = selectedApp(),
            host = host,
            loadBlacklist = { CrawlBlacklist(skipCheckable = false) },
            createSession = {
                val sessionDir = File(tempDir, "session-$it").apply { mkdirs() }
                CrawlSessionDirectory(
                    sessionId = sessionDir.name,
                    directory = sessionDir,
                    manifestFile = File(sessionDir, "crawl-index.json"),
                    logFile = File(sessionDir, "crawl.log"),
                )
            },
            scanScreenOverride = { _, initialRoot, _, _ ->
                host.snapshotForRoot(initialRoot)
            },
        )
    }

    private fun selectedApp(): SelectedAppRef {
        return SelectedAppRef(
            packageName = "com.example.target",
            appName = "Target",
            launcherActivity = "com.example.target.MainActivity",
            selectedAt = 123L,
        )
    }

    private fun fakeScreen(
        id: String,
        screenName: String,
        packageName: String = "com.example.target",
        elements: List<PressableElement>,
        transitions: Map<String, String>,
    ): FakeScreen {
        return FakeScreen(
            id = id,
            screenName = screenName,
            packageName = packageName,
            elements = elements,
            transitions = transitions,
        )
    }

    private fun fakeElement(label: String, index: Int): PressableElement {
        return PressableElement(
            label = label,
            resourceId = "com.example.target:id/${label.lowercase().replace(' ', '_')}",
            bounds = "[0,${index * 100}][200,${index * 100 + 80}]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(index),
            firstSeenStep = 0,
        )
    }

    private data class FakeScreen(
        val id: String,
        val screenName: String,
        val packageName: String,
        val elements: List<PressableElement>,
        val transitions: Map<String, String>,
    )

    private open class FakeHost(
        private val entryScreenId: String,
        initialScreenId: String = entryScreenId,
        private val screens: Map<String, FakeScreen>,
        private val showBackAffordanceOnEntryRoot: Boolean = false,
        private val screensWithoutBackAffordance: Set<String> = emptySet(),
        private val shiftedBoundsOnReplayScreens: Set<String> = emptySet(),
    ) : DeepCrawlCoordinator.Host {
        private var currentScreenId = initialScreenId
        private val backStack = mutableListOf(initialScreenId)
        private val captureCountsByScreenId = mutableMapOf<String, Int>()
        private var returnedToEntryScreen = false
        var relaunchCount = 0
            private set

        override suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot? {
            if (expectedPackageName != null && expectedPackageName != "com.example.target") {
                return null
            }
            val captureCount = captureCountsByScreenId.getOrDefault(currentScreenId, 0)
            captureCountsByScreenId[currentScreenId] = captureCount + 1
            return rootFor(
                screenId = currentScreenId,
                shiftedBounds = currentScreenId in shiftedBoundsOnReplayScreens && captureCount >= 2,
            )
        }

        override fun scrollForward(childIndexPath: List<Int>): Boolean = false

        override fun scrollBackward(childIndexPath: List<Int>): Boolean = false

        override fun click(element: PressableElement): Boolean {
            val current = screens.getValue(currentScreenId)
            val destination = current.transitions[element.label] ?: return false
            if (destination != currentScreenId) {
                currentScreenId = destination
                backStack += destination
            }
            return true
        }

        override fun performGlobalBack(): Boolean {
            if (backStack.size <= 1) {
                return false
            }
            backStack.removeAt(backStack.lastIndex)
            currentScreenId = backStack.last()
            if (currentScreenId == entryScreenId) {
                returnedToEntryScreen = true
            }
            return true
        }

        override suspend fun relaunchTargetApp(selectedApp: SelectedAppRef): String? {
            relaunchCount += 1
            currentScreenId = entryScreenId
            backStack.clear()
            backStack += entryScreenId
            return null
        }

        override fun publishProgress(message: String) = Unit

        override fun setActiveCrawlLogger(logger: CrawlLogger?) = Unit

        fun snapshotForRoot(root: AccessibilityNodeSnapshot): ScreenSnapshot {
            val screenId = root.viewIdResourceName?.substringAfterLast('/') ?: error("Missing fake screen id.")
            val screen = screens.getValue(screenId)
            return ScreenSnapshot(
                screenName = screen.screenName,
                packageName = screen.packageName,
                elements = screen.elements,
                xmlDump = """<screen id="$screenId" package="${screen.packageName}" />""",
                stepSnapshots = listOf(
                    ScrollCaptureStep(
                        stepIndex = 0,
                        root = root,
                        newElementCount = screen.elements.size,
                    )
                ),
                scrollStepCount = 1,
            )
        }

        private fun rootFor(
            screenId: String,
            shiftedBounds: Boolean = false,
        ): AccessibilityNodeSnapshot {
            val screen = screens.getValue(screenId)
            val rootBounds = shiftedBounds.shiftedBounds("[0,0][1080,2400]")
            return AccessibilityNodeSnapshot(
                className = "android.widget.FrameLayout",
                packageName = screen.packageName,
                viewIdResourceName = "com.example.target:id/$screenId",
                text = null,
                contentDescription = null,
                clickable = false,
                supportsClickAction = false,
                scrollable = false,
                enabled = true,
                visibleToUser = true,
                bounds = rootBounds,
                children = buildList {
                    screen.elements.mapIndexedTo(this) { index, element ->
                        AccessibilityNodeSnapshot(
                            className = element.className,
                            packageName = screen.packageName,
                            viewIdResourceName = element.resourceId,
                            text = element.label,
                            contentDescription = null,
                            clickable = true,
                            supportsClickAction = true,
                            scrollable = false,
                            enabled = true,
                            visibleToUser = true,
                            bounds = shiftedBounds.shiftedBounds(element.bounds),
                            children = emptyList(),
                            childIndexPath = listOf(index),
                        )
                    }
                    if (screenId == entryScreenId && showBackAffordanceOnEntryRoot && returnedToEntryScreen) {
                        add(
                            AccessibilityNodeSnapshot(
                                className = "android.widget.ImageButton",
                                packageName = screen.packageName,
                                viewIdResourceName = "com.example.target:id/up_button_hint",
                                text = null,
                                contentDescription = "Navigate up",
                                clickable = true,
                                supportsClickAction = true,
                                scrollable = false,
                                enabled = true,
                                visibleToUser = true,
                                bounds = shiftedBounds.shiftedBounds("[0,0][120,120]"),
                                children = emptyList(),
                                childIndexPath = listOf(screen.elements.size),
                            )
                        )
                    }
                    if (screenId != entryScreenId && screenId !in screensWithoutBackAffordance) {
                        add(
                            AccessibilityNodeSnapshot(
                                className = "android.widget.ImageButton",
                                packageName = screen.packageName,
                                viewIdResourceName = "com.example.target:id/back_button",
                                text = null,
                                contentDescription = "Navigate up",
                                clickable = true,
                                supportsClickAction = true,
                                scrollable = false,
                                enabled = true,
                                visibleToUser = true,
                                bounds = shiftedBounds.shiftedBounds("[0,0][120,120]"),
                                children = emptyList(),
                                childIndexPath = listOf(screen.elements.size),
                            )
                        )
                    }
                },
            )
        }

        private fun Boolean.shiftedBounds(bounds: String): String {
            if (!this) {
                return bounds
            }

            val match = BOUNDS_REGEX.matchEntire(bounds) ?: return bounds
            val left = match.groupValues[1].toInt() + REPLAY_BOUNDS_SHIFT_PX
            val top = match.groupValues[2].toInt() + REPLAY_BOUNDS_SHIFT_PX
            val right = match.groupValues[3].toInt() + REPLAY_BOUNDS_SHIFT_PX
            val bottom = match.groupValues[4].toInt() + REPLAY_BOUNDS_SHIFT_PX
            return "[$left,$top][$right,$bottom]"
        }

        private companion object {
            private const val REPLAY_BOUNDS_SHIFT_PX = 24
            private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        }
    }
}
