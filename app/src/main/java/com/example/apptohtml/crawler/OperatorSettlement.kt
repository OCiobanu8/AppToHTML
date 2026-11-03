package com.example.apptohtml.crawler

import org.w3c.dom.Element
import java.io.File

/** One screen of a crawl folder as the operator's tool sees it: its crawl id and its loaded files. */
internal data class CaseScreen(
    val screenId: String,
    val capture: LoadedCapture,
)

/**
 * A crawl folder as the operator's tool reads it: every case, every screen it could load, and every
 * capture it could not, with why.
 *
 * Which files are screens is the crawler's rule ([SavedCrawlLoader.isCrawlScreenXml]), and each pair
 * is read through [CaptureIdentitySource], so the tool sees exactly the identities and first viewports
 * the crawler's own resume would.
 */
internal class CaseCrawl(
    val directory: File,
    val cases: List<IdentityCase>,
    val screens: List<CaseScreen>,
    val problems: Map<String, CaptureProblem>,
) {
    /** The loaded screen [screenId], or null when it is not in the folder or could not be loaded. */
    fun screen(screenId: String): CaseScreen? = screens.firstOrNull { it.screenId == screenId }

    /** The case [caseId], or null when the case file holds no such case. */
    fun case(caseId: String): IdentityCase? = cases.firstOrNull { it.id == caseId }

    companion object {
        private val SCREEN_ID = Regex("""^(screen_\d+)_""")

        /**
         * The refusal every check and settle gives while a settle that stopped part-way — HALF WRITTEN,
         * or its process killed between moves — still has its originals kept in [directory]; null when
         * there is none. One home, so the page's check and the command line's dry run agree.
         */
        fun stoppedSettle(directory: File): OperatorSettlement.Verdict.NotSettleable? =
            directory.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(".settle-") && it.listFiles().orEmpty().any { f -> f.name.endsWith(".before") } }
                .minByOrNull { it.name }
                ?.let { staging ->
                    OperatorSettlement.Verdict.NotSettleable(
                        "an earlier settle stopped part-way and kept the originals in ${staging.absolutePath}: copy each " +
                            "<name>.before back over <name> in the crawl folder, delete that folder, then settle again",
                    )
                }

        /**
         * Reads [directory]. Throws [MalformedIdentityCases] when the case file cannot be read: a
         * tool that settled cases against a misread case file would write the wrong one back.
         */
        fun load(directory: File): CaseCrawl {
            val cases = IdentityCaseStore.read(IdentityCaseStore.fileIn(directory))
            val screens = mutableListOf<CaseScreen>()
            val problems = sortedMapOf<String, CaptureProblem>()
            directory.listFiles().orEmpty()
                .filter { it.isFile && SavedCrawlLoader.isCrawlScreenXml(it) }
                .sortedBy { it.name }
                .forEach { xml ->
                    // The crawl's own id is in the file; the file name's prefix is only the fallback
                    // for a capture too broken to say.
                    val fallbackId = SCREEN_ID.find(xml.name)?.groupValues?.get(1) ?: xml.name.removeSuffix(".xml")
                    CaptureIdentitySource.load(xml, requirePage = false).fold(
                        onSuccess = { capture ->
                            val screenId = ScreenXmlReader.readHead(xml)?.screenId ?: fallbackId
                            screens += CaseScreen(screenId, capture)
                        },
                        onFailure = { error ->
                            problems[fallbackId] = (error as? CaptureProblemException)?.problem
                                ?: CaptureProblem(xml, error.message.orEmpty())
                        },
                    )
                }
            return CaseCrawl(directory, cases, screens.sortedBy { it.screenId }, problems)
        }
    }
}

/**
 * "Do the traits an operator wrote for a case hold?" — checked offline, against the crawl's saved
 * captures, before anything is written.
 *
 * The rule is the crawler's own, composed and never re-derived. The operator's identity must hold on
 * its own screen's first viewport, no other settled identity may hold there
 * ([TraitEvaluator.matchKnownScreens], the recognition direction), and it may hold on no other
 * screen's first viewport ([IdentitySettlement.decide], the settlement direction). A screen whose
 * capture cannot be read is never counted as a non-collision.
 */
internal object OperatorSettlement {

    /** What the check concluded about one case and one set of traits. */
    sealed interface Verdict {
        /** What the tool reports and turns into an exit code. */
        val status: ScreenIdentityReport.Status

        /** One sentence, the same in the page and on the command line. */
        fun describe(): String

        /** The traits hold, on that screen and on no other. [candidate] is what a settle writes. */
        data class Holds(val screenId: String, val candidate: ScreenIdentity, val checkedAgainst: Int) : Verdict {
            override val status get() = ScreenIdentityReport.Status.HOLDS
            override fun describe() =
                "HOLDS on $screenId: no other settled identity holds there, and these traits hold on no " +
                    "other screen ($checkedAgainst checked)"
        }

