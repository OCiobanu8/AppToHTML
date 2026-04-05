package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import java.text.Normalizer
import java.util.Locale

object ScreenNaming {
    private const val minStrongTextTitleScore = 160
    private val boundsRegex = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
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
    private val strongTitleIdMarkers = setOf(
        "action_bar",
        "app_bar",
        "collapsing",
        "header",
        "heading",
        "pane_title",
        "screen_title",
        "toolbar",
        "top_bar",
    )

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

        val textCandidate = root?.let { currentRoot ->
            runCatching { deriveNameFromVisibleText(currentRoot) }
                .onFailure { error ->
                    DiagnosticLogger.error("Visible-text screen naming failed; falling back to other naming strategies.", error)
                }
                .getOrNull()
        }
        val resourceCandidate = root?.let { currentRoot ->
            runCatching { deriveNameFromVisibleResourceIds(currentRoot, selectedApp) }
                .onFailure { error ->
                    DiagnosticLogger.error("Resource-id screen naming failed; falling back to launcher/app naming.", error)
                }
                .getOrNull()
        }

        when {
            textCandidate != null && (
                resourceCandidate == null ||
                    textCandidate.score >= minStrongTextTitleScore ||
                    textCandidate.score > resourceCandidate.score
                ) -> {
                return textCandidate.title
            }

            resourceCandidate != null -> {
                return resourceCandidate.title
            }
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
        return runCatching {
            val normalized = Normalizer.normalize(screenName, Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
            val slug = normalized
                .lowercase(Locale.US)
                .replace("[^a-z0-9]+".toRegex(), "_")
                .trim('_')
            slug.ifBlank { "captured_screen" }
        }.getOrElse { error ->
            DiagnosticLogger.error("Failed to slugify screen name '$screenName'. Falling back to a generic filename.", error)
            "captured_screen"
        }
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
    ): ResourceTitleCandidate? {
        val appTokens = tokenizeIdentifier(selectedApp.appName).toSet()
        val candidates = collectVisibleResourceIdCandidates(root, appTokens)
        return candidates.maxByOrNull { it.score }
    }

    private fun deriveNameFromVisibleText(root: AccessibilityNodeSnapshot): TextTitleCandidate? {
        val candidates = collectVisibleTextCandidates(
            node = root,
            depth = 0,
            insideScrollableAncestor = false,
            insideClickableAncestor = false,
        )
        return candidates.maxByOrNull { it.score }
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

    private fun collectVisibleTextCandidates(
        node: AccessibilityNodeSnapshot,
        depth: Int,
        insideScrollableAncestor: Boolean,
        insideClickableAncestor: Boolean,
    ): List<TextTitleCandidate> {
        val current = buildList {
            if (node.visibleToUser) {
                runCatching {
                    buildTextCandidate(
                        node = node,
                        depth = depth,
                        insideScrollableAncestor = insideScrollableAncestor,
                        insideClickableAncestor = insideClickableAncestor,
                    )
                }.onFailure { error ->
                    DiagnosticLogger.error("Skipping a text-title candidate because its node content could not be parsed safely.", error)
                }.getOrNull()?.let(::add)
            }
        }

        val nextInsideScrollable = insideScrollableAncestor || node.scrollable
        val nextInsideClickable = insideClickableAncestor || node.clickable || node.supportsClickAction
        return current + node.children.flatMap { child ->
            collectVisibleTextCandidates(
                node = child,
                depth = depth + 1,
                insideScrollableAncestor = nextInsideScrollable,
                insideClickableAncestor = nextInsideClickable,
            )
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

    private fun buildTextCandidate(
        node: AccessibilityNodeSnapshot,
        depth: Int,
        insideScrollableAncestor: Boolean,
        insideClickableAncestor: Boolean,
    ): TextTitleCandidate? {
        val rawText = sequenceOf(
            node.text?.trim(),
            node.contentDescription?.trim()?.takeIf { !node.clickable && !node.supportsClickAction },
        )
            .filterNotNull()
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val title = rawText.replace("\\s+".toRegex(), " ").trim()
        if (title.length < 3 || title.length > 80) return null

        val wordCount = title.split(Regex("\\s+")).size
        if (wordCount > 8) return null

        val bounds = parseBounds(node.bounds) ?: return null
        val viewIdSegment = node.viewIdResourceName
            ?.substringAfterLast('/')
            ?.lowercase(Locale.US)
            .orEmpty()

        var score = 0
        if (!node.clickable && !node.supportsClickAction) score += 35
        if (!insideClickableAncestor) score += 20
        score += when {
            insideScrollableAncestor -> -150
            else -> 110
        }
        score += when {
            bounds.top <= 200 -> 80
            bounds.top <= 400 -> 35
            bounds.top <= 800 -> 10
            else -> -30
        }
        score += (bounds.height / 2).coerceIn(0, 40)
        score += (40 - depth * 8).coerceAtLeast(0)
        score += when {
            wordCount in 1..4 -> 20
            wordCount <= 6 -> 5
            else -> -20
        }
        if (strongTitleIdMarkers.any { marker -> marker in viewIdSegment }) {
            score += 180
        } else if ("title" in viewIdSegment && !insideScrollableAncestor) {
            score += 40
        }
        if (title.contains(':') || title.endsWith('.')) {
            score -= 20
        }

        return TextTitleCandidate(title = title, score = score)
    }

    private fun tokenizeIdentifier(value: String): List<String> {
        return value
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    private fun parseBounds(bounds: String): ScreenBounds? {
        val match = boundsRegex.matchEntire(bounds) ?: return null
        val top = match.groupValues[2].toInt()
        val bottom = match.groupValues[4].toInt()
        return ScreenBounds(
            top = top,
            height = (bottom - top).coerceAtLeast(0),
        )
    }

    private data class ScreenBounds(
        val top: Int,
        val height: Int,
    )

    private data class ResourceTitleCandidate(
        val title: String,
        val score: Int,
    )

    private data class TextTitleCandidate(
        val title: String,
        val score: Int,
    )
}
