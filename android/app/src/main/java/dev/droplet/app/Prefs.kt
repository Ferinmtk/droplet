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
}
