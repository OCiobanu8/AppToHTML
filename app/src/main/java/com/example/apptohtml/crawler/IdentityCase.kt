package com.example.apptohtml.crawler

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Why an operator is needed. */
enum class IdentityCaseKind(val xmlValue: String) {
    /** A new screen's proposed identity does not hold, so it could not be settled. */
    PROPOSAL_DOES_NOT_HOLD("proposal-does-not-hold"),

    /**
     * More than one screen claims this one; or one does — its settled identity holds here, or this
     * screen's proposal holds on it — and the name is too weak to tell the two apart.
     */
    UNIQUENESS_COLLISION("uniqueness-collision"),

    /**
     * A settled screen no longer satisfies its own identity when the crawl stands on it again: arriving
     * by replay, or — for the root — re-scanned at the start of a resume.
     */
    SETTLED_DOES_NOT_HOLD("settled-does-not-hold"),
    ;

    companion object {
        /** The kind written as [value] in the case file, or null when no kind is written that way. */
        fun fromXml(value: String): IdentityCaseKind? = entries.firstOrNull { it.xmlValue == value }
    }
}

/** Whether a case still needs an operator. Closing one is `a2h-c2b.13`'s; nothing here closes one. */
enum class IdentityCaseStatus(val xmlValue: String) {
    OPEN("open"),
    CLOSED("closed"),
    ;

    companion object {
        /** The status written as [value] in the case file, or null when no status is written that way. */
        fun fromXml(value: String): IdentityCaseStatus? = entries.firstOrNull { it.xmlValue == value }
    }
}

/**
 * One operator case: a point where the crawl's model of the app and the app itself diverged.
 *
 * Lives in exactly one place — the crawl's case file, [IdentityCaseStore] — and every screen it
 * involves carries only a pointer to it ([IdentityCasePointers]). [relatedScreenIds] are the other
 * screens the case names: every screen a collision found, never narrowed to one.
 * [rejectedProposal] is the identity the crawler proposed and could not settle, kept so an operator
 * can see what was tried; empty when nothing was proposed.
 */
data class IdentityCase(
    val id: String,
    val status: IdentityCaseStatus,
    val kind: IdentityCaseKind,
    val raisedOn: String,
    val relatedScreenIds: List<String>,
    val detail: String,
    val rejectedProposal: List<Trait> = emptyList(),
) {
    /** Every screen this case involves, sorted: the one it was raised on and the related ones. */
    val involvedScreenIds: List<String>
        get() = (relatedScreenIds + raisedOn).distinct().sorted()
}

/**
 * A case file that cannot be trusted, or a screen pointing at a case the file does not hold.
 *
 * Fails the resume rather than being skipped: dropping a case silently would let the crawl carry on
 * past a divergence an operator was meant to see. Carries the file, because "malformed" without a
 * location is not actionable.
 */
