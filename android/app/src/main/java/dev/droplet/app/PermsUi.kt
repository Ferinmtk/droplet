package dev.droplet.app

import android.content.Context
import dev.droplet.app.mesh.Perms
import org.json.JSONObject

/** What screens say about per-device permissions and Pause (docs/mesh.md §9.9). */
object PermsUi {
    /** How faint an action is that can't be used now. */
    const val DIMMED = 0.38f

    fun label(context: Context, cap: String): String = context.getString(when (cap) {
        Perms.FILES -> R.string.perm_cap_files
        Perms.CHAT -> R.string.perm_cap_chat
        Perms.CLIPBOARD -> R.string.perm_cap_clipboard
        Perms.NOTIFY -> R.string.perm_cap_notify
        Perms.CONTROL -> R.string.perm_cap_control
        Perms.RING -> R.string.perm_cap_ring
        else -> R.string.perm_cap_access
    })

    /** One line under each switch: what it covers on a phone. */
    fun explain(context: Context, cap: String): String = context.getString(when (cap) {
        Perms.FILES -> R.string.perm_cap_files_sub
        Perms.CHAT -> R.string.perm_cap_chat_sub
        Perms.CLIPBOARD -> R.string.perm_cap_clipboard_sub
        Perms.NOTIFY -> R.string.perm_cap_notify_sub
        Perms.CONTROL -> R.string.perm_cap_control_sub
        Perms.RING -> R.string.perm_cap_ring_sub
        else -> R.string.perm_cap_access_sub
    })

    /**
     * Why sending a message of type [t] to [p] can't happen now, or null if it
     * can: this phone's own switches and pauses, then what the device said
     * about this phone. Files and messages to a paused device aren't
     * unavailable: they wait for the resume.
     */
    fun unavailable(p: Mesh.PeerView, t: String, pausedAll: Boolean = Mesh.pausedAll): String? {
        val msg = JSONObject().put("t", t)
        val waits = t == "text" || t == "offer"
        Perms.check(p.entry, msg, pausedAll, "out")?.let { no ->
            if (no.why == Perms.PAUSED && waits) return null
            val text = Perms.localText(p.entry.name, no.why, no.cap, pausedAll)
            // "Everything is paused…", but a device's name as it's written
            return if (text.startsWith(p.entry.name)) text else text.replaceFirstChar { it.uppercase() }
        }
        val cap = Perms.capability(msg)
        val why = Perms.remoteRefuses(p.remote, cap) ?: return null
        if (why == Perms.PAUSED && waits) return null
        return Perms.refusalText(p.entry.name, why, cap)
    }
}
