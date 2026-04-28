package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrawlGraphJsonWriterTest {
    @Test
    fun write_produces_valid_parseable_json_with_all_node_and_edge_fields() {
        val tempFile = Files.createTempFile("crawl-graph", ".json").toFile()
        try {
            val graph = CrawlGraph(
                sessionId = "crawl_20260421_190000",
                packageName = "com.example.target",
                generatedAtMs = 1_234L,
                rootScreenId = "screen_00000",
                maxDepthReached = 2,
                nodes = listOf(
                    CrawlGraphNode(
                        screenId = "screen_00000",
                        screenName = "Home",
                        fingerprint = "fp-home",
                        packageName = "com.example.target",
                        depth = 0,
                        discoveryIndex = 0,
                        htmlFileName = "screen_00000_home.html",
                        xmlFileName = "screen_00000_home.xml",
                        mergedXmlFileName = "screen_00000_home_merged_accessibility.xml",
                    )
                ),
                edges = listOf(
                    CrawlGraphEdge(
                        edgeId = "edge_000",
                        fromScreenId = "screen_00000",
                        toScreenId = "screen_00001",
                        label = "Open details",
                        status = CrawlEdgeStatus.CAPTURED,
                        message = "Child screen captured.",
                    )
                ),
            )

            CrawlGraphJsonWriter.write(graph, tempFile)

            val parsed = parseObject(tempFile.readText())
            val node = parsed.array("nodes").objectAt(0)
            val edge = parsed.array("edges").objectAt(0)

            assertEquals("crawl_20260421_190000", parsed.string("sessionId"))
            assertEquals("com.example.target", parsed.string("packageName"))
            assertEquals(1_234L, parsed.long("generatedAtMs"))
            assertEquals("screen_00000", parsed.string("rootScreenId"))
            assertEquals(2L, parsed.long("maxDepthReached"))
            assertEquals("screen_00000", node.string("screenId"))
            assertEquals("Home", node.string("screenName"))
            assertEquals("fp-home", node.string("fingerprint"))
            assertEquals("com.example.target", node.string("packageName"))
            assertEquals(0L, node.long("depth"))
            assertEquals(0L, node.long("discoveryIndex"))
            assertEquals("screen_00000_home.html", node.string("htmlFileName"))
            assertEquals("screen_00000_home.xml", node.string("xmlFileName"))
            assertEquals("screen_00000_home_merged_accessibility.xml", node.string("mergedXmlFileName"))
            assertEquals("edge_000", edge.string("edgeId"))
            assertEquals("screen_00000", edge.string("fromScreenId"))
            assertEquals("screen_00001", edge.string("toScreenId"))
            assertEquals("Open details", edge.string("label"))
            assertEquals("captured", edge.string("status"))
            assertEquals("Child screen captured.", edge.string("message"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun write_escapes_quote_and_newline_characters_in_labels() {
        val graph = CrawlGraph(
            sessionId = "crawl_20260421_190000",
            packageName = "com.example.target",
            generatedAtMs = 1_234L,
            rootScreenId = null,
            maxDepthReached = 0,
            nodes = listOf(
                CrawlGraphNode(
                    screenId = "screen_00000",
                    screenName = "Home \"Screen\"",
                    fingerprint = "fp-home",
                    packageName = "com.example.target",
                    depth = 0,
                    discoveryIndex = 0,
                    htmlFileName = "screen_00000_home.html",
                    xmlFileName = "screen_00000_home.xml",
                    mergedXmlFileName = null,
                )
            ),
            edges = listOf(
                CrawlGraphEdge(
                    edgeId = "edge_000",
                    fromScreenId = "screen_00000",
                    toScreenId = null,
                    label = "Open \"details\"\nnow",
                    status = CrawlEdgeStatus.FAILED,
                    message = "Line 1\nLine \"2\"",
                )
            ),
        )

        val json = CrawlGraphJsonWriter.toJson(graph)
        val parsed = parseObject(json)
        val node = parsed.array("nodes").objectAt(0)
        val edge = parsed.array("edges").objectAt(0)

        assertTrue(json.contains("""Home \"Screen\""""))
        assertTrue(json.contains("""Open \"details\"\nnow"""))
        assertTrue(json.contains("""Line 1\nLine \"2\""""))
        assertEquals("Home \"Screen\"", node.string("screenName"))
        assertTrue(node.value("mergedXmlFileName") == null)
        assertEquals("Open \"details\"\nnow", edge.string("label"))
        assertEquals("Line 1\nLine \"2\"", edge.string("message"))
        assertTrue(edge.value("toScreenId") == null)
    }

    private fun parseObject(json: String): JsonObject {
        val parser = JsonParser(json)
        val parsed = parser.parseValue()
        parser.requireEnd()
        return JsonObject(parsed.asObjectMap())
    }

    private class JsonObject(
        private val values: Map<String, Any?>,
    ) {
        fun string(key: String): String = values.getValue(key) as String

        fun long(key: String): Long = values.getValue(key) as Long

        fun array(key: String): JsonArray = JsonArray(values.getValue(key) as List<Any?>)

        fun value(key: String): Any? = values[key]
    }

    private class JsonArray(
        private val values: List<Any?>,
    ) {
        fun objectAt(index: Int): JsonObject {
            val value = values[index]
            check(value is Map<*, *>) { "Expected JSON object but found ${value?.javaClass?.name ?: "null"}." }
            @Suppress("UNCHECKED_CAST")
            return JsonObject(value as Map<String, Any?>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObjectMap(): Map<String, Any?> {
        check(this is Map<*, *>) { "Expected JSON object but found ${this?.javaClass?.name ?: "null"}." }
        return this as Map<String, Any?>
    }

    private class JsonParser(
        private val source: String,
    ) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            check(index < source.length) { "Unexpected end of JSON." }
            return when (val current = source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                'n' -> parseNull()
                't', 'f' -> parseBoolean()
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected character '$current' at $index.")
            }
        }

        fun requireEnd() {
            skipWhitespace()
            check(index == source.length) { "Unexpected trailing content at $index." }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            if (peek('}')) {
                index += 1
                return emptyMap()
            }

            val result = linkedMapOf<String, Any?>()
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                when {
                    peek('}') -> {
                        index += 1
                        return result
                    }

                    peek(',') -> index += 1
                    else -> error("Expected ',' or '}' at $index.")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            if (peek(']')) {
                index += 1
                return emptyList()
            }

            val result = mutableListOf<Any?>()
            while (true) {
                result += parseValue()
                skipWhitespace()
                when {
                    peek(']') -> {
                        index += 1
                        return result
                    }

                    peek(',') -> index += 1
                    else -> error("Expected ',' or ']' at $index.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < source.length) {
                val current = source[index++]
                when (current) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        check(index < source.length) { "Unexpected end of escape sequence." }
                        val escaped = source[index++]
                        builder.append(
                            when (escaped) {
                                '"', '\\', '/' -> escaped
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val hex = source.substring(index, index + 4)
                                    index += 4
                                    hex.toInt(16).toChar()
                                }

                                else -> error("Unsupported escape '\\$escaped' at ${index - 1}.")
                            }
                        )
                    }

                    else -> builder.append(current)
                }
            }
            error("Unterminated string literal.")
        }

        private fun parseNull(): Any? {
            expectLiteral("null")
            return null
        }

        private fun parseBoolean(): Boolean {
            return if (peek('t')) {
                expectLiteral("true")
                true
            } else {
                expectLiteral("false")
                false
            }
        }

        private fun parseNumber(): Long {
            val start = index
            if (peek('-')) {
                index += 1
            }
            while (index < source.length && source[index].isDigit()) {
                index += 1
            }
            return source.substring(start, index).toLong()
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) {
                index += 1
            }
        }

        private fun expect(expected: Char) {
            check(peek(expected)) { "Expected '$expected' at $index." }
            index += 1
        }

        private fun expectLiteral(literal: String) {
            check(source.startsWith(literal, index)) { "Expected '$literal' at $index." }
            index += literal.length
        }

        private fun peek(expected: Char): Boolean {
            return index < source.length && source[index] == expected
        }
    }
}
