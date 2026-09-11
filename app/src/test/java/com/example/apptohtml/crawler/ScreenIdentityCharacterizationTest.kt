package com.example.apptohtml.crawler

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization pins for `a2h-c2b.2` — structured screen identity.
 *
 * These pin **outcomes**, not call shapes. They are written against the pre-refactor code and must
 * keep passing afterwards; the only edit the SCOPE permits is how the *expected value* is built
 * (`String` -> `ScreenIdentity`) and renamed identifiers. Relaxing an assertion here to make the
 * refactor pass is a scope violation, not a fix.
 *
 * Three groups:
 *  - entry-restore outcomes, including both sides of the Dice threshold;
 *  - the root-vs-child back-affordance asymmetry that the refactor deliberately removes;
 *  - dedup grouping, which pins the `hints` -> `titleDisambiguators` rename as *pure*.
 */
class ScreenIdentityCharacterizationTest {
    private val coordinator = ScrollScanCoordinator()

    // ---- entry restore -------------------------------------------------------------------

    @Test
    fun entryRestore_exact_match_is_matched_expected_logical() = runBlocking {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        val observed = screenRoot(labels = listOf("Apps", "Display", "Network"))

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH,
            result.entryFingerprintMatchReason,
        )
        assertTrue(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
    }

    @Test
    fun entryRestore_observed_enriches_expected_is_compatible() = runBlocking {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        val observed = screenRoot(labels = listOf("Apps", "Display", "Network", "Storage"))

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.entryFingerprintMatchReason,
        )
        assertFalse(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
        assertEquals(3, result.entryFingerprintExpectedCount)
        assertEquals(4, result.entryFingerprintObservedCount)
        assertEquals(3, result.entryFingerprintOverlapCount)
    }

