package com.example.apptohtml.storage

import android.content.Context
import com.example.apptohtml.crawler.CaptureFileStore
import com.example.apptohtml.crawler.CrawlEdgeStatus
import com.example.apptohtml.crawler.CrawlScreenRecord
import com.example.apptohtml.crawler.PressableElementLinkKey
import com.example.apptohtml.crawler.SavedCrawlLoader
import com.example.apptohtml.crawler.ScreenExpansionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

data class SavedCrawlSnapshot(
    val screens: List<SavedScreenSummary>,
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

        return SavedCrawlSnapshot(
            screens = screens,
        )
    }

    fun snapshotFlow(packageName: String): Flow<SavedCrawlSnapshot?> {
        return refreshCounter.map { snapshot(packageName) }
    }

    fun refresh() {
        refreshCounter.value = refreshCounter.value + 1
    }


    private fun crawlDir(packageName: String): File {
        return crawlDirectoryProvider(packageName)
    }

}
