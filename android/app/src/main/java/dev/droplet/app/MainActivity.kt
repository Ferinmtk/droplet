package dev.droplet.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.droplet.app.databinding.ActivityHomeBinding
import dev.droplet.app.mesh.TrustList
import dev.droplet.app.tv.Tv
import dev.droplet.app.tv.TvActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen, with or without a hub: your devices and what to do with
 * each (send files, message, ring, send the clipboard, the presentation
 * remote), pairing, the phone's own remotes (TV, Bluetooth mouse and
 * keyboard), the hub's web app when there is a hub, and what arrived.
 *
 * Opens on setup until the phone is set up (with a hub, or without one).
 * Notifications' links into the hub's web app ([EXTRA_PATH]) pass through
 * here: see [DeepLink].
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityHomeBinding
    private var sendTo: TrustList.Entry? = null
    /** Which device the file picker is for, kept across a restart while the picker is on screen. */
    private var sendToFp: String? = null
    private val onRing: () -> Unit = { runOnUiThread { if (::b.isInitialized) renderRinging() } }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val to = sendTo ?: sendToFp?.let { Mesh.peer(it) } ?: return@registerForActivityResult
        sendTo = null
        sendToFp = null
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    // kept readable across restarts, for a file that has to wait for the device
                    runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    describe(u)
                }
            }
            if (files.isEmpty()) return@launch
            UploadService.start(this@MainActivity, files, null, PeersActivity.MESH_PREFIX + to.fp, to.name)
            say(resources.getQuantityString(R.plurals.home_sending_files, files.size, files.size, to.name))
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // droplet's dark teal, whatever the system theme
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (!Prefs.isSetUp) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)

        b.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.pair.setOnClickListener { startActivity(Intent(this, PeersActivity::class.java)) }
        b.shareLink.setOnClickListener { shareDownloadLink() }
        b.ringingStop.setOnClickListener { Ringer.stop(this, tellHub = true) }
        b.tileTv.setOnClickListener { startActivity(TvActivity.intent(this)) }
        b.tileBt.setOnClickListener { startActivity(Intent(this, BluetoothActivity::class.java)) }
        b.tileHub.setOnClickListener { openHub(null) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Mesh.state.collect { render() } }
                launch { Mesh.changes.collect { render() } }
                launch { Tv.changes.collect { renderTiles() } }
                launch { Live.state.collect { renderTiles() } }
                launch { Router.state.collect { renderTiles() } }
                launch { Mesh.pairRequests.collect { render() } }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sendToFp = savedInstanceState?.getString(STATE_SEND_TO)
        if (savedInstanceState == null) followLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        followLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sendTo?.fp?.let { outState.putString(STATE_SEND_TO, it) }
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized) return
        Mesh.hold(MESH_TAG)
        Ringer.addListener(onRing)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        if (!Prefs.isSetUp) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        // MIUI and force-stop kill the service; opening the app brings it back
        if (Prefs.stayConnected && !ConnectionService.running) runCatching { ConnectionService.start(this) }
        if (Prefs.hasHub) {
            // the hub tile says how the hub is reached: keep that fresh while on screen
            Router.hold(ROUTER_TAG)
            Live.refresh()
        }
        // who's really reachable now (links only open when something's sent otherwise)
        Mesh.probe()
        render()
    }

    override fun onPause() {
        if (::b.isInitialized) Router.release(ROUTER_TAG)
        super.onPause()
    }

    override fun onStop() {
        if (::b.isInitialized) {
            Ringer.removeListener(onRing)
            Mesh.release(MESH_TAG)
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the only moment Android lets droplet read the clipboard: send it if it changed
        if (hasFocus && ::b.isInitialized) runCatching { ClipBridge.onForeground(this) }
    }

    // --- links from notifications ------------------------------------------------------

    private fun followLink(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_PATH) ?: return
        intent.removeExtra(EXTRA_PATH)
        when (val t = DeepLink.target(path, Prefs.hasHub, Ringer.ringing != null) { id -> Mesh.peerById(id)?.fp }) {
            is DeepLink.Target.Hub -> openHub(t.path)
            is DeepLink.Target.Chat -> startActivity(ChatActivity.intent(this, t.fp))
            DeepLink.Target.Ring -> startActivity(Intent(this, RingActivity::class.java))
            DeepLink.Target.Home -> Unit
        }
    }

    private fun openHub(path: String?) {
        if (!Prefs.hasHub) return
        val i = Intent(this, HubActivity::class.java)
        if (path != null) i.putExtra(EXTRA_PATH, path)
        startActivity(i)
    }

    // --- drawing ---------------------------------------------------------------------

    private fun render() {
        if (!::b.isInitialized || isFinishing) return
        val s = Mesh.state.value
        val n = Mesh.node
        val name = Prefs.meshDeviceName ?: SetupActivity.suggestedName(this)
        b.me.text = getString(R.string.home_me, name)
        renderRinging()
        renderNotice(s)

        // pairing requests waiting for an answer
        b.requests.removeAllViews()
        for (r in n?.incoming?.waiting().orEmpty()) {
            val v = b.requests.inflate(R.layout.item_request)
            v.findViewById<TextView>(R.id.title).text = getString(R.string.mesh_pair_request, r.name)
            v.findViewById<TextView>(R.id.sub).text = getString(R.string.home_request_sub)
            v.findViewById<View>(R.id.answer).setOnClickListener { startActivity(PeersActivity.answerIntent(this, r.request)) }
            b.requests.addView(v)
        }

        val peers = Mesh.peers()
        b.devices.removeAllViews()
        for (p in peers) b.devices.addView(deviceCard(p))
        // with the mesh still starting, "no devices" would be a lie for a moment
        val known = n != null || s.status == Mesh.Status.FAILED || s.status == Mesh.Status.OFF && !Prefs.meshEnabled
        b.empty.visibility = if (peers.isEmpty() && known) View.VISIBLE else View.GONE
        b.devicesTitle.text = if (peers.isEmpty()) getString(R.string.home_devices)
            else getString(R.string.home_devices_n, peers.size)
        b.pair.setText(if (peers.isEmpty()) R.string.home_pair_first else R.string.home_pair)

        renderTiles()
        renderReceived()
        renderMessages()
    }

    private fun renderRinging() {
        val ring = Ringer.ringing
        b.ringing.visibility = if (ring != null) View.VISIBLE else View.GONE
        if (ring != null) b.ringingText.text = getString(R.string.home_ringing, ring.from)
    }

    /** Only when something stands between the phone and its devices. */
    private fun renderNotice(s: Mesh.Snapshot) {
        val (text, button, action) = when {
            !Prefs.meshEnabled -> Triple(getString(R.string.home_notice_mesh_off), getString(R.string.home_notice_turn_on)) {
                Prefs.meshEnabled = true
                Mesh.enabledChanged()
                if (!ConnectionService.running && Prefs.stayConnected) runCatching { ConnectionService.start(this) }
            }
            s.status == Mesh.Status.FAILED -> Triple(getString(R.string.mesh_status_failed, s.error.orEmpty()),
                getString(R.string.home_notice_retry)) { Mesh.enabledChanged() }
            !Prefs.stayConnected -> Triple(getString(R.string.home_notice_stay), getString(R.string.home_notice_stay_on)) {
                Prefs.stayConnected = true
                runCatching { ConnectionService.start(this) }
                render()
            }
            else -> Triple(null, null, null)
        }
        b.notice.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text != null) {
            b.noticeText.text = text
            b.noticeButton.text = button
            b.noticeButton.setOnClickListener { action?.invoke() }
        }
    }

    private fun deviceCard(p: Mesh.PeerView): View {
        val e = p.entry
        val v = b.devices.inflate(R.layout.item_device)
        v.findViewById<TextView>(R.id.name).text = e.name
        v.findViewById<ImageView>(R.id.icon).setImageResource(if (e.os == "android") R.drawable.ic_phone else R.drawable.ic_laptop)
        val waiting = Mesh.node?.outbox?.forPeer(e.fp)?.size ?: 0
        val looking = p.route in setOf("seen", "offline") && Mesh.isProbing(e.fp)
        val route = if (looking) getString(R.string.home_route_looking) else routeText(p.route)
        v.findViewById<TextView>(R.id.route).text = if (waiting > 0) {
            route + " · " + resources.getQuantityString(R.plurals.home_waiting, waiting, waiting)
        } else route
        v.findViewById<View>(R.id.dot).setBackgroundResource(when (p.route) {
            "lan", "tailnet", "seen" -> R.drawable.r_dot_on
            "hub", "hub-mailbox" -> R.drawable.r_dot_hub
            else -> R.drawable.r_dot
        })
        v.findViewById<View>(R.id.act_files).setOnClickListener {
            sendTo = e
            sendToFp = e.fp
            try {
                pickFiles.launch(arrayOf("*/*"))
            } catch (x: ActivityNotFoundException) {
                sendTo = null
                say(getString(R.string.no_file_picker))
            }
        }
        v.findViewById<View>(R.id.act_message).setOnClickListener { startActivity(ChatActivity.intent(this, e.fp)) }
        v.findViewById<View>(R.id.act_ring).setOnClickListener { ring(e) }
        v.findViewById<View>(R.id.act_clip).apply {
            visibility = if ("clipboard" in e.caps || e.caps.isEmpty()) View.VISIBLE else View.GONE
            setOnClickListener { sendClipboard(e) }
        }
        v.findViewById<View>(R.id.act_remote).apply {
            visibility = if ("input" in e.caps) View.VISIBLE else View.GONE
            setOnClickListener {
                Prefs.remoteTarget = PeersActivity.MESH_PREFIX + e.fp
                startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
            }
        }
        v.findViewById<View>(R.id.more).setOnClickListener { more(e) }
        return v
    }

    private fun routeText(route: String): String = getString(when (route) {
        "lan" -> R.string.home_route_lan
        "tailnet" -> R.string.home_route_tailnet
        "seen" -> R.string.home_route_seen
        "hub", "hub-mailbox" -> R.string.home_route_hub
        else -> R.string.home_route_offline
    })

    private fun renderTiles() {
        if (!::b.isInitialized) return
        val tv = Tv.tvs().firstOrNull { it.id == Prefs.tvSelected } ?: Tv.tvs().firstOrNull()
        b.tileTvSub.text = tv?.name ?: getString(R.string.home_tile_tv_none)
        b.tileHub.visibility = if (Prefs.hasHub) View.VISIBLE else View.GONE
        if (Prefs.hasHub) {
            val live = Live.state.value
            b.tileHubSub.text = if (live.connected) getString(R.string.home_tile_hub_on, Router.hubLabel())
                else Router.hubLabel()
        }
    }

    private fun renderReceived() {
        val items = Received.recent(RECENT)
        b.received.removeAllViews()
        b.receivedTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        b.received.visibility = b.receivedTitle.visibility
        for (it in items) {
            val v = b.received.inflate(R.layout.item_home_row)
            v.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_file)
            v.findViewById<TextView>(R.id.title).text = it.name
            v.findViewById<TextView>(R.id.sub).text = getString(R.string.home_received_from, it.from, ago(it.ts))
            v.setOnClickListener { _ -> openFile(it) }
            v.findViewById<View>(R.id.action).setOnClickListener { _ -> shareFile(it) }
            b.received.addView(v)
        }
    }

    private fun renderMessages() {
        val n = Mesh.node
        // the newest message from each device
        val latest = n?.chat?.recent(null, 400).orEmpty().filter { it.optString("dir") == "in" }
            .groupBy { it.optString("fp") }.mapNotNull { (_, list) -> list.maxByOrNull { it.optDouble("ts") } }
            .sortedByDescending { it.optDouble("ts") }.take(RECENT)
        b.messages.removeAllViews()
        b.messagesTitle.visibility = if (latest.isEmpty()) View.GONE else View.VISIBLE
        b.messages.visibility = b.messagesTitle.visibility
        for (m in latest) {
            val fp = m.optString("fp")
            val v = b.messages.inflate(R.layout.item_home_row)
            v.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_message)
            v.findViewById<TextView>(R.id.title).text = n?.trust?.get(fp)?.name ?: m.optString("name")
            v.findViewById<TextView>(R.id.sub).text = getString(R.string.home_message_sub,
                m.optString("body").replace('\n', ' '), ago((m.optDouble("ts") * 1000).toLong()))
            v.findViewById<View>(R.id.action).visibility = View.GONE
            v.setOnClickListener { startActivity(ChatActivity.intent(this, fp)) }
            b.messages.addView(v)
        }
    }

    private fun ago(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    // --- what you can do with a device -------------------------------------------------

    private fun ring(e: TrustList.Entry) {
        background({ Mesh.node?.ring(e.fp) ?: error(getString(R.string.mesh_off_now)) }) {
            Snackbar.make(b.root, getString(R.string.home_ringing_them, e.name), Snackbar.LENGTH_LONG)
                .setAction(R.string.stop) { background({ Mesh.node?.ring(e.fp, stop = true) }) { } }
                .show()
        }
    }

    private fun sendClipboard(e: TrustList.Entry) {
        // this screen has focus, so Android lets it read the clipboard now
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) return say(getString(R.string.clip_empty))
        if (text.toByteArray().size > 256 * 1024) return say(getString(R.string.clip_too_big))
        background({ Mesh.node?.clip(e.fp, text) ?: error(getString(R.string.mesh_off_now)) }) {
            say(getString(R.string.home_clip_sent, e.name))
        }
    }

    private fun more(e: TrustList.Entry) {
        val items = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.mesh_act_ring_stop) to { background({ Mesh.node?.ring(e.fp, stop = true) }) { } },
        )
        if (e.source == TrustList.SOURCE_PAIRED) items += getString(R.string.mesh_act_unpair) to { askUnpair(e) }
        items += getString(R.string.home_about_device) to { about(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun about(e: TrustList.Entry) {
        val source = getString(if (e.source == TrustList.SOURCE_PAIRED) R.string.mesh_source_paired else R.string.mesh_source_roster)
        val lines = listOfNotNull(
            getString(R.string.home_about_source, source),
            e.os.takeIf { it.isNotEmpty() }?.let { getString(R.string.home_about_os, it) },
            (e.lan + listOfNotNull(e.tailnetIp)).takeIf { it.isNotEmpty() }?.let { getString(R.string.home_about_addresses, it.joinToString(", ")) },
            getString(R.string.home_about_fp, e.fp.chunked(4).take(8).joinToString(" ")),
            getString(R.string.home_about_roster).takeIf { e.source != TrustList.SOURCE_PAIRED },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun askUnpair(e: TrustList.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mesh_unpair_title, e.name))
            .setMessage(R.string.mesh_unpair_body)
            .setPositiveButton(R.string.mesh_act_unpair) { _, _ ->
                background({ Mesh.node?.unpair(e.fp) ?: error(getString(R.string.mesh_off_now)) }) { told ->
                    say(getString(if (told) R.string.mesh_unpaired else R.string.mesh_unpaired_untold, e.name))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- files that arrived ---------------------------------------------------------------

    private fun fileUri(it: Received.Item): Uri? = it.uri?.let { u -> Uri.parse(u) }?.takeIf { u -> u.scheme == "content" }

    private fun openFile(it: Received.Item) {
        val uri = fileUri(it) ?: return say(getString(R.string.home_open_in_files, it.where))
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, it.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (x: Exception) {
            say(getString(R.string.home_open_in_files, it.where))
        }
    }

    private fun shareFile(it: Received.Item) {
        val uri = fileUri(it) ?: return say(getString(R.string.home_open_in_files, it.where))
        val send = Intent(Intent.ACTION_SEND).setType(it.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, it.name))
        } catch (x: Exception) {
            say(getString(R.string.no_app_for_link))
        }
    }

    // --- the first device -------------------------------------------------------------------

    /** The other device needs droplet too: the release page's link, to send it however suits. */
    private fun shareDownloadLink() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.home_share_link_text, RELEASES))
        try {
            startActivity(Intent.createChooser(send, getString(R.string.home_share_link)))
        } catch (x: Exception) {
            say(RELEASES)
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun say(s: String) {
        if (::b.isInitialized) Snackbar.make(b.root, s, Snackbar.LENGTH_LONG).show()
        else Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    /** Runs [work] off the main thread; its result, or its error said plainly. */
    private fun <T> background(work: () -> T, ok: (T) -> Unit) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work() } }
            r.onSuccess(ok).onFailure { say(getString(R.string.mesh_failed, it.message ?: it.javaClass.simpleName)) }
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
        /** A path in the hub's web app ("/#chat-<id>", "/"), from a notification. */
        const val EXTRA_PATH = "path"
        private const val STATE_SEND_TO = "send_to"
        const val RELEASES = "https://github.com/Ferinmtk/droplet/releases/latest"
        private const val MESH_TAG = "home"
        private const val ROUTER_TAG = "home"
        private const val RECENT = 4

        fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
    }
}

/**
 * Where a notification's link into the hub's web app goes (docs: the hub's
 * `/#chat-<device>`, `/#ring`, `/#inbox`, `/`). With a hub it's the web app,
 * as before; without one, the native screen that matches, or home.
 */
object DeepLink {
    sealed class Target {
        data class Hub(val path: String) : Target()
        data class Chat(val fp: String) : Target()
        object Ring : Target()
        object Home : Target()
    }

    @VisibleForTesting
    fun target(path: String, hasHub: Boolean, ringing: Boolean, peerFp: (String) -> String?): Target {
        if (hasHub) return Target.Hub(path)
        val hash = path.substringAfter('#', "")
        return when {
            hash.startsWith("chat-") -> peerFp(hash.removePrefix("chat-"))?.let { Target.Chat(it) } ?: Target.Home
            hash == "ring" && ringing -> Target.Ring
            else -> Target.Home
        }
    }
}