        /** The traits assert nothing present, so they would identify nothing. */
        object AssertsNothing : Verdict {
            override val status get() = ScreenIdentityReport.Status.UNSETTLED
            override fun describe() =
                "UNSETTLED: these traits assert nothing present; add a has-list or a has-control"
        }

        /** The traits do not hold on the screen they are for. */
        data class FailsOnOwnScreen(val screenId: String, val failing: List<Trait>) : Verdict {
            override val status get() = ScreenIdentityReport.Status.DOES_NOT_HOLD
            override fun describe() = "DOES_NOT_HOLD on $screenId: ${describeFailing(failing)}"
        }

        /**
         * The identity does not name its own screen: run against the screen's capture, the crawler's
         * recognition would not pick this screen, because the identity's package or name does not
         * match what the capture shows. The crawler could never recognise the screen by it.
         */
        data class DoesNotNameScreen(val screenId: String) : Verdict {
            override val status get() = ScreenIdentityReport.Status.DOES_NOT_HOLD
            override fun describe() =
                "DOES_NOT_HOLD on $screenId: the identity's package or name does not match its own capture, " +
                    "so the crawler would never recognise the screen by it"
        }

        /**
         * The traits would not tell this screen apart. [forward] are screens whose settled identity
         * holds on this one; [reverse] are screens these traits also hold on.
         */
        data class NotUnique(val screenId: String, val forward: List<String>, val reverse: List<String>) : Verdict {
            override val status get() = ScreenIdentityReport.Status.NOT_UNIQUE
            override fun describe() = buildString {
                append("NOT_UNIQUE for ").append(screenId).append(": ")
                val parts = mutableListOf<String>()
                if (reverse.isNotEmpty()) parts += "these traits also hold on ${reverse.joinToString(", ")}"
                if (forward.isNotEmpty()) parts += "the settled identity of ${forward.joinToString(", ")} holds on this screen"
                append(parts.joinToString("; "))
            }
        }

        /** Some screens' captures could not be read, so uniqueness against them is unknown. */
        data class CannotCheck(val screenIds: List<String>, val problems: List<String>) : Verdict {
            override val status get() = ScreenIdentityReport.Status.INVALID_INPUT
            override fun describe() =
                "INVALID_INPUT: uniqueness cannot be checked against ${screenIds.joinToString(", ")}: " +
                    problems.joinToString("; ")
        }

        /** The request itself cannot be judged: no such case, a closed one, unreadable traits. */
        data class NotSettleable(val reason: String) : Verdict {
            override val status get() = ScreenIdentityReport.Status.INVALID_INPUT
            override fun describe() = "INVALID_INPUT: $reason"
        }
    }

    /**
     * The traits in [text], which must be one `<traits>` element and carry no `settled-by`: the
     * operator says *what* the screen asserts, the tool records *who*. Throws
     * [MalformedScreenIdentity] naming what is wrong.
     */
    fun parseTraits(text: String): List<Trait> {
        val root = try {
            parseXmlElement(text.trim())
        } catch (e: Exception) {
            throw MalformedScreenIdentity("the traits are not well-formed XML: ${e.message}", e)
        }
        if (root.tagName != "traits") {
            throw MalformedScreenIdentity("the traits must be one <traits> element, found <${root.tagName}>")
        }
        // Wrapped so the block is read by the one parser every other <traits> goes through.
        val holder = root.ownerDocument.createElement("holder").also { it.appendChild(root) }
        return ScreenIdentityXml.parseTraits(holder as Element, ScreenIdentityXml.TraitsStamp.FORBIDDEN).traits
    }

    /** Checks the traits written in [traitsText] for [caseId] against [crawl]. Writes nothing. */
    fun check(crawl: CaseCrawl, caseId: String, traitsText: String): Verdict {
        CaseCrawl.stoppedSettle(crawl.directory)?.let { return it }
        val case = crawl.case(caseId)
            ?: return Verdict.NotSettleable("there is no case $caseId in ${IdentityCaseStore.FILE_NAME}")
        if (case.status == IdentityCaseStatus.CLOSED) {
            return Verdict.NotSettleable("$caseId is closed: the resumed crawl already confirmed it on the live screen")
        }
        val traits = try {
            parseTraits(traitsText)
        } catch (e: MalformedScreenIdentity) {
            return Verdict.NotSettleable("the traits are refused: ${e.message}")
        }
        val screen = crawl.screen(case.raisedOn)
            ?: return Verdict.NotSettleable(
                crawl.problems[case.raisedOn]?.let { "the capture of ${case.raisedOn} cannot be used: $it" }
                    ?: "${case.raisedOn}, which $caseId is raised on, is not in this crawl folder"
            )
        val ownTree = screen.capture.firstViewport
            ?: return Verdict.NotSettleable("${screen.capture.xmlFile.name} records no scroll steps to check against")
        // A settle writes both files; a screen whose own page is gone, or carries no identity block,
        // cannot be settled, so the check does not promise that it can.
        val page = screen.capture.htmlFile
        if (!page.isFile || CaptureIdentitySource.locatePageBlock(page.readText(Charsets.UTF_8)) == null) {
            return Verdict.NotSettleable(
                "${page.name} is missing or has no identity block, so the screen cannot be settled; " +
                    "a screen is its .xml and .html together",
            )
        }
        val candidate = screen.capture.identity.copy(traits = traits, settledBy = IdentityOrigin.OPERATOR)
        return judge(screen.screenId, candidate, ownTree, crawl)
    }

