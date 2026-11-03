package com.example.apptohtml.crawler

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The host-side entry point: runs the validator and the case tool over real captures on the JVM,
 * with no device except where a push asks for one.
 *
 * It lives in the unit-test source set because that is the only JVM classpath this Android module
 * builds, and it is **inert unless asked for**. With no `-Da2h.*` property set every case is skipped
 * by an assumption, so a plain `./gradlew test` runs no validation, serves nothing and writes nothing
 * — the suite's own result stays a statement about the code, not about whatever captures happen to
 * be on this machine. Which job runs is [CaseToolMode]'s decision, made once from the properties.
 *
 * Validate one screen against an observation:
 * ```
 * ./gradlew testDebugUnitTest --tests "*ScreenIdentityValidationEntryPoint" \
 *   -Da2h.target=<path>/screen_00003_apps.xml \
 *   -Da2h.observed=<path>/screen_00003_apps.xml \
 *   -Da2h.known=<crawl dir>
 * ```
 *
 * Survey a whole crawl as text — every screen validated against its own capture, then its cases:
 * ```
 * ./gradlew testDebugUnitTest --tests "*ScreenIdentityValidationEntryPoint" -Da2h.crawl=<crawl dir> -Da2h.survey=true
 * ```
 *
 * Settle a case from the command line (`a2h.dryRun=true` checks only; `a2h.push=true` needs `a2h.adb`):
 * ```
 * ... -Da2h.crawl=<crawl dir> -Da2h.settle=case_000 -Da2h.traits=<traits.xml>
 * ```
 *
 * Serve the case overview (the script picks the port, writes the token to a one-time file the tool
 * deletes on reading — a `-D` value would be recorded by Gradle — and opens the browser):
 * ```
 * ... -Da2h.crawl=<crawl dir> -Da2h.port=<port> -Da2h.tokenFile=<file> [-Da2h.adb=<adb>]
 * ```
 */
class ScreenIdentityValidationEntryPoint {

    private val mode: CaseToolMode get() = selected

    @Test
    fun `validate one screen`() {
        val job = mode as? CaseToolMode.ValidateOne
        assumeTrue("set -Da2h.target to validate one screen", job != null)
        val outcome = ScreenIdentityReport.validate(
            targetXml = job!!.target,
            observedXml = job.observed,
            knownScreensDir = job.known,
            isRootScreen = job.isRoot,
        )
        println(outcome.text)
        println("status=${outcome.status}")
    }

    @Test
    fun `survey a crawl`() {
        val job = mode as? CaseToolMode.Survey
        assumeTrue("set -Da2h.crawl and -Da2h.survey=true to survey a whole crawl", job != null)
        println(CaseToolJobs.survey(job!!.crawl))
    }

    @Test
    fun `settle a case`() {
        val job = mode as? CaseToolMode.Settle
        assumeTrue("set -Da2h.crawl, -Da2h.settle and -Da2h.traits to settle a case", job != null)
        println(CaseToolJobs.settle(job!!))
    }

    @Test
    fun `serve the case tool`() {
        val job = mode as? CaseToolMode.Serve
        assumeTrue("set -Da2h.crawl, -Da2h.port and -Da2h.tokenFile to serve the case overview", job != null)
        val server = CaseToolServer(
            crawlDir = job!!.crawl,
            token = job.token,
            port = job.port,
            device = { crawl -> job.device?.let { settings -> AdbDeviceCrawl.forCrawl(settings, crawl) } },
            remoteDir = { crawl -> AdbDeviceCrawl.remoteDirFor(job.device, crawl) },
        )
        server.start()
        // The token is not printed: the script already holds it, and this output is written to a log.
        println("caseTool=serving port=${server.address.port}")
        server.awaitDone()
        println("caseTool=stopped")
    }

