package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrawlGraphBuilderTest {
    @Test
    fun build_preserves_node_order_by_screen_record_index() {
        val manifest = manifest(
            screens = listOf(
                screenRecord(
                    screenId = "screen_010",
                    screenName = "Second discovered",
                    depth = 1,
                ),
                screenRecord(
                    screenId = "screen_002",
                    screenName = "First discovered",
                    depth = 0,
                ),
            ),
        )

        val graph = CrawlGraphBuilder.build(manifest)

        assertEquals(listOf("screen_010", "screen_002"), graph.nodes.map { it.screenId })
        assertEquals(listOf(0, 1), graph.nodes.map { it.discoveryIndex })
    }

    @Test
    fun build_maps_edges_one_to_one_from_manifest() {
        val manifest = manifest(
            edges = listOf(
                CrawlEdgeRecord(
                    edgeId = "edge_000",
                    parentScreenId = "screen_000",
                    childScreenId = "screen_001",
                    label = "Open details",
                    resourceId = "com.example:id/open_details",
                    className = "android.widget.Button",
                    bounds = "[0,0][100,50]",
                    childIndexPath = listOf(0),
                    firstSeenStep = 0,
                    status = CrawlEdgeStatus.CAPTURED,
                    message = null,
                ),
                CrawlEdgeRecord(
                    edgeId = "edge_001",
                    parentScreenId = "screen_001",
                    childScreenId = null,
                    label = "Skip external",
                    resourceId = "com.example:id/open_external",
                    className = "android.widget.Button",
                    bounds = "[0,60][100,110]",
                    childIndexPath = listOf(1),
                    firstSeenStep = 0,
                    status = CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE,
                    message = "User chose to stay inside the app.",
                ),
            ),
        )

        val graph = CrawlGraphBuilder.build(manifest)

        assertEquals(2, graph.edges.size)
        assertEquals(
            listOf("edge_000", "edge_001"),
            graph.edges.map { it.edgeId },
        )
        assertEquals(
            listOf("screen_000", "screen_001"),
            graph.edges.map { it.fromScreenId },
        )
        assertEquals(
            listOf("screen_001", null),
            graph.edges.map { it.toScreenId },
        )
        assertEquals(
            listOf(CrawlEdgeStatus.CAPTURED, CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE),
            graph.edges.map { it.status },
        )
        assertEquals(
            listOf(null, "User chose to stay inside the app."),
            graph.edges.map { it.message },
        )
    }

    @Test
    fun build_uses_file_basenames_not_absolute_paths() {
        val manifest = manifest(
            screens = listOf(
                screenRecord(
                    screenId = "screen_000",
                    screenName = "Home",
                    htmlPath = "C:\\captures\\crawl_123\\000_home.html",
                    xmlPath = "C:\\captures\\crawl_123\\000_home.xml",
                    mergedXmlPath = "C:\\captures\\crawl_123\\000_home_merged_accessibility.xml",
                ),
                screenRecord(
                    screenId = "screen_001",
                    screenName = "Details",
                    htmlPath = "/tmp/crawl/001_details.html",
                    xmlPath = "/tmp/crawl/001_details.xml",
                    mergedXmlPath = null,
                ),
            ),
        )

        val graph = CrawlGraphBuilder.build(manifest)

        assertEquals("000_home.html", graph.nodes[0].htmlFileName)
        assertEquals("000_home.xml", graph.nodes[0].xmlFileName)
        assertEquals("000_home_merged_accessibility.xml", graph.nodes[0].mergedXmlFileName)
        assertEquals("001_details.html", graph.nodes[1].htmlFileName)
        assertEquals("001_details.xml", graph.nodes[1].xmlFileName)
        assertNull(graph.nodes[1].mergedXmlFileName)
    }

    private fun manifest(
        screens: List<CrawlScreenRecord> = listOf(screenRecord()),
        edges: List<CrawlEdgeRecord> = emptyList(),
    ): CrawlManifest {
        return CrawlManifest(
            sessionId = "crawl_20260421_190000",
            packageName = "com.example.target",
            startedAt = 1_000L,
            finishedAt = 2_000L,
            status = CrawlRunStatus.IN_PROGRESS,
            rootScreenId = "screen_000",
            maxDepthReached = 2,
            screens = screens,
            edges = edges,
        )
    }

    private fun screenRecord(
        screenId: String = "screen_000",
        screenName: String = "Home",
        depth: Int = 0,
        htmlPath: String = "C:\\captures\\crawl_123\\000_home.html",
        xmlPath: String = "C:\\captures\\crawl_123\\000_home.xml",
        mergedXmlPath: String? = "C:\\captures\\crawl_123\\000_home_merged_accessibility.xml",
    ): CrawlScreenRecord {
        return CrawlScreenRecord(
            screenId = screenId,
            screenName = screenName,
            packageName = "com.example.target",
            screenFingerprint = "fingerprint-$screenId",
            htmlPath = htmlPath,
            xmlPath = xmlPath,
            mergedXmlPath = mergedXmlPath,
            scrollStepCount = 1,
            parentScreenId = null,
            triggerLabel = null,
            triggerResourceId = null,
            route = CrawlRoute(),
            depth = depth,
        )
    }
}
