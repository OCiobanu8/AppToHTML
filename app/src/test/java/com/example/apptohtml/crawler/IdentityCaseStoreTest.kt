package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins for `a2h-c2b.3` scope call F (Gate 2, option B): a case lives in one file, and every screen it
 * involves carries only a pointer to it, derived from the open cases.
 */
class IdentityCaseStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- the case file ---------------------------------------------------------------------

    @Test
    fun the_case_file_round_trips_every_field() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)
        val cases = listOf(collision(), proposalCase())

        IdentityCaseStore.write(file, cases)

        assertEquals(cases.sortedBy { it.id }, IdentityCaseStore.read(file))
    }

    @Test
    fun the_case_file_is_written_sorted_and_byte_identical_whatever_the_order_given() {
        val forward = IdentityCaseStore.serialize(listOf(collision(), proposalCase()))
        val backward = IdentityCaseStore.serialize(listOf(proposalCase(), collision()))

        assertEquals(forward, backward)
        assertTrue(forward.indexOf("case_000") < forward.indexOf("case_001"))
    }

    @Test
    fun a_collision_names_every_screen_with_its_role() {
        val text = IdentityCaseStore.serialize(listOf(collision()))

        assertTrue(text.contains("""<screen screen-id="screen_00002" role="related" />"""))
        assertTrue(text.contains("""<screen screen-id="screen_00005" role="related" />"""))
        assertTrue(text.contains("""<screen screen-id="screen_00007" role="raised-on" />"""))
    }

    @Test
    fun the_rejected_proposal_is_written_in_the_identity_blocks_own_trait_syntax() {
        val text = IdentityCaseStore.serialize(listOf(proposalCase()))
        val traitsOnly = StringBuilder().also {
            ScreenIdentityXml.appendTraits(it, proposalCase().rejectedProposal, depth = 2)
        }.toString()

        assertTrue(text.contains(traitsOnly))
    }

    /** Round-3 observation: a crash between delete and rename leaves only the `.tmp`; say so. */
    @Test
    fun a_case_file_lost_mid_write_is_reported_with_the_temporary_file_that_holds_it() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)
        File(tempFolder.root, "${IdentityCaseStore.FILE_NAME}.tmp").writeText(IdentityCaseStore.serialize(listOf(collision())))

        assertFailsNaming("${IdentityCaseStore.FILE_NAME}.tmp") { IdentityCaseStore.read(file) }
    }

    @Test
    fun no_cases_writes_no_file() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)

        IdentityCaseStore.write(file, emptyList())

        assertFalse(file.exists())
        assertEquals(emptyList<IdentityCase>(), IdentityCaseStore.read(file))
    }

    @Test
    fun an_unreadable_case_file_fails_loudly_naming_it() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME).apply { writeText("<identity-cases><case") }

        assertFailsNaming(file.absolutePath) { IdentityCaseStore.read(file) }
    }

    @Test
    fun an_unknown_kind_or_status_fails_loudly_naming_the_case() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)
        val good = IdentityCaseStore.serialize(listOf(proposalCase()))

        file.writeText(good.replace("""kind="proposal-does-not-hold"""", """kind="vibes""""))
        assertFailsNaming("case_000") { IdentityCaseStore.read(file) }

        file.writeText(good.replace("""status="open"""", """status="maybe""""))
        assertFailsNaming("case_000") { IdentityCaseStore.read(file) }
    }

    @Test
    fun a_case_held_twice_fails_loudly() {
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)
        val one = IdentityCaseStore.serialize(listOf(proposalCase()))
        val body = one.substringAfter("<identity-cases schema=\"v1\">\n").substringBefore("</identity-cases>")
        file.writeText(one.replace("</identity-cases>", body + "</identity-cases>"))

        assertFailsNaming("case_000") { IdentityCaseStore.read(file) }
    }

    // ---- the tracker holds them ------------------------------------------------------------

    @Test
    fun every_involved_screen_points_at_an_open_case_and_two_cases_mean_two_pointers() {
        val tracker = tracker()
        val first = tracker.openCase(IdentityCaseKind.UNIQUENESS_COLLISION, "screen_00007", listOf("screen_00002"), "x")
        val second = tracker.openCase(IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD, "screen_00002", emptyList(), "y")

        assertEquals(listOf(first.id), tracker.openCaseIdsFor("screen_00007"))
        assertEquals(listOf(first.id, second.id), tracker.openCaseIdsFor("screen_00002"))
        assertEquals(emptyList<String>(), tracker.openCaseIdsFor("screen_00009"))
    }

    @Test
    fun closing_a_case_drops_its_pointers_and_keeps_it_as_history() {
        val tracker = tracker()
        val case = tracker.openCase(IdentityCaseKind.UNIQUENESS_COLLISION, "screen_00007", listOf("screen_00002"), "x")

        tracker.closeCase(case.id)

        assertEquals(emptyList<String>(), tracker.openCaseIdsFor("screen_00007"))
        assertEquals(emptyList<String>(), tracker.openCaseIdsFor("screen_00002"))
        assertEquals(IdentityCaseStatus.CLOSED, tracker.cases().single().status)
        assertTrue(IdentityCaseStore.serialize(tracker.cases()).contains("""status="closed""""))
    }

    @Test
    fun case_ids_continue_after_a_resume() {
        val loaded = listOf(proposalCase().copy(id = "case_004"))
        val tracker = CrawlRunTracker.fromExistingState(
            sessionId = "s",
            packageName = PKG,
            startedAt = 0L,
            screens = emptyList(),
            edges = emptyList(),
            rootScreenId = null,
            nextScreenSequence = 0,
            nextEdgeSequence = 0,
            cases = loaded,
        )

        assertEquals("case_005", tracker.openCase(IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD, "s1", emptyList(), "z").id)
    }

    // ---- pointers --------------------------------------------------------------------------

    @Test
    fun a_screen_with_no_open_case_gets_no_pointer() {
        val xml = serialize(identity = settledIdentity(), openCaseIds = emptyList())

        assertFalse(xml.contains("<identity-case"))
    }

    @Test
    fun pointers_sit_right_after_the_identity_sorted() {
        val xml = serialize(identity = settledIdentity(), openCaseIds = listOf("case_003", "case_001"))

        val afterIdentity = xml.substringAfter("</screen-identity>\n")
        assertTrue(
            afterIdentity.startsWith(
                """    <identity-case ref="case_001" />""" + "\n" + """    <identity-case ref="case_003" />""" + "\n",
            ),
        )
    }

    @Test
    fun refreshing_pointers_gives_exactly_what_serializing_afresh_gives() {
        val identities = listOf(settledIdentity(), ScreenIdentity.EMPTY.withName(settledIdentity().name!!))
        val pointerSets = listOf(emptyList(), listOf("case_000"), listOf("case_000", "case_002"))
        identities.forEach { identity ->
            pointerSets.forEach { before ->
                pointerSets.forEach { after ->
                    assertEquals(
                        "identity with ${identity.elements.size} element(s), $before to $after",
                        serialize(identity, after),
                        IdentityCasePointers.refresh(serialize(identity, before), after),
                    )
                }
            }
        }
    }

    @Test
    fun refreshing_a_file_on_disk_rewrites_only_its_pointers() {
        val file = File(tempFolder.root, "screen_00001_cart.xml")
        file.writeText(serialize(settledIdentity(), listOf("case_000")))

        IdentityCasePointers.refresh(file, emptyList())

        assertEquals(serialize(settledIdentity(), emptyList()), file.readText())
    }

    // ---- resume ----------------------------------------------------------------------------

    @Test
    fun a_resume_restores_the_cases_and_a_rewrite_reproduces_both_files_byte_for_byte() {
        val dir = tempFolder.newFolder("crawl")
        val case = proposalCase().copy(raisedOn = "screen_00000", relatedScreenIds = emptyList())
        val caseFile = IdentityCaseStore.fileIn(dir)
        IdentityCaseStore.write(caseFile, listOf(case))
        val screenXml = File(dir, "screen_00000_cart.xml")
        screenXml.writeText(serialize(settledIdentity(), listOf(case.id)))
        val caseText = caseFile.readText()
        val screenText = screenXml.readText()

        val loaded = SavedCrawlLoader.load(dir)!!
        val tracker = CrawlRunTracker.fromExistingState(
            sessionId = "s",
            packageName = PKG,
            startedAt = 0L,
            screens = loaded.screens,
            edges = loaded.edges,
            rootScreenId = loaded.rootScreenId,
            nextScreenSequence = loaded.nextScreenSequence,
            nextEdgeSequence = loaded.nextEdgeSequence,
            cases = loaded.cases,
        )
        IdentityCaseStore.write(caseFile, tracker.cases())
        screenXml.writeText(serialize(settledIdentity(), tracker.openCaseIdsFor("screen_00000")))

        assertEquals(listOf(case), loaded.cases)
        assertEquals(caseText, caseFile.readText())
        assertEquals(screenText, screenXml.readText())
    }

    @Test
    fun the_case_file_is_never_loaded_as_a_screen() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(proposalCase().copy(raisedOn = "screen_00000", relatedScreenIds = emptyList())),
        )
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_000")))

        assertEquals(listOf("screen_00000"), SavedCrawlLoader.load(dir)!!.screens.map { it.screenId })
    }

    @Test
    fun a_resume_fails_loudly_on_a_pointer_to_a_case_the_file_does_not_hold() {
        val dir = tempFolder.newFolder("crawl")
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_009")))

        assertFailsNaming("case_009") { SavedCrawlLoader.load(dir) }
    }

    @Test
    fun a_resume_fails_loudly_on_a_pointer_to_a_closed_case() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(proposalCase().copy(raisedOn = "screen_00000", status = IdentityCaseStatus.CLOSED)),
        )
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_000")))

        assertFailsNaming("case_000") { SavedCrawlLoader.load(dir) }
    }

    @Test
    fun a_resume_fails_loudly_on_a_malformed_case_file() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.fileIn(dir).writeText("not xml at all")
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), emptyList()))

        assertFailsNaming(IdentityCaseStore.FILE_NAME) { SavedCrawlLoader.load(dir) }
    }

    // ---- round 2: a hostile edit is refused, named, never absorbed (D7, D8) ----------------

    @Test
    fun an_edited_case_file_that_is_no_longer_what_this_crawl_writes_is_refused_by_name() {
        val good = IdentityCaseStore.serialize(listOf(collision()))
        val file = File(tempFolder.root, IdentityCaseStore.FILE_NAME)
        val edits = mapOf(
            "role typo" to good.replace("""role="related" />""", """role="relatd" />"""),
            "blank screen id" to good.replace("screen-id=\"screen_00002\"", "screen-id=\"\""),
            "unknown schema" to good.replace("""schema="v1"""", """schema="v99""""),
            "unknown element in a case" to good.replace("  </case>", "    <note>hi</note>\n  </case>"),
            "unknown element at the root" to good.replace("</identity-cases>", "  <extra />\n</identity-cases>"),
            "second raised-on screen" to good.replace(
                """<screen screen-id="screen_00002" role="related" />""",
                """<screen screen-id="screen_00002" role="raised-on" />""",
            ),
            "a screen listed twice" to good.replace(
                """<screen screen-id="screen_00005" role="related" />""",
                """<screen screen-id="screen_00002" role="related" />""",
            ),
        )
        edits.forEach { (what, text) ->
            assertTrue("fixture edit did not apply: $what", text != good)
            file.writeText(text)
            assertFailsNaming(if (what.contains("root") || what.contains("schema")) IdentityCaseStore.FILE_NAME else "case_001") {
                IdentityCaseStore.read(file)
            }
        }
    }

    @Test
    fun a_resume_fails_loudly_on_a_case_naming_a_screen_the_crawl_does_not_hold() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(proposalCase().copy(raisedOn = "screen_00000", relatedScreenIds = listOf("screen_00042"))),
        )
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_000")))

        assertFailsNaming("screen_00042") { SavedCrawlLoader.load(dir) }
    }

    /** Round-5 D2: an operator who breaks a case's screen XML is told which file, not that the case is wrong. */
    @Test
    fun a_resume_names_the_screen_file_it_could_not_read_when_a_case_involves_that_screen() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(proposalCase().copy(raisedOn = "screen_00000", relatedScreenIds = listOf("screen_00003"))),
        )
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_000")))
        File(dir, "screen_00003_cart.xml").writeText("<screen><crawl")

        assertFailsNaming("screen_00003_cart.xml could not be read") { SavedCrawlLoader.load(dir) }
    }

    @Test
    fun a_resume_fails_loudly_on_a_pointer_from_a_screen_the_case_does_not_involve() {
        val dir = tempFolder.newFolder("crawl")
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(proposalCase().copy(raisedOn = "screen_00001", relatedScreenIds = emptyList())),
        )
        File(dir, "screen_00000_cart.xml").writeText(serialize(settledIdentity(), listOf("case_000")))
        File(dir, "screen_00001_cart.xml").writeText(
            serialize(settledIdentity(), emptyList()).replace("screen_00000", "screen_00001"),
        )

        assertFailsNaming("case_000") { SavedCrawlLoader.load(dir) }
    }

    @Test
    fun refreshing_a_file_saved_with_windows_line_endings_keeps_them_and_still_refreshes() {
        val crlf = serialize(settledIdentity(), listOf("case_000")).replace("\n", "\r\n")

        assertEquals(
            serialize(settledIdentity(), emptyList()).replace("\n", "\r\n"),
            IdentityCasePointers.refresh(crlf, emptyList()),
        )
        assertEquals(
            serialize(settledIdentity(), listOf("case_000", "case_003")).replace("\n", "\r\n"),
            IdentityCasePointers.refresh(crlf, listOf("case_000", "case_003")),
        )
    }

    @Test
    fun the_same_open_case_is_returned_rather_than_opened_twice() {
        val tracker = tracker()
        val first = tracker.openCase(IdentityCaseKind.SETTLED_DOES_NOT_HOLD, "screen_00001", emptyList(), "x")
        val again = tracker.openCase(IdentityCaseKind.SETTLED_DOES_NOT_HOLD, "screen_00001", emptyList(), "x")
        val other = tracker.openCase(IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD, "screen_00001", emptyList(), "y")

        assertEquals(first, again)
        assertEquals(listOf("case_000", "case_001"), tracker.cases().map { it.id })
        assertEquals("case_001", other.id)
    }

    /** Round-3 D2: a reused case describes what failed this time, not the first time. */
    @Test
    fun a_reused_case_keeps_its_id_and_takes_the_new_detail_and_proposal() {
        val tracker = tracker()
        val proposal = TraitProposer.propose(LifecycleTrees.cart())
        tracker.openCase(IdentityCaseKind.SETTLED_DOES_NOT_HOLD, "screen_00001", emptyList(), "missing control gift wrap")

        val again = tracker.openCase(
            IdentityCaseKind.SETTLED_DOES_NOT_HOLD,
            "screen_00001",
            emptyList(),
            "missing control coupon",
            rejectedProposal = proposal,
        )

        assertEquals("case_000", again.id)
        assertEquals("missing control coupon", again.detail)
        assertEquals(proposal, again.rejectedProposal)
        assertEquals(listOf(again), tracker.cases())
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun tracker() = CrawlRunTracker(sessionId = "s", packageName = PKG, startedAt = 0L)

    private fun collision() = IdentityCase(
        id = "case_001",
        status = IdentityCaseStatus.OPEN,
        kind = IdentityCaseKind.UNIQUENESS_COLLISION,
        raisedOn = "screen_00007",
        relatedScreenIds = listOf("screen_00002", "screen_00005"),
        detail = "settled identities of screen_00002, screen_00005 all hold on this screen",
    )

    private fun proposalCase() = IdentityCase(
        id = "case_000",
        status = IdentityCaseStatus.OPEN,
        kind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
        raisedOn = "screen_00003",
        relatedScreenIds = listOf("screen_00001"),
        detail = "the proposal also holds on screen_00001 & <friends>",
        rejectedProposal = TraitProposer.propose(LifecycleTrees.cart()),
    )

    private fun settledIdentity(): ScreenIdentity =
        Lifecycle.settled(LifecycleTrees.cart(), Lifecycle.name("Cart"))

    private fun serialize(identity: ScreenIdentity, openCaseIds: List<String>): String {
        val root = LifecycleTrees.cart()
        val snapshot = ScreenSnapshot(
            screenName = "Cart",
            packageName = PKG,
            elements = emptyList(),
            xmlDump = "",
            stepSnapshots = listOf(ScrollCaptureStep(stepIndex = 0, root = root, newElementCount = 0)),
            scrollStepCount = 1,
        )
        return AccessibilityXmlSerializer.serialize(
            snapshot,
            ScreenCrawlState(
                screenId = "screen_00000",
                depth = 0,
                expansionStatus = ScreenExpansionStatus.COMPLETE,
                isRoot = true,
                screenIdentity = identity,
                parent = null,
                route = CrawlRoute(),
                runLevel = RunLevelState(
                    sessionId = "s",
                    startedAt = 0L,
                    finishedAt = null,
                    status = CrawlRunStatus.IN_PROGRESS,
                    maxDepthReached = 0,
                ),
                openCaseIds = openCaseIds,
            ),
        )
    }

    private fun assertFailsNaming(fragment: String, block: () -> Any?) {
        try {
            block()
            fail("Expected MalformedIdentityCases naming $fragment")
        } catch (expected: MalformedIdentityCases) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(fragment))
        }
    }

    private companion object {
        const val PKG = LifecycleTrees.PKG
    }
}
