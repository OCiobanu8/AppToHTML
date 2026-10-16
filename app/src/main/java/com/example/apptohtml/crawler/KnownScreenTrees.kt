package com.example.apptohtml.crawler

import java.io.File

/**
 * The first-viewport tree of every screen the crawl knows, read back from each screen's saved XML.
 *
 * Reading the saved bytes rather than holding live trees is what makes the uniqueness check the same
 * in a fresh crawl and a resumed one: both compare against exactly what is on disk. A screen's XML is
 * rewritten when it is expanded — from a re-scan — so whoever rewrites it calls [invalidate], and the
 * next check reads the new bytes. Between rewrites a capture is read once, readable or not, so a new
 * screen costs one evaluation context per known screen, not one file parse each.
 *
 * An unreadable capture is reported once through [onUnreadable] and then as null; [IdentitySettlement]
 * refuses to count such a screen as a non-collision.
 */
class KnownScreenTrees(
    private val read: (File) -> AccessibilityNodeSnapshot? = CaptureTreeReader::readFirstViewport,
    private val onUnreadable: (screenId: String, xmlFile: File) -> Unit = { _, _ -> },
) {
    private val treesByScreenId = mutableMapOf<String, AccessibilityNodeSnapshot?>()

    /** [record]'s first-viewport tree, or null when its saved XML cannot be read back. */
    fun treeFor(record: CrawlScreenRecord): AccessibilityNodeSnapshot? {
        if (record.screenId in treesByScreenId) return treesByScreenId[record.screenId]
        val file = File(record.xmlPath)
        val tree = read(file)
        if (tree == null) onUnreadable(record.screenId, file)
        treesByScreenId[record.screenId] = tree
        return tree
    }

    /** Forgets [screenId]'s tree: its saved XML has been rewritten, so it is read again when needed. */
    fun invalidate(screenId: String) {
        treesByScreenId.remove(screenId)
    }

    /** Every record in [records] as the settle decision sees it. */
    fun knownScreens(records: Collection<CrawlScreenRecord>): List<KnownScreen> =
        records.map { record -> KnownScreen(record.screenId, record.identity, treeFor(record)) }
}
