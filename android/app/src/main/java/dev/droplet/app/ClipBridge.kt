package dev.droplet.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Clipboard sync (docs/remote.md §3.6).
 *
 * Other devices → phone works any time: Android lets apps write the
 * clipboard from the background.
 *
 * Phone → other devices is limited: since Android 10 only the app on screen
 * (or the keyboard) may read the clipboard. So the phone's clipboard goes out
 * when droplet comes to the foreground and the clipboard has changed, or when
 * the owner taps "Send clipboard" (quick-settings tile or notification), which
 * briefly brings up an invisible droplet screen to read it.
 */
object ClipBridge {
    private const val MAX_BYTES = 256 * 1024
    private const val MIN_GAP_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private var lastSentAt = 0L

    enum class Result { SENT, EMPTY, SAME, TOO_BIG, OFF, OFFLINE }

    private fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** A clip from another device: put it on this phone's clipboard. */
    fun apply(context: Context, text: String) {
        if (text.isEmpty() || !Prefs.capClipboard) return
        main.post {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return@post
            // remembered first, so neither the foreground check nor the tile sends it straight back
            Prefs.clipLast = fingerprint(text)
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("droplet", text)) }
            Prefs.clipSeenAt = cm.primaryClipDescription?.timestamp ?: 0L
        }
    }

    /**
     * Called when a droplet window gains focus, the only time Android allows
     * reading the clipboard. Sends it if it changed since droplet last saw it.
     * Only the clip's description is read when nothing changed, so Android's
     * "droplet pasted from your clipboard" notice doesn't show every time.
     */
    fun onForeground(context: Context) {
        if (!Prefs.capClipboard || (!Live.state.value.connected && Mesh.state.value.links == 0)) return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        if (desc.timestamp != 0L && desc.timestamp == Prefs.clipSeenAt) return
        // passwords copied from a password manager stay on the phone unless sent on purpose
        if (sensitive(desc)) {
            Prefs.clipSeenAt = desc.timestamp
            return
        }
        send(context, manual = false)
    }

    /** Reads the clipboard (the caller must have window focus) and sends it. */
    fun send(context: Context, manual: Boolean): Result {
        if (!Prefs.capClipboard) return Result.OFF
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return Result.EMPTY
        val clip = cm.primaryClip
        Prefs.clipSeenAt = cm.primaryClipDescription?.timestamp ?: 0L
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return Result.EMPTY
        if (text.toByteArray().size > MAX_BYTES) return Result.TOO_BIG
        val print = fingerprint(text)
        if (!manual && print == Prefs.clipLast) return Result.SAME
        val now = SystemClock.elapsedRealtime()
        if (!manual && now - lastSentAt < MIN_GAP_MS) return Result.SAME
        val hub = Live.send(JSONObject().put("t", "clip").put("text", text))
        // and straight to your devices on direct links (all of them, when the hub is down)
        val direct = Mesh.clipToPeers(text, viaHub = hub)
        if (!hub && !direct) return Result.OFFLINE
        lastSentAt = now
        Prefs.clipLast = print
        return Result.SENT
    }

    private fun sensitive(desc: ClipDescription): Boolean {
        val extras = desc.extras ?: return false
        val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        return extras.getBoolean(key, false)
    }
}
