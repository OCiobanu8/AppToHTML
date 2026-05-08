package com.example.apptohtml.crawler

data class CrawlGraphNode(
    val screenId: String,
    val screenName: String,
    val fingerprint: String,
    val replayFingerprint: String,
    val packageName: String,
    val depth: Int,
    val discoveryIndex: Int,
    val htmlFileName: String?,
    val xmlFileName: String?,
    val mergedXmlFileName: String?,
)

data class CrawlGraphEdge(
    val edgeId: String,
    val fromScreenId: String,
    val toScreenId: String?,
    val label: String,
    val status: CrawlEdgeStatus,
    val message: String?,
)

data class CrawlGraph(
    val sessionId: String,
    val packageName: String,
    val generatedAtMs: Long,
    val rootScreenId: String?,
    val maxDepthReached: Int,
    val nodes: List<CrawlGraphNode>,
    val edges: List<CrawlGraphEdge>,
)
