package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.SavedScreenFixtures.PACKAGE
import com.example.apptohtml.crawler.SavedScreenFixtures.identity
import com.example.apptohtml.crawler.SavedScreenFixtures.writeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `open -> handled -> closed`: how a case's status is written, read, pointed at and moved. */
class IdentityCaseStatusTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val list = HasList(
        containerResourceId = "$PACKAGE:id/list",
        minRows = 2,
        rowChildResourceIds = setOf("$PACKAGE:id/title"),
    )

    private fun case(
        id: String = "case_000",
        status: IdentityCaseStatus,
        raisedOn: String = "screen_00001",
        related: List<String> = emptyList(),
        replaced: ReplacedIdentity? = null,
    ) = IdentityCase(
        id = id,
        status = status,
        kind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
        raisedOn = raisedOn,
        relatedScreenIds = related,
        detail = "the proposal asserts nothing",
        replaced = replaced,
    )

    @Test
    fun `a handled case round-trips through the case file and keeps its screen's pointer through the loader`() {
        val dir = tempFolder.newFolder()
        writeScreen(dir, "screen_00000", "Home", identity("Home"))
        writeScreen(dir, "screen_00001", "Battery", identity("Battery", listOf(list)), activeCaseIds = listOf("case_000"))
        val handled = case(status = IdentityCaseStatus.HANDLED)
        IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), listOf(handled))

        val loaded = SavedCrawlLoader.load(dir)!!

        assertTrue(IdentityCaseStore.fileIn(dir).readText().contains("""status="handled""""))
        assertEquals(listOf(handled), loaded.cases)
        val tracker = trackerOf(loaded)
        assertEquals(listOf("case_000"), tracker.activeCaseIdsFor("screen_00001"))
    }

    @Test
    fun `the loader accepts a pointer to an open case and to a handled one`() {
        listOf(IdentityCaseStatus.OPEN, IdentityCaseStatus.HANDLED).forEach { status ->
            val dir = tempFolder.newFolder()
            writeScreen(dir, "screen_00000", "Home", identity("Home"))
            writeScreen(dir, "screen_00001", "Battery", identity("Battery"), activeCaseIds = listOf("case_000"))
            IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), listOf(case(status = status)))

            assertEquals(2, SavedCrawlLoader.load(dir)!!.screens.size)
        }
    }

    @Test
    fun `the loader refuses a pointer to a closed case and to a missing one, naming the screen file`() {
        listOf(listOf(case(status = IdentityCaseStatus.CLOSED)), listOf(case(id = "case_001", status = IdentityCaseStatus.OPEN)))
            .forEach { cases ->
                val dir = tempFolder.newFolder()
                writeScreen(dir, "screen_00000", "Home", identity("Home"))
                val battery = writeScreen(dir, "screen_00001", "Battery", identity("Battery"), activeCaseIds = listOf("case_000"))
                IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), cases)

                try {
                    SavedCrawlLoader.load(dir)
                    fail("a pointer to a case that is not active was accepted: $cases")
                } catch (refused: MalformedIdentityCases) {
                    assertTrue(refused.message, refused.message!!.contains(battery.absolutePath))
                    assertTrue(refused.message, refused.message!!.contains("case_000"))
                }
            }
    }

    @Test
    fun `a replaced identity round-trips with who had settled it, and its traits carry no stamp`() {
        val file = IdentityCaseStore.fileIn(tempFolder.newFolder())
        val withReplaced = case(
            status = IdentityCaseStatus.HANDLED,
            replaced = ReplacedIdentity(listOf(list), IdentityOrigin.MACHINE),
        )

        IdentityCaseStore.write(file, listOf(withReplaced))
        val text = file.readText()

        assertTrue(text, text.contains("""<replaced settled-by="machine">"""))
        assertTrue(text, text.substringAfter("<replaced").contains("\n      <traits>\n"))
        assertEquals(listOf(withReplaced), IdentityCaseStore.read(file))
    }

    @Test
    fun `a replaced identity that names nobody, or asserts nothing, is refused naming the case`() {
        val refusals = listOf(
            """<replaced><traits><has-control><element label="a" resource-id="x" class="c" list-item="false" checkable="false" editable="false" back="false" /></has-control></traits></replaced>""",
            """<replaced settled-by="machine"></replaced>""",
            """<replaced settled-by="machine"><traits settled-by="machine"><has-control><element label="a" resource-id="x" class="c" list-item="false" checkable="false" editable="false" back="false" /></has-control></traits></replaced>""",
        )
        refusals.forEach { replacedXml ->
            val file = IdentityCaseStore.fileIn(tempFolder.newFolder())
            IdentityCaseStore.write(file, listOf(case(status = IdentityCaseStatus.HANDLED)))
            file.writeText(file.readText().replace("  </case>", "    $replacedXml\n  </case>"))

            try {
                IdentityCaseStore.read(file)
                fail("accepted $replacedXml")
            } catch (refused: MalformedIdentityCases) {
                assertTrue(refused.message, refused.message!!.contains("case_000"))
            }
        }
    }

    @Test
    fun `refreshing pointers keeps every other line ending of a mixed file`() {
        val dir = tempFolder.newFolder()
        val xml = writeScreen(dir, "screen_00001", "Battery", identity("Battery", listOf(list)), activeCaseIds = listOf("case_000"))
        // One CRLF line above the crawl block, the rest LF: an editor touched one line.
        val mixed = xml.readText().replaceFirst("\n", "\r\n")

        val refreshed = IdentityCasePointers.refresh(mixed, emptyList())

        assertEquals(mixed.replace("""    <identity-case ref="case_000" />""" + "\n", ""), refreshed)
        assertEquals(1, refreshed.count { it == '\r' })
        assertEquals(mixed, IdentityCasePointers.refresh(refreshed, listOf("case_000")))
    }

    @Test
    fun `closing keeps the case as history and drops it from every involved screen's pointers`() {
        val tracker = trackerWith(
            case(status = IdentityCaseStatus.HANDLED, related = listOf("screen_00002")),
            case(id = "case_001", status = IdentityCaseStatus.OPEN, raisedOn = "screen_00002"),
        )

        val closed = tracker.closeCase("case_000")

        assertEquals(IdentityCaseStatus.CLOSED, closed.status)
        assertEquals(listOf("case_000", "case_001"), tracker.cases().map { it.id })
        assertEquals(emptyList<String>(), tracker.activeCaseIdsFor("screen_00001"))
        assertEquals(listOf("case_001"), tracker.activeCaseIdsFor("screen_00002"))
    }

    @Test
    fun `reopening a handled case keeps its id and says why`() {
        val tracker = trackerWith(case(status = IdentityCaseStatus.HANDLED))

        val reopened = tracker.reopenCase("case_000", "the identity does not hold on the live screen: has-list x")

        assertEquals("case_000", reopened.id)
        assertEquals(IdentityCaseStatus.OPEN, reopened.status)
        assertEquals("the identity does not hold on the live screen: has-list x", tracker.cases().single().detail)
        assertEquals(emptyList<IdentityCase>(), tracker.handledCasesOn("screen_00001"))
    }

    @Test
    fun `a settled identity failing on a screen whose case was reopened refreshes that case and keeps its proposal`() {
        val tracker = trackerWith(case(status = IdentityCaseStatus.OPEN).copy(rejectedProposal = listOf(list)))

        val again = tracker.openCase(
            kind = IdentityCaseKind.SETTLED_DOES_NOT_HOLD,
            raisedOn = "screen_00001",
            relatedScreenIds = emptyList(),
            detail = "settled identity does not hold on replay: missing control x",
        )

        assertEquals("case_000", again.id)
        assertEquals(1, tracker.cases().size)
        assertEquals(IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD, again.kind)
        assertEquals("settled identity does not hold on replay: missing control x", again.detail)
        assertEquals(listOf<Trait>(list), again.rejectedProposal)
    }

    @Test
    fun `only handled cases raised on a screen are confirmed there`() {
        val tracker = trackerWith(
            case(id = "case_000", status = IdentityCaseStatus.HANDLED),
            case(id = "case_001", status = IdentityCaseStatus.OPEN),
            case(id = "case_002", status = IdentityCaseStatus.HANDLED, raisedOn = "screen_00002", related = listOf("screen_00001")),
            case(id = "case_003", status = IdentityCaseStatus.CLOSED),
        )

        assertEquals(listOf("case_000"), tracker.handledCasesOn("screen_00001").map { it.id })
    }

    private fun trackerWith(vararg cases: IdentityCase): CrawlRunTracker = CrawlRunTracker.fromExistingState(
        sessionId = "s",
        packageName = PACKAGE,
        startedAt = 0L,
        screens = emptyList(),
        edges = emptyList(),
        rootScreenId = null,
        nextScreenSequence = 0,
        nextEdgeSequence = 0,
        cases = cases.toList(),
    )

    private fun trackerOf(loaded: LoadedCrawlState): CrawlRunTracker = CrawlRunTracker.fromExistingState(
        sessionId = "s",
        packageName = PACKAGE,
        startedAt = 0L,
        screens = loaded.screens,
        edges = loaded.edges,
        rootScreenId = loaded.rootScreenId,
        nextScreenSequence = loaded.nextScreenSequence,
        nextEdgeSequence = loaded.nextEdgeSequence,
        cases = loaded.cases,
    )
}
