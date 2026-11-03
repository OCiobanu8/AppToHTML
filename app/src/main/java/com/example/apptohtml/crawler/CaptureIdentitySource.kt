package com.example.apptohtml.crawler

import org.w3c.dom.Element
import java.io.File

/**
 * One captured screen, loaded from the pair of files that describe it.
 *
 * A screen is its `.xml` and its `.html` together. Both carry the identity, and both are read,
 * because an operator edits whichever is in front of them and the pair is only trustworthy while
 * they agree.
 */
internal data class LoadedCapture(
    /** How the report names this screen: the file's base name. */
    val screenId: String,
    val identity: ScreenIdentity,
    /** The step-0 tree, absent when the capture records no scroll steps. */
    val firstViewport: AccessibilityNodeSnapshot?,
    val xmlFile: File,
    val htmlFile: File,
)

/**
 * Why a capture could not be used. Every case names *where*, because the operator who hand-edited
 * the file is the one who has to act on it.
 */
internal data class CaptureProblem(
    val file: File,
    val detail: String,
) {
    override fun toString(): String = "${file.name}: $detail"
}

/**
 * Reads a screen's identity from its files.
 *
 * The two copies are compared as **parsed identities** rather than as text: the HTML copy is
 * indented to sit inside `<head>`, so comparing raw text would fail on whitespace while missing
 * nothing real. Any genuine difference — an element, a trait, the root class — is still caught.
 *
 * Neither file is preferred when they disagree. Picking one would silently discard an edit made to
 * the other, which is the failure this check exists to prevent.
 */
internal object CaptureIdentitySource {

    /**
     * The capture whose XML is [xmlFile], read with its page. With [requirePage] — validating one
     * screen — a missing page or one with no identity block is refused. Without it — reading a whole
     * crawl, as the settle does — such a page is read as the crawler's resume reads it ([pageProblem]):
     * the XML alone, because the next rewrite writes the page from it and no edit can be lost.
     */
    fun load(xmlFile: File, requirePage: Boolean = true): Result<LoadedCapture> {
        val htmlFile = File(xmlFile.parentFile, xmlFile.name.removeSuffix(".xml") + ".html")

        val fromXml = readXmlIdentity(xmlFile).getOrElse { return Result.failure(it) }
        val pageHasBlock = htmlFile.isFile && embeddedBlock(htmlFile.readText(Charsets.UTF_8)) != null
        if (!requirePage && !pageHasBlock) {
            return Result.success(
                LoadedCapture(
                    screenId = xmlFile.name.removeSuffix(".xml"),
                    identity = fromXml,
                    firstViewport = CaptureTreeReader.readFirstViewport(xmlFile),
                    xmlFile = xmlFile,
                    htmlFile = htmlFile,
                )
            )
        }
        val fromHtml = readHtmlIdentity(htmlFile).getOrElse { return Result.failure(it) }

        differenceBetween(fromXml, fromHtml)?.let { difference ->
            return failure(
                xmlFile,
                "the identity in ${xmlFile.name} and ${htmlFile.name} disagree — $difference. " +
                    "Neither is preferred; make them match and re-run.",
            )
        }

        return Result.success(
            LoadedCapture(
                screenId = xmlFile.name.removeSuffix(".xml"),
                identity = fromXml,
                firstViewport = CaptureTreeReader.readFirstViewport(xmlFile),
                xmlFile = xmlFile,
                htmlFile = htmlFile,
            )
        )
    }

    /**
     * Why the page beside [xmlFile] cannot stand next to [xmlIdentity] — the identity read from that
     * XML — or null when they agree.
     *
     * For the crawler's resume, which reads only the XML: a page whose identity differs would be
     * overwritten by the next rewrite, and a page whose identity block is refused holds an edit the
     * crawler would discard. A missing page, or one with no identity block, is not a problem here:
     * the next rewrite writes it from the XML and no edit is lost. The same comparison [load] uses, and
     * the same missing-page rule its crawl-wide read (`requirePage = false`) uses.
     */
    fun pageProblem(xmlFile: File, xmlIdentity: ScreenIdentity): CaptureProblem? {
        val htmlFile = File(xmlFile.parentFile, xmlFile.name.removeSuffix(".xml") + ".html")
        if (!htmlFile.isFile) return null
        if (embeddedBlock(htmlFile.readText(Charsets.UTF_8)) == null) return null
        val fromHtml = readHtmlIdentity(htmlFile).getOrElse { error ->
            return (error as? CaptureProblemException)?.problem ?: CaptureProblem(htmlFile, error.message.orEmpty())
        }
        return differenceBetween(xmlIdentity, fromHtml)?.let { difference ->
            CaptureProblem(
                xmlFile,
                "the identity in ${xmlFile.absolutePath} and ${htmlFile.absolutePath} disagree — $difference. " +
                    "Neither is preferred; make them match before resuming.",
            )
        }
    }

    private fun readXmlIdentity(xmlFile: File): Result<ScreenIdentity> {
        if (!xmlFile.isFile) return failure(xmlFile, "no such file.")
        val head = try {
            // The refusal-reporting reader: a rejected trait must name itself, not collapse into
            // "no readable <screen-identity>", which is what the operator is told to recapture over.
            ScreenXmlReader.readHeadReportingRefusals(xmlFile)
        } catch (e: MalformedScreenIdentity) {
            return failure(xmlFile, e.message ?: "unusable screen identity.")
        } ?: return failure(
            xmlFile,
            "no readable <screen-identity>. A capture made before the identity block was added has " +
                "none — recapture the screen rather than editing one in by hand.",
        )
        return Result.success(head.screenIdentity)
    }

