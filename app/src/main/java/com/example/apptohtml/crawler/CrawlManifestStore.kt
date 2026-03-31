package com.example.apptohtml.crawler

import java.io.File

data class CrawlSessionDirectory(
    val sessionId: String,
    val directory: File,
    val manifestFile: File,
)

object CrawlManifestStore {
    fun write(manifest: CrawlManifest, destination: File) {
        destination.writeText(toJson(manifest), Charsets.UTF_8)
    }

    internal fun toJson(manifest: CrawlManifest): String {
        return buildString {
            appendLine("{")
            appendLine("""  "sessionId": "${escape(manifest.sessionId)}",""")
            appendLine("""  "packageName": "${escape(manifest.packageName)}",""")
            appendLine("""  "startedAt": ${manifest.startedAt},""")
            appendLine("""  "finishedAt": ${manifest.finishedAt ?: "null"},""")
            appendLine("""  "status": "${manifest.status.displayName()}",""")
            appendLine("""  "rootScreenId": ${quotedOrNull(manifest.rootScreenId)},""")
            appendLine("""  "screens": [""")
            append(manifest.screens.joinToString(",\n") { screen ->
                buildString {
                    appendLine("    {")
                    appendLine("""      "screenId": "${escape(screen.screenId)}",""")
                    appendLine("""      "screenName": "${escape(screen.screenName)}",""")
                    appendLine("""      "htmlPath": "${escape(screen.htmlPath)}",""")
                    appendLine("""      "xmlPath": "${escape(screen.xmlPath)}",""")
                    appendLine("""      "scrollStepCount": ${screen.scrollStepCount},""")
                    appendLine("""      "parentScreenId": ${quotedOrNull(screen.parentScreenId)},""")
                    appendLine("""      "triggerLabel": ${quotedOrNull(screen.triggerLabel)},""")
                    appendLine("""      "triggerResourceId": ${quotedOrNull(screen.triggerResourceId)},""")
                    append("""      "depth": ${screen.depth}""")
                    appendLine()
                    append("    }")
                }
            })
            appendLine()
            appendLine("  ],")
            appendLine("""  "edges": [""")
            append(manifest.edges.joinToString(",\n") { edge ->
                buildString {
                    appendLine("    {")
                    appendLine("""      "edgeId": "${escape(edge.edgeId)}",""")
                    appendLine("""      "parentScreenId": "${escape(edge.parentScreenId)}",""")
                    appendLine("""      "childScreenId": ${quotedOrNull(edge.childScreenId)},""")
                    appendLine("""      "label": "${escape(edge.label)}",""")
                    appendLine("""      "resourceId": ${quotedOrNull(edge.resourceId)},""")
                    appendLine("""      "className": ${quotedOrNull(edge.className)},""")
                    appendLine("""      "bounds": "${escape(edge.bounds)}",""")
                    appendLine("""      "childIndexPath": [${edge.childIndexPath.joinToString(",")}],""")
                    appendLine("""      "firstSeenStep": ${edge.firstSeenStep},""")
                    appendLine("""      "status": "${edge.status.name.lowercase()}",""")
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
}
