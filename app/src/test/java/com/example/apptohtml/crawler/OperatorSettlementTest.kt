package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.CaseFolderFixtures.adaptive
import com.example.apptohtml.crawler.CaseFolderFixtures.battery
import com.example.apptohtml.crawler.CaseFolderFixtures.batterySaver
import com.example.apptohtml.crawler.CaseFolderFixtures.batteryUsage
import com.example.apptohtml.crawler.CaseFolderFixtures.case
import com.example.apptohtml.crawler.CaseFolderFixtures.cartList
import com.example.apptohtml.crawler.CaseFolderFixtures.elementOf
import com.example.apptohtml.crawler.CaseFolderFixtures.fingerprint
import com.example.apptohtml.crawler.CaseFolderFixtures.home
import com.example.apptohtml.crawler.CaseFolderFixtures.idlessScreen
import com.example.apptohtml.crawler.CaseFolderFixtures.lookalike
import com.example.apptohtml.crawler.CaseFolderFixtures.settingsFolder
import com.example.apptohtml.crawler.CaseFolderFixtures.traitsText
import com.example.apptohtml.crawler.CaseFolderFixtures.writeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The offline settle: whether an operator's traits hold, and writing them when they do.
 *
 * Every folder here is written by the crawler's own serializers, with real first viewports, so the
 * check reads exactly what a resume would.
 */
class OperatorSettlementTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun check(dir: File, caseId: String, text: String) =
        OperatorSettlement.check(CaseCrawl.load(dir), caseId, text)

    // ---- the check -----------------------------------------------------------------------------

    @Test
    fun `traits that hold two ways on an unsettled screen are HOLDS, stamped operator`() {
        val verdict = check(settingsFolder(tempFolder.newFolder()), "case_000", traitsText(batteryUsage))

        verdict as OperatorSettlement.Verdict.Holds
        assertEquals("screen_00001", verdict.screenId)
        assertEquals(listOf<Trait>(batteryUsage), verdict.candidate.traits)
        assertEquals(IdentityOrigin.OPERATOR, verdict.candidate.settledBy)
        assertEquals(2, verdict.checkedAgainst)
        assertEquals(ScreenIdentityReport.Status.HOLDS, verdict.status)
    }

    @Test
    fun `traits that do not hold on the screen's own capture are DOES_NOT_HOLD, naming the trait`() {
        val verdict = check(settingsFolder(tempFolder.newFolder()), "case_000", traitsText(HasControl(elementOf(home, "open_cart"))))

        verdict as OperatorSettlement.Verdict.FailsOnOwnScreen
        assertTrue(verdict.describe(), verdict.describe().contains("open_cart"))
    }

    @Test
    fun `traits that assert nothing present are UNSETTLED`() {
        val verdict = check(settingsFolder(tempFolder.newFolder()), "case_000", traitsText(LacksControl(elementOf(home, "open_cart"))))

        assertEquals(OperatorSettlement.Verdict.AssertsNothing, verdict)
    }

    @Test
    fun `a differently-named screen the traits also hold on is not a collision`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00003", "Battery", idlessScreen("Battery usage", "Health"))

        assertTrue(check(dir, "case_000", traitsText(batteryUsage)) is OperatorSettlement.Verdict.Holds)
    }

    @Test
    fun `traits that also hold on a same-named screen are NOT_UNIQUE in the reverse direction`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00003", "Battery", lookalike)
        assertSameName(dir)

        val verdict = check(dir, "case_000", traitsText(batteryUsage))

        assertEquals(OperatorSettlement.Verdict.NotUnique("screen_00001", forward = emptyList(), reverse = listOf("screen_00003")), verdict)
        assertEquals("NOT_UNIQUE for screen_00001: these traits also hold on screen_00003", verdict.describe())
    }

    @Test
    fun `a settled same-named screen whose identity holds here is NOT_UNIQUE in the forward direction`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00003", "Battery", lookalike, listOf(HasControl(elementOf(lookalike, "Battery usage"))))
        assertSameName(dir)

        val verdict = check(dir, "case_000", traitsText(adaptive))

        assertEquals(OperatorSettlement.Verdict.NotUnique("screen_00001", forward = listOf("screen_00003"), reverse = emptyList()), verdict)
        assertEquals(
            "NOT_UNIQUE for screen_00001: the settled identity of screen_00003 holds on this screen",
            verdict.describe(),
        )
    }

    @Test
    fun `a capture that cannot be read refuses the check, naming the screen`() {
        val dir = settingsFolder(tempFolder.newFolder())
        File(dir, "screen_00004_broken.xml").writeText("<screen><crawl")

        val verdict = check(dir, "case_000", traitsText(batteryUsage))

        verdict as OperatorSettlement.Verdict.CannotCheck
        assertEquals(listOf("screen_00004"), verdict.screenIds)
    }

    @Test
    fun `a known screen with no first viewport refuses the check rather than counting as unique`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00004", "Storage", tree = null)

        val verdict = check(dir, "case_000", traitsText(batteryUsage))

        verdict as OperatorSettlement.Verdict.CannotCheck
        assertEquals(listOf("screen_00004"), verdict.screenIds)
    }

    @Test
    fun `requests that cannot be judged are refused with a reason`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases + case("case_002", "screen_00001", status = IdentityCaseStatus.CLOSED),
        )
        val refusals = mapOf(
            check(dir, "case_009", traitsText(batteryUsage)) to "no case case_009",
            check(dir, "case_002", traitsText(batteryUsage)) to "closed",
            check(dir, "case_000", "<traits><has-vibes /></traits>") to "has-vibes",
            check(dir, "case_000", "<traits><unclosed>") to "not well-formed",
            check(dir, "case_000", "<identity />") to "one <traits> element",
            check(dir, "case_000", traitsText(batteryUsage).replace("<traits>", """<traits settled-by="operator">""")) to "settled-by",
        )

        refusals.forEach { (verdict, phrase) ->
            assertTrue("$verdict", verdict is OperatorSettlement.Verdict.NotSettleable)
            assertTrue(verdict.describe(), verdict.describe().contains(phrase))
            assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, verdict.status)
        }
    }

    @Test
    fun `the check agrees with the crawler's own two-way rule, computed independently`() {
        val reverseDir = settingsFolder(tempFolder.newFolder()).also { writeScreen(it, "screen_00003", "Battery", lookalike) }
        val forwardDir = settingsFolder(tempFolder.newFolder()).also {
            writeScreen(it, "screen_00003", "Battery", lookalike, listOf(HasControl(elementOf(lookalike, "Battery usage"))))
        }
        listOf(reverseDir, forwardDir).forEach(::assertAgreesWithTheCrawler)
    }

    private fun assertAgreesWithTheCrawler(dir: File) {
        val crawl = CaseCrawl.load(dir)
        val own = crawl.screen("screen_00001")!!
        val others = crawl.screens.filterNot { it.screenId == "screen_00001" }

        listOf(listOf(batteryUsage), listOf(adaptive), listOf(HasControl(elementOf(home, "open_cart")))).forEach { traits ->
            val candidate = own.capture.identity.copy(traits = traits, settledBy = IdentityOrigin.OPERATOR)
            val ownContext = TraitEvaluationContext.of(own.capture.firstViewport!!, candidate.name)
            val decision = IdentitySettlement.decide(
                candidate,
                ownContext,
                others.map { KnownScreen(it.screenId, it.capture.identity, it.capture.firstViewport) },
            )
            val forward = TraitEvaluator.matchKnownScreens(
                ownContext,
                others.associate { it.screenId to it.capture.identity } + ("screen_00001" to candidate),
            )
            val expectedHolds = decision == IdentitySettlement.Decision.Settled &&
                forward == ScreenTraitMatch.One("screen_00001", forward.unsettledScreenIds)

            val verdict = OperatorSettlement.check(crawl, "case_000", traitsText(*traits.toTypedArray()))

            assertEquals("$traits", expectedHolds, verdict is OperatorSettlement.Verdict.Holds)
            when (decision) {
                is IdentitySettlement.Decision.AlsoHolds ->
                    assertEquals(decision.screenIds, (verdict as OperatorSettlement.Verdict.NotUnique).reverse)
                is IdentitySettlement.Decision.FailsOnOwnScreen ->
                    assertEquals(decision.failing, (verdict as OperatorSettlement.Verdict.FailsOnOwnScreen).failing)
                else -> Unit
            }
            if (decision !is IdentitySettlement.Decision.FailsOnOwnScreen && decision != IdentitySettlement.Decision.AssertsNothing &&
                forward is ScreenTraitMatch.Ambiguous
            ) {
                assertEquals("$traits", forward.screenIds - "screen_00001", (verdict as OperatorSettlement.Verdict.NotUnique).forward)
            }
        }
    }

    @Test
    fun `an identity whose package does not match its own capture does not name its screen`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val batteryXml = File(dir, "screen_00001_battery.xml")
        listOf(batteryXml, SavedScreenFixtures.htmlOf(batteryXml)).forEach { file ->
            file.writeText(file.readText().replace("""<screen-identity package="${CaseFolderFixtures.PKG}"""", """<screen-identity package="com.other""""))
        }
        writeScreen(dir, "screen_00003", "Battery", lookalike, listOf(HasControl(elementOf(lookalike, "Battery usage"))))
        val before = fingerprint(dir)

        val verdict = check(dir, "case_000", traitsText(adaptive))
        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertEquals(OperatorSettlement.Verdict.DoesNotNameScreen("screen_00001"), verdict)
        assertEquals(ScreenIdentityReport.Status.DOES_NOT_HOLD, verdict.status)
        assertEquals(before, fingerprint(dir))
        assertTrue(outcome.verdict is OperatorSettlement.Verdict.DoesNotNameScreen)
    }

    @Test
    fun `the verdict does not depend on the order the screens are listed in`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00003", "Battery", lookalike)
        val crawl = CaseCrawl.load(dir)
        val reversed = CaseCrawl(crawl.directory, crawl.cases.reversed(), crawl.screens.reversed(), crawl.problems)

        listOf(batteryUsage, adaptive).forEach { trait ->
            assertEquals(
                OperatorSettlement.check(crawl, "case_000", traitsText(trait)),
                OperatorSettlement.check(reversed, "case_000", traitsText(trait)),
            )
        }
    }

    // ---- the settle ----------------------------------------------------------------------------

    @Test
    fun `a settle that holds writes both files as one operator identity and marks the case handled`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val batteryXml = File(dir, "screen_00001_battery.xml")
        val beforeXml = batteryXml.readText()
        val beforeHtml = SavedScreenFixtures.htmlOf(batteryXml).readText()

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = null)

        assertTrue("${outcome.problem}", outcome.succeeded)
        val reread = CaptureIdentitySource.load(batteryXml).getOrThrow()
        assertEquals(listOf<Trait>(batteryUsage), reread.identity.traits)
        assertEquals(IdentityOrigin.OPERATOR, reread.identity.settledBy)
        val case = IdentityCaseStore.read(IdentityCaseStore.fileIn(dir)).single { it.id == "case_000" }
        assertEquals(IdentityCaseStatus.HANDLED, case.status)
        assertNull(case.replaced)
        assertEquals(stripIdentity(beforeXml), stripIdentity(batteryXml.readText()))
        assertEquals(stripPageIdentity(beforeHtml), stripPageIdentity(SavedScreenFixtures.htmlOf(batteryXml).readText()))
        assertTrue(batteryXml.readText().contains("""<identity-case ref="case_000" />"""))
        assertEquals(listOf("screen_00001_battery.xml", "screen_00001_battery.html", IdentityCaseStore.FILE_NAME), outcome.written.map { it.name })
        assertEquals(emptyList<String>(), dir.listFiles()!!.filter { it.name.startsWith(".settle") }.map { it.name })
    }

    @Test
    fun `a settled file is byte-identical to one the crawler would serialize afresh`() {
        val dir = settingsFolder(tempFolder.newFolder())

        CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = null)

        val fresh = tempFolder.newFolder()
        val expectedIdentity = CaptureIdentitySource.load(File(dir, "screen_00001_battery.xml")).getOrThrow().identity
        writeScreen(fresh, "screen_00001", "Battery", battery, listOf(batteryUsage), IdentityOrigin.OPERATOR, listOf("case_000"))
        assertEquals(expectedIdentity.traits, listOf<Trait>(batteryUsage))
        assertEquals(File(fresh, "screen_00001_battery.xml").readText(), File(dir, "screen_00001_battery.xml").readText())
        assertEquals(File(fresh, "screen_00001_battery.html").readText(), File(dir, "screen_00001_battery.html").readText())
    }

    @Test
    fun `every refused settle leaves every file byte-identical`() {
        val requests = listOf(
            "case_000" to traitsText(HasControl(elementOf(home, "open_cart"))),
            "case_000" to traitsText(LacksControl(elementOf(home, "open_cart"))),
            "case_009" to traitsText(batteryUsage),
            "case_000" to "<traits><unclosed>",
        )
        requests.forEach { (caseId, text) ->
            val dir = settingsFolder(tempFolder.newFolder())
            val before = fingerprint(dir)

            val outcome = CaseSettleService.settle(dir, caseId, text, write = true, device = null)

            assertTrue(outcome.verdict !is OperatorSettlement.Verdict.Holds)
            assertEquals(emptyList<File>(), outcome.written)
            assertEquals(before, fingerprint(dir))
        }
        val folders = listOf(
            "reverse" to settingsFolder(tempFolder.newFolder()).also { writeScreen(it, "screen_00003", "Battery", lookalike) },
            "forward" to settingsFolder(tempFolder.newFolder()).also {
                writeScreen(it, "screen_00003", "Battery", lookalike, listOf(HasControl(elementOf(lookalike, "Battery usage"))))
            },
            "unreadable" to settingsFolder(tempFolder.newFolder()).also { File(it, "screen_00004_broken.xml").writeText("<screen><crawl") },
            "closed" to settingsFolder(tempFolder.newFolder()).also { dir ->
                IdentityCaseStore.write(
                    IdentityCaseStore.fileIn(dir),
                    CaseCrawl.load(dir).cases.map { if (it.id == "case_000") it.copy(status = IdentityCaseStatus.CLOSED) else it },
                )
            },
        )
        folders.forEach { (name, dir) ->
            val trait = if (name == "forward") adaptive else batteryUsage
            val before = fingerprint(dir)

            val outcome = CaseSettleService.settle(dir, "case_000", traitsText(trait), write = true, device = null)

            assertTrue("$name: ${outcome.verdict}", outcome.verdict !is OperatorSettlement.Verdict.Holds)
            assertEquals(name, before, fingerprint(dir))
        }
    }

    @Test
    fun `a check without write reports the same verdict and writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)

        val dryRun = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = false, device = null)

        assertEquals(check(dir, "case_000", traitsText(batteryUsage)), dryRun.verdict)
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a handled case can be settled again, replacing its traits and recording nothing as replaced`() {
        val dir = settingsFolder(tempFolder.newFolder())
        CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = null)

        val again = CaseSettleService.settle(dir, "case_000", traitsText(batterySaver), write = true, device = null)

        assertTrue("${again.problem}", again.succeeded)
        assertEquals(listOf<Trait>(batterySaver), CaptureIdentitySource.load(File(dir, "screen_00001_battery.xml")).getOrThrow().identity.traits)
        val case = IdentityCaseStore.read(IdentityCaseStore.fileIn(dir)).single { it.id == "case_000" }
        assertEquals(IdentityCaseStatus.HANDLED, case.status)
        assertNull(case.replaced)
    }

    @Test
    fun `settling a settled-does-not-hold case replaces the identity and records what it replaced`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val wider = HasList(LifecycleTrees.CART_ITEMS, 1, setOf(LifecycleTrees.TITLE))

        val outcome = CaseSettleService.settle(dir, "case_001", traitsText(wider), write = true, device = null)

        assertTrue("${outcome.problem}", outcome.succeeded)
        val case = IdentityCaseStore.read(IdentityCaseStore.fileIn(dir)).single { it.id == "case_001" }
        assertEquals(ReplacedIdentity(listOf(cartList), IdentityOrigin.MACHINE), case.replaced)
        val cartIdentity = CaptureIdentitySource.load(File(dir, "screen_00002_cart.xml")).getOrThrow().identity
        assertEquals(listOf<Trait>(wider), cartIdentity.traits)
        assertEquals(IdentityOrigin.OPERATOR, cartIdentity.settledBy)
    }

    @Test
    fun `settles over the same folder listed in two orders write the same bytes`() {
        val first = settingsFolder(tempFolder.newFolder())
        val second = settingsFolder(tempFolder.newFolder())
        val inOrder = CaseCrawl.load(first)
        val loaded = CaseCrawl.load(second)
        val reversed = CaseCrawl(loaded.directory, loaded.cases.reversed(), loaded.screens.reversed(), loaded.problems)

        listOf(inOrder, reversed).forEach { crawl ->
            val holds = OperatorSettlement.check(crawl, "case_000", traitsText(adaptive)) as OperatorSettlement.Verdict.Holds
            SettleWriter.write(crawl, crawl.case("case_000")!!, crawl.screen("screen_00001")!!, holds.candidate)
        }

        assertEquals(fingerprint(first), fingerprint(second))
    }

    @Test
    fun `two settles of the same folder and traits write the same bytes`() {
        val first = settingsFolder(tempFolder.newFolder())
        val second = settingsFolder(tempFolder.newFolder())

        CaseSettleService.settle(first, "case_000", traitsText(batteryUsage), write = true, device = null)
        CaseSettleService.settle(second, "case_000", traitsText(batteryUsage), write = true, device = null)

        assertEquals(fingerprint(first), fingerprint(second))
    }

    @Test
    fun `a move that fails part-way puts every file back and says nothing was written`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)
        val failOnPage = object : SettleFileOps by SettleFileOps.REAL {
            override fun move(from: File, to: File) {
                if (to.name.endsWith(".html")) throw java.nio.file.AccessDeniedException(to.path)
                SettleFileOps.REAL.move(from, to)
            }
        }

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null, fileOps = failOnPage)

        assertTrue("${outcome.problem}", outcome.problem!!.startsWith("Nothing written"))
        assertTrue(outcome.problem!!.contains("screen_00001_battery.html"))
        assertEquals(emptyList<File>(), outcome.written)
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a move that fails and cannot be undone says HALF WRITTEN and keeps the originals`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val stuck = object : SettleFileOps by SettleFileOps.REAL {
            override fun move(from: File, to: File) {
                if (to.name == IdentityCaseStore.FILE_NAME) throw java.io.IOException("disk full")
                SettleFileOps.REAL.move(from, to)
            }

            override fun restore(backup: File, target: File) = throw java.io.IOException("still locked")
        }

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null, fileOps = stuck)

        assertTrue("${outcome.problem}", outcome.problem!!.startsWith("HALF WRITTEN"))
        assertTrue(outcome.problem!!.contains("screen_00001_battery.xml"))
        assertTrue(File(dir, ".settle-case_000/screen_00001_battery.xml.before").isFile)
    }

    @Test
    fun `a settle whose files would not read back as meant writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val crawl = CaseCrawl.load(dir)
        val screen = crawl.screen("screen_00001")!!
        val unwritable = HasControl(
            ScreenElementIdentity(
                ElementFingerprint(resourceId = null, label = "bad\u0001label", className = "android.widget.Button", isListItem = false, checkable = false, editable = false),
                isBackAffordance = false,
            ),
        )
        val candidate = screen.capture.identity.copy(traits = listOf(unwritable), settledBy = IdentityOrigin.OPERATOR)
        val before = fingerprint(dir)

        try {
            SettleWriter.write(crawl, crawl.case("case_000")!!, screen, candidate)
            fail("a settle that does not read back as itself was written")
        } catch (refused: SettleWriteRefused) {
            assertTrue(refused.nothingWritten)
        }
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a reopened case settled again keeps the identity it first replaced`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val wider = HasList(LifecycleTrees.CART_ITEMS, 1, setOf(LifecycleTrees.TITLE))
        CaseSettleService.settle(dir, "case_001", traitsText(wider), write = true, device = null)
        val tracker = CrawlRunTracker.fromExistingState("s", CaseFolderFixtures.PKG, 0L, emptyList(), emptyList(), null, 0, 0, CaseCrawl.load(dir).cases)
        tracker.reopenCase("case_001", "settled identity does not hold on the live screen at resume")
        IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), tracker.cases())

        val again = CaseSettleService.settle(dir, "case_001", traitsText(cartList), write = true, device = null)

        assertTrue("${again.problem}", again.succeeded)
        assertEquals(
            ReplacedIdentity(listOf(cartList), IdentityOrigin.MACHINE),
            IdentityCaseStore.read(IdentityCaseStore.fileIn(dir)).single { it.id == "case_001" }.replaced,
        )
    }

    @Test
    fun `a settle over a refused case file is refused with the reason and writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.fileIn(dir).writeText("<identity-cases schema=\"v9\" />")
        val before = fingerprint(dir)

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertTrue("${outcome.verdict}", outcome.verdict is OperatorSettlement.Verdict.NotSettleable)
        assertTrue(outcome.verdict.describe(), outcome.verdict.describe().contains("the case file is refused"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a settle on a page saved with Windows line endings keeps them`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val page = File(dir, "screen_00001_battery.html")
        page.writeText(page.readText().replace("\n", "\r\n"))

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertTrue("${outcome.problem}", outcome.succeeded)
        val settled = page.readText()
        assertEquals("bare line feeds in a CRLF page", 0, Regex("(?<!\r)\n").findAll(settled).count())
        assertTrue(settled.contains("""<traits settled-by="operator">"""))
    }

    @Test
    fun `a case file that would not read back as meant writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val loaded = CaseCrawl.load(dir)
        val unwritable = loaded.cases.map { if (it.id == "case_000") it.copy(detail = "bad\u0001detail") else it }
        val crawl = CaseCrawl(loaded.directory, unwritable, loaded.screens, loaded.problems)
        val holds = OperatorSettlement.check(crawl, "case_000", traitsText(adaptive)) as OperatorSettlement.Verdict.Holds
        val before = fingerprint(dir)

        try {
            SettleWriter.write(crawl, crawl.case("case_000")!!, crawl.screen("screen_00001")!!, holds.candidate)
            fail("a case file that does not read back as written was written")
        } catch (refused: SettleWriteRefused) {
            assertTrue(refused.message, refused.message!!.contains(IdentityCaseStore.FILE_NAME))
        }
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a settle with no device says which files still need pushing`() {
        val dir = settingsFolder(tempFolder.newFolder())

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertEquals(outcome.written, outcome.toPush)
        assertEquals(3, outcome.toPush.size)
    }

    @Test
    fun `a push after an unpushed settle names both possible causes, not only a changed device`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir)
        CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        val outcome = CaseSettleService.settle(dir, "case_001", traitsText(HasList(LifecycleTrees.CART_ITEMS, 1, setOf(LifecycleTrees.TITLE))), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.problem!!.contains("an earlier settle here was written but not pushed"))
        assertTrue(outcome.problem!!.contains(IdentityCaseStore.FILE_NAME))
        assertEquals(emptyList<String>(), device.pushes)
    }

    @Test
    fun `a case id that could leave the crawl folder is refused and nothing outside it is touched`() {
        val root = tempFolder.newFolder()
        val dir = settingsFolder(File(root, "crawl").apply { mkdirs() })
        val victim = File(root, "zzvictim").apply { mkdirs() }
        File(victim, "keep.txt").writeText("keep")
        val caseFile = IdentityCaseStore.fileIn(dir)
        caseFile.writeText(caseFile.readText().replace("""id="case_000"""", """id="x/../../zzvictim""""))
        val before = fingerprint(dir)

        val outcome = CaseSettleService.settle(dir, "x/../../zzvictim", traitsText(adaptive), write = true, device = null)

        assertTrue("${outcome.verdict}", outcome.verdict is OperatorSettlement.Verdict.NotSettleable)
        assertTrue(outcome.verdict.describe(), outcome.verdict.describe().contains("is not a case id"))
        assertEquals("keep", File(victim, "keep.txt").readText())
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `the writer refuses a case id that could name a folder outside the crawl`() {
        val root = tempFolder.newFolder()
        val dir = settingsFolder(File(root, "crawl").apply { mkdirs() })
        // `.settle-x/../../zzvictim` resolves to a sibling of the crawl folder, which a settle deletes.
        val victim = File(root, "zzvictim").apply { mkdirs() }
        File(victim, "keep.txt").writeText("keep")
        val crawl = CaseCrawl.load(dir)
        val holds = OperatorSettlement.check(crawl, "case_000", traitsText(adaptive)) as OperatorSettlement.Verdict.Holds
        val before = fingerprint(dir)

        try {
            SettleWriter.write(crawl, crawl.case("case_000")!!.copy(id = "x/../../zzvictim"), crawl.screen("screen_00001")!!, holds.candidate)
            fail("a case id with a path in it reached the filesystem")
        } catch (refused: SettleWriteRefused) {
            assertTrue(refused.nothingWritten)
        }
        assertEquals("keep", File(victim, "keep.txt").readText())
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a missing page on another screen does not block a settle, as it does not block a resume`() {
        val dir = settingsFolder(tempFolder.newFolder())
        File(dir, "screen_00001_battery.html").delete()
        assertEquals(3, SavedCrawlLoader.load(dir)!!.screens.size)

        val outcome = CaseSettleService.settle(dir, "case_001", traitsText(HasList(LifecycleTrees.CART_ITEMS, 1, setOf(LifecycleTrees.TITLE))), write = true, device = null)

        assertTrue("${outcome.problem} ${outcome.verdict}", outcome.succeeded)
    }

    @Test
    fun `a settle on a screen whose own page is missing writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        File(dir, "screen_00001_battery.html").delete()
        val before = fingerprint(dir)

        val dryRun = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = false, device = null)
        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        // The check refuses, so a dry run never promises a settle that could not be written.
        listOf(dryRun, outcome).forEach { result ->
            assertTrue("${result.verdict}", result.verdict is OperatorSettlement.Verdict.NotSettleable)
            assertTrue(result.verdict.describe(), result.verdict.describe().contains("screen_00001_battery.html is missing"))
        }
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a settle keeps every other line ending of a file with mixed line endings`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val xml = File(dir, "screen_00001_battery.xml")
        val page = SavedScreenFixtures.htmlOf(xml)
        listOf(xml, page).forEach { file ->
            val text = file.readText()
            // Only the first line ends in CRLF: a file an editor touched on one line.
            file.writeText(text.replaceFirst("\n", "\r\n"))
        }
        val beforeXml = xml.readText()
        val beforePage = page.readText()

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertTrue("${outcome.problem}", outcome.succeeded)
        assertEquals(stripIdentity(beforeXml), stripIdentity(xml.readText()))
        assertEquals(stripPageIdentity(beforePage), stripPageIdentity(page.readText()))
        assertEquals(1, xml.readText().count { it == '\r' })
        assertEquals(1, page.readText().count { it == '\r' })
    }

    @Test
    fun `a settle while another holds the folder's lock is refused and writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)
        java.io.RandomAccessFile(CaseSettleService.lockFileFor(dir), "rw").use { access ->
            access.channel.lock().use {
                val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

                assertTrue("${outcome.verdict}", outcome.verdict is OperatorSettlement.Verdict.NotSettleable)
                assertTrue(outcome.verdict.describe(), outcome.verdict.describe().contains("another settle is running"))
            }
        }
        assertEquals(before, fingerprint(dir))
        assertTrue(CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null).succeeded)
    }

    @Test
    fun `a settle whose lock cannot be opened is refused with the reason, not thrown`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)
        val tmp = System.getProperty("java.io.tmpdir")
        val outcome = try {
            System.setProperty("java.io.tmpdir", File(tempFolder.root, "no-such-folder").path)
            CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)
        } finally {
            System.setProperty("java.io.tmpdir", tmp)
        }

        assertTrue("${outcome.verdict}", outcome.verdict is OperatorSettlement.Verdict.NotSettleable)
        assertTrue(outcome.verdict.describe(), outcome.verdict.describe().contains("cannot be opened"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `the lock lives outside the crawl folder and is the same file for the same folder`() {
        val dir = settingsFolder(tempFolder.newFolder())
        CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        val lock = CaseSettleService.lockFileFor(dir)
        assertFalse(lock.canonicalPath.startsWith(dir.canonicalPath))
        assertEquals(lock, CaseSettleService.lockFileFor(File(dir.path + File.separator + ".")))
        assertFalse(lock == CaseSettleService.lockFileFor(tempFolder.newFolder()))
    }

    @Test
    fun `a settle refuses rather than drop text that shares a line with the identity block`() {
        val edits = listOf<Pair<String, (String) -> String>>(
            "screen_00001_battery.xml" to { it.replace("    <screen-identity ", "    <!--keep--><screen-identity ") },
            "screen_00001_battery.xml" to { xml ->
                val end = xml.indexOf("</screen-identity>") + "</screen-identity>".length
                xml.substring(0, end) + "<!--keep>-->" + xml.substring(end)
            },
            "screen_00001_battery.html" to { it.replace("<script type=\"application/xml\"", "<!--keep--><script type=\"application/xml\"") },
        )
        edits.forEach { (name, edit) ->
            val dir = settingsFolder(tempFolder.newFolder())
            val file = File(dir, name).apply { writeText(edit(readText())) }
            assertTrue(file.readText().contains("<!--keep"))
            val before = fingerprint(dir)

            val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

            assertTrue("$name: ${outcome.problem}", outcome.problem!!.contains("other text shares a line with the identity block"))
            assertTrue("$name: ${outcome.problem}", outcome.problem!!.contains(file.absolutePath))
            assertEquals(name, before, fingerprint(dir))
        }
    }

    @Test
    fun `the check and the dry run both point at a settle that stopped part-way`() {
        val dir = settingsFolder(tempFolder.newFolder())
        File(dir, ".settle-case_001").mkdirs()
        File(dir, ".settle-case_001/screen_00002_cart.xml.before").writeText("original")

        val check = OperatorSettlement.check(CaseCrawl.load(dir), "case_000", traitsText(adaptive))
        val dryRun = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = false, device = null)

        assertEquals(check, dryRun.verdict)
        assertTrue(check.describe(), check.describe().contains("stopped part-way"))
    }

    @Test
    fun `a staging folder that kept no originals blocks nothing, and a settle clears it`() {
        val dir = settingsFolder(tempFolder.newFolder())
        // A settle killed before it kept any original leaves only its staged copies behind.
        File(dir, ".settle-case_000").mkdirs()
        File(dir, ".settle-case_000/screen_00001_battery.xml").writeText("staged, never moved")
        File(dir, ".settle-case_000/screen_00001_battery.xml.before.partial").writeText("half a backup")

        val check = OperatorSettlement.check(CaseCrawl.load(dir), "case_000", traitsText(adaptive))
        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertTrue("$check", check is OperatorSettlement.Verdict.Holds)
        assertTrue("${outcome.problem}", outcome.succeeded)
        assertFalse(File(dir, ".settle-case_000").exists())
    }

    @Test
    fun `a settle leaves no partial backup behind`() {
        val dir = settingsFolder(tempFolder.newFolder())

        CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)

        assertEquals(emptyList<String>(), dir.walkTopDown().filter { it.name.endsWith(".partial") || it.name.endsWith(".before") }.map { it.name }.toList())
    }

    // ---- pushing -------------------------------------------------------------------------------

    @Test
    fun `the push is allowed only when every device file matches the pulled copy`() {
        val local = mapOf("a.xml" to "1", "a.html" to "2", IdentityCaseStore.FILE_NAME to "3")

        assertEquals(PushDecision.Result.Allowed, PushDecision.decide(local, local))
        assertEquals(PushDecision.Result.Refused(listOf("a.html")), PushDecision.decide(local, local + ("a.html" to "9")))
        assertEquals(PushDecision.Result.Refused(listOf(IdentityCaseStore.FILE_NAME)), PushDecision.decide(local, local + (IdentityCaseStore.FILE_NAME to null)))
        assertEquals(PushDecision.Result.Allowed, PushDecision.decide(mapOf("x" to null), mapOf("x" to null)))
    }

    @Test
    fun `a push whose device copy moved on writes nothing locally and pushes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir).apply { files["screen_00001_battery.xml"] = "changed on device" }
        val before = fingerprint(dir)

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.problem!!.contains("the device crawl changed since the pull"))
        assertTrue(outcome.problem!!.contains("screen_00001_battery.xml"))
        assertEquals(before, fingerprint(dir))
        assertEquals(emptyList<String>(), device.pushes)
    }

    @Test
    fun `an unavailable device writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = FakeDevice(unavailableReason = "no device"))

        assertTrue("${outcome.problem}", outcome.problem!!.contains("no device"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a push that matches writes locally, pushes the three files and confirms them`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir)

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.succeeded)
        assertEquals(listOf("screen_00001_battery.xml", "screen_00001_battery.html", IdentityCaseStore.FILE_NAME), device.pushes)
        assertEquals(device.pushes, outcome.pushed)
    }

    @Test
    fun `a push whose device copies do not match what was written says so`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir).apply { corruptOn = "screen_00001_battery.html" }

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.problem!!.contains("do not match what was written"))
        assertTrue(outcome.problem!!.contains("screen_00001_battery.html"))
        assertFalse(outcome.succeeded)
    }

    @Test
    fun `a settle never clears the originals a HALF WRITTEN settle kept`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val stuck = object : SettleFileOps by SettleFileOps.REAL {
            override fun move(from: File, to: File) {
                if (to.name == IdentityCaseStore.FILE_NAME) throw java.io.IOException("disk full")
                SettleFileOps.REAL.move(from, to)
            }

            override fun restore(backup: File, target: File) = throw java.io.IOException("still locked")
        }
        CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null, fileOps = stuck)
        val kept = File(dir, ".settle-case_000/screen_00001_battery.xml.before")
        val keptBytes = kept.readBytes()

        val again = CaseSettleService.settle(dir, "case_000", traitsText(adaptive), write = true, device = null)
        val other = CaseSettleService.settle(dir, "case_001", traitsText(cartList), write = true, device = null)

        // Every settle, of this case or another, points at the kept originals until they are put back.
        listOf(again, other).forEach { outcome ->
            assertTrue("${outcome.verdict}", outcome.verdict.describe().contains("kept the originals in"))
            assertTrue(outcome.verdict.describe(), outcome.verdict.describe().contains(".settle-case_000"))
        }
        // The writer refuses on its own too, whoever calls it.
        val crawl = CaseCrawl.load(dir)
        try {
            SettleWriter.write(crawl, crawl.case("case_000")!!, crawl.screen("screen_00001")!!, crawl.screen("screen_00001")!!.capture.identity)
            fail("the writer cleared a HALF WRITTEN settle's originals")
        } catch (refused: SettleWriteRefused) {
            assertTrue(refused.message, refused.message!!.contains("HALF WRITTEN"))
        }
        assertTrue(kept.isFile)
        assertTrue(keptBytes.contentEquals(kept.readBytes()))
        assertNull(IdentityCaseStore.read(IdentityCaseStore.fileIn(dir)).single { it.id == "case_000" }.replaced)
    }

    @Test
    fun `a push that fails part-way says the files still need pushing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir).apply { failOn = "screen_00001_battery.html" }

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(batteryUsage), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.problem!!.contains("still need pushing"))
        assertEquals(listOf("screen_00001_battery.xml"), outcome.pushed)
        assertEquals(3, outcome.written.size)
    }


    /** Precondition for a collision: the crawler gives both Battery screens the same name key. */
    private fun assertSameName(dir: File) {
        val crawl = CaseCrawl.load(dir)
        assertEquals(
            DedupPolicy.nameKey(crawl.screen("screen_00001")!!.capture.identity),
            DedupPolicy.nameKey(crawl.screen("screen_00003")!!.capture.identity),
        )
    }

    private fun stripIdentity(xml: String): String {
        val span = ScreenIdentitySpan.find(xml, xml.indexOf("<crawl "))!!
        return xml.substring(0, span.lineStart) + xml.substring(span.end)
    }

    private fun stripPageIdentity(html: String): String {
        val start = html.indexOf("<script type=\"application/xml\"")
        return html.substring(0, start) + html.substring(html.indexOf("</script>", start))
    }
}
