package dev.droplet.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Everything the app remembers between launches. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("droplet", Context.MODE_PRIVATE)
    }

    // --- the hub (docs/local-first.md) ------------------------------------------
    // Versions before 1.2 knew the hub only by one URL, its tailnet address.
    // That URL stays under the same key; the rest is what makes the LAN work.

    /**
     * The hub's address with ordinary, verified TLS: its tailnet URL (or,
     * for testing, the emulator's http://10.0.2.2:port). No trailing slash.
     */
    var hubUrl: String?
        get() = sp.getString("hub_url", null)
        set(v) = sp.edit { putString("hub_url", v) }

    /** The hub's permanent id (16 hex characters), from /api/hub/info or mDNS. */
    var hubId: String?
        get() = sp.getString("hub_id", null)
        set(v) = sp.edit { putString("hub_id", v) }

    /** SHA-256 of the hub's LAN certificate (DER), lowercase hex: the pin. */
    var hubFingerprint: String?
        get() = sp.getString("hub_fp", null)
        set(v) = sp.edit { putString("hub_fp", v) }

    /** Where the pin came from: [PIN_TAILNET] (verified TLS) or [PIN_TOFU] (first use on the LAN). */
    var pinSource: String?
        get() = sp.getString("pin_source", null)
        set(v) = sp.edit { putString("pin_source", v) }

    /** The hub machine's name, e.g. "t15". */
    var hubName: String?
        get() = sp.getString("hub_name", null)
        set(v) = sp.edit { putString("hub_name", v) }

    /** LAN addresses ("192.168.100.20:8443") that worked, most recent first. Hints only: DHCP moves them. */
    var lanAddresses: List<String>
        get() = sp.getString("lan_addrs", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit { putString("lan_addrs", v.distinct().take(MAX_LAN_ADDRESSES).joinToString(",")) }

    /**
     * This device's token on the hub. The WebView keeps it as the
     * `droplet_device` cookie per origin; this copy lets it follow the
     * app from one origin (tailnet, LAN) to the other.
     */
    var deviceToken: String?
        get() = sp.getString("device_token", null)
        set(v) = sp.edit { putString("device_token", v) }

    /** True once a hub is known in any way. */
    val hasHub: Boolean get() = hubUrl != null || (hubId != null && hubFingerprint != null)

    /**
     * Chose "No hub" in setup (or forgot the hub later): the phone works with
     * its directly paired devices alone. Not about the hub, so forgetting the
     * hub leaves it alone (and sets it).
     */
    var noHub: Boolean
        get() = sp.getBoolean("no_hub", false)
        set(v) = sp.edit { putBoolean("no_hub", v) }

    /** Set up either way: with a hub, or without one. Until then the app opens on setup. */
    val isSetUp: Boolean get() = hasHub || noHub

    /** Forgets everything about the hub (Settings → Forget this hub). */
    fun forgetHub() = sp.edit {
        for (k in listOf("hub_url", "hub_id", "hub_fp", "pin_source", "hub_name", "lan_addrs", "device_token",
            "seen_inbox", "seen_unread", "inbox_primed", "last_target", "remote_target", "silenced_ring",
            "mesh_device_id")) remove(k)
    }

    const val PIN_TAILNET = "tailnet"
    const val PIN_TOFU = "tofu"
    private const val MAX_LAN_ADDRESSES = 4

    var stayConnected: Boolean
        get() = sp.getBoolean("stay_connected", false)
        set(v) = sp.edit { putBoolean("stay_connected", v) }

    var notifyInbox: Boolean
        get() = sp.getBoolean("notify_inbox", true)
        set(v) = sp.edit { putBoolean("notify_inbox", v) }

    /** Packages whose notifications are never mirrored. */
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("excluded", v) }

    /** Packages seen posting notifications, so the exclude list can offer apps with no launcher icon. */
    var seenPackages: Set<String>
        get() = sp.getStringSet("seen_packages", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("seen_packages", v) }

    /** Where the share sheet sent things last time; listed first next time. */
    var lastTarget: String?
        get() = sp.getString("last_target", null)
        set(v) = sp.edit { putString("last_target", v) }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(v) = sp.edit { putBoolean("asked_notifications", v) }

    /** The last ring this phone silenced, so a slow server doesn't make it ring again. */
    var silencedRing: String?
        get() = sp.getString("silenced_ring", null)
        set(v) = sp.edit { putString("silenced_ring", v) }

    /** Inbox file names already notified about (see ConnectionService). */
    var seenInbox: Set<String>
        get() = sp.getStringSet("seen_inbox", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("seen_inbox", v) }

    /** Unread chat counts per device at the last check, as "id=count" entries. */
    var seenUnread: Map<String, Int>
        get() = sp.getStringSet("seen_unread", emptySet())!!.mapNotNull {
            val (id, n) = it.split("=", limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            n.toIntOrNull()?.let { c -> id to c }
        }.toMap()
        set(v) = sp.edit { putStringSet("seen_unread", v.map { "${it.key}=${it.value}" }.toSet()) }

    var inboxPrimed: Boolean
        get() = sp.getBoolean("inbox_primed", false)
        set(v) = sp.edit { putBoolean("inbox_primed", v) }

    // --- remote control (see Live) ---------------------------------------------
    // Each capability is also gated by its Android permission; these are the
    // owner's switches on top, so a granted permission can still be turned off.

    var capMedia: Boolean
        get() = sp.getBoolean("cap_media", true)
        set(v) = sp.edit { putBoolean("cap_media", v) }

    var capSms: Boolean
        get() = sp.getBoolean("cap_sms", true)
        set(v) = sp.edit { putBoolean("cap_sms", v) }

    var capFiles: Boolean
        get() = sp.getBoolean("cap_files", true)
        set(v) = sp.edit { putBoolean("cap_files", v) }

    var capClipboard: Boolean
        get() = sp.getBoolean("cap_clipboard", true)
        set(v) = sp.edit { putBoolean("cap_clipboard", v) }

    /** Fingerprint of the clipboard text last synced either way, so it isn't sent back. */
    var clipLast: String?
        get() = sp.getString("clip_last", null)
        set(v) = sp.edit { putString("clip_last", v) }

    /** The clipboard's timestamp when it was last looked at, so an unchanged clipboard isn't read again. */
    var clipSeenAt: Long
        get() = sp.getLong("clip_seen_at", 0L)
        set(v) = sp.edit { putLong("clip_seen_at", v) }

    /** The device the presentation remote drove last time. */
    var remoteTarget: String?
        get() = sp.getString("remote_target", null)
        set(v) = sp.edit { putString("remote_target", v) }

    // --- the mesh (see Mesh) -------------------------------------------------------
    // Direct links to your other devices. The identity and trust list live in
    // files under the app's private storage, not here.

    /** Direct connections on or off (Settings). On by default: the server only listens while Stay connected runs. */
    var meshEnabled: Boolean
        get() = sp.getBoolean("mesh_enabled", true)
        set(v) = sp.edit { putBoolean("mesh_enabled", v) }

    /** This phone's device id on the hub, learned from the live connection: the mesh peer id. */
    var meshDeviceId: String?
        get() = sp.getString("mesh_device_id", null)
        set(v) = sp.edit { putString("mesh_device_id", v) }

    /** This phone's name on the hub, which the mesh uses too. */
    var meshDeviceName: String?
        get() = sp.getString("mesh_device_name", null)
        set(v) = sp.edit { putString("mesh_device_name", v) }

    // --- Bluetooth mouse and keyboard (see BtHid) -----------------------------------
    // Not about the hub, so forgetting the hub leaves these alone.

    /** The Bluetooth address of the computer or TV used last, for Reconnect. */
    var btLastHost: String?
        get() = sp.getString("bt_last_host", null)
        set(v) = sp.edit { putString("bt_last_host", v) }

    var btLastHostName: String?
        get() = sp.getString("bt_last_host_name", null)
        set(v) = sp.edit { putString("bt_last_host_name", v) }

    /** What this phone's firmware did when asked to be a HID device: [BT_SUPPORTED], [BT_UNSUPPORTED], [BT_REFUSED], or null (never tried). */
    var btSupport: String?
        get() = sp.getString("bt_support", null)
        set(v) = sp.edit { putString("bt_support", v) }

    const val BT_SUPPORTED = "yes"
    const val BT_UNSUPPORTED = "no"
    const val BT_REFUSED = "refused"

    /** Presentation remote targets that are Bluetooth hosts are stored as this prefix and the address. */
    const val BT_TARGET = "bt:"

    // --- the TV remote (dev.droplet.app.tv) ---------------------------------------------------

    /** The paired TV the remote shows (its id in tv/tvs.json). */
    var tvSelected: String?
        get() = sp.getString("tv_selected", null)
        set(v) = sp.edit { putString("tv_selected", v) }

    /** The TV remote shows the touchpad rather than the D-pad. */
    var tvTouchpad: Boolean
        get() = sp.getBoolean("tv_touchpad", false)
        set(v) = sp.edit { putBoolean("tv_touchpad", v) }

    /** The TV remote shows every button, not just the essentials. */
    var tvMore: Boolean
        get() = sp.getBoolean("tv_more", false)
        set(v) = sp.edit { putBoolean("tv_more", v) }

    /** The TV remote vibrates on each press. */
    var tvHaptics: Boolean
        get() = sp.getBoolean("tv_haptics", true)
        set(v) = sp.edit { putBoolean("tv_haptics", v) }
}
