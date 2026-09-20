package dev.droplet.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Send clipboard": an invisible screen that exists only so Android lets
 * droplet read the clipboard (it has to be the focused app), sends it to
 * the other devices, and goes away. Opened by the quick-settings tile and
 * the Stay connected notification.
 */
class ClipSendActivity : AppCompatActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // brings the connections up if Stay connected is off
        if (Prefs.hasHub) Live.hold(TAG)
        Mesh.hold(TAG)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        lifecycleScope.launch {
            val result = if (Prefs.hasHub) {
                val up = withTimeoutOrNull(8_000) { Live.state.first { it.connected } } != null
                if (up) ClipBridge.send(this@ClipSendActivity, manual = true) else sendDirect()
            } else sendDirect()
            val msg = when (result) {
                ClipBridge.Result.SENT, ClipBridge.Result.SAME -> R.string.clip_sent
                ClipBridge.Result.EMPTY -> R.string.clip_empty
                ClipBridge.Result.TOO_BIG -> R.string.clip_too_big
                ClipBridge.Result.OFF -> R.string.clip_off
                ClipBridge.Result.OFFLINE -> R.string.clip_offline
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** No hub (or it's out of reach): straight to each paired device, waiting to know it got there. */
    private suspend fun sendDirect(): ClipBridge.Result {
        withTimeoutOrNull(8_000) { Mesh.state.first { it.status != Mesh.Status.STARTING && it.status != Mesh.Status.OFF || !Prefs.meshEnabled } }
        return ClipBridge.sendDirect(this)
    }

    override fun onDestroy() {
        if (Prefs.hasHub) Live.release(TAG)
        Mesh.release(TAG)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "clip-send"
    }
}
