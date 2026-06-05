package com.example.apptohtml.crawler

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRendererTest {

    @Test
    fun anchor_carries_fingerprint_attribute_matching_element_fingerprint() {
        val element = PressableElement(
            label = "Open",
            resourceId = "com.example.target:id/open",
            bounds = "[0,0][100,100]",
            className = "android.widget.Button",
            isListItem = false,
            childIndexPath = listOf(0),
            checkable = false,
            checked = false,
            editable = false,
            firstSeenStep = 0,
        )
        val snapshot = ScreenSnapshot(
            screenName = "Home",
            packageName = "com.example.target",
            elements = listOf(element),
            xmlDump = "",
            scrollStepCount = 1,
        )

        val html = HtmlRenderer.render(snapshot)

        assertTrue(html.contains("<a href="))
        assertTrue(html.contains("""fingerprint="${ElementFingerprint.of(element).encoded}""""))
    }
}