    private fun readHtmlIdentity(htmlFile: File): Result<ScreenIdentity> {
        if (!htmlFile.isFile) {
            return failure(
                htmlFile,
                "the page is missing. A screen is its .xml and .html together; validating one " +
                    "alone would pass an edit that only reached the other.",
            )
        }
        val block = embeddedBlock(htmlFile.readText(Charsets.UTF_8))
            ?: return failure(
                htmlFile,
                "no <script id=\"${HtmlRenderer.SCREEN_IDENTITY_ELEMENT_ID}\"> block.",
            )
        val element = parseElement(block)
            ?: return failure(htmlFile, "the identity block is not well-formed XML.")
        return try {
            Result.success(
                ScreenIdentityXml.parse(element).let { content ->
                    ScreenIdentityXml.parseName(element)?.let(content::withName) ?: content
                }
            )
        } catch (e: MalformedScreenIdentity) {
            failure(htmlFile, e.message ?: "unusable screen identity.")
        }
    }

    /**
     * Where a page's identity block sits: [openStart] is the `<script` tag, [contentStart] the first
     * character after it, [closeStart] the `</script>` that ends it. The one place a page's block is
     * found, for reading it here and for replacing it in a settle.
     */
    data class PageBlock(val openStart: Int, val contentStart: Int, val closeStart: Int)

    /** The identity block in [html], or null when the page has none. */
    fun locatePageBlock(html: String): PageBlock? {
        val open = """<script type="application/xml" id="${HtmlRenderer.SCREEN_IDENTITY_ELEMENT_ID}">"""
        val start = html.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = html.indexOf("</script>", from)
        if (end < 0) return null
        return PageBlock(start, from, end)
    }

    private fun embeddedBlock(html: String): String? =
        locatePageBlock(html)?.let { block -> html.substring(block.contentStart, block.closeStart) }

    private fun parseElement(xml: String): Element? = runCatching { parseXmlElement(xml.trim()) }.getOrNull()

    /** The first real difference, phrased for someone about to edit a file, or null when they agree. */
    private fun differenceBetween(xml: ScreenIdentity, html: ScreenIdentity): String? {
        if (xml.rootClassName != html.rootClassName) {
            return "root class \"${xml.rootClassName}\" vs \"${html.rootClassName}\""
        }
        if (xml.packageName != html.packageName) {
            return "package \"${xml.packageName}\" vs \"${html.packageName}\""
        }
        val onlyXml = xml.elements - html.elements
        val onlyHtml = html.elements - xml.elements
        if (onlyXml.isNotEmpty() || onlyHtml.isNotEmpty()) {
            return buildString {
                append("elements differ")
                if (onlyXml.isNotEmpty()) {
                    append("; only in the XML: ")
                    append(onlyXml.joinToString(", ") { it.fingerprint.label })
                }
                if (onlyHtml.isNotEmpty()) {
                    append("; only in the HTML: ")
                    append(onlyHtml.joinToString(", ") { it.fingerprint.label })
                }
            }
        }
        if (xml.traits != html.traits) {
            return describeTraitDifference(xml.traits, html.traits)
        }
        if (xml.settledBy != html.settledBy) {
            return "settled by ${xml.settledBy?.xmlValue ?: "nobody"} in the XML but " +
                "${html.settledBy?.xmlValue ?: "nobody"} in the page"
        }
        if (DedupPolicy.nameKey(xml) != DedupPolicy.nameKey(html)) {
            return "name \"${DedupPolicy.nameKey(xml)}\" vs \"${DedupPolicy.nameKey(html)}\""
        }
        return null
    }

    /** Test seam: the difference phrasing is the deliverable here, so it is asserted directly. */
    internal fun describeDifferenceForTest(xml: List<Trait>, html: List<Trait>): String =
        describeTraitDifference(xml, html)

    /**
     * Names the trait that actually differs.
     *
     * Reporting counts alone produced "XML has 1, HTML has 1" whenever an operator *edited* a trait
     * rather than adding one — which is the common case, and says nothing. The same shape as
     * `a2h-c2b.6`'s "Expected X but found X", and just as useless to act on.
     */
    private fun describeTraitDifference(xml: List<Trait>, html: List<Trait>): String {
        if (xml.size != html.size) {
            return "traits differ — the XML asserts ${xml.size}, the page asserts ${html.size}"
        }
        // Same assertions in a different order: the lists are unequal but the sets agree. Reported
        // as the reorder it is, because telling an operator "trait 1 differs" when both files
        // assert exactly the same things sends them looking for a difference that is not there.
        if (xml.toSet() == html.toSet()) {
            return "the same traits are listed in a different order; make the two files agree"
        }
        val position = xml.indices.firstOrNull { index -> xml[index] != html[index] }
            ?: return "traits differ"
        return "trait ${position + 1} differs — the XML says ${describe(xml[position])}, " +
            "the page says ${describe(html[position])}"
    }

    /** One trait, in the words an operator would use to find it in the file. */
    private fun describe(trait: Trait): String = when (trait) {
        is HasList ->
            "has-list ${trait.containerResourceId} with ${trait.minRows}+ rows carrying " +
                trait.rowChildResourceIds.joinToString(", ")
        is HasControl -> "has-control ${trait.element.fingerprint.label}"
        is LacksControl -> "lacks-control ${trait.element.fingerprint.label}"
    }

    private fun <T> failure(file: File, detail: String): Result<T> =
        Result.failure(CaptureProblemException(CaptureProblem(file, detail)))
}

/** Carries a [CaptureProblem] through `Result`, so every failure names a file and a reason. */
internal class CaptureProblemException(val problem: CaptureProblem) : Exception(problem.toString())
