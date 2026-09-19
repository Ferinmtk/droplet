package dev.droplet.app.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.droplet.app.Discovery
import dev.droplet.app.R
import dev.droplet.app.databinding.ActivityTvPairBinding
import dev.droplet.app.padForSystemBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing the phone with a TV: "Find my TV" lists the Android / Google TVs
 * announcing themselves on the Wi-Fi (or takes an address), then the TV
 * shows a code and it's typed here. Clear words for each way it goes wrong:
 * the TV off or elsewhere, a mistyped code (the TV's code stays up for
 * another try), the TV turning the code down or its screen closing.
 *
 * Also the way back when a TV forgot droplet: [again] goes straight to
 * asking that TV for a new code.
 */
class TvPairActivity : AppCompatActivity() {
    private lateinit var b: ActivityTvPairBinding
    private var browse: Discovery.Handle? = null
    private var found: List<Tv.Found> = emptyList()
    private var busy = false

    /** The pairing in progress; kept across a rotation, closed when the screen is left. */
    private class Session(
        val pairing: TvPairing,
        val host: String,
        val port: Int,
        val name: String,
        val mac: String?,
        val mdns: String?,
        val started: Long = SystemClock.elapsedRealtime(),
        var tries: Int = 0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityTvPairBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = true)

        b.back.setOnClickListener { finish() }
        b.connect.setOnClickListener { byAddress() }
        b.ip.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { byAddress(); true } else false
        }
        b.pair.setOnClickListener { finishPairing() }
        b.code.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { finishPairing(); true } else false
        }
        b.code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val up = s.toString().uppercase()
                if (up != s.toString()) s?.replace(0, s.length, up)
                b.codeError.visibility = View.GONE
            }
        })
        b.startAgain.setOnClickListener { lastTarget?.invoke() ?: showFind() }
        b.openRemote.setOnClickListener {
            startActivity(TvActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        val s = session
        when {
            // back from a rotation with a code on the TV
            s != null && savedInstanceState != null -> showCode(s, error = null)
            intent.getStringExtra(EXTRA_HOST) != null && savedInstanceState == null -> {
                val host = intent.getStringExtra(EXTRA_HOST)!!
                start(host, intent.getIntExtra(EXTRA_PORT, TvCatalog.API_PORT), intent.getStringExtra(EXTRA_NAME) ?: host,
                    intent.getStringExtra(EXTRA_MAC), intent.getStringExtra(EXTRA_MDNS))
            }
            else -> showFind()
        }
    }

    override fun onDestroy() {
        browse?.stop()
        browse = null
        // leaving pairing (not rotating): the TV closes its code screen
        if (isFinishing) endSession()
        super.onDestroy()
    }

    // --- find ----------------------------------------------------------------------------------

    private fun show(step: View) {
        for (v in listOf(b.stepFind, b.stepBusy, b.stepCode, b.stepDone)) v.visibility = if (v === step) View.VISIBLE else View.GONE
    }

    private fun showFind(error: String? = null) {
        show(b.stepFind)
        b.findError.text = error
        b.findError.visibility = if (error != null) View.VISIBLE else View.GONE
        if (browse == null) {
            browse = runCatching { Tv.browse(this) { list -> found = list; renderFound() } }.getOrNull()
            b.foundNone.postDelayed({ if (!isDestroyed) renderFound(settled = true) }, NONE_FOUND_MS)
        }
        renderFound()
    }

    private fun renderFound(settled: Boolean = false) {
        val paired = Tv.tvs()
        b.found.removeAllViews()
        for (f in found.sortedBy { it.name.lowercase() }) {
            val already = paired.any { (it.mdns != null && it.mdns == f.service) || (it.mac != null && it.mac == f.mac) || it.host == f.host }
            b.found.addView(row(f.name, if (already) "${f.host} · ${getString(R.string.tv_find_paired)}" else f.host) {
                start(f.host, f.port, f.name, f.mac, f.service)
            })
        }
        if (settled || b.foundNone.visibility == View.VISIBLE) b.foundNone.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(name: String, sub: String, onClick: () -> Unit): View {
        val px = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@TvPairActivity, R.drawable.r_card)
            foreground = ContextCompat.getDrawable(this@TvPairActivity, R.drawable.tv_tile_ripple)
            minimumHeight = (68 * px).toInt()
            setPadding((16 * px).toInt(), (10 * px).toInt(), (12 * px).toInt(), (10 * px).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@TvPairActivity).apply {
                setImageResource(R.drawable.tv_ic_tv)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@TvPairActivity, R.color.r_accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((26 * px).toInt(), (26 * px).toInt()))
            addView(LinearLayout(this@TvPairActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@TvPairActivity).apply {
                    text = name
                    textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@TvPairActivity, R.color.r_text))
                })
                addView(TextView(this@TvPairActivity).apply {
                    text = sub
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@TvPairActivity, R.color.r_dim))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (14 * px).toInt() })
            addView(ImageView(this@TvPairActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((24 * px).toInt(), (24 * px).toInt()))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * px).toInt()
            }
        }
    }

    private fun byAddress() {
        val typed = b.ip.text.toString().trim()
        if (typed.isEmpty() || busy) return
        hideKeyboard()
        busy = true
        lifecycleScope.launch {
            val host = withContext(Dispatchers.IO) { runCatching { TvCatalog.checkHost(typed) } }
            busy = false
            host.onSuccess { h ->
                // it may be one mDNS has already found: keep its name and MAC
                val f = found.firstOrNull { it.host == h }
                start(h, f?.port ?: TvCatalog.API_PORT, f?.name ?: h, f?.mac, f?.service)
            }.onFailure { showFind(it.message) }
        }
    }

    // --- pairing ----------------------------------------------------------------------------------

    /** Asks the same TV again ("Start again"), after the pairing with it ended. */
    private var lastTarget: (() -> Unit)? = null

    private fun start(host: String, port: Int, name: String, mac: String?, mdns: String?) {
        lastTarget = { start(host, port, name, mac, mdns) }
        endSession()
        show(b.stepBusy)
        b.busyText.text = getString(R.string.tv_asking, name)
        busy = true
        lifecycleScope.launch {
            var opened: TvPairing? = null
            var kept = false
            try {
                val started = withContext(Dispatchers.IO) {
                    TvPairing(Tv.identity(), host, port, Tv.clientName()).also { opened = it }.start()
                }
                // mDNS knows the TV by the name its owner gave it; the certificate's is the model's
                val shown = if (mdns != null || name != host) name else started.serverName ?: started.certName ?: host
                val s = Session(opened!!, host, port, shown, started.mac ?: mac, mdns)
                session = s
                kept = true
                showCode(s, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFind(when {
                    e is TvException && e.kind == TvException.Kind.UNREACHABLE -> getString(R.string.tv_err_unreachable, host)
                    e is TvException && e.kind == TvException.Kind.PROTOCOL -> getString(R.string.tv_err_not_tv, host)
                    e is TvException -> getString(R.string.tv_err_not_started)
                    else -> getString(R.string.tv_err_identity, e.message.orEmpty())
                })
            } finally {
                busy = false
                // the screen closed while the TV was being asked: close its code screen too
                if (!kept) opened?.let { p -> Tv.io.execute { p.close() } }
            }
        }
    }

    private fun showCode(s: Session, error: String?, over: Boolean = false) {
        show(b.stepCode)
        b.codeBody.text = getString(R.string.tv_code_body, s.name)
        b.codeError.text = error
        b.codeError.visibility = if (error != null) View.VISIBLE else View.GONE
        b.pair.visibility = if (over) View.GONE else View.VISIBLE
        b.code.isEnabled = !over
        b.startAgain.visibility = if (over) View.VISIBLE else View.GONE
        if (!over) {
            b.code.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(b.code, 0)
        }
    }

    private fun finishPairing() {
        val s = session ?: return showFind()
        if (busy) return
        val code = try {
            TvCatalog.checkCode(b.code.text.toString())
        } catch (e: TvCatalog.Invalid) {
            showCode(s, getString(R.string.tv_err_code_format))
            return
        }
        if (SystemClock.elapsedRealtime() - s.started > PAIR_TTL_MS) {
            endSession()
            showCode(s, getString(R.string.tv_err_expired), over = true)
            return
        }
        busy = true
        b.pair.isEnabled = false
        b.pair.setText(R.string.tv_pairing)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (val done = s.pairing.finish(code)) {
                        TvPairing.Result.WrongCode -> null
                        is TvPairing.Result.Paired -> Tv.adopt(s.host, s.port, s.name, s.mac, s.mdns, done.serverCertDer)
                    }
                }
            }
            busy = false
            b.pair.isEnabled = true
            b.pair.setText(R.string.tv_pair)
            if (isDestroyed) return@launch
            r.onSuccess { tv ->
                if (tv == null) {
                    s.tries++
                    if (s.tries >= MAX_TRIES) {
                        endSession()
                        showCode(s, getString(R.string.tv_err_wrong_code_last), over = true)
                    } else {
                        b.code.selectAll()
                        showCode(s, getString(R.string.tv_err_wrong_code))
                    }
                } else {
                    session = null
                    hideKeyboard()
                    b.doneTitle.text = getString(R.string.tv_paired_title, tv.name)
                    show(b.stepDone)
                }
            }.onFailure {
                endSession()
                showCode(s, getString(R.string.tv_err_refused), over = true)
            }
        }
    }

    private fun endSession() {
        val s = session ?: return
        session = null
        Tv.io.execute { s.pairing.close() }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_MAC = "mac"
        const val EXTRA_MDNS = "mdns"
        /** Open on "Find my TV" even when TVs are paired already. */
        const val EXTRA_FIND = "find"
        private const val PAIR_TTL_MS = 300_000L
        private const val MAX_TRIES = 5
        private const val NONE_FOUND_MS = 6_000L

        /** One pairing at a time, for the whole app. */
        @Volatile private var session: Session? = null

        /** Pair [tv] again: straight to asking it for a new code. */
        fun again(context: Context, tv: PairedTv): Intent = Intent(context, TvPairActivity::class.java)
            .putExtra(EXTRA_HOST, tv.host).putExtra(EXTRA_PORT, tv.port).putExtra(EXTRA_NAME, tv.name)
            .putExtra(EXTRA_MAC, tv.mac).putExtra(EXTRA_MDNS, tv.mdns)
    }
}
