package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickFallbackMatcherTest {
    @Test
    fun doesNotMatchUnrelatedCarrierRowForNetworkInternet() {
        val target = ClickFallbackMatcher.Target(
            label = "Network & internet",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = "[64,256][1000,360]",
            checkable = false,
            checked = false,
        )
        val unrelatedCandidate = candidate(
            handle = "tmobileRow",
            resolvedLabel = "T-Mobile",
            resourceId = "com.android.settings:id/carrier_name",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 1024, 1000, 1128),
            depth = 7,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(unrelatedCandidate),
            target = target,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun matchesSameLabelAfterPathShift() {
        val target = ClickFallbackMatcher.Target(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = "[64,512][1000,616]",
            checkable = false,
            checked = false,
        )
        val shiftedCandidate = candidate(
            handle = "shiftedDisplay",
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 612, 1000, 716),
            depth = 5,
        )
        val unrelatedCandidate = candidate(
            handle = "soundRow",
            resolvedLabel = "Sound",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 800, 1000, 904),
            depth = 5,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(unrelatedCandidate, shiftedCandidate),
            target = target,
        )

        assertEquals(2, matches.size)
        assertEquals("shiftedDisplay", matches.first().candidate.handle)
        assertEquals(ClickFallbackMatcher.EligibilityReason.RESOURCE_ID_MATCH, matches.first().eligibilityReason)
    }

    @Test
    fun matchesIconOnlyByStrongBoundsAndClass() {
        val target = ClickFallbackMatcher.Target(
            label = "",
            resourceId = null,
            className = "android.widget.ImageView",
            bounds = "[900,128][996,224]",
            checkable = false,
            checked = false,
        )
        val iconCandidate = candidate(
            handle = "icon",
            resolvedLabel = null,
            resourceId = null,
            className = "android.widget.ImageView",
            bounds = ClickFallbackMatcher.Bounds(905, 130, 999, 222),
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(iconCandidate),
            target = target,
        )

        assertEquals(1, matches.size)
        val match = matches.first()
        assertEquals(ClickFallbackMatcher.EligibilityReason.CLASS_PLUS_BOUNDS_MATCH, match.eligibilityReason)
    }

    @Test
    fun matchesUnlabeledIconByBoundsAloneEvenWithoutClassMatch() {
        val target = ClickFallbackMatcher.Target(
            label = "",
            resourceId = null,
            className = null,
            bounds = "[900,128][996,224]",
            checkable = false,
            checked = false,
        )
        val iconCandidate = candidate(
            handle = "icon",
            resolvedLabel = null,
            resourceId = null,
            className = "android.view.View",
            bounds = ClickFallbackMatcher.Bounds(905, 130, 999, 222),
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(iconCandidate),
            target = target,
        )

        assertEquals(1, matches.size)
        assertEquals(ClickFallbackMatcher.EligibilityReason.BOUNDS_ICON_MATCH, matches.first().eligibilityReason)
    }

    @Test
    fun rejectsClassOnlyAndCheckStateOnly() {
        val target = ClickFallbackMatcher.Target(
            label = "Wi-Fi",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.Switch",
            bounds = "[64,512][1000,616]",
            checkable = true,
            checked = true,
        )
        val classOnly = candidate(
            handle = "classOnly",
            resolvedLabel = "Different",
            resourceId = "com.android.settings:id/other",
            className = "android.widget.Switch",
            bounds = ClickFallbackMatcher.Bounds(64, 1200, 1000, 1304),
            checkable = true,
            checked = true,
            depth = 6,
        )
        val checkStateOnly = candidate(
            handle = "checkStateOnly",
            resolvedLabel = "Different",
            resourceId = null,
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 1400, 1000, 1504),
            checkable = true,
            checked = true,
            depth = 7,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(classOnly, checkStateOnly),
            target = target,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun rejectsInvisibleDisabledOrNonClickableCandidates() {
        val target = ClickFallbackMatcher.Target(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = "[64,512][1000,616]",
            checkable = false,
            checked = false,
        )
        val invisible = candidate(
            handle = "invisible",
            visible = false,
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 512, 1000, 616),
            depth = 4,
        )
        val disabled = candidate(
            handle = "disabled",
            enabled = false,
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 512, 1000, 616),
            depth = 4,
        )
        val unclickable = candidate(
            handle = "unclickable",
            clickable = false,
            supportsClickAction = false,
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 512, 1000, 616),
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(invisible, disabled, unclickable),
            target = target,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun ranksDeeperResolvedCandidatesLowerThanShallowAfterEligibility() {
        val target = ClickFallbackMatcher.Target(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = "[64,512][1000,616]",
            checkable = false,
            checked = false,
        )
        val shallow = candidate(
            handle = "shallow",
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 512, 1000, 616),
            depth = 3,
        )
        val deep = candidate(
            handle = "deep",
            resolvedLabel = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
            bounds = ClickFallbackMatcher.Bounds(64, 512, 1000, 616),
            depth = 9,
        )

        val matches = ClickFallbackMatcher.selectMatches(
            candidates = listOf(deep, shallow),
            target = target,
        )

        assertEquals(2, matches.size)
        assertEquals("shallow", matches.first().candidate.handle)
        assertEquals("deep", matches.last().candidate.handle)
    }

    @Test
    fun parsesAndIgnoresInvalidBoundsStrings() {
        assertNull(ClickFallbackMatcher.Bounds.parse("invalid"))
        assertNotNull(ClickFallbackMatcher.Bounds.parse("[0,0][10,10]"))
    }

    private fun candidate(
        handle: String,
        visible: Boolean = true,
        enabled: Boolean = true,
        clickable: Boolean = true,
        supportsClickAction: Boolean = true,
        resolvedLabel: String?,
        resourceId: String?,
        className: String?,
        bounds: ClickFallbackMatcher.Bounds?,
        checkable: Boolean = false,
        checked: Boolean = false,
        depth: Int,
    ): ClickFallbackMatcher.Candidate<String> {
        return ClickFallbackMatcher.Candidate(
            handle = handle,
            visible = visible,
            enabled = enabled,
            clickable = clickable,
            supportsClickAction = supportsClickAction,
            resolvedLabel = resolvedLabel,
            resourceId = resourceId,
            className = className,
            bounds = bounds,
            checkable = checkable,
            checked = checked,
            depth = depth,
        )
    }
}
