package dev.droplet.app

/**
 * Keys for the Bluetooth keyboard (BtHid): characters and key names to HID
 * usages (USB HID Usage Tables, page 0x07 Keyboard/Keypad and 0x0C Consumer).
 *
 * A HID keyboard sends key positions, not characters, and the host turns
 * them into characters with its own layout. This map is the US layout, so
 * text types as written when the host uses US (or US-International without
 * dead keys); anything the US layout has no key for is skipped.
 */
object HidKeys {
    // modifier bits of the keyboard report's first byte (left-hand keys)
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_META = 0x08

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C

    /** One key press: the key's usage, with the modifiers it needs (Shift for capitals and symbols). */
    data class Stroke(val usage: Int, val mods: Int = 0)

    /** The key (and Shift) that types [c] on a US layout, or null if there's none. */
    fun forChar(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0x04 + (c - 'a'))
        in 'A'..'Z' -> Stroke(0x04 + (c - 'A'), MOD_SHIFT)
        in '1'..'9' -> Stroke(0x1E + (c - '1'))
        '0' -> Stroke(0x27)
        '\n', '\r' -> Stroke(ENTER)
        '\t' -> Stroke(TAB)
        ' ' -> Stroke(SPACE)
        '!' -> Stroke(0x1E, MOD_SHIFT)
        '@' -> Stroke(0x1F, MOD_SHIFT)
        '#' -> Stroke(0x20, MOD_SHIFT)
        '$' -> Stroke(0x21, MOD_SHIFT)
        '%' -> Stroke(0x22, MOD_SHIFT)
        '^' -> Stroke(0x23, MOD_SHIFT)
        '&' -> Stroke(0x24, MOD_SHIFT)
        '*' -> Stroke(0x25, MOD_SHIFT)
        '(' -> Stroke(0x26, MOD_SHIFT)
        ')' -> Stroke(0x27, MOD_SHIFT)
        else -> symbol(c)
    }

    // US layout punctuation: each key's unshifted and shifted character
    // (0x32, the non-US "#" key, is left out: US types # and ~ elsewhere)
    private fun symbol(c: Char): Stroke? = when (c) {
        '-' -> Stroke(0x2D)
        '_' -> Stroke(0x2D, MOD_SHIFT)
        '=' -> Stroke(0x2E)
        '+' -> Stroke(0x2E, MOD_SHIFT)
        '[' -> Stroke(0x2F)
        '{' -> Stroke(0x2F, MOD_SHIFT)
        ']' -> Stroke(0x30)
        '}' -> Stroke(0x30, MOD_SHIFT)
        '\\' -> Stroke(0x31)
        '|' -> Stroke(0x31, MOD_SHIFT)
        ';' -> Stroke(0x33)
        ':' -> Stroke(0x33, MOD_SHIFT)
        '\'' -> Stroke(0x34)
        '"' -> Stroke(0x34, MOD_SHIFT)
        '`' -> Stroke(0x35)
        '~' -> Stroke(0x35, MOD_SHIFT)
        ',' -> Stroke(0x36)
        '<' -> Stroke(0x36, MOD_SHIFT)
        '.' -> Stroke(0x37)
        '>' -> Stroke(0x37, MOD_SHIFT)
        '/' -> Stroke(0x38)
        '?' -> Stroke(0x38, MOD_SHIFT)
        else -> null
    }

    /**
     * Named keys, with docs/remote.md's names (the web's KeyboardEvent.key),
     * so the Bluetooth keyboard and droplet's own protocol speak alike.
     */
    private val NAMED: Map<String, Int> = buildMap {
        put("Enter", ENTER)
        put("Escape", ESCAPE)
        put("Backspace", BACKSPACE)
        put("Tab", TAB)
        put("Space", SPACE)
        put("PrintScreen", 0x46)
        put("Insert", 0x49)
        put("Home", 0x4A)
        put("PageUp", 0x4B)
        put("Delete", 0x4C)
        put("End", 0x4D)
        put("PageDown", 0x4E)
        put("ArrowRight", 0x4F)
        put("ArrowLeft", 0x50)
        put("ArrowDown", 0x51)
        put("ArrowUp", 0x52)
        put("ContextMenu", 0x65)
        for (i in 1..12) put("F$i", 0x3A + i - 1)
    }

    /** The usage of a key name (docs/remote.md), or of a single character's key for shortcuts; null if unknown. */
    fun named(name: String): Int? =
        NAMED[name] ?: name.singleOrNull()?.let { forChar(it.lowercaseChar())?.usage }

    /** Consumer-page usages for the media row and the TV's Home and Back. */
    val CONSUMER: Map<String, Int> = mapOf(
        "MediaPlayPause" to 0xCD,
        "MediaNext" to 0xB5,        // Scan Next Track
        "MediaPrevious" to 0xB6,    // Scan Previous Track
        "MediaStop" to 0xB7,
        "AudioVolumeUp" to 0xE9,
        "AudioVolumeDown" to 0xEA,
        "AudioVolumeMute" to 0xE2,
        "BrowserHome" to 0x223,     // AC Home: Home on Android and Google TV
        "BrowserBack" to 0x224,     // AC Back: Back on Android and Google TV
    )

    /** Modifier bits for docs/remote.md's names: ctrl, alt, shift, meta. */
    fun mods(names: Collection<String>): Int = names.fold(0) { m, n ->
        m or when (n) {
            "ctrl" -> MOD_CTRL
            "alt" -> MOD_ALT
            "shift" -> MOD_SHIFT
            "meta" -> MOD_META
            else -> 0
        }
    }
}

/**
 * What changed in the typing field since it was last sent: how many
 * characters to erase from the end, and what to type after that. Works on
 * code points, so an emoji counts as what it is and not as two halves.
 */
object TypingDiff {
    data class Change(val erase: Int, val type: String)

    fun between(sent: String, now: String): Change {
        val a = sent.codePoints().toArray()
        val b = now.codePoints().toArray()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        val added = StringBuilder()
        for (i in p until b.size) added.appendCodePoint(b[i])
        return Change(a.size - p, added.toString())
    }
}
