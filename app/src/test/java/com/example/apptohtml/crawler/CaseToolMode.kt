package com.example.apptohtml.crawler

import java.io.File

/**
 * Which job the validation tool's entry point was asked to do, read from its `a2h.*` properties.
 *
 * The script's argument contract in one place: `-Target` validates one screen, `-Crawl -Survey`
 * prints the text survey, `-Crawl -Settle -Traits` settles from the command line, and `-Crawl` alone
 * serves the case overview. Pure, so the contract is pinned without starting anything.
 */
internal sealed interface CaseToolMode {
    /** No `a2h.*` job was asked for: a plain test run, which must do nothing. */
    object None : CaseToolMode

    data class ValidateOne(val target: File, val observed: File, val known: File?, val isRoot: Boolean) : CaseToolMode

    data class Survey(val crawl: File) : CaseToolMode

    /** Serve the overview and case pages on 127.0.0.1:[port], every request carrying [token]. */
    data class Serve(val crawl: File, val port: Int, val token: String, val device: DeviceSettings?) : CaseToolMode

    data class Settle(
        val crawl: File,
        val caseId: String,
        val traits: File,
        val dryRun: Boolean,
        val device: DeviceSettings?,
    ) : CaseToolMode

    /** The properties name a job but cannot run it; [reason] says what is missing. */
    data class Invalid(val reason: String) : CaseToolMode

    /** How to reach the device: the `adb` binary, an optional serial, and an optional remote crawl folder. */
    data class DeviceSettings(val adb: File, val serial: String?, val remoteDir: String?)

    companion object {
        /** The one job [property]'s `a2h.*` values ask for, or [Invalid] naming what is missing. */
        fun select(property: (String) -> String?): CaseToolMode {
            val target = property("a2h.target")
            val crawl = property("a2h.crawl")
            return when {
                target != null -> ValidateOne(
                    target = File(target),
                    observed = File(property("a2h.observed") ?: target),
                    known = property("a2h.known")?.let(::File),
                    isRoot = property("a2h.root")?.toBoolean() ?: false,
                )
                crawl == null -> None
                property("a2h.settle") != null -> {
                    val traits = property("a2h.traits")
                        ?: return Invalid("settling ${property("a2h.settle")} needs a traits file (a2h.traits)")
                    val push = property("a2h.push")?.toBoolean() ?: false
                    val device = deviceSettings(property)
                    if (push && device == null) return Invalid("pushing needs adb (a2h.adb)")
                    Settle(
                        crawl = File(crawl),
                        caseId = property("a2h.settle")!!,
                        traits = File(traits),
                        dryRun = property("a2h.dryRun")?.toBoolean() ?: false,
                        device = device.takeIf { push },
                    )
                }
                property("a2h.survey")?.toBoolean() == true -> Survey(File(crawl))
                else -> {
                    val port = property("a2h.port")?.toIntOrNull()
                        ?: return Invalid("serving the case overview needs a port (a2h.port)")
                    val token = property("a2h.token") ?: property("a2h.tokenFile")?.let(::takeToken)
                        ?: return Invalid("serving the case overview needs a token (a2h.tokenFile)")
                    Serve(File(crawl), port, token, deviceSettings(property))
                }
            }
        }

        /**
         * The token in the one-time file at [path], which is deleted once read. The script passes the
         * token this way so it never becomes a Gradle input, which Gradle records on disk.
         */
        private fun takeToken(path: String): String? {
            val file = File(path)
            if (!file.isFile) return null
            return file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }.also { file.delete() }
        }

        private fun deviceSettings(property: (String) -> String?): DeviceSettings? =
            property("a2h.adb")?.let { adb ->
                DeviceSettings(File(adb), property("a2h.serial"), property("a2h.remoteRoot"))
            }
    }
}
