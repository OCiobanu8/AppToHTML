package com.example.apptohtml.crawler

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where a `<screen-identity>` element sits in a screen's XML text: from the start of its line to the
 * end of the line that closes it. One home for finding it, used by the pointer refresh and the settle.
 */
internal data class ScreenIdentitySpan(val lineStart: Int, val end: Int, val depth: Int) {
    companion object {
        /**
         * The first `<screen-identity>` in [text], whatever its line endings; [end] is just past the
         * line break that ends its last line. Null when there is none.
         */
        fun find(text: String, from: Int = 0): ScreenIdentitySpan? {
            val start = text.indexOf("<screen-identity", from)
            if (start < 0) return null
            val tagEnd = text.indexOf(">", start)
            if (tagEnd < 0) return null
            val elementEnd = if (text[tagEnd - 1] == '/') {
                tagEnd + 1
            } else {
                val closing = text.indexOf("</screen-identity>", start)
                if (closing < 0) return null
                closing + "</screen-identity>".length
            }
            val lineBreak = text.indexOf('\n', elementEnd)
            val end = if (lineBreak < 0) text.length else lineBreak + 1
            val lineStart = text.lastIndexOf('\n', start) + 1
            return ScreenIdentitySpan(lineStart, end, depth = (start - lineStart) / 2)
        }

        /** The line break [text] uses at the end of the line holding [index]: CRLF or LF. */
        fun lineBreakAt(text: String, index: Int): String {
            val lineBreak = text.indexOf('\n', index)
            return if (lineBreak > 0 && text[lineBreak - 1] == '\r') "\r\n" else "\n"
        }
    }
}

/**
 * A settle that could not be written. [nothingWritten] is true when every file is as it was before
 * the settle began; false only when a failed move could not be undone, in which case the message
 * names the files to restore and where their originals were kept.
 */
