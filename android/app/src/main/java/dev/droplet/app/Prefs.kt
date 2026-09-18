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

    /** Base URL of the hub, without a trailing slash; null until setup is done. */
    var hubUrl: String?
        get() = sp.getString("hub_url", null)
        set(v) = sp.edit { putString("hub_url", v) }

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
}
