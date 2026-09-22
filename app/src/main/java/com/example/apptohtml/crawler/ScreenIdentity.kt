package com.example.apptohtml.crawler

/**
 * One pressable element as it participates in a screen's identity.
 *
 * Wraps the unchanged [ElementFingerprint] atom and adds the classification that used to be
 * applied by *filtering at build time*: whether this element is the screen's back affordance.
 * Keeping it in the set — flagged rather than dropped — is what lets a single stored identity
 * serve both the comparisons that count back affordances and the ones that don't.
 */
data class ScreenElementIdentity(
    val fingerprint: ElementFingerprint,
    val isBackAffordance: Boolean,
) {
    /** Canonical serialized form of the element half; unchanged from the pre-structure encoding. */
    val encoded: String get() = fingerprint.encoded
}

/**
 * The name half of a screen's identity: what the crawler would *call* this screen.
 *
 * Derived from the screen-naming pass, and used only by dedup and graph linking. Replay ignores
 * it entirely. It is a distinct field rather than something blended into a score, so the two
 * signals can be weighted independently.
 */
data class ScreenNameIdentity(
    val packageName: String,
    val screenName: String,
    val titleDisambiguators: List<String>,
    val confidence: ScreenDedupConfidence,
) {
    val canLinkToExisting: Boolean
        get() = confidence == ScreenDedupConfidence.STRONG
}

/**
 * The one structured identity of a screen, produced at capture for every screen — root and child
 * alike — and rebuilt from a live root whenever the crawler needs to ask "am I on this screen?".
 *
 * Replaces the flat `rootClass::element||element||…` string. Because the parts stay addressable,
 * a comparison can report *which* elements are missing or extra rather than only "different", and
 * each call site can name the policy it wants instead of relying on which of four string builders
 * it happened to call.
 *
 * [name] is null for an identity built from a live root as a probe: such a screen has not been
 * through the naming pass and genuinely has no settled name. Every *captured* screen has one.
 *
 * [traits] is the third part: assertions this identity makes about the screen, checked against a
 * live tree by [TraitEvaluator] rather than compared with another identity. An identity with no
 * traits, or with negations alone, is *not settled yet* — never *matches everything* — and no
 * traits is what every identity carries until one is proposed or settled for it.
 */
data class ScreenIdentity(
    val packageName: String?,
    val rootClassName: String,
    val elements: Set<ScreenElementIdentity>,
    val name: ScreenNameIdentity? = null,
    val traits: List<Trait> = emptyList(),
) {
    /**
     * The controls that identify this screen: exactly the elements its [HasControl] traits assert.
     *
     * Derived rather than stored as a per-element flag, so "which controls identify this screen"
     * has one home, and no new field enters the [ScreenElementIdentity] equality that
     * [EntryRestorePolicy] compares directly. [LacksControl] elements are not identifying — they
     * are what the screen is not.
     */
    val identifyingElements: Set<ScreenElementIdentity>
        get() = traits.filterIsInstance<HasControl>().mapTo(linkedSetOf()) { it.element }

    /** The element set a comparison should look at, given whether it counts back affordances. */
    fun elementsFor(countBackAffordances: Boolean): Set<ScreenElementIdentity> {
        return if (countBackAffordances) {
            elements
        } else {
            elements.filterNot(ScreenElementIdentity::isBackAffordance).toSet()
        }
    }

    fun withName(name: ScreenNameIdentity): ScreenIdentity = copy(name = name)

    companion object {
        /**
         * The identity of nothing: no package, no root class, no elements, no traits.
         *
         * For the one case where a screen's file must still be written although its tree was never
         * captured. Asserts nothing present, so [TraitEvaluator] reads it as unsettled rather than
         * as a screen that matches everything.
         */
        val EMPTY = ScreenIdentity(
            packageName = null,
            rootClassName = "",
            elements = emptySet(),
        )

        /**
         * Builds the content half from a live root.
         *
         * Order matters and is deliberately preserved from the pre-structure builder: pressables
         * are deduplicated on [ElementFingerprint] **first**, so the first occurrence wins, and
         * only the survivor is then tested for back-ness. Testing before deduplicating would
         * change which element survives and silently alter identities.
         */
        fun fromRoot(root: AccessibilityNodeSnapshot): ScreenIdentity {
            val elements = AccessibilityTreeSnapshotter.collectPressableElements(root)
                .distinctBy(ElementFingerprint::of)
                .map { element ->
                    ScreenElementIdentity(
                        fingerprint = ElementFingerprint.of(element),
                        isBackAffordance = BackAffordanceTokens.looksLikeEntryBackAffordance(element),
                    )
                }
                .toSet()

            return ScreenIdentity(
                packageName = root.packageName,
                rootClassName = root.className.orEmpty(),
                elements = elements,
            )
        }
    }
}

/**
 * The element-level back-affordance test, in one place.
 *
 * Previously existed as two character-identical private copies in different files, including
 * the hard-coded top-of-screen band.
 *
 * Distinct from [EntryScreenBackAffordanceDetector], which answers a different question — *is
 * there a back button I can press?* — over live nodes with toolbar and ancestor context. This one
 * answers *is this element part of the screen's identity?* over a flattened [PressableElement]
 * that carries only its bounds. They are not merged.
 */
object BackAffordanceTokens {
    private const val TOP_BAND_PX = 300
    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

    fun looksLikeEntryBackAffordance(element: PressableElement): Boolean {
        val normalizedLabel = normalize(element.label)
        val normalizedResourceId = normalize(element.resourceId)
        val hasBackSignal = normalizedResourceId.contains("back") ||
            normalizedResourceId.contains("navigate up") ||
            normalizedResourceId.contains("navigateup") ||
            normalizedResourceId.contains("up button") ||
            normalizedResourceId.contains("upbutton") ||
            normalizedResourceId.contains("nav button") ||
            normalizedResourceId.contains("navbutton") ||
            normalizedLabel == "navigate up" ||
            normalizedLabel == "go back" ||
            normalizedLabel == "navigate back" ||
            normalizedLabel == "back" ||
            normalizedLabel == "up"
        if (!hasBackSignal) {
            return false
        }

        val top = BOUNDS_REGEX.matchEntire(element.bounds)?.groupValues?.get(2)?.toInt()
            ?: return false
        return top <= TOP_BAND_PX
    }

    fun normalize(value: String?): String {
        return value
            ?.substringAfterLast('/')
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }
}
