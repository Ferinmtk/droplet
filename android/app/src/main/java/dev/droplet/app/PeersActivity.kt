package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.droplet.app.databinding.ActivityPeersBinding
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.Seen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pair a device (docs/mesh.md §4), like Bluetooth: droplet devices on this
 * Wi-Fi that aren't paired yet, devices asking to pair with this phone, and
 * pairing by address. Either way both screens show the same four-digit
 * code, and the owner says whether they match. Paired devices then live on
 * the home screen ([MainActivity]).
 */
class PeersActivity : AppCompatActivity() {
    private enum class Panel { LIST, CODE, DONE }

    private lateinit var b: ActivityPeersBinding
    private var panel = Panel.LIST
    /** A pairing this phone started, while its code is on screen or it waits for the other side. */
    private var outgoing: MeshPairing.Outgoing? = null
    /** A request from another device, while its code is on screen. */
    private var incoming: MeshPairing.Request? = null
    /** Bumped whenever a pairing is started or given up, so a late answer to an old one is dropped. */
    private var attempt = 0
    private var answering: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        b = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        b.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.pairAddress.setOnClickListener { askAddress() }
        b.shareLink.setOnClickListener { shareDownloadLink() }
        b.done.setOnClickListener { finish() }
        b.doneAnother.setOnClickListener { show(Panel.LIST) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (panel) {
                    Panel.CODE -> cancelCode()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        answering = intent.getStringExtra(EXTRA_ANSWER)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Mesh.state.collect { render() } }
                launch { Mesh.changes.collect { render() } }
                launch { Mesh.pairRequests.collect { if (panel == Panel.LIST) answer(it.request) else render() } }
            }
        }
        show(Panel.LIST)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ANSWER)?.let { answering = it }
    }

    override fun onStart() {
        super.onStart()
        Mesh.hold(TAG)
    }

    override fun onResume() {
        super.onResume()
        answering?.let {
            answering = null
            // the node may still be starting when opened from the notification
            lifecycleScope.launch {
                repeat(50) { _ -> if (Mesh.node == null) delay(100) }
                answer(it)
            }
        }
    }

    override fun onStop() {
        Mesh.release(TAG)
        super.onStop()
    }

    // --- panels ------------------------------------------------------------------------

    private fun show(p: Panel) {
        panel = p
        b.panelList.visibility = if (p == Panel.LIST) View.VISIBLE else View.GONE
        b.panelCode.visibility = if (p == Panel.CODE) View.VISIBLE else View.GONE
        b.panelDone.visibility = if (p == Panel.DONE) View.VISIBLE else View.GONE
        b.title.setText(if (p == Panel.DONE) R.string.pair_done_eyebrow else R.string.pair_title)
        b.root.scrollTo(0, 0)
        render()
    }

    private fun render() {
        if (panel != Panel.LIST) return
        val s = Mesh.state.value
        val n = Mesh.node
        val name = Prefs.meshDeviceName ?: SetupActivity.suggestedName(this)
        b.intro.text = getString(R.string.pair_intro, name)
        b.off.visibility = View.GONE
        when {
            !Prefs.meshEnabled -> showOff(getString(R.string.mesh_status_off))
            s.status == Mesh.Status.FAILED -> showOff(getString(R.string.mesh_status_failed, s.error.orEmpty()))
        }
        b.pairAddress.isEnabled = n != null
        b.spinner.visibility = if (n != null || s.status == Mesh.Status.STARTING) View.VISIBLE else View.GONE

        val waiting = n?.incoming?.waiting().orEmpty()
        b.asking.removeAllViews()
        b.askingTitle.visibility = if (waiting.isEmpty()) View.GONE else View.VISIBLE
        for (r in waiting) {
            b.asking.addView(row(r.name, getString(R.string.pair_asking_sub, osName(r.os)), iconFor(r.os),
                getString(R.string.pair_answer_verb)) { answer(r.request) })
        }

        val trusted = n?.trust?.all().orEmpty().map { it.fp }.toSet()
        val nearby = n?.nearby().orEmpty().filter { it.fp !in trusted }.distinctBy { it.fp }
        b.nearby.removeAllViews()
        b.nearbyNone.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
        for (d in nearby) {
            val sub = listOf(osName(d.os), d.addresses.firstOrNull().orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
            b.nearby.addView(row(d.name, sub, iconFor(d.os), getString(R.string.pair_verb)) { pair(d) })
        }
    }

    private fun showOff(text: String) {
        b.off.text = text
        b.off.visibility = View.VISIBLE
    }

    private fun row(name: String, sub: String, icon: Int, verb: String, onClick: () -> Unit): View {
        val v = b.nearby.inflate(R.layout.item_nearby)
        v.findViewById<TextView>(R.id.name).text = name
        v.findViewById<TextView>(R.id.sub).text = sub
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.verb).text = verb
        v.setOnClickListener { onClick() }
        return v
    }

    private fun iconFor(os: String) = if (os == "android") R.drawable.ic_phone else R.drawable.ic_laptop

    private fun osName(os: String): String = when (os) {
        "android" -> "Android"
        "windows" -> "Windows"
        "linux" -> "Linux"
        else -> ""
    }

    private fun say(s: String) = Snackbar.make(b.root, s, Snackbar.LENGTH_LONG).show()

    // --- pairing: this phone asks -----------------------------------------------------------

    private fun pair(s: Seen) {
        val address = s.addresses.firstOrNull() ?: return
        startPair(s.name, address, s.port, s.fp, s.os)
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.mesh_pair_address_hint)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mesh_pair_address)
            .setMessage(R.string.pair_address_help)
            .setView(box)
            .setPositiveButton(R.string.pair_verb) { _, _ ->
                val (host, port) = parseAddress(input.text.toString()) ?: run {
                    say(getString(R.string.mesh_pair_bad_address))
                    return@setPositiveButton
                }
                startPair(host, host, port, null, "")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPair(label: String, host: String, port: Int, expect: String?, os: String) {
        outgoing = null
        incoming = null
        val mine = ++attempt
        show(Panel.CODE)
        b.codeTitle.text = getString(R.string.pair_with, label)
        b.code.text = getString(R.string.pair_code_blank)
        b.codeHelp.text = getString(R.string.mesh_pairing_with, label)
        b.codeWait.visibility = View.GONE
        b.codeYes.visibility = View.GONE
        b.codeNo.setText(R.string.cancel)
        b.codeNo.setOnClickListener { cancelCode() }
        b.doneIcon.setImageResource(iconFor(os))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { (Mesh.node ?: error(getString(R.string.mesh_off_now))).pairStart(host, port, expect) } }
            if (mine != attempt || panel != Panel.CODE || outgoing != null || incoming != null) {
                // cancelled meanwhile
                r.getOrNull()?.let { og -> Mesh.node?.pairConfirm(og.request!!, false) }
                return@launch
            }
            r.onSuccess { showOutgoing(it) }.onFailure {
                show(Panel.LIST)
                say(getString(R.string.mesh_pair_failed, it.message ?: "?"))
            }
        }
    }

    /** Both screens show the code; the owner says whether they match. */
    private fun showOutgoing(og: MeshPairing.Outgoing) {
        outgoing = og
        b.codeTitle.text = getString(R.string.pair_with, og.peerName)
        b.code.text = og.code
        b.codeHelp.text = getString(R.string.pair_check_out, og.peerName)
        b.codeYes.visibility = View.VISIBLE
        b.codeYes.setText(R.string.pair_they_match)
        b.codeYes.setOnClickListener { confirmOutgoing(og) }
        b.codeNo.setText(R.string.pair_no_match)
        if (og.peerOs.isNotEmpty()) b.doneIcon.setImageResource(iconFor(og.peerOs))
    }

    private fun confirmOutgoing(og: MeshPairing.Outgoing) {
        b.codeYes.visibility = View.GONE
        b.codeNo.setText(R.string.cancel)
        b.codeWait.visibility = View.VISIBLE
        b.codeStatus.text = getString(R.string.mesh_pair_waiting, og.peerName)
        b.codeHelp.text = getString(R.string.pair_accept_there, og.peerName)
        val n = Mesh.node ?: return
        runCatching {
            n.pairConfirm(og.request!!, true) { state ->
                runOnUiThread {
                    if (isDestroyed || outgoing !== og) return@runOnUiThread
                    outgoing = null
                    when (state) {
                        MeshPairing.ACCEPTED -> showDone(og.peerName)
                        else -> {
                            show(Panel.LIST)
                            say(getString(R.string.mesh_pair_failed, when (state) {
                                MeshPairing.DENIED -> getString(R.string.mesh_pair_state_denied, og.peerName)
                                MeshPairing.CANCELLED -> getString(R.string.mesh_pair_state_cancelled)
                                else -> getString(R.string.mesh_pair_state_expired)
                            }))
                        }
                    }
                }
            }
        }.onFailure {
            show(Panel.LIST)
            say(getString(R.string.mesh_pair_failed, it.message ?: "?"))
        }
    }

    /** Cancel on the code screen: the codes didn't match, or the owner changed their mind. */
    private fun cancelCode() {
        attempt++
        outgoing?.let { og ->
            outgoing = null
            if (og.localOk) Mesh.scope.launch { runCatching { og.cancel() } }
            else runCatching { Mesh.node?.pairConfirm(og.request!!, false) }
        }
        incoming?.let { r ->
            incoming = null
            decide(r, false)
        }
        show(Panel.LIST)
    }

    // --- pairing: another device asks ------------------------------------------------------

    private fun answer(request: String) {
        val r = Mesh.node?.incoming?.waiting()?.firstOrNull { it.request == request }
        if (r == null) {
            say(getString(R.string.mesh_answer_gone))
            return
        }
        if (panel == Panel.CODE) return
        incoming = r
        outgoing = null
        show(Panel.CODE)
        b.codeTitle.text = getString(R.string.mesh_answer_title, r.name)
        b.code.text = r.code
        b.codeHelp.text = getString(R.string.pair_check_in, r.name)
        b.codeWait.visibility = View.GONE
        b.codeYes.visibility = View.VISIBLE
        b.codeYes.setText(R.string.pair_they_match)
        b.codeYes.setOnClickListener {
            incoming = null
            decide(r, true)
        }
        b.codeNo.setText(R.string.pair_no_match)
        b.codeNo.setOnClickListener { cancelCode() }
        b.doneIcon.setImageResource(iconFor(r.os))
    }

    private fun decide(r: MeshPairing.Request, accept: Boolean) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { (Mesh.node ?: error(getString(R.string.mesh_off_now))).pairAnswer(r.request, accept) } }
            res.onSuccess { if (accept) showDone(r.name) }
                .onFailure {
                    if (accept) show(Panel.LIST)
                    say(getString(R.string.mesh_failed, it.message ?: it.javaClass.simpleName))
                }
        }
    }

    private fun showDone(name: String) {
        show(Panel.DONE)
        b.doneTitle.text = getString(R.string.mesh_paired, name)
    }

    private fun shareDownloadLink() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.home_share_link_text, MainActivity.RELEASES))
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.home_share_link))) }
    }

    companion object {
        const val MESH_PREFIX = "mesh:"
        private const val TAG = "peers"
        private const val EXTRA_ANSWER = "answer"

        fun answerIntent(context: Context, request: String): Intent =
            Intent(context, PeersActivity::class.java).putExtra(EXTRA_ANSWER, request)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** "192.168.1.20", "192.168.1.20:1740", "[fd00::1]:1739" → host and port. */
        fun parseAddress(s: String): Pair<String, Int>? {
            val t = s.trim()
            val m = Regex("^\\[([0-9A-Fa-f:.]+)](?::(\\d{1,5}))?$").matchEntire(t)
                ?: Regex("^([0-9.]+|[0-9A-Za-z.-]+\\.[A-Za-z]+)(?::(\\d{1,5}))?$").matchEntire(t)
                ?: Regex("^([0-9A-Fa-f]*:[0-9A-Fa-f:.]*)$").matchEntire(t)
                ?: return null
            val port = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: MeshNode.DEFAULT_PORT
            if (port !in 1..65535) return null
            return m.groupValues[1] to port
        }
    }
}
