package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickFallbackMatcherTest {

    @Test
    fun doesNotMatchUnrelatedRowWithDifferentLabel() {
        val target = target(
            label = "Network & internet",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
        )
        // Shares the generic .../title resource id but has a different label -> different fingerprint.
        val unrelated = candidate(
            handle = "tmobileRow",
            fingerprint = fingerprint(
                label = "T-Mobile",
                resourceId = "com.android.settings:id/title",
                className = "android.widget.TextView",
            ),
            depth = 7,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(unrelated), target)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun matchesSameElementRegardlessOfPosition() {
        val target = target(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
        )
        val display = candidate(
            handle = "display",
            fingerprint = fingerprint(
                label = "Display",
                resourceId = "com.android.settings:id/title",
                className = "android.widget.TextView",
            ),
            depth = 5,
        )
        // Same generic resource id, different label -> NOT a match (semantic identity is stricter
        // than the old resourceId-only match).
        val sound = candidate(
            handle = "sound",
            fingerprint = fingerprint(
                label = "Sound",
                resourceId = "com.android.settings:id/title",
                className = "android.widget.TextView",
            ),
            depth = 5,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(sound, display), target)

        assertEquals(1, matches.size)
        assertEquals("display", matches.first().candidate.handle)
        assertEquals(ClickFallbackMatcher.EligibilityReason.RESOURCE_ID_MATCH, matches.first().eligibilityReason)
    }

    @Test
    fun matchesUnlabeledIconByClassFingerprint() {
        val target = target(label = "", resourceId = null, className = "android.widget.ImageView")
        val icon = candidate(
            handle = "icon",
            fingerprint = fingerprint(label = "", resourceId = null, className = "android.widget.ImageView"),
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(icon), target)

        assertEquals(1, matches.size)
        assertEquals(ClickFallbackMatcher.EligibilityReason.CLASS_MATCH, matches.first().eligibilityReason)
    }

    @Test
    fun doesNotMatchUnlabeledIconWithDifferentClass() {
        // Without bounds, a differing class is a differing fingerprint -> no match (stricter than
        // the old bounds-alone icon match).
        val target = target(label = "", resourceId = null, className = "android.widget.ImageView")
        val icon = candidate(
            handle = "icon",
            fingerprint = fingerprint(label = "", resourceId = null, className = "android.view.View"),
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(icon), target)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun usesLabelMatchReasonWhenNoResourceId() {
        val target = target(label = "Save", resourceId = null, className = "android.widget.Button")
        val save = candidate(
            handle = "save",
            fingerprint = fingerprint(label = "Save", resourceId = null, className = "android.widget.Button"),
            depth = 3,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(save), target)

        assertEquals(1, matches.size)
        assertEquals(ClickFallbackMatcher.EligibilityReason.LABEL_MATCH, matches.first().eligibilityReason)
    }

    @Test
    fun rejectsInvisibleDisabledOrNonClickableCandidates() {
        val fp = fingerprint(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
        )
        val target = ClickFallbackMatcher.Target(fp)
        val invisible = candidate(handle = "invisible", fingerprint = fp, visible = false, depth = 4)
        val disabled = candidate(handle = "disabled", fingerprint = fp, enabled = false, depth = 4)
        val unclickable = candidate(
            handle = "unclickable",
            fingerprint = fp,
            clickable = false,
            supportsClickAction = false,
            depth = 4,
        )

        val matches = ClickFallbackMatcher.selectMatches(listOf(invisible, disabled, unclickable), target)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun ranksShallowerCandidatesAboveDeeperAmongEqualFingerprints() {
        val fp = fingerprint(
            label = "Display",
            resourceId = "com.android.settings:id/title",
            className = "android.widget.TextView",
        )
        val target = ClickFallbackMatcher.Target(fp)
        val shallow = candidate(handle = "shallow", fingerprint = fp, depth = 3)
        val deep = candidate(handle = "deep", fingerprint = fp, depth = 9)

        val matches = ClickFallbackMatcher.selectMatches(listOf(deep, shallow), target)

        assertEquals(2, matches.size)
        assertEquals("shallow", matches.first().candidate.handle)
        assertEquals("deep", matches.last().candidate.handle)
    }

    private fun fingerprint(
        label: String = "",
        resourceId: String? = null,
        className: String? = null,
        isListItem: Boolean = false,
        checkable: Boolean = false,
        editable: Boolean = false,
    ): ElementFingerprint = ElementFingerprint.ofFields(
        label = label,
        resourceId = resourceId,
        className = className,
        isListItem = isListItem,
        checkable = checkable,
        editable = editable,
    )

    private fun target(
        label: String,
        resourceId: String?,
        className: String?,
    ): ClickFallbackMatcher.Target =
        ClickFallbackMatcher.Target(fingerprint(label = label, resourceId = resourceId, className = className))

    private fun candidate(
        handle: String,
        fingerprint: ElementFingerprint,
        visible: Boolean = true,
        enabled: Boolean = true,
        clickable: Boolean = true,
        supportsClickAction: Boolean = true,
        depth: Int,
    ): ClickFallbackMatcher.Candidate<String> = ClickFallbackMatcher.Candidate(
        handle = handle,
        visible = visible,
        enabled = enabled,
        clickable = clickable,
        supportsClickAction = supportsClickAction,
        fingerprint = fingerprint,
        depth = depth,
    )
}
