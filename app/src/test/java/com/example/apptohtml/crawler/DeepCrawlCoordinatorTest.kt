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
            assertTrue(File(summary.manifestFile.parentFile, "crawl-graph.json").exists())
            assertTrue(File(summary.manifestFile.parentFile, "crawl-graph.html").exists())
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
    fun bfsTraversal_skips_editable_elements_when_skipEditable_true() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-editable").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Edit name", 0, editable = true),
                            fakeElement("Open B", 1),
                        ),
                        transitions = mapOf("Open B" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                blacklist = CrawlBlacklist(
                    skipCheckable = false,
                    skipEditable = true,
                ),
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(2, summary.capturedScreenCount)
            assertEquals(1, summary.skippedElementCount)
            assertTrue(manifestJson.contains("blacklist-editable"))
            assertTrue(manifestJson.contains(""""label": "Edit name""""))
            assertTrue(manifestJson.contains(""""label": "Open B""""))
            assertTrue(!manifestJson.contains(""""screenName": "Edit name""""))
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
    fun bfsTraversal_relaunches_when_current_restore_root_is_nonEntryWithoutBackAffordance() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-stale-entry-restore").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(
                            fakeElement("Open SIMs", 0),
                            fakeElement("Open Wi-Fi", 1),
                        ),
                        transitions = mapOf(
                            "Open SIMs" to "S",
                            "Open Wi-Fi" to "W",
                        ),
                    ),
                    "S" to fakeScreen(
                        id = "S",
                        screenName = "SIMs",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "W" to fakeScreen(
                        id = "W",
                        screenName = "Wi-Fi",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                screensWithoutBackAffordance = setOf("S"),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(3, summary.capturedScreenCount)
            assertTrue(host.relaunchCount >= 1)
            assertTrue(manifestJson.contains(""""screenName": "Wi-Fi""""))
            assertTrue(crawlLogText.contains("outcome=expected_logical_not_found"))
            assertTrue(crawlLogText.contains("matchedExpectedLogical=false"))
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
            assertTrue(crawlLogText.contains("frontier_dequeue screenId=screen_00000"))
            assertTrue(crawlLogText.contains("linked_existing"))
            assertTrue(crawlLogText.contains("manifest_status_transition status=completed"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_skips_edge_when_user_selects_skip() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-skip").toFile()
        val externalPackageName = "com.google.android.googlequicksearchbox"
        try {
            val host = object : FakeHost(
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
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                val externalContexts = mutableListOf<ExternalPackageDecisionContext>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    externalPackageContext?.let { externalContexts += it }
                    return PauseDecision.SKIP_EDGE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(1, summary.capturedScreenCount)
            assertEquals(1, summary.skippedElementCount)
            assertEquals(externalPackageName, host.externalContexts.single().nextPackageName)
            assertTrue(manifestJson.contains(""""status": "skipped_external_package""""))
            assertTrue(manifestJson.contains(""""label": "Open Google""""))
            assertFalse(manifestJson.contains(""""screenName": "Google""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_continues_and_captures_cross_package_child_when_user_selects_continue() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-continue").toFile()
        val externalPackageName = "com.google.android.googlequicksearchbox"
        try {
            val host = object : FakeHost(
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
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                val externalContexts = mutableListOf<ExternalPackageDecisionContext>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    externalPackageContext?.let { externalContexts += it }
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertEquals(externalPackageName, host.externalContexts.single().nextPackageName)
            assertTrue(manifestJson.contains(""""screenName": "Google""""))
            assertTrue(manifestJson.contains(""""packageName": "$externalPackageName""""))
            assertTrue(manifestJson.contains(""""expectedPackageName": "$externalPackageName""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_waitsForExpectedEntryAfterContinueBeforeReclicking() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-google-entry-settle").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                private var returnedFromPause = false
                private var entryRestoreCapturesAfterPause = 0

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun relaunchTargetApp(selectedApp: SelectedAppRef): String? {
                    entryRestoreCapturesAfterPause = 0
                    return super.relaunchTargetApp(selectedApp)
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (
                        returnedFromPause &&
                        currentPackageName() == "com.example.target" &&
                        expectedPackageName == "com.example.target"
                    ) {
                        entryRestoreCapturesAfterPause += 1
                        if (entryRestoreCapturesAfterPause == 1) {
                            captureExpectedPackages += expectedPackageName
                            return fakeRoot(
                                screenId = "A",
                                packageName = "com.example.target",
                                elements = emptyList(),
                            )
                        }
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""status": "failed""""))
            assertTrue(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(crawlLogText.contains("entry_restore_relaunch_attempt attempt=1/"))
            assertTrue(crawlLogText.contains("outcome=expected_logical_not_found"))
            assertTrue(crawlLogText.contains("entry_restore_relaunch_attempt attempt=2/"))
            assertTrue(crawlLogText.contains("outcome=matched_expected_logical"))
            assertTrue(crawlLogText.contains("external_boundary_restore_result"))
            assertFalse(crawlLogText.contains("result=top_fingerprint_mismatch"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_failsWhenExpectedEntryNeverSettlesAfterContinue() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-google-entry-never-settles").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                private var returnedFromPause = false

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (
                        returnedFromPause &&
                        currentPackageName() == "com.example.target" &&
                        expectedPackageName == "com.example.target"
                    ) {
                        captureExpectedPackages += expectedPackageName
                        return fakeRoot(
                            screenId = "A",
                            packageName = "com.example.target",
                            elements = emptyList(),
                        )
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(1, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""status": "failed""""))
            assertFalse(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(crawlLogText.contains("outcome=expected_logical_not_found"))
            assertTrue(crawlLogText.contains("matchedExpectedLogical=false"))
            assertTrue(crawlLogText.contains("The expected entry screen did not settle after relaunch."))
            assertFalse(
                crawlLogText.lineSequence()
                    .filter { it.contains("entry_restore_relaunch_attempt") }
                    .any { it.contains("matchedExpectedLogical=true") }
            )
            assertFalse(crawlLogText.contains("external_boundary_restore_result"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun initialCrawl_relaunchSamplesUntilEntryRootBecomesVisible() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-initial-relaunch-settle").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                private var nullCapturesAfterRelaunchRemaining = 1

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (
                        relaunchCount > 0 &&
                        expectedPackageName == "com.example.target" &&
                        nullCapturesAfterRelaunchRemaining > 0
                    ) {
                        nullCapturesAfterRelaunchRemaining -= 1
                        captureExpectedPackages += expectedPackageName
                        return null
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(1, host.relaunchCount)
            assertEquals(1, summary.capturedScreenCount)
            assertTrue(crawlLogText.contains("entry_restore_relaunch_attempt attempt=1/"))
            assertTrue(crawlLogText.contains("outcome=capture_missing"))
            assertTrue(crawlLogText.contains("entry_restore_relaunch_attempt attempt=2/"))
            assertTrue(crawlLogText.contains("outcome=no_back_affordance_assumed_entry"))
            assertTrue(crawlLogText.contains("entry_restore_result strategy=relaunch success=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_acceptsCompatibleSparseExpectedAndRichRestoredDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-compatible").toFile()
        val externalPackageName = "com.google.android.gms"
        val sparseRoot = fakeRoot(
            screenId = "G",
            packageName = externalPackageName,
            elements = listOf(
                fakeElement("More options", 0, className = "android.view.View"),
            ),
        )
        val richRoot = fakeRoot(
            screenId = "G",
            packageName = externalPackageName,
            elements = listOf(
                fakeElement("All services", 0),
                fakeElement("Give feedback", 1, className = "android.widget.TextView"),
                fakeElement("More options", 2, className = "android.view.View"),
                fakeElement("Sign in", 3, className = "android.view.View"),
            ),
        )
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                private var returnedFromPause = false

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (
                        currentPackageName() == externalPackageName &&
                        (expectedPackageName == null || expectedPackageName == externalPackageName)
                    ) {
                        captureExpectedPackages += expectedPackageName
                        return if (returnedFromPause) richRoot else sparseRoot
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""status": "failed""""))
            assertTrue(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(crawlLogText.contains("destinationFingerprintMatched=false"))
            assertTrue(crawlLogText.contains("destinationCompatible=true"))
            assertTrue(crawlLogText.contains("compatibilityReason=actual_enriches_expected_identities"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_failsCompatibleRestoreForUnrelatedSamePackageDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-incompatible").toFile()
        val externalPackageName = "com.google.android.gms"
        val expectedRoot = fakeRoot(
            screenId = "G",
            packageName = externalPackageName,
            elements = listOf(
                fakeElement("Account", 0),
                fakeElement("Privacy", 1),
            ),
        )
        val unrelatedRoot = fakeRoot(
            screenId = "G",
            packageName = externalPackageName,
            elements = listOf(
                fakeElement("Cart", 0),
                fakeElement("Checkout", 1),
            ),
        )
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                private var returnedFromPause = false

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (
                        currentPackageName() == externalPackageName &&
                        (expectedPackageName == null || expectedPackageName == externalPackageName)
                    ) {
                        captureExpectedPackages += expectedPackageName
                        return if (returnedFromPause) unrelatedRoot else expectedRoot
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(1, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""status": "failed""""))
            assertFalse(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(crawlLogText.contains("destinationCompatible=false"))
            assertTrue(crawlLogText.contains("compatibilityReason=unrelated_destination_identities"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_settlesGoogleLikeExternalSparseToRichDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-google-enrichment").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                        captureVariants = listOf(
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("More options", 0, className = "android.view.View"),
                                ),
                            ),
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("All services", 0),
                                    fakeElement("Give feedback", 1, className = "android.widget.TextView"),
                                    fakeElement("More options", 2, className = "android.view.View"),
                                    fakeElement("Sign in", 3, className = "android.view.View"),
                                ),
                                extraVisibleText = listOf(
                                    "Google services",
                                    "Manage your Google settings",
                                ),
                            ),
                        ),
                    ),
                ),
                screensWithoutBackAffordance = setOf("G"),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()
            val googleXml = tempDir.walkTopDown()
                .firstOrNull { it.isFile && it.extension == "xml" && it.readText().contains(externalPackageName) }

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""status": "failed""""))
            assertTrue(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(googleXml != null)
            assertTrue(crawlLogText.contains("observedFingerprint=\"android.widget.FrameLayout::More options"))
            assertTrue(crawlLogText.contains("selectedFingerprint=\"android.widget.FrameLayout::All services"))
            assertTrue(crawlLogText.contains("selectionReason=best_richness"))
            assertTrue(crawlLogText.contains("becameCurrentBest=true"))
            assertTrue(crawlLogText.contains("visibleTextOrContentDescriptionCount=6"))
            assertTrue(crawlLogText.contains("external_boundary_restore_result"))
            assertTrue(crawlLogText.contains("destinationCompatible=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_settlesDigitalWellbeingLikeEmptyToRichDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-wellbeing-enrichment").toFile()
        val externalPackageName = "com.google.android.apps.wellbeing"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Digital Wellbeing", 0)),
                        transitions = mapOf("Open Digital Wellbeing" to "W"),
                    ),
                    "W" to fakeScreen(
                        id = "W",
                        screenName = "Digital Wellbeing",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                        captureVariants = listOf(
                            fakeScreenVariant(elements = emptyList()),
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("App timers", 0),
                                    fakeElement("Bedtime mode", 1),
                                    fakeElement("View app activity details", 2),
                                ),
                                extraVisibleText = listOf("TODAY"),
                            ),
                        ),
                    ),
                ),
                screensWithoutBackAffordance = setOf("W"),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""status": "failed""""))
            assertTrue(manifestJson.contains(""""screenName": "Digital Wellbeing""""))
            assertTrue(crawlLogText.contains("observedFingerprint=\"android.widget.FrameLayout::\""))
            assertTrue(crawlLogText.contains("selectedFingerprint=\"android.widget.FrameLayout::App timers"))
            assertTrue(crawlLogText.contains("selectionReason=best_richness"))
            assertTrue(crawlLogText.contains("visibleTextOrContentDescriptionCount=4"))
            assertTrue(crawlLogText.contains("external_boundary_restore_result"))
            assertTrue(crawlLogText.contains("destinationCompatible=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_waits_for_delayed_external_package_before_no_navigation_skip() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-delayed-external-continue").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                delayedTransitions = mapOf("Open Google" to DelayedTransition(capturesBeforeTransition = 1)),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertEquals(0, summary.skippedElementCount)
            assertFalse(manifestJson.contains(""""status": "skipped_no_navigation""""))
            assertTrue(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(manifestJson.contains(""""packageName": "$externalPackageName""""))
            assertTrue(crawlLogText.contains("child_destination_observe_attempt"))
            assertTrue(crawlLogText.contains("result=unchanged_retry"))
            assertTrue(crawlLogText.contains("result=changed"))
            assertTrue(crawlLogText.contains("external_package_accepted"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_replays_through_recorded_package_context() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-replay").toFile()
        val externalPackageName = "com.google.android.googlequicksearchbox"
        try {
            val host = object : FakeHost(
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
                        packageName = externalPackageName,
                        elements = listOf(fakeElement("Open Results", 0)),
                        transitions = mapOf("Open Results" to "H"),
                    ),
                    "H" to fakeScreen(
                        id = "H",
                        screenName = "Results",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertTrue(host.captureExpectedPackages.any { it == externalPackageName })
            assertEquals(3, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Results""""))
            assertTrue(manifestJson.contains(""""expectedPackageName": "$externalPackageName""""))
            assertTrue(manifestJson.contains(""""packageName": "$externalPackageName""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun routeReplay_settlesIntermediateExternalStepBeforeContinuingToDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-route-replay-enrichment").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google",
                        packageName = externalPackageName,
                        elements = listOf(fakeElement("Open Results", 0)),
                        transitions = mapOf("Open Results" to "H"),
                        captureVariants = listOf(
                            fakeScreenVariant(
                                elements = listOf(fakeElement("More options", 0)),
                            ),
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("Open Results", 0),
                                    fakeElement("More options", 1),
                                ),
                                extraVisibleText = listOf("All services"),
                            ),
                        ),
                    ),
                    "H" to fakeScreen(
                        id = "H",
                        screenName = "Results",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                resetCaptureCountsOnRelaunch = true,
            ) {
                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision = PauseDecision.CONTINUE
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(3, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Results""""))
            assertTrue(crawlLogText.contains("replay_route_step_settle_result"))
            assertTrue(crawlLogText.contains("knownDestinationFingerprint=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("selectedFingerprint=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("Open Results"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun routeReplay_exitsEarlyWhenFirstEligibleSampleMatchesSavedDestinationFingerprint() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-route-replay-early").toFile()
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
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(2, summary.capturedScreenCount)
            assertTrue(crawlLogText.contains("replay_route_step_settle_result"))
            assertTrue(crawlLogText.contains("sampleCount=1"))
            assertTrue(crawlLogText.contains("stopReason=known_destination_fingerprint_matched"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-discovery-fixed-dwell").toFile()
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
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertTrue(crawlLogText.contains("child_destination_settle_result"))
            assertTrue(crawlLogText.contains("triggerLabel=\"Open B\""))
            assertTrue(crawlLogText.contains("sampleCount=10"))
            assertTrue(crawlLogText.contains("stopReason=fixed_dwell_exhausted"))
            assertTrue(crawlLogText.contains("sameFingerprintAsPrevious=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_restores_external_foreground_before_real_scan_after_continue() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-foreground").toFile()
        val externalPackageName = "com.google.android.googlequicksearchbox"
        val appPackageName = "com.example.apptohtml"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                        scrollable = true,
                    ),
                    "APP" to fakeScreen(
                        id = "APP",
                        screenName = "AppToHTML",
                        packageName = appPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                val packagesSeenForExternalCapturesAfterPause = mutableListOf<String>()
                val packagesSeenForScrollAfterPause = mutableListOf<String>()
                private var returnedFromPause = false

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    foregroundScreen("APP")
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (returnedFromPause && expectedPackageName == externalPackageName) {
                        packagesSeenForExternalCapturesAfterPause += currentPackageName()
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }

                override fun scrollForward(childIndexPath: List<Int>): Boolean {
                    if (returnedFromPause) {
                        packagesSeenForScrollAfterPause += currentPackageName()
                    }
                    return false
                }
            }

            val outcome = coordinator(host, tempDir, useRealScan = true).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val childXml = tempDir.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .map { it.readText() }
                .firstOrNull { it.contains(externalPackageName) }

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertTrue(host.packagesSeenForExternalCapturesAfterPause.isNotEmpty())
            assertTrue(host.packagesSeenForExternalCapturesAfterPause.all { it == externalPackageName })
            assertTrue(host.packagesSeenForScrollAfterPause.isNotEmpty())
            assertTrue(host.packagesSeenForScrollAfterPause.all { it == externalPackageName })
            assertTrue(manifestJson.contains(""""packageName": "$externalPackageName""""))
            assertFalse(manifestJson.contains(""""packageName": "$appPackageName""""))
            assertTrue(childXml != null)
            assertFalse(childXml!!.contains(appPackageName))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_retries_expected_package_capture_after_continue_restore() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-restore-retry").toFile()
        val externalPackageName = "com.google.android.gms"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
                delayedTransitions = mapOf("Open Google" to DelayedTransition(capturesBeforeTransition = 1)),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val childXml = tempDir.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .map { it.readText() }
                .firstOrNull { it.contains(externalPackageName) }

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(2, summary.capturedScreenCount)
            assertEquals(0, summary.skippedElementCount)
            assertTrue(host.captureExpectedPackages.count { it == externalPackageName } >= 2)
            assertTrue(manifestJson.contains(""""screenName": "Google Services""""))
            assertTrue(manifestJson.contains(""""packageName": "$externalPackageName""""))
            assertTrue(manifestJson.contains(""""expectedPackageName": "$externalPackageName""""))
            assertTrue(childXml != null)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_fails_restore_when_expected_package_never_appears() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-restore-missing").toFile()
        val externalPackageName = "com.google.android.gms"
        val appPackageName = "com.example.apptohtml"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Google", 0)),
                        transitions = mapOf("Open Google" to "G"),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "APP" to fakeScreen(
                        id = "APP",
                        screenName = "AppToHTML",
                        packageName = appPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                private var returnedFromPause = false

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    foregroundScreen("APP")
                    returnedFromPause = true
                    return PauseDecision.CONTINUE
                }

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? {
                    if (returnedFromPause && expectedPackageName == externalPackageName) {
                        captureExpectedPackages += expectedPackageName
                        return null
                    }
                    return super.captureCurrentRootSnapshot(expectedPackageName)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val childXmls = tempDir.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .map { it.readText() }
                .toList()

            assertEquals(listOf(PauseReason.EXTERNAL_PACKAGE_BOUNDARY), host.pauseReasons)
            assertEquals(1, summary.capturedScreenCount)
            assertEquals(0, summary.skippedElementCount)
            assertTrue(host.captureExpectedPackages.count { it == externalPackageName } >= 2)
            assertTrue(manifestJson.contains(""""status": "failed""""))
            assertFalse(manifestJson.contains(""""status": "skipped_no_navigation""""))
            assertFalse(manifestJson.contains(""""screenName": "Google Services""""))
            assertFalse(childXmls.any { it.contains(appPackageName) })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun samePackageClick_withNoEligibleChangedSample_isSkippedAsNoNavigation() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-no-navigation").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Refresh", 0)),
                        transitions = mapOf("Refresh" to "A"),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(1, summary.capturedScreenCount)
            assertEquals(1, summary.skippedElementCount)
            assertTrue(manifestJson.contains(""""status": "skipped_no_navigation""""))
            assertTrue(crawlLogText.contains("edge_skipped_no_navigation"))
            assertTrue(crawlLogText.contains("stopReason=no_eligible_sample"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun samePackageClick_settlesSparseChangedRootToRicherChildBeforeScanning() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-same-package-enrichment").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Details", 0)),
                        transitions = mapOf("Open Details" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Details",
                        elements = emptyList(),
                        transitions = emptyMap(),
                        captureVariants = listOf(
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("Summary", 0),
                                ),
                            ),
                            fakeScreenVariant(
                                elements = listOf(
                                    fakeElement("Summary", 0),
                                    fakeElement("Detailed option", 1),
                                ),
                                extraVisibleText = listOf("Loaded details"),
                            ),
                        ),
                    ),
                ),
            )

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                blacklist = CrawlBlacklist(
                    labelTokens = setOf("summary", "detailed option", "navigate up"),
                    skipCheckable = false,
                ),
                useRealScan = true,
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()
            val richChildXml = tempDir.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .map { it.readText() }
                .firstOrNull { it.contains("Detailed option") && it.contains("Loaded details") }

            assertEquals(2, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""status": "failed""""))
            assertTrue(crawlLogText.contains("observedFingerprint=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("Summary"))
            assertTrue(crawlLogText.contains("selectedFingerprint=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("Detailed option"))
            assertTrue(crawlLogText.contains("selectionReason=best_richness"))
            assertTrue(crawlLogText.contains("visibleTextOrContentDescriptionCount=4"))
            assertTrue(richChildXml != null)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageDecision_allows_previously_accepted_packages_and_pauses_for_new_package() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-allowed-packages").toFile()
        val googlePackageName = "com.google.android.googlequicksearchbox"
        val chromePackageName = "com.android.chrome"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(
                            fakeElement("Open Google", 0),
                            fakeElement("Open Google Again", 1),
                        ),
                        transitions = mapOf(
                            "Open Google" to "G",
                            "Open Google Again" to "G",
                        ),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google Services",
                        packageName = googlePackageName,
                        elements = listOf(
                            fakeElement("Open Details", 0),
                            fakeElement("Back To Settings", 1),
                            fakeElement("Open Chrome", 2),
                        ),
                        transitions = mapOf(
                            "Open Details" to "H",
                            "Back To Settings" to "A",
                            "Open Chrome" to "C",
                        ),
                    ),
                    "H" to fakeScreen(
                        id = "H",
                        screenName = "Google Details",
                        packageName = googlePackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "C" to fakeScreen(
                        id = "C",
                        screenName = "Chrome",
                        packageName = chromePackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                val externalContexts = mutableListOf<ExternalPackageDecisionContext>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    externalPackageContext?.let { externalContexts += it }
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(
                listOf(
                    PauseReason.EXTERNAL_PACKAGE_BOUNDARY,
                    PauseReason.EXTERNAL_PACKAGE_BOUNDARY,
                ),
                host.pauseReasons,
            )
            assertEquals(
                listOf(googlePackageName, chromePackageName),
                host.externalContexts.map { it.nextPackageName },
            )
            assertEquals(4, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Google Details""""))
            assertTrue(manifestJson.contains(""""screenName": "Settings""""))
            assertTrue(manifestJson.contains(""""screenName": "Chrome""""))
            assertTrue(manifestJson.contains(""""packageName": "$googlePackageName""""))
            assertTrue(manifestJson.contains(""""packageName": "$chromePackageName""""))
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

    @Test
    fun pauseCheckpoint_fires_on_elapsed_time_and_continues_after_user_approval() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-pause-time").toFile()
        var timeCallCount = 0
        try {
            val host = object : FakeHost(
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
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()
                val pauseSnapshots = mutableListOf<PauseProgressSnapshot>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    pauseSnapshots += snapshot
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                pauseConfig = PauseCheckpointConfig(
                    initialTimeThresholdMs = 1_000L,
                    subsequentTimeThresholdMs = 5_000L,
                    initialFailedEdgeThreshold = 99,
                    subsequentFailedEdgeThreshold = 99,
                ),
                timeProvider = {
                    if (timeCallCount++ == 0) 0L else 1_000L
                },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary

            assertEquals(listOf(PauseReason.ELAPSED_TIME_EXCEEDED), host.pauseReasons)
            assertEquals(1, host.pauseSnapshots.single().capturedScreenCount)
            assertEquals(0, host.pauseSnapshots.single().capturedChildScreenCount)
            assertEquals(0, host.pauseSnapshots.single().failedEdgeCount)
            assertEquals(2, summary.capturedScreenCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pauseCheckpoint_fires_on_failed_edge_count_and_continues_after_user_approval() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-pause-failed-edge").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Broken", 0),
                            fakeElement("Open B", 1),
                        ),
                        transitions = mapOf("Open B" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val events = mutableListOf<String>()
                val pauseReasons = mutableListOf<PauseReason>()
                val pauseSnapshots = mutableListOf<PauseProgressSnapshot>()

                override fun click(element: PressableElement): Boolean {
                    events += "click:${element.label}"
                    if (element.label == "Broken") {
                        return false
                    }
                    return super.click(element)
                }

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    events += "pause:${reason.name}"
                    pauseReasons += reason
                    pauseSnapshots += snapshot
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                pauseConfig = PauseCheckpointConfig(
                    initialTimeThresholdMs = 60_000L,
                    subsequentTimeThresholdMs = 60_000L,
                    initialFailedEdgeThreshold = 1,
                    subsequentFailedEdgeThreshold = 10,
                ),
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            assertEquals(listOf(PauseReason.FAILED_EDGE_COUNT_EXCEEDED), host.pauseReasons)
            assertEquals(1, host.pauseSnapshots.single().failedEdgeCount)
            assertTrue(host.events.indexOf("click:Broken") < host.events.indexOf("pause:FAILED_EDGE_COUNT_EXCEEDED"))
            assertTrue(host.events.indexOf("pause:FAILED_EDGE_COUNT_EXCEEDED") < host.events.indexOf("click:Open B"))
            assertEquals(2, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""label": "Broken""""))
            assertTrue(manifestJson.contains(""""status": "failed""""))
            assertTrue(manifestJson.contains(""""screenName": "Screen B""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pauseCheckpoint_rolls_forward_only_the_triggered_budget() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-pause-roll-forward").toFile()
        var currentTimeMs = 1_000L
        var timeCallCount = 0
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Broken", 0),
                            fakeElement("Open B", 1),
                        ),
                        transitions = mapOf("Open B" to "B"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val events = mutableListOf<String>()
                val pauseReasons = mutableListOf<PauseReason>()

                override fun click(element: PressableElement): Boolean {
                    events += "click:${element.label}"
                    if (element.label == "Broken") {
                        return false
                    }
                    return super.click(element)
                }

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    events += "pause:${reason.name}"
                    pauseReasons += reason
                    if (reason == PauseReason.ELAPSED_TIME_EXCEEDED) {
                        currentTimeMs = 1_499L
                    }
                    return PauseDecision.CONTINUE
                }
            }

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                pauseConfig = PauseCheckpointConfig(
                    initialTimeThresholdMs = 1_000L,
                    subsequentTimeThresholdMs = 500L,
                    initialFailedEdgeThreshold = 1,
                    subsequentFailedEdgeThreshold = 10,
                ),
                timeProvider = {
                    if (timeCallCount++ == 0) 0L else currentTimeMs
                },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary

            assertEquals(
                listOf(
                    PauseReason.ELAPSED_TIME_EXCEEDED,
                    PauseReason.FAILED_EDGE_COUNT_EXCEEDED,
                ),
                host.pauseReasons,
            )
            assertTrue(host.events.indexOf("pause:ELAPSED_TIME_EXCEEDED") < host.events.indexOf("click:Broken"))
            assertTrue(host.events.indexOf("click:Broken") < host.events.indexOf("pause:FAILED_EDGE_COUNT_EXCEEDED"))
            assertTrue(host.events.indexOf("pause:FAILED_EDGE_COUNT_EXCEEDED") < host.events.indexOf("click:Open B"))
            assertEquals(2, summary.capturedScreenCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pauseCheckpoint_stops_and_saves_partial_when_user_chooses_stop() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-pause-stop").toFile()
        var timeCallCount = 0
        try {
            val host = object : FakeHost(
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
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                    externalPackageContext: ExternalPackageDecisionContext?,
                ): PauseDecision {
                    pauseReasons += reason
                    return PauseDecision.STOP
                }
            }

            val outcome = coordinator(
                host = host,
                tempDir = tempDir,
                pauseConfig = PauseCheckpointConfig(
                    initialTimeThresholdMs = 1_000L,
                    subsequentTimeThresholdMs = 5_000L,
                    initialFailedEdgeThreshold = 99,
                    subsequentFailedEdgeThreshold = 99,
                ),
                timeProvider = {
                    if (timeCallCount++ == 0) 0L else 1_000L
                },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val partial = outcome as DeepCrawlCoordinator.DeepCrawlOutcome.PartialAbort
            val manifestJson = partial.summary.manifestFile.readText()

            assertEquals(listOf(PauseReason.ELAPSED_TIME_EXCEEDED), host.pauseReasons)
            assertEquals(1, partial.summary.capturedScreenCount)
            assertTrue(partial.message.contains("elapsed-time checkpoint"))
            assertTrue(manifestJson.contains(""""status": "partial_abort""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun routeReplay_failsAtFirstStepWhenStaleNavigationReachesUnrelatedScreen() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-replay-wrong-screen").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Network and internet", 0)),
                        transitions = mapOf("Network and internet" to "N"),
                    ),
                    "N" to fakeScreen(
                        id = "N",
                        screenName = "Network and internet",
                        elements = listOf(fakeElement("T-Mobile", 0)),
                        transitions = mapOf("T-Mobile" to "T"),
                    ),
                    "T" to fakeScreen(
                        id = "T",
                        screenName = "T-Mobile",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                var networkClickCount = 0

                override fun click(element: PressableElement): Boolean {
                    if (element.label == "Network and internet") {
                        networkClickCount += 1
                        if (networkClickCount >= 2) {
                            foregroundScreen("T")
                            return true
                        }
                    }
                    return super.click(element)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = outcome.summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()
            val manifestJson = summary.manifestFile.readText()

            assertTrue(
                "expected replay_route_step_validation entry but log was:\n$crawlLogText",
                crawlLogText.contains("replay_route_step_validation"),
            )
            assertTrue(
                "expected matchedExpectedReplay=false in:\n$crawlLogText",
                crawlLogText.contains("matchedExpectedReplay=false"),
            )
            assertTrue(
                "expected route replay failure to be reported at step 0 in:\n$crawlLogText",
                crawlLogText.contains("Route replay step 0 for 'Network and internet' reached an unexpected screen"),
            )
            assertTrue(
                "expected failed edge in manifest:\n$manifestJson",
                manifestJson.contains(""""status": "failed""""),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun routeReplay_validatesIntermediateStepBeforeFinalDestination() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-replay-intermediate").toFile()
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Network", 0)),
                        transitions = mapOf("Open Network" to "N"),
                    ),
                    "N" to fakeScreen(
                        id = "N",
                        screenName = "Network",
                        elements = listOf(fakeElement("Open SIMs", 0)),
                        transitions = mapOf("Open SIMs" to "S"),
                    ),
                    "S" to fakeScreen(
                        id = "S",
                        screenName = "SIMs",
                        elements = listOf(fakeElement("Open T-Mobile", 0)),
                        transitions = mapOf("Open T-Mobile" to "T"),
                    ),
                    "T" to fakeScreen(
                        id = "T",
                        screenName = "T-Mobile",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "X" to fakeScreen(
                        id = "X",
                        screenName = "Unrelated",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                var openNetworkClickCount = 0

                override fun click(element: PressableElement): Boolean {
                    if (element.label == "Open Network") {
                        openNetworkClickCount += 1
                        if (openNetworkClickCount >= 3) {
                            foregroundScreen("X")
                            return true
                        }
                    }
                    return super.click(element)
                }
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "Settings",
            )

            val summary = outcome.summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertTrue(
                "expected replay_route_step_validation for stepIndex=0 in:\n$crawlLogText",
                crawlLogText.contains("replay_route_step_validation") &&
                    crawlLogText.contains("matchedExpectedReplay=false"),
            )
            assertTrue(
                "intermediate parent (stepIndex=0) should fail before reaching final destination in:\n$crawlLogText",
                crawlLogText.contains("Route replay step 0 for 'Open Network' reached an unexpected screen"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun crawlManifest_persistsReplayFingerprintAndExpectedReplayMetadata() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-manifest-replay-fields").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Settings",
                        elements = listOf(fakeElement("Open Network", 0)),
                        transitions = mapOf("Open Network" to "N"),
                    ),
                    "N" to fakeScreen(
                        id = "N",
                        screenName = "Network",
                        elements = emptyList(),
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

            assertTrue(
                "expected replayFingerprint field in manifest:\n$manifestJson",
                manifestJson.contains(""""replayFingerprint":"""),
            )
            assertTrue(
                "expected expectedReplayFingerprint route field in manifest:\n$manifestJson",
                manifestJson.contains(""""expectedReplayFingerprint":"""),
            )
            assertTrue(
                "expected expectedReplayScreenName field in manifest:\n$manifestJson",
                manifestJson.contains(""""expectedReplayScreenName": "Network""""),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun coordinator(
        host: FakeHost,
        tempDir: File,
        blacklist: CrawlBlacklist = CrawlBlacklist(skipCheckable = false),
        pauseConfig: PauseCheckpointConfig = PauseCheckpointConfig(),
        timeProvider: () -> Long = { System.currentTimeMillis() },
        useRealScan: Boolean = false,
    ): DeepCrawlCoordinator {
        var postClickSettleTimeMs = 0L
        var entryRestoreSettleTimeMs = 0L
        return DeepCrawlCoordinator(
            selectedApp = selectedApp(),
            host = host,
            loadBlacklist = { blacklist },
            createSession = {
                val sessionDir = File(tempDir, "session-$it").apply { mkdirs() }
                CrawlSessionDirectory(
                    sessionId = sessionDir.name,
                    directory = sessionDir,
                    manifestFile = File(sessionDir, "crawl-index.json"),
                    logFile = File(sessionDir, "crawl.log"),
                    graphJsonFile = File(sessionDir, "crawl-graph.json"),
                    graphHtmlFile = File(sessionDir, "crawl-graph.html"),
                )
            },
            pauseConfig = pauseConfig,
            scanScreenOverride = if (useRealScan) null else { _, initialRoot, _, _ ->
                host.snapshotForRoot(initialRoot)
            },
            timeProvider = timeProvider,
            postClickSettleTimeProvider = {
                postClickSettleTimeMs += 1_000L
                postClickSettleTimeMs
            },
            entryRestoreSettleTimeProvider = {
                entryRestoreSettleTimeMs += 350L
                entryRestoreSettleTimeMs
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
        scrollable: Boolean = false,
        captureVariants: List<FakeScreenVariant> = emptyList(),
    ): FakeScreen {
        return FakeScreen(
            id = id,
            screenName = screenName,
            packageName = packageName,
            elements = elements,
            transitions = transitions,
            scrollable = scrollable,
            captureVariants = captureVariants,
        )
    }

    private fun fakeScreenVariant(
        elements: List<PressableElement>,
        extraVisibleText: List<String> = emptyList(),
        className: String = "android.widget.FrameLayout",
        packageNameOverride: String? = null,
        scrollable: Boolean? = null,
    ): FakeScreenVariant {
        return FakeScreenVariant(
            elements = elements,
            extraVisibleText = extraVisibleText,
            className = className,
            packageNameOverride = packageNameOverride,
            scrollable = scrollable,
        )
    }

    private fun fakeElement(
        label: String,
        index: Int,
        editable: Boolean = false,
        className: String = if (editable) "android.widget.EditText" else "android.widget.Button",
    ): PressableElement {
        return PressableElement(
            label = label,
            resourceId = "com.example.target:id/${label.lowercase().replace(' ', '_')}",
            bounds = "[0,${index * 100}][200,${index * 100 + 80}]",
            className = className,
            isListItem = false,
            childIndexPath = listOf(index),
            editable = editable,
            firstSeenStep = 0,
        )
    }

    private fun fakeRoot(
        screenId: String,
        packageName: String,
        elements: List<PressableElement>,
        className: String = "android.widget.FrameLayout",
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = "com.example.target:id/$screenId",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][1080,2400]",
            children = elements.mapIndexed { index, element ->
                AccessibilityNodeSnapshot(
                    className = element.className,
                    packageName = packageName,
                    viewIdResourceName = element.resourceId,
                    text = element.label,
                    contentDescription = null,
                    clickable = true,
                    supportsClickAction = true,
                    scrollable = false,
                    editable = element.editable,
                    enabled = true,
                    visibleToUser = true,
                    bounds = element.bounds,
                    children = emptyList(),
                    childIndexPath = listOf(index),
                )
            },
        )
    }

    private data class FakeScreen(
        val id: String,
        val screenName: String,
        val packageName: String,
        val elements: List<PressableElement>,
        val transitions: Map<String, String>,
        val scrollable: Boolean = false,
        val captureVariants: List<FakeScreenVariant> = emptyList(),
    )

    private data class FakeScreenVariant(
        val elements: List<PressableElement>,
        val extraVisibleText: List<String> = emptyList(),
        val className: String = "android.widget.FrameLayout",
        val packageNameOverride: String? = null,
        val scrollable: Boolean? = null,
    )

    private data class DelayedTransition(
        val capturesBeforeTransition: Int,
    )

    private data class PendingDelayedTransition(
        val destinationScreenId: String,
        val capturesBeforeTransition: Int,
    )

    private open class FakeHost(
        private val entryScreenId: String,
        initialScreenId: String = entryScreenId,
        private val screens: Map<String, FakeScreen>,
        private val showBackAffordanceOnEntryRoot: Boolean = false,
        private val screensWithoutBackAffordance: Set<String> = emptySet(),
        private val shiftedBoundsOnReplayScreens: Set<String> = emptySet(),
        private val delayedTransitions: Map<String, DelayedTransition> = emptyMap(),
        private val resetCaptureCountsOnRelaunch: Boolean = false,
    ) : DeepCrawlCoordinator.Host {
        private var currentScreenId = initialScreenId
        private val backStack = mutableListOf(initialScreenId)
        private val captureCountsByScreenId = mutableMapOf<String, Int>()
        private var pendingDelayedTransition: PendingDelayedTransition? = null
        private var returnedToEntryScreen = false
        val captureExpectedPackages = mutableListOf<String?>()
        var relaunchCount = 0
            private set

        open override suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot? {
            captureExpectedPackages += expectedPackageName
            val captureScreenId = currentScreenId
            val currentPackageName = screens.getValue(captureScreenId).packageName
            val pendingTransition = pendingDelayedTransition
            if (expectedPackageName != null && expectedPackageName != currentPackageName) {
                advancePendingDelayedTransitionAfterCapture(pendingTransition)
                return null
            }
            val captureCount = captureCountsByScreenId.getOrDefault(captureScreenId, 0)
            captureCountsByScreenId[captureScreenId] = captureCount + 1
            val root = rootFor(
                screenId = captureScreenId,
                shiftedBounds = captureScreenId in shiftedBoundsOnReplayScreens && captureCount >= 2,
                captureCount = captureCount,
            )
            advancePendingDelayedTransitionAfterCapture(pendingTransition)
            return root
        }

        open override fun scrollForward(childIndexPath: List<Int>): Boolean = false

        open override fun scrollBackward(childIndexPath: List<Int>): Boolean = false

        override fun click(element: PressableElement): Boolean {
            val current = screens.getValue(currentScreenId)
            val destination = current.transitions[element.label] ?: return false
            if (destination != currentScreenId) {
                val delayedTransition = delayedTransitions[element.label]
                if (delayedTransition != null) {
                    pendingDelayedTransition = PendingDelayedTransition(
                        destinationScreenId = destination,
                        capturesBeforeTransition = delayedTransition.capturesBeforeTransition.coerceAtLeast(0),
                    )
                    if (pendingDelayedTransition?.capturesBeforeTransition == 0) {
                        completePendingDelayedTransition()
                    }
                    return true
                }
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
            pendingDelayedTransition = null
            if (resetCaptureCountsOnRelaunch) {
                captureCountsByScreenId.clear()
            }
            return null
        }

        override suspend fun awaitPauseDecision(
            reason: PauseReason,
            snapshot: PauseProgressSnapshot,
            externalPackageContext: ExternalPackageDecisionContext?,
        ): PauseDecision = PauseDecision.CONTINUE

        override fun publishProgress(message: String) = Unit

        override fun setActiveCrawlLogger(logger: CrawlLogger?) = Unit

        protected fun foregroundScreen(screenId: String) {
            currentScreenId = screenId
            backStack.clear()
            backStack += screenId
            pendingDelayedTransition = null
        }

        protected fun currentPackageName(): String {
            return screens.getValue(currentScreenId).packageName
        }

        fun captureCountFor(screenId: String): Int {
            return captureCountsByScreenId.getOrDefault(screenId, 0)
        }

        private fun advancePendingDelayedTransitionAfterCapture(
            pendingTransition: PendingDelayedTransition?,
        ) {
            if (pendingTransition == null || pendingDelayedTransition !== pendingTransition) {
                return
            }
            if (pendingTransition.capturesBeforeTransition <= 1) {
                completePendingDelayedTransition()
            } else {
                pendingDelayedTransition = pendingTransition.copy(
                    capturesBeforeTransition = pendingTransition.capturesBeforeTransition - 1,
                )
            }
        }

        private fun completePendingDelayedTransition() {
            val destination = pendingDelayedTransition?.destinationScreenId ?: return
            currentScreenId = destination
            backStack += destination
            pendingDelayedTransition = null
        }

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
            captureCount: Int = 0,
        ): AccessibilityNodeSnapshot {
            val screen = screens.getValue(screenId)
            val variant = screen.captureVariants.getOrNull(
                captureCount.coerceAtMost((screen.captureVariants.size - 1).coerceAtLeast(0))
            )
            val packageName = variant?.packageNameOverride ?: screen.packageName
            val className = variant?.className ?: "android.widget.FrameLayout"
            val elements = variant?.elements ?: screen.elements
            val scrollable = variant?.scrollable ?: screen.scrollable
            val rootBounds = shiftedBounds.shiftedBounds("[0,0][1080,2400]")
            return AccessibilityNodeSnapshot(
                className = className,
                packageName = packageName,
                viewIdResourceName = "com.example.target:id/$screenId",
                text = null,
                contentDescription = null,
                clickable = false,
                supportsClickAction = false,
                scrollable = scrollable,
                enabled = true,
                visibleToUser = true,
                bounds = rootBounds,
                children = buildList {
                    elements.mapIndexedTo(this) { index, element ->
                        AccessibilityNodeSnapshot(
                            className = element.className,
                            packageName = packageName,
                            viewIdResourceName = element.resourceId,
                            text = element.label,
                            contentDescription = null,
                            clickable = true,
                            supportsClickAction = true,
                            scrollable = false,
                            editable = element.editable,
                            enabled = true,
                            visibleToUser = true,
                            bounds = shiftedBounds.shiftedBounds(element.bounds),
                            children = emptyList(),
                            childIndexPath = listOf(index),
                        )
                    }
                    variant?.extraVisibleText.orEmpty().forEachIndexed { extraIndex, text ->
                        val childIndex = elements.size + extraIndex
                        add(
                            AccessibilityNodeSnapshot(
                                className = "android.widget.TextView",
                                packageName = packageName,
                                viewIdResourceName = "com.example.target:id/${screenId}_text_$extraIndex",
                                text = text,
                                contentDescription = null,
                                clickable = false,
                                supportsClickAction = false,
                                scrollable = false,
                                enabled = true,
                                visibleToUser = true,
                                bounds = shiftedBounds.shiftedBounds(
                                    "[0,${childIndex * 100}][600,${childIndex * 100 + 80}]"
                                ),
                                children = emptyList(),
                                childIndexPath = listOf(childIndex),
                            )
                        )
                    }
                    if (screenId == entryScreenId && showBackAffordanceOnEntryRoot && returnedToEntryScreen) {
                        add(
                            AccessibilityNodeSnapshot(
                                className = "android.widget.ImageButton",
                                packageName = packageName,
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
                                childIndexPath = listOf(elements.size + variant?.extraVisibleText.orEmpty().size),
                            )
                        )
                    }
                    if (screenId != entryScreenId && screenId !in screensWithoutBackAffordance) {
                        add(
                            AccessibilityNodeSnapshot(
                                className = "android.widget.ImageButton",
                                packageName = packageName,
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
                                childIndexPath = listOf(elements.size + variant?.extraVisibleText.orEmpty().size),
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