    private companion object {
        /**
         * Chosen once per test JVM. JUnit builds an instance per test, and choosing per instance would let
         * the first one consume the one-time token file before the serving job reads it.
         */
        val selected: CaseToolMode by lazy { CaseToolMode.select(::property) }

        /** Blank properties are treated as absent, so an empty `-Da2h.x=` does not half-enable a run. */
        fun property(name: String): String? =
            System.getProperty(name)?.takeIf { it.isNotBlank() }
    }
}

/**
 * The command-line jobs, as functions returning exactly what the entry point prints. Kept out of the
 * inert entry point so that what the script reads — every line and the closing `status=` — is tested.
 */
internal object CaseToolJobs {
    /** The text survey of [dir]: one line per screen, the tally, the cases section and the status. */
    fun survey(dir: java.io.File): String = buildString {
        val loaded = ScreenIdentityReport.loadKnownScreens(dir)
        val usable = loaded.mapNotNull { it.getOrNull() }
        val broken = loaded.mapNotNull { it.exceptionOrNull() }

        appendLine("=== ${dir.name}: ${loaded.size} captures, ${usable.size} usable ===")
        broken.sortedBy { it.message }.forEach { appendLine("INVALID_INPUT  ${it.message}") }

        val tally = linkedMapOf<ScreenIdentityReport.Status, Int>()
        usable.sortedBy { it.screenId }.forEach { capture ->
            val outcome = ScreenIdentityReport.validate(
                targetXml = capture.xmlFile,
                observedXml = capture.xmlFile,
                knownScreensDir = dir,
                // Read from the artifact's own is-root flag, not guessed from the file name: the
                // crawl records it, and a crawl whose root is not screen 0 would be misjudged.
                isRootScreen = ScreenXmlReader.readHead(capture.xmlFile)?.isRoot ?: false,
            )
            tally[outcome.status] = (tally[outcome.status] ?: 0) + 1
            appendLine("${outcome.status.name.padEnd(14)} ${capture.screenId}")
        }

        appendLine("=== tally ===")
        tally.entries.sortedBy { it.key.name }.forEach { (status, count) ->
            appendLine("${status.name.padEnd(14)} $count")
        }
        val cases = SurveyCases.render(dir)
        appendLine(cases.text)
        // Machine-readable, so a caller does not have to scrape the headings above to learn whether
        // the survey found anything wrong.
        val worst = SurveyCases.worst(tally.keys, unreadable = broken.isNotEmpty() || cases.refused)
        appendLine("surveyUsable=${usable.size}")
        appendLine("surveyUnreadable=${broken.size}")
        append("status=$worst")
    }

    /** A command-line settle for [job]: the report, then its `status=` line. A dry run writes nothing. */
    fun settle(job: CaseToolMode.Settle): String {
        val traits = job.traits
        if (!traits.isFile) {
            return "INVALID_INPUT: no traits file at ${traits.absolutePath}\nstatus=${ScreenIdentityReport.Status.INVALID_INPUT}"
        }
        val crawl = runCatching { CaseCrawl.load(job.crawl) }.getOrNull()
        val device = try {
            job.device?.let { settings -> crawl?.let { AdbDeviceCrawl.forCrawl(settings, it) } }
        } catch (e: IllegalStateException) {
            return "INVALID_INPUT: cannot push: ${e.message}\nstatus=${ScreenIdentityReport.Status.INVALID_INPUT}"
        }
        val outcome = CaseSettleService.settle(
            directory = job.crawl,
            caseId = job.caseId,
            traitsText = traits.readText(Charsets.UTF_8),
            write = !job.dryRun,
            device = device,
        )
        return SettleReport.render(job.caseId, job.dryRun, outcome, crawl?.let { AdbDeviceCrawl.remoteDirFor(job.device, it) }) +
            "\nstatus=${SettleReport.status(job.dryRun, outcome)}"
    }
}

/** The survey's cases section: every case with its status, screen and detail, from the overview's rows. */
internal object SurveyCases {
    /** The section's [text], and whether the case file was [refused]. */
    data class Section(val text: String, val refused: Boolean)

