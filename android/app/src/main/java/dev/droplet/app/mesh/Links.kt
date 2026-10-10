package dev.droplet.app.mesh

/**
 * Links sent to another device (docs/mesh.md §9.10), as the reference's
 * mesh/links.py.
 *
 * Only web addresses travel as links, and only those are ever opened: `http`
 * and `https`, with a host, no spaces or control characters (nor the
 * invisible formatting ones), at most [MAX_URL] characters. Anything else
 * (`file:`, `javascript:`, `data:`, `intent:`, an app's own scheme) is refused
 * when it's sent and when it arrives, and never opened.
 */
object Links {
    const val MAX_URL = 2048

    /** Control and formatting characters that could hide or reorder text (the reference's _HIDDEN). */
    fun hidden(cp: Int): Boolean = cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 ||
        cp == 0xFEFF || cp == 0x00AD

    /** Python's `str.isprintable()` for one code point: not a control, format, separator, private or unassigned one. */
    fun printable(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false
            else -> true
        }
    }

    class BadLink(message: String) : IllegalArgumentException(message)

    /** The URL, trimmed, if it's one that may be sent and opened; else [BadLink] saying why. */
    fun checkUrl(v: Any?): String {
        if (v !is String) throw BadLink("a link must be text")
        val url = v.trim()
        if (url.isEmpty()) throw BadLink("the link is empty")
        if (url.codePointCount(0, url.length) > MAX_URL) throw BadLink("that link is too long ($MAX_URL characters at most)")
        if (url.codePoints().anyMatch { Character.isWhitespace(it) || !printable(it) || hidden(it) }) {
            throw BadLink("a link can't hold spaces or control characters")
        }
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(url)
            ?: throw BadLink("only web links (http:// or https://) can be sent and opened")
        if (m.groupValues[1].lowercase() !in setOf("http", "https")) {
            throw BadLink("only web links (http:// or https://) can be sent and opened")
        }
        if (host(m.groupValues[2]).isEmpty()) throw BadLink("that link has no address in it")
        return url
    }

    /** The host of "//user@host:port/path", as urlsplit's hostname: empty if there is none. */
    private fun host(rest: String): String {
        if (!rest.startsWith("//")) return ""
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) return hostPort.substringAfter('[').substringBefore(']')
        return hostPort.substringBefore(':')
    }

    fun checkUrlOrNull(v: Any?): String? = try {
        checkUrl(v)
    } catch (e: BadLink) {
        null
    }

    fun isUrl(v: Any?): Boolean = try {
        checkUrl(v)
        true
    } catch (e: BadLink) {
        false
    }

    /** A chat message that's nothing but a web link: the link (for an Open button), else null. */
    fun onlyUrl(text: String?): String? {
        val t = text?.trim() ?: return null
        if (!Regex("^(?i)https?://.*", RegexOption.DOT_MATCHES_ALL).matches(t)) return null
        return t.takeIf { isUrl(it) }
    }
}
