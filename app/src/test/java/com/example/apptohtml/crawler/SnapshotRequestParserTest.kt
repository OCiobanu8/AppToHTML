package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast is the only externally-reachable entry point in the app, so its extras are parsed
 * by a pure function that can be exercised without a device.
 */
class SnapshotRequestParserTest {

    private fun parse(
        token: String? = null,
        name: String? = null,
        scrollBoolean: Boolean? = null,
        scrollString: String? = null,
    ) = SnapshotRequestParser.parse(
        token = token,
        name = name,
        scrollBoolean = scrollBoolean,
        scrollString = scrollString,
        tokenFallback = { "generated" },
    )

    @Test
    fun `scroll defaults to true when the extra is absent`() {
        assertTrue(parse().scroll)
    }

    @Test
    fun `a boolean scroll extra is honored`() {
        assertFalse(parse(scrollBoolean = false).scroll)
        assertTrue(parse(scrollBoolean = true).scroll)
    }

    @Test
    fun `a string scroll extra is honored because operators reach for --es`() {
        // `am broadcast --es scroll false` delivers a String, not a Boolean. Ignoring it would make
        // scroll=false silently do nothing, which reads as a broken flag.
        assertFalse(parse(scrollString = "false").scroll)
        assertFalse(parse(scrollString = "FALSE").scroll)
        assertFalse(parse(scrollString = "0").scroll)
        assertFalse(parse(scrollString = " no ").scroll)
        assertTrue(parse(scrollString = "true").scroll)
        assertTrue(parse(scrollString = "1").scroll)
    }

    @Test
    fun `a boolean scroll extra wins over a string one`() {
        assertTrue(parse(scrollBoolean = true, scrollString = "false").scroll)
    }

    @Test
    fun `an unparseable scroll string falls back to scrolling`() {
        assertTrue(parse(scrollString = "maybe").scroll)
        assertTrue(parse(scrollString = "").scroll)
    }

    @Test
    fun `an absent or blank token is generated`() {
        assertEquals("generated", parse().token)
        assertEquals("generated", parse(token = "   ").token)
    }

    @Test
    fun `a supplied token is preserved`() {
        assertEquals("abc123", parse(token = "abc123").token)
    }

    @Test
    fun `a token cannot escape its directory`() {
        // The token becomes part of a directory name, so path separators and traversal sequences
        // must not survive parsing.
        assertEquals("etcpasswd", parse(token = "../../etc/passwd").token)
        assertEquals("ab", parse(token = "a\\b").token)
        assertFalse(parse(token = "../..").token.contains(".."))
    }

    @Test
    fun `a token of only illegal characters falls back to a safe literal`() {
        assertEquals("token", parse(token = "///").token)
    }

    @Test
    fun `an over-long token is truncated`() {
        assertEquals(64, parse(token = "a".repeat(200)).token.length)
    }

    @Test
    fun `a blank name override is dropped rather than producing an empty label`() {
        assertNull(parse(name = "   ").nameOverride)
        assertNull(parse().nameOverride)
    }

    @Test
    fun `a name override is trimmed`() {
        assertEquals("empty cart", parse(name = "  empty cart  ").nameOverride)
    }
}
