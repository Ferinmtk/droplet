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
        // brings the connection up if Stay connected is off
        Live.hold(TAG)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        lifecycleScope.launch {
            val up = withTimeoutOrNull(8_000) { Live.state.first { it.connected } } != null
            val result = if (up) ClipBridge.send(this@ClipSendActivity, manual = true) else ClipBridge.Result.OFFLINE
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

    override fun onDestroy() {
        Live.release(TAG)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "clip-send"
    }
}
