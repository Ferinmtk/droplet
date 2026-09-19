package dev.droplet.app.tv

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * What droplet will send a TV, the same lists and rules as the hub's tv.py:
 * keys from a fixed allowlist, apps from a fixed catalogue or a plain
 * https:// link, text within limits, and a TV only on this network.
 */
object TvCatalog {
    const val API_PORT = 6466            // the pairing port is always the next one up
    const val MAX_TEXT = 500
    const val MAX_URL = 2048

    /**
     * The keys the remote sends: the name the UI uses -> the protocol's key
     * code (remotemessage.proto's RemoteKeyCode, which is Android's KEYCODE_
     * numbering). MUTE is the speaker mute (KEYCODE_VOLUME_MUTE); Android's
     * KEYCODE_MUTE is the microphone.
     */
    val KEYS: Map<String, Int> = linkedMapOf(
        "DPAD_UP" to 19, "DPAD_DOWN" to 20, "DPAD_LEFT" to 21, "DPAD_RIGHT" to 22, "DPAD_CENTER" to 23,
        "BACK" to 4, "HOME" to 3, "MENU" to 82, "POWER" to 26,
        "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "CHANNEL_UP" to 166, "CHANNEL_DOWN" to 167,
        "MEDIA_PLAY_PAUSE" to 85, "MEDIA_NEXT" to 87, "MEDIA_PREVIOUS" to 88, "MEDIA_REWIND" to 89,
        "MEDIA_FAST_FORWARD" to 90, "MEDIA_STOP" to 86,
        "SETTINGS" to 176, "INFO" to 165, "GUIDE" to 172, "SEARCH" to 84, "TV_INPUT" to 178, "DEL" to 67, "ENTER" to 66,
        "0" to 7, "1" to 8, "2" to 9, "3" to 10, "4" to 11, "5" to 12, "6" to 13, "7" to 14, "8" to 15, "9" to 16,
        "MUTE" to 164,
    )

    /** An app in the launcher: a package (opened with market://launch?id=, which falls back to its Play Store page), or a key. */
    data class App(val id: String, val name: String, val pkg: String? = null, val key: String? = null)

    /** tv.py's APPS, in its order. */
    val APPS: List<App> = listOf(
        App("youtube", "YouTube", pkg = "com.google.android.youtube.tv"),
        App("netflix", "Netflix", pkg = "com.netflix.ninja"),
        App("prime", "Prime Video", pkg = "com.amazon.amazonvideo.livingroom"),
        App("spotify", "Spotify", pkg = "com.spotify.tv.android"),
        App("showmax", "Showmax", pkg = "com.showmax.app"),
        App("disney", "Disney+", pkg = "com.disney.disneyplus"),
        App("plex", "Plex", pkg = "com.plexapp.android"),
        App("home", "Google TV home", key = "HOME"),
    )

    /** Friendlier names for the app in front (tv.py's APP_NAMES). */
    private val APP_NAMES: Map<String, String> = APPS.filter { it.pkg != null }.associate { it.pkg!! to it.name } + mapOf(
        "com.google.android.apps.tv.launcherx" to "Home",
        "com.google.android.tvlauncher" to "Home",
        "com.google.android.leanbacklauncher" to "Home",
        "com.android.tv.settings" to "Settings",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.videos" to "Google TV",
        "com.android.vending" to "Play Store",
        "com.google.android.katniss" to "Assistant",
        "com.google.android.tv" to "Live TV",
        "com.tcl.tv" to "TV",
    )

    fun appName(pkg: String?): String? = if (pkg.isNullOrEmpty()) null else APP_NAMES[pkg] ?: pkg

    /** Something the UI shows the user as it is. */
    class Invalid(message: String) : IllegalArgumentException(message)

    fun keyCode(name: String): Int = KEYS[name.trim().uppercase()] ?: throw Invalid("That isn't a key droplet sends.")

    /** The 6-character code, tidied (spaces and dashes out, upper case). */
    fun checkCode(value: String): String {
        val code = value.replace(Regex("[\\s-]"), "").uppercase()
        if (!Regex("^[0-9A-F]{6}$").matches(code)) throw Invalid("The code is 6 characters: digits 0-9 and letters A-F.")
        return code
    }

