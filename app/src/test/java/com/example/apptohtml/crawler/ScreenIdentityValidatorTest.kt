package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Does the identity hold, and does it hold only here?
 *
 * The headline is the **trait** answer; the element-set answer travels beside it. A test here that
 * asserted one through the other would defeat the separation, so each is asserted on its own.
 *
 * The differential test is the load-bearing one: it demands the validator's three answers equal the
 * production calls on the same inputs, which is what stops a second definition of identity growing
 * inside the tool.
 */
class ScreenIdentityValidatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // --- the headline ---------------------------------------------------------------------------

    @Test
    fun `a settled identity that holds uniquely HOLDS`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val observed = listScreen(rowCount = 3)

        val report = validate(target, observed, knownScreens = listOf(target))

        assertEquals(ScreenIdentityValidator.Outcome.HOLDS, report.outcome)
        assertEquals(TraitVerdict.HOLDS, report.traitVerdict)
    }

    @Test
    fun `a fresh capture with no traits is UNSETTLED`() {
        val target = capture("cart", traits = emptyList())
        val observed = listScreen(rowCount = 3)

        val report = validate(target, observed, knownScreens = listOf(target))

        assertEquals(
            "Every capture starts here: it proposes an element set but asserts nothing.",
            ScreenIdentityValidator.Outcome.UNSETTLED,
            report.outcome,
        )
    }

    @Test
    fun `an identity of negations alone is UNSETTLED`() {
        val target = capture(
            "cart",
            traits = listOf(LacksControl(control("Sign in", "$PACKAGE:id/sign_in"))),
        )

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target))

        assertEquals(ScreenIdentityValidator.Outcome.UNSETTLED, report.outcome)
    }

    @Test
    fun `an identity whose traits fail DOES_NOT_HOLD`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 9)))

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target))

        assertEquals(ScreenIdentityValidator.Outcome.DOES_NOT_HOLD, report.outcome)
    }

    @Test
    fun `a differently-named screen with the same traits is not a collision`() {
        // The observed screen is named as ITSELF. A sibling called something else is a different
        // screen, so it is not a candidate however similar its traits — that is the identity model,
        // and naming the observation after the target used to force the opposite answer.
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val sibling = capture("wishlist", traits = listOf(cartList(minRows = 2)))

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target, sibling))

        assertEquals(ScreenIdentityValidator.Outcome.HOLDS, report.outcome)
        assertEquals(
            "only the target, because the sibling is a different screen",
            "cart",
            (report.knownScreenMatch as ScreenTraitMatch.One).screenId,
        )
    }

    @Test
    fun `an identity that also holds on a sibling screen is NOT_UNIQUE and names every match`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val sibling = capture("wishlist", traits = listOf(cartList(minRows = 2)), screenName = "cart")

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target, sibling))

        assertEquals(
            "Two screens holding on one tree means the identity labels rather than identifies.",
            ScreenIdentityValidator.Outcome.NOT_UNIQUE,
            report.outcome,
        )
        val match = report.knownScreenMatch as ScreenTraitMatch.Ambiguous
        assertEquals(
            "Every colliding screen is listed; picking a winner is the operator's decision.",
            listOf("cart", "wishlist"),
            match.screenIds,
        )
    }

    @Test
    fun `tightening a trait turns NOT_UNIQUE into HOLDS`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 3)))
        val sibling = capture("wishlist", traits = listOf(cartList(minRows = 9)), screenName = "cart")

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target, sibling))

        assertEquals(ScreenIdentityValidator.Outcome.HOLDS, report.outcome)
    }

    // --- the element set is reported beside, never blended ---------------------------------------

    @Test
    fun `traits can hold while the element set differs`() {
        val target = capture(
            "cart",
            traits = listOf(cartList(minRows = 2)),
            elements = setOf(control("Gone", "$PACKAGE:id/gone")),
        )

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target))

        assertEquals(
            "The headline is the trait answer and must not be dragged down by element churn.",
            ScreenIdentityValidator.Outcome.HOLDS,
            report.outcome,
        )
        assertTrue(
            "…and the element-set answer must still say so, in full.",
            !report.elementSet.matched,
        )
        assertEquals(
            setOf("Gone"),
            report.elementSet.missing.map { it.fingerprint.label }.toSet(),
        )
    }

    @Test
    fun `a screen that gained one row reports it as extra, not as missing`() {
        // The Settings incident shape: the screen is the same, one row appeared.
        val beforeTree = listScreen(rowCount = 2, extraControl = null)
        val target = capture(
            "settings",
            traits = emptyList(),
            elements = ScreenIdentity.fromRoot(beforeTree).elements,
        )
        val afterTree = listScreen(rowCount = 2, extraControl = "Storage")

        val report = validate(target, afterTree, knownScreens = listOf(target))

        assertEquals(ScreenIdentityMatchReason.ELEMENT_SET_DIFFERS, report.elementSet.reason)
        assertEquals("nothing was lost", emptySet<String>(), report.elementSet.missing)
        assertEquals(
            "exactly the new row is extra",
            setOf("storage"),
            report.elementSet.extra.map { it.fingerprint.label }.toSet(),
        )
    }

    @Test
    fun `the same elements under a different root class are not the same screen`() {
        val tree = listScreen(rowCount = 2)
        val target = capture(
            "cart",
            traits = emptyList(),
            elements = ScreenIdentity.fromRoot(tree).elements,
            rootClassName = "android.widget.LinearLayout",
        )

        val report = validate(target, tree, knownScreens = listOf(target))

        assertEquals(
            "Identical pressables in a different container are a different screen. A validator " +
                "that ignored the root class would report a clean match on one.",
            ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH,
            report.elementSet.reason,
        )
        assertTrue(!report.elementSet.matched)
    }

    @Test
    fun `the root flag decides whether back affordances count`() {
        val tree = listScreen(rowCount = 2)
        val withBack = ScreenIdentity.fromRoot(tree).elements +
            ScreenElementIdentity(
                fingerprint = ElementFingerprint(
                    resourceId = "$PACKAGE:id/back",
                    label = "navigate up",
                    className = "android.widget.ImageButton",
                    isListItem = false,
                    checkable = false,
                    editable = false,
                ),
                isBackAffordance = true,
            )
        val target = capture("cart", traits = emptyList(), elements = withBack)

        val asRoot = validate(target, tree, listOf(target), isRootScreen = true)
        val asChild = validate(target, tree, listOf(target), isRootScreen = false)

        assertTrue("the root's identity carries no back affordance, so it matches", asRoot.elementSet.matched)
        assertTrue("a child's does, so the missing one shows", !asChild.elementSet.matched)
    }

    // --- per-element re-clickability -------------------------------------------------------------

    @Test
    fun `each element is reported resolved, ambiguous or unresolved`() {
        val tree = listScreen(rowCount = 2, extraControl = "Checkout")
        val target = capture(
            "cart",
            traits = emptyList(),
            elements = setOf(
                control("checkout", "$PACKAGE:id/checkout"),
                control("vanished", "$PACKAGE:id/vanished"),
            ),
        )

        val report = validate(target, tree, knownScreens = listOf(target))
        val byLabel = report.elements.associateBy { it.element.fingerprint.label }

        assertEquals(
            ScreenIdentityValidator.Resolution.RESOLVED,
            byLabel.getValue("checkout").resolution,
        )
        assertEquals(
            ScreenIdentityValidator.Resolution.UNRESOLVED,
            byLabel.getValue("vanished").resolution,
        )
    }

    @Test
    fun `a disabled control cannot be re-clicked, so it is unresolved`() {
        val tree = listScreen(rowCount = 2, extraControl = "Checkout", extraControlEnabled = false)
        val target = capture(
            "cart",
            traits = emptyList(),
            elements = setOf(control("checkout", "$PACKAGE:id/checkout")),
        )

        val report = validate(target, tree, knownScreens = listOf(target))

        assertEquals(
            "The control is on screen but greyed out. Reporting it as re-clickable would send an " +
                "operator to paste back an element the crawler can never press.",
            ScreenIdentityValidator.Resolution.UNRESOLVED,
            report.elements.single().resolution,
        )
    }

    @Test
    fun `an element can be unresolved while the identity still holds`() {
        val target = capture(
            "cart",
            traits = listOf(cartList(minRows = 2)),
            elements = setOf(control("vanished", "$PACKAGE:id/vanished")),
        )

        val report = validate(target, listScreen(rowCount = 3), knownScreens = listOf(target))

        assertEquals(ScreenIdentityValidator.Outcome.HOLDS, report.outcome)
        assertEquals(
            "Re-clickability is a separate question from identity and must not move the headline.",
            ScreenIdentityValidator.Resolution.UNRESOLVED,
            report.elements.single().resolution,
        )
    }

    @Test
    fun `an unresolved element brings paste-ready replacements`() {
        val target = capture(
            "cart",
            traits = emptyList(),
            elements = setOf(control("vanished", "$PACKAGE:id/vanished")),
        )

        val report = validate(target, listScreen(rowCount = 2, extraControl = "Checkout"), listOf(target))

        assertTrue(
            "What the operator pastes is the observed screen's own elements.",
            report.suggestedElements.any { it.fingerprint.label == "checkout" },
        )
    }

    // --- diagnostics never move the headline ------------------------------------------------------

    @Test
    fun `collisions are reported without changing the outcome`() {
        val tree = listScreen(rowCount = 3)
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))

        val report = validate(target, tree, knownScreens = listOf(target))

        assertTrue(
            "Three identically-shaped rows collide on one fingerprint.",
            report.observedDiagnostics.collisions.isNotEmpty(),
        )
        assertEquals(
            "A hard-to-describe screen is still a screen whose identity holds.",
            ScreenIdentityValidator.Outcome.HOLDS,
            report.outcome,
        )
    }

    @Test
    fun `traits holding while the target itself does not match is DOES_NOT_HOLD, not NOT_UNIQUE`() {
        // The traits hold, but the target's package differs from the observed screen's, so
        // matchKnownScreens excludes it and returns None. That is the identity failing to describe
        // this screen, not two screens colliding — reporting NOT_UNIQUE announced a collision that
        // did not exist, and contradicted the report's own "no known screen holds here" line.
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val foreign = listScreenOfPackage("com.other.app", rowCount = 3)

        val report = validate(target, foreign, knownScreens = listOf(target))

        assertEquals(TraitVerdict.HOLDS, report.traitVerdict)
        assertTrue(report.knownScreenMatch is ScreenTraitMatch.None)
        assertEquals(
            ScreenIdentityValidator.Outcome.DOES_NOT_HOLD,
            report.outcome,
        )
    }

    // --- the differential: production code decides -------------------------------------------------

    @Test
    fun `every answer equals the production call on the same inputs`() {
        val cases = listOf(
            capture("cart", traits = listOf(cartList(minRows = 2))) to listScreen(rowCount = 3),
            capture("cart", traits = listOf(cartList(minRows = 9))) to listScreen(rowCount = 3),
            capture("cart", traits = emptyList()) to listScreen(rowCount = 1),
            capture("cart", traits = listOf(HasControl(control("checkout", "$PACKAGE:id/checkout"))))
                to listScreen(rowCount = 2, extraControl = "Checkout"),
            capture("cart", traits = listOf(HasControl(control("absent", "$PACKAGE:id/absent"))))
                to listScreen(rowCount = 2),
        )

        cases.forEachIndexed { index, (target, observed) ->
            val report = validate(target, observed, knownScreens = listOf(target))
            val context = TraitEvaluationContext.of(observed, target.identity.name)

            assertEquals(
                "case $index: the trait verdict must be TraitEvaluator's, not the tool's",
                TraitEvaluator.verdict(target.identity, context),
                report.traitVerdict,
            )
            assertEquals(
                "case $index: uniqueness must be matchKnownScreens'",
                TraitEvaluator.matchKnownScreens(context, mapOf(target.screenId to target.identity)),
                report.knownScreenMatch,
            )
            assertEquals(
                "case $index: the element set must be SameScreenPolicy's",
                SameScreenPolicy(BackAffordanceCounting.forScreen(false)).compare(
                    target.identity,
                    ScreenIdentity.fromRoot(observed)
                        .let { id -> target.identity.name?.let(id::withName) ?: id },
                ),
                report.elementSet,
            )
        }
    }

    @Test
    fun `two runs on the same inputs agree, whatever order the known screens arrive in`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val sibling = capture("wishlist", traits = listOf(cartList(minRows = 2)), screenName = "cart")
        val tree = listScreen(rowCount = 3)

        val forward = validate(target, tree, knownScreens = listOf(target, sibling))
        val reversed = validate(target, tree, knownScreens = listOf(sibling, target))

        assertEquals(
            "Presentation order must not reach the report, or it is not reproducible.",
            forward,
            reversed,
        )
    }

    @Test
    fun `the report distinguishes a target that holds from one that merely is not alone`() {
        val target = capture("cart", traits = listOf(cartList(minRows = 2)))
        val alone = validate(target, listScreen(rowCount = 3), listOf(target))
        val crowded = validate(
            target,
            listScreen(rowCount = 3),
            listOf(target, capture("wishlist", traits = listOf(cartList(minRows = 1)), screenName = "cart")),
        )

        assertNotEquals(alone.outcome, crowded.outcome)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * [observedName] defaults to the target's, which is what validating a screen against its own
     * capture means. Tests that care about uniqueness pass the observed screen's real name, because
     * naming the observation after the target is exactly the bug these fixtures once hid.
     */
    private fun validate(
        target: LoadedCapture,
        observed: AccessibilityNodeSnapshot,
        knownScreens: List<LoadedCapture>,
        isRootScreen: Boolean = false,
        observedName: ScreenNameIdentity? = target.identity.name,
    ) = ScreenIdentityValidator.validate(target, observed, observedName, knownScreens, isRootScreen)

    /** The same shape as [listScreen] but attributed to another package. */
    private fun listScreenOfPackage(
        packageName: String,
        rowCount: Int,
    ): AccessibilityNodeSnapshot {
        fun reattribute(n: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot =
            n.copy(packageName = packageName, children = n.children.map(::reattribute))
        return reattribute(listScreen(rowCount))
    }

    private fun cartList(minRows: Int) = HasList(
        containerResourceId = "$PACKAGE:id/list",
        minRows = minRows,
        rowChildResourceIds = setOf("$PACKAGE:id/title"),
    )

    private fun control(label: String, resourceId: String) = ScreenElementIdentity(
        fingerprint = ElementFingerprint(
            resourceId = resourceId,
            label = label,
            className = "android.widget.Button",
            isListItem = false,
            checkable = false,
            editable = false,
        ),
        isBackAffordance = false,
    )

    /** A capture on disk, written by the production serializer so the reader path is exercised. */
    private fun capture(
        screenId: String,
        traits: List<Trait>,
        elements: Set<ScreenElementIdentity> = emptySet(),
        screenName: String = screenId,
        rootClassName: String = ROOT_CLASS,
    ): LoadedCapture {
        val identity = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = rootClassName,
            elements = elements,
            traits = traits,
        ).withName(
            ScreenNameIdentity(
                packageName = "com_example_target",
                screenName = screenName,
                titleDisambiguators = emptyList(),
                confidence = ScreenDedupConfidence.STRONG,
            )
        )
        val dir = tempFolder.newFolder()
        val snapshot = ScreenSnapshot(
            screenName = screenId,
            packageName = PACKAGE,
            elements = emptyList(),
            xmlDump = "",
            scrollStepCount = 1,
            stepSnapshots = listOf(
                ScrollCaptureStep(stepIndex = 0, root = listScreen(rowCount = 1), newElementCount = 0)
            ),
        )
        val state = ScreenCrawlState(
            screenId = screenId,
            depth = 1,
            expansionStatus = ScreenExpansionStatus.COMPLETE,
            isRoot = false,
            screenIdentity = identity,
            parent = null,
            route = CrawlRoute(),
            runLevel = null,
            edgesByElement = emptyMap(),
        )
        File(dir, "$screenId.xml")
            .writeText(AccessibilityXmlSerializer.serialize(snapshot, state), Charsets.UTF_8)
        File(dir, "$screenId.html")
            .writeText(HtmlRenderer.render(snapshot, emptyMap(), identity), Charsets.UTF_8)
        return CaptureIdentitySource.load(File(dir, "$screenId.xml")).getOrThrow()
    }

    /** A screen whose list holds [rowCount] rows, optionally with one extra top-level control. */
    private fun listScreen(
        rowCount: Int,
        extraControl: String? = null,
        extraControlEnabled: Boolean = true,
    ): AccessibilityNodeSnapshot {
        val rows = (0 until rowCount).map { index ->
            node(
                className = "android.widget.LinearLayout",
                resourceId = "$PACKAGE:id/row",
                bounds = "[0,${300 + index * 100}][1080,${300 + index * 100 + 90}]",
                clickable = true,
                children = listOf(
                    node(
                        className = "android.widget.TextView",
                        resourceId = "$PACKAGE:id/title",
                        text = "Row $index",
                        bounds = "[0,${300 + index * 100}][540,${300 + index * 100 + 90}]",
                    )
                ),
            )
        }
        val extras = listOfNotNull(
            extraControl?.let { label ->
                node(
                    className = "android.widget.Button",
                    resourceId = "$PACKAGE:id/${label.lowercase()}",
                    text = label,
                    bounds = "[0,100][1080,200]",
                    clickable = true,
                    enabled = extraControlEnabled,
                )
            }
        )
        return node(
            className = ROOT_CLASS,
            resourceId = "$PACKAGE:id/root",
            bounds = "[0,0][1080,2400]",
            children = extras + node(
                className = "androidx.recyclerview.widget.RecyclerView",
                resourceId = "$PACKAGE:id/list",
                bounds = "[0,300][1080,2000]",
                scrollable = true,
                children = rows,
            ),
        )
    }

    private fun node(
        className: String,
        resourceId: String,
        bounds: String,
        text: String? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ) = AccessibilityNodeSnapshot(
        className = className,
        packageName = PACKAGE,
        viewIdResourceName = resourceId,
        text = text,
        contentDescription = null,
        clickable = clickable,
        supportsClickAction = false,
        scrollable = scrollable,
        enabled = enabled,
        visibleToUser = true,
        bounds = bounds,
        children = children,
    )

    private companion object {
        const val PACKAGE = "com.example.target"
        const val ROOT_CLASS = "android.widget.FrameLayout"
    }
}
