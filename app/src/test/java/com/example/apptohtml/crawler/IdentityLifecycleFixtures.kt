package com.example.apptohtml.crawler

/**
 * Synthetic trees for the `a2h-c2b.3` lifecycle pins: proposal, settlement and recognition.
 *
 * Every node carries a real `childIndexPath` derived from its position, because the scroll merge and
 * the proposer both read paths and a fixture with empty paths can pass vacuously (`a2h-c2b.12`, 4).
 */
internal object LifecycleTrees {
    const val PKG = "com.example.shop"
    const val CART_ITEMS = "$PKG:id/cart_items"
    const val ROW_ID = "$PKG:id/cart_item"
    const val TITLE = "$PKG:id/title"
    const val PRICE = "$PKG:id/price"
    const val QUANTITY = "$PKG:id/quantity"
    const val CHECKOUT = "$PKG:id/checkout"
    const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    const val LINEAR = "android.widget.LinearLayout"

    data class Item(val title: String, val price: String, val quantity: String)

    val CART_A = listOf(
        Item("Espresso beans", "12.50", "2"),
        Item("Milk frother", "34.00", "1"),
        Item("Paper filters", "3.20", "5"),
    )
    val CART_B = listOf(
        Item("Descaling kit", "9.99", "1"),
        Item("Tamper", "19.00", "1"),
    )

    /**
     * A cart: a list of rows carrying title, price and quantity, a Checkout button with an id, an
     * id-less "Help" button, and a "Navigate up" back affordance in the top band. The back affordance
     * carries a resource id on purpose, so only the back-affordance rule — not the "has no id" rule —
     * can keep it out of a proposal.
     */
    fun cart(
        items: List<Item> = CART_A,
        extraButtonIds: List<String> = emptyList(),
        packageName: String = PKG,
    ): AccessibilityNodeSnapshot {
        val children = mutableListOf<NodeSpec>()
        children += NodeSpec(
            className = "android.widget.ImageButton",
            resourceId = "$packageName:id/navigate_up",
            contentDescription = "Navigate up",
            clickable = true,
            bounds = "[0,60][140,200]",
        )
        children += NodeSpec(
            className = RECYCLER,
            resourceId = CART_ITEMS,
            scrollable = true,
            bounds = "[0,220][1080,2000]",
            children = items.mapIndexed { index, item ->
                val top = 220 + index * 150
                NodeSpec(
                    className = LINEAR,
                    resourceId = ROW_ID,
                    clickable = true,
                    bounds = "[0,$top][1080,${top + 150}]",
                    children = listOf(
                        NodeSpec("android.widget.TextView", TITLE, text = item.title),
                        NodeSpec("android.widget.TextView", PRICE, text = item.price),
                        NodeSpec("android.widget.TextView", QUANTITY, text = item.quantity),
                    ),
                )
            },
        )
        children += NodeSpec(
            className = "android.widget.Button",
            resourceId = CHECKOUT,
            text = "Checkout",
            clickable = true,
            bounds = "[0,2100][1080,2180]",
        )
        children += NodeSpec(
            className = "android.widget.Button",
            resourceId = null,
            text = "Help",
            clickable = true,
            bounds = "[0,2200][1080,2280]",
        )
        extraButtonIds.forEachIndexed { index, id ->
            children += NodeSpec(
                className = "android.widget.Button",
                resourceId = "$packageName:id/$id",
                text = id,
                clickable = true,
                bounds = "[0,${2300 + index * 10}][1080,${2380 + index * 10}]",
            )
        }
        return build(
            NodeSpec(
                className = "android.widget.FrameLayout",
                resourceId = null,
                bounds = "[0,0][1080,2400]",
                children = children,
            ),
            packageName = packageName,
        )
    }

    /** A screen of id-less buttons and no list at all — nothing stable to assert. */
    fun idless(labels: List<String> = listOf("About", "Legal", "Build number")): AccessibilityNodeSnapshot =
        build(
            NodeSpec(
                className = "android.widget.FrameLayout",
                resourceId = null,
                bounds = "[0,0][1080,2400]",
                children = labels.mapIndexed { index, label ->
                    NodeSpec(
                        className = LINEAR,
                        resourceId = null,
                        text = label,
                        clickable = true,
                        bounds = "[0,${300 + index * 150}][1080,${450 + index * 150}]",
                    )
                },
            ),
        )

    /** A tree with exactly the given buttons, each carrying an id. */
    fun buttons(vararg ids: String, packageName: String = PKG): AccessibilityNodeSnapshot = build(
        NodeSpec(
            className = "android.widget.FrameLayout",
            resourceId = null,
            bounds = "[0,0][1080,2400]",
            children = ids.mapIndexed { index, id ->
                NodeSpec(
                    className = "android.widget.Button",
                    resourceId = "$packageName:id/$id",
                    text = id,
                    clickable = true,
                    bounds = "[0,${300 + index * 100}][1080,${380 + index * 100}]",
                )
            },
        ),
        packageName = packageName,
    )

    /** Reverses every node's children, recursively — same content, opposite child order. */
    fun reversed(root: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot =
        reindex(reverseChildren(root), emptyList())

    data class NodeSpec(
        val className: String,
        val resourceId: String?,
        val text: String? = null,
        val contentDescription: String? = null,
        val clickable: Boolean = false,
        val scrollable: Boolean = false,
        val bounds: String = "[0,0][100,100]",
        val children: List<NodeSpec> = emptyList(),
    )

    fun build(spec: NodeSpec, packageName: String = PKG): AccessibilityNodeSnapshot =
        toNode(spec, packageName, emptyList())

    private fun toNode(spec: NodeSpec, packageName: String, path: List<Int>): AccessibilityNodeSnapshot =
        AccessibilityNodeSnapshot(
            className = spec.className,
            packageName = packageName,
            viewIdResourceName = spec.resourceId,
            text = spec.text,
            contentDescription = spec.contentDescription,
            clickable = spec.clickable,
            supportsClickAction = spec.clickable,
            scrollable = spec.scrollable,
            enabled = true,
            visibleToUser = true,
            bounds = spec.bounds,
            children = spec.children.mapIndexed { index, child -> toNode(child, packageName, path + index) },
            childIndexPath = path,
        )

    private fun reverseChildren(node: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot =
        node.copy(children = node.children.reversed().map(::reverseChildren))

    private fun reindex(node: AccessibilityNodeSnapshot, path: List<Int>): AccessibilityNodeSnapshot =
        node.copy(
            childIndexPath = path,
            children = node.children.mapIndexed { index, child -> reindex(child, path + index) },
        )
}
