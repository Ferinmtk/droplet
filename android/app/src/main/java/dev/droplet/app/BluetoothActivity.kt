package dev.droplet.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.droplet.app.HidController.Phase
import dev.droplet.app.databinding.ActivityBluetoothBinding
import kotlinx.coroutines.launch

/**
 * The phone as a Bluetooth mouse and keyboard for a computer or TV (BtHid):
 * getting connected, then a touchpad, a keyboard and media keys. Nothing on
 * the other side but its own Bluetooth.
 */
class BluetoothActivity : AppCompatActivity() {
    private lateinit var b: ActivityBluetoothBinding
    private val main = Handler(Looper.getMainLooper())
    private val hid get() = BtHid.controller
    private lateinit var gestures: TouchpadGestures

    private var tab = R.id.tab_pad
    private var visibleUntil = 0L
    private var bondReceiver: BroadcastReceiver? = null

    // the typing field: what the host has been sent, so only changes go out
    private var sent = SENTINEL
    private var syncing = false

    // sticky modifiers: 0 off, 1 for the next key, 2 held until tapped again
    private val modState = IntArray(MODS.size)
    private val modTapped = LongArray(MODS.size)
    private val modButtons = arrayOfNulls<MaterialButton>(MODS.size)

    private val clearStatus = Runnable { showStatus(null) }
    private val tickVisible = object : Runnable {
        override fun run() = showVisible()
    }
    private val hideMode = Runnable { b.padMode.visibility = View.GONE }

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BtHid.permitted(this)) {
            hid.retry()
            watchBonds(true)
            refreshHosts()
        } else if (BtHid.PERMISSIONS.none { p -> shouldShowRequestPermissionRationale(p) }) {
            // "don't ask again" (or MIUI's own permission manager): the app's page is the way on
            Toast.makeText(this, R.string.bt_perm_denied, Toast.LENGTH_LONG).show()
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        render(hid.state.value)
    }

    private val askEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hid.retry()
    }

    private val askVisible = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // the answer is the number of seconds it's visible for, or "cancelled"
        if (r.resultCode > 0) {
            visibleUntil = SystemClock.elapsedRealtime() + r.resultCode * 1000L
            showVisible()
        } else {
            showStatus(getString(R.string.bt_visible_refused), bad = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityBluetoothBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = true)
        savedInstanceState?.let {
            tab = it.getInt("tab", R.id.tab_pad)
            visibleUntil = it.getLong("visible_until")
        }

        b.back.setOnClickListener { finish() }
        b.visible.setOnClickListener { makeVisible() }
        b.disconnect.setOnClickListener {
            haptic(it)
            hid.disconnect()
        }
        b.hostRow.setOnClickListener { pickHost() }

        b.tabs.check(tab)
        b.tabs.addOnButtonCheckedListener { _, id, checked -> if (checked) showTab(id) }
        showTab(tab)

        setUpTouchpad()
        setUpKeyboard()
        setUpMedia()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hid.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // the phone is a Bluetooth keyboard and mouse only while this is open (or the remote uses it)
        BtHid.hold(TAG)
        watchBonds(true)
        refreshHosts()
        showVisible()
    }

    override fun onStop() {
        gestures.cancel()
        // a Left or Right held down on screen is let go
        hid.mouse.reset()
        watchBonds(false)
        main.removeCallbacks(tickVisible)
        BtHid.release(TAG)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putLong("visible_until", visibleUntil)
    }

    // --- state ------------------------------------------------------------------------

    private fun render(s: HidController.State) {
        b.stateDot.setBackgroundResource(when (s.phase) {
            Phase.CONNECTED -> R.drawable.r_dot_on
            Phase.READY, Phase.CONNECTING, Phase.STARTING, Phase.IDLE -> R.drawable.r_dot
            else -> R.drawable.r_dot_bad
        })
        b.stateShort.setText(s.phase.label())

        val connected = s.connected
        b.controls.visibility = if (connected) View.VISIBLE else View.GONE
        b.setup.visibility = if (connected) View.GONE else View.VISIBLE
        if (connected) {
            b.hostName.text = s.host?.name
            // in use: the screen stays on, as with a real touchpad
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            gestures.cancel()
            hideKeyboard()
        }

        val pairing = s.phase == Phase.READY || s.phase == Phase.CONNECTING || s.phase == Phase.STARTING
        b.devicesLabel.visibility = if (pairing) View.VISIBLE else View.GONE
        b.devices.visibility = if (pairing) View.VISIBLE else View.GONE
        b.pairLabel.visibility = if (pairing) View.VISIBLE else View.GONE
        b.pair.visibility = if (pairing) View.VISIBLE else View.GONE
        // made visible before registering, a host wouldn't learn the phone is a keyboard
        b.visible.isEnabled = s.registered
        if (pairing) showHosts(s)
        showNotice(s)
    }

    /** The card at the top of the setup: what's going on, and the one thing to do about it. */
    private fun showNotice(s: HidController.State) {
        var title = ""
        var body = ""
        var action: String? = null
        var onAction: (() -> Unit)? = null
        var action2: String? = null
        var onAction2: (() -> Unit)? = null
        var bad = false
        val last = lastHost()
        when (s.phase) {
            Phase.IDLE, Phase.STARTING -> {
                title = getString(R.string.bt_starting_title)
                body = getString(R.string.bt_starting_body)
            }
            Phase.OLD_ANDROID -> {
                title = getString(R.string.bt_old_title)
                body = getString(R.string.bt_old_body)
                bad = true
            }
            Phase.NO_ADAPTER -> {
                title = getString(R.string.bt_no_adapter_title)
                body = getString(R.string.bt_no_adapter_body)
                bad = true
            }
            Phase.NO_PERMISSION -> {
                title = getString(R.string.bt_perm_title)
                body = getString(R.string.bt_perm_body)
                action = getString(R.string.bt_perm_button)
                onAction = { askPermission.launch(BtHid.PERMISSIONS) }
            }
            Phase.BLUETOOTH_OFF -> {
                title = getString(R.string.bt_off_title)
                body = getString(R.string.bt_off_body)
                action = getString(R.string.bt_off_button)
                onAction = { turnOn() }
            }
            Phase.UNSUPPORTED -> {
                title = getString(R.string.bt_unsupported_title)
                body = getString(R.string.bt_unsupported_body)
                action2 = getString(R.string.bt_try_again)
                onAction2 = { hid.retry() }
                bad = true
            }
            Phase.REFUSED -> {
                title = getString(R.string.bt_refused_title)
                body = getString(R.string.bt_refused_body)
                action = getString(R.string.bt_try_again)
                onAction = { hid.retry() }
                bad = true
            }
            Phase.READY -> {
                val tried = hid.lastTried
                if (s.problem != null && tried != null) {
                    title = getString(if (s.problem == HidController.Problem.NO_ANSWER) R.string.bt_no_answer_title else R.string.bt_failed_title, tried.name)
                    body = getString(R.string.bt_failed_body)
                    action = getString(R.string.bt_try_again)
                    onAction = { hid.connect(tried) }
                    bad = true
                } else if (last != null) {
                    title = getString(R.string.bt_ready_title)
                    body = getString(R.string.bt_ready_body)
                    action = getString(R.string.bt_reconnect, last.name)
                    onAction = { hid.connect(last) }
                } else {
                    title = getString(R.string.bt_ready_new_title)
                    body = getString(R.string.bt_ready_new_body)
                }
            }
            Phase.CONNECTING -> {
                title = getString(R.string.bt_connecting_title, s.host?.name ?: getString(R.string.bt_this_device))
                body = getString(R.string.bt_connecting_body)
                action2 = getString(R.string.cancel)
                onAction2 = { hid.disconnect() }
            }
            Phase.CONNECTED -> Unit
        }
        b.notice.setBackgroundResource(if (bad) R.drawable.r_banner else R.drawable.r_card)
        b.noticeTitle.text = title
        b.noticeBody.text = body
        b.noticeAction.text = action
        b.noticeAction.visibility = if (action != null) View.VISIBLE else View.GONE
        b.noticeAction.setOnClickListener { v -> haptic(v); onAction?.invoke() }
        b.noticeAction2.text = action2
        b.noticeAction2.visibility = if (action2 != null) View.VISIBLE else View.GONE
        b.noticeAction2.setOnClickListener { v -> haptic(v); onAction2?.invoke() }
    }

    // --- hosts ------------------------------------------------------------------------

    private var hosts: List<HidHost> = emptyList()

    private fun refreshHosts() {
        hosts = hid.bonded()
        val s = hid.state.value
        if (s.phase == Phase.READY || s.phase == Phase.CONNECTING || s.phase == Phase.STARTING) showHosts(s)
        showNotice(s)
    }

    /** The last host, if it's still paired. */
    private fun lastHost(): HidHost? {
        val addr = Prefs.btLastHost ?: return null
        return hosts.firstOrNull { it.address == addr }
    }

    private fun showHosts(s: HidController.State) {
        b.devices.removeAllViews()
        if (hosts.isEmpty()) {
            val empty = TextView(this).apply {
                setText(R.string.bt_paired_none)
                setTextColor(ContextCompat.getColor(context, R.color.r_dim))
                textSize = 14f
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            b.devices.addView(empty)
            return
        }
        val last = Prefs.btLastHost
        // the last one used goes first
        for (h in hosts.sortedByDescending { it.address == last }) {
            val row = b.devices.inflate(R.layout.item_bt_host)
            row.findViewById<TextView>(R.id.name).text = h.name
            row.findViewById<TextView>(R.id.sub).text =
                if (s.phase == Phase.CONNECTING && s.host?.address == h.address) getString(R.string.bt_short_connecting) else h.address
            row.findViewById<View>(R.id.last).visibility = if (h.address == last) View.VISIBLE else View.GONE
            row.setOnClickListener {
                haptic(it)
                hid.connect(h)
            }
            b.devices.addView(row)
        }
    }

    /** Connected: tapping the host switches to another paired one. */
    private fun pickHost() {
        refreshHosts()
        if (hosts.isEmpty()) return
        val current = hid.state.value.host?.address
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_pick_host)
            .setSingleChoiceItems(hosts.map { it.name }.toTypedArray(), hosts.indexOfFirst { it.address == current }) { d, i ->
                hid.connect(hosts[i])
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun watchBonds(on: Boolean) {
        bondReceiver?.let { runCatching { unregisterReceiver(it) } }
        bondReceiver = null
        if (!on || !BtHid.permitted(this)) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refreshHosts()
        }
        // a system broadcast: no other app can send it
        ContextCompat.registerReceiver(this, r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        bondReceiver = r
    }

    // --- Bluetooth on, and visible ------------------------------------------------------------

    private fun turnOn() {
        try {
            askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            // some phones (MIUI) refuse the request screen: their own Bluetooth page does it
            tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun makeVisible() {
        if (!BtHid.permitted(this)) {
            askPermission.launch(BtHid.PERMISSIONS)
            return
        }
        try {
            askVisible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, VISIBLE_SECONDS))
        } catch (e: Exception) {
            tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun showVisible() {
        main.removeCallbacks(tickVisible)
        val left = (visibleUntil - SystemClock.elapsedRealtime()) / 1000
        if (left <= 0) {
            b.visibleState.visibility = View.GONE
            return
        }
        b.visibleState.text = getString(R.string.bt_visible_now, phoneName(), "%d:%02d".format(left / 60, left % 60))
        b.visibleState.visibility = View.VISIBLE
        main.postDelayed(tickVisible, 1_000)
    }

    /** The name the computer or TV lists this phone under. */
    @SuppressLint("MissingPermission")  // only called with the permission (see makeVisible)
    private fun phoneName(): String = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter?.name
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.bt_visible_this_phone)

    // --- tabs -------------------------------------------------------------------------

    private fun showTab(id: Int) {
        tab = id
        b.padPanel.visibility = if (id == R.id.tab_pad) View.VISIBLE else View.GONE
        b.keysPanel.visibility = if (id == R.id.tab_keys) View.VISIBLE else View.GONE
        b.mediaPanel.visibility = if (id == R.id.tab_media) View.VISIBLE else View.GONE
        if (id != R.id.tab_pad && ::gestures.isInitialized) gestures.cancel()
        if (id != R.id.tab_keys) hideKeyboard()
    }

    // --- touchpad ---------------------------------------------------------------------

    private fun setUpTouchpad() {
        gestures = TouchpadGestures(1f / resources.displayMetrics.density, object : TouchpadGestures.Sink {
            override fun move(dx: Float, dy: Float) {
                hid.mouse.move(dx, dy)
            }

            override fun button(button: Int, down: Boolean) {
                hid.mouse.button(button, down)
            }

            override fun click(button: Int, times: Int) {
                hid.mouse.click(button, times)
            }

            override fun scroll(vertical: Float, horizontal: Float) {
                hid.mouse.scroll(vertical, horizontal)
            }

            override fun feedback(what: TouchpadGestures.Feedback) {
                when (what) {
                    TouchpadGestures.Feedback.TAP -> b.pad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    TouchpadGestures.Feedback.RIGHT_CLICK -> padMode(R.string.bt_pad_right, HapticFeedbackConstants.KEYBOARD_TAP)
                    TouchpadGestures.Feedback.MIDDLE_CLICK -> padMode(R.string.bt_pad_middle, HapticFeedbackConstants.KEYBOARD_TAP)
                    TouchpadGestures.Feedback.DRAG_START -> padMode(R.string.bt_pad_dragging, HapticFeedbackConstants.LONG_PRESS, hold = true)
                    TouchpadGestures.Feedback.SCROLL_START -> padMode(R.string.bt_pad_scrolling, null)
                    TouchpadGestures.Feedback.DRAG_END -> main.post(hideMode)
                }
            }
        })
        b.pad.gestures = gestures
        holdable(b.left, HidReports.BUTTON_LEFT)
        holdable(b.right, HidReports.BUTTON_RIGHT)
    }

    private fun padMode(text: Int, haptic: Int?, hold: Boolean = false) {
        haptic?.let { b.pad.performHapticFeedback(it) }
        main.removeCallbacks(hideMode)
        b.padMode.setText(text)
        b.padMode.setTextColor(ContextCompat.getColor(this, if (hold) R.color.r_coral else R.color.r_accent))
        b.padMode.visibility = View.VISIBLE
        if (!hold) main.postDelayed(hideMode, 900)
    }

    /** Left and Right press while touched and let go when released, so holding Left and sliding on the pad drags. */
    @SuppressLint("ClickableViewAccessibility")  // a click (press and release) also comes through performClick
    private fun holdable(v: MaterialButton, button: Int) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    haptic(view)
                    hid.mouse.button(button, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    hid.mouse.button(button, false)
                }
            }
            true
        }
        // TalkBack and keyboards "click" without touching: a whole click then
        v.setOnClickListener { hid.mouse.click(button) }
    }

    // --- keyboard -----------------------------------------------------------------------

    private fun setUpKeyboard() {
        val dp = resources.displayMetrics.density
        MODS.forEachIndexed { i, m ->
            val btn = key(getString(m.label)) { v -> tapMod(i); haptic(v) }
            modButtons[i] = btn
            b.mods.addView(btn, weighted(1f, i > 0))
        }
        paintMods()

        row(b.keys, listOf(
            Key("Esc", "Escape"), Key("Tab", "Tab"), Key("⌫", "Backspace"), Key("Enter", "Enter", accent = true),
        ))
        row(b.keys, listOf(Key("Home", "Home"), Key("↑", "ArrowUp"), Key("End", "End"), Key("PgUp", "PageUp")))
        row(b.keys, listOf(Key("←", "ArrowLeft"), Key("↓", "ArrowDown"), Key("→", "ArrowRight"), Key("PgDn", "PageDown")))
        row(b.keys, listOf(Key("Del", "Delete"), Key("Space", "Space", weight = 2f), Key("Menu", "ContextMenu")))

        for (n in 1..12) {
            val btn = key("F$n") { v -> sendKey("F$n", v) }
            val lp = LinearLayout.LayoutParams((60 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            if (n > 1) lp.marginStart = (6 * dp).toInt()
            b.fkeys.addView(btn, lp)
        }

        val shortcuts = listOf(
            Shortcut(R.string.bt_copy, "c", HidKeys.MOD_CTRL),
            Shortcut(R.string.bt_paste, "v", HidKeys.MOD_CTRL),
            Shortcut(R.string.bt_cut, "x", HidKeys.MOD_CTRL),
            Shortcut(R.string.bt_undo, "z", HidKeys.MOD_CTRL),
            Shortcut(R.string.bt_select_all, "a", HidKeys.MOD_CTRL),
            Shortcut(R.string.bt_alt_tab, "Tab", HidKeys.MOD_ALT),
            Shortcut(R.string.bt_close_window, "F4", HidKeys.MOD_ALT),
            Shortcut(R.string.bt_start_menu, null, HidKeys.MOD_META),
        )
        for (chunk in shortcuts.chunked(4)) {
            val line = rowLayout(b.shortcuts)
            chunk.forEachIndexed { i, sc ->
                line.addView(key(getString(sc.label)) { v ->
                    val mods = sc.mods or useMods()
                    val ok = if (sc.key == null) hid.keyboard.modifiers(mods) else hid.keyboard.key(sc.key, mods)
                    afterKey(ok, v)
                }.apply { textSize = 13f }, weighted(1f, i > 0))
            }
        }

        b.typing.setText(SENTINEL)
        b.typing.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) syncTyping()
            }
        })
        // typing always goes on at the end, as it does on the other screen
        b.typing.setOnClickListener { b.typing.setSelection(b.typing.length()) }
    }

    private data class Key(val label: String, val name: String, val weight: Float = 1f, val accent: Boolean = false)
    private data class Shortcut(val label: Int, val key: String?, val mods: Int)

    private fun row(container: LinearLayout, keys: List<Key>) {
        val line = rowLayout(container)
        keys.forEachIndexed { i, k ->
            val btn = key(k.label) { v -> sendKey(k.name, v) }
            if (k.accent) accent(btn)
            line.addView(btn, weighted(k.weight, i > 0))
        }
    }

    private fun rowLayout(container: LinearLayout): LinearLayout {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (6 * resources.displayMetrics.density).toInt()
        container.addView(line, lp)
        return line
    }

    private fun weighted(weight: Float, gap: Boolean) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            if (gap) marginStart = (6 * resources.displayMetrics.density).toInt()
        }

    private fun key(label: String, onClick: (View) -> Unit): MaterialButton =
        MaterialButton(ContextThemeWrapper(this, R.style.ThemeOverlay_Droplet_Key)).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick(it) }
        }

    private fun accent(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.r_accent))
        btn.setTextColor(ContextCompat.getColor(this, R.color.r_on_accent))
        btn.strokeWidth = 0
    }

    private fun sendKey(name: String, v: View) {
        afterKey(hid.keyboard.key(name, useMods()), v)
    }

    private fun afterKey(ok: Boolean, v: View) {
        haptic(v)
        if (!ok) showStatus(getString(R.string.bt_not_connected), bad = true)
    }

    private fun tapMod(i: Int) {
        val now = SystemClock.uptimeMillis()
        modState[i] = when {
            modState[i] == 1 && now - modTapped[i] < DOUBLE_TAP_MS -> 2
            modState[i] != 0 -> 0
            else -> 1
        }
        modTapped[i] = now
        paintMods()
    }

    private fun stickyMods(): Int = MODS.indices.filter { modState[it] != 0 }.fold(0) { m, i -> m or MODS[i].bit }

    /** The sticky modifiers for this key; the once-only ones are used up. */
    private fun useMods(): Int {
        val mods = stickyMods()
        var changed = false
        for (i in MODS.indices) if (modState[i] == 1) {
            modState[i] = 0
            changed = true
        }
        if (changed) paintMods()
        return mods
    }

    private fun paintMods() {
        MODS.indices.forEach { i ->
            val btn = modButtons[i] ?: return@forEach
            val (bg, fg, stroke) = when (modState[i]) {
                2 -> Triple(R.color.r_accent, R.color.r_on_accent, R.color.r_accent)
                1 -> Triple(R.color.r_accent_soft, R.color.r_accent, R.color.r_accent)
                else -> Triple(R.color.r_card_2, R.color.r_text, R.color.r_line_2)
            }
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
            btn.setTextColor(ContextCompat.getColor(this, fg))
            btn.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, stroke))
            val label = getString(MODS[i].label)
            btn.text = if (modState[i] == 2) "$label •" else label
            btn.contentDescription = if (modState[i] == 2) getString(R.string.bt_mod_locked, label) else label
            btn.isSelected = modState[i] != 0
        }
    }

    /** Sends what changed in the typing field: backspaces for what went, key presses for what came. */
    private fun syncTyping() {
        val now = b.typing.text.toString()
        val change = TypingDiff.between(sent, now)
        if (change.erase == 0 && change.type.isEmpty()) return
        val kb = hid.keyboard
        repeat(change.erase) { kb.tap(HidKeys.BACKSPACE) }
        val keep = sent.codePointCount(0, sent.length) - change.erase
        val prefix = now.substring(0, now.offsetByCodePoints(0, keep))
        val typed = change.type.replace(SENTINEL, "")
        var kept = prefix + typed
        if (typed.isNotEmpty()) {
            val mods = stickyMods()
            if (mods != 0) {
                // a modifier is waiting: the characters are shortcuts, not text
                for (c in typed) {
                    val stroke = HidKeys.forChar(if (c.isLetter()) c.lowercaseChar() else c) ?: continue
                    kb.tap(stroke.usage, stroke.mods or mods)
                }
                useMods()
                kept = prefix
            } else {
                val skipped = kb.type(typed)
                if (skipped.isNotEmpty()) {
                    showStatus(getString(R.string.bt_skipped, skipped), bad = true)
                    kept = prefix + typable(typed)
                }
            }
        }
        // after Enter, or once the line is long, start afresh (the other screen keeps it all)
        if ('\n' in kept || !kept.startsWith(SENTINEL) || kept.codePointCount(0, kept.length) > MAX_FIELD) kept = SENTINEL
        sent = kept
        if (kept != now) {
            syncing = true
            b.typing.setText(kept)
            syncing = false
        }
        b.typing.setSelection(b.typing.length())
        b.typingHint.visibility = if (kept == SENTINEL) View.VISIBLE else View.GONE
    }

    private fun typable(s: String): String {
        val out = StringBuilder()
        s.codePoints().forEach { cp ->
            if (Character.isBmpCodePoint(cp) && HidKeys.forChar(cp.toChar()) != null) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun hideKeyboard() {
        if (!b.typing.hasFocus()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.typing.windowToken, 0)
        b.typing.clearFocus()
    }

    // --- media ------------------------------------------------------------------------

    private fun setUpMedia() {
        val rows = listOf(
            listOf(Media(R.string.bt_prev_track, "MediaPrevious"), Media(R.string.bt_play_pause, "MediaPlayPause", accent = true), Media(R.string.bt_next_track, "MediaNext")),
            listOf(Media(R.string.bt_vol_down, "AudioVolumeDown"), Media(R.string.bt_mute, "AudioVolumeMute"), Media(R.string.bt_vol_up, "AudioVolumeUp")),
            listOf(Media(R.string.bt_stop, "MediaStop"), Media(R.string.bt_home, "BrowserHome"), Media(R.string.bt_back, "BrowserBack")),
        )
        val tall = (64 * resources.displayMetrics.density).toInt()
        for (r in rows) {
            val line = rowLayout(b.media)
            (line.layoutParams as LinearLayout.LayoutParams).topMargin = (10 * resources.displayMetrics.density).toInt()
            r.forEachIndexed { i, m ->
                val btn = key(getString(m.label)) { v ->
                    afterKey(hid.keyboard.consumer(HidKeys.CONSUMER.getValue(m.name)), v)
                }.apply { textSize = 14f }
                if (m.accent) accent(btn)
                line.addView(btn, weighted(1f, i > 0).apply { height = tall })
            }
        }
    }

    private data class Media(val label: Int, val name: String, val accent: Boolean = false)

    // --- bits ---------------------------------------------------------------------------

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun showStatus(text: String?, bad: Boolean = false) {
        main.removeCallbacks(clearStatus)
        b.status.text = text
        b.status.visibility = if (text != null) View.VISIBLE else View.GONE
        b.status.setTextColor(ContextCompat.getColor(this, if (bad) R.color.r_coral else R.color.r_dim))
        if (text != null) main.postDelayed(clearStatus, 5_000)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private data class Mod(val label: Int, val bit: Int)

    companion object {
        private const val TAG = "bluetooth"
        private const val VISIBLE_SECONDS = 120
        private const val DOUBLE_TAP_MS = 350L
        private const val MAX_FIELD = 80

        /**
         * Stands at the start of the typing field for "whatever is already on
         * the other screen": deleting it means Backspace even when the field
         * looks empty (phone keyboards send no key for that).
         */
        private const val SENTINEL = "\u200B"

        private val MODS = listOf(
            Mod(R.string.bt_mod_ctrl, HidKeys.MOD_CTRL),
            Mod(R.string.bt_mod_alt, HidKeys.MOD_ALT),
            Mod(R.string.bt_mod_shift, HidKeys.MOD_SHIFT),
            Mod(R.string.bt_mod_win, HidKeys.MOD_META),
        )
    }
}
