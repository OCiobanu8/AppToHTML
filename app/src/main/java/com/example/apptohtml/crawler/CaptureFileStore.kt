package com.example.apptohtml.crawler

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CapturedScreenFiles(
    val htmlFile: File,
    val xmlFile: File,
    val mergedXmlFile: File? = null,
)

object CaptureFileStore {
    fun createSession(
        context: Context,
        packageName: String,
        startedAt: Long = System.currentTimeMillis(),
    ): CrawlSessionDirectory {
        val baseDir = preferredBaseDir(context, packageName)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        var sessionId = "crawl_$timestamp"
        var sessionDir = File(baseDir, sessionId)
        var suffix = 1
        while (sessionDir.exists()) {
            sessionId = "crawl_${timestamp}_$suffix"
            sessionDir = File(baseDir, sessionId)
            suffix += 1
        }
        sessionDir.mkdirs()

        return CrawlSessionDirectory(
            sessionId = sessionId,
            directory = sessionDir,
            manifestFile = File(sessionDir, "crawl-index.json"),
            logFile = File(sessionDir, "crawl.log"),
        )
    }

    fun saveScreen(
        session: CrawlSessionDirectory,
        snapshot: ScreenSnapshot,
        sequenceNumber: Int,
        screenPrefix: String,
        resolvedChildLinks: Map<PressableElementLinkKey, String> = emptyMap(),
    ): CapturedScreenFiles {
        val baseName = "%03d_%s_%s".format(
            Locale.US,
            sequenceNumber,
            screenPrefix,
            ScreenNaming.toFileBase(snapshot.screenName),
        )
        val htmlFile = File(session.directory, "$baseName.html")
        val xmlFile = File(session.directory, "$baseName.xml")
        val mergedXmlFile = snapshot.mergedXmlDump?.let {
            File(session.directory, "${baseName}_merged_accessibility.xml")
        }

        htmlFile.writeText(HtmlRenderer.render(snapshot, resolvedChildLinks), Charsets.UTF_8)
        xmlFile.writeText(snapshot.xmlDump, Charsets.UTF_8)
        mergedXmlFile?.writeText(snapshot.mergedXmlDump.orEmpty(), Charsets.UTF_8)

        return CapturedScreenFiles(
            htmlFile = htmlFile,
            xmlFile = xmlFile,
            mergedXmlFile = mergedXmlFile,
        )
    }

    fun rewriteScreenHtml(
        files: CapturedScreenFiles,
        snapshot: ScreenSnapshot,
        resolvedChildLinks: Map<PressableElementLinkKey, String>,
    ) {
        files.htmlFile.writeText(HtmlRenderer.render(snapshot, resolvedChildLinks), Charsets.UTF_8)
    }

    fun saveManifest(
        session: CrawlSessionDirectory,
        manifest: CrawlManifest,
    ): File {
        CrawlManifestStore.write(manifest, session.manifestFile)
        return session.manifestFile
    }

    fun save(
        context: Context,
        snapshot: ScreenSnapshot,
        resolvedChildLinks: Map<PressableElementLinkKey, String> = emptyMap(),
    ): CapturedScreenFiles {
        val baseDir = preferredBaseDir(context, snapshot.packageName)
        preparePackageDirectory(baseDir)
        val baseName = ScreenNaming.toFileBase(snapshot.screenName)
        val htmlFile = File(baseDir, "$baseName.html")
        val xmlFile = File(baseDir, "$baseName.xml")
        val mergedXmlFile = snapshot.mergedXmlDump?.let {
            File(baseDir, "${baseName}_merged_accessibility.xml")
        }

        htmlFile.writeText(HtmlRenderer.render(snapshot, resolvedChildLinks), Charsets.UTF_8)
        xmlFile.writeText(snapshot.xmlDump, Charsets.UTF_8)
        mergedXmlFile?.writeText(snapshot.mergedXmlDump.orEmpty(), Charsets.UTF_8)

        return CapturedScreenFiles(
            htmlFile = htmlFile,
            xmlFile = xmlFile,
            mergedXmlFile = mergedXmlFile,
        )
    }

    internal fun preparePackageDirectory(baseDir: File) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
            return
        }

        baseDir.listFiles().orEmpty().forEach { file ->
            if (!file.delete()) {
                throw IllegalStateException("Could not delete previous capture file: ${file.absolutePath}")
            }
        }
    }

    private fun preferredBaseDir(context: Context, packageName: String): File {
        val externalRoot = context.getExternalFilesDir(null)
        val root = externalRoot ?: context.filesDir
        return File(root, "html/$packageName")
    }
}
