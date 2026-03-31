package com.example.apptohtml.crawler

import android.content.Context
import com.example.apptohtml.R
import java.util.Locale

data class CrawlBlacklist(
    val labelTokens: Set<String> = emptySet(),
    val resourceIdTokens: Set<String> = emptySet(),
    val classNameTokens: Set<String> = emptySet(),
    val skipCheckable: Boolean = true,
) {
    fun skipReason(element: PressableElement): String? {
        val normalizedLabel = normalize(element.label)
        if (matchesToken(normalizedLabel, labelTokens)) {
            return "blacklist-label"
        }

        val normalizedResourceId = normalize(element.resourceId)
        if (matchesToken(normalizedResourceId, resourceIdTokens)) {
            return "blacklist-resource-id"
        }

        val normalizedClassName = normalize(element.className)
        if (matchesToken(normalizedClassName, classNameTokens)) {
            return "blacklist-class"
        }

        if (skipCheckable && element.checkable) {
            return "blacklist-checkable"
        }

        return null
    }

    private fun matchesToken(value: String, tokens: Set<String>): Boolean {
        return tokens.any { token ->
            value.contains(token)
        }
    }

    private fun normalize(value: String?): String {
        return value
            .orEmpty()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}

object CrawlBlacklistLoader {
    fun load(context: Context): CrawlBlacklist {
        val json = context.resources.openRawResource(R.raw.crawl_blacklist)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText() }
        return parse(json)
    }

    internal fun parse(json: String): CrawlBlacklist {
        return CrawlBlacklist(
            labelTokens = readStringSet(json, "labelTokens"),
            resourceIdTokens = readStringSet(json, "resourceIdTokens"),
            classNameTokens = readStringSet(json, "classNameTokens"),
            skipCheckable = readBoolean(json, "skipCheckable", true),
        )
    }

    private fun readStringSet(json: String, name: String): Set<String> {
        val rawArray = arrayPattern(name).find(json)?.groupValues?.get(1).orEmpty()
        return buildSet {
            stringPattern.findAll(rawArray).forEach { match ->
                val token = match.groupValues[1]
                    .lowercase(Locale.US)
                    .trim()
                if (token.isNotEmpty()) {
                    add(token)
                }
            }
        }
    }

    private fun readBoolean(json: String, name: String, defaultValue: Boolean): Boolean {
        val rawValue = booleanPattern(name).find(json)?.groupValues?.get(1) ?: return defaultValue
        return rawValue.equals("true", ignoreCase = true)
    }

    private fun arrayPattern(name: String): Regex {
        return Regex("\"$name\"\\s*:\\s*\\[(.*?)]", setOf(RegexOption.DOT_MATCHES_ALL))
    }

    private fun booleanPattern(name: String): Regex {
        return Regex("\"$name\"\\s*:\\s*(true|false)")
    }

    private val stringPattern = Regex("\"(.*?)\"")
}