    private fun judge(
        screenId: String,
        candidate: ScreenIdentity,
        ownTree: AccessibilityNodeSnapshot,
        crawl: CaseCrawl,
    ): Verdict {
        val ownContext = TraitEvaluationContext.of(ownTree, candidate.name)
        val others = crawl.screens
            .filterNot { it.screenId == screenId }
            .map { KnownScreen(it.screenId, it.capture.identity, it.capture.firstViewport) }

        val settlement = IdentitySettlement.decide(candidate, ownContext, others)
        when (settlement) {
            IdentitySettlement.Decision.AssertsNothing -> return Verdict.AssertsNothing
            is IdentitySettlement.Decision.FailsOnOwnScreen -> return Verdict.FailsOnOwnScreen(screenId, settlement.failing)
            else -> Unit
        }
        // Recognition, run on this screen's own capture, must land on this screen and only on it:
        // the forward direction is the crawler's question "which known screen is this?".
        val match = TraitEvaluator.matchKnownScreens(
            ownContext,
            others.associate { it.screenId to it.identity } + (screenId to candidate),
        )
        val namesItself = when (match) {
            is ScreenTraitMatch.One -> match.screenId == screenId
            is ScreenTraitMatch.Ambiguous -> screenId in match.screenIds
            is ScreenTraitMatch.None -> false
        }
        if (!namesItself) return Verdict.DoesNotNameScreen(screenId)
        val forward = (match as? ScreenTraitMatch.Ambiguous)?.screenIds.orEmpty() - screenId
        val reverse = (settlement as? IdentitySettlement.Decision.AlsoHolds)?.screenIds.orEmpty()
        if (forward.isNotEmpty() || reverse.isNotEmpty()) {
            return Verdict.NotUnique(screenId, forward.sorted(), reverse.sorted())
        }
        val unreadable = (settlement as? IdentitySettlement.Decision.CannotCheckAgainst)?.screenIds.orEmpty()
        val problems = crawl.problems.filterKeys { it != screenId }
        if (unreadable.isNotEmpty() || problems.isNotEmpty()) {
            return Verdict.CannotCheck(
                screenIds = (unreadable + problems.keys).distinct().sorted(),
                problems = unreadable.map { "$it records no scroll steps" } + problems.values.map(CaptureProblem::toString),
            )
        }
        return Verdict.Holds(screenId, candidate, checkedAgainst = others.size)
    }
}

/** One case, as the overview and the text survey both list it. */
internal data class CaseOverviewRow(
    val caseId: String,
    val status: IdentityCaseStatus,
    val kind: IdentityCaseKind,
    val raisedOn: String,
    val screenName: String,
    val relatedScreenIds: List<String>,
    val detail: String,
) {
    /** Whether the operator can work this case: open or handled. A closed case is history. */
    val isActionable: Boolean get() = status.isActive

    /** What a resume will do with it, in the words the survey and the overview use. */
    val onResume: String
        get() = when (status) {
            IdentityCaseStatus.OPEN -> "left as it is"
            IdentityCaseStatus.HANDLED -> "confirmed on the live screen"
            IdentityCaseStatus.CLOSED -> "history"
        }
}

/** The rows the overview page and the text survey both print, so they cannot disagree. */
internal object CaseOverview {
    /** One row per case in [crawl], sorted by case id. */
    fun rows(crawl: CaseCrawl): List<CaseOverviewRow> = crawl.cases.sortedBy(IdentityCase::id).map { case ->
        CaseOverviewRow(
            caseId = case.id,
            status = case.status,
            kind = case.kind,
            raisedOn = case.raisedOn,
            screenName = crawl.screen(case.raisedOn)?.capture?.xmlFile?.name?.removeSuffix(".xml") ?: case.raisedOn,
            relatedScreenIds = case.relatedScreenIds,
            detail = case.detail,
        )
    }
}
