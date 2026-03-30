package com.example.apptohtml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppToHtmlInstrumentedTest {
    @Test
    fun appPickerLoadsLauncherApps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = AppDiscovery.queryLauncherApps(
            packageManager = context.packageManager,
            excludePackageName = context.packageName,
        )
        assertTrue(apps.isNotEmpty())
    }

    @Test
    fun accessibilityServiceComponentNameUsesRenamedService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "${context.packageName}/com.example.apptohtml.AppToHtmlAccessibilityService",
            AppDiscovery.accessibilityServiceComponentName(context.packageName),
        )
    }
}
