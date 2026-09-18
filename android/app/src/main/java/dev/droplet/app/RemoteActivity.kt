package dev.droplet.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.droplet.app.databinding.ActivityRemoteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The presentation remote: big Next / Previous buttons, and the volume keys
 * (up = next, down = previous) while it's open. Each press is an `input`
 * key event over the live connection (docs/remote.md §3.1), so it works in
 * any slide app on a computer running droplet's helper.
 */
class RemoteActivity : AppCompatActivity() {
    private lateinit var b: ActivityRemoteBinding
    private val main = Handler(Looper.getMainLooper())

    private var names: Map<String, String> = emptyMap()
    private var loadingNames = false
    private var candidates: List<String> = emptyList()
    private var target: String? = Prefs.remoteTarget

    // elapsed-time timer: running since `timerBase`, or stopped showing `timerHeld` ms
    private var timerRunning = false
    private var timerBase = 0L
    private var timerHeld = 0L
    private val tick = object : Runnable {
        override fun run() {
            showTimer()
            if (timerRunning) main.postDelayed(this, 1_000 - (elapsed() % 1_000))
        }
    }
    private val clearStatus = Runnable { showStatus(null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        // a talk can run long; the screen stays on while this is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        savedInstanceState?.let {
            timerRunning = it.getBoolean("timer_running")
            timerHeld = it.getLong("timer_held")
            timerBase = SystemClock.elapsedRealtime() - it.getLong("timer_elapsed")
        }

        b.back.setOnClickListener { finish() }
        b.targetRow.setOnClickListener { pickTarget() }
        b.next.setOnClickListener { next(it) }
        b.prev.setOnClickListener { previous(it) }
        b.start.setOnClickListener {
            if (press(it, "F5")) restartTimer()
        }
        b.black.setOnClickListener { press(it, "b") }
        b.end.setOnClickListener {
            if (press(it, "Escape")) pauseTimer()
        }
        b.timer.setOnClickListener { if (timerRunning) pauseTimer() else resumeTimer() }
        b.timer.setOnLongClickListener {
            timerRunning = false
            timerHeld = 0
            showTimer()
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        showTimer()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Live.state.collect { render(it) } }
                launch {
                    Live.events.collect { e ->
                        if (e.optString("re") == "input") showStatus(e.optString("error"), bad = true)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // brings the connection up even when Stay connected is off
        Live.hold(TAG)
        if (timerRunning) main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        Live.release(TAG)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("timer_running", timerRunning)
        outState.putLong("timer_held", timerHeld)
        outState.putLong("timer_elapsed", elapsed())
    }

    /** Volume up = next, volume down = previous, and the same for a Bluetooth clicker's Page keys. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_UP -> false
            else -> return super.dispatchKeyEvent(event)
        }
        // one slide per press: holding the key doesn't race through the deck
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val button = if (forward) b.next else b.prev
            button.isPressed = true
            main.postDelayed({ button.isPressed = false }, 120)
            if (forward) next(button) else previous(button)
        }
        return true  // the volume stays where it is
    }

    // --- keys -------------------------------------------------------------------

    private fun next(v: View) {
        if (press(v, "ArrowRight") && !timerRunning && timerHeld == 0L) restartTimer()
    }

    private fun previous(v: View) {
        press(v, "ArrowLeft")
    }

    /** Sends one key press to the chosen computer; false if it couldn't go. */
    private fun press(v: View, key: String): Boolean {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val to = target?.takeIf { it in candidates }
        if (to == null) {
            showStatus(getString(if (Live.state.value.connected) R.string.remote_no_target else R.string.remote_offline), bad = true)
            return false
        }
        val ev = JSONArray().put(JSONObject().put("k", "key").put("key", key))
        if (!Live.send(JSONObject().put("t", "input").put("to", to).put("ev", ev))) {
            showStatus(getString(R.string.remote_offline), bad = true)
            return false
        }
        return true
    }

    // --- target ---------------------------------------------------------------------

    private fun render(s: Live.Snapshot) {
        candidates = s.peers.values.filter { it.id != s.deviceId && "input" in it.caps }.map { it.id }.sorted()
        if (target !in candidates) target = candidates.firstOrNull { it == Prefs.remoteTarget } ?: candidates.firstOrNull()
        if (candidates.any { it !in names }) loadNames()

        b.liveDot.setBackgroundResource(when {
            s.connected -> R.drawable.r_dot_on
            s.status == Live.Status.CONNECTING -> R.drawable.r_dot
            else -> R.drawable.r_dot_bad
        })
        b.live.text = if (s.connected) getString(R.string.remote_live) else s.describe(this)

        val t = target
        b.target.text = when {
            t != null -> names[t] ?: getString(R.string.remote_loading)
            !s.connected -> getString(R.string.remote_offline_short)
            else -> getString(R.string.remote_none)
        }
        b.target.setTextColor(ContextCompat.getColor(this, if (t != null) R.color.r_text else R.color.r_coral))
        val ready = t != null
        listOf(b.next, b.prev, b.start, b.black, b.end).forEach { it.alpha = if (ready) 1f else 0.45f }
    }

    private fun loadNames() {
        if (loadingNames) return
        loadingNames = true
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Hub.devices() }.getOrNull() }
            loadingNames = false
            if (found != null) {
                names = found.associate { it.id to it.name }
                render(Live.state.value)
            }
        }
    }

    private fun pickTarget() {
        if (candidates.isEmpty()) {
            showStatus(getString(if (Live.state.value.connected) R.string.remote_none_help else R.string.remote_offline), bad = true)
            return
        }
        val labels = candidates.map { names[it] ?: it }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_pick)
            .setSingleChoiceItems(labels, candidates.indexOf(target)) { d, i ->
                target = candidates[i]
                Prefs.remoteTarget = target
                render(Live.state.value)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- timer and status -----------------------------------------------------------

    private fun elapsed() = if (timerRunning) SystemClock.elapsedRealtime() - timerBase else timerHeld

    private fun restartTimer() {
        timerHeld = 0
        timerBase = SystemClock.elapsedRealtime()
        timerRunning = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun pauseTimer() {
        if (!timerRunning) return
        timerHeld = elapsed()
        timerRunning = false
        showTimer()
    }

    private fun resumeTimer() {
        timerBase = SystemClock.elapsedRealtime() - timerHeld
        timerRunning = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun showTimer() {
        val s = elapsed() / 1000
        b.timer.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        b.timer.setTextColor(ContextCompat.getColor(this, if (timerRunning) R.color.r_text else R.color.r_dim))
    }

    private fun showStatus(text: String?, bad: Boolean = false) {
        main.removeCallbacks(clearStatus)
        b.status.text = text ?: getString(R.string.remote_keys_hint)
        b.status.setTextColor(ContextCompat.getColor(this, if (text != null && bad) R.color.r_coral else R.color.r_dim))
        if (text != null) main.postDelayed(clearStatus, 4_000)
    }

    companion object {
        private const val TAG = "remote"
    }
}
