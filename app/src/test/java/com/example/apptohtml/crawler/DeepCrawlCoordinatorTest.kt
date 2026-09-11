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
    fun resumeContinueAuto_processes_loaded_pending_edge_without_duplicate_edge() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-resume-pending").toFile()
        try {
            val sessionDir = File(tempDir, "session-1000").apply { mkdirs() }
            val openB = fakeElement("Open B", 0)
            writeSavedScreenXml(
                dir = sessionDir,
                screenId = "screen_00000",
                screenName = "Screen A",
                depth = 0,
                expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
                isRoot = true,
                elements = listOf(openB),
                edgesByElement = mapOf(
                    openB.toLinkKey() to EdgeXmlView(
                        edgeId = "edge_010",
                        status = CrawlEdgeStatus.PENDING,
                    )
                ),
            )
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(openB),
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
                timeProvider = { 1_000L },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
                intent = CrawlStartIntent.RESUME,
                resumeMode = ResumeMode.ContinueAuto,
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            assertEquals(2, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""edgeId": "edge_010""""))
            assertTrue(manifestJson.contains(""""status": "captured""""))
            assertFalse(manifestJson.contains(""""edgeId": "edge_011""""))
            assertTrue(File(sessionDir, "screen_00000_screen_a.xml").readText().contains("""expansion-status="complete""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun resumeReExpand_drops_prior_outbound_edges_and_expands_fresh() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-reexpand").toFile()
        try {
            val sessionDir = File(tempDir, "session-1000").apply { mkdirs() }
            val openB = fakeElement("Open B", 0)
            writeSavedScreenXml(
                dir = sessionDir,
                screenId = "screen_00000",
                screenName = "Screen A",
                depth = 0,
                expansionStatus = ScreenExpansionStatus.COMPLETE,
                isRoot = true,
                elements = listOf(openB),
                edgesByElement = mapOf(
                    openB.toLinkKey() to EdgeXmlView(
                        edgeId = "edge_000",
                        status = CrawlEdgeStatus.CAPTURED,
                        childScreenId = "screen_00001",
                        childScreenName = "Old Screen B",
                    )
                ),
            )
            writeSavedScreenXml(
                dir = sessionDir,
                screenId = "screen_00001",
                screenName = "Old Screen B",
                depth = 1,
                expansionStatus = ScreenExpansionStatus.COMPLETE,
                isRoot = false,
                elements = emptyList(),
                parent = ParentEdgeRef(
                    screenId = "screen_00000",
                    triggerLabel = "Open B",
                    triggerResourceId = openB.resourceId,
                ),
            )
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(openB),
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
                timeProvider = { 1_000L },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
                intent = CrawlStartIntent.RESUME,
                resumeMode = ResumeMode.ReExpand("screen_00000"),
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()
            assertEquals(3, summary.capturedScreenCount)
            assertFalse(manifestJson.contains(""""edgeId": "edge_000""""))
            assertTrue(manifestJson.contains(""""edgeId": "edge_001""""))
            assertFalse(File(sessionDir, "screen_00000_screen_a.xml").readText().contains("""id="edge_000""""))
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

    /**
     * SCOPE: "a test runs a crawl producing a root and a child and asserts both identities come
     * from the same builder — concretely, that the root's identity now contains the back-affordance
     * element."
     *
     * The root carries an element labelled "Up" from its very first capture. The identity's token
     * test flags it as a back affordance; `EntryScreenBackAffordanceDetector` does not treat it as a
     * pressable back button (a bare "up" needs toolbar context there), so the initial rewind leaves
     * it alone. Before this cycle the root's stored value came from the entry builder, which dropped
     * that element; one builder now keeps it, flagged, exactly as a child's always did.
     *
     * Round-4 N2: the previous version of this pin could not fail — the harness only added the
     * root's back button after the crawler returned, so the root's stored identity never held one.
     */
    @Test
    fun rootAndChild_identities_come_from_the_same_builder() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-one-builder").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Up", 0), fakeElement("Open B", 1)),
                        transitions = mapOf("Up" to "A", "Open B" to "B"),
                    ),
                    "B" to fakeScreen(id = "B", screenName = "Screen B", elements = emptyList(), transitions = emptyMap()),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val manifestJson = outcome.summary.manifestFile.readText()
            val replayValues = Regex(""""replayFingerprint": "([^"]*)"""")
                .findAll(manifestJson).map { it.groupValues[1] }.toList()

            assertEquals(2, replayValues.size)
            assertTrue(
                "the ROOT's stored identity keeps its flagged back affordance",
                replayValues.first().contains("com.example.target:id/up|up|android.widget.Button|false|false|false|true"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Pins the `nameKey` / `keyFor` split at the path that actually consumes it.
     *
     * A screen whose title is weak ("Continue") is deliberately NOT eligible to be catalogued, so
     * `DedupPolicy.keyFor` returns null for it. Replay validation asks a different question — *is
     * the screen I replayed to still called what it was called?* — and must use the ungated
     * `nameKey`. Gating it makes the stored side `""`, which the live side can never equal, and
     * every weak-titled screen fails replay and loses its whole subtree.
     */
    @Test
    fun weakTitledScreen_still_replays_and_expands() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-weak-title").toFile()
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
                    // "Continue" is in ScreenNaming's weak call-to-action set, so this screen is
                    // STRONG-ineligible for dedup while still being a perfectly replayable screen.
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Continue",
                        elements = listOf(fakeElement("Open C", 0)),
                        transitions = mapOf("Open C" to "C"),
                    ),
                    "C" to fakeScreen(id = "C", screenName = "Screen C", elements = emptyList(), transitions = emptyMap()),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = outcome.summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertFalse(
                "a weak-titled screen must not fail replay validation",
                crawlLogText.contains("Route replay diverged"),
            )
            assertEquals(3, summary.capturedScreenCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * S4 — route-step replay validation, pinned at the harness seam.
     *
     * A depth-1 child is captured with its back affordance and then loses it before the replay
     * capture. Pre-refactor S4 compared the child's whole element set with zero tolerance and the
     * back affordance always counted, so this diverges and the edge fails. The subject of an S4
     * comparison is the destination CHILD, which is never the crawl root — so this outcome must
     * not depend on whether the child's PARENT happens to be the root.
     */
    @Test
    fun routeStepValidation_fails_when_a_depth_one_child_loses_its_back_affordance() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-route-step-back").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(fakeElement("Open B", 0), fakeElement("Open C", 1)),
                        transitions = mapOf("Open B" to "B", "Open C" to "C"),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Screen B",
                        elements = listOf(fakeElement("Open D", 0)),
                        transitions = mapOf("Open D" to "D"),
                    ),
                    "C" to fakeScreen(id = "C", screenName = "Screen C", elements = emptyList(), transitions = emptyMap()),
                    "D" to fakeScreen(id = "D", screenName = "Screen D", elements = emptyList(), transitions = emptyMap()),
                ),
                backAffordanceDroppedOnReplayScreens = setOf("B"),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = outcome.summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertTrue(
                "expected route replay to diverge when the child's back affordance disappeared",
                crawlLogText.contains("matchedExpectedReplay=false"),
            )
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
    fun bfsTraversal_logs_compatible_entry_fingerprint_details() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-compatible-entry-log").toFile()
        val openB = fakeElement("Open B", 0)
        val openC = fakeElement("Open C", 1)
        val storage = fakeElement("Storage", 2)
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(openB, openC),
                        transitions = mapOf(
                            "Open B" to "B",
                            "Open C" to "C",
                        ),
                        captureVariants = listOf(
                            fakeScreenVariant(elements = listOf(openB, openC)),
                            fakeScreenVariant(elements = listOf(openB, openC)),
                            fakeScreenVariant(elements = listOf(openB, openC)),
                            fakeScreenVariant(elements = listOf(openB, openC, storage)),
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
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()
            val entryRestoreLogText = crawlLogText.lineSequence()
                .filter { it.contains("entry_restore") }
                .joinToString("\n")

            assertEquals(1, summary.capturedScreenCount)
            assertTrue(entryRestoreLogText, crawlLogText.contains("outcome=matched_compatible_logical"))
            assertTrue(crawlLogText.contains("matchedExpectedLogical=false"))
            assertTrue(crawlLogText.contains("matchedCompatibleLogical=true"))
            assertTrue(crawlLogText.contains("entryFingerprintMatchReason=actual_enriches_expected_identities"))
            assertTrue(crawlLogText.contains("entryFingerprintExpectedCount=2"))
            assertTrue(crawlLogText.contains("entryFingerprintObservedCount=3"))
            assertTrue(crawlLogText.contains("entryFingerprintOverlapCount=2"))
            assertTrue(crawlLogText.contains("entryFingerprintExpectedCoverage=1.000"))
            assertTrue(crawlLogText.contains("entryFingerprintObservedCoverage=0.667"))
            assertTrue(crawlLogText.contains("entryFingerprintDiceSimilarity=0.800"))
            assertTrue(crawlLogText.contains("verifiedForReplay=true"))
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

    // --- a2h-c2b.1 pins: the external-package boundary is uncrossable and needs no operator ---

    @Test
    fun externalPackageClick_isSkippedAutomatically_withoutAnyPauseDecision() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-auto-skip").toFile()
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

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
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
            val rootXml = summary.rootFiles.xmlFile.readText()

            assertEquals(
                "The external boundary must never ask the operator for a decision: ${host.pauseReasons}",
                emptyList<PauseReason>(),
                host.pauseReasons,
            )
            assertTrue(manifestJson.contains(""""status": "skipped_external_package""""))
            assertTrue(manifestJson.contains(""""label": "Open Google""""))
            assertTrue(
                "The skipped edge must record the destination package: $rootXml",
                rootXml.contains("""external-package="$externalPackageName""""),
            )
            assertEquals(1, summary.capturedScreenCount)
            assertEquals(1, summary.skippedElementCount)
            assertFalse(manifestJson.contains(""""screenName": "Google""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun externalPackageSkip_recoversToTargetApp_onNextEdge() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-recovery").toFile()
        val externalPackageName = "com.google.android.googlequicksearchbox"
        try {
            val host = object : FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Open Google", 0),
                            fakeElement("Open Details", 1),
                        ),
                        transitions = mapOf(
                            "Open Google" to "G",
                            "Open Details" to "B",
                        ),
                    ),
                    "G" to fakeScreen(
                        id = "G",
                        screenName = "Google",
                        packageName = externalPackageName,
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                    "B" to fakeScreen(
                        id = "B",
                        screenName = "Details",
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            ) {
                val pauseReasons = mutableListOf<PauseReason>()

                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
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
            val crawlLogText = File(summary.manifestFile.parentFile, "crawl.log").readText()

            assertEquals(emptyList<PauseReason>(), host.pauseReasons)

            // The crawler is standing in the foreign app when the skip is recorded. Recovery is
            // not new code: the next edge restore probe fails the package check and relaunches.
            val skipIndex = crawlLogText.indexOf("edge_skipped_external_package")
            assertTrue("Expected an external skip to be logged.", skipIndex >= 0)
            val relaunchIndex = crawlLogText.indexOf("entry_restore_attempt strategy=relaunch", skipIndex)
            assertTrue(
                "Expected a relaunch of the target app after the external skip.",
                relaunchIndex > skipIndex,
            )
            assertTrue("Expected at least one relaunch.", host.relaunchCount >= 1)

            // ...and the next edge then captures normally, inside the target app.
            assertEquals(2, summary.capturedScreenCount)
            assertTrue(manifestJson.contains(""""screenName": "Details""""))
            assertTrue(manifestJson.contains(""""status": "captured""""))
            assertFalse(manifestJson.contains(""""status": "failed""""))
        } finally {
            tempDir.deleteRecursively()
        }
    }


    @Test
    fun noCodePath_capturesAScreenOutsideTheTargetPackage() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-external-guard").toFile()
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
                override suspend fun awaitPauseDecision(
                    reason: PauseReason,
                    snapshot: PauseProgressSnapshot,
                ): PauseDecision = PauseDecision.CONTINUE
            }

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val manifestJson = summary.manifestFile.readText()

            // No screen record may belong to a package other than the target, whatever the host
            // answers -- and nothing is asked, so there is no answer that could change this.
            assertFalse(
                "A screen outside the target package was captured: $manifestJson",
                manifestJson.contains(""""packageName": "$externalPackageName""""),
            )
            assertFalse(manifestJson.contains(""""screenName": "Google""""))
            assertFalse(manifestJson.contains(""""screenName": "Results""""))

            // The one edge leaving the target app must not reach a capturing status.
            assertFalse(manifestJson.contains(""""status": "captured""""))
            assertFalse(manifestJson.contains(""""status": "linked_existing""""))
            assertEquals(1, summary.capturedScreenCount)
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
    fun delayedExternalPackage_isSkippedAsExternal_notAsNoNavigation() = runBlocking {
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

            assertEquals(emptyList<PauseReason>(), host.pauseReasons)

            // The settle loop must wait out the delayed transition. Without that wait the crawler
            // would still be looking at the parent screen and would file this as
            // skipped_no_navigation, losing the fact that the click left the target app.
            assertTrue(crawlLogText.contains("child_destination_observe_attempt"))
            assertTrue(crawlLogText.contains("result=unchanged_retry"))
            assertTrue(crawlLogText.contains("result=changed"))
            assertFalse(manifestJson.contains(""""status": "skipped_no_navigation""""))
            assertTrue(manifestJson.contains(""""status": "skipped_external_package""""))

            // ...and the external screen itself is never captured.
            assertEquals(1, summary.capturedScreenCount)
            assertEquals(1, summary.skippedElementCount)
            assertFalse(manifestJson.contains(""""screenName": "Google Services""""))
            assertFalse(manifestJson.contains(""""packageName": "$externalPackageName""""))
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
            assertTrue(crawlLogText.contains("sampleCount=3"))
            assertTrue(crawlLogText.contains("stopReason=fixed_dwell_exhausted"))
            assertTrue(crawlLogText.contains("sameIdentityAsPrevious=true"))
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
            assertTrue(crawlLogText.contains("observedIdentity=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("Summary"))
            assertTrue(crawlLogText.contains("selectedIdentity=\"android.widget.FrameLayout::"))
            assertTrue(crawlLogText.contains("Detailed option"))
            assertTrue(crawlLogText.contains("selectionReason=best_richness"))
            assertTrue(crawlLogText.contains("visibleTextOrContentDescriptionCount=4"))
            assertTrue(richChildXml != null)
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

    @Test
    fun perScreenXml_emits_crawl_block_and_edges_after_completed_run() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-per-screen-xml").toFile()
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

            val rootXml = summary.rootFiles.xmlFile.readText()
            assertTrue(
                "root XML should carry a <crawl> block:\n$rootXml",
                rootXml.contains("<crawl schema=\"v1\""),
            )
            assertTrue(
                "root XML should be marked is-root and complete:\n$rootXml",
                rootXml.contains("is-root=\"true\"") &&
                    rootXml.contains("expansion-status=\"complete\""),
            )
            assertTrue(
                "root XML should carry run-level session-id:\n$rootXml",
                rootXml.contains("session-id=\""),
            )
            assertTrue(
                "root XML should emit a captured edge for the only element:\n$rootXml",
                rootXml.contains("<edge id=\"edge_000\" status=\"captured\"") &&
                    rootXml.contains("child-screen-id=\"screen_00001\""),
            )

            val parentDir = summary.rootFiles.xmlFile.parentFile
                ?: error("root XML missing parent dir")
            val childXmlFile = parentDir.listFiles()
                ?.firstOrNull { it.name.startsWith("screen_00001_") && it.name.endsWith(".xml") }
                ?: error("child XML file should exist under ${parentDir.absolutePath}")
            val childXml = childXmlFile.readText()
            assertTrue(
                "child XML should carry a <crawl> block:\n$childXml",
                childXml.contains("<crawl schema=\"v1\""),
            )
            assertTrue(
                "child XML should be non-root:\n$childXml",
                childXml.contains("is-root=\"false\"") &&
                    childXml.contains("expansion-status=\"complete\""),
            )
            assertTrue(
                "child XML should reference its parent screen:\n$childXml",
                childXml.contains("<parent screen-id=\"screen_00000\""),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun perScreenXml_emits_skipped_blacklist_edge() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-per-screen-skipped").toFile()
        try {
            val host = FakeHost(
                entryScreenId = "A",
                screens = mapOf(
                    "A" to fakeScreen(
                        id = "A",
                        screenName = "Screen A",
                        elements = listOf(
                            fakeElement("Open B", 0),
                            fakeElement("Skip Me", 1, editable = true),
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
                host,
                tempDir,
                blacklist = CrawlBlacklist(skipCheckable = false),
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )
            val summary = (outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed).summary
            val rootXml = summary.rootFiles.xmlFile.readText()
            assertTrue(
                "root XML should record a blacklisted skip edge:\n$rootXml",
                rootXml.contains("status=\"skipped_blacklist\""),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun nameFreeze_rescan_of_child_screen_passes_captured_name_as_preferred() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-name-freeze").toFile()
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
            val scanCalls = mutableListOf<Pair<String, String?>>()
            val outcome = coordinator(
                host,
                tempDir,
                onScanCall = { screenId, preferredName -> scanCalls += screenId to preferredName },
            ).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed
            val bCalls = scanCalls.filter { (id, _) -> id == "B" }
            assertEquals(
                "Screen B should be scanned for first capture and for prepareScreenForExpansion rescan: $scanCalls",
                2,
                bCalls.size,
            )
            assertEquals(
                "First capture of B must not pass a preferred name (canonical name chosen now).",
                null,
                bCalls[0].second,
            )
            assertEquals(
                "Rescan of B for expansion must freeze the name with the previously-captured value.",
                "Screen B",
                bCalls[1].second,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun nameFreeze_keeps_screen_fingerprint_stable_across_capture_and_rescan() = runBlocking {
        val tempDir = Files.createTempDirectory("deep-crawl-name-freeze-fp").toFile()
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
                        elements = emptyList(),
                        transitions = emptyMap(),
                    ),
                ),
            )

            val outcome = coordinator(host, tempDir).crawl(
                initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
                eventClassName = "ScreenA",
            )

            outcome as DeepCrawlCoordinator.DeepCrawlOutcome.Completed
            // BFS reached Screen C through B; if rescan of B had drifted the name, the
            // replay validation in prepareScreenForExpansion would have failed and C
            // would never have been enqueued / captured.
            assertEquals(3, outcome.summary.capturedScreenCount)
            assertEquals(2, outcome.summary.maxDepthReached)
            val manifestJson = outcome.summary.manifestFile.readText()
            assertTrue(
                "Screen B should be present with its captured name:\n$manifestJson",
                manifestJson.contains(""""screenName": "Screen B""""),
            )
            assertTrue(
                "Screen C should be present with its captured name:\n$manifestJson",
                manifestJson.contains(""""screenName": "Screen C""""),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- route-step characterization (S2-S4 on a replayed child) ---------------------------
    //
    // Uses only harness API present at HEAD 6e1ce3b plus the `replayElementsByScreen` knob, so the
    // identical block runs against the pre-refactor code and the refactored code. A child is
    // discovered first; `replayElementsByScreen` changes what it shows only after the crawler has
    // returned to the root, i.e. on the replay that precedes expanding it.

    private fun rowAt(label: String, index: Int, top: Int): PressableElement =
        fakeElement(label, index).copy(bounds = "[0,$top][200,${top + 60}]")

    private suspend fun crawlThreeLevels(
        tempDir: File,
        childElements: List<PressableElement>,
        childTransitions: Map<String, String>,
        replayChildElements: List<PressableElement>? = null,
    ): Pair<CrawlRunSummary, String> {
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
                    elements = childElements,
                    transitions = childTransitions,
                ),
                "C" to fakeScreen(id = "C", screenName = "Screen C", elements = emptyList(), transitions = emptyMap()),
            ),
            replayElementsByScreen = replayChildElements?.let { mapOf("B" to it) }.orEmpty(),
        )
        val summary = coordinator(host, tempDir).crawl(
            initialRoot = host.captureCurrentRootSnapshot("com.example.target")!!,
            eventClassName = "ScreenA",
        ).summary
        return summary to File(summary.manifestFile.parentFile, "crawl.log").readText()
    }

    /** Replaying to an unchanged child succeeds and the child expands. */
    @Test
    fun routeStep_unchangedChild_replays_and_expands() = runBlocking {
        val tempDir = Files.createTempDirectory("route-step-unchanged").toFile()
        try {
            val (summary, log) = crawlThreeLevels(
                tempDir = tempDir,
                childElements = listOf(fakeElement("Open C", 0)),
                childTransitions = mapOf("Open C" to "C"),
            )
            assertEquals(3, summary.capturedScreenCount)
            assertFalse(log.contains("matchedExpectedReplay=false"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Zero tolerance: one extra control on replay is a different screen, and the child is lost. */
    @Test
    fun routeStep_extraElementOnReplay_fails_replay() = runBlocking {
        val tempDir = Files.createTempDirectory("route-step-extra").toFile()
        try {
            val (summary, log) = crawlThreeLevels(
                tempDir = tempDir,
                childElements = listOf(fakeElement("Open C", 0)),
                childTransitions = mapOf("Open C" to "C"),
                replayChildElements = listOf(fakeElement("Open C", 0), fakeElement("Late banner", 1)),
            )
            assertEquals(2, summary.capturedScreenCount)
            assertTrue(log.contains("matchedExpectedReplay=false"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Position is not identity: a control that only moved is the same screen. */
    @Test
    fun routeStep_positionOnlyChangeOnReplay_still_matches() = runBlocking {
        val tempDir = Files.createTempDirectory("route-step-moved").toFile()
        try {
            val (summary, log) = crawlThreeLevels(
                tempDir = tempDir,
                childElements = listOf(rowAt("Open C", 0, top = 500)),
                childTransitions = mapOf("Open C" to "C"),
                replayChildElements = listOf(rowAt("Open C", 0, top = 540)),
            )
            assertEquals(3, summary.capturedScreenCount)
            assertFalse(log.contains("matchedExpectedReplay=false"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Round-4 N1. A row whose resource id merely *contains* "back" (`send_feedback`) drifts across
     * the 300px band on replay. Back-ness is decided from position; identity must not be. At HEAD
     * the zero-tolerance sites compared position-free fingerprint encodings, so this replays fine.
     */
    @Test
    fun routeStep_backSignalRowCrossingTheTopBand_still_matches() = runBlocking {
        val tempDir = Files.createTempDirectory("route-step-band").toFile()
        try {
            val (summary, log) = crawlThreeLevels(
                tempDir = tempDir,
                childElements = listOf(fakeElement("Open C", 0), rowAt("Send feedback", 1, top = 280)),
                childTransitions = mapOf("Open C" to "C", "Send feedback" to "B"),
                replayChildElements = listOf(fakeElement("Open C", 0), rowAt("Send feedback", 1, top = 320)),
            )
            assertEquals(3, summary.capturedScreenCount)
            assertFalse(log.contains("matchedExpectedReplay=false"))
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
        onScanCall: ((screenId: String, preferredName: String?) -> Unit)? = null,
    ): DeepCrawlCoordinator {
        var postClickSettleTimeMs = 0L
        var entryRestoreSettleTimeMs = 0L
        return DeepCrawlCoordinator(
            selectedApp = selectedApp(),
            host = host,
            loadBlacklist = { blacklist },
            createSession = { startedAt, _ ->
                val sessionDir = File(tempDir, "session-$startedAt").apply { mkdirs() }
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
            scanScreenOverride = if (useRealScan) null else { _, initialRoot, _, _, preferredName ->
                val screenId = initialRoot.viewIdResourceName?.substringAfterLast('/')
                if (screenId != null) onScanCall?.invoke(screenId, preferredName)
                host.snapshotForRoot(initialRoot, preferredName = preferredName)
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

    private fun writeSavedScreenXml(
        dir: File,
        screenId: String,
        screenName: String,
        depth: Int,
        expansionStatus: ScreenExpansionStatus,
        isRoot: Boolean,
        elements: List<PressableElement>,
        edgesByElement: Map<PressableElementLinkKey, EdgeXmlView> = emptyMap(),
        parent: ParentEdgeRef? = null,
    ) {
        val snapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = "com.example.target",
            elements = elements,
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = ScreenCrawlState(
            screenId = screenId,
            depth = depth,
            expansionStatus = expansionStatus,
            isRoot = isRoot,
            screenIdentity = ScreenIdentityFields(
                packageName = ScreenNaming.normalizeIdentityToken("com.example.target"),
                title = ScreenNaming.normalizeIdentityToken(screenName),
                titleDisambiguators = emptyList(),
            ),
            parent = parent,
            route = CrawlRoute(),
            runLevel = if (isRoot) {
                RunLevelState(
                    sessionId = "session-1000",
                    startedAt = 1_000L,
                    finishedAt = null,
                    status = CrawlRunStatus.IN_PROGRESS,
                    maxDepthReached = depth,
                )
            } else {
                null
            },
            edgesByElement = edgesByElement,
        )
        val baseName = "${screenId}_${ScreenNaming.toFileBase(screenName)}"
        File(dir, "$baseName.html").writeText("<html></html>", Charsets.UTF_8)
        File(dir, "$baseName.xml").writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState),
            Charsets.UTF_8,
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
        /** Screens that lose their back affordance from their second capture onward. */
        private val backAffordanceDroppedOnReplayScreens: Set<String> = emptySet(),
        /** Elements a screen shows once the crawler has returned to the root, i.e. on replay. */
        private val replayElementsByScreen: Map<String, List<PressableElement>> = emptyMap(),
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
                dropBackAffordance = captureScreenId in backAffordanceDroppedOnReplayScreens && captureCount >= 2,
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

        fun snapshotForRoot(
            root: AccessibilityNodeSnapshot,
            preferredName: String? = null,
        ): ScreenSnapshot {
            val screenId = root.viewIdResourceName?.substringAfterLast('/') ?: error("Missing fake screen id.")
            val screen = screens.getValue(screenId)
            preferredNameByScreenId[screenId] = preferredName
            val effectiveName = preferredName?.takeIf { it.isNotBlank() } ?: screen.screenName
            return ScreenSnapshot(
                screenName = effectiveName,
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

        val preferredNameByScreenId: MutableMap<String, String?> = mutableMapOf()

        private fun rootFor(
            screenId: String,
            shiftedBounds: Boolean = false,
            captureCount: Int = 0,
            dropBackAffordance: Boolean = false,
        ): AccessibilityNodeSnapshot {
            val screen = screens.getValue(screenId)
            val variant = screen.captureVariants.getOrNull(
                captureCount.coerceAtMost((screen.captureVariants.size - 1).coerceAtLeast(0))
            )
            val packageName = variant?.packageNameOverride ?: screen.packageName
            val className = variant?.className ?: "android.widget.FrameLayout"
            val elements = replayElementsByScreen[screenId]?.takeIf { returnedToEntryScreen }
                ?: variant?.elements
                ?: screen.elements
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
                    if (screenId != entryScreenId && screenId !in screensWithoutBackAffordance && !dropBackAffordance) {
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
