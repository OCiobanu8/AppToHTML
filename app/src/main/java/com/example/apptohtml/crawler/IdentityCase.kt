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

/**
 * Where a case stands: `open -> handled -> closed`.
 *
 * [OPEN] needs an operator. [HANDLED] means an operator settled the screen and the validation tool
 * confirmed, against the crawl's saved captures, that the identity holds; the resumed crawl still has
 * to confirm it on the live screen. [CLOSED] means it did, and the case is kept as history.
 */
enum class IdentityCaseStatus(val xmlValue: String) {
    OPEN("open"),
    HANDLED("handled"),
    CLOSED("closed"),
    ;

    /** Whether the case is unfinished, so every screen it involves still points at it. */
    val isActive: Boolean get() = this != CLOSED

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
 * can see what was tried; empty when nothing was proposed. [replaced] is the settled identity an
 * operator's settle replaced on the raised-on screen, so a replacement is on the record and never
 * silent; null when the screen asserted nothing before.
 */
data class IdentityCase(
    val id: String,
    val status: IdentityCaseStatus,
    val kind: IdentityCaseKind,
    val raisedOn: String,
    val relatedScreenIds: List<String>,
    val detail: String,
    val rejectedProposal: List<Trait> = emptyList(),
    val replaced: ReplacedIdentity? = null,
) {
    /** Every screen this case involves, sorted: the one it was raised on and the related ones. */
    val involvedScreenIds: List<String>
        get() = (relatedScreenIds + raisedOn).distinct().sorted()
}

/** The traits a settle replaced, and who had settled them. */
data class ReplacedIdentity(
    val traits: List<Trait>,
    val settledBy: IdentityOrigin,
) {
    init {
        require(traits.isNotEmpty()) { "A replaced identity asserted something; it had traits." }
    }
}

/**
 * A saved crawl the resume refuses to load, with a message naming the file and what is wrong.
 *
 * Refused rather than repaired or skipped: every subtype is a place where carrying on would silently
 * drop or overwrite something an operator wrote or was meant to see. The saved-crawl panel shows the
 * message instead of offering the resume.
 */
open class RefusedSavedCrawl(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * A case file that cannot be trusted, or a screen pointing at a case the file does not hold.
 *
 * Fails the resume rather than being skipped: dropping a case silently would let the crawl carry on
 * past a divergence an operator was meant to see. Carries the file, because "malformed" without a
 * location is not actionable.
 */
class MalformedIdentityCases(message: String, cause: Throwable? = null) :
    RefusedSavedCrawl(message, cause)

/**
 * A screen whose identity the resume cannot use as written: refused by the reader, or different in
 * its `.xml` and its `.html`.
 *
 * Skipping the screen would drop an operator's edit, and loading one copy would let the next rewrite
 * overwrite the other, so the resume stops and names the files instead.
 */
class RefusedScreenIdentity(message: String, cause: Throwable? = null) :
    RefusedSavedCrawl(message, cause)

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

    private val CASE_ID = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Whether [id] can be a case id: letters, digits, `_` and `-` only. Ids name folders and URLs, so
     * nothing that could step out of a folder (`/`, `..`) is ever accepted from a case file.
     */
    fun isCaseId(id: String): Boolean = CASE_ID.matches(id)

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
            case.replaced?.let { replaced ->
                append("""    <replaced ${ScreenIdentityXml.SETTLED_BY_ATTRIBUTE}="${replaced.settledBy.xmlValue}">""").append('\n')
                ScreenIdentityXml.appendTraits(this, replaced.traits, depth = 3)
                append("    </replaced>").append('\n')
            }
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
        if (!isCaseId(id)) fail("\"$id\" is not a case id: only letters, digits, '_' and '-' are allowed")
        val status = IdentityCaseStatus.fromXml(element.getAttribute("status"))
            ?: fail("unknown status \"${element.getAttribute("status")}\"")
        val kind = IdentityCaseKind.fromXml(element.getAttribute("kind"))
            ?: fail("unknown kind \"${element.getAttribute("kind")}\"")
        val raisedOn = element.getAttribute("raised-on").ifBlank { fail("missing raised-on") }
        element.childElements().firstOrNull { it.tagName !in CASE_CHILDREN }?.let { stray ->
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
            ScreenIdentityXml.parseTraits(element, ScreenIdentityXml.TraitsStamp.FORBIDDEN).traits
        } catch (e: MalformedScreenIdentity) {
            throw MalformedIdentityCases(
                "Case $id in ${file.absolutePath}: rejected proposal unreadable: ${e.message}",
                e,
            )
        }
        val replacedElements = element.childElementsNamed("replaced")
        if (replacedElements.size > 1) fail("it records ${replacedElements.size} <replaced> identities, not one")
        val replaced = replacedElements.singleOrNull()?.let { replacedElement ->
            val origin = IdentityOrigin.fromXml(replacedElement.getAttribute(ScreenIdentityXml.SETTLED_BY_ATTRIBUTE))
                ?: fail("its <replaced> identity does not say who settled it")
            val traits = try {
                ScreenIdentityXml.parseTraits(replacedElement, ScreenIdentityXml.TraitsStamp.FORBIDDEN).traits
            } catch (e: MalformedScreenIdentity) {
                throw MalformedIdentityCases(
                    "Case $id in ${file.absolutePath}: replaced identity unreadable: ${e.message}",
                    e,
                )
            }
            if (traits.isEmpty()) fail("its <replaced> identity asserts nothing")
            ReplacedIdentity(traits, origin)
        }
        return IdentityCase(
            id = id,
            status = status,
            kind = kind,
            raisedOn = raisedOn,
            relatedScreenIds = related.sorted(),
            detail = element.getAttribute("detail"),
            rejectedProposal = rejected,
            replaced = replaced,
        )
    }

