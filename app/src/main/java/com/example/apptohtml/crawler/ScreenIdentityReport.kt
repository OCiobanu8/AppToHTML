package com.example.apptohtml.crawler

import java.io.File

/**
 * Runs the validator over saved captures and renders what it found.
 *
 * Separate from [ScreenIdentityValidator] so the answers and their presentation stay apart: the
 * validator decides, this only formats. Output is deterministic — every list is sorted — because a
 * report an operator cannot diff twice is not evidence.
 */
internal object ScreenIdentityReport {

    /** What the run concluded, and what a caller turns into an exit status. */
    enum class Status {
        HOLDS,
        NOT_UNIQUE,
        DOES_NOT_HOLD,
        UNSETTLED,
        INVALID_INPUT,
    }

    /** The rendered report and the status a caller turns into an exit code. */
    data class Outcome(val status: Status, val text: String)

    /**
     * Validates [targetXml] against [observedXml], with every capture in [knownScreensDir] deciding
     * uniqueness.
     *
     * The target is included among the known screens even when the directory does not contain it:
     * uniqueness means *exactly one* known screen holds, and leaving the target out would make its
     * own match read as none.
     */
    fun validate(
        targetXml: File,
        observedXml: File,
        knownScreensDir: File?,
        isRootScreen: Boolean,
    ): Outcome {
        val target = CaptureIdentitySource.load(targetXml).getOrElse { error ->
            return invalid(error)
        }
        val observedCapture = CaptureIdentitySource.load(observedXml).getOrElse { error ->
            return invalid(error)
        }
        val observedRoot = observedCapture.firstViewport
            ?: return Outcome(
                Status.INVALID_INPUT,
                "INVALID_INPUT\n  ${observedXml.name}: records no scroll steps, so there is no " +
                    "screen to validate against.",
            )

        // Two inputs the tool must refuse rather than judge, because the crawler would not reach a
        // verdict on them either and a verdict it disagrees with is worse than none.
        if (target.identity.name == null) {
            return Outcome(
                Status.INVALID_INPUT,
                "INVALID_INPUT\n  ${targetXml.name}: the identity has no name half. The crawler " +
                    "compares names before anything else and a nameless identity never matches, so " +
                    "any verdict here would contradict it. Restore the title and name-package " +
                    "attributes on <screen-identity>.",
            )
        }
        val targetPackage = target.identity.packageName
        val observedPackage = observedCapture.identity.packageName
        if (targetPackage != null && observedPackage != null && targetPackage != observedPackage) {
            return Outcome(
                Status.INVALID_INPUT,
                "INVALID_INPUT\n  ${targetXml.name} is from $targetPackage but " +
                    "${observedXml.name} is from $observedPackage. These are different apps; " +
                    "there is no screen identity question to answer between them.",
            )
        }

        val known = knownScreensDir?.let(::loadKnownScreens) ?: emptyList()
        val problems = known.mapNotNull { it.exceptionOrNull() }
        if (problems.isNotEmpty()) {
            return Outcome(
                Status.INVALID_INPUT,
                buildString {
                    appendLine("INVALID_INPUT")
                    problems.sortedBy { it.message }.forEach { appendLine("  ${it.message}") }
                }.trimEnd(),
            )
        }
        // No duplicate-id guard, deliberately. A capture's screenId IS its file base name
        // (`CaptureIdentitySource.load`), and a directory cannot hold two files with one name, so
        // two known screens sharing an id is unreachable. The target is filtered out before being
        // re-added, so it cannot duplicate itself either. Were the id ever derived from the file's
        // *contents* instead, a guard would be needed: the evaluator takes an id-keyed map, and one
        // of the pair would silently decide uniqueness for both.
        val knownScreens = known.mapNotNull { it.getOrNull() }
            .filterNot { it.screenId == target.screenId }
            .plus(target)
            .sortedBy { it.screenId }

        val report = ScreenIdentityValidator.validate(
            target = target,
            observed = observedRoot,
            observedName = observedCapture.identity.name,
            knownScreens = knownScreens,
            isRootScreen = isRootScreen,
        )
        return Outcome(report.outcome.toStatus(), render(target, report))
    }

