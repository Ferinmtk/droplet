package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.droplet.app.databinding.ActivityPeersBinding
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.Seen
import dev.droplet.app.mesh.TrustList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your devices (docs/mesh.md): the trusted peers with how each is reached
 * now, the droplet devices on this Wi-Fi that aren't paired, and pairing
 * requests waiting for an answer. Tap a device for what you can do with it.
 */
class PeersActivity : AppCompatActivity() {
    private lateinit var b: ActivityPeersBinding
    private var sendTo: TrustList.Entry? = null
    private var answering: String? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val to = sendTo ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    // kept readable across restarts, for a file that has to wait for the peer
                    runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    describe(u)
                }
            }
            if (files.isNotEmpty()) UploadService.start(this@PeersActivity, files, null, MESH_PREFIX + to.fp, to.name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.pairAddress.setOnClickListener { askAddress() }
        answering = intent.getStringExtra(EXTRA_ANSWER)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Mesh.state.collect { render() } }
                launch { Mesh.changes.collect { render() } }
                launch { Mesh.pairRequests.collect { answer(it.request) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ANSWER)?.let { answer(it) }
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
                repeat(50) { _ -> if (Mesh.node == null) kotlinx.coroutines.delay(100) }
                answer(it)
            }
        }
    }

    override fun onStop() {
        Mesh.release(TAG)
        super.onStop()
    }

    // --- the lists -------------------------------------------------------------------

    private fun render() {
        val s = Mesh.state.value
        val n = Mesh.node
        b.meName.text = Prefs.meshDeviceName ?: android.os.Build.MODEL
        b.meState.text = when {
            n != null && s.status == Mesh.Status.RUNNING -> getString(R.string.mesh_identity, n.identity.fp.chunked(4).take(4).joinToString(" "), s.port) +
                (n.identity.fallbackReason?.let { "\n" + getString(R.string.mesh_identity_file, it) } ?: "")
            s.status == Mesh.Status.STARTING -> getString(R.string.mesh_status_starting)
            s.status == Mesh.Status.FAILED -> getString(R.string.mesh_status_failed, s.error.orEmpty())
            else -> getString(R.string.mesh_status_off)
        }
        b.pairAddress.isEnabled = n != null

        val waiting = n?.incoming?.waiting().orEmpty()
        b.asking.removeAllViews()
        b.askingTitle.visibility = if (waiting.isEmpty()) View.GONE else View.VISIBLE
        for (r in waiting) b.asking.addView(row(r.name, getString(R.string.mesh_pair_request_code, r.code), R.drawable.ic_device,
            online = true) { answer(r.request) })

        val peers = Mesh.peers()
        b.trusted.removeAllViews()
        b.trustedNone.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        for (p in peers) {
            val source = getString(if (p.entry.source == TrustList.SOURCE_PAIRED) R.string.mesh_source_paired else R.string.mesh_source_roster)
            b.trusted.addView(row(p.entry.name, Mesh.describeRoute(this, p.route) + " · " + source, R.drawable.ic_device,
                online = p.route in setOf("lan", "tailnet", "hub")) { actions(p.entry) })
        }

        val trusted = peers.map { it.entry.fp }.toSet()
        val nearby = n?.nearby().orEmpty().filter { it.fp !in trusted }.distinctBy { it.fp }
        b.nearby.removeAllViews()
        b.nearbyNone.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
        for (s2 in nearby) {
            b.nearby.addView(row(s2.name, listOf(s2.os.ifEmpty { "?" }, s2.addresses.firstOrNull().orEmpty()).joinToString(" · "),
                R.drawable.ic_device, online = null) { pair(s2) })
        }
    }

    private fun row(name: String, sub: String, icon: Int, online: Boolean?, onClick: () -> Unit): View {
        val v = b.trusted.inflate(R.layout.item_target)
        v.findViewById<TextView>(R.id.name).text = name
        v.findViewById<TextView>(R.id.sub).text = sub
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<View>(R.id.dot).visibility = if (online == true) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.last).visibility = View.GONE
        v.setOnClickListener { onClick() }
        return v
    }

    // --- what you can do with a device ------------------------------------------------------

    private fun actions(e: TrustList.Entry) {
        val items = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.mesh_act_text to { startActivity(ChatActivity.intent(this, e.fp)) },
            R.string.mesh_act_file to {
                sendTo = e
                pickFiles.launch(arrayOf("*/*"))
            },
            R.string.mesh_act_ring to { background({ Mesh.node!!.ring(e.fp) }) { toast(getString(R.string.mesh_sent_route, Mesh.describeRoute(this, it))) } },
            R.string.mesh_act_ring_stop to { background({ Mesh.node!!.ring(e.fp, stop = true) }) { } },
        )
        if ("input" in e.caps) items += R.string.mesh_act_remote to {
            Prefs.remoteTarget = MESH_PREFIX + e.fp
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        if (e.source == TrustList.SOURCE_PAIRED) items += R.string.mesh_act_unpair to { askUnpair(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun askUnpair(e: TrustList.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mesh_unpair_title, e.name))
            .setMessage(R.string.mesh_unpair_body)
            .setPositiveButton(R.string.mesh_act_unpair) { _, _ ->
                background({ Mesh.node!!.unpair(e.fp) }) { told ->
                    toast(getString(if (told) R.string.mesh_unpaired else R.string.mesh_unpaired_untold, e.name))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs [work] off the main thread; its result, or its error as a toast. */
    private fun <T> background(work: () -> T, ok: (T) -> Unit) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work() } }
            r.onSuccess(ok).onFailure { toast(getString(R.string.mesh_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // --- pairing: this phone asks -----------------------------------------------------------

    private fun pair(s: Seen) {
        val address = s.addresses.firstOrNull() ?: return
        startPair(s.name, address, s.port, s.fp)
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
            .setView(box)
            .setPositiveButton(R.string.mesh_accept) { _, _ ->
                val (host, port) = parseAddress(input.text.toString()) ?: run {
                    toast(getString(R.string.mesh_pair_bad_address))
                    return@setPositiveButton
                }
                startPair(host, host, port, null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPair(label: String, host: String, port: Int, expect: String?) {
        val waiting = MaterialAlertDialogBuilder(this).setMessage(getString(R.string.mesh_pairing_with, label))
            .setCancelable(false).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Mesh.node!!.pairStart(host, port, expect) } }
            waiting.dismiss()
            r.onSuccess { confirmCode(it) }.onFailure { toast(getString(R.string.mesh_pair_failed, it.message ?: "?")) }
        }
    }

    /** Both screens show the code; the owner says whether they match. */
    private fun confirmCode(og: MeshPairing.Outgoing) {
        var decided = false
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mesh_pair_check_title, og.peerName))
            .setMessage(getString(R.string.mesh_pair_check, og.peerName, og.code))
            .setCancelable(false)
            .setPositiveButton(R.string.mesh_pair_matches) { _, _ ->
                decided = true
                val wait = MaterialAlertDialogBuilder(this).setMessage(getString(R.string.mesh_pair_waiting, og.peerName))
                    .setNegativeButton(R.string.cancel) { _, _ -> Mesh.scope.launch { og.cancel() } }
                    .show()
                Mesh.node?.pairConfirm(og.request!!, true) { state ->
                    runOnUiThread {
                        if (!isDestroyed) wait.dismiss()
                        toast(when (state) {
                            MeshPairing.ACCEPTED -> getString(R.string.mesh_paired, og.peerName)
                            MeshPairing.DENIED -> getString(R.string.mesh_pair_failed, getString(R.string.mesh_pair_state_denied, og.peerName))
                            MeshPairing.CANCELLED -> getString(R.string.mesh_pair_failed, getString(R.string.mesh_pair_state_cancelled))
                            else -> getString(R.string.mesh_pair_failed, getString(R.string.mesh_pair_state_expired))
                        })
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                decided = true
                Mesh.node?.pairConfirm(og.request!!, false)
            }
            .setOnDismissListener { if (!decided) Mesh.node?.pairConfirm(og.request!!, false) }
            .show()
    }

    // --- pairing: another device asks ------------------------------------------------------

    private var answerDialog: AlertDialog? = null

    private fun answer(request: String) {
        val r = Mesh.node?.incoming?.waiting()?.firstOrNull { it.request == request }
        if (r == null) {
            toast(getString(R.string.mesh_answer_gone))
            return
        }
        if (answerDialog?.isShowing == true) return
        answerDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mesh_answer_title, r.name))
            .setMessage(getString(R.string.mesh_answer_body, r.name, r.os.ifEmpty { "?" }, r.code))
            .setPositiveButton(R.string.mesh_accept) { _, _ -> decide(r, true) }
            .setNegativeButton(R.string.mesh_deny) { _, _ -> decide(r, false) }
            .show()
    }

    private fun decide(r: MeshPairing.Request, accept: Boolean) {
        background({ Mesh.node!!.pairAnswer(r.request, accept) }) {
            if (accept) toast(getString(R.string.mesh_paired, r.name))
        }
    }

    private fun describe(uri: Uri): Outgoing? = runCatching {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        Outgoing(uri, name ?: uri.lastPathSegment ?: "file", size, contentResolver.getType(uri))
    }.getOrNull()

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
