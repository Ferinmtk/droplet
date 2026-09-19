package dev.droplet.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.droplet.app.BluetoothActivity
import dev.droplet.app.Prefs
import dev.droplet.app.R
import dev.droplet.app.databinding.ActivityTvBinding
import dev.droplet.app.padForSystemBars
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The TV remote: the phone talks to the TV itself (Tv, TvLink), so it
 * works with the hub down. Styled like the web remote (static/tv.js) and
 * the presentation remote: a round D-pad or a touchpad, Back / Home /
 * Menu, volume and channel rockers, media keys, typing, the app catalogue
 * and the rarer keys. The phone's volume keys drive the TV's volume while
 * it's open.
 */
class TvActivity : AppCompatActivity() {
    private lateinit var b: ActivityTvBinding
    private val main = Handler(Looper.getMainLooper())
    private var tv: PairedTv? = null
    private var state = TvLink.State()
    private var waking = false
    private val appTiles = mutableMapOf<String, View>()
    private val controls = mutableListOf<View>()
    private val presser = Presser()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Tv.selected() == null) {
            // nothing paired yet: find one first
            startActivity(Intent(this, TvPairActivity::class.java))
            finish()
            return
        }
        b = ActivityTvBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = true)

        b.back.setOnClickListener { finish() }
        b.tvRow.setOnClickListener { pickTv() }
        b.power.setOnClickListener { power() }
        b.bannerAction.setOnClickListener { bannerAction() }
        b.bluetooth.setOnClickListener { startActivity(Intent(this, BluetoothActivity::class.java)) }
        b.pairAgain.setOnClickListener { pairAgain() }
        b.forget.setOnClickListener { askForget() }

        setUpStage()
        setUpNav()
        setUpSimpleVolume()
        setUpRockers()
        setUpMedia()
        setUpTyping()
        setUpApps()
        setUpMore()
        b.moreToggle.setOnClickListener {
            Prefs.tvMore = !Prefs.tvMore
            applyView()
            haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
        }
        applyView()

        b.haptics.isChecked = Prefs.tvHaptics
        b.haptics.setOnCheckedChangeListener { v, on ->
            Prefs.tvHaptics = on
            if (on) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        Tv.io.execute {
            val text = runCatching { Tv.identity() }.fold({ id ->
                getString(R.string.tv_key_storage, if (id.storage == TvIdentity.STORAGE_KEYSTORE) getString(R.string.tv_key_keystore)
                    else getString(R.string.tv_key_file, id.fallbackReason.orEmpty()))
            }, { getString(R.string.tv_err_identity, it.message.orEmpty()) })
            main.post { if (!isDestroyed) b.keyStorage.text = text }
        }

        tv = Tv.selected()
        render()
        @OptIn(ExperimentalCoroutinesApi::class)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Tv.link.flatMapLatest { it?.state ?: flowOf(TvLink.State()) }.collect {
                        state = it
                        render()
                    }
                }
                launch {
                    Tv.changes.collect {
                        tv = Tv.selected()
                        if (tv == null) {
                            startActivity(Intent(this@TvActivity, TvPairActivity::class.java))
                            finish()
                        } else render()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::b.isInitialized) Tv.hold(TAG)
    }

    override fun onStop() {
        if (::b.isInitialized) {
            presser.cancelAll()
            Tv.release(TAG)
        }
        super.onStop()
    }

    // --- the phone's volume keys drive the TV's volume ------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME_UP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
            KeyEvent.KEYCODE_VOLUME_MUTE -> "MUTE"
            else -> return super.dispatchKeyEvent(event)
        }
        // not connected: the phone's own volume, as usual
        if (!::b.isInitialized || !state.connected) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && (key != "MUTE" || event.repeatCount == 0)) {
            // holding the key repeats, as on a TV remote, without building a backlog
            send(key, repeat = event.repeatCount > 0, feedback = null)
        }
        return true
    }

    // --- sending ---------------------------------------------------------------------------------

    private fun link(): TvLink? = Tv.link.value?.takeIf { it.state.value.connected }

    /** One press of [key]; false (and a word why) when the TV isn't connected. */
    private fun send(key: String, repeat: Boolean = false, feedback: View?): Boolean {
        val l = link()
        if (l == null) {
            Tv.link.value?.kick()
            if (!repeat) status(getString(R.string.tv_not_connected), bad = true)
            return false
        }
        // a held key that the TV is behind on is skipped, not queued
        if (repeat && l.backlog > 2) return true
        if (feedback != null && !repeat) haptic(feedback, HapticFeedbackConstants.KEYBOARD_TAP)
        return l.key(TvCatalog.keyCode(key))
    }

    private fun haptic(v: View, kind: Int) {
        if (Prefs.tvHaptics) v.performHapticFeedback(kind)
    }

    enum class Mode {
        /** A press on touch. */
        TAP,
        /** A press on touch, then again and again while held. */
        REPEAT,
        /** Short when let go quickly; held past [HOLD_MS], a long press that lasts until let go. */
        HOLD,
    }

    /** What touching a key sends: see [Mode]. One key at a time. */
    private inner class Presser {
        private var repeat: Runnable? = null
        private var holdTimer: Runnable? = null
        private var long: TvLink.LongPress? = null
        private var key: String? = null

        fun down(key: String, mode: Mode, v: View) {
            cancelAll()
            this.key = key
            when (mode) {
                Mode.TAP -> send(key, feedback = v)
                Mode.REPEAT -> {
                    if (!send(key, feedback = v)) return
                    val again = object : Runnable {
                        override fun run() {
                            send(key, repeat = true, feedback = v)
                            main.postDelayed(this, REPEAT_MS)
                        }
                    }
                    repeat = again
                    main.postDelayed(again, REPEAT_DELAY_MS)
                }
                Mode.HOLD -> {
                    haptic(v, HapticFeedbackConstants.KEYBOARD_TAP)
                    val timer = Runnable {
                        holdTimer = null
                        val l = link()
                        if (l == null) {
                            send(key, feedback = v)   // says why
                            return@Runnable
                        }
                        haptic(v, HapticFeedbackConstants.LONG_PRESS)
                        long = l.startLong(TvCatalog.keyCode(key))
                    }
                    holdTimer = timer
                    main.postDelayed(timer, HOLD_MS)
                }
            }
        }

        fun up(key: String, mode: Mode, v: View, cancelled: Boolean) {
            if (this.key != key) return
            repeat?.let { main.removeCallbacks(it) }
            repeat = null
            if (mode == Mode.HOLD) {
                val pending = holdTimer
                if (pending != null) {
                    main.removeCallbacks(pending)
                    holdTimer = null
                    if (!cancelled) send(key, feedback = null)   // felt already, on touch
                }
                long?.let { lp -> Tv.link.value?.endLong(lp) }
                long = null
            }
            this.key = null
        }

        fun cancelAll() {
            repeat?.let { main.removeCallbacks(it) }
            holdTimer?.let { main.removeCallbacks(it) }
            repeat = null
            holdTimer = null
            long?.let { lp -> Tv.link.value?.endLong(lp) }
            long = null
            key = null
        }
    }

    /** [v] sends [key] as [mode] says; a click with no touch (TalkBack, a keyboard) is a short press. */
    @SuppressLint("ClickableViewAccessibility")  // a click (press and release) also comes through performClick
    private fun bind(v: View, key: String, mode: Mode = Mode.TAP) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    presser.down(key, mode, view)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    presser.up(key, mode, view, cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL)
                }
            }
            true
        }
        v.setOnClickListener { send(key, feedback = it) }
        controls += v
    }

    // --- building the remote ---------------------------------------------------------------------

    private val roundTheme by lazy { ContextThemeWrapper(this, R.style.ThemeOverlay_Droplet_RoundKey) }
    private val px get() = resources.displayMetrics.density

    private fun roundKey(icon: Int, label: Int, size: Int = 56): MaterialButton = MaterialButton(roundTheme).apply {
        setIconResource(icon)
        contentDescription = getString(label)
        tooltipText = getString(label)
        layoutParams = LinearLayout.LayoutParams((size * px).toInt(), (size * px).toInt())
    }

    /** A round key with its name under it. */
    private fun captioned(key: MaterialButton, caption: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(key)
        addView(TextView(this@TvActivity).apply {
            setText(caption)
            setTextColor(ContextCompat.getColor(this@TvActivity, R.color.r_dim))
            textSize = 12f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4 * px).toInt()
        })
    }

    private fun setUpStage() {
        b.dpad.listener = object : DpadView.Listener {
            override fun down(zone: DpadView.Zone) =
                presser.down(zone.key, if (zone == DpadView.Zone.OK) Mode.HOLD else Mode.REPEAT, b.dpad)

            override fun up(zone: DpadView.Zone, cancelled: Boolean) =
                presser.up(zone.key, if (zone == DpadView.Zone.OK) Mode.HOLD else Mode.REPEAT, b.dpad, cancelled)
        }
        b.pad.listener = object : TvPadView.Listener {
            private var long: TvLink.LongPress? = null
            override fun step(key: String, repeat: Boolean) {
                send(key, repeat = repeat, feedback = null)
                haptic(b.pad, HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun tap() {
                send("DPAD_CENTER", feedback = b.pad)
            }

            override fun holdStart() {
                long = link()?.startLong(TvCatalog.keyCode("DPAD_CENTER")) ?: run { send("DPAD_CENTER", feedback = null); null }
            }

            override fun holdEnd() {
                long?.let { Tv.link.value?.endLong(it) }
                long = null
            }
        }
        controls += b.dpad
        controls += b.pad
        b.mode.check(if (Prefs.tvTouchpad) R.id.mode_pad else R.id.mode_buttons)
        b.mode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Prefs.tvTouchpad = id == R.id.mode_pad
            showMode(Prefs.tvMore && Prefs.tvTouchpad)
        }
    }

    private fun showMode(pad: Boolean) {
        b.dpad.visibility = if (pad) View.GONE else View.VISIBLE
        b.pad.visibility = if (pad) View.VISIBLE else View.GONE
    }

    private fun setUpNav() {
        val keys = listOf(
            Triple(R.drawable.tv_ic_back, R.string.tv_back, "BACK"),
            Triple(R.drawable.tv_ic_home, R.string.tv_home, "HOME"),
            Triple(R.drawable.tv_ic_menu, R.string.tv_menu, "MENU"),
        )
        for ((icon, label, key) in keys) {
            val k = roundKey(icon, label, 60)
            // Back and Home long-press, as on the TV's own remote (Home held: the quick settings on Google TV)
            bind(k, key, if (key == "MENU") Mode.TAP else Mode.HOLD)
            val cell = captioned(k, label)
            if (key == "MENU") menuCell = cell
            b.nav.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private var menuCell: View? = null

    /** The simple view's volume: down, mute and up, big, on one row. */
    private fun setUpSimpleVolume() {
        val keys = listOf(
            Triple(R.drawable.tv_ic_minus, R.string.tv_vol_down, "VOLUME_DOWN"),
            Triple(R.drawable.tv_ic_muted, R.string.tv_mute, "MUTE"),
            Triple(R.drawable.tv_ic_plus, R.string.tv_vol_up, "VOLUME_UP"),
        )
        for ((icon, label, key) in keys) {
            val k = roundKey(icon, label, 64)
            bind(k, key, if (key == "MUTE") Mode.TAP else Mode.REPEAT)
            if (key == "MUTE") muteKeys += k
            b.simpleVolume.addView(captioned(k, label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * The simple view (the default) shows only the essentials, big: the
     * D-pad, Back and Home, volume and mute, and power in the header.
     * "More buttons" adds the rest; the choice is remembered.
     */
    private fun applyView() {
        val more = Prefs.tvMore
        val full = if (more) View.VISIBLE else View.GONE
        val simple = if (more) View.GONE else View.VISIBLE
        b.mode.visibility = full
        b.rockers.visibility = full
        b.media.visibility = full
        b.moreSections.visibility = full
        menuCell?.visibility = full
        b.simpleVolume.visibility = simple
        b.moreToggle.setText(if (more) R.string.tv_show_fewer else R.string.tv_show_more)
        // the touchpad is part of the full view
        showMode(more && Prefs.tvTouchpad)
    }

    private fun setUpRockers() {
        fun rocker(container: LinearLayout, label: Int, up: Pair<Int, String>, down: Pair<Int, String>, upName: Int, downName: Int) {
            val u = roundKey(up.first, upName, 60).apply { backgroundTintList = ColorStateList.valueOf(0); strokeWidth = 0 }
            val d = roundKey(down.first, downName, 60).apply { backgroundTintList = ColorStateList.valueOf(0); strokeWidth = 0 }
            bind(u, up.second, Mode.REPEAT)
            bind(d, down.second, Mode.REPEAT)
            container.addView(u)
            container.addView(TextView(this).apply {
                setText(label)
                setTextColor(ContextCompat.getColor(this@TvActivity, R.color.r_dim))
                textSize = 12f
                letterSpacing = 0.1f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (28 * px).toInt()))
            container.addView(d)
        }
        rocker(b.volRocker, R.string.tv_vol, R.drawable.tv_ic_plus to "VOLUME_UP", R.drawable.tv_ic_minus to "VOLUME_DOWN",
            R.string.tv_vol_up, R.string.tv_vol_down)
        rocker(b.chRocker, R.string.tv_ch, R.drawable.tv_ic_up to "CHANNEL_UP", R.drawable.tv_ic_down to "CHANNEL_DOWN",
            R.string.tv_ch_up, R.string.tv_ch_down)
        val mute = roundKey(R.drawable.tv_ic_muted, R.string.tv_mute, 52)
        bind(mute, "MUTE")
        muteKeys += mute
        b.middle.addView(captioned(mute, R.string.tv_mute))
        val input = roundKey(R.drawable.tv_ic_input, R.string.tv_input, 52)
        bind(input, "TV_INPUT")
        b.middle.addView(captioned(input, R.string.tv_input), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * px).toInt() })
    }

    private val muteKeys = mutableListOf<MaterialButton>()

    private fun setUpMedia() {
        val keys = listOf(
            Triple(R.drawable.tv_ic_rew, R.string.tv_rewind, "MEDIA_REWIND"),
            Triple(R.drawable.tv_ic_prev, R.string.tv_previous, "MEDIA_PREVIOUS"),
            Triple(R.drawable.tv_ic_playpause, R.string.tv_play_pause, "MEDIA_PLAY_PAUSE"),
            Triple(R.drawable.tv_ic_next, R.string.tv_next, "MEDIA_NEXT"),
            Triple(R.drawable.tv_ic_ff, R.string.tv_fast_forward, "MEDIA_FAST_FORWARD"),
        )
        for ((icon, label, key) in keys) {
            val play = key == "MEDIA_PLAY_PAUSE"
            val k = roundKey(icon, label, if (play) 60 else 52)
            if (play) {
                k.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.r_accent))
                k.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.r_on_accent))
                k.strokeWidth = 0
            }
            // rewind and fast-forward held keep seeking, as a TV remote's do
            bind(k, key, if (key == "MEDIA_REWIND" || key == "MEDIA_FAST_FORWARD") Mode.REPEAT else Mode.TAP)
            val cell = FrameLayout(this).apply { addView(k, FrameLayout.LayoutParams(k.layoutParams).apply { gravity = Gravity.CENTER }) }
            b.media.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun wideKey(icon: Int, label: Int): MaterialButton =
        MaterialButton(ContextThemeWrapper(this, R.style.ThemeOverlay_Droplet_Key)).apply {
            setIconResource(icon)
            setText(label)
            isAllCaps = false
            textSize = 14f
        }

    private fun setUpTyping() {
        b.send.setOnClickListener { sendText() }
        b.text.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
        }
        val keys = listOf(
            Triple(R.drawable.tv_ic_del, R.string.tv_delete, "DEL"),
            Triple(R.drawable.tv_ic_enter, R.string.tv_enter, "ENTER"),
            Triple(R.drawable.tv_ic_search, R.string.tv_search, "SEARCH"),
        )
        keys.forEachIndexed { i, (icon, label, key) ->
            val k = wideKey(icon, label)
            bind(k, key, if (key == "DEL") Mode.REPEAT else Mode.TAP)
            b.typeKeys.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = (6 * px).toInt()
            })
        }
        controls += b.send
    }

    private fun sendText() {
        val text = try {
            TvCatalog.checkText(b.text.text.toString())
        } catch (e: TvCatalog.Invalid) {
            b.text.requestFocus()
            return
        }
        val l = link()
        if (l == null) {
            status(getString(R.string.tv_not_connected), bad = true)
            return
        }
        if (l.text(text)) {
            haptic(b.send, HapticFeedbackConstants.KEYBOARD_TAP)
            b.text.text = null
            status(getString(R.string.tv_typed))
        }
    }

    private fun setUpApps() {
        val letters = mapOf("netflix" to "N", "prime" to "P", "showmax" to "S", "disney" to "D+")
        val glyphs = mapOf("youtube" to R.drawable.tv_ic_app_youtube, "spotify" to R.drawable.tv_ic_app_spotify,
            "plex" to R.drawable.tv_ic_app_plex, "home" to R.drawable.tv_ic_home)
        for (app in TvCatalog.APPS) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (6 * px).toInt(), 0, (6 * px).toInt())
                isClickable = true
                isFocusable = true
                foreground = ContextCompat.getDrawable(this@TvActivity, R.drawable.tv_tile_ripple)
                contentDescription = app.name
            }
            val icon = FrameLayout(this).apply { background = ContextCompat.getDrawable(this@TvActivity, R.drawable.tv_app_tile) }
            val size = (52 * px).toInt()
            val glyph = glyphs[app.id]
            if (glyph != null) {
                icon.addView(ImageView(this).apply {
                    setImageResource(glyph)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@TvActivity, R.color.r_text))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams((26 * px).toInt(), (26 * px).toInt(), Gravity.CENTER))
            } else {
                icon.addView(TextView(this).apply {
                    text = letters[app.id]
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@TvActivity, R.color.r_text))
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            tile.addView(icon, LinearLayout.LayoutParams(size, size))
            tile.addView(TextView(this).apply {
                text = if (app.id == "home") getString(R.string.tv_home) else app.name
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@TvActivity, R.color.r_dim))
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * px).toInt()
            })
            tile.setOnClickListener { launch(app) }
            app.pkg?.let { appTiles[it] = icon }
            b.apps.addView(tile, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0   // the column's weight shares the row out evenly
            })
            controls += tile
        }
        b.openLink.setOnClickListener { openLink() }
        b.link.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { openLink(); true } else false
        }
        controls += b.openLink
    }

    private fun launch(app: TvCatalog.App) {
        val l = link()
        if (l == null) {
            status(getString(R.string.tv_not_connected), bad = true)
            return
        }
        haptic(b.apps, HapticFeedbackConstants.KEYBOARD_TAP)
        when (val what = TvCatalog.launch(app.id)) {
            is TvCatalog.Launch.Key -> l.key(TvCatalog.keyCode(what.key))
            is TvCatalog.Launch.Link -> if (l.link(what.link)) status(getString(R.string.tv_opening, app.name))
        }
    }

    private fun openLink() {
        val raw = b.link.text.toString().trim()
        if (!raw.lowercase().startsWith("https://")) {
            status(getString(R.string.tv_link_https), bad = true)
            b.link.requestFocus()
            return
        }
        val what = try {
            TvCatalog.launch(raw)
        } catch (e: TvCatalog.Invalid) {
            status(e.message, bad = true)
            return
        }
        val l = link()
        if (l == null) {
            status(getString(R.string.tv_not_connected), bad = true)
            return
        }
        if (what is TvCatalog.Launch.Link && l.link(what.link)) {
            b.link.text = null
            hideKeyboard()
            status(getString(R.string.tv_opening_link))
        }
    }

    private fun setUpMore() {
        // 1-9, then Info 0 Guide, like a phone's keypad
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("INFO", "0", "GUIDE"))
        for ((r, row) in rows.withIndex()) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (key in row) {
                val k: MaterialButton = when (key) {
                    "INFO" -> roundKey(R.drawable.tv_ic_info, R.string.tv_info, 56)
                    "GUIDE" -> roundKey(R.drawable.tv_ic_guide, R.string.tv_guide, 56)
                    else -> MaterialButton(roundTheme).apply {
                        text = key
                        textSize = 20f
                        setTextColor(ContextCompat.getColor(this@TvActivity, R.color.r_text))
                        contentDescription = getString(R.string.tv_number, key)
                        layoutParams = LinearLayout.LayoutParams((56 * px).toInt(), (56 * px).toInt())
                    }
                }
                bind(k, key)
                val cell = FrameLayout(this).apply { addView(k, FrameLayout.LayoutParams(k.layoutParams).apply { gravity = Gravity.CENTER }) }
                line.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            b.more.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = (10 * px).toInt()
            })
        }
        val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Triple(R.drawable.tv_ic_gear, R.string.tv_settings, "SETTINGS"), Triple(R.drawable.tv_ic_stop, R.string.tv_stop, "MEDIA_STOP"))
            .forEachIndexed { i, (icon, label, key) ->
                val k = wideKey(icon, label)
                bind(k, key)
                extras.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = (6 * px).toInt()
                })
            }
        b.more.addView(extras, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * px).toInt()
        })
    }

    // --- power, pairing, choosing a TV ---------------------------------------------------------------

    private fun power() {
        val l = link()
        if (l != null) {
            send("POWER", feedback = b.power)
            return
        }
        wake()
    }

    /** The TV isn't answering: Wake-on-LAN if its MAC is known, and try to connect again now. */
    private fun wake() {
        val t = tv ?: return
        Tv.link.value?.kick()
        val mac = t.mac
        if (mac == null) {
            status(getString(R.string.tv_banner_unreachable, t.name), bad = true)
            return
        }
        waking = true
        render()
        Tv.io.execute {
            TvWake.send(mac, t.host)
            main.post {
                Tv.link.value?.kick()
                main.postDelayed({
                    waking = false
                    if (!isDestroyed) {
                        render()
                        if (!state.connected) status(getString(R.string.tv_woke), bad = true)
                    }
                }, WAKE_WAIT_MS)
            }
        }
    }

    private fun needsPairing(): Boolean = tv?.paired == false ||
        state.phase == TvLink.Phase.NEEDS_PAIRING || state.phase == TvLink.Phase.IDENTITY_CHANGED

    private fun bannerAction() {
        if (needsPairing()) pairAgain() else wake()
    }

    private fun pairAgain() {
        val t = tv ?: return
        startActivity(TvPairActivity.again(this, t))
    }

    private fun pickTv() {
        val all = Tv.tvs()
        val labels = all.map { it.name } + getString(R.string.tv_pair_other)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_pick)
            .setSingleChoiceItems(labels.toTypedArray(), all.indexOfFirst { it.id == tv?.id }) { d, i ->
                d.dismiss()
                if (i >= all.size) startActivity(Intent(this, TvPairActivity::class.java).putExtra(TvPairActivity.EXTRA_FIND, true))
                else Tv.select(all[i].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askForget() {
        val t = tv ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tv_forget_title, t.name))
            .setMessage(R.string.tv_forget_body)
            .setPositiveButton(R.string.tv_forget) { _, _ -> Tv.forget(t.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- showing the state ------------------------------------------------------------------------------

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun render() {
        if (!::b.isInitialized) return
        val t = tv ?: return
        val s = state
        b.tvName.text = t.name
        val app = TvCatalog.appName(s.app)
        val forgot = needsPairing()
        b.state.text = when {
            forgot -> getString(R.string.tv_state_forgot)
            waking -> getString(R.string.tv_waking)
            s.connected && s.on == false -> getString(R.string.tv_state_standby)
            s.connected && app != null -> getString(R.string.tv_state_on_app, app)
            s.connected -> getString(R.string.tv_state_on)
            s.phase == TvLink.Phase.UNREACHABLE -> getString(R.string.tv_state_unreachable)
            else -> getString(R.string.tv_state_connecting)
        }
        b.stateDot.setBackgroundResource(when {
            s.connected -> R.drawable.r_dot_on
            forgot || s.phase == TvLink.Phase.UNREACHABLE -> R.drawable.r_dot_bad
            else -> R.drawable.r_dot
        })

        val on = s.connected && s.on == true
        b.power.iconTint = ColorStateList.valueOf(color(if (on) R.color.r_accent else R.color.r_dim))
        b.power.contentDescription = getString(if (on) R.string.tv_turn_off else R.string.tv_turn_on)
        b.power.tooltipText = b.power.contentDescription

        val v = s.volume.takeIf { s.connected }
        b.volumeRow.visibility = if (v != null && v.max > 0) View.VISIBLE else View.GONE
        if (v != null && v.max > 0) {
            b.volume.max = v.max
            b.volume.setProgressCompat(v.level.coerceIn(0, v.max), false)
            b.volumeText.text = if (v.muted) getString(R.string.tv_volume_muted) else v.level.toString()
            b.volumeIcon.setImageResource(if (v.muted) R.drawable.tv_ic_muted else R.drawable.tv_ic_vol)
            b.volumeRow.contentDescription = if (v.muted) getString(R.string.tv_volume_muted) else getString(R.string.tv_volume, v.level, v.max)
        }
        muteKeys.forEach { it.iconTint = ColorStateList.valueOf(color(if (v?.muted == true) R.color.r_coral else R.color.r_text)) }

        // the banner: pair again, or it isn't answering
        when {
            forgot -> {
                b.banner.visibility = View.VISIBLE
                b.bannerText.text = getString(if (t.lost == Tv.LOST_IDENTITY || s.phase == TvLink.Phase.IDENTITY_CHANGED)
                    R.string.tv_banner_identity else R.string.tv_banner_forgot, t.name)
                b.bannerAction.visibility = View.VISIBLE
                b.bannerAction.setText(R.string.tv_pair_again)
                b.bannerAction.isEnabled = true
            }
            s.phase == TvLink.Phase.UNREACHABLE || waking -> {
                b.banner.visibility = View.VISIBLE
                b.bannerText.text = getString(R.string.tv_banner_unreachable, t.name)
                b.bannerAction.visibility = if (t.mac != null) View.VISIBLE else View.GONE
                b.bannerAction.setText(if (waking) R.string.tv_waking else R.string.tv_turn_on)
                b.bannerAction.isEnabled = !waking
            }
            else -> b.banner.visibility = View.GONE
        }
        b.pairAgain.visibility = if (forgot) View.GONE else View.VISIBLE

        for ((pkg, icon) in appTiles) {
            icon.background = ContextCompat.getDrawable(this, if (s.connected && s.app == pkg) R.drawable.tv_app_tile_now else R.drawable.tv_app_tile)
        }
        val ready = s.connected
        controls.forEach { it.alpha = if (ready) 1f else 0.45f }
    }

    private var snack: Snackbar? = null

    private fun status(text: String?, bad: Boolean = false) {
        if (text == null) return
        snack?.dismiss()
        snack = Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(color(R.color.r_card_2))
            setTextColor(color(if (bad) R.color.r_coral else R.color.r_text))
            show()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    companion object {
        private const val TAG = "tv-remote"
        const val HOLD_MS = 550L
        const val REPEAT_DELAY_MS = 380L
        const val REPEAT_MS = 110L
        private const val WAKE_WAIT_MS = 8_000L

        fun intent(context: Context) = Intent(context, TvActivity::class.java)
    }
}
