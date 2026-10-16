package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Real captures, taken from a Settings crawl on an emulator (2026-09-18).
 *
 * Synthetic trees are built to be decoded correctly; these were not. They carry an oracle the reader
 * cannot fake: the device wrote a `fingerprint=` attribute on every visible pressable node as it
 * captured, so the identity rebuilt from the file can be checked against what the device itself
 * computed at capture time. Agreement means the reader reconstructed the tree the crawler had.
 */
class RealCaptureFixtureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the re-read identity matches the fingerprints the device wrote`() {
        FIXTURES.forEach { name ->
            val file = fixture("$name.xml")
            val root = CaptureTreeReader.readFirstViewport(file)
            assertNotNull("$name: the capture must yield a step-0 tree", root)

            val rebuilt = ScreenIdentity.fromRoot(root!!).elements
                .map { it.fingerprint.encoded }
                .toSet()
            val deviceWrote = deviceFingerprintsOfStepZero(file)

            assertTrue("$name: the fixture must carry device fingerprints", deviceWrote.isNotEmpty())
            assertEquals(
                "$name: the identity rebuilt from the file must equal what the device computed " +
                    "while capturing it. A mismatch means the reader reconstructed a different tree.",
                deviceWrote,
                rebuilt,
            )
        }
    }

    @Test
    fun `the re-read identity matches the one the capture recorded for itself`() {
        FIXTURES.forEach { name ->
            val file = fixture("$name.xml")
            val persisted = ScreenXmlReader.readHead(file)!!.screenIdentity
            val rebuilt = ScreenIdentity.fromRoot(CaptureTreeReader.readFirstViewport(file)!!)

            assertEquals(
                "$name: a screen's stored element set is built from its first viewport, so " +
                    "re-reading that viewport must reproduce it.",
                persisted.elements.map { it.fingerprint }.toSet(),
                rebuilt.elements.map { it.fingerprint }.toSet(),
            )
            assertEquals("$name: root class", persisted.rootClassName, rebuilt.rootClassName)
        }
    }

    @Test
    fun `a real capture proposes no traits, so it validates as UNSETTLED`() {
        val file = fixture("screen_00023_saved_devices.xml")
        val identity = ScreenXmlReader.readHead(file)!!.screenIdentity

        assertTrue(
            "This capture predates a2h-c2b.3, which made the crawler propose traits; it carries none.",
            identity.traits.isEmpty(),
        )
        assertEquals(
            TraitVerdict.UNSETTLED,
            TraitEvaluator.verdict(
                identity,
                TraitEvaluationContext.of(CaptureTreeReader.readFirstViewport(file)!!),
            ),
        )
    }

    @Test
    fun `a page written before the identity block is reported, not guessed past`() {
        // These pages come from the crawl that exposed the saveScreen bug: their XML carries the
        // identity but their HTML does not, because the page was written once and never rewritten.
        val result = CaptureIdentitySource.load(fixture("screen_00023_saved_devices.xml"))

        assertTrue("the pair must be refused", result.isFailure)
        val problem = (result.exceptionOrNull() as CaptureProblemException).problem
        assertTrue(
            "the failure must name the page, not the XML: ${problem.detail}",
            problem.file.name.endsWith(".html"),
        )
        assertTrue(
            "and say what is missing: ${problem.detail}",
            problem.detail.contains("screen-identity"),
        )
    }

    /**
     * The `fingerprint=` values the device wrote on step 0's visible pressable nodes.
     *
     * Read by text on purpose: going through the reader under test would make the comparison
     * circular.
     */
    private fun deviceFingerprintsOfStepZero(file: File): Set<String> {
        val xml = file.readText(Charsets.UTF_8)
        val stepZero = Regex("""<step index="0".*?</step>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.value
            .orEmpty()
        return Regex("""fingerprint="([^"]*)"""")
            .findAll(stepZero)
            .map { it.groupValues[1].replace("&amp;", "&").replace("&quot;", "\"") }
            .toSet()
    }

    /**
     * Copies a capture **and its page** into a temp directory, because a screen is the pair and the
     * loader looks for the sibling next to the XML.
     */
    private fun fixture(name: String): File {
        val dir = tempFolder.newFolder()
        val base = name.removeSuffix(".xml")
        listOf("$base.xml", "$base.html").forEach { each ->
            val stream = javaClass.getResourceAsStream("/captures/$each") ?: return@forEach
            val file = File(dir, each)
            stream.use { input -> file.outputStream().use(input::copyTo) }
        }
        return File(dir, "$base.xml")
    }

    /**
     * `a2h-c2b.3` scope call A on a real screen: the Settings list the `a2h-u68` evidence settled by
     * hand — every pressable row id-less — is proposed as the structure that held there, and the
     * proposal holds on the very capture it came from.
     */
    @Test
    fun `a real Settings list screen is proposed as its list, and the proposal holds on itself`() {
        val file = fixture("screen_00001_network_internet.xml")
        val root = CaptureTreeReader.readFirstViewport(file)!!

        val traits = TraitProposer.propose(root)
        val list = traits.filterIsInstance<HasList>()
            .single { it.containerResourceId == "com.android.settings:id/recycler_view" }

        assertTrue(list.rowChildResourceIds.toString(), "android:id/title" in list.rowChildResourceIds)
        assertEquals(1, list.minRows)
        assertTrue(
            "no id-less row may be proposed as a control",
            traits.filterIsInstance<HasControl>().none { it.element.fingerprint.resourceId.isNullOrBlank() },
        )
        val name = ScreenXmlReader.readHead(file)!!.screenIdentity.name
        assertEquals(
            TraitVerdict.HOLDS,
            TraitEvaluator.verdict(
                ScreenIdentity.fromRoot(root).copy(traits = traits),
                TraitEvaluationContext.of(root, name),
            ),
        )
    }

    private companion object {
        val FIXTURES = listOf(
            "screen_00023_saved_devices",
            "screen_00019_vpn",
            "screen_00001_network_internet",
        )
    }
}
