package com.example.apptohtml.crawler

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The host-side entry point: runs the validator over real captures on the JVM, with no device.
 *
 * It lives in the unit-test source set because that is the only JVM classpath this Android module
 * builds, and it is **inert unless asked for**. With no `-Da2h.*` property set every case is skipped
 * by an assumption, so a plain `./gradlew test` runs no validation and writes no report — the
 * suite's own result stays a statement about the code, not about whatever captures happen to be on
 * this machine.
 *
 * Validate one screen against an observation:
 * ```
 * ./gradlew testDebugUnitTest --tests "*ScreenIdentityValidationEntryPoint" \
 *   -Da2h.target=<path>/screen_00003_apps.xml \
 *   -Da2h.observed=<path>/screen_00003_apps.xml \
 *   -Da2h.known=<crawl dir>
 * ```
 *
 * Survey a whole crawl — every screen validated against its own capture:
 * ```
 * ./gradlew testDebugUnitTest --tests "*ScreenIdentityValidationEntryPoint" -Da2h.crawl=<crawl dir>
 * ```
 */
class ScreenIdentityValidationEntryPoint {

    @Test
    fun `validate one screen`() {
        val target = property("a2h.target")
        assumeTrue("set -Da2h.target to validate one screen", target != null)
        val observed = property("a2h.observed") ?: target!!
        val known = property("a2h.known")

        val outcome = ScreenIdentityReport.validate(
            targetXml = File(target!!),
            observedXml = File(observed),
            knownScreensDir = known?.let(::File),
            isRootScreen = property("a2h.root")?.toBoolean() ?: false,
        )
        println(outcome.text)
        println("status=${outcome.status}")
    }

    @Test
    fun `survey a crawl`() {
        val crawl = property("a2h.crawl")
        assumeTrue("set -Da2h.crawl to survey a whole crawl", crawl != null)
        val dir = File(crawl!!)

        val loaded = ScreenIdentityReport.loadKnownScreens(dir)
        val usable = loaded.mapNotNull { it.getOrNull() }
        val broken = loaded.mapNotNull { it.exceptionOrNull() }

        println("=== ${dir.name}: ${loaded.size} captures, ${usable.size} usable ===")
        broken.sortedBy { it.message }.forEach { println("INVALID_INPUT  ${it.message}") }

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
            println("${outcome.status.name.padEnd(14)} ${capture.screenId}")
        }

        println("=== tally ===")
        tally.entries.sortedBy { it.key.name }.forEach { (status, count) ->
            println("${status.name.padEnd(14)} $count")
        }
        // Machine-readable, so a caller does not have to scrape the headings above to learn whether
        // the survey found anything wrong. The worst status is what a survey's exit code means: a
        // crawl containing one screen that no longer holds is not a successful survey.
        val worst = listOf(
            ScreenIdentityReport.Status.INVALID_INPUT,
            ScreenIdentityReport.Status.NOT_UNIQUE,
            ScreenIdentityReport.Status.DOES_NOT_HOLD,
            ScreenIdentityReport.Status.UNSETTLED,
            ScreenIdentityReport.Status.HOLDS,
        ).firstOrNull { it in tally.keys || (it == ScreenIdentityReport.Status.INVALID_INPUT && broken.isNotEmpty()) }
            ?: ScreenIdentityReport.Status.INVALID_INPUT
        println("surveyUsable=${usable.size}")
        println("surveyUnreadable=${broken.size}")
        println("status=$worst")
    }

    /** Blank properties are treated as absent, so an empty `-Da2h.x=` does not half-enable a run. */
    private fun property(name: String): String? =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
}
