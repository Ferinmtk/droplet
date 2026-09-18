package dev.droplet.app

import android.content.Intent
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
 *
 * Or, aimed at "Bluetooth: <computer>", the same keys go out as a Bluetooth
 * keyboard (BtHid): nothing on the computer, and no hub or Wi-Fi needed.
 */
class RemoteActivity : AppCompatActivity() {
    private lateinit var b: ActivityRemoteBinding
    private val main = Handler(Looper.getMainLooper())

    private var names: Map<String, String> = emptyMap()
    private var loadingNames = false
    private var candidates: List<String> = emptyList()
    /** A droplet device id, or [Prefs.BT_TARGET] and a Bluetooth address. */
    private var target: String? = Prefs.remoteTarget
    private var live = Live.Snapshot()

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
        b.bluetooth.setOnClickListener { startActivity(Intent(this, BluetoothActivity::class.java)) }
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
                launch { Live.state.collect { live = it; render() } }
                launch { BtHid.state.collect { render() } }
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
        btHost()?.let { useBluetooth(it) }
        if (timerRunning) main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        Live.release(TAG)
        BtHid.release(BT_TAG)
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
        btHost()?.let { return pressBluetooth(it, key) }
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

    /** The key as a Bluetooth keyboard press, once [host] is connected; until then, why not. */
    private fun pressBluetooth(host: HidHost, key: String): Boolean {
        val s = BtHid.state.value
        if (s.connected && s.host?.address == host.address) {
            if (BtHid.controller.keyboard.key(key)) return true
        }
        useBluetooth(host)
        val now = BtHid.state.value
        showStatus(if (now.phase in BT_TROUBLE) getString(R.string.remote_bt_problem)
            else getString(R.string.remote_bt_connecting, host.name), bad = true)
        return false
    }

    /** Keeps the phone a Bluetooth keyboard while this is open, connected to [host]. */
    private fun useBluetooth(host: HidHost) {
        BtHid.hold(BT_TAG)
        BtHid.controller.connect(host)
    }

    /** The Bluetooth host the remote is aimed at, if it's aimed at one. */
    private fun btHost(): HidHost? {
        val addr = target?.takeIf { it.startsWith(Prefs.BT_TARGET) }?.removePrefix(Prefs.BT_TARGET) ?: return null
        val name = BtHid.state.value.host?.takeIf { it.address == addr }?.name
            ?: Prefs.btLastHostName?.takeIf { Prefs.btLastHost == addr }
            ?: BtHid.controller.bonded().firstOrNull { it.address == addr }?.name
            ?: addr
        return HidHost(addr, name)
    }

    // --- target ---------------------------------------------------------------------

    private fun render() {
        val s = live
        candidates = s.peers.values.filter { it.id != s.deviceId && "input" in it.caps }.map { it.id }.sorted()
        val bt = btHost()
        if (bt == null && target !in candidates) {
            target = candidates.firstOrNull { it == Prefs.remoteTarget } ?: candidates.firstOrNull()
        }
        if (candidates.any { it !in names }) loadNames()
        if (bt != null) return renderBluetooth(bt)

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
        showReady(t != null)
    }

    private fun renderBluetooth(host: HidHost) {
        val hid = BtHid.state.value
        val connected = hid.connected && hid.host?.address == host.address
        b.liveDot.setBackgroundResource(when {
            connected -> R.drawable.r_dot_on
            hid.phase in BT_TROUBLE -> R.drawable.r_dot_bad
            else -> R.drawable.r_dot
        })
        b.live.text = if (connected) getString(R.string.remote_bt_live) else getString(hid.phase.label())
        b.target.text = getString(R.string.remote_bt_target, host.name)
        b.target.setTextColor(ContextCompat.getColor(this, if (hid.phase in BT_TROUBLE) R.color.r_coral else R.color.r_text))
        showReady(connected)
    }

    private fun showReady(ready: Boolean) {
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
                render()
            }
        }
    }

    /**
     * Computers running droplet's helper, then paired Bluetooth devices, then
     * the way to set Bluetooth up (or pair another computer).
     */
    private fun pickTarget() {
        if (candidates.isEmpty()) {
            showStatus(getString(if (Live.state.value.connected) R.string.remote_none_help else R.string.remote_offline), bad = true)
        }
        val bonded = BtHid.controller.bonded()
        val ids = candidates + bonded.map { Prefs.BT_TARGET + it.address }
        val labels = candidates.map { names[it] ?: it } +
            bonded.map { getString(R.string.remote_bt_target, it.name) } +
            getString(if (bonded.isEmpty()) R.string.remote_bt_setup else R.string.remote_bt_pair)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_pick)
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(target)) { d, i ->
                d.dismiss()
                if (i >= ids.size) {
                    startActivity(Intent(this, BluetoothActivity::class.java))
                    return@setSingleChoiceItems
                }
                target = ids[i]
                Prefs.remoteTarget = target
                val bt = btHost()
                if (bt != null) useBluetooth(bt) else BtHid.release(BT_TAG)
                render()
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
        private const val BT_TAG = "remote-bt"
        private val BT_TROUBLE = setOf(
            HidController.Phase.NO_PERMISSION, HidController.Phase.BLUETOOTH_OFF, HidController.Phase.UNSUPPORTED,
            HidController.Phase.REFUSED, HidController.Phase.OLD_ANDROID, HidController.Phase.NO_ADAPTER,
        )
    }
}
