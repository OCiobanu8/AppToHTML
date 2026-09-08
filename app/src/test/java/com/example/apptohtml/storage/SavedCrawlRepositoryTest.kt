package com.example.apptohtml.storage

import com.example.apptohtml.crawler.AccessibilityXmlSerializer
import com.example.apptohtml.crawler.CrawlEdgeStatus
import com.example.apptohtml.crawler.CrawlRoute
import com.example.apptohtml.crawler.CrawlRunStatus
import com.example.apptohtml.crawler.EdgeXmlView
import com.example.apptohtml.crawler.ParentEdgeRef
import com.example.apptohtml.crawler.PressableElement
import com.example.apptohtml.crawler.RunLevelState
import com.example.apptohtml.crawler.ScreenCrawlState
import com.example.apptohtml.crawler.ScreenExpansionStatus
import com.example.apptohtml.crawler.ScreenIdentityFields
import com.example.apptohtml.crawler.ScreenNaming
import com.example.apptohtml.crawler.ScreenSnapshot
import com.example.apptohtml.crawler.toLinkKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedCrawlRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val targetPackage = "com.example.target"
    private val externalPackage = "com.example.external"

    @Test
    fun snapshot_returns_null_when_no_saved_crawl_exists() {
        val repository = SavedCrawlRepository { packageName ->
            File(tempFolder.root, packageName)
        }

        assertNull(repository.snapshot(targetPackage))
    }

    @Test
    fun snapshot_derives_screen_summaries_and_pending_work() {
        val crawlDir = tempFolder.newFolder("crawl")
        val open = element(label = "Open")
        writeScreen(
            dir = crawlDir,
            screenId = "screen_00000",
            screenName = "Home",
            packageName = targetPackage,
            depth = 0,
            expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
            isRoot = true,
            elements = listOf(open),
            edgesByElement = mapOf(
                open.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_001",
                    status = CrawlEdgeStatus.PENDING,
                )
            ),
        )
        val share = element(label = "Share")
        writeScreen(
            dir = crawlDir,
            screenId = "screen_00001",
            screenName = "Detail",
            packageName = targetPackage,
            depth = 1,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = false,
            elements = listOf(share),
            edgesByElement = mapOf(
                share.toLinkKey() to EdgeXmlView(
                    edgeId = "edge_002",
                    status = CrawlEdgeStatus.CAPTURED,
                    childScreenId = "screen_00002",
                    childScreenName = "Share Sheet",
                )
            ),
        )
        writeScreen(
            dir = crawlDir,
            screenId = "screen_00002",
            screenName = "Share Sheet",
            packageName = externalPackage,
            depth = 2,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = false,
        )
        val repository = SavedCrawlRepository { crawlDir }

        val snapshot = repository.snapshot(targetPackage)

        assertNotNull(snapshot)
        assertTrue(snapshot!!.hasPendingWork)
        assertEquals(3, snapshot.screens.size)
        assertEquals(1, snapshot.screens.first { it.screenId == "screen_00000" }.pendingEdgeCount)
    }

    private fun writeScreen(
        dir: File,
        screenId: String,
        screenName: String,
        packageName: String,
        depth: Int,
        expansionStatus: ScreenExpansionStatus,
        isRoot: Boolean,
        elements: List<PressableElement> = emptyList(),
        edgesByElement: Map<com.example.apptohtml.crawler.PressableElementLinkKey, EdgeXmlView> = emptyMap(),
    ) {
        val snapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = packageName,
            elements = elements,
            xmlDump = "",
            scrollStepCount = 1,
        )
        val crawlState = ScreenCrawlState(
            screenId = screenId,
            depth = depth,
            expansionStatus = expansionStatus,
            isRoot = isRoot,
            screenIdentity = ScreenIdentityFields(
                packageName = ScreenNaming.normalizeIdentityToken(packageName),
                title = ScreenNaming.normalizeIdentityToken(screenName),
            ),
            parent = if (isRoot) {
                null
            } else {
                ParentEdgeRef(
                    screenId = "screen_00000",
                    triggerLabel = "Open",
                    triggerResourceId = null,
                )
            },
            route = CrawlRoute(),
            runLevel = if (isRoot) {
                RunLevelState(
                    sessionId = "session-1",
                    startedAt = 1_000L,
                    status = CrawlRunStatus.IN_PROGRESS,
                    maxDepthReached = 2,
                )
            } else {
                null
            },
            edgesByElement = edgesByElement,
        )
        val baseName = "${screenId}_${ScreenNaming.toFileBase(screenName)}"
        File(dir, "$baseName.html").writeText("<html></html>", Charsets.UTF_8)
        File(dir, "$baseName.xml").writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState),
            Charsets.UTF_8,
        )
    }

    private fun element(label: String): PressableElement {
        return PressableElement(
            label = label,
            resourceId = "$targetPackage:id/${label.lowercase()}",
            bounds = "[0,0][100,100]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(0),
            firstSeenStep = 0,
        )
    }
}
