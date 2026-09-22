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
    private const val CRAWL_DIR_NAME = "crawl"

    fun createSession(
        context: Context,
        packageName: String,
        startedAt: Long = System.currentTimeMillis(),
        wipeExisting: Boolean = false,
    ): CrawlSessionDirectory {
        val baseDir = preferredBaseDir(context, packageName)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val crawlDir = File(baseDir, CRAWL_DIR_NAME)
        if (wipeExisting && crawlDir.exists()) {
            crawlDir.deleteRecursively()
        }
        if (!crawlDir.exists()) {
            crawlDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        val sessionId = "crawl_$timestamp"
        val logFile = uniqueLogFile(crawlDir, timestamp)

        return CrawlSessionDirectory(
            sessionId = sessionId,
            directory = crawlDir,
            manifestFile = File(crawlDir, "crawl-index.json"),
            logFile = logFile,
            graphJsonFile = File(crawlDir, "crawl-graph.json"),
            graphHtmlFile = File(crawlDir, "crawl-graph.html"),
        )
    }

    fun crawlDirectory(context: Context, packageName: String): File {
        return File(preferredBaseDir(context, packageName), CRAWL_DIR_NAME)
    }

    fun wipePackageCrawl(context: Context, packageName: String) {
        val crawlDir = crawlDirectory(context, packageName)
        if (crawlDir.exists()) {
            crawlDir.deleteRecursively()
        }
    }

    /**
     * Writes a screen's files for the first time.
     *
     * [identity] is taken separately from [crawlState] because a screen is saved *before* it enters
     * the tracker, so its crawl state does not exist yet while its identity already does. Passing
     * only the crawl state left every page written without its identity block until some later
     * rewrite happened to supply one — which only ever happened for screens whose edges resolved.
     */
    fun saveScreen(
        session: CrawlSessionDirectory,
        snapshot: ScreenSnapshot,
        screenId: String,
        resolvedChildLinks: Map<PressableElementLinkKey, String> = emptyMap(),
        crawlState: ScreenCrawlState? = null,
        identity: ScreenIdentity? = crawlState?.screenIdentity,
    ): CapturedScreenFiles {
        val baseName = "${screenId}_${ScreenNaming.toFileBase(snapshot.screenName)}"
        val htmlFile = File(session.directory, "$baseName.html")
        val xmlFile = File(session.directory, "$baseName.xml")
        val mergedXmlFile = snapshot.mergedXmlDump?.let {
            File(session.directory, "${baseName}_merged_accessibility.xml")
        }

        htmlFile.writeText(
            HtmlRenderer.render(snapshot, resolvedChildLinks, identity),
            Charsets.UTF_8,
        )
        val xmlContent = if (crawlState != null) {
            AccessibilityXmlSerializer.serialize(snapshot, crawlState)
        } else {
            snapshot.xmlDump
        }
        xmlFile.writeText(xmlContent, Charsets.UTF_8)
        mergedXmlFile?.writeText(snapshot.mergedXmlDump.orEmpty(), Charsets.UTF_8)

        return CapturedScreenFiles(
            htmlFile = htmlFile,
            xmlFile = xmlFile,
            mergedXmlFile = mergedXmlFile,
        )
    }

    /**
     * Rewrites the page as a screen's edges resolve.
     *
     * [identity] is not optional in practice: this runs many times over a crawl, and omitting it
     * would republish the page without its identity block, erasing anything an operator had settled
     * by hand. It is the reason this function takes a parameter it never used to need.
     */
    fun rewriteScreenHtml(
        files: CapturedScreenFiles,
        snapshot: ScreenSnapshot,
        resolvedChildLinks: Map<PressableElementLinkKey, String>,
        identity: ScreenIdentity?,
    ) {
        files.htmlFile.writeText(
            HtmlRenderer.render(snapshot, resolvedChildLinks, identity),
            Charsets.UTF_8,
        )
    }

    fun rewriteScreenXml(
        files: CapturedScreenFiles,
        snapshot: ScreenSnapshot,
        crawlState: ScreenCrawlState,
    ) {
        files.xmlFile.writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState),
            Charsets.UTF_8,
        )
    }

    private fun uniqueLogFile(crawlDir: File, timestamp: String): File {
        var candidate = File(crawlDir, "crawl_$timestamp.log")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(crawlDir, "crawl_${timestamp}_$suffix.log")
            suffix += 1
        }
        return candidate
    }

    fun saveManifest(
        session: CrawlSessionDirectory,
        manifest: CrawlManifest,
    ): File {
        CrawlManifestStore.write(manifest, session.manifestFile)
        return session.manifestFile
    }

    fun saveGraph(
        session: CrawlSessionDirectory,
        graph: CrawlGraph,
    ) {
        CrawlGraphJsonWriter.write(graph, session.graphJsonFile)
        session.graphHtmlFile.writeText(CrawlGraphHtmlRenderer.render(graph), Charsets.UTF_8)
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
