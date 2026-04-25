package com.example.apptohtml.crawler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CrawlGraphHtmlRendererTest {
    @Test
    fun render_inlines_json_in_script_tag() {
        val html = CrawlGraphHtmlRenderer.render(graph())

        assertTrue(html.contains("""<script type="application/json" id="crawl-graph-data">"""))
        assertTrue(html.contains(""""sessionId": "crawl_20260421_190000""""))
        assertTrue(html.contains(""""screenId": "screen_000""""))
        assertTrue(html.contains(""""htmlFileName": "000_home.html""""))
    }

    @Test
    fun render_has_no_http_or_https_references() {
        val html = CrawlGraphHtmlRenderer.render(graph())

        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
    }

    @Test
    fun render_includes_status_filter_checkbox_for_every_crawl_edge_status() {
        val html = CrawlGraphHtmlRenderer.render(graph())

        CrawlEdgeStatus.values().forEach { status ->
            val statusName = status.name.lowercase(Locale.US)
            assertTrue(html.contains("""data-status-filter="$statusName""""))
            assertTrue(html.contains("""class="filter-chip status-$statusName""""))
        }
    }

    private fun graph(): CrawlGraph {
        return CrawlGraph(
            sessionId = "crawl_20260421_190000",
            packageName = "com.example.target",
            generatedAtMs = 1_234L,
            rootScreenId = "screen_000",
            maxDepthReached = 2,
            nodes = listOf(
                CrawlGraphNode(
                    screenId = "screen_000",
                    screenName = "Home",
                    fingerprint = "fp-home",
                    packageName = "com.example.target",
                    depth = 0,
                    discoveryIndex = 0,
                    htmlFileName = "000_home.html",
                    xmlFileName = "000_home.xml",
                    mergedXmlFileName = "000_home_merged_accessibility.xml",
                ),
                CrawlGraphNode(
                    screenId = "screen_001",
                    screenName = "Details",
                    fingerprint = "fp-details",
                    packageName = "com.example.target",
                    depth = 1,
                    discoveryIndex = 1,
                    htmlFileName = "001_details.html",
                    xmlFileName = "001_details.xml",
                    mergedXmlFileName = null,
                ),
            ),
            edges = listOf(
                CrawlGraphEdge(
                    edgeId = "edge_000",
                    fromScreenId = "screen_000",
                    toScreenId = "screen_001",
                    label = "Open details",
                    status = CrawlEdgeStatus.CAPTURED,
                    message = null,
                ),
                CrawlGraphEdge(
                    edgeId = "edge_001",
                    fromScreenId = "screen_001",
                    toScreenId = null,
                    label = "Open browser",
                    status = CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE,
                    message = "User chose to stay inside the app.",
                ),
            ),
        )
    }
}
