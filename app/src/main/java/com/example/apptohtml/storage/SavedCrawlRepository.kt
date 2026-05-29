package com.example.apptohtml.storage

import android.content.Context
import com.example.apptohtml.crawler.CaptureFileStore
import com.example.apptohtml.crawler.CrawlEdgeApproval
import com.example.apptohtml.crawler.CrawlEdgeStatus
import com.example.apptohtml.crawler.CrawlScreenRecord
import com.example.apptohtml.crawler.PressableElementLinkKey
import com.example.apptohtml.crawler.SavedCrawlLoader
import com.example.apptohtml.crawler.ScreenExpansionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.regex.Pattern

data class SavedCrawlSnapshot(
    val screens: List<SavedScreenSummary>,
    val allowedPackages: List<AllowedPackage>,
) {
    val hasPendingWork: Boolean = screens.any {
        it.expansionStatus == ScreenExpansionStatus.NOT_STARTED ||
            it.expansionStatus == ScreenExpansionStatus.IN_PROGRESS
    }
}

data class SavedScreenSummary(
    val screenId: String,
    val screenName: String,
    val packageName: String,
    val depth: Int,
    val expansionStatus: ScreenExpansionStatus,
    val pendingEdgeCount: Int,
)

data class AllowedPackage(
    val packageName: String,
    val edgeId: String,
    val parentScreenId: String,
    val parentScreenName: String,
    val triggerLabel: String?,
)

class SavedCrawlRepository(
    private val crawlDirectoryProvider: (String) -> File,
) {
    private val refreshCounter = MutableStateFlow(0)

    constructor(context: Context) : this({ packageName ->
        CaptureFileStore.crawlDirectory(context.applicationContext, packageName)
    })

    fun snapshot(packageName: String): SavedCrawlSnapshot? {
        val crawlDir = crawlDir(packageName)
        val loaded = SavedCrawlLoader.load(crawlDir) ?: return null
        if (loaded.screens.isEmpty()) return null

        val pendingCounts = loaded.edges
            .filter { it.status == CrawlEdgeStatus.PENDING || it.status == CrawlEdgeStatus.IN_PROGRESS }
            .groupingBy { it.parentScreenId }
            .eachCount()
        val screensById = loaded.screens.associateBy { it.screenId }
        val screens = loaded.screens
            .sortedWith(compareBy<CrawlScreenRecord> { it.depth }.thenBy { it.screenId })
            .map { screen ->
                SavedScreenSummary(
                    screenId = screen.screenId,
                    screenName = screen.screenName,
                    packageName = screen.packageName,
                    depth = screen.depth,
                    expansionStatus = screen.expansionStatus,
                    pendingEdgeCount = pendingCounts[screen.screenId] ?: 0,
                )
            }
        val allowedPackages = loaded.edges
            .filter { it.approval == CrawlEdgeApproval.EXPLICIT }
            .mapNotNull { edge ->
                val childPackage = edge.childScreenId
                    ?.let { childId -> screensById[childId]?.packageName }
                    ?: return@mapNotNull null
                val parent = screensById[edge.parentScreenId] ?: return@mapNotNull null
                AllowedPackage(
                    packageName = childPackage,
                    edgeId = edge.edgeId,
                    parentScreenId = parent.screenId,
                    parentScreenName = parent.screenName,
                    triggerLabel = edge.label.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.packageName to it.edgeId }
            .sortedWith(compareBy<AllowedPackage> { it.packageName }.thenBy { it.edgeId })

        return SavedCrawlSnapshot(
            screens = screens,
            allowedPackages = allowedPackages,
        )
    }

    fun snapshotFlow(packageName: String): Flow<SavedCrawlSnapshot?> {
        return refreshCounter.map { snapshot(packageName) }
    }

    fun refresh() {
        refreshCounter.value = refreshCounter.value + 1
    }

    fun revokeApproval(packageName: String, edgeId: String): Boolean {
        val crawlDir = crawlDir(packageName)
        if (!crawlDir.exists() || !crawlDir.isDirectory) return false
        val edgePattern = edgeTagPattern(edgeId)
        crawlDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.endsWith("_merged_accessibility.xml") }
            .sortedBy { it.name }
            .forEach { file ->
                val raw = file.readText(Charsets.UTF_8)
                val matcher = edgePattern.matcher(raw)
                if (!matcher.find()) return@forEach
                val edgeTag = matcher.group()
                if (!edgeTag.contains("""approval="explicit"""")) return@forEach
                val updatedTag = edgeTag.replace("""approval="explicit"""", """approval="revoked"""")
                file.writeText(raw.replaceRange(matcher.start(), matcher.end(), updatedTag), Charsets.UTF_8)
                refresh()
                return true
            }
        return false
    }

    private fun crawlDir(packageName: String): File {
        return crawlDirectoryProvider(packageName)
    }

    private fun edgeTagPattern(edgeId: String): Pattern {
        val escapedEdgeId = Pattern.quote(edgeId)
        return Pattern.compile("""<edge\b(?=[^>]*\bid="$escapedEdgeId")[^>]*/>""")
    }
}