    /** The cases section for the crawl in [dir]; a refused case file is reported, not thrown. */
    fun render(dir: java.io.File): Section {
        val crawl = try {
            CaseCrawl.load(dir)
        } catch (refused: MalformedIdentityCases) {
            return Section("=== cases ===\nINVALID_INPUT  ${refused.message}", refused = true)
        }
        val text = buildString {
            appendLine("=== cases ===")
            val rows = CaseOverview.rows(crawl)
            if (rows.isEmpty()) appendLine("(none)")
            rows.forEach { row ->
                appendLine(
                    "${row.caseId}  ${row.status.xmlValue.padEnd(8)} ${row.kind.xmlValue.padEnd(24)} " +
                        "${row.screenName}  on resume: ${row.onResume}"
                )
                appendLine("    ${row.detail}")
            }
        }.trimEnd()
        return Section(text, refused = false)
    }

    /**
     * The survey's status: the worst of the screens' [statuses], and INVALID_INPUT when a capture or
     * the case file could not be read ([unreadable]). A crawl with one screen that no longer holds, or
     * a case file that cannot be trusted, is not a successful survey.
     */
    fun worst(statuses: Set<ScreenIdentityReport.Status>, unreadable: Boolean): ScreenIdentityReport.Status =
        listOf(
            ScreenIdentityReport.Status.INVALID_INPUT,
            ScreenIdentityReport.Status.NOT_UNIQUE,
            ScreenIdentityReport.Status.DOES_NOT_HOLD,
            ScreenIdentityReport.Status.UNSETTLED,
            ScreenIdentityReport.Status.HOLDS,
        ).firstOrNull { it in statuses || (it == ScreenIdentityReport.Status.INVALID_INPUT && unreadable) }
            ?: ScreenIdentityReport.Status.INVALID_INPUT
}

/** The `adb push` commands that bring files written without a device into the device crawl folder. */
internal object PushCommands {
    /** One command per file in [files]; without a known [remoteDir], the file names and where they go. */
    fun lines(files: List<java.io.File>, remoteDir: String?): List<String> = files.map { file ->
        if (remoteDir == null) {
            "${file.name} (into the app's crawl folder on the device)"
        } else {
            "adb push \"${file.absolutePath}\" \"$remoteDir/${file.name}\""
        }
    }
}

/** What a command-line settle printed, and the status the script turns into an exit code. */
internal object SettleReport {
    /** Holds, but a write or push did not complete; the script's exit code 6. */
    const val SETTLE_FAILED = "SETTLE_FAILED"

    /** The report a command-line settle of [caseId] prints. */
    fun render(caseId: String, dryRun: Boolean, outcome: CaseSettleService.Outcome, remoteDir: String? = null): String = buildString {
        appendLine("SETTLE $caseId${if (dryRun) " (dry run)" else ""}")
        appendLine("  ${outcome.verdict.describe()}")
        if (dryRun && outcome.verdict is OperatorSettlement.Verdict.Holds) appendLine("  would write the screen's .xml and .html and mark $caseId handled")
        outcome.written.forEach { appendLine("  written: ${it.name}") }
        outcome.pushed.forEach { appendLine("  pushed: $it") }
        if (outcome.toPush.isNotEmpty()) {
            appendLine("  still to push, before the crawl resumes:")
            PushCommands.lines(outcome.toPush, remoteDir).forEach { appendLine("    $it") }
        }
        outcome.problem?.let { appendLine("  $it") }
    }.trimEnd()

    /** The status line a command-line settle ends with, which the script maps to its exit code. */
    fun status(dryRun: Boolean, outcome: CaseSettleService.Outcome): String = when {
        outcome.verdict !is OperatorSettlement.Verdict.Holds -> outcome.verdict.status.name
        dryRun -> ScreenIdentityReport.Status.HOLDS.name
        outcome.problem != null -> SETTLE_FAILED
        else -> ScreenIdentityReport.Status.HOLDS.name
    }
}
