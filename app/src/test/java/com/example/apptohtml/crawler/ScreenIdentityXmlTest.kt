package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The screen's own identity now lives in its XML: name, element set and traits.
 *
 * These pins are about the *round trip*, not the bytes. What an operator writes into a file has to
 * come back as the same identity the crawler would compare against, or settling a screen by hand is
 * theatre. The one byte-level assertion here is determinism, which a diff-clean rewrite depends on.
 */
class ScreenIdentityXmlTest {

    @Test
    fun `a whole identity survives write and read`() {
        val identity = fullIdentity()

        val parsed = roundTrip(identity)

        assertNotNull("The file must still be readable.", parsed)
        assertEquals(
            "Root class is part of the identity and must survive.",
            identity.rootClassName,
            parsed!!.rootClassName,
        )
        assertEquals(
            "The element set must survive, back-affordance flag included.",
            identity.elements,
            parsed.elements,
        )
        assertEquals(
            "Every trait must survive — this is what an operator settles a screen with.",
            identity.traits,
            parsed.traits,
        )
        assertEquals("com_example_target", parsed.name?.packageName)
        assertEquals("cart", parsed.name?.screenName)
        assertEquals(listOf("two items"), parsed.name?.titleDisambiguators)
    }

    @Test
    fun `a back affordance survives as a back affordance`() {
        val parsed = roundTrip(fullIdentity())!!

        val back = parsed.elements.filter { it.isBackAffordance }
        assertEquals("Exactly the one flagged element comes back flagged.", 1, back.size)
        assertEquals("Navigate up", back.single().fingerprint.label)
    }

    @Test
    fun `an identity with no traits round-trips to no traits`() {
        val identity = fullIdentity(traits = emptyList())

        val parsed = roundTrip(identity)!!

        assertTrue(
            "A capture proposes no traits; it must not come back asserting something.",
            parsed.traits.isEmpty(),
        )
        assertEquals(identity.elements, parsed.elements)
    }

    @Test
    fun `the same identity always serializes to the same bytes`() {
        val elements = fullIdentity().elements
        val forward = fullIdentity(elements = LinkedHashSet(elements.sortedBy { it.encoded }))
        val reversed =
            fullIdentity(elements = LinkedHashSet(elements.sortedByDescending { it.encoded }))

        assertEquals(
            "Element order in the set must not reach the file, or every rewrite churns the diff.",
            serialize(forward),
            serialize(reversed),
        )
    }

    @Test
    fun `the screen identity and a route step read their elements through the same code`() {
        val element = pressable("Checkout", "com.example.target:id/checkout")
        val identity = fullIdentity(elements = setOf(element), traits = emptyList())
        val stepIdentity = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = setOf(element),
        )