class MalformedIdentityCases(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * The crawl's one case file, `identity-cases.xml` — written and read here and nowhere else.
 *
 * Deterministic: cases are written sorted by id, each case's screens sorted by id, and the rejected
 * proposal in the identity block's own `<traits>` syntax, so one set of cases always produces the
 * same bytes and an operator reads one trait syntax everywhere.
 */
object IdentityCaseStore {
    /** The case file's name inside a crawl folder. */
    const val FILE_NAME = "identity-cases.xml"
    private const val SCHEMA = "v1"

    /** The case file inside [crawlDirectory]. */
    fun fileIn(crawlDirectory: File): File = File(crawlDirectory, FILE_NAME)

    /**
     * Writes [cases] to [file], replacing it through a temporary sibling so a reader never sees half
     * a file. Writes nothing, and removes nothing, when there are no cases.
     */
    fun write(file: File, cases: List<IdentityCase>) {
        if (cases.isEmpty()) return
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(serialize(cases), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Could not replace ${file.absolutePath}." }
        }
    }

    /** The case file's text for [cases]. */
    fun serialize(cases: List<IdentityCase>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<identity-cases schema="$SCHEMA">""").append('\n')
        cases.sortedBy(IdentityCase::id).forEach { case ->
            append("  <case")
            append(""" id="${escapeXml(case.id)}"""")
            append(""" status="${case.status.xmlValue}"""")
            append(""" kind="${case.kind.xmlValue}"""")
            append(""" raised-on="${escapeXml(case.raisedOn)}"""")
            append(""" detail="${escapeXml(case.detail)}"""")
            append(">").append('\n')
            case.involvedScreenIds.forEach { screenId ->
                val role = if (screenId == case.raisedOn) "raised-on" else "related"
                append("""    <screen screen-id="${escapeXml(screenId)}" role="$role" />""").append('\n')
            }
            ScreenIdentityXml.appendTraits(this, case.rejectedProposal, depth = 2)
            append("  </case>").append('\n')
        }
        append("</identity-cases>").append('\n')
    }

    /**
     * Every case in [file], or empty when the crawl has none. Throws [MalformedIdentityCases] naming
     * the file and the case when anything in it cannot be read as written — and naming the temporary
     * sibling [write] uses when an interrupted write left only that behind.
     */
    fun read(file: File): List<IdentityCase> {
        if (!file.exists()) {
            val interrupted = File(file.parentFile, "${file.name}.tmp")
            if (interrupted.exists()) {
                throw MalformedIdentityCases(
                    "Case file ${file.absolutePath} is missing but an interrupted write left " +
                        "${interrupted.absolutePath}; check it and rename it to ${file.name}.",
                )
            }
            return emptyList()
        }
        val root = try {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = false
                    isValidating = false
                    runCatching {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                }
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        } catch (e: Exception) {
            throw MalformedIdentityCases("Case file ${file.absolutePath} is not readable XML.", e)
        }
        if (root?.tagName != "identity-cases") {
            throw MalformedIdentityCases("Case file ${file.absolutePath} has no <identity-cases> root.")
        }
        if (root.getAttribute("schema") != SCHEMA) {
            throw MalformedIdentityCases(
                "Case file ${file.absolutePath} has schema \"${root.getAttribute("schema")}\"; only $SCHEMA is read.",
            )
        }
        root.childElements().firstOrNull { it.tagName != "case" }?.let { stray ->
            throw MalformedIdentityCases("Case file ${file.absolutePath} holds an unknown <${stray.tagName}>.")
        }
        val cases = root.childElementsNamed("case").map { element -> parseCase(element, file) }
        val duplicate = cases.groupBy(IdentityCase::id).entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw MalformedIdentityCases("Case file ${file.absolutePath} holds ${duplicate.key} twice.")
        }
        return cases.sortedBy(IdentityCase::id)
    }

    private fun parseCase(element: Element, file: File): IdentityCase {
        val id = element.getAttribute("id")
        fun fail(what: String): Nothing =
            throw MalformedIdentityCases("Case ${id.ifBlank { "<no id>" }} in ${file.absolutePath}: $what")
        if (id.isBlank()) fail("missing id")
        val status = IdentityCaseStatus.fromXml(element.getAttribute("status"))
            ?: fail("unknown status \"${element.getAttribute("status")}\"")
        val kind = IdentityCaseKind.fromXml(element.getAttribute("kind"))
            ?: fail("unknown kind \"${element.getAttribute("kind")}\"")
        val raisedOn = element.getAttribute("raised-on").ifBlank { fail("missing raised-on") }
        element.childElements().firstOrNull { it.tagName != "screen" && it.tagName != "traits" }?.let { stray ->
            fail("unknown <${stray.tagName}>")
        }
        val screens = element.childElementsNamed("screen")
        screens.forEach { screen ->
            val screenId = screen.getAttribute("screen-id")
            if (screenId.isBlank()) fail("a <screen> has no screen-id")
            val role = screen.getAttribute("role")
            if (role != "raised-on" && role != "related") fail("screen $screenId has unknown role \"$role\"")
        }
        val raisedOnScreens = screens
            .filter { it.getAttribute("role") == "raised-on" }
            .map { it.getAttribute("screen-id") }
        if (raisedOnScreens != listOf(raisedOn)) {
            fail("its raised-on screens are $raisedOnScreens, not exactly [$raisedOn]")
        }
        val related = screens
            .filter { it.getAttribute("role") == "related" }
            .map { it.getAttribute("screen-id") }
        if (raisedOn in related || related.size != related.distinct().size) fail("a screen is listed twice")
        val rejected = try {
            ScreenIdentityXml.parseTraits(element)
        } catch (e: MalformedScreenIdentity) {
            throw MalformedIdentityCases(
                "Case $id in ${file.absolutePath}: rejected proposal unreadable: ${e.message}",
                e,
            )
        }
        return IdentityCase(
            id = id,
            status = status,
            kind = kind,
            raisedOn = raisedOn,
            relatedScreenIds = related.sorted(),
            detail = element.getAttribute("detail"),
            rejectedProposal = rejected,
        )
    }
}

/**
 * The `<identity-case ref="…"/>` pointers a screen's XML carries — one per **open** case involving it.
 *
 * Pointers are references, never a second copy: they are always derived from the open cases, written
 * by [append] when a screen is serialized, and brought up to date by [refresh] on a screen whose
 * snapshot is no longer in memory. Both produce the same lines in the same place, so a refreshed
 * file is byte-identical to one serialized afresh.
 */
object IdentityCasePointers {
    private val POINTER_LINE = Regex("""^[ ]*<identity-case ref="[^"]*" />\n""", RegexOption.MULTILINE)
    private const val CRLF = "\r\n"

    /** Writes a pointer line per id in [caseIds], sorted, at [depth]. Writes nothing when empty. */
    fun append(builder: StringBuilder, caseIds: List<String>, depth: Int) {
        val indent = "  ".repeat(depth)
        caseIds.sorted().forEach { id ->
            builder.append(indent)
                .append("""<identity-case ref="${escapeXml(id)}" />""")
                .append('\n')
        }
    }

    /** The case ids [element]'s `<crawl>` block points at, in document order. */
    fun read(crawl: Element): List<String> =
        crawl.childElementsNamed("identity-case").map { it.getAttribute("ref") }

    /**
     * [xml] with its pointers replaced by one per id in [caseIds], placed directly after the
     * `<screen-identity>` element exactly as the serializer places them. Returns [xml] unchanged when
     * it has no `<screen-identity>` element inside a `<crawl>` block.
     */
    fun refresh(xml: String, caseIds: List<String>): String {
        // A file an operator saved with Windows line endings is refreshed in kind, not skipped.
        if (CRLF in xml) {
            return refresh(xml.replace(CRLF, "\n"), caseIds).replace("\n", CRLF)
        }
        val crawlStart = xml.indexOf("<crawl ")
        val crawlEnd = xml.indexOf("</crawl>")
        if (crawlStart < 0 || crawlEnd < crawlStart) return xml
        val head = xml.substring(0, crawlStart)
        val block = POINTER_LINE.replace(xml.substring(crawlStart, crawlEnd), "")
        val tail = xml.substring(crawlEnd)

        val identityStart = block.indexOf("<screen-identity")
        if (identityStart < 0) return xml
        val selfClosingEnd = block.indexOf("/>\n", identityStart)
        val tagEnd = block.indexOf(">", identityStart)
        val identityEnd = if (selfClosingEnd >= 0 && selfClosingEnd + 1 == tagEnd) {
            selfClosingEnd + "/>\n".length
        } else {
            val closing = block.indexOf("</screen-identity>\n", identityStart)
            if (closing < 0) return xml
            closing + "</screen-identity>\n".length
        }
        val lineStart = block.lastIndexOf('\n', identityStart) + 1
        val depth = (identityStart - lineStart) / 2
        val pointers = StringBuilder().also { append(it, caseIds, depth) }
        return head + block.substring(0, identityEnd) + pointers + block.substring(identityEnd) + tail
    }

    /** Rewrites [xmlFile]'s pointers in place to [caseIds]. Does nothing when the file is missing. */
    fun refresh(xmlFile: File, caseIds: List<String>) {
        if (!xmlFile.exists()) return
        val before = xmlFile.readText(Charsets.UTF_8)
        val after = refresh(before, caseIds)
        if (after != before) xmlFile.writeText(after, Charsets.UTF_8)
    }
}
