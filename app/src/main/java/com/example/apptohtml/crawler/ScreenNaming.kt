package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import java.text.Normalizer
import java.util.Locale

object ScreenNaming {
    private val genericWindowNames = setOf(
        "android.view.View",
        "android.view.ViewGroup",
        "android.widget.FrameLayout",
        "android.widget.LinearLayout",
        "android.widget.ScrollView",
        "android.widget.RelativeLayout",
        "android.widget.ListView",
        "android.widget.GridView",
        "androidx.constraintlayout.widget.ConstraintLayout",
        "androidx.recyclerview.widget.RecyclerView",
        "android.view.ViewRootImpl",
        "android.view.ViewRootImpl\$W",
        "androidx.compose.ui.platform.ComposeView",
        "androidx.compose.ui.platform.AndroidComposeView",
        "com.android.internal.policy.DecorView",
    )
    private val genericIdTokens = setOf(
        "android",
        "app",
        "bar",
        "container",
        "content",
        "fragment",
        "frame",
        "grid",
        "group",
        "holder",
        "id",
        "item",
        "layout",
        "list",
        "main",
        "pane",
        "parent",
        "recycler",
        "root",
        "scroll",
        "scrollable",
        "section",
        "view",
        "wrapper",
    )
    private val homepageMarkers = setOf("homepage", "dashboard", "landing")

    fun deriveScreenName(
        eventClassName: String?,
        selectedApp: SelectedAppRef,
        root: AccessibilityNodeSnapshot? = null,
    ): String {
        val eventName = eventClassName
            ?.takeIf(::isUsefulWindowClassName)
            ?.substringAfterLast('.')
            ?.substringAfterLast('$')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (!eventName.isNullOrBlank()) {
            return eventName
        }

        val resourceBasedName = root?.let { deriveNameFromVisibleResourceIds(it, selectedApp) }
        if (!resourceBasedName.isNullOrBlank()) {
            return resourceBasedName
        }

        val launcherName = selectedApp.launcherActivity
            .substringAfterLast('.')
            .substringAfterLast('$')
            .trim()
        if (launcherName.isNotBlank()) {
            return launcherName
        }

        return selectedApp.appName.ifBlank { "CapturedScreen" }
    }

    fun chooseElementLabel(
        text: String?,
        contentDescription: String?,
        viewIdResourceName: String?,
        bounds: String,
    ): String {
        val trimmedText = text?.trim().orEmpty()
        if (trimmedText.isNotEmpty()) return trimmedText

        val trimmedDescription = contentDescription?.trim().orEmpty()
        if (trimmedDescription.isNotEmpty()) return trimmedDescription

        val viewIdSegment = viewIdResourceName
            ?.substringAfterLast('/')
            ?.replace('_', ' ')
            ?.trim()
            .orEmpty()
        if (viewIdSegment.isNotEmpty()) return viewIdSegment

        return "Tap target $bounds"
    }

    fun toFileBase(screenName: String): String {
        val normalized = Normalizer.normalize(screenName, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
        val slug = normalized
            .lowercase(Locale.US)
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')
        return slug.ifBlank { "captured_screen" }
    }

    private fun isUsefulWindowClassName(className: String): Boolean {
        if (className.isBlank()) return false
        if (genericWindowNames.contains(className)) return false

        val simpleName = className.substringAfterLast('.').substringAfterLast('$')
        if (simpleName.isBlank()) return false

        return simpleName.endsWith("Activity") ||
            simpleName.endsWith("Fragment") ||
            simpleName.endsWith("Screen") ||
            simpleName !in setOf(
                "View",
                "ViewGroup",
                "FrameLayout",
                "LinearLayout",
                "ScrollView",
                "RelativeLayout",
                "ListView",
                "GridView",
                "RecyclerView",
                "ConstraintLayout",
                "ComposeView",
                "AndroidComposeView",
                "DecorView",
            )
    }

    private fun deriveNameFromVisibleResourceIds(
        root: AccessibilityNodeSnapshot,
        selectedApp: SelectedAppRef,
    ): String? {
        val appTokens = tokenizeIdentifier(selectedApp.appName).toSet()
        val candidates = collectVisibleResourceIdCandidates(root, appTokens)
        return candidates.maxByOrNull { it.score }?.title
    }

    private fun collectVisibleResourceIdCandidates(
        node: AccessibilityNodeSnapshot,
        appTokens: Set<String>,
    ): List<ResourceTitleCandidate> {
        val current = buildList {
            if (node.visibleToUser) {
                buildCandidate(node.viewIdResourceName, appTokens)?.let(::add)
            }
        }

        return current + node.children.flatMap { child ->
            collectVisibleResourceIdCandidates(child, appTokens)
        }
    }

    private fun buildCandidate(
        viewIdResourceName: String?,
        appTokens: Set<String>,
    ): ResourceTitleCandidate? {
        val segment = viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
        if (segment.isBlank()) return null

        val rawTokens = tokenizeIdentifier(segment)
        if (rawTokens.isEmpty()) return null

        val cleanedTokens = rawTokens.filterNot { token ->
            token in genericIdTokens
        }
        val hasHomepageMarker = rawTokens.any { token -> token in homepageMarkers } ||
            (rawTokens.contains("home") && rawTokens.contains("page"))
        val titleTokens = when {
            hasHomepageMarker && appTokens.isNotEmpty() -> appTokens.toList() + "homepage"
            cleanedTokens.isNotEmpty() -> cleanedTokens
            else -> return null
        }

        val title = titleTokens.joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
            }
        }.trim()
        if (title.isBlank()) return null

        var score = cleanedTokens.size
        if (hasHomepageMarker) score += 100
        if (rawTokens.any { token -> token in appTokens }) score += 40
        if (rawTokens.any { token -> token == "title" || token == "toolbar" }) score -= 20
        if (rawTokens.any { token -> token == "search" }) score -= 30

        return ResourceTitleCandidate(title = title, score = score)
    }

    private fun tokenizeIdentifier(value: String): List<String> {
        return value
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    private data class ResourceTitleCandidate(
        val title: String,
        val score: Int,
    )
}
