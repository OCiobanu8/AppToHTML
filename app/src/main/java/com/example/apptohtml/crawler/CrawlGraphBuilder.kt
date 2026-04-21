package com.example.apptohtml.crawler

object CrawlGraphBuilder {
    fun build(manifest: CrawlManifest): CrawlGraph {
        return CrawlGraph(
            sessionId = manifest.sessionId,
            packageName = manifest.packageName,
            generatedAtMs = manifest.finishedAt ?: manifest.startedAt,
            rootScreenId = manifest.rootScreenId,
            maxDepthReached = manifest.maxDepthReached,
            nodes = manifest.screens.mapIndexed { index, screen ->
                CrawlGraphNode(
                    screenId = screen.screenId,
                    screenName = screen.screenName,
                    fingerprint = screen.screenFingerprint,
                    packageName = screen.packageName,
                    depth = screen.depth,
                    discoveryIndex = index,
                    htmlFileName = basenameOrNull(screen.htmlPath),
                    xmlFileName = basenameOrNull(screen.xmlPath),
                    mergedXmlFileName = basenameOrNull(screen.mergedXmlPath),
                )
            },
            edges = manifest.edges.map { edge ->
                CrawlGraphEdge(
                    edgeId = edge.edgeId,
                    fromScreenId = edge.parentScreenId,
                    toScreenId = edge.childScreenId,
                    label = edge.label,
                    status = edge.status,
                    message = edge.message,
                )
            },
        )
    }

    private fun basenameOrNull(path: String?): String? {
        return path?.substringAfterLast('/')?.substringAfterLast('\\')
    }
}