        val file = write(identity, routeStepIdentity = stepIdentity)
        try {
            val head = ScreenXmlReader.readFull(file)!!.head
            val fromScreen = head.screenIdentity.elements.single()
            val fromStep = head.route.steps.single().expectedReplayIdentity!!.elements.single()

            assertEquals(
                "Both sides decode to the same value because both go through ScreenIdentityXml. " +
                    "Mutating that shared reader turns this pin and the route-step pins red together.",
                fromScreen,
                fromStep,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a trait the type refuses is reported, not crashed past`() {
        // min-rows="0" would hold on any container with a child, which HasList refuses to be.
        val file = writeRaw(
            """
            <traits>
              <has-list container-resource-id="com.example.target:id/list" min-rows="0">
                <row-child resource-id="com.example.target:id/title" />
              </has-list>
            </traits>
            """.trimIndent()
        )
        try {
            assertNull(
                "An identity that cannot be built is an unreadable screen, never an exception " +
                    "escaping into the crawl.",
                ScreenXmlReader.readFull(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an unknown trait is reported, not ignored`() {
        val file = writeRaw("<traits>\n  <has-vibes />\n</traits>")
        try {
            assertNull(
                "Silently dropping an unknown trait would weaken the identity an operator wrote.",
                ScreenXmlReader.readFull(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a control trait without exactly one element is reported`() {
        val file = writeRaw("<traits>\n  <has-control />\n</traits>")
        try {
            assertNull(ScreenXmlReader.readFull(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a second traits block is refused, not silently ignored`() {
        // The natural mistake when following the tool's own "paste these into BOTH files" advice.
        // Keeping only the first block would hand back a weaker identity than was written.
        val file = writeRaw(
            """
            <traits>
              <has-control>
                <element label="Checkout" resource-id="com.example.target:id/checkout" class="android.widget.Button" list-item="false" checkable="false" editable="false" back="false" />
              </has-control>
            </traits>
            <traits>
              <has-control>
                <element label="Remove" resource-id="com.example.target:id/remove" class="android.widget.Button" list-item="false" checkable="false" editable="false" back="false" />
              </has-control>
            </traits>
            """.trimIndent()
        )
        try {
            assertNull(
                "Two blocks means two answers to one question; silently taking the first loses " +
                    "assertions the operator wrote.",
                ScreenXmlReader.readFull(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an edited trait is reported by what changed, not by counting`() {
        // Found while settling a real screen: editing a trait rather than adding one produced
        // "XML has 1, HTML has 1" — the a2h-c2b.6 shape, true and useless.
        val xmlSide = listOf(cartList(minRows = 3))
        val htmlSide = listOf(cartList(minRows = 1))

        val difference = CaptureIdentitySource.describeDifferenceForTest(xmlSide, htmlSide)

        assertTrue("must name the trait: $difference", difference.contains("has-list"))
        assertTrue("must give the XML's value: $difference", difference.contains("3+ rows"))
        assertTrue("must give the page's value: $difference", difference.contains("1+ rows"))
    }

    private fun cartList(minRows: Int) = HasList(
        containerResourceId = "$PACKAGE:id/cart_list",
        minRows = minRows,
        rowChildResourceIds = setOf("$PACKAGE:id/title"),
    )

    // --- fixtures -------------------------------------------------------------------------------

    private fun fullIdentity(
        elements: Set<ScreenElementIdentity> = defaultElements(),
        traits: List<Trait> = defaultTraits(),
    ): ScreenIdentity = ScreenIdentity(
        packageName = PACKAGE,
        rootClassName = ROOT_CLASS,
        elements = elements,
        traits = traits,
    ).withName(
        ScreenNameIdentity(
            packageName = "com_example_target",
            screenName = "cart",
            titleDisambiguators = listOf("two items"),
            confidence = ScreenDedupConfidence.STRONG,
        )
    )

    private fun defaultElements(): Set<ScreenElementIdentity> = setOf(
        pressable("Checkout", "$PACKAGE:id/checkout"),
        pressable("Remove", "$PACKAGE:id/remove", isListItem = true),
        pressable("Navigate up", "$PACKAGE:id/back", isBack = true),
    )

    private fun defaultTraits(): List<Trait> = listOf(
        HasList(
            containerResourceId = "$PACKAGE:id/cart_list",
            minRows = 2,
            rowChildResourceIds = setOf("$PACKAGE:id/title", "$PACKAGE:id/price"),
        ),
        HasControl(pressable("Checkout", "$PACKAGE:id/checkout")),
        LacksControl(pressable("Sign in", "$PACKAGE:id/sign_in")),
    )

    private fun pressable(
        label: String,
        resourceId: String,
        isListItem: Boolean = false,
        isBack: Boolean = false,
    ) = ScreenElementIdentity(
        fingerprint = ElementFingerprint(
            resourceId = resourceId,
            label = label,
            className = "android.widget.Button",
            isListItem = isListItem,
            checkable = false,
            editable = false,
        ),
        isBackAffordance = isBack,
    )

    private fun crawlState(
        identity: ScreenIdentity,
        routeStepIdentity: ScreenIdentity? = null,
    ) = ScreenCrawlState(
        screenId = "screen_00001",
        depth = 1,
        expansionStatus = ScreenExpansionStatus.IN_PROGRESS,
        isRoot = false,
        screenIdentity = identity,
        parent = ParentEdgeRef("screen_00000", "Open", null),
        route = CrawlRoute(
            steps = listOf(
                CrawlRouteStep(
                    childIndexPath = listOf(0),
                    bounds = "[0,0][100,100]",
                    resourceId = "$PACKAGE:id/open",
                    className = "android.widget.Button",
                    label = "Open",
                    checkable = false,
                    checked = false,
                    editable = false,
                    firstSeenStep = 0,
                    expectedReplayIdentity = routeStepIdentity,
                )
            )
        ),
        runLevel = null,
        edgesByElement = emptyMap(),
    )

    private fun serialize(
        identity: ScreenIdentity,
        routeStepIdentity: ScreenIdentity? = null,
    ): String = AccessibilityXmlSerializer.serialize(
        ScreenSnapshot(
            screenName = "Cart",
            packageName = PACKAGE,
            elements = emptyList(),
            xmlDump = "",
        ),
        crawlState(identity, routeStepIdentity),
    )

    private fun write(identity: ScreenIdentity, routeStepIdentity: ScreenIdentity? = null): File {
        val file = File.createTempFile("screen_identity", ".xml")
        file.writeText(serialize(identity, routeStepIdentity))
        return file
    }

    private fun roundTrip(identity: ScreenIdentity): ScreenIdentity? {
        val file = write(identity)
        return try {
            ScreenXmlReader.readFull(file)?.head?.screenIdentity
        } finally {
            file.delete()
        }
    }

    /**
     * A screen file whose `<screen-identity>` carries [traitsBlock] verbatim, for refusal cases.
     *
     * Splices raw text in rather than building the trait, because these are shapes the trait types
     * refuse to be built as — the whole point is that they can only arrive from a file.
     */
    private fun writeRaw(traitsBlock: String): File {
        val xml = serialize(fullIdentity(traits = emptyList()))
        val closing = Regex("""(?m)^([ \t]*)</screen-identity>$""").find(xml)
        checkNotNull(closing) { "fixture needs an expanded <screen-identity> to splice into:\n$xml" }
        val indent = closing.groupValues[1]
        val indented = traitsBlock.lines().joinToString("\n") { "$indent  $it" }
        val patched = xml.replaceRange(
            closing.range.first,
            closing.range.first,
            "$indented\n",
        )
        val file = File.createTempFile("screen_identity_bad", ".xml")
        file.writeText(patched)
        return file
    }

    private companion object {
        const val PACKAGE = "com.example.target"
        const val ROOT_CLASS = "android.widget.FrameLayout"
    }
}
