package com.example.apptohtml.crawler

import java.io.File
import java.util.Locale

object CrawlGraphJsonWriter {
    fun write(graph: CrawlGraph, destination: File) {
        destination.writeText(toJson(graph), Charsets.UTF_8)
    }

    internal fun toJson(graph: CrawlGraph): String {
        return buildString {
            appendLine("{")
            appendLine("""  "sessionId": "${escape(graph.sessionId)}",""")
            appendLine("""  "packageName": "${escape(graph.packageName)}",""")
            appendLine("""  "generatedAtMs": ${graph.generatedAtMs},""")
            appendLine("""  "rootScreenId": ${quotedOrNull(graph.rootScreenId)},""")
            appendLine("""  "maxDepthReached": ${graph.maxDepthReached},""")
            appendLine("""  "nodes": [""")
            append(graph.nodes.joinToString(",\n") { node ->
                buildString {
                    appendLine("    {")
                    appendLine("""      "screenId": "${escape(node.screenId)}",""")
                    appendLine("""      "screenName": "${escape(node.screenName)}",""")
                    appendLine("""      "fingerprint": "${escape(node.fingerprint)}",""")
                    appendLine("""      "packageName": "${escape(node.packageName)}",""")
                    appendLine("""      "depth": ${node.depth},""")
                    appendLine("""      "discoveryIndex": ${node.discoveryIndex},""")
                    appendLine("""      "htmlFileName": ${quotedOrNull(node.htmlFileName)},""")
                    appendLine("""      "xmlFileName": ${quotedOrNull(node.xmlFileName)},""")
                    append("""      "mergedXmlFileName": ${quotedOrNull(node.mergedXmlFileName)}""")
                    appendLine()
                    append("    }")
                }
            })
            appendLine()
            appendLine("  ],")
            appendLine("""  "edges": [""")
            append(graph.edges.joinToString(",\n") { edge ->
                buildString {
                    appendLine("    {")
                    appendLine("""      "edgeId": "${escape(edge.edgeId)}",""")
                    appendLine("""      "fromScreenId": "${escape(edge.fromScreenId)}",""")
                    appendLine("""      "toScreenId": ${quotedOrNull(edge.toScreenId)},""")
                    appendLine("""      "label": "${escape(edge.label)}",""")
                    appendLine("""      "status": "${edge.status.jsonName()}",""")
                    append("""      "message": ${quotedOrNull(edge.message)}""")
                    appendLine()
                    append("    }")
                }
            })
            appendLine()
            appendLine("  ]")
            append("}")
        }
    }

    private fun quotedOrNull(value: String?): String {
        return if (value == null) {
            "null"
        } else {
            """"${escape(value)}""""
        }
    }

    private fun escape(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                append(
                    when (char) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> char
                    }
                )
            }
        }
    }

    private fun CrawlEdgeStatus.jsonName(): String = name.lowercase(Locale.US)
}