    /** Every capture in [dir], each loaded as its own result so one bad pair does not hide the rest. */
    fun loadKnownScreens(dir: File): List<Result<LoadedCapture>> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.endsWith("_merged_accessibility.xml") }
            .sortedBy { it.name }
            .map(CaptureIdentitySource::load)

    private fun invalid(error: Throwable): Outcome {
        val detail = (error as? CaptureProblemException)?.problem?.toString() ?: error.message
        return Outcome(Status.INVALID_INPUT, "INVALID_INPUT\n  $detail")
    }

    private fun ScreenIdentityValidator.Outcome.toStatus(): Status = when (this) {
        ScreenIdentityValidator.Outcome.HOLDS -> Status.HOLDS
        ScreenIdentityValidator.Outcome.NOT_UNIQUE -> Status.NOT_UNIQUE
        ScreenIdentityValidator.Outcome.DOES_NOT_HOLD -> Status.DOES_NOT_HOLD
        ScreenIdentityValidator.Outcome.UNSETTLED -> Status.UNSETTLED
    }

    private fun render(
        target: LoadedCapture,
        report: ScreenIdentityValidator.Report,
    ): String = buildString {
        appendLine(report.outcome.name)
        appendLine("  screen: ${target.screenId}")
        appendLine("  traits: ${report.traitVerdict} (${target.identity.traits.size} asserted)")
        report.failingTraits.forEach { trait -> appendLine("    failed: ${describe(trait)}") }

        // Uniqueness, spelled out — a collision names every screen, because choosing between them
        // is the operator's decision and not the tool's.
        when (val match = report.knownScreenMatch) {
            is ScreenTraitMatch.One -> appendLine("  unique: yes, only ${match.screenId} holds here")
            is ScreenTraitMatch.None -> appendLine("  unique: no known screen holds here")
            is ScreenTraitMatch.Ambiguous ->
                appendLine("  unique: NO — ${match.screenIds.joinToString(", ")} all hold here")
        }
        if (report.knownScreenMatch.unsettledScreenIds.isNotEmpty()) {
            appendLine(
                "  unsettled screens (asserting nothing, so never candidates): " +
                    report.knownScreenMatch.unsettledScreenIds.size
            )
        }

        // Reported beside the headline, never folded into it.
        val set = report.elementSet
        appendLine("  element set: ${set.reason} (matched=${set.matched})")
        if (set.missing.isNotEmpty()) {
            appendLine("    missing (${set.missing.size}):")
            set.missing.sortedBy { it.encoded }.forEach { appendLine("      ${it.encoded}") }
        }
        if (set.extra.isNotEmpty()) {
            appendLine("    extra (${set.extra.size}):")
            set.extra.sortedBy { it.encoded }.forEach { appendLine("      ${it.encoded}") }
        }

        val unresolved = report.elements.filter {
            it.resolution != ScreenIdentityValidator.Resolution.RESOLVED
        }
        appendLine(
            "  re-clickable: ${report.elements.size - unresolved.size}/${report.elements.size}"
        )
        unresolved.sortedBy { it.element.encoded }.forEach { entry ->
            appendLine("    ${entry.resolution}: ${entry.element.encoded}")
            entry.candidates.forEach { appendLine("      candidate: $it") }
        }

        appendDiagnostics("target", report.targetDiagnostics)
        appendDiagnostics("observed", report.observedDiagnostics)

        // Machine-readable facts, so a caller deciding something stricter than the headline reads
        // values rather than scraping the prose above. Renaming a heading must not silently disable
        // a --strict flag somewhere downstream.
        appendLine("  elementSetMatched=${report.elementSet.matched}")
        appendLine("  unresolvedElements=${unresolved.size}")

        if (report.suggestedElements.isNotEmpty()) {
            appendLine("  paste these into <screen-identity> in BOTH files:")
            val block = StringBuilder()
            ScreenIdentityXml.appendElements(block, report.suggestedElements, depth = 3)
            append(block)
        }
    }.trimEnd()

    /** One trait, in the words an operator would use to find it in the file. */
    private fun describe(trait: Trait): String = when (trait) {
        is HasList ->
            "has-list ${trait.containerResourceId} with ${trait.minRows}+ rows carrying " +
                trait.rowChildResourceIds.joinToString(", ")
        is HasControl -> "has-control ${trait.element.fingerprint.label}"
        is LacksControl -> "lacks-control ${trait.element.fingerprint.label}"
    }

    /** Counted and reported, never acted on: a hard-to-describe screen is still a valid screen. */
    private fun StringBuilder.appendDiagnostics(
        side: String,
        diagnostics: ScreenIdentityValidator.Diagnostics,
    ) {
        if (diagnostics.weakElements.isEmpty() && diagnostics.collisions.isEmpty()) return
        appendLine(
            "  $side diagnostics: ${diagnostics.weakElements.size} weak, " +
                "${diagnostics.collisions.size} colliding"
        )
        diagnostics.collisions.sorted().take(5).forEach { appendLine("    collision: $it") }
        diagnostics.weakElements.sorted().take(5).forEach { appendLine("    weak: $it") }
    }
}
