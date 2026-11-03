package com.example.apptohtml.crawler

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The device-side crawl folder, reached through `adb`. Host-only: it runs the operator's `adb`.
 *
 * Commands run as argument lists, never through a host shell. `adb shell` joins its arguments into
 * one command line for the device's shell, so a remote path given to it is single-quoted here; `adb
 * push` takes the path as its own argument. [remoteDir] defaults to the folder the app writes the
 * crawl's package to (`CaptureFileStore`'s layout under the app's external files). Every call gives up
 * after [timeoutSeconds], so a hung `adb` cannot hang the tool.
 */
internal class AdbDeviceCrawl(
    private val adb: File,
    private val serial: String?,
    private val remoteDir: String,
    private val timeoutSeconds: Long = ADB_TIMEOUT_SECONDS,
) : DeviceCrawl {

    override val unavailableReason: String?
        get() {
            if (!adb.isFile) return "adb was not found at ${adb.absolutePath}"
            val state = run("get-state")
            return if (state.exitCode == 0 && state.output.trim() == "device") {
                null
            } else {
                "no ready device (${state.output.trim().ifBlank { "adb exit ${state.exitCode}" }})"
            }
        }

    override fun sha256(name: String): String? {
        val result = run("shell", "sha256sum", shellQuoted("$remoteDir/$name"))
        if (result.exitCode != 0) {
            if ("No such file" in result.output) return null
            error("adb could not read $name: ${result.output.trim().ifBlank { "exit ${result.exitCode}" }}")
        }
        return result.output.trim().substringBefore(' ').takeIf { it.length == 64 }?.lowercase()
            ?: error("adb returned no hash for $name: ${result.output.trim()}")
    }

    override fun push(file: File, name: String) {
        val result = run("push", file.absolutePath, "$remoteDir/$name")
        check(result.exitCode == 0) { "adb push ${file.name} failed (exit ${result.exitCode}): ${result.output.trim()}" }
    }

    private data class Result(val exitCode: Int, val output: String)

    private fun run(vararg arguments: String): Result {
        val command = buildList {
            add(adb.absolutePath)
            serial?.let { add("-s"); add(it) }
            addAll(arguments)
        }
        // Output goes to a file, not a pipe read before waiting: reading a pipe blocks until adb exits,
        // which would make the timeout below unreachable.
        val output = File.createTempFile("a2h-adb", ".out")
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, "adb ${arguments.joinToString(" ")} timed out after ${timeoutSeconds}s")
            }
            return Result(process.exitValue(), output.readText())
        } finally {
            output.delete()
        }
    }

    /** [path] as one word for the device's shell. */
    private fun shellQuoted(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    companion object {
        private const val ADB_TIMEOUT_SECONDS = 60L
        private const val APP_PACKAGE = "com.example.apptohtml"

        /** The device crawl folder the app writes [targetPackage]'s crawl to. */
        fun defaultRemoteDir(targetPackage: String): String =
            "/sdcard/Android/data/$APP_PACKAGE/files/html/$targetPackage/crawl"

        /** The device crawl folder [crawl]'s files belong in, honouring a configured override; null when unknown. */
        fun remoteDirFor(settings: CaseToolMode.DeviceSettings?, crawl: CaseCrawl): String? =
            settings?.remoteDir ?: crawl.screens.firstNotNullOfOrNull { it.capture.identity.packageName }?.let(::defaultRemoteDir)

        /** The device for [settings], pointed at [crawl]'s folder on the device. */
        fun forCrawl(settings: CaseToolMode.DeviceSettings, crawl: CaseCrawl): AdbDeviceCrawl {
            val targetPackage = crawl.screens.firstNotNullOfOrNull { it.capture.identity.packageName }
                ?: error("Cannot tell which app ${crawl.directory} belongs to: no screen names a package.")
            return AdbDeviceCrawl(settings.adb, settings.serial, settings.remoteDir ?: defaultRemoteDir(targetPackage))
        }
    }
}
