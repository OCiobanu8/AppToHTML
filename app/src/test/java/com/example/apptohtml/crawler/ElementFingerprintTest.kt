package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElementFingerprintTest {

    @Test
    fun of_excludesCheckedButKeepsCheckable() {
        val unchecked = pressable(label = "Wi-Fi", checkable = true, checked = false)
        val checked = pressable(label = "Wi-Fi", checkable = true, checked = true)

        // checked is transient state -> same identity
        assertEquals(ElementFingerprint.of(unchecked), ElementFingerprint.of(checked))

        val notCheckable = pressable(label = "Wi-Fi", checkable = false, checked = false)
        // checkable is the element's type/affordance -> different identity
        assertNotEquals(ElementFingerprint.of(unchecked), ElementFingerprint.of(notCheckable))
    }

    @Test
    fun of_ignoresBoundsAndChildIndexPath() {
        val a = pressable(label = "Open", bounds = "[0,0][100,100]", childIndexPath = listOf(0, 1))
        val b = pressable(label = "Open", bounds = "[900,900][1000,1000]", childIndexPath = listOf(3, 7, 2))

        assertEquals(ElementFingerprint.of(a), ElementFingerprint.of(b))
        assertEquals(ElementFingerprint.of(a).encoded, ElementFingerprint.of(b).encoded)
    }

    @Test
    fun normalizeLabel_stripsParenthesizedBadge() {
        assertEquals("messages", ElementFingerprint.normalizeLabel("Messages (3)"))
        assertEquals("messages", ElementFingerprint.normalizeLabel("Messages (5)"))
        assertEquals("messages", ElementFingerprint.normalizeLabel("messages"))
        assertEquals(
            ElementFingerprint.normalizeLabel("Messages (3)"),
            ElementFingerprint.normalizeLabel("Messages (5)"),
        )
    }

    @Test
    fun normalizeLabel_stripsTrailingBareNumber() {
        assertEquals("inbox", ElementFingerprint.normalizeLabel("Inbox 12"))
        // Accepted trade-off: trailing bare numbers cannot be distinguished from meaningful
        // trailing digits, so these merge.
        assertEquals("room", ElementFingerprint.normalizeLabel("Room 101"))
        assertEquals("room", ElementFingerprint.normalizeLabel("Room 202"))
        assertEquals(
            ElementFingerprint.normalizeLabel("Room 101"),
            ElementFingerprint.normalizeLabel("Room 202"),
        )
    }

    @Test
    fun normalizeLabel_preservesInternalNumbers() {
        // Number is not trailing -> kept.
        assertEquals("level 3 settings", ElementFingerprint.normalizeLabel("Level 3 settings"))
        assertNotEquals(
            ElementFingerprint.normalizeLabel("Level 3 settings"),
            ElementFingerprint.normalizeLabel("Level 5 settings"),
        )
    }

    @Test
    fun normalizeLabel_trimsAndLowercases() {
        assertEquals("settings", ElementFingerprint.normalizeLabel("  Settings  "))
        assertEquals("", ElementFingerprint.normalizeLabel("   "))
        assertEquals("", ElementFingerprint.normalizeLabel(""))
    }

    @Test
    fun ofFields_normalizesBlankResourceIdAndClassNameToNull() {
        val blanks = ElementFingerprint.ofFields(
            label = "Open",
            resourceId = "   ",
            className = "",
            isListItem = false,
            checkable = false,
            editable = false,
        )
        assertNull(blanks.resourceId)
        assertNull(blanks.className)
    }

    @Test
    fun encoded_isStableAndFieldOrdered() {
        val fingerprint = ElementFingerprint.ofFields(
            label = "Open",
            resourceId = "com.example:id/open",
            className = "android.widget.Button",
            isListItem = true,
            checkable = false,
            editable = false,
        )
        val expected = "com.example:id/open|open|android.widget.Button|true|false|false"
        assertEquals(expected, fingerprint.encoded)
        // Same inputs -> same string.
        assertEquals(expected, ElementFingerprint.of(
            pressable(
                label = "Open",
                resourceId = "com.example:id/open",
                className = "android.widget.Button",
                isListItem = true,
            ),
        ).encoded)
    }

    private fun pressable(
        label: String,
        resourceId: String? = null,
        bounds: String = "[0,0][10,10]",
        className: String? = "android.widget.TextView",
        isListItem: Boolean = false,
        childIndexPath: List<Int> = emptyList(),
        checkable: Boolean = false,
        checked: Boolean = false,
        editable: Boolean = false,
    ): PressableElement = PressableElement(
        label = label,
        resourceId = resourceId,
        bounds = bounds,
        className = className,
        isListItem = isListItem,
        childIndexPath = childIndexPath,
        checkable = checkable,
        checked = checked,
        editable = editable,
    )
}
