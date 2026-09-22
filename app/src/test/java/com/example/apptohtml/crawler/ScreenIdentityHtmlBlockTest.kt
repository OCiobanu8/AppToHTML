package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page carries the same identity the XML does.
 *
 * Two copies of one fact can drift, so what these pin is that both come from one producer and that
 * the page keeps its copy through the rewrites a crawl performs as its edges resolve. A page that
 * quietly dropped the block on rewrite would erase whatever an operator had settled by hand.
 */
class ScreenIdentityHtmlBlockTest {

    @Test
    fun `the page embeds the identity as an xml data block`() {
        val html = HtmlRenderer.render(snapshot(), emptyMap(), identity())

        assertTrue(
            "The block is looked up by id, so the id is part of the contract.",
            html.contains("""<script type="application/xml" id="screen-identity">"""),
        )
        assertTrue("The block sits in <head>.", html.indexOf("id=\"screen-identity\"") < html.indexOf("<body>"))
        assertTrue(html.contains("</script>"))
    }

    @Test
    fun `the page and the xml publish the same identity text`() {
        val identity = identity()

        val block = AccessibilityXmlSerializer.screenIdentityBlock(identity)
        val html = HtmlRenderer.render(snapshot(), emptyMap(), identity)
        val embedded = embeddedBlock(html)

        assertEquals(
            "Both files come from one producer; only the indentation added for <head> differs.",
            block.lines().map(String::trim),
            embedded.lines().map(String::trim).filter(String::isNotEmpty),
        )
    }

    @Test
    fun `the embedded block parses back to the identity that was written`() {
        val identity = identity()

        val embedded = embeddedBlock(HtmlRenderer.render(snapshot(), emptyMap(), identity))
        val parsed = parseIdentityBlock(embedded)

        assertEquals(identity.elements, parsed.elements)
        assertEquals(identity.traits, parsed.traits)
        assertEquals(identity.rootClassName, parsed.rootClassName)
    }

    @Test
    fun `a rewrite keeps the identity block`() {
        val identity = identity()

        val first = HtmlRenderer.render(snapshot(), emptyMap(), identity)
        val rewritten = HtmlRenderer.render(
            snapshot(),
            mapOf(snapshot().elements.first().toLinkKey() to "child.html"),
            identity,
        )

        assertEquals(
            "Resolving an edge changes the body, never the identity.",
            embeddedBlock(first),
            embeddedBlock(rewritten),
        )
    }

    @Test
    fun `the page carries its identity from the very first write`() {
        // Found on a real Settings crawl: 30 of 36 pages had no identity block. Every XML was
        // corrected by a later rewrite, but a page is only rewritten when one of its edges
        // resolves — so every leaf screen shipped without one. A screen is saved before it enters
        // the tracker, so its crawl state does not exist yet while its identity already does.
        val dir = java.nio.file.Files.createTempDirectory("crawl").toFile()
        try {
            val files = CaptureFileStore.saveScreen(
                session = CrawlSessionDirectory(
                    sessionId = "session-1",
                    directory = dir,
                    manifestFile = java.io.File(dir, "crawl-index.json"),
                    logFile = java.io.File(dir, "crawl.log"),
                    graphJsonFile = java.io.File(dir, "crawl-graph.json"),
                    graphHtmlFile = java.io.File(dir, "crawl-graph.html"),
                ),
                snapshot = snapshot(),
                screenId = "screen_00007",
                identity = identity(),
            )

            val onDisk = files.htmlFile.readText()
            assertTrue(
                "A leaf screen is never rewritten, so the first write is its only chance.",
                onDisk.contains("id=\"screen-identity\""),
            )
            assertEquals(identity().traits, parseIdentityBlock(embeddedBlock(onDisk)).traits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rewriting a screen's page on disk keeps its identity block`() {
        val htmlFile = java.io.File.createTempFile("screen", ".html")
        val xmlFile = java.io.File.createTempFile("screen", ".xml")
        val files = CapturedScreenFiles(htmlFile = htmlFile, xmlFile = xmlFile, mergedXmlFile = null)
        try {
            htmlFile.writeText(HtmlRenderer.render(snapshot(), emptyMap(), identity()))

            // What a crawl does many times over as a screen's edges resolve.
            CaptureFileStore.rewriteScreenHtml(
                files = files,
                snapshot = snapshot(),
                resolvedChildLinks = mapOf(snapshot().elements.first().toLinkKey() to "child.html"),
                identity = identity(),
            )

            val onDisk = htmlFile.readText()
            assertTrue(
                "A rewrite that dropped the block would erase whatever an operator had settled.",
                onDisk.contains("id=\"screen-identity\""),
            )
            assertEquals(identity().traits, parseIdentityBlock(embeddedBlock(onDisk)).traits)
        } finally {
            htmlFile.delete()
            xmlFile.delete()
        }
    }

    @Test
    fun `a page rendered without an identity carries no block`() {
        val html = HtmlRenderer.render(snapshot(), emptyMap(), null)

        assertFalse(
            "An absent block means no identity to publish — not an identity that is empty.",
            html.contains("id=\"screen-identity\""),
        )
    }

    @Test
    fun `values that could close the script block early are escaped`() {
        val hostile = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = "android.widget.FrameLayout",
            elements = setOf(element("</script><b>x</b>", "$PACKAGE:id/x")),
        ).withName(
            ScreenNameIdentity(
                packageName = "com_example_target",
                screenName = "</script>",
                titleDisambiguators = emptyList(),
                confidence = ScreenDedupConfidence.STRONG,
            )
        )

        val html = HtmlRenderer.render(snapshot(), emptyMap(), hostile)

        assertEquals(
            "Exactly one closing tag: the block's own.",
            1,
            Regex("</script>").findAll(html).count(),
        )
        assertEquals(
            "And the hostile label still round-trips.",
            hostile.elements,
            parseIdentityBlock(embeddedBlock(html)).elements,
        )
    }

    // --- helpers --------------------------------------------------------------------------------

    /** The text between the script tags, which is what the XML's own element must agree with. */
    private fun embeddedBlock(html: String): String {
        val open = """<script type="application/xml" id="screen-identity">"""
        val start = html.indexOf(open)
        check(start >= 0) { "no identity block in:\n$html" }
        val from = start + open.length
        val end = html.indexOf("</script>", from)
        check(end >= 0) { "unterminated identity block" }
        return html.substring(from, end).trim('\n')
    }

    private fun parseIdentityBlock(block: String): ScreenIdentity {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(block.trimIndent().byteInputStream(Charsets.UTF_8))
        return ScreenIdentityXml.parse(document.documentElement)
    }

    private fun identity() = ScreenIdentity(
        packageName = PACKAGE,
        rootClassName = "android.widget.FrameLayout",
        elements = setOf(
            element("Checkout", "$PACKAGE:id/checkout"),
            element("Navigate up", "$PACKAGE:id/back", isBack = true),
        ),
        traits = listOf(
            HasList(
                containerResourceId = "$PACKAGE:id/cart_list",
                minRows = 2,
                rowChildResourceIds = setOf("$PACKAGE:id/title"),
            ),
        ),
    ).withName(
        ScreenNameIdentity(
            packageName = "com_example_target",
            screenName = "cart",
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
        screenName = "Cart",
        packageName = PACKAGE,
        elements = listOf(
            PressableElement(
                label = "Checkout",
                resourceId = "$PACKAGE:id/checkout",
                className = "android.widget.Button",
                bounds = "[0,0][100,100]",
                isListItem = false,
            )
        ),
        xmlDump = "",
    )

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
