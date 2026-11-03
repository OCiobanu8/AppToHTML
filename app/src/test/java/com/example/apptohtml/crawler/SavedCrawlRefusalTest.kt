package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.SavedScreenFixtures.PACKAGE
import com.example.apptohtml.crawler.SavedScreenFixtures.htmlOf
import com.example.apptohtml.crawler.SavedScreenFixtures.identity
import com.example.apptohtml.crawler.SavedScreenFixtures.writeScreen
import com.example.apptohtml.storage.SavedCrawlRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A resume never silently drops or later overwrites what an operator wrote into a screen's identity.
 *
 * The first two pins are **red on pre-change code**: the loader skipped a screen whose identity it
 * could not build, and never read the page, so a disagreeing pair loaded and the next rewrite
 * replaced the page's copy.
 */
class SavedCrawlRefusalTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun list(minRows: Int) = HasList(
        containerResourceId = "$PACKAGE:id/list",
        minRows = minRows,
        rowChildResourceIds = setOf("$PACKAGE:id/title"),
    )

    @Test
    fun resume_refusesAScreenWhoseTraitsAreRefused() {
        val dir = crawlWithChild()
        val child = childXml(dir)
        editBoth(child) { it.replace("""min-rows="2"""", """min-rows="0"""") }

        val refusal = loadRefusal(dir)

        assertTrue(refusal, refusal.contains(child.absolutePath))
        assertTrue(refusal, refusal.contains("minRows must be at least 1"))
    }

    @Test
    fun resume_refusesAScreenWhoseTraitsCarryNoStamp() {
        val dir = crawlWithChild()
        val child = childXml(dir)
        editBoth(child) { it.replace("""<traits settled-by="operator">""", "<traits>") }

        val refusal = loadRefusal(dir)

        assertTrue(refusal, refusal.contains(child.absolutePath))
        assertTrue(refusal, refusal.contains("settled-by"))
    }

    @Test
    fun resume_refusesAScreenWhoseTraitsCarryAnUnknownStamp() {
        val dir = crawlWithChild()
        val child = childXml(dir)
        editBoth(child) { it.replace("""<traits settled-by="operator">""", """<traits settled-by="me">""") }

        val refusal = loadRefusal(dir)

        assertTrue(refusal, refusal.contains(child.absolutePath))
        assertTrue(refusal, refusal.contains("""settled-by="me""""))
    }

    @Test
    fun resume_refusesAScreenWhoseXmlAndHtmlIdentitiesDisagree() {
        val dir = crawlWithChild()
        val child = childXml(dir)
        val page = htmlOf(child)
        page.writeText(page.readText().replace("""min-rows="2"""", """min-rows="3""""))

        val refusal = loadRefusal(dir)

        assertTrue(refusal, refusal.contains(child.absolutePath))
        assertTrue(refusal, refusal.contains(page.absolutePath))
        assertTrue(refusal, refusal.contains("trait 1 differs"))
    }

    @Test
    fun resume_refusesAPageWhoseOwnIdentityIsRefused() {
        val dir = crawlWithChild()
        val page = htmlOf(childXml(dir))
        page.writeText(page.readText().replace("""min-rows="2"""", """min-rows="0""""))

        val refusal = loadRefusal(dir)

        assertTrue(refusal, refusal.contains(page.absolutePath))
        assertTrue(refusal, refusal.contains("minRows must be at least 1"))
    }

    @Test
    fun resume_loadsAScreenWhosePageIsMissing_becauseNoEditCanBeLost() {
        val dir = crawlWithChild()
        htmlOf(childXml(dir)).delete()

        val loaded = SavedCrawlLoader.load(dir)

        assertNotNull(loaded)
        assertEquals(2, loaded!!.screens.size)
    }

    @Test
    fun resume_loadsAgreeingFiles_withTheirOriginIntact() {
        val dir = crawlWithChild()

        val child = SavedCrawlLoader.load(dir)!!.screens.single { it.screenId == "screen_00001" }

        assertEquals(IdentityOrigin.OPERATOR, child.identity.settledBy)
        assertEquals(listOf(list(2)), child.identity.traits)
    }

    @Test
    fun savedCrawlPanel_showsEachRefusalAsItsLoadError_insteadOfCrashing() {
        val refusedDir = crawlWithChild()
        editBoth(childXml(refusedDir)) { it.replace("""min-rows="2"""", """min-rows="0"""") }
        val disagreeingDir = crawlWithChild()
        htmlOf(childXml(disagreeingDir)).let { page ->
            page.writeText(page.readText().replace("""min-rows="2"""", """min-rows="3""""))
        }

        listOf(refusedDir, disagreeingDir).forEach { dir ->
            val snapshot = SavedCrawlRepository { dir }.snapshot(PACKAGE)

            assertNotNull(snapshot)
            assertEquals(emptyList<Any>(), snapshot!!.screens)
            assertTrue("${snapshot.loadError}", snapshot.loadError!!.contains(childXml(dir).absolutePath))
        }
    }

    private fun crawlWithChild(): File {
        val dir = tempFolder.newFolder()
        writeScreen(dir, "screen_00000", "Home", identity("Home"))
        writeScreen(dir, "screen_00001", "Orders", identity("Orders", traits = listOf(list(2))))
        return dir
    }

    private fun childXml(dir: File): File = dir.listFiles()!!.single { it.name == "screen_00001_orders.xml" }

    private fun editBoth(xml: File, edit: (String) -> String) {
        listOf(xml, htmlOf(xml)).forEach { file -> file.writeText(edit(file.readText())) }
    }

    /** The loader's refusal message, failing the test when the loader accepted the crawl instead. */
    private fun loadRefusal(dir: File): String {
        val loaded = try {
            SavedCrawlLoader.load(dir)
        } catch (refused: IllegalStateException) {
            return refused.message.orEmpty()
        }
        fail(
            "The resume accepted the crawl and kept ${loaded?.screens?.map { it.screenId }}: " +
                "an operator's edit was dropped or will be overwritten without a word.",
        )
        error("unreachable")
    }
}
