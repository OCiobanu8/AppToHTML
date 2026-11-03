package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.SavedScreenFixtures.PACKAGE
import com.example.apptohtml.crawler.SavedScreenFixtures.htmlOf
import com.example.apptohtml.crawler.SavedScreenFixtures.identity
import com.example.apptohtml.crawler.SavedScreenFixtures.writeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Who settled an identity is recorded on its `<traits>` block, read back exactly, and never guessed.
 */
class IdentityOriginTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val list = HasList(
        containerResourceId = "$PACKAGE:id/list",
        minRows = 2,
        rowChildResourceIds = setOf("$PACKAGE:id/title"),
    )

    @Test
    fun `an operator stamp round-trips through the loader and a rewrite reproduces both files`() {
        val dir = tempFolder.newFolder("crawl")
        val operatorSettled = identity("Home", traits = listOf(list), settledBy = IdentityOrigin.OPERATOR)
        val xml = writeScreen(dir, "screen_00000", "Home", operatorSettled)
        val originalXml = xml.readText()
        val originalHtml = htmlOf(xml).readText()

        val record = SavedCrawlLoader.load(dir)!!.screens.single()

        assertEquals(IdentityOrigin.OPERATOR, record.identity.settledBy)
        assertTrue(originalXml.contains("""<traits settled-by="operator">"""))
        assertTrue(originalHtml.contains("""<traits settled-by="operator">"""))
        val snapshot = SavedScreenFixtures.snapshot("Home")
        assertEquals(
            originalXml,
            AccessibilityXmlSerializer.serialize(snapshot, SavedScreenFixtures.crawlState("screen_00000", record.identity)),
        )
        assertEquals(originalHtml, HtmlRenderer.render(snapshot, emptyMap(), record.identity))
    }

    @Test
    fun `a machine stamp is written and read as machine`() {
        val block = AccessibilityXmlSerializer.screenIdentityBlock(
            identity("Home", traits = listOf(list), settledBy = IdentityOrigin.MACHINE),
        )

        assertTrue(block, block.contains("""<traits settled-by="machine">"""))
        assertEquals(IdentityOrigin.MACHINE, ScreenIdentityXml.parse(parse(block)).settledBy)
    }

    @Test
    fun `an identity asserting nothing writes no traits block and no stamp`() {
        val block = AccessibilityXmlSerializer.screenIdentityBlock(identity("Home"))

        assertFalse(block, block.contains("<traits"))
        assertFalse(block, block.contains("settled-by"))
        assertNull(ScreenIdentityXml.parse(parse(block)).settledBy)
    }

    @Test
    fun `traits nobody stamped cannot be written as a screen identity`() {
        try {
            AccessibilityXmlSerializer.screenIdentityBlock(identity("Home", traits = listOf(list), settledBy = null))
            fail("an unstamped screen identity was written")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("who settled"))
        }
    }

    @Test
    fun `the tool refuses a traits block with no stamp, naming the stamp`() {
        val xml = writeHandEdited(stamp = "")

        val problem = CaptureIdentitySource.load(xml).exceptionOrNull()

        assertTrue("$problem", problem is CaptureProblemException)
        assertTrue("$problem", problem!!.message!!.contains("no settled-by stamp"))
    }

    @Test
    fun `the tool refuses a traits block with an unknown stamp, naming it`() {
        val xml = writeHandEdited(stamp = """ settled-by="me"""")

        val problem = CaptureIdentitySource.load(xml).exceptionOrNull()

        assertTrue("$problem", problem is CaptureProblemException)
        assertTrue("$problem", problem!!.message!!.contains("""settled-by="me""""))
    }

    @Test
    fun `an empty traits block with an unknown stamp is still refused`() {
        val element = parse(
            """<screen-identity package="$PACKAGE" root-class="r"><traits settled-by="bogus"></traits></screen-identity>""",
        )

        try {
            ScreenIdentityXml.parse(element)
            fail("an unknown stamp was accepted on an empty block")
        } catch (refused: MalformedScreenIdentity) {
            assertTrue(refused.message, refused.message!!.contains("bogus"))
        }
    }

    @Test
    fun `an empty traits block with no stamp on a screen identity is refused, as any unstamped block is`() {
        val element = parse("""<screen-identity package="$PACKAGE" root-class="r"><traits></traits></screen-identity>""")

        try {
            ScreenIdentityXml.parse(element)
            fail("an unstamped empty block was accepted")
        } catch (refused: MalformedScreenIdentity) {
            assertTrue(refused.message, refused.message!!.contains("no settled-by stamp"))
        }
    }

    @Test
    fun `an empty traits block asserts nothing and credits nobody`() {
        val element = parse(
            """<screen-identity package="$PACKAGE" root-class="r"><traits settled-by="operator"></traits></screen-identity>""",
        )

        val read = ScreenIdentityXml.parse(element)

        assertEquals(emptyList<Trait>(), read.traits)
        assertNull(read.settledBy)
    }

    @Test
    fun `files that differ only in who settled them disagree, and the difference is named`() {
        val dir = tempFolder.newFolder("crawl")
        val xml = writeScreen(dir, "screen_00000", "Home", identity("Home", listOf(list), IdentityOrigin.OPERATOR))
        val html = htmlOf(xml)
        html.writeText(
            html.readText().replace("""<traits settled-by="operator">""", """<traits settled-by="machine">"""),
        )

        val problem = CaptureIdentitySource.load(xml).exceptionOrNull()

        assertTrue("$problem", problem is CaptureProblemException)
        assertTrue("$problem", problem!!.message!!.contains("settled by operator in the XML but machine in the page"))
    }

    @Test
    fun `identities that differ only in origin are not equal`() {
        val machine = identity("Home", listOf(list), IdentityOrigin.MACHINE)
        val operator = identity("Home", listOf(list), IdentityOrigin.OPERATOR)

        assertFalse(machine == operator)
        assertEquals(machine, operator.copy(settledBy = IdentityOrigin.MACHINE))
        assertEquals(machine.hashCode(), operator.copy(settledBy = IdentityOrigin.MACHINE).hashCode())
    }

    @Test
    fun `a case's rejected proposal is written with no stamp and round-trips`() {
        val dir = tempFolder.newFolder("crawl")
        val case = IdentityCase(
            id = "case_000",
            status = IdentityCaseStatus.OPEN,
            kind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
            raisedOn = "screen_00001",
            relatedScreenIds = emptyList(),
            detail = "the proposal also holds on screen_00002",
            rejectedProposal = listOf(list),
        )
        val file = IdentityCaseStore.fileIn(dir)

        IdentityCaseStore.write(file, listOf(case))

        assertFalse(file.readText(), file.readText().contains("settled-by"))
        assertEquals(listOf(case), IdentityCaseStore.read(file))
    }

    @Test
    fun `a stamped rejected proposal is refused, naming the case`() {
        val dir = tempFolder.newFolder("crawl")
        val file = IdentityCaseStore.fileIn(dir)
        IdentityCaseStore.write(
            file,
            listOf(
                IdentityCase(
                    id = "case_000",
                    status = IdentityCaseStatus.OPEN,
                    kind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
                    raisedOn = "screen_00001",
                    relatedScreenIds = emptyList(),
                    detail = "d",
                    rejectedProposal = listOf(list),
                ),
            ),
        )
        file.writeText(file.readText().replace("<traits>", """<traits settled-by="operator">"""))

        try {
            IdentityCaseStore.read(file)
            fail("a stamped rejected proposal was read")
        } catch (expected: MalformedIdentityCases) {
            assertTrue(expected.message, expected.message!!.contains("case_000"))
            assertTrue(expected.message, expected.message!!.contains("settled-by"))
        }
    }

    /** A screen whose `.xml` and `.html` both carry a hand-written `<traits[stamp]>` block. */
    private fun writeHandEdited(stamp: String): java.io.File {
        val dir = tempFolder.newFolder("crawl")
        val xml = writeScreen(dir, "screen_00000", "Home", identity("Home", listOf(list), IdentityOrigin.OPERATOR))
        listOf(xml, htmlOf(xml)).forEach { file ->
            file.writeText(file.readText().replace("""<traits settled-by="operator">""", "<traits$stamp>"))
        }
        return xml
    }

    private fun parse(xml: String) = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(xml.byteInputStream())
        .documentElement
}
