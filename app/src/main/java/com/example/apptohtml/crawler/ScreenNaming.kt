package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import java.text.Normalizer
import java.util.Locale

object ScreenNaming {
    private const val minStrongTextTitleScore = 160
    private const val minIdentityHintScore = 120
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
    private val weakChromeTitles = setOf(
        "navigate up",
        "more options",
        "recommended",
        "all services",
        "back",
        "up",
    )
    private val weakCallToActionTitles = setOf(
        "sign in",
        "sign in to continue",
        "continue",
        "continue with google",
        "continue with email",
    )

    fun deriveScreenName(
        eventClassName: String?,
        selectedApp: SelectedAppRef,
        root: AccessibilityNodeSnapshot? = null,
    ): String {
        return analyzeScreenName(
            eventClassName = eventClassName,
            selectedApp = selectedApp,
            root = root,
        ).chosenName
    }

    fun dedupFingerprint(
        screenName: String,
        packageName: String? = null,
        root: AccessibilityNodeSnapshot? = null,
    ): String {
        return buildScreenIdentity(
            screenName = screenName,
            packageName = packageName,
            root = root,
        ).fingerprint
    }

    internal fun buildScreenIdentity(
        screenName: String,
        packageName: String?,
        root: AccessibilityNodeSnapshot? = null,
    ): ScreenIdentity {
        val normalizedTitle = normalizeIdentityToken(screenName).ifBlank { "unnamed" }
        val identityHints = root?.let(::collectIdentityHints).orEmpty()
            .map(::normalizeIdentityToken)
            .filter { hint -> hint.isNotBlank() && hint != normalizedTitle }
            .distinct()
            .take(2)
        val normalizedPackage = normalizeIdentityToken(packageName).ifBlank { "unknown" }
        val confidence = when {
            isWeakDedupTitle(screenName) -> ScreenDedupConfidence.WEAK
            identityHints.isNotEmpty() -> ScreenDedupConfidence.STRONG
            packageName.isNullOrBlank() -> ScreenDedupConfidence.WEAK
            else -> ScreenDedupConfidence.STRONG
        }

        return ScreenIdentity(
            fingerprint = buildString {
                append("v2:pkg:")
                append(normalizedPackage)
                append(":title:")
                append(normalizedTitle)
                append(":hint:")
                append(identityHints.ifEmpty { listOf("none") }.joinToString("|"))
            },
            confidence = confidence,
            identityHints = identityHints,
        )
    }

