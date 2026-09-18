package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.droplet.app.databinding.ActivitySetupBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import javax.net.ssl.SSLException

/**
 * Finding the hub and getting let in (docs/local-first.md §4), with or
 * without Tailscale:
 *
 * 1. **Find:** hubs announcing themselves on the Wi-Fi, an address typed in,
 *    or the tailnet URL.
 * 2. **Name:** what the phone is called on the hub (the device's name).
 * 3. **Wait:** on the LAN a new device must be allowed in from one of the
 *    owner's devices; the four-digit code shows on both. The hub's PIN or a
 *    link code are the other ways in.
 *
 * Also opened when the hub says `403 {"pair": true}` ([EXTRA_PAIR]) and when
 * its identity changed ([EXTRA_REPAIR]).
 */
class SetupActivity : AppCompatActivity() {
    private enum class Panel { FIND, NAME, CODE, DECLINED }

    private lateinit var b: ActivitySetupBinding
    private var panel = Panel.FIND
    private var browse: Discovery.Handle? = null
    private var found: List<Announced> = emptyList()
    private var nothingYet: Job? = null
    private var busy: Job? = null
    private var poll: Job? = null

    /** The hub being set up: how it's reached, and what it said about itself. */
    private var route: Router.Route? = null
    private var info: HubInfo? = null
    private var waiting: Pairing.Standing.Waiting? = null
    private var trustedRoute = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // droplet's dark teal, whatever the system theme
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        b.url.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { connectTyped(); true } else false
        }
        b.connect.setOnClickListener { connectTyped() }
        val tailnet = Prefs.hubUrl?.takeIf { it.startsWith("https://") } ?: DEFAULT_HUB
        b.tailscale.text = getString(R.string.setup_use_tailscale, tailnet.removePrefix("https://"))
        b.tailscale.setOnClickListener { b.url.setText(tailnet); connectTyped() }

        b.name.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { join(); true } else false
        }
        b.join.setOnClickListener { join() }
        b.nameLink.setOnClickListener { askLinkCode() }
        b.nameBack.setOnClickListener { showFind() }
        b.pin.setOnClickListener { askPin() }
        b.codeLink.setOnClickListener { askLinkCode() }
        b.codeCancel.setOnClickListener { showFind() }
        b.askAgain.setOnClickListener { showName() }
        b.declinedBack.setOnClickListener { showFind() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panel != Panel.FIND) showFind()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (intent.getBooleanExtra(EXTRA_REPAIR, false)) b.identityBanner.visibility = View.VISIBLE
        val current = Router.current()
        if (intent.getBooleanExtra(EXTRA_PAIR, false) && current != null) {
            // the hub said this phone isn't let in: straight to where it stands
            showFind()
            work(b.findError) {
                route = current
                info = io { Pairing.info(current) }
                trustedRoute = current.kind == Router.Kind.TAILNET
                proceed()
            }
        } else {
            showFind()
        }
    }

    override fun onStart() {
        super.onStart()
        if (panel == Panel.FIND) startBrowsing()
        if (panel == Panel.CODE) startPolling()
    }

    override fun onStop() {
        stopBrowsing()
        poll?.cancel()
        super.onStop()
    }

    // --- panels ----------------------------------------------------------------------

    private fun show(p: Panel) {
        panel = p
        b.panelFind.visibility = if (p == Panel.FIND) View.VISIBLE else View.GONE
        b.panelName.visibility = if (p == Panel.NAME) View.VISIBLE else View.GONE
        b.panelCode.visibility = if (p == Panel.CODE) View.VISIBLE else View.GONE
        b.panelDeclined.visibility = if (p == Panel.DECLINED) View.VISIBLE else View.GONE
        if (p == Panel.FIND) startBrowsing() else stopBrowsing()
        if (p != Panel.CODE) poll?.cancel()
        b.root.scrollTo(0, 0)
    }

    private fun showFind() {
        busy?.cancel()
        show(Panel.FIND)
        b.findError.visibility = View.GONE
        setBusy(false)
    }

    private fun showName(error: String? = null) {
        show(Panel.NAME)
        val hub = info?.name ?: Router.hubLabel()
        b.nameHub.text = listOfNotNull(hub, route?.let { Router.shortLabel(this, it) }).joinToString(" · ")
        b.nameBody.text = getString(R.string.setup_name_body, hub)
        if (b.name.text.isNullOrBlank()) b.name.setText(suggestedName(this))
        b.join.setText(if (trustedRoute) R.string.setup_join_trusted else R.string.setup_join)
        showError(b.nameError, error)
    }

    private fun showCode(w: Pairing.Standing.Waiting) {
        waiting = w
        show(Panel.CODE)
        b.code.text = w.code
        b.codeHelp.text = getString(R.string.setup_code_help, w.name)
        b.codeStatus.setText(R.string.setup_code_status)
        b.pin.visibility = if (info?.pin == true) View.VISIBLE else View.GONE
        showError(b.codeError, null)
        startPolling()
    }

    private fun showDeclined(name: String) {
        show(Panel.DECLINED)
        b.declinedBody.text = getString(R.string.setup_declined_body, name)
    }

    private fun showError(view: TextView, msg: String?) {
        view.text = msg.orEmpty()
        view.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun setBusy(on: Boolean) {
        for (v in listOf(b.connect, b.tailscale, b.join, b.nameLink, b.pin, b.codeLink, b.askAgain)) v.isEnabled = !on
        b.connect.setText(if (on && panel == Panel.FIND) R.string.setup_checking else R.string.setup_connect)
        for (i in 0 until b.hubs.childCount) b.hubs.getChildAt(i).isEnabled = !on
    }

    /** Runs [block] as the one thing going on, showing failures in [errorView]. */
    private fun work(errorView: TextView, block: suspend () -> Unit) {
        busy?.cancel()
        showError(errorView, null)
        setBusy(true)
        busy = lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(errorView, friendly(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun friendly(e: Exception): String = when {
        e is HubException -> e.message ?: getString(R.string.share_unreachable)
        Pinning.mismatch(e) != null -> getString(R.string.setup_mismatch)
        else -> getString(R.string.setup_unreachable, route?.host ?: "the hub", e.message ?: e.javaClass.simpleName)
    }

    // --- finding the hub on the Wi-Fi -------------------------------------------------

    private fun startBrowsing() {
        if (browse != null) return
        b.findSpinner.visibility = View.VISIBLE
        b.findStatus.setText(R.string.setup_looking)
        b.findHint.visibility = View.GONE
        render(found)
        browse = (browser ?: Discovery::browse)(this, { list -> render(list) }, { _ ->
            b.findSpinner.visibility = View.GONE
            b.findHint.setText(R.string.setup_no_search)
            b.findHint.visibility = View.VISIBLE
        })
        nothingYet?.cancel()
        nothingYet = lifecycleScope.launch {
            delay(NOTHING_YET_MS)
            if (found.isEmpty()) {
                b.findHint.setText(R.string.setup_nothing_yet)
                b.findHint.visibility = View.VISIBLE
            }
        }
    }

    private fun stopBrowsing() {
        browse?.stop()
        browse = null
        nothingYet?.cancel()
    }

    private fun render(list: List<Announced>) {
        found = Discovery.arrange(list, Prefs.hubId)
        if (found.isNotEmpty()) {
            b.findHint.visibility = View.GONE
            b.findStatus.setText(if (found.size == 1) R.string.setup_found_one else R.string.setup_found_some)
        }
        b.hubs.removeAllViews()
        for (a in found) {
            val row = b.hubs.inflate(R.layout.item_hub)
            row.findViewById<TextView>(R.id.hub_name).text = a.name
            row.findViewById<TextView>(R.id.hub_sub).text = getString(R.string.setup_hub_sub, a.host, shortId(a.id))
            row.findViewById<View>(R.id.hub_yours).visibility = if (a.id == Prefs.hubId) View.VISIBLE else View.GONE
            row.setOnClickListener { connectAnnounced(a) }
            b.hubs.addView(row)
        }
    }

    /** A hub from mDNS: trust its announced certificate (first use), and check it says the same. */
    private fun connectAnnounced(a: Announced) = work(b.findError) {
        val r = Router.Route(Router.Kind.LAN, a.base, a.fingerprint)
        route = r
        val i = io { Pairing.info(r) } ?: throw HubException(getString(R.string.setup_not_droplet, a.host))
        if (i.id != a.id || i.fingerprint != a.fingerprint) throw HubException(getString(R.string.setup_mismatch))
        adopt(i, r, tailnet = a.tailnet ?: i.tailnet)
        proceed()
    }

    /** An address typed in: a LAN IP (trust on first use) or a tailnet URL (verified TLS). */
    private fun connectTyped() {
        val typed = b.url.text?.toString()?.trim().orEmpty()
        val url = Hub.normalize(typed)
        val uri = url?.let { android.net.Uri.parse(it) }
        val host = uri?.host
        if (uri == null || host.isNullOrEmpty()) {
            showError(b.findError, getString(R.string.setup_bad_url))
            return
        }
        work(b.findError) {
            val explicitPort = uri.port.takeIf { it > 0 }
            when {
                // the emulator's alias for the development machine (network_security_config allows only it)
                uri.scheme == "http" -> viaUrl(url)
                // an IP, a .local name, or a port: droplet's LAN listener, pinned on first use
                isIp(host) || host.endsWith(".local") || (explicitPort != null && explicitPort != 443) ->
                    viaLan(host, explicitPort ?: LAN_PORT)
                else -> try {
                    viaUrl(url)
                } catch (e: SSLException) {
                    // not a publicly trusted certificate: maybe droplet's own, on the LAN
                    if (Pinning.mismatch(e) != null) throw e
                    viaLan(host, explicitPort ?: LAN_PORT)
                }
            }
        }
    }

    private suspend fun viaUrl(url: String) {
        val r = Router.Route(Router.Kind.TAILNET, url)
        route = r
        val i = io { Pairing.info(r) }
        if (i == null) {
            // a hub from before local-first: the page names the phone, as it always did
            useLegacy(url)
            return
        }
        adopt(i, r, tailnet = url)
        proceed()
    }

    private suspend fun viaLan(host: String, port: Int) {
        val fp = io { Pinning.capture(host, port) }
            ?: throw HubException(getString(R.string.setup_no_lan_tls))
        val r = Router.Route(Router.Kind.LAN, "https://" + hostPort(host, port), fp)
        route = r
        val i = io { Pairing.info(r) } ?: throw HubException(getString(R.string.setup_not_droplet, host))
        // the certificate must be the one the hub says it has
        if (i.fingerprint != fp) throw HubException(getString(R.string.setup_mismatch))
        adopt(i, r, tailnet = i.tailnet)
        proceed()
    }

    /**
     * Stores the hub's identity. A different hub starts from nothing: its
     * devices and chats are different, and this phone's token there means
     * nothing (and mustn't be handed to it).
     */
    private suspend fun adopt(i: HubInfo, r: Router.Route, tailnet: String?) {
        info = i
        trustedRoute = r.kind == Router.Kind.TAILNET && r.base.startsWith("https://")
        val sameHub = Prefs.hubId == i.id
        // upgrading from 1.1, which knew only the tailnet URL
        val upgrading = Prefs.hubId == null && Prefs.hubUrl != null && (Prefs.hubUrl == tailnet || Prefs.hubUrl == r.base)
        if (!sameHub && !upgrading) {
            forget(this)
        }
        val pin = if (r.kind == Router.Kind.LAN) r.pin else i.fingerprint
        val pinChanged = sameHub && Prefs.hubFingerprint != null && pin != null && pin != Prefs.hubFingerprint
        if (pinChanged && !trustedRoute) {
            // pairing again after "the hub's identity changed": keep this phone's
            // identity only if the tailnet (verified TLS) vouches for the new certificate
            val vouched = Prefs.hubUrl?.let { t ->
                runCatching { io { Pairing.info(Router.Route(Router.Kind.TAILNET, t)) } }.getOrNull()
            }
            if (vouched?.id != i.id || vouched.fingerprint != pin) Hub.clearToken()
        }
        Prefs.hubId = i.id
        if (pin != Prefs.hubFingerprint || Prefs.pinSource == null) {
            Prefs.hubFingerprint = pin
            Prefs.pinSource = if (pin == null) null else if (trustedRoute) Prefs.PIN_TAILNET else Prefs.PIN_TOFU
        } else if (trustedRoute) {
            Prefs.pinSource = Prefs.PIN_TAILNET
        }
        i.name?.let { Prefs.hubName = it }
        val lanHere = if (r.kind == Router.Kind.LAN) listOf(r.base.removePrefix("https://")) else emptyList()
        Prefs.lanAddresses = lanHere + i.lan + Prefs.lanAddresses
        if (tailnet != null) Prefs.hubUrl = tailnet
    }

    private fun useLegacy(url: String) {
        if (Prefs.hubUrl != url || Prefs.hubId != null) forget(this)
        Prefs.hubUrl = url
        done(Router.Route(Router.Kind.TAILNET, url))
    }

    // --- getting let in ---------------------------------------------------------------

    /** Where this phone stands with the chosen hub decides the next panel. */
    private suspend fun proceed() {
        val r = route ?: return
        when (val st = io { Pairing.standing(r, Hub.deviceToken()) }) {
            is Pairing.Standing.In -> done(r)
            is Pairing.Standing.Waiting -> showCode(st)
            is Pairing.Standing.Out -> {
                // naming on a trusted route (the tailnet, a PIN session) lets it straight in
                trustedRoute = trustedRoute || st.trusted
                showName()
            }
        }
    }

    private fun join() {
        val r = route ?: return showFind()
        val name = b.name.text?.toString()?.trim()?.split(Regex("\\s+"))?.joinToString(" ").orEmpty()
        if (name.isEmpty()) return showError(b.nameError, getString(R.string.setup_name_empty))
        work(b.nameError) {
            val (st, token) = io { Pairing.join(r, name) }
            Hub.setToken(token)
            when (st) {
                is Pairing.Standing.In -> done(r)
                is Pairing.Standing.Waiting -> showCode(st)
                is Pairing.Standing.Out -> showName()
            }
        }
    }

    /** Asks the hub every 2.5 s until one of the owner's devices answers. */
    private fun startPolling() {
        val r = route ?: return
        poll?.cancel()
        poll = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val st = try {
                    io { Pairing.standing(r, Hub.deviceToken()) }
                } catch (e: IOException) {
                    b.codeStatus.setText(R.string.setup_code_lost)
                    continue
                }
                b.codeStatus.setText(R.string.setup_code_status)
                when (st) {
                    is Pairing.Standing.In -> return@launch done(r)
                    is Pairing.Standing.Waiting -> if (st.code != waiting?.code) showCode(st)
                    is Pairing.Standing.Out -> return@launch showDeclined(waiting?.name ?: b.name.text.toString())
                }
            }
        }
    }

    private fun askPin() {
        val r = route ?: return
        val input = digitsField(getString(R.string.setup_pin_hint), max = 32, numeric = false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_pin)
            .setView(padded(input))
            .setPositiveButton(R.string.setup_pin_go) { _, _ ->
                val pin = input.text.toString()
                if (pin.isEmpty()) return@setPositiveButton
                work(b.codeError) {
                    if (!io { Pairing.login(r, pin, Hub.deviceToken()) }) throw HubException(getString(R.string.setup_pin_wrong))
                    when (val st = io { Pairing.standing(r, Hub.deviceToken()) }) {
                        is Pairing.Standing.In -> done(r)
                        is Pairing.Standing.Waiting -> showCode(st)
                        is Pairing.Standing.Out -> { trustedRoute = true; showName() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askLinkCode() {
        val r = route ?: return
        val input = digitsField(getString(R.string.s_link_hint), max = 6, numeric = true)
        val errorView = if (panel == Panel.CODE) b.codeError else b.nameError
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_link)
            .setMessage(R.string.s_link_help)
            .setView(padded(input))
            .setPositiveButton(R.string.s_link_go) { _, _ ->
                val code = input.text.toString().filter { it.isDigit() }
                if (code.length != 6) return@setPositiveButton showError(errorView, getString(R.string.s_link_six))
                work(errorView) {
                    val (_, token) = io { Pairing.link(r, code) }
                    // the join request this phone made is no longer needed
                    val old = waiting
                    val oldToken = Hub.deviceToken()
                    if (old != null && oldToken != null && oldToken != token) io { Pairing.withdraw(r, old.id, token) }
                    Hub.setToken(token)
                    done(r)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun digitsField(hint: String, max: Int, numeric: Boolean) = EditText(this).apply {
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(max))
        this.hint = hint
        textSize = 22f
        if (numeric) letterSpacing = 0.3f
    }

    private fun padded(v: View) = FrameLayout(this).apply {
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v)
    }

    /** Let in: this route is the one to use now. */
    private fun done(r: Router.Route) {
        poll?.cancel()
        Router.use(r)
        Router.pairingNeeded(false)
        Hub.setToken(Hub.deviceToken())
        if (Prefs.stayConnected) runCatching { ConnectionService.start(this) }
        Live.refresh()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val DEFAULT_HUB = "https://t15.tail7375fe.ts.net"
        /** The hub said this phone isn't let in: go straight to pairing on the current route. */
        const val EXTRA_PAIR = "pair"
        /** The hub's identity changed: explain, and pair again. */
        const val EXTRA_REPAIR = "repair"
        const val LAN_PORT = 8443
        private const val POLL_MS = 2_500L
        private const val NOTHING_YET_MS = 6_000L

        /** mDNS browsing, replaced in tests (Robolectric has no NsdManager). */
        @VisibleForTesting
        var browser: ((Context, (List<Announced>) -> Unit, (Int) -> Unit) -> Discovery.Handle)? = null

        fun shortId(id: String): String = if (id.length > 8) id.take(4) + "…" + id.takeLast(4) else id

        /** The phone's own name ("Redmi Note 11E Pro"), which the owner set or the maker chose. */
        fun suggestedName(context: Context): String {
            val set = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            if (!set.isNullOrBlank()) return set.trim().take(40)
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().take(40)
        }

        private fun isIp(host: String): Boolean =
            host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3 ||
                (':' in host && runCatching { InetAddress.getByName(host) }.isSuccess)

        /** Forgets the hub entirely: its identity, this phone's token on it, cookies, the route. */
        fun forget(context: Context) {
            Hub.clearToken()  // first: it needs the origins the hub was reached on
            // the peers that hub vouched for go too; directly paired ones stay
            Mesh.forgetHub(Prefs.hubId)
            Prefs.forgetHub()
            Router.reset()
            Live.refresh()
            if (!Prefs.hasHub) ConnectionService.stop(context)
        }
    }
}