    /**
     * Sits exactly ON the 2/3 boundary: 6 expected, 6 observed, 4 shared -> Dice = 8/12.
     * Pins that the comparison is `>=` and not `>`, so nudging the threshold either way turns
     * this red.
     */
    @Test
    fun entryRestore_dice_at_threshold_is_compatible() = runBlocking {
        val expected = screenRoot(labels = SIX_LABELS)
        val observed = screenRoot(
            labels = listOf("Apps", "Display", "Network", "Sound", "Storage", "Wallpaper"),
        )

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
            result.entryFingerprintMatchReason,
        )
        assertEquals(4, result.entryFingerprintOverlapCount)
        assertEquals(2.0 / 3.0, result.entryFingerprintDiceSimilarity, 1e-12)
        assertTrue(result.verifiedForReplay)
    }

    @Test
    fun entryRestore_dice_below_threshold_is_not_found() = runBlocking {
        val expected = screenRoot(labels = SIX_LABELS)
        val observed = screenRoot(
            labels = listOf("Apps", "Display", "Network", "Storage", "Wallpaper", "Camera"),
        )

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.IDENTITY_OVERLAP_TOO_LOW,
            result.entryFingerprintMatchReason,
        )
        assertEquals(3, result.entryFingerprintOverlapCount)
        assertEquals(0.5, result.entryFingerprintDiceSimilarity, 1e-12)
        assertFalse(result.verifiedForReplay)
    }

    @Test
    fun entryRestore_root_class_mismatch_is_not_found() = runBlocking {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        val observed = screenRoot(
            labels = listOf("Apps", "Display", "Network"),
            rootClassName = "android.widget.LinearLayout",
        )

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH,
            result.entryFingerprintMatchReason,
        )
        assertFalse(result.verifiedForReplay)
    }

    @Test
    fun entryRestore_foreign_package_is_unrelated() = runBlocking {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        // Round-6 D1: identical content (resource ids still in the target's namespace) shown by a
        // foreign package. Previously the fixture also moved the resource ids, so the sets never
        // overlapped and the pin passed on the overlap rule with the package check deleted.
        val observed = screenRoot(
            labels = listOf("Apps", "Display", "Network"),
            packageName = "com.other.app",
            resourcePackage = TARGET_PACKAGE,
        )

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES,
            result.entryFingerprintMatchReason,
        )
        assertFalse(result.verifiedForReplay)
    }

    @Test
    fun entryRestore_without_expected_assumes_entry() = runBlocking {
        val observed = screenRoot(labels = listOf("Apps", "Display", "Network"))

        val result = rewind(observed, expectedIdentity = null)

        assertEquals(EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY, result.outcome)
        assertEquals(
            ScreenIdentityMatchReason.NO_EXPECTED_FINGERPRINT,
            result.entryFingerprintMatchReason,
        )
        assertTrue(result.verifiedForReplay)
    }

    // ---- the polymorphic replay fingerprint ---------------------------------------------

    /**
     * RETIRED AND REPLACED, deliberately. The pre-refactor pin asserted the *asymmetry* this cycle
     * exists to remove: that the entry builder dropped the back affordance while the plain builder
     * kept it, so one screen produced two incompatible values. There is now one builder, so that
     * premise no longer exists and the pin cannot be kept as written.
     *
     * What replaces it asserts the property that removed the asymmetry: the back affordance is
     * present in the structure and flagged, and the policy — not the builder — decides whether to
     * count it.
     */
    @Test
    fun backAffordance_is_kept_in_the_structure_and_flagged() {
        val withBack = screenRoot(labels = listOf("Apps", "Display"), withBackAffordance = true)
        val withoutBack = screenRoot(labels = listOf("Apps", "Display"))

        val backElements = ScreenIdentity.fromRoot(withBack).elements
            .filter(ScreenElementIdentity::isBackAffordance)
        assertEquals(1, backElements.size)
        assertEquals("navigate up", backElements.single().fingerprint.label)

        assertEquals(
            "counting back affordances, the two screens differ",
            false,
            SameScreenPolicy(countBackAffordances = true)
                .compare(ScreenIdentity.fromRoot(withBack), ScreenIdentity.fromRoot(withoutBack))
                .matched,
        )
        assertEquals(
            "ignoring them, they are the same screen",
            true,
            SameScreenPolicy(countBackAffordances = false)
                .compare(ScreenIdentity.fromRoot(withBack), ScreenIdentity.fromRoot(withoutBack))
                .matched,
        )
    }

    /** The capability the structure buys: a non-match says what differed. */
    @Test
    fun a_non_matching_comparison_reports_missing_and_extra_elements() {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        val observed = screenRoot(labels = listOf("Apps", "Display", "Storage"))

        val comparison = SameScreenPolicy(countBackAffordances = true)
            .compare(ScreenIdentity.fromRoot(expected), ScreenIdentity.fromRoot(observed))

        assertEquals(false, comparison.matched)
        assertEquals(ScreenIdentityMatchReason.ELEMENT_SET_DIFFERS, comparison.reason)
        assertEquals(setOf("network"), comparison.missing.map { it.fingerprint.label }.toSet())
        assertEquals(setOf("storage"), comparison.extra.map { it.fingerprint.label }.toSet())
    }

    @Test
    fun entryRestore_tolerates_a_back_affordance_appearing_on_the_entry_screen() = runBlocking {
        val expected = screenRoot(labels = listOf("Apps", "Display", "Network"))
        val observed = screenRoot(
            labels = listOf("Apps", "Display", "Network"),
            withBackAffordance = true,
        )

        val result = rewind(observed, ScreenIdentity.fromRoot(expected))

        assertEquals(EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL, result.outcome)
        assertTrue(result.verifiedForReplay)
    }

    /**
     * Pins the one comparison-outcome change this cycle makes, named in SCOPE.
     *
     * Back-ness is decided from `bounds`, which is NOT part of `ElementFingerprint`. So two
     * pressables can share a fingerprint while only one sits in the top band. The builder
     * deduplicates FIRST — first occurrence in tree order wins — and only then asks whether the
     * survivor is a back affordance.
     *
     * Pre-refactor the entry matcher's *observed* side did the opposite (filter, then set), so it
     * kept the fingerprint via the second element while the *expected* side dropped it. One builder
     * now serves both sides.
     */
    @Test
    fun entryIdentity_dedups_before_flagging_so_one_builder_serves_both_sides() {
        val root = rootWithTwoIdenticallyFingerprintedRows()

        val identity = ScreenIdentity.fromRoot(root)
        val backup = identity.elements.single { it.fingerprint.label == "backup" }

        assertTrue("the top-band occurrence wins dedup and is flagged", backup.isBackAffordance)
        assertTrue(
            "so a comparison that ignores back affordances drops it entirely",
            identity.elementsFor(countBackAffordances = false)
                .none { it.fingerprint.label == "backup" },
        )
    }

    /**
     * ASSESS metric 7 — every part of the identity is readable as a **field**. No string parsing:
     * that is the whole difference between this and the flattened representation it replaces.
     */
    @Test
    fun every_part_of_the_identity_is_addressable_as_a_field() {
        val identity = ScreenIdentity
            .fromRoot(screenRoot(labels = listOf("Apps", "Display"), withBackAffordance = true))
            .withName(
                ScreenNaming.buildScreenNameIdentity(
                    screenName = "Network and internet",
                    packageName = TARGET_PACKAGE,
                    root = namedRoot(title = "Network and internet", detail = "Wi-Fi"),
                )
            )

        assertEquals(TARGET_PACKAGE, identity.packageName)
        assertEquals("android.widget.FrameLayout", identity.rootClassName)
        assertEquals("network_and_internet", identity.name!!.screenName)
        assertEquals("com_example_target", identity.name!!.packageName)
        assertEquals(listOf("wi_fi"), identity.name!!.titleDisambiguators)
        assertEquals(ScreenDedupConfidence.STRONG, identity.name!!.confidence)
        assertEquals(
            setOf("apps", "display", "navigate up"),
            identity.elements.map { it.fingerprint.label }.toSet(),
        )
        assertEquals(1, identity.elements.count { it.isBackAffordance })
    }

    // ---- guards that must not be deletable with the suite green (round 6) ------------------

    /** Round-6 D2: the root-class half of the zero-tolerance rule. Same pressables, other container. */
    @Test
    fun sameScreen_rejects_the_same_pressables_in_a_different_container() {
        val expected = ScreenIdentity.fromRoot(screenRoot(labels = listOf("Apps", "Display")))
        val observed = ScreenIdentity.fromRoot(
            screenRoot(labels = listOf("Apps", "Display"), rootClassName = "android.widget.LinearLayout"),
        )

        val comparison = SameScreenPolicy(countBackAffordances = true).compare(expected, observed)

        assertFalse(comparison.matched)
        assertEquals(ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH, comparison.reason)
    }

    /**
     * Round-6 D3: the mismatch direction of the replay-name check — the guard that fired on the
     * 2026-09-10 device run. Only "same name passes" was pinned before.
     */
    @Test
    fun sameName_rejects_a_screen_whose_name_key_changed() {
        val recorded = testIdentity(screenName = "Connect a device", titleDisambiguators = listOf("Connected to this phone"))

        val otherDisambiguator = SameNamePolicy.compare(
            recorded,
            testIdentity(screenName = "Connect a device", titleDisambiguators = listOf("Not connected")),
        )
        val otherTitle = SameNamePolicy.compare(
            recorded,
            testIdentity(screenName = "Pair new device", titleDisambiguators = listOf("Connected to this phone")),
        )

        assertFalse(otherDisambiguator.matched)
        assertEquals(ScreenIdentityMatchReason.NAME_DIFFERS, otherDisambiguator.reason)
        assertFalse(otherTitle.matched)
        assertEquals(ScreenIdentityMatchReason.NAME_DIFFERS, otherTitle.reason)
    }

    /**
     * Round-6 D4: a WEAK-titled screen is never linked, even to an identical one. Existing pins
     * checked the confidence value, or used weak screens whose disambiguators already differed, so
     * the gate itself could be deleted with the suite green. Two distinct "Continue" screens with no
     * disambiguators would then collapse into one and the second subtree would never be captured.
     */
    @Test
    fun tracker_never_links_a_weak_titled_screen_even_to_an_identical_one() {
        val tracker = CrawlRunTracker(
            sessionId = "crawl_test",
            packageName = TARGET_PACKAGE,
            startedAt = 0L,
        )
        val weak = testIdentity(screenName = "Continue", confidence = ScreenDedupConfidence.WEAK)

        tracker.addScreen(
            screenId = "screen_1",
            snapshot = screenSnapshot(),
            identity = weak,
            files = capturedFiles(),
            parentScreenId = null,
            triggerElement = null,
            route = CrawlRoute(),
            depth = 0,
        )

        assertEquals(null, tracker.findScreenIdByIdentity(weak))
    }

    // ---- position must never take part in identity ------------------------------------------

    /**
     * A back-signal row drifts across the 300px band between two captures of the SAME screen.
     *
     * Back-ness is derived from position (`top <= 300`) and any resource id *containing* "back"
     * qualifies — `send_feedback`, `backup`, `background`. Identity was never position-dependent:
     * before this cycle every zero-tolerance site compared position-free `ElementFingerprint`
     * encodings, so this was the same screen. The flag may decide *membership* — whether back
     * affordances are counted at all — but must never take part in *equality*.
     *
     * A collapsing toolbar or a late banner is enough to move such a row 40px in production.
     */
    @Test
    fun a_back_signal_row_crossing_the_top_band_is_still_the_same_screen() {
        val captured = ScreenIdentity.fromRoot(rootWithFeedbackRowAt(top = 280))
        val replayed = ScreenIdentity.fromRoot(rootWithFeedbackRowAt(top = 320))

        assertTrue(
            "S2/S4/S5: the same screen, whatever the row's position",
            SameScreenPolicy(countBackAffordances = true).compare(captured, replayed).matched,
        )
        assertFalse(
            "S3/S6: a click that only shifted the row did not navigate",
            NavigatedAwayPolicy(countBackAffordances = true)
                .navigatedAway(beforeClick = captured, top = captured, after = replayed),
        )
    }

    // ---- the richness metric's input (a live selection comparator, not a diagnostic) -----

    /**
     * `DestinationRichnessMetrics.logicalFingerprintLength` feeds `richnessScore`, which picks the
     * settle sample that becomes the captured child. It must keep measuring the pre-structure
     * *logical* encoding: six fields per element, no back-affordance flag, and the element set the
     * policy actually compares. Measuring the flagged encoding adds ~6 chars per element and a
     * whole extra element on root-parent screens, which moves the selection.
     */
    @Test
    fun richnessInput_excludes_the_back_flag_and_honors_the_policy() {
        val identity = ScreenIdentity.fromRoot(
            screenRoot(labels = listOf("Apps", "Display"), withBackAffordance = true)
        )

        val counting = ScreenIdentityCodec.encodeLogical(identity, countBackAffordances = true)
        val ignoring = ScreenIdentityCodec.encodeLogical(identity, countBackAffordances = false)

        assertTrue("the root class leads the measured string", counting.startsWith("android.widget.FrameLayout::"))
        assertFalse("no back flag belongs in the measured string", counting.endsWith("|true"))
        assertFalse(counting.contains("|true|"))
        assertFalse(ignoring.contains("back_button"))
        assertTrue(counting.contains("back_button"))
        assertEquals(
            "each element encodes as exactly six fields",
            6,
            counting.substringAfter("::").split("||").first().split("|").size,
        )
    }

    // RETIRED: `richnessMetric_measures_the_logical_encoding` built its own input and asserted
    // `x.length == from(root, x).length` — true by construction, and green under the very defect
    // it claimed to guard. The real guard is
    // DestinationSettlerTest.settle_measures_richness_from_the_logical_encoding, which drives the
    // settler and is archived RED against the pre-fix wiring.

    // ---- dedup grouping (pins the rename as pure) ----------------------------------------

    @Test
    fun dedup_groups_two_visits_to_the_same_named_screen() {
        val first = namedRoot(title = "Network and internet", detail = "Wi-Fi")
        val second = namedRoot(title = "Network and internet", detail = "Wi-Fi")

        val a = ScreenNaming.buildScreenNameIdentity("Network and internet", TARGET_PACKAGE, first)
        val b = ScreenNaming.buildScreenNameIdentity("Network and internet", TARGET_PACKAGE, second)

        assertEquals(dedupKey(a), dedupKey(b))
        assertEquals(ScreenDedupConfidence.STRONG, a.confidence)
        assertTrue(a.canLinkToExisting)
    }

    @Test
    fun dedup_separates_screens_with_different_names() {
        val network = namedRoot(title = "Network and internet", detail = "Wi-Fi")
        val sound = namedRoot(title = "Sound and vibration", detail = "Volume")

        val a = ScreenNaming.buildScreenNameIdentity("Network and internet", TARGET_PACKAGE, network)
        val b = ScreenNaming.buildScreenNameIdentity("Sound and vibration", TARGET_PACKAGE, sound)

        assertNotEquals(dedupKey(a), dedupKey(b))
    }

    @Test
    fun dedup_separates_same_titled_screens_that_differ_in_their_first_disambiguator() {
        val wifi = namedRoot(title = "Details", detail = "Wi-Fi")
        val mobile = namedRoot(title = "Details", detail = "Mobile network")

        val a = ScreenNaming.buildScreenNameIdentity("Details", TARGET_PACKAGE, wifi)
        val b = ScreenNaming.buildScreenNameIdentity("Details", TARGET_PACKAGE, mobile)

        assertNotEquals(dedupKey(a), dedupKey(b))
    }

    /**
     * Today's cap, and a quirk worth recording: `collectTitleDisambiguators` applies `take(2)` and
     * *then* `buildScreenNameIdentity` drops any disambiguator equal to the title. The title is
     * itself a text candidate, so it consumes one of the two slots and a screen with four candidate
     * texts ends up with a single disambiguator.
     *
     * The Gate-1 proposal to widen the cap to 10 was withdrawn; this pins that it did not move.
     */
    @Test
    fun dedup_disambiguators_are_capped_and_the_title_consumes_a_candidate_slot() {
        val root = namedRoot(
            title = "Details",
            detail = "Wi-Fi",
            extraDetails = listOf("Mobile network", "Hotspot", "Airplane mode"),
        )

        val identity = ScreenNaming.buildScreenNameIdentity("Details", TARGET_PACKAGE, root)

        assertEquals(listOf("wi_fi"), identity.titleDisambiguators)
    }

    @Test
    fun dedup_refuses_to_link_a_weak_title() {
        val root = namedRoot(title = "Continue", detail = null)

        val identity = ScreenNaming.buildScreenNameIdentity("Continue", TARGET_PACKAGE, root)

        assertEquals(ScreenDedupConfidence.WEAK, identity.confidence)
        assertFalse(identity.canLinkToExisting)
    }

    /**
     * Resource-id candidates are not score-filtered, so a bare layout id becomes a disambiguator in its own
     * right — here the title node's own `screen_title`, which says nothing about the screen.
     *
     * Pinned because it is today's behavior, and because it is the concrete reason the Gate-1
     * proposal to widen the disambiguator cap from 2 to 10 was withdrawn: slots three through ten would
     * fill with exactly this, in tree order, and dedup would stop linking anything.
     */
    @Test
    fun dedup_accepts_an_unfiltered_resource_id_as_a_disambiguator() {
        val root = namedRoot(title = "Storage", detail = null)

        val identity = ScreenNaming.buildScreenNameIdentity("Storage", TARGET_PACKAGE, root)

        assertEquals(listOf("screen_title"), identity.titleDisambiguators)
        assertEquals(ScreenDedupConfidence.STRONG, identity.confidence)
        assertTrue(identity.canLinkToExisting)
    }

    @Test
    fun tracker_links_a_repeat_visit_and_keeps_distinct_screens_apart() {
        val tracker = CrawlRunTracker(
            sessionId = "crawl_test",
            packageName = TARGET_PACKAGE,
            startedAt = 0L,
        )
        val identity = ScreenNaming.buildScreenNameIdentity(
            "Network and internet",
            TARGET_PACKAGE,
            namedRoot(title = "Network and internet", detail = "Wi-Fi"),
        )
        val other = ScreenNaming.buildScreenNameIdentity(
            "Sound and vibration",
            TARGET_PACKAGE,
            namedRoot(title = "Sound and vibration", detail = "Volume"),
        )

        tracker.addScreen(
            screenId = "screen_1",
            snapshot = screenSnapshot(),
            identity = ScreenIdentity(
                packageName = TARGET_PACKAGE,
                rootClassName = "android.widget.FrameLayout",
                elements = emptySet(),
                name = identity,
            ),
            files = capturedFiles(),
            parentScreenId = null,
            triggerElement = null,
            route = CrawlRoute(),
            depth = 0,
        )

        assertEquals("screen_1", tracker.findScreenIdByIdentity(withName(identity)))
        assertEquals(null, tracker.findScreenIdByIdentity(withName(other)))
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun rewind(
        observed: AccessibilityNodeSnapshot,
        expectedIdentity: ScreenIdentity?,
    ): EntryScreenResetResult {
        return coordinator.rewindToEntryScreen(
            initialRoot = observed,
            targetPackageName = TARGET_PACKAGE,
            expectedEntryIdentity = expectedIdentity,
            tryBack = { throw AssertionError("no back press expected in these pins") },
            captureCurrentRoot = { null },
        )
    }

    private fun screenRoot(
        labels: List<String>,
        rootClassName: String = "android.widget.FrameLayout",
        packageName: String = TARGET_PACKAGE,
        withBackAffordance: Boolean = false,
        resourcePackage: String = packageName,
    ): AccessibilityNodeSnapshot {
        val children = buildList {
            labels.mapIndexedTo(this) { index, label ->
                pressable(
                    label = label,
                    resourceId = "$resourcePackage:id/${label.lowercase().replace(' ', '_')}",
                    bounds = "[0,${400 + index * 100}][600,${400 + index * 100 + 80}]",
                    packageName = packageName,
                    childIndex = index,
                )
            }
            if (withBackAffordance) {
                add(
                    pressable(
                        label = "Navigate up",
                        resourceId = "$packageName:id/back_button",
                        bounds = "[0,0][120,120]",
                        packageName = packageName,
                        childIndex = labels.size,
                        className = "android.widget.ImageButton",
                    )
                )
            }
        }
        return AccessibilityNodeSnapshot(
            className = rootClassName,
            packageName = packageName,
            viewIdResourceName = "$packageName:id/root",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][1080,2400]",
            children = children,
        )
    }

    /** A root whose static text drives the name-based identity: a title plus optional disambiguator rows. */
    private fun namedRoot(
        title: String,
        detail: String?,
        extraDetails: List<String> = emptyList(),
    ): AccessibilityNodeSnapshot {
        val texts = listOfNotNull(detail) + extraDetails
        val children = buildList {
            add(
                staticText(
                    text = title,
                    resourceId = "$TARGET_PACKAGE:id/screen_title",
                    bounds = "[120,20][900,120]",
                    childIndex = 0,
                )
            )
            texts.forEachIndexed { index, text ->
                add(
                    staticText(
                        text = text,
                        resourceId = "$TARGET_PACKAGE:id/detail_$index",
                        bounds = "[120,${140 + index * 90}][900,${140 + index * 90 + 70}]",
                        childIndex = index + 1,
                    )
                )
            }
        }
        return AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = TARGET_PACKAGE,
            viewIdResourceName = "$TARGET_PACKAGE:id/root",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][1080,2400]",
            children = children,
        )
    }

    private fun staticText(
        text: String,
        resourceId: String,
        bounds: String,
        childIndex: Int,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.TextView",
            packageName = TARGET_PACKAGE,
            viewIdResourceName = resourceId,
            text = text,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    private fun pressable(
        label: String,
        resourceId: String,
        bounds: String,
        packageName: String,
        childIndex: Int,
        className: String = "android.widget.Button",
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = resourceId,
            text = label,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    /**
     * Two pressables with the same [ElementFingerprint] — same label, resource id, class — but at
     * different heights, so only the first is inside the back-affordance top band.
     */
    private fun rootWithTwoIdenticallyFingerprintedRows(): AccessibilityNodeSnapshot {
        val rows = listOf(80, 900).mapIndexed { index, top ->
            pressable(
                label = "Backup",
                resourceId = "$TARGET_PACKAGE:id/backup",
                bounds = "[0,$top][600,${top + 60}]",
                packageName = TARGET_PACKAGE,
                childIndex = index,
            )
        }
        return AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = TARGET_PACKAGE,
            viewIdResourceName = "$TARGET_PACKAGE:id/root",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][1080,2400]",
            children = rows,
        )
    }

    private fun rootWithFeedbackRowAt(top: Int): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = TARGET_PACKAGE,
            viewIdResourceName = "$TARGET_PACKAGE:id/root",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][1080,2400]",
            children = listOf(
                pressable(
                    label = "Send feedback",
                    resourceId = "$TARGET_PACKAGE:id/send_feedback",
                    bounds = "[0,$top][600,${top + 60}]",
                    packageName = TARGET_PACKAGE,
                    childIndex = 0,
                ),
                pressable(
                    label = "Apps",
                    resourceId = "$TARGET_PACKAGE:id/apps",
                    bounds = "[0,500][600,560]",
                    packageName = TARGET_PACKAGE,
                    childIndex = 1,
                ),
            ),
        )
    }

    private fun dedupKey(name: ScreenNameIdentity): String? = DedupPolicy.keyFor(withName(name))

    private fun withName(name: ScreenNameIdentity): ScreenIdentity = ScreenIdentity(
        packageName = TARGET_PACKAGE,
        rootClassName = "android.widget.FrameLayout",
        elements = emptySet(),
        name = name,
    )

    private fun screenSnapshot(): ScreenSnapshot {
        return ScreenSnapshot(
            screenName = "Network and internet",
            packageName = TARGET_PACKAGE,
            elements = emptyList(),
            xmlDump = "",
        )
    }

    private fun capturedFiles(): CapturedScreenFiles {
        return CapturedScreenFiles(
            htmlFile = java.io.File("build/tmp/screen_1.html"),
            xmlFile = java.io.File("build/tmp/screen_1.xml"),
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        val SIX_LABELS = listOf("Apps", "Display", "Network", "Sound", "Battery", "About")
    }
}