    internal fun analyzeScreenName(
        eventClassName: String?,
        selectedApp: SelectedAppRef,
        root: AccessibilityNodeSnapshot? = null,
    ): ScreenNameDebugInfo {
        val eventName = eventClassName
            ?.takeIf(::isUsefulWindowClassName)
            ?.substringAfterLast('.')
            ?.substringAfterLast('$')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (!eventName.isNullOrBlank()) {
            return debugInfo(
                chosenName = eventName,
                chosenStrategy = "event_class",
                chosenScore = null,
                eventClassName = eventClassName,
                eventClassCandidate = eventName,
                textCandidates = emptyList(),
                resourceIdCandidate = null,
                root = root,
                packageName = root?.packageName ?: selectedApp.packageName,
            )
        }

        val textCandidates = root?.let { currentRoot ->
            runCatching { deriveNameFromVisibleText(currentRoot) }
                .onFailure { error ->
                    DiagnosticLogger.error("Visible-text screen naming failed; falling back to other naming strategies.", error)
                }
                .getOrElse { emptyList() }
        }.orEmpty()
        val resourceCandidate = root?.let { currentRoot ->
            runCatching { deriveNameFromVisibleResourceIds(currentRoot, selectedApp) }
                .onFailure { error ->
                    DiagnosticLogger.error("Resource-id screen naming failed; falling back to launcher/app naming.", error)
                }
                .getOrNull()
        }
        val strongestTextCandidate = textCandidates.maxByOrNull { it.score }

        when {
            strongestTextCandidate != null && (
                resourceCandidate == null ||
                    strongestTextCandidate.score >= minStrongTextTitleScore ||
                    strongestTextCandidate.score > resourceCandidate.score
                ) -> {
                return debugInfo(
                    chosenName = strongestTextCandidate.title,
                    chosenStrategy = "visible_text",
                    chosenScore = strongestTextCandidate.score,
                    eventClassName = eventClassName,
                    eventClassCandidate = null,
                    textCandidates = textCandidates,
                    resourceIdCandidate = resourceCandidate,
                    root = root,
                    packageName = root?.packageName ?: selectedApp.packageName,
                )
            }

            resourceCandidate != null -> {
                return debugInfo(
                    chosenName = resourceCandidate.title,
                    chosenStrategy = "resource_id",
                    chosenScore = resourceCandidate.score,
                    eventClassName = eventClassName,
                    eventClassCandidate = null,
                    textCandidates = textCandidates,
                    resourceIdCandidate = resourceCandidate,
                    root = root,
                    packageName = root?.packageName ?: selectedApp.packageName,
                )
            }
        }

        val launcherName = selectedApp.launcherActivity
            .substringAfterLast('.')
            .substringAfterLast('$')
            .trim()
        if (launcherName.isNotBlank()) {
            return debugInfo(
                chosenName = launcherName,
                chosenStrategy = "launcher_activity",
                chosenScore = null,
                eventClassName = eventClassName,
                eventClassCandidate = null,
                textCandidates = textCandidates,
                resourceIdCandidate = resourceCandidate,
                root = root,
                packageName = root?.packageName ?: selectedApp.packageName,
            )
        }

        return debugInfo(
            chosenName = selectedApp.appName.ifBlank { "CapturedScreen" },
            chosenStrategy = "app_name",
            chosenScore = null,
            eventClassName = eventClassName,
            eventClassCandidate = null,
            textCandidates = textCandidates,
            resourceIdCandidate = resourceCandidate,
            root = root,
            packageName = root?.packageName ?: selectedApp.packageName,
        )
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

    private fun debugInfo(
        chosenName: String,
        chosenStrategy: String,
        chosenScore: Int?,
        eventClassName: String?,
        eventClassCandidate: String?,
        textCandidates: List<TextTitleCandidate>,
        resourceIdCandidate: ResourceTitleCandidate?,
        root: AccessibilityNodeSnapshot?,
        packageName: String?,
    ): ScreenNameDebugInfo {
        val identity = buildScreenIdentity(
            screenName = chosenName,
            packageName = packageName,
            root = root,
        )
        return ScreenNameDebugInfo(
            chosenName = chosenName,
            chosenStrategy = chosenStrategy,
            chosenScore = chosenScore,
            chosenTitleIsWeak = isWeakDedupTitle(chosenName),
            eventClassName = eventClassName,
            eventClassCandidate = eventClassCandidate,
            textCandidates = textCandidates
                .sortedByDescending { it.score }
                .take(5)
                .map { candidate ->
                    ScreenNameCandidate(
                        title = candidate.title,
                        score = candidate.score,
                    )
                },
            resourceIdCandidate = resourceIdCandidate?.let { candidate ->
                ScreenNameCandidate(
                    title = candidate.title,
                    score = candidate.score,
                )
            },
            identityHints = identity.identityHints,
            dedupFingerprint = identity.fingerprint,
            dedupConfidence = identity.confidence,
        )
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

    private fun deriveNameFromVisibleText(root: AccessibilityNodeSnapshot): List<TextTitleCandidate> {
        return collectVisibleTextCandidates(
            node = root,
            depth = 0,
            insideScrollableAncestor = false,
            insideClickableAncestor = false,
        )
    }

    private fun collectIdentityHints(root: AccessibilityNodeSnapshot): List<String> {
        val textHints = collectVisibleTextCandidates(
            node = root,
            depth = 0,
            insideScrollableAncestor = false,
            insideClickableAncestor = false,
        )
            .asSequence()
            .filter { candidate ->
                candidate.score >= minIdentityHintScore && !isWeakDedupTitle(candidate.title)
            }
            .map { candidate -> candidate.title }
        val resourceHints = collectVisibleResourceIdCandidates(root, emptySet())
            .asSequence()
            .filter { candidate -> !isWeakDedupTitle(candidate.title) }
            .map { candidate -> candidate.title }

        return (textHints + resourceHints)
            .map { hint -> hint.replace("\\s+".toRegex(), " ").trim() }
            .filter { hint -> hint.isNotBlank() }
            .distinct()
            .take(2)
            .toList()
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
        if (isWeakDedupTitle(title)) score -= 120

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
        if (isWeakDedupTitle(title)) {
            score -= 260
        }

        return TextTitleCandidate(title = title, score = score)
    }

    private fun isWeakDedupTitle(title: String): Boolean {
        val normalized = normalizeTitle(title)
        return normalized in weakChromeTitles ||
            normalized in weakCallToActionTitles ||
            normalized.startsWith("sign in to ")
    }

    private fun normalizeTitle(value: String): String {
        return value
            .trim()
            .replace("\\s+".toRegex(), " ")
            .lowercase(Locale.US)
    }

    private fun normalizeIdentityToken(value: String?): String {
        return value
            .orEmpty()
            .trim()
            .lowercase(Locale.US)
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')
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

internal data class ScreenIdentity(
    val fingerprint: String,
    val confidence: ScreenDedupConfidence,
    val identityHints: List<String>,
) {
    val canLinkToExisting: Boolean
        get() = confidence == ScreenDedupConfidence.STRONG
}

internal enum class ScreenDedupConfidence {
    STRONG,
    WEAK,
}

internal data class ScreenNameDebugInfo(
    val chosenName: String,
    val chosenStrategy: String,
    val chosenScore: Int?,
    val chosenTitleIsWeak: Boolean,
    val eventClassName: String?,
    val eventClassCandidate: String?,
    val textCandidates: List<ScreenNameCandidate>,
    val resourceIdCandidate: ScreenNameCandidate?,
    val identityHints: List<String>,
    val dedupFingerprint: String,
    val dedupConfidence: ScreenDedupConfidence,
)

internal data class ScreenNameCandidate(
    val title: String,
    val score: Int,
)