    /** Text for the TV's text field: printable characters and spaces, up to [MAX_TEXT]. */
    fun checkText(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (cp == ' '.code || printable(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val text = sb.toString()
        if (text.isEmpty()) throw Invalid("Type some text first.")
        if (text.codePointCount(0, text.length) > MAX_TEXT) throw Invalid("That's more than $MAX_TEXT characters.")
        return text
    }

    /** Python's str.isprintable, near enough: no control, format, separator (other than the space) or unassigned characters. */
    private fun printable(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SPACE_SEPARATOR.toInt() -> false
        else -> true
    }

    /**
     * A web link the TV may open: https only. Other schemes (intent:,
     * file:, content:, market:…) could point the TV at anything installed
     * on it.
     */
    fun checkUrl(value: String): String {
        val url = value.trim()
        if (url.length > MAX_URL || url.any { it.isWhitespace() || Character.isISOControl(it) }) {
            throw Invalid("That link isn't one droplet can open on the TV.")
        }
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw Invalid("Only https:// links can be opened on the TV.")
        }
        val authority = uri.rawAuthority
        if (!"https".equals(uri.scheme, ignoreCase = false) || uri.host.isNullOrEmpty() || authority == null || '@' in authority) {
            throw Invalid("Only https:// links can be opened on the TV.")
        }
        return url
    }

    /** What an app tile or link does: a key press, or an app link for the TV. */
    sealed class Launch {
        data class Key(val key: String) : Launch()
        data class Link(val link: String) : Launch()
    }

    /** A catalogue id or an https link -> what to send. */
    fun launch(value: String): Launch {
        val s = value.trim()
        APPS.firstOrNull { it.id == s.lowercase() }?.let { app ->
            return if (app.key != null) Launch.Key(app.key) else Launch.Link(marketLink(app.pkg!!))
        }
        if (s.lowercase().startsWith("https://")) return Launch.Link(checkUrl(s))
        throw Invalid("Pick an app from the list, or give an https:// link.")
    }

    /** A bare package becomes market://launch?id=…, as androidtvremote2 does for a link without a scheme. */
    fun marketLink(pkg: String): String = "market://launch?id=$pkg"

    // --- addresses ----------------------------------------------------------------------

    private val HOSTNAME = Regex("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * An address on this network: private, loopback or link-local, or on a
     * tailnet (100.64.0.0/10, fd7a:115c:a1e0::/48 is within fc00::/7). Keeps
     * droplet from being pointed at a machine on the internet.
     */
    fun isLocal(ip: InetAddress): Boolean {
        if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress) return true
        val b = ip.address
        return when (ip) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64    // 100.64.0.0/10
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC                                       // fc00::/7
            else -> false
        }
    }

    /** An IP address literal, parsed without any DNS lookup; null if it isn't one. */
    fun literal(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return when {
            IPV4.matches(h) -> if (h.split('.').all { it.toInt() in 0..255 }) InetAddress.getByName(h) else null
            ':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' } ->
                runCatching { InetAddress.getByName(h) }.getOrNull()
            else -> null
        }
    }

    /**
     * [value] as an address to connect to: an IP on this network, or a name
     * that resolves only to such addresses. Blocking when it's a name (a DNS
     * lookup), so off the main thread.
     */
    fun checkHost(value: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): String {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        val ip = literal(host)
        if (ip != null) {
            if (ip.isAnyLocalAddress || !isLocal(ip)) throw Invalid("The TV has to be on your own network.")
            return ip.hostAddress!!
        }
        if (!HOSTNAME.matches(host) || IPV4.matches(host)) throw Invalid("That doesn't look like an IP address.")
        val ips = try {
            resolve(host)
        } catch (e: Exception) {
            throw Invalid("Can't find $host on the network. Try its IP address.")
        }
        if (ips.isEmpty() || !ips.all { isLocal(it) }) throw Invalid("The TV has to be on your own network.")
        return host.removeSuffix(".")
    }

    // --- the TV's certificate --------------------------------------------------------------

    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** A MAC address in lower case with colons, or null for anything else (or the all-zero and broadcast ones). */
    fun normMac(value: String?): String? {
        var s = value.orEmpty().trim().lowercase().replace('-', ':')
        if (Regex("^[0-9a-f]{12}$").matches(s)) s = s.chunked(2).joinToString(":")
        return s.takeIf { MAC.matches(it) && it != "00:00:00:00:00:00" && it != "ff:ff:ff:ff:ff:ff" }
    }

    /**
     * The TV's name and MAC from its certificate's common name, as
     * androidtvremote2 reads them: "atvremote/<device>/<product>/<name>/<MAC>".
     */
    fun nameAndMac(commonName: String?): Pair<String?, String?> {
        val cn = commonName.orEmpty()
        val parts = cn.split('/')
        val name = if (parts.size > 1) parts[parts.size - 2] else cn
        return name.trim().ifEmpty { null } to normMac(parts.last())
    }
}
