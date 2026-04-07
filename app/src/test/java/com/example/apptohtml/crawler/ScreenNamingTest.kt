package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNamingTest {
    @Test
    fun deriveScreenName_prefers_real_title_over_generic_toolbar_chrome() {
        val root = node(
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2400]",
            children = listOf(
                node(
                    className = "android.widget.TextView",
                    text = "Navigate up",
                    bounds = "[0,0][320,100]",
                    childIndexPath = listOf(0),
                ),
                node(
                    className = "android.widget.TextView",
                    text = "More options",
                    bounds = "[760,0][1080,100]",
                    childIndexPath = listOf(1),
                ),
                node(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.google.android.gms:id/screen_title",
                    text = "Google services",
                    bounds = "[120,110][900,220]",
                    childIndexPath = listOf(2),
                ),
            ),
        )

        assertEquals(
            "Google services",
            ScreenNaming.deriveScreenName(
                eventClassName = null,
                selectedApp = selectedApp(),
                root = root,
            )
        )
    }

    @Test
    fun buildScreenIdentity_uses_hints_and_marks_generic_titles_weak() {
        val googleServices = node(
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2400]",
            children = listOf(
                node(
                    className = "android.widget.TextView",
                    text = "Connected devices & sharing",
                    bounds = "[0,140][1080,240]",
                    childIndexPath = listOf(0),
                ),
            ),
        )
        val sims = node(
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2400]",
            children = listOf(
                node(
                    className = "android.widget.TextView",
                    text = "SIMs",
                    bounds = "[0,140][1080,240]",
                    childIndexPath = listOf(0),
                ),
            ),
        )

        val googleIdentity = ScreenNaming.buildScreenIdentity(
            screenName = "Navigate up",
            packageName = "com.android.settings",
            root = googleServices,
        )
        val simIdentity = ScreenNaming.buildScreenIdentity(
            screenName = "Navigate up",
            packageName = "com.android.settings",
            root = sims,
        )

        assertEquals(ScreenDedupConfidence.WEAK, googleIdentity.confidence)
        assertEquals(ScreenDedupConfidence.WEAK, simIdentity.confidence)
        assertTrue(googleIdentity.fingerprint != simIdentity.fingerprint)
        assertTrue(googleIdentity.identityHints.isNotEmpty())
        assertTrue(simIdentity.identityHints.isNotEmpty())
    }

    private fun selectedApp(): SelectedAppRef {
        return SelectedAppRef(
            packageName = "com.android.settings",
            appName = "Settings",
            launcherActivity = "com.android.settings.Settings",
            selectedAt = 123L,
        )
    }

    private fun node(
        className: String,
        packageName: String = "com.android.settings",
        viewIdResourceName: String? = null,
        text: String? = null,
        bounds: String = "[0,0][100,100]",
        children: List<AccessibilityNodeSnapshot> = emptyList(),
        childIndexPath: List<Int> = emptyList(),
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            text = text,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = children,
            childIndexPath = childIndexPath,
        )
    }
}