internal class SettleWriteRefused(
    message: String,
    val nothingWritten: Boolean = true,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** How a settle replaces and restores files: a seam, so a failing move can be tested on any platform. */
internal interface SettleFileOps {
    /** Replaces [to] with [from], atomically where the filesystem allows. */
    fun move(from: File, to: File)

    /** Puts [backup]'s bytes back into [target]. */
    fun restore(backup: File, target: File)

    companion object {
        val REAL = object : SettleFileOps {
            override fun move(from: File, to: File) {
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }

            override fun restore(backup: File, target: File) {
                Files.copy(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/**
 * Writes an operator's settled identity into a screen's `.xml` and `.html`, and marks its case
 * handled, as one change.
 *
 * Only the identity block of each screen file is replaced, by the crawler's own producers
 * ([AccessibilityXmlSerializer.screenIdentityLines], [HtmlRenderer.renderIdentityBlock]); every other
 * byte stays as it was. All three new texts are written into a temporary folder under the same
 * names, read back through [CaptureIdentitySource] and [IdentityCaseStore] and required to say
 * exactly what was meant, and only then moved into place. The originals are kept beside them first,
 * so a move that fails part-way is undone: every refusal leaves every file as it was.
 */
internal object SettleWriter {

    /** The three files a settle replaced, and the case as it now reads. */
    data class Written(val xml: File, val html: File, val caseFile: File, val case: IdentityCase) {
        val files: List<File> get() = listOf(xml, html, caseFile)
    }

    /** Writes [candidate] onto [screen] and marks [case] handled, in [crawl]'s folder. */
    fun write(
        crawl: CaseCrawl,
        case: IdentityCase,
        screen: CaseScreen,
        candidate: ScreenIdentity,
        fileOps: SettleFileOps = SettleFileOps.REAL,
    ): Written {
        val xmlFile = screen.capture.xmlFile
        val htmlFile = screen.capture.htmlFile
        val caseFile = IdentityCaseStore.fileIn(crawl.directory)
        val previous = screen.capture.identity
        val handled = case.copy(
            status = IdentityCaseStatus.HANDLED,
            // The first identity a case's settles replace is kept: once recorded it is never overwritten,
            // so a reopened case settled again still shows what was there before. Only a settle of an
            // open case records one — the first time its screen asserted something; a settle of a still
            // handled case replaces the operator's own unconfirmed attempt, which is not recorded.
            replaced = case.replaced ?: if (case.status == IdentityCaseStatus.OPEN && previous.traits.isNotEmpty()) {
                ReplacedIdentity(previous.traits, previous.settledBy ?: IdentityOrigin.MACHINE)
            } else {
                null
            },
        )
        val cases = crawl.cases.map { if (it.id == case.id) handled else it }

        val newXml = replaceInXml(xmlFile, xmlFile.readText(Charsets.UTF_8), candidate)
        val newHtml = replaceInHtml(htmlFile, htmlFile.readText(Charsets.UTF_8), candidate)
        val newCases = IdentityCaseStore.serialize(cases)

        // The id names a folder that is deleted recursively; one that could leave the crawl folder is refused.
        if (!IdentityCaseStore.isCaseId(case.id)) throw SettleWriteRefused("\"${case.id}\" is not a case id.")
        val staging = File(crawl.directory, ".settle-${case.id}")
        // A HALF WRITTEN settle left the originals here for the operator to put back; they are the only
        // copy, so a later settle stops rather than clear them away.
        if (staging.listFiles().orEmpty().any { it.name.endsWith(".before") }) {
            throw SettleWriteRefused(
                "${staging.absolutePath} still holds the originals of an earlier settle that was HALF WRITTEN. " +
                    "Copy each <name>.before back over <name> in the crawl folder, delete that folder, then settle again.",
            )
        }
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw SettleWriteRefused("Could not create ${staging.absolutePath}.")
        var keepStaging = false
        try {
            val stagedXml = File(staging, xmlFile.name).apply { writeText(newXml, Charsets.UTF_8) }
            val stagedHtml = File(staging, htmlFile.name).apply { writeText(newHtml, Charsets.UTF_8) }
            val stagedCases = File(staging, caseFile.name).apply { writeText(newCases, Charsets.UTF_8) }
            verify(stagedXml, stagedHtml, stagedCases, candidate, cases)
            val replacements = listOf(stagedXml to xmlFile, stagedHtml to htmlFile, stagedCases to caseFile)
            // Each backup is copied under a temporary name and renamed when complete: a settle killed
            // mid-copy must never leave a truncated file named .before for the operator to copy back.
            val backups = replacements.map { (_, target) ->
                val partial = File(staging, "${target.name}.before.partial")
                target.copyTo(partial, overwrite = true)
                File(staging, "${target.name}.before").also { backup -> check(partial.renameTo(backup)) { "Could not keep ${backup.name}." } }
            }
            val moved = mutableListOf<Int>()
            try {
                replacements.forEachIndexed { index, (from, to) ->
                    fileOps.move(from, to)
                    moved += index
                }
            } catch (e: Exception) {
                val unrestored = moved.filterNot { index ->
                    runCatching { fileOps.restore(backups[index], replacements[index].second) }.isSuccess
                }.map { index -> replacements[index].second }
                if (unrestored.isEmpty()) {
                    throw SettleWriteRefused(
                        "Could not replace ${replacements[moved.size].second.absolutePath}: ${e.message}. " +
                            "Every file was put back as it was.",
                        nothingWritten = true,
                        cause = e,
                    )
                }
                keepStaging = true
                throw SettleWriteRefused(
                    "HALF WRITTEN: could not replace ${replacements[moved.size].second.absolutePath} (${e.message}), " +
                        "and could not put back ${unrestored.joinToString(", ") { it.name }}. Their originals are in " +
                        "${staging.absolutePath} as <name>.before; copy them back before resuming.",
                    nothingWritten = false,
                    cause = e,
                )
            }
        } catch (refused: SettleWriteRefused) {
            throw refused
        } catch (e: Exception) {
            throw SettleWriteRefused("Could not stage the settle: ${e.message}", nothingWritten = true, cause = e)
        } finally {
            if (!keepStaging) staging.deleteRecursively()
        }
        return Written(xmlFile, htmlFile, caseFile, handled)
    }

    private fun verify(
        stagedXml: File,
        stagedHtml: File,
        stagedCases: File,
        candidate: ScreenIdentity,
        cases: List<IdentityCase>,
    ) {
        val reread = CaptureIdentitySource.load(stagedXml).getOrElse { error ->
            throw SettleWriteRefused("The settled files did not read back: ${error.message}", cause = error)
        }
        if (reread.identity.traits != candidate.traits || reread.identity.settledBy != candidate.settledBy ||
            reread.identity.elements != candidate.elements
        ) {
            throw SettleWriteRefused(
                "${stagedXml.name} and ${stagedHtml.name} did not read back as the identity being settled.",
            )
        }
        if (IdentityCaseStore.read(stagedCases) != cases.sortedBy(IdentityCase::id)) {
            throw SettleWriteRefused("${stagedCases.name} did not read back as written.")
        }
    }

    // Only the block's own lines are written, with the line break the block already used; every
    // other byte of the file, its other line endings included, stays exactly as it was.
    private fun replaceInXml(file: File, text: String, candidate: ScreenIdentity): String {
        val crawlStart = text.indexOf("<crawl ")
        val span = (if (crawlStart >= 0) ScreenIdentitySpan.find(text, crawlStart) else null)
            ?: throw SettleWriteRefused("${file.absolutePath} has no <screen-identity> inside <crawl> to replace.")
        val blockStart = text.indexOf("<screen-identity", span.lineStart)
        val tagEnd = text.indexOf('>', blockStart)
        val elementEnd = if (text[tagEnd - 1] == '/') tagEnd + 1 else text.indexOf("</screen-identity>", blockStart) + "</screen-identity>".length
        refuseSharedLine(file, text.substring(span.lineStart, blockStart), text.substring(elementEnd, span.end))
        val lineBreak = ScreenIdentitySpan.lineBreakAt(text, span.lineStart)
        val lines = AccessibilityXmlSerializer.screenIdentityLines(candidate, span.depth).replace("\n", lineBreak)
        return text.substring(0, span.lineStart) + lines + text.substring(span.end)
    }

    /**
     * The crawler writes an identity block on lines of its own. A file reformatted so that other text
     * shares those lines is refused: replacing whole lines would drop that text, and keeping it would
     * mean guessing at a layout the crawler never wrote.
     */
    private fun refuseSharedLine(file: File, before: String, after: String) {
        if (before.isNotBlank() || after.isNotBlank()) {
            throw SettleWriteRefused(
                "${file.absolutePath}: other text shares a line with the identity block " +
                    "(\"${(before + after).trim().take(40)}\"). Put the block back on lines of its own, then settle again.",
            )
        }
    }

    private fun replaceInHtml(file: File, text: String, candidate: ScreenIdentity): String {
        val block = CaptureIdentitySource.locatePageBlock(text)
            ?: throw SettleWriteRefused("${file.absolutePath} has no identity block to replace.")
        val start = block.openStart
        val close = block.closeStart
        val lineStart = text.lastIndexOf('\n', start) + 1
        refuseSharedLine(file, text.substring(lineStart, start), after = "")
        val lineBreak = ScreenIdentitySpan.lineBreakAt(text, start)
        // The page's own render dedents the whole template, block included, by however much its
        // shallowest line allows, so the block's depth on a page is the page's, not the producer's.
        // Shifting the produced block to the depth the page already has keeps a settled page
        // byte-identical to one rendered afresh.
        val pageIndent = start - (text.lastIndexOf('\n', start) + 1)
        val produced = HtmlRenderer.renderIdentityBlock(candidate).removePrefix("\n")
        val producedIndent = produced.takeWhile { it == ' ' }.length
        val shifted = produced.lines().joinToString(lineBreak) { line -> shift(line, pageIndent - producedIndent) }
        return text.substring(0, lineStart) + shifted + text.substring(close + "</script>".length)
    }

    private fun shift(line: String, by: Int): String = when {
        by > 0 -> " ".repeat(by) + line
        by < 0 -> line.drop(minOf(-by, line.takeWhile { it == ' ' }.length))
        else -> line
    }

}

/** Whether a settle may push its files to the device, given what the device holds now. */
internal object PushDecision {
    sealed interface Result {
        object Allowed : Result

        /** These files differ on the device from the copy the operator pulled. */
        data class Refused(val differing: List<String>) : Result
    }

    /**
     * [local] and [device] map each file name to its SHA-256, null when that side has no such file.
     * The push is allowed only when every name agrees, missing on both sides included: anything else
     * means the device crawl moved on since the pull, and pushing would clobber it.
     */
    fun decide(local: Map<String, String?>, device: Map<String, String?>): Result {
        val differing = (local.keys + device.keys).sorted().filter { name -> local[name] != device[name] }
        return if (differing.isEmpty()) Result.Allowed else Result.Refused(differing)
    }
}

/** The device-side crawl folder a settle pushes to. A seam: the real one runs `adb`. */
internal interface DeviceCrawl {
    /** Why the device cannot be used, or null when it can. */
    val unavailableReason: String?

    /**
     * The SHA-256 of [name] in the device crawl folder, lower-case hex, or null when it has no such
     * file. Throws when the device cannot be read, so a failure is never mistaken for a missing file.
     */
    fun sha256(name: String): String?

    /** Copies [file] into the device crawl folder as [name]. Throws when the copy fails. */
    fun push(file: File, name: String)
}

/**
 * Settles one case, end to end: check, then (when asked) compare with the device, write, push and
 * confirm. The page's *Settle* and the command line both call this, so they cannot behave differently.
 *
 * Nothing is written anywhere unless the identity holds and, when pushing, the device still holds
 * exactly the files the operator pulled.
 */
internal object CaseSettleService {

    /**
     * What one settle did. [written] and [pushed] are empty when it stopped early; [problem] says why.
     * [toPush] are the files written but not pushed, because no device was given: the device crawl is
     * behind until they are pushed.
     */
    data class Outcome(
        val verdict: OperatorSettlement.Verdict,
        val written: List<File> = emptyList(),
        val pushed: List<String> = emptyList(),
        val problem: String? = null,
        val toPush: List<File> = emptyList(),
    ) {
        /** Whether everything asked for happened. */
        val succeeded: Boolean get() = problem == null && verdict is OperatorSettlement.Verdict.Holds
    }

    /**
     * Checks [traitsText] for [caseId] in [directory]; when it holds and [write] is set, writes the
     * settle, and when [device] is given, pushes it. [device] null means do not push.
     */
    fun settle(
        directory: File,
        caseId: String,
        traitsText: String,
        write: Boolean,
        device: DeviceCrawl?,
        fileOps: SettleFileOps = SettleFileOps.REAL,
    ): Outcome {
        if (!write) return settleLocked(directory, caseId, traitsText, write, device, fileOps)
        // One settle at a time per folder, whichever process runs it: the case file is rewritten whole,
        // so two settles reading it at once would each drop the other's change.
        val lockFile = lockFileFor(directory)
        val access = try {
            java.io.RandomAccessFile(lockFile, "rw")
        } catch (e: java.io.IOException) {
            return Outcome(OperatorSettlement.Verdict.NotSettleable("the settle lock ${lockFile.absolutePath} cannot be opened: ${e.message}"))
        }
        return access.use { access ->
            val lock = try {
                access.channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            } ?: return Outcome(
                OperatorSettlement.Verdict.NotSettleable(
                    "another settle is running on this crawl folder (${lockFile.absolutePath} is held); try again when it ends",
                ),
            )
            lock.use { settleLocked(directory, caseId, traitsText, write, device, fileOps) }
        }
    }

    /**
     * The file a settle locks for [directory]. It lives in the system's temporary folder, named after
     * the crawl folder's canonical path, so it is never pulled, pushed or read as part of the crawl, and
     * it is never deleted — deleting a lock file lets two processes each lock a different one.
     */
    fun lockFileFor(directory: File): File {
        val path = directory.canonicalPath.lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val name = "a2h-settle-" + digest.take(12).joinToString("") { "%02x".format(it) } + ".lock"
        return File(System.getProperty("java.io.tmpdir"), name)
    }

    private fun settleLocked(
        directory: File,
        caseId: String,
        traitsText: String,
        write: Boolean,
        device: DeviceCrawl?,
        fileOps: SettleFileOps,
    ): Outcome {
        // Checked before the folder is read: the leftovers make the screen's pair disagree, and the
        // operator should be pointed at the kept originals, not at that disagreement.
        CaseCrawl.stoppedSettle(directory)?.let { return Outcome(it) }
        val crawl = try {
            CaseCrawl.load(directory)
        } catch (refused: MalformedIdentityCases) {
            return Outcome(OperatorSettlement.Verdict.NotSettleable("the case file is refused: ${refused.message}"))
        }
        val verdict = OperatorSettlement.check(crawl, caseId, traitsText)
        if (verdict !is OperatorSettlement.Verdict.Holds || !write) return Outcome(verdict)
        val case = requireNotNull(crawl.case(caseId))
        val screen = requireNotNull(crawl.screen(verdict.screenId))
        val names = listOf(screen.capture.xmlFile, screen.capture.htmlFile, IdentityCaseStore.fileIn(directory))

        if (device != null) {
            device.unavailableReason?.let { reason ->
                return Outcome(verdict, problem = "Nothing written: the device cannot be used for the push: $reason")
            }
            val local = names.associate { it.name to sha256(it) }
            val onDevice = try {
                names.associate { it.name to device.sha256(it.name) }
            } catch (e: Exception) {
                return Outcome(verdict, problem = "Nothing written: the device could not be read: ${e.message}")
            }
            val decision = PushDecision.decide(local, onDevice)
            if (decision is PushDecision.Result.Refused) {
                return Outcome(
                    verdict,
                    problem = "Nothing written: on the device, ${decision.differing.joinToString(", ")} " +
                        "differ from the local copies this settle would replace. Either the device crawl " +
                        "changed since the pull (pull it again and settle on the fresh copy), or an earlier " +
                        "settle here was written but not pushed (push those files first, as that settle listed).",
                )
            }
        }

        val written = try {
            SettleWriter.write(crawl, case, screen, verdict.candidate, fileOps)
        } catch (refused: SettleWriteRefused) {
            return Outcome(verdict, problem = if (refused.nothingWritten) "Nothing written: ${refused.message}" else refused.message)
        } catch (e: Exception) {
            // Reading a screen file failed before anything was staged: nothing was written.
            return Outcome(verdict, problem = "Nothing written: ${e.message ?: e.toString()}")
        }
        if (device == null) return Outcome(verdict, written = written.files, toPush = written.files)

        val pushed = mutableListOf<String>()
        return try {
            written.files.forEach { file ->
                device.push(file, file.name)
                pushed += file.name
            }
            val mismatched = written.files.filter { runCatching { device.sha256(it.name) }.getOrNull() != sha256(it) }.map(File::getName)
            Outcome(
                verdict,
                written = written.files,
                pushed = pushed,
                problem = mismatched.takeIf { it.isNotEmpty() }?.let {
                    "Pushed, but the device copies of ${it.joinToString(", ")} do not match what was written; push them again."
                },
            )
        } catch (e: Exception) {
            Outcome(
                verdict,
                written = written.files,
                pushed = pushed,
                problem = "Written locally, but the push failed after ${pushed.size} of ${written.files.size} " +
                    "files: ${e.message}. The files still need pushing.",
            )
        }
    }

    /** The SHA-256 of [file], lower-case hex, or null when it does not exist. */
    fun sha256(file: File): String? {
        if (!file.isFile) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