    private val CASE_CHILDREN = setOf("screen", "traits", "replaced")
}

/**
 * The `<identity-case ref="…"/>` pointers a screen's XML carries — one per **active** case involving
 * it, open or handled.
 *
 * Pointers are references, never a second copy: they are always derived from the active cases, written
 * by [append] when a screen is serialized, and brought up to date by [refresh] on a screen whose
 * snapshot is no longer in memory. Both produce the same lines in the same place, so a refreshed
 * file is byte-identical to one serialized afresh.
 */
object IdentityCasePointers {
    private val POINTER_LINE = Regex("""^[ ]*<identity-case ref="[^"]*" />\r?\n""", RegexOption.MULTILINE)

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
        // Only the pointer lines are touched, written with the line break the identity block uses, so a
        // file saved with Windows line endings — or with mixed ones — keeps every other byte.
        val crawlStart = xml.indexOf("<crawl ")
        val crawlEnd = xml.indexOf("</crawl>")
        if (crawlStart < 0 || crawlEnd < crawlStart) return xml
        val head = xml.substring(0, crawlStart)
        val block = POINTER_LINE.replace(xml.substring(crawlStart, crawlEnd), "")
        val tail = xml.substring(crawlEnd)

        val span = ScreenIdentitySpan.find(block) ?: return xml
        val lineBreak = ScreenIdentitySpan.lineBreakAt(block, span.lineStart)
        val pointers = StringBuilder().also { append(it, caseIds, span.depth) }.toString().replace("\n", lineBreak)
        return head + block.substring(0, span.end) + pointers + block.substring(span.end) + tail
    }

    /** Rewrites [xmlFile]'s pointers in place to [caseIds]. Does nothing when the file is missing. */
    fun refresh(xmlFile: File, caseIds: List<String>) {
        if (!xmlFile.exists()) return
        val before = xmlFile.readText(Charsets.UTF_8)
        val after = refresh(before, caseIds)
        if (after != before) xmlFile.writeText(after, Charsets.UTF_8)
    }
}
