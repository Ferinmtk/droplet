package dev.droplet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The US layout map, key names and consumer usages (HidKeys), and the typing field's diff. */
class HidKeysTest {

    /** The US layout, written out independently: each key's usage, its character, and its shifted character. */
    private val US = listOf(
        0x1E to "1!", 0x1F to "2@", 0x20 to "3#", 0x21 to "4$", 0x22 to "5%", 0x23 to "6^", 0x24 to "7&",
        0x25 to "8*", 0x26 to "9(", 0x27 to "0)", 0x2C to "  ", 0x2D to "-_", 0x2E to "=+", 0x2F to "[{",
        0x30 to "]}", 0x31 to "\\|", 0x33 to ";:", 0x34 to "'\"", 0x35 to "`~", 0x36 to ",<", 0x37 to ".>", 0x38 to "/?",
    ) + ('a'..'z').map { (0x04 + (it - 'a')) to "$it${it.uppercaseChar()}" }

    @Test
    fun everyPrintableAsciiCharacterTypes() {
        val expected = HashMap<Char, HidKeys.Stroke>()
        for ((usage, chars) in US) {
            expected.putIfAbsent(chars[0], HidKeys.Stroke(usage))
            if (chars[1] != chars[0]) expected[chars[1]] = HidKeys.Stroke(usage, HidKeys.MOD_SHIFT)
        }
        for (c in ' '..'~') {
            assertEquals("'$c' (${c.code})", expected[c], HidKeys.forChar(c))
        }
        assertEquals(95, (' '..'~').count { HidKeys.forChar(it) != null })
    }

    @Test
    fun shiftOnlyWhereTheUsLayoutNeedsIt() {
        for (c in "abcxyz0123456789-=[]\\;',./` ") assertEquals("$c", 0, HidKeys.forChar(c)!!.mods)
        for (c in "ABCXYZ!@#$%^&*()_+{}|:\"<>?~") assertEquals("$c", HidKeys.MOD_SHIFT, HidKeys.forChar(c)!!.mods)
    }

    @Test
    fun controlCharactersThatAreKeys() {
        assertEquals(HidKeys.Stroke(HidKeys.ENTER), HidKeys.forChar('\n'))
        assertEquals(HidKeys.Stroke(HidKeys.ENTER), HidKeys.forChar('\r'))
        assertEquals(HidKeys.Stroke(HidKeys.TAB), HidKeys.forChar('\t'))
        assertNull(HidKeys.forChar('\u0000'))
        assertNull(HidKeys.forChar('\u001B'))
        assertNull(HidKeys.forChar('\u007F'))
    }

    @Test
    fun charactersWithoutAUsKeyAreNotMapped() {
        for (c in "éèüßñçøå€£¥§°±µ¿¡«»“”‘’–—…•\u00A0\u200B中あ한Жω") assertNull("$c", HidKeys.forChar(c))
        // and so emoji (surrogate halves) aren't either
        for (c in "👋") assertNull(HidKeys.forChar(c))
    }

    @Test
    fun namedKeysMirrorTheProtocol() {
        val expected = mapOf(
            "Enter" to 0x28, "Escape" to 0x29, "Backspace" to 0x2A, "Tab" to 0x2B, "Space" to 0x2C,
            "PrintScreen" to 0x46, "Insert" to 0x49, "Home" to 0x4A, "PageUp" to 0x4B, "Delete" to 0x4C,
            "End" to 0x4D, "PageDown" to 0x4E, "ArrowRight" to 0x4F, "ArrowLeft" to 0x50, "ArrowDown" to 0x51,
            "ArrowUp" to 0x52, "ContextMenu" to 0x65,
            "F1" to 0x3A, "F2" to 0x3B, "F3" to 0x3C, "F4" to 0x3D, "F5" to 0x3E, "F6" to 0x3F,
            "F7" to 0x40, "F8" to 0x41, "F9" to 0x42, "F10" to 0x43, "F11" to 0x44, "F12" to 0x45,
        )
        for ((name, usage) in expected) assertEquals(name, usage, HidKeys.named(name))
        // single characters are their key, for shortcuts (Ctrl+C) and the remote's "b"
        assertEquals(0x05, HidKeys.named("b"))
        assertEquals(0x05, HidKeys.named("B"))
        assertEquals(0x06, HidKeys.named("c"))
        assertEquals(0x1E, HidKeys.named("1"))
        assertNull(HidKeys.named("F13"))
        assertNull(HidKeys.named("Hyper"))
        assertNull(HidKeys.named(""))
        assertNull(HidKeys.named("é"))
    }

    @Test
    fun presentationKeys() {
        // docs/remote.md's presentation remote: next, previous, start, end, black
        assertEquals(0x4F, HidKeys.named("ArrowRight"))
        assertEquals(0x50, HidKeys.named("ArrowLeft"))
        assertEquals(0x3E, HidKeys.named("F5"))
        assertEquals(0x29, HidKeys.named("Escape"))
        assertEquals(0x05, HidKeys.named("b"))
    }

    @Test
    fun consumerUsages() {
        assertEquals(
            mapOf(
                "MediaPlayPause" to 0xCD, "MediaNext" to 0xB5, "MediaPrevious" to 0xB6, "MediaStop" to 0xB7,
                "AudioVolumeUp" to 0xE9, "AudioVolumeDown" to 0xEA, "AudioVolumeMute" to 0xE2,
                "BrowserHome" to 0x223, "BrowserBack" to 0x224,
            ),
            HidKeys.CONSUMER,
        )
    }

    @Test
    fun modifierNames() {
        assertEquals(0, HidKeys.mods(emptyList()))
        assertEquals(HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, HidKeys.mods(listOf("ctrl", "shift")))
        assertEquals(HidKeys.MOD_ALT or HidKeys.MOD_META, HidKeys.mods(listOf("meta", "alt", "nonsense")))
        // they are the report's left-hand modifier bits
        assertEquals(listOf(0x01, 0x02, 0x04, 0x08), listOf(HidKeys.MOD_CTRL, HidKeys.MOD_SHIFT, HidKeys.MOD_ALT, HidKeys.MOD_META))
    }

    @Test
    fun typingDiff() {
        assertEquals(TypingDiff.Change(0, "b"), TypingDiff.between("a", "ab"))
        assertEquals(TypingDiff.Change(1, ""), TypingDiff.between("ab", "a"))
        assertEquals(TypingDiff.Change(0, ""), TypingDiff.between("same", "same"))
        // an autocorrect-style replacement: erase back to where they part, then type the rest
        assertEquals(TypingDiff.Change(2, "he "), TypingDiff.between("\u200Bteh", "\u200Bthe "))
        // an emoji is one character to erase, not two
        assertEquals(TypingDiff.Change(1, ""), TypingDiff.between("a👋", "a"))
        assertEquals(TypingDiff.Change(0, "👋"), TypingDiff.between("a", "a👋"))
        assertEquals(TypingDiff.Change(1, ""), TypingDiff.between("\u200B", ""))
        assertTrue(TypingDiff.between("", "").let { it.erase == 0 && it.type.isEmpty() })
    }
}
