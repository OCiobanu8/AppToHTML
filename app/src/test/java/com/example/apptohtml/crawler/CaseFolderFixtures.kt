package com.example.apptohtml.crawler

import java.io.File

/**
 * A crawl folder with cases, written by the crawler's own serializers with real first viewports, for
 * the offline settle and the case tool. One home, so the engine and the server are tested against
 * the same folders.
 */
internal object CaseFolderFixtures {
    const val PKG = LifecycleTrees.PKG

    fun idlessScreen(vararg labels: String) = LifecycleTrees.idless(labels.toList())

    fun elementOf(tree: AccessibilityNodeSnapshot, label: String): ScreenElementIdentity =
        ScreenIdentity.fromRoot(tree).elements.single { it.fingerprint.label.equals(label, ignoreCase = true) }

    val home = LifecycleTrees.buttons("open_battery", "open_cart")
    val battery = idlessScreen("Battery usage", "Battery saver", "Adaptive battery")

    /** Shares Battery's first two labels, so the crawler names it exactly as it names Battery. */
    val lookalike = idlessScreen("Battery usage", "Battery saver", "Battery health")
    val cart = LifecycleTrees.cart()

    val batteryUsage = HasControl(elementOf(battery, "Battery usage"))
    val batterySaver = HasControl(elementOf(battery, "Battery saver"))
    val adaptive = HasControl(elementOf(battery, "Adaptive battery"))
    val cartList = HasList(LifecycleTrees.CART_ITEMS, 1, setOf(LifecycleTrees.TITLE, LifecycleTrees.PRICE))

    fun writeScreen(
        dir: File,
        screenId: String,
        screenName: String,
        tree: AccessibilityNodeSnapshot?,
        traits: List<Trait> = emptyList(),
        settledBy: IdentityOrigin? = IdentityOrigin.MACHINE.takeIf { traits.isNotEmpty() },
        activeCaseIds: List<String> = emptyList(),
    ): File {
        val root = tree ?: LifecycleTrees.buttons()
        val identity = ScreenIdentity.fromRoot(root)
            .withName(ScreenNaming.buildScreenNameIdentity(screenName, PKG, root))
            .copy(traits = traits, settledBy = settledBy)
        val snapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = PKG,
            elements = emptyList(),
            xmlDump = "",
            stepSnapshots = tree?.let { listOf(ScrollCaptureStep(stepIndex = 0, root = it, newElementCount = 0)) }.orEmpty(),
        )
        val base = "${screenId}_${screenName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
        File(dir, "$base.html").writeText(HtmlRenderer.render(snapshot, emptyMap(), identity))
        val xml = File(dir, "$base.xml")
        xml.writeText(
            AccessibilityXmlSerializer.serialize(
                snapshot,
                SavedScreenFixtures.crawlState(screenId, identity, activeCaseIds = activeCaseIds),
            ),
        )
        return xml
    }

    fun case(
        id: String,
        raisedOn: String,
        kind: IdentityCaseKind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
        status: IdentityCaseStatus = IdentityCaseStatus.OPEN,
        related: List<String> = emptyList(),
        detail: String = "the proposal asserts nothing",
    ) = IdentityCase(id, status, kind, raisedOn, related, detail)

    /** Home (settled), Battery (unsettled, case_000 open), Cart (settled, case_001 settled-does-not-hold). */
    fun settingsFolder(dir: File): File {
        writeScreen(dir, "screen_00000", "Home", home, listOf(HasControl(elementOf(home, "open_battery"))))
        writeScreen(dir, "screen_00001", "Battery", battery, activeCaseIds = listOf("case_000"))
        writeScreen(dir, "screen_00002", "Cart", cart, listOf(cartList), activeCaseIds = listOf("case_001"))
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            listOf(
                case("case_000", "screen_00001"),
                case("case_001", "screen_00002", kind = IdentityCaseKind.SETTLED_DOES_NOT_HOLD),
            ),
        )
        return dir
    }

    fun traitsText(vararg traits: Trait): String =
        StringBuilder().also { ScreenIdentityXml.appendTraits(it, traits.toList(), depth = 0) }.toString()

    /** Every file under [dir] by relative path, with its SHA-256: equal maps mean nothing was written. */
    fun fingerprint(dir: File): Map<String, String?> =
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            .associate { it.relativeTo(dir).path to CaseSettleService.sha256(it) }
}

/** A device crawl folder held in memory: file name to its content's SHA-256 stand-in. */
internal class FakeDevice(override val unavailableReason: String? = null) : DeviceCrawl {
    val files = mutableMapOf<String, String>()
    val pushes = mutableListOf<String>()

    /** A push of this file fails. */
    var failOn: String? = null

    /** A push of this file "succeeds" but lands different bytes on the device. */
    var corruptOn: String? = null

    override fun sha256(name: String): String? = files[name]?.let { content ->
        if (content.length == 64) content else "device:$content"
    }

    override fun push(file: File, name: String) {
        if (name == failOn) error("adb push $name failed")
        files[name] = if (name == corruptOn) "corrupted" else CaseSettleService.sha256(file)!!
        pushes += name
    }

    companion object {
        /** A device holding exactly the files [dir] holds now: what a fresh pull leaves. */
        fun mirroring(dir: File) = FakeDevice().apply {
            dir.listFiles()!!.filter { it.isFile }.forEach { files[it.name] = CaseSettleService.sha256(it)!! }
        }
    }
}
