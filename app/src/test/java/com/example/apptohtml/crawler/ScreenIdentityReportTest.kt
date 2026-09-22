package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The tool's operational contract: it observes, and it repeats.
 *
 * Both properties matter more than they sound. A tool that edited the files an operator is settling
 * would destroy the thing under test, and a report that differed between runs could not be diffed —
 * so it could not be evidence of anything.
 */
class ScreenIdentityReportTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a run leaves every input byte-identical`() {
        val dir = crawlOf("alpha", "beta")
        val before = fingerprintOf(dir)

        ScreenIdentityReport.validate(
            targetXml = File(dir, "alpha.xml"),
            observedXml = File(dir, "alpha.xml"),
            knownScreensDir = dir,
            isRootScreen = false,
        )

        assertEquals(
            "The captures under test must come out exactly as they went in.",
            before,
            fingerprintOf(dir),
        )
    }

    @Test
    fun `a run writes nothing into the input directory`() {
        val dir = crawlOf("alpha")
        val namesBefore = dir.list()!!.sorted()

        ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(
            "No report, no scratch file, nothing beside the operator's captures.",
            namesBefore,
            dir.list()!!.sorted(),
        )
    }

    @Test
    fun `two runs on the same inputs produce the same report`() {
        val dir = crawlOf("alpha", "beta")

        val first = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)
        val second = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(first.status, second.status)
        assertEquals("A report that cannot be diffed twice is not evidence.", first.text, second.text)
    }

    @Test
    fun `the report does not depend on the order captures are found in`() {
        // Two directories with the same screens, whose listings differ by name order. The known
        // screens reach the validator in different orders, and the verdict must not notice.
        val forward = crawlOf("alpha", "beta")
        val reversed = crawlOf("beta", "alpha")

        val a = ScreenIdentityReport.validate(File(forward, "alpha.xml"), File(forward, "alpha.xml"), forward, false)
        val b = ScreenIdentityReport.validate(File(reversed, "alpha.xml"), File(reversed, "alpha.xml"), reversed, false)

        assertEquals(a.status, b.status)
        assertEquals(a.text, b.text)
    }

    @Test
    fun `a capture with no identity block is INVALID_INPUT naming the file`() {
        val dir = crawlOf("alpha")
        val html = File(dir, "alpha.html")
        html.writeText(html.readText().replace("screen-identity", "something-else"))

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, outcome.status)
        assertTrue("must name the file: ${outcome.text}", outcome.text.contains("alpha.html"))
    }

    @Test
    fun `one unreadable capture among the known screens is reported, not skipped`() {
        val dir = crawlOf("alpha", "beta")
        File(dir, "beta.html").delete()

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(
            "Quietly dropping a known screen would weaken the uniqueness answer without saying so.",
            ScreenIdentityReport.Status.INVALID_INPUT,
            outcome.status,
        )
        assertTrue(outcome.text.contains("beta.html"))
    }

    @Test
    fun `a screen id is its file name, so two known screens cannot share one`() {
        // The SCOPE asks for duplicate screen ids to be INVALID_INPUT. They are unreachable: the id
        // is the file base name, and a directory cannot hold two files with one name. This pins the
        // reason, so deriving the id from anything else turns it red and the guard becomes needed.
        val dir = crawlOf("alpha", "beta")
        File(dir, "alpha.xml").copyTo(File(dir, "alpha-copy.xml"))
        File(dir, "alpha.html").copyTo(File(dir, "alpha-copy.html"))

        val ids = ScreenIdentityReport.loadKnownScreens(dir).mapNotNull { it.getOrNull() }
            .map { it.screenId }

        assertEquals(
            "A copied capture is a distinct screen id, because the id follows the file name.",
            listOf("alpha", "alpha-copy", "beta"),
            ids.sorted(),
        )
        assertEquals("and therefore they are unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `the report carries machine-readable facts beside the prose`() {
        val dir = crawlOf("alpha")

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertTrue(
            "A caller deciding something stricter than the headline must read values, not scrape " +
                "headings — or renaming a heading disables it with nothing turning red.",
            outcome.text.contains("elementSetMatched="),
        )
        assertTrue(outcome.text.contains("unresolvedElements="))
    }

    @Test
    fun `a refused trait names the assertion, not "recapture the screen"`() {
        // A hand-edited trait the type rejects used to report "no readable <screen-identity>. A
        // capture made before the identity block was added has none — recapture the screen", which
        // tells an operator to throw away the edit they just made. The message that names the
        // refusal existed but was unreachable, because the crawler's reader swallowed it.
        val dir = crawlOf("alpha")
        listOf("alpha.xml", "alpha.html").forEach { name ->
            val file = File(dir, name)
            file.writeText(spliceTraits(file.readText(), MIN_ROWS_ZERO))
        }

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, outcome.status)
        assertTrue(
            "must name what was rejected: ${outcome.text}",
            outcome.text.contains("has-list") && outcome.text.contains("minRows"),
        )
        assertTrue(
            "must NOT tell the operator their edit is an old-format capture: ${outcome.text}",
            !outcome.text.contains("recapture"),
        )
    }

    @Test
    fun `a capture that genuinely predates the identity block still says so`() {
        val dir = crawlOf("alpha")
        val xml = File(dir, "alpha.xml")
        xml.writeText(xml.readText().replace("<screen-identity", "<screen-identity-old"))

        val outcome = ScreenIdentityReport.validate(xml, xml, dir, false)

        assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, outcome.status)
        assertTrue(
            "the two cases must stay distinguishable: ${outcome.text}",
            outcome.text.contains("recapture"),
        )
    }

    /** Splices [traits] in before the closing tag, as an operator editing the file would. */
    private fun spliceTraits(source: String, traits: String): String {
        val closing = Regex("""(?m)^([ \t]*)</screen-identity>$""").find(source)
            ?: error("fixture needs an expanded <screen-identity>: $source")
        val indent = closing.groupValues[1]
        val body = traits.trimIndent().lines().joinToString("\n") { "$indent  $it" }
        return source.replaceRange(closing.range.first, closing.range.first, body + "\n")
    }

    @Test
    fun `a nameless identity is refused, because the crawler would never match it`() {
        // SameNamePolicy treats a null name key as NAME_DIFFERS, while matchKnownScreens waves it
        // through. The tool used to report HOLDS on an identity the crawler refuses to arrive at —
        // the one outcome this tool must never produce.
        val dir = crawlOf("alpha")
        listOf("alpha.xml", "alpha.html").forEach { name ->
            val file = File(dir, name)
            file.writeText(
                file.readText()
                    .replace(Regex(""" title="[^"]*""""), "")
                    .replace(Regex(""" name-package="[^"]*""""), "")
            )
        }

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, outcome.status)
        assertTrue("must say what is missing: ${outcome.text}", outcome.text.contains("name half"))
    }

    @Test
    fun `captures from two different apps are refused rather than judged`() {
        val dir = crawlOf("alpha")
        val other = crawlOf("beta")
        val foreign = File(other, "beta.xml")
        listOf(foreign, File(other, "beta.html")).forEach { file ->
            file.writeText(file.readText().replace(PACKAGE, "com.other.app"))
        }

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), foreign, dir, false)

        assertEquals(
            "A package mismatch is not a trait failure; reporting DOES_NOT_HOLD told the operator " +
                "to re-settle a screen that replays fine.",
            ScreenIdentityReport.Status.INVALID_INPUT,
            outcome.status,
        )
        assertTrue(outcome.text.contains("different apps"))
    }

    @Test
    fun `a failing trait is named in the report`() {
        val dir = crawlOf("alpha")
        listOf("alpha.xml", "alpha.html").forEach { name ->
            val file = File(dir, name)
            file.writeText(spliceTraits(file.readText(), IMPOSSIBLE_LIST))
        }

        val outcome = ScreenIdentityReport.validate(File(dir, "alpha.xml"), File(dir, "alpha.xml"), dir, false)

        assertEquals(ScreenIdentityReport.Status.DOES_NOT_HOLD, outcome.status)
        assertTrue(
            "The crawler's divergence message names the failing assertion; the tool an operator " +
                "settles with must do at least as well: ${outcome.text}",
            outcome.text.contains("failed: has-list"),
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** A crawl directory holding one capture pair per name, written by the production writers. */
    private fun crawlOf(vararg screenIds: String): File {
        val dir = tempFolder.newFolder()
        screenIds.forEach { screenId ->
            val identity = ScreenIdentity(
                packageName = PACKAGE,
                rootClassName = "android.widget.FrameLayout",
                elements = setOf(
                    ScreenElementIdentity(
                        fingerprint = ElementFingerprint(
                            resourceId = "$PACKAGE:id/$screenId",
                            label = screenId,
                            className = "android.widget.Button",
                            isListItem = false,
                            checkable = false,
                            editable = false,
                        ),
                        isBackAffordance = false,
                    )
                ),
            ).withName(
                ScreenNameIdentity(
                    packageName = "com_example_target",
                    screenName = screenId,
                    titleDisambiguators = emptyList(),
                    confidence = ScreenDedupConfidence.STRONG,
                )
            )
            val snapshot = ScreenSnapshot(
                screenName = screenId,
                packageName = PACKAGE,
                elements = emptyList(),
                xmlDump = "",
                scrollStepCount = 1,
                stepSnapshots = listOf(
                    ScrollCaptureStep(stepIndex = 0, root = treeFor(screenId), newElementCount = 0)
                ),
            )
            val state = ScreenCrawlState(
                screenId = screenId,
                depth = 1,
                expansionStatus = ScreenExpansionStatus.COMPLETE,
                isRoot = false,
                screenIdentity = identity,
                parent = null,
                route = CrawlRoute(),
                runLevel = null,
                edgesByElement = emptyMap(),
            )
            File(dir, "$screenId.xml")
                .writeText(AccessibilityXmlSerializer.serialize(snapshot, state), Charsets.UTF_8)
            File(dir, "$screenId.html")
                .writeText(HtmlRenderer.render(snapshot, emptyMap(), identity), Charsets.UTF_8)
        }
        return dir
    }

    private fun treeFor(screenId: String) = AccessibilityNodeSnapshot(
        className = "android.widget.FrameLayout",
        packageName = PACKAGE,
        viewIdResourceName = "$PACKAGE:id/root",
        text = null,
        contentDescription = null,
        clickable = false,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,0][1080,2400]",
        children = listOf(
            AccessibilityNodeSnapshot(
                className = "android.widget.Button",
                packageName = PACKAGE,
                viewIdResourceName = "$PACKAGE:id/$screenId",
                text = screenId,
                contentDescription = null,
                clickable = true,
                supportsClickAction = false,
                scrollable = false,
                enabled = true,
                visibleToUser = true,
                bounds = "[0,100][1080,200]",
                children = emptyList(),
            )
        ),
    )

    /** Name plus content hash for every file, so any write at all shows up. */
    private fun fingerprintOf(dir: File): String = dir.listFiles()!!
        .sortedBy { it.name }
        .joinToString("\n") { file ->
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            "${file.name}:${digest.joinToString("") { "%02x".format(it) }}"
        }

    private companion object {
        const val PACKAGE = "com.example.target"

        /** A list assertion no fixture screen can satisfy, so the trait verdict is DOES_NOT_HOLD. */
        val IMPOSSIBLE_LIST = """
            <traits>
              <has-list container-resource-id="com.example.target:id/nonexistent" min-rows="3">
                <row-child resource-id="com.example.target:id/title" />
              </has-list>
            </traits>
        """

        /** A shape `HasList` refuses: with no minimum, any container with a child would satisfy it. */
        val MIN_ROWS_ZERO = """
            <traits>
              <has-list container-resource-id="com.example.target:id/list" min-rows="0">
                <row-child resource-id="com.example.target:id/title" />
              </has-list>
            </traits>
        """
    }
}
