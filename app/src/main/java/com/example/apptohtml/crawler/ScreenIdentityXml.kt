package com.example.apptohtml.crawler

import org.w3c.dom.Element

/**
 * The one on-disk shape of a [ScreenIdentity]'s content half, written and read here and nowhere
 * else.
 *
 * The same shape serves every place an identity is persisted — the route-step expectations
 * (`<expected-destination>`, `<expected-replay>`) and the screen's own `<screen-identity>`. They are
 * the same kind of value, so they get the same syntax and the same parser: an operator who learns
 * to read one can read the others, and a change to the shape cannot reach one writer without
 * reaching every reader.
 *
 * Element order is **canonical, not incidental**: elements serialize sorted by
 * [ScreenElementIdentity.encoded], so the same identity always produces the same bytes regardless of
 * the set's iteration order. Determinism here is what makes a rewritten file diff-clean and a
 * report reproducible.
 */
internal object ScreenIdentityXml {

    /**
     * Writes [identity]'s package, root class and element set as [tagName], indented at [depth].
     *
     * An identity with no elements is written self-closing rather than as an empty container, so a
     * screen that genuinely has no pressables round-trips to an empty set rather than to null.
     */
    fun append(
        builder: StringBuilder,
        tagName: String,
        identity: ScreenIdentity,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<").append(tagName)
        builder.append(""" package="${escapeXml(identity.packageName.orEmpty())}"""")
        builder.append(""" root-class="${escapeXml(identity.rootClassName)}"""")
        val elements = identity.elements.sortedBy { it.encoded }
        if (elements.isEmpty()) {
            builder.append(" />")
            builder.append('\n')
            return
        }
        builder.append(">")
        builder.append('\n')
        appendElements(builder, elements, depth + 1)
        builder.append(indent)
        builder.append("</").append(tagName).append(">")
        builder.append('\n')
    }

    /** The `<element …>` children alone, so a container that also carries other blocks can reuse them. */
    fun appendElements(
        builder: StringBuilder,
        elements: List<ScreenElementIdentity>,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        elements.forEach { element ->
            val fingerprint = element.fingerprint
            builder.append(indent)
            builder.append("<element")
            builder.append(""" label="${escapeXml(fingerprint.label)}"""")
            builder.append(""" resource-id="${escapeXml(fingerprint.resourceId.orEmpty())}"""")
            builder.append(""" class="${escapeXml(fingerprint.className.orEmpty())}"""")
            builder.append(""" list-item="${fingerprint.isListItem}"""")
            builder.append(""" checkable="${fingerprint.checkable}"""")
            builder.append(""" editable="${fingerprint.editable}"""")
            builder.append(""" back="${element.isBackAffordance}"""")
            builder.append(" />")
            builder.append('\n')
        }
    }

    /**
     * Writes [traits] as a `<traits>` block, omitted entirely when empty.
     *
     * An absent block and an empty one would mean the same thing — an identity asserting nothing —
     * so only one of them is ever written. Traits keep the order the identity holds them in; making
     * that order canonical is `a2h-c2b.10`, deliberately not decided here.
     */
    fun appendTraits(
        builder: StringBuilder,
        traits: List<Trait>,
        depth: Int,
    ) {
        if (traits.isEmpty()) return
        val indent = "  ".repeat(depth)
        builder.append(indent).append("<traits>").append('\n')
        traits.forEach { trait -> appendTrait(builder, trait, depth + 1) }
        builder.append(indent).append("</traits>").append('\n')
    }

    private fun appendTrait(builder: StringBuilder, trait: Trait, depth: Int) {
        val indent = "  ".repeat(depth)
        when (trait) {
            is HasList -> {
                builder.append(indent).append("<has-list")
                builder.append(""" container-resource-id="${escapeXml(trait.containerResourceId)}"""")
                builder.append(""" min-rows="${trait.minRows}"""")
                builder.append(">").append('\n')
                val childIndent = "  ".repeat(depth + 1)
                trait.rowChildResourceIds.forEach { id ->
                    builder.append(childIndent)
                        .append("""<row-child resource-id="${escapeXml(id)}" />""")
                        .append('\n')
                }
                builder.append(indent).append("</has-list>").append('\n')
            }
            // Both control traits carry an element, so they reuse the one element shape rather than
            // inventing attributes of their own.
            is HasControl -> appendControlTrait(builder, "has-control", trait.element, depth)
            is LacksControl -> appendControlTrait(builder, "lacks-control", trait.element, depth)
        }
    }

    private fun appendControlTrait(
        builder: StringBuilder,
        tagName: String,
        element: ScreenElementIdentity,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent).append("<").append(tagName).append(">").append('\n')
        appendElements(builder, listOf(element), depth + 1)
        builder.append(indent).append("</").append(tagName).append(">").append('\n')
    }

    /**
     * The name half of a `<screen-identity>` element, or null when it carries none.
     *
     * Here rather than at each call site because there were briefly two readers for it — one that
     * always attached a name and one that attached none when the attributes were blank — and two
     * byte-identical files read back as disagreeing. The element and trait halves already shared a
     * reader; this closes the same gap for the name.
     *
     * `confidence` is not persisted: it is a judgement about a live screen's naming signals, not a
     * property of the recorded name, so it is recomputed from the same inputs the crawler used.
     */
    fun parseName(element: Element): ScreenNameIdentity? {
        // Presence of the attributes decides, not whether they are blank. The writer always emits
        // both, so every real capture has a name half — and the crawler's own loader builds one
        // from blank attributes too. Treating blank as "no name" made the tool and the crawler read
        // the same bytes as two different identities: the crawler got a catalogued, STRONG name and
        // the tool got none, which is a disagreement about a file rather than about a screen.
        if (!element.hasAttribute("title") && !element.hasAttribute("name-package")) return null
        val title = element.getAttribute("title")
        val namePackage = element.getAttribute("name-package")
        return ScreenNameIdentity(
            packageName = namePackage,
            screenName = title,
            titleDisambiguators = (1..ScreenIdentityCodec.MAX_TITLE_DISAMBIGUATORS).mapNotNull { index ->
                element.getAttribute("title-disambiguator-$index").takeIf { it.isNotBlank() }
            },
            confidence = ScreenNaming.buildScreenNameIdentity(
                screenName = title,
                packageName = namePackage,
                root = null,
            ).confidence,
        )
    }

    /** Rebuilds the identity [element] carries. The inverse of [append]. */
    fun parse(element: Element): ScreenIdentity = ScreenIdentity(
        packageName = element.getAttribute("package").takeIf { it.isNotBlank() },
        rootClassName = element.getAttribute("root-class"),
        elements = parseElements(element),
        traits = parseTraits(element),
    )

    /**
     * The traits of [parent]'s `<traits>` block, or empty when it has none. The inverse of
     * [appendTraits].
     *
     * Throws [MalformedScreenIdentity] rather than guessing: a trait the reader cannot build is a
     * question for whoever wrote it. [HasList] refuses its own degenerate shapes at construction, so
     * a `min-rows="0"` or an empty schema surfaces here as a refusal — never as a trait that would
     * hold on every screen.
     */
    fun parseTraits(parent: Element): List<Trait> {
        // A second <traits> block is refused rather than ignored. Pasting one in is the natural
        // mistake when following the tool's own "paste these into BOTH files" advice, and silently
        // keeping only the first would hand back a weaker identity than the operator wrote — the
        // same failure as silently dropping an unknown trait.
        val blocks = parent.childElementsNamed("traits")
        if (blocks.size > 1) {
            throw MalformedScreenIdentity(
                "${blocks.size} <traits> blocks; a screen identity asserts one set. Merge them."
            )
        }
        val block = blocks.firstOrNull() ?: return emptyList()
        return block.childElements().map { child ->
            when (child.tagName) {
                "has-list" -> parseHasList(child)
                "has-control" -> HasControl(singleElementOf(child, "has-control"))
                "lacks-control" -> LacksControl(singleElementOf(child, "lacks-control"))
                else -> throw MalformedScreenIdentity(
                    "Unknown trait <${child.tagName}> in <traits>."
                )
            }
        }
    }

    private fun parseHasList(element: Element): HasList {
        val minRowsText = element.getAttribute("min-rows")
        val minRows = minRowsText.toIntOrNull()
            ?: throw MalformedScreenIdentity(
                "<has-list> needs an integer min-rows, was \"$minRowsText\"."
            )
        val rowChildResourceIds = element.childElementsNamed("row-child")
            .map { it.getAttribute("resource-id") }
        return try {
            HasList(
                containerResourceId = element.getAttribute("container-resource-id"),
                minRows = minRows,
                rowChildResourceIds = rowChildResourceIds.toSet(),
            )
        } catch (e: IllegalArgumentException) {
            // HasList owns which shapes are too weak to assert; this only names where one was found.
            throw MalformedScreenIdentity("<has-list> rejected: ${e.message}", e)
        }
    }

    private fun singleElementOf(trait: Element, tagName: String): ScreenElementIdentity {
        val elements = parseElements(trait)
        if (elements.size != 1) {
            throw MalformedScreenIdentity(
                "<$tagName> needs exactly one <element>, found ${elements.size}."
            )
        }
        return elements.single()
    }

    /** The `<element …>` children of [parent] as identities. The inverse of [appendElements]. */
    fun parseElements(parent: Element): Set<ScreenElementIdentity> =
        parent.childElementsNamed("element").map { child ->
            ScreenElementIdentity(
                fingerprint = ElementFingerprint(
                    resourceId = child.getAttribute("resource-id").takeIf { it.isNotBlank() },
                    label = child.getAttribute("label"),
                    className = child.getAttribute("class").takeIf { it.isNotBlank() },
                    isListItem = child.getAttribute("list-item") == "true",
                    checkable = child.getAttribute("checkable") == "true",
                    editable = child.getAttribute("editable") == "true",
                ),
                isBackAffordance = child.getAttribute("back") == "true",
            )
        }.toSet()
}
