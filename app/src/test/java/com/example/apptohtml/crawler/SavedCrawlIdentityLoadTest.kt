package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a resumed crawl recovers from the files it wrote.
 *
 * The loader used to blank a screen's content half with the comment "not recoverable from the
 * artifacts" — true at the time, because nothing wrote it. Now that it is written, blanking it would
 * throw away an operator's work on every resume, so these pin that it comes back.
 *
 * The `replayFingerprint` assertions are the visible consequence and are **red on pre-change code**:
 * a resumed crawl used to write an empty fingerprint into its own debug manifest and graph.
 */
class SavedCrawlIdentityLoadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val targetPackage = "com.example.target"

    @Test
    fun `a resumed crawl recovers the element set it wrote`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())

        val loaded = SavedCrawlLoader.load(dir)

        assertNotNull(loaded)
        val record = loaded!!.screens.single()
        assertEquals(
            "The element set is on disk; recomputing it from the live screen loses hand edits.",
            settledIdentity().elements,
            record.identity.elements,
        )
        assertEquals(
            "Root class travels with the element set or the comparison cannot run.",
            settledIdentity().rootClassName,
            record.identity.rootClassName,
        )
    }

    @Test
    fun `a resumed crawl recovers the traits an operator settled by hand`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())

        val record = SavedCrawlLoader.load(dir)!!.screens.single()

        assertEquals(
            "Traits are the whole point of settling a screen; losing them on resume erases the work.",
            settledIdentity().traits,
            record.identity.traits,
        )
    }

    @Test
    fun `the manifest of a resumed crawl carries a real replay fingerprint`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())
        val loaded = SavedCrawlLoader.load(dir)!!

        val manifestFile = File(tempFolder.newFolder("out"), "crawl-index.json")
        CrawlManifestStore.write(manifestOf(loaded), manifestFile)
        val json = manifestFile.readText()

        val expected = ScreenIdentityCodec.encodeContent(settledIdentity())
        assertTrue("fixture must produce a non-empty fingerprint", expected.isNotEmpty())
        assertFalse(
            "A resumed crawl used to write an empty replayFingerprint, because the loader " +
                "blanked the content half. That is the bug this pins.\n$json",
            json.contains(""""replayFingerprint": """""),
        )
        assertTrue(
            "The manifest must carry the identity the screen was written with.\n$json",
            json.contains(""""replayFingerprint": "${expected.replace("\"", "\\\"")}""""),
        )
    }

    @Test
    fun `the graph of a resumed crawl carries a real replay fingerprint`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())
        val loaded = SavedCrawlLoader.load(dir)!!

        val graph = CrawlGraphBuilder.build(manifestOf(loaded))

        assertEquals(
            ScreenIdentityCodec.encodeContent(settledIdentity()),
            graph.nodes.single().replayFingerprint,
        )
    }

    @Test
    fun `loading does not change the name key or the dedup key`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())

        val loaded = SavedCrawlLoader.load(dir)!!
        val record = loaded.screens.single()

        // Compared against what the codec produces for the same name rather than against a literal:
        // the key's format belongs to ScreenIdentityCodec, and restating it here would be a second
        // copy of a fact that file owns.
        assertEquals(
            "Dedup keys index a crawl. Recovering the content half must not move them.",
            DedupPolicy.nameKey(settledIdentity()),
            DedupPolicy.nameKey(record.identity),
        )
        assertEquals(
            "The screen must still be reachable under the key the crawl indexed it by.",
            mapOf(DedupPolicy.keyFor(record.identity) to "screen_00000"),
            loaded.dedupKeyToScreenId,
        )
    }

    @Test
    fun `a screen rewritten after a resume keeps its identity in both files`() {
        val dir = tempFolder.newFolder("crawl")
        writeScreen(dir, identity = settledIdentity())
        val originalXml = File(dir, "$BASE_NAME.xml").readText()
        val originalHtml = File(dir, "$BASE_NAME.html").readText()

        val record = SavedCrawlLoader.load(dir)!!.screens.single()

        // Exactly what a resume does: write the screen's files back from the loaded record.
        val rewrittenXml = AccessibilityXmlSerializer.serialize(
            snapshot(),
            crawlState(record.identity),
        )
        val rewrittenHtml = HtmlRenderer.render(snapshot(), emptyMap(), record.identity)

        assertEquals(
            "A resume must not rewrite the XML into something different from what it read.",
            originalXml,
            rewrittenXml,
        )
        assertEquals(
            "…nor the page.",
            originalHtml,
            rewrittenHtml,
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** A screen an operator has settled: a real element set plus traits no capture would propose. */
    private fun settledIdentity(): ScreenIdentity = ScreenIdentity(
        packageName = targetPackage,
        rootClassName = "android.widget.FrameLayout",
        elements = setOf(
            element("Open", "$targetPackage:id/open"),
            element("Navigate up", "$targetPackage:id/back", isBack = true),
        ),
        traits = listOf(
            HasList(
                containerResourceId = "$targetPackage:id/list",
                minRows = 3,
                rowChildResourceIds = setOf("$targetPackage:id/title", "$targetPackage:id/price"),
            ),
            HasControl(element("Open", "$targetPackage:id/open")),
        ),
    ).withName(
        ScreenNameIdentity(
            packageName = ScreenNaming.normalizeIdentityToken(targetPackage),
            screenName = ScreenNaming.normalizeIdentityToken("Home"),
            titleDisambiguators = emptyList(),
            confidence = ScreenDedupConfidence.STRONG,
        )
    )

    private fun element(label: String, resourceId: String, isBack: Boolean = false) =
        ScreenElementIdentity(
            fingerprint = ElementFingerprint(
                resourceId = resourceId,
                label = label,
                className = "android.widget.Button",
                isListItem = false,
                checkable = false,
                editable = false,
            ),
            isBackAffordance = isBack,
        )

    private fun snapshot() = ScreenSnapshot(
        screenName = "Home",
        packageName = targetPackage,
        elements = emptyList(),
        xmlDump = "",
        scrollStepCount = 1,
    )

    private fun crawlState(identity: ScreenIdentity) = ScreenCrawlState(
        screenId = "screen_00000",
        depth = 0,
        expansionStatus = ScreenExpansionStatus.COMPLETE,
        isRoot = true,
        screenIdentity = identity,
        parent = null,
        route = CrawlRoute(),
        runLevel = RunLevelState(
            sessionId = "session-1",
            startedAt = 1_000L,
            finishedAt = null,
            status = CrawlRunStatus.IN_PROGRESS,
            maxDepthReached = 0,
        ),
        edgesByElement = emptyMap(),
    )

    private fun writeScreen(dir: File, identity: ScreenIdentity) {
        File(dir, "$BASE_NAME.html")
            .writeText(HtmlRenderer.render(snapshot(), emptyMap(), identity), Charsets.UTF_8)
        File(dir, "$BASE_NAME.xml").writeText(
            AccessibilityXmlSerializer.serialize(snapshot(), crawlState(identity)),
            Charsets.UTF_8,
        )
    }

    private fun manifestOf(loaded: LoadedCrawlState): CrawlManifest =
        CrawlRunTracker.fromExistingState(
            sessionId = "session-2",
            packageName = targetPackage,
            startedAt = 2_000L,
            screens = loaded.screens,
            edges = loaded.edges,
            dedupKeyToScreenId = loaded.dedupKeyToScreenId,
            rootScreenId = loaded.rootScreenId,
            nextScreenSequence = loaded.nextScreenSequence,
            nextEdgeSequence = loaded.nextEdgeSequence,
        ).buildManifest(CrawlRunStatus.COMPLETED, finishedAt = 3_000L)

    private companion object {
        const val BASE_NAME = "screen_00000_home"
    }
}
