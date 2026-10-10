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
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import dev.droplet.app.mesh.Perms
import dev.droplet.app.mesh.TrustList
import dev.droplet.app.tv.Tv
import dev.droplet.app.tv.TvActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** Select: sending to several devices at once (docs/mesh.md §9.10). */
    private var selecting = false
    private val ticked = LinkedHashSet<String>()
    /** "All my devices": your own devices that allow what's sent, worked out when it's sent. */
    private var allMine = false
    /** Several devices the file picker is for (kept across a restart), when not [sendTo]. */
    private var sendToMany: List<String>? = null
    /** Each device's card, by fingerprint: its transfers are redrawn in place as they move. */
    private val cards = HashMap<String, View>()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val many = sendToMany?.mapNotNull { Mesh.peer(it) }
        sendToMany = null
        if (many != null) {
            if (uris.isEmpty() || many.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        describe(u)
                    }
                }
                if (files.isEmpty()) return@launch
                val targets = many.map { PeersActivity.MESH_PREFIX + it.fp to it.label }
                UploadService.start(this@MainActivity, files, null, targets[0].first, targets[0].second, more = targets.drop(1))
                say(resources.getQuantityString(R.plurals.home_sending_several, many.size, files.size, many.size))
                endSelect()
            }
            return@registerForActivityResult
        }
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
            UploadService.start(this@MainActivity, files, null, PeersActivity.MESH_PREFIX + to.fp, to.label)
            say(resources.getQuantityString(R.plurals.home_sending_files, files.size, files.size, to.label))
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
        b.select.setOnClickListener { if (selecting) endSelect() else { selecting = true; render() } }
        b.selectAll.setOnClickListener {
            val mine = Mesh.peers().filter { it.entry.relation == Perms.OWN_DEVICE }.map { it.entry.fp }
            allMine = true
            ticked.clear()
            ticked += mine
            render()
        }
        b.severalFiles.setOnClickListener { severalFiles() }
        b.severalMessage.setOnClickListener { askMessage() }
        b.severalLink.setOnClickListener { severalTargets(Perms.CHAT)?.let { askLink(it) } }
        b.severalClip.setOnClickListener { severalClipboard() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Mesh.state.collect { render() } }
                launch { Mesh.changes.collect { render() } }
                // files on their way: their progress, redrawn in place at most 4 times a second
                launch {
                    while (true) {
                        delay(250)
                        if (Mesh.transfers().any { it.active }) renderAllTransfers()
                    }
                }
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
        sendToMany = savedInstanceState?.getStringArray(STATE_SEND_MANY)?.toList()
        if (savedInstanceState == null) followLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        followLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sendTo?.fp?.let { outState.putString(STATE_SEND_TO, it) }
        sendToMany?.let { outState.putStringArray(STATE_SEND_MANY, it.toTypedArray()) }
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
        cards.clear()
        ticked.retainAll(peers.map { it.entry.fp }.toSet())
        if (peers.size < 2 && selecting) selecting = false
        for (p in peers) b.devices.addView(deviceCard(p).also { cards[p.entry.fp] = it })
        renderSelect(peers.size)
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
            Mesh.pausedAll -> Triple(getString(R.string.home_notice_paused), getString(R.string.home_notice_resume)) {
                Mesh.pauseEverything(false)
                render()
            }
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
        v.findViewById<TextView>(R.id.name).text = e.label
        // a nickname: the device's own name too, small
        v.findViewById<TextView>(R.id.realname).apply {
            text = e.name
            visibility = if (e.nickname.isNotEmpty()) View.VISIBLE else View.GONE
        }
        v.findViewById<ImageView>(R.id.icon).setImageResource(if (e.os == "android") R.drawable.ic_phone else R.drawable.ic_laptop)
        val waiting = Mesh.node?.outbox?.forPeer(e.fp)?.size ?: 0
        val looking = p.route in setOf("seen", "offline") && Mesh.isProbing(e.fp)
        val reached = if (looking) getString(R.string.home_route_looking) else routeText(p.route)
        // paused here, everything paused, or it paused sharing with this phone (docs/mesh.md §9.9)
        val route = when {
            Mesh.pausedAll -> getString(R.string.perm_paused_all_route, reached)
            e.paused -> getString(R.string.perm_paused_route, reached)
            p.pausedThere -> getString(R.string.perm_paused_there_route, reached)
            else -> reached
        }
        val lines = mutableListOf(if (waiting > 0) {
            route + " · " + resources.getQuantityString(R.plurals.home_waiting, waiting, waiting)
        } else route)
        // the last thing it turned down, for a while: "slim doesn't allow the clipboard from you"
        p.refusal?.takeIf { it.why == Perms.DENIED && System.currentTimeMillis() - it.ts < REFUSAL_SHOWN_MS }
            ?.let { lines += it.text }
        v.findViewById<TextView>(R.id.route).apply {
            text = lines.joinToString("\n")
            // "Paused · Connected on Wi-Fi" may need a second line beside the Pause button
            maxLines = lines.size + 1
        }
        v.findViewById<View>(R.id.badge).visibility = if (e.isOther) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.pause).apply {
            setText(if (e.paused) R.string.perm_act_resume else R.string.perm_act_pause)
            setOnClickListener { togglePause(e) }
        }
        v.findViewById<View>(R.id.dot).setBackgroundResource(when (p.route) {
            "lan", "tailnet", "seen" -> R.drawable.r_dot_on
            "hub", "hub-mailbox" -> R.drawable.r_dot_hub
            else -> R.drawable.r_dot
        })
        /** An action greyed out, saying why when tapped; or as usual. */
        fun action(id: Int, t: String, go: () -> Unit) = v.findViewById<View>(id).apply {
            val why = PermsUi.unavailable(p, t)
            alpha = if (why == null) 1f else PermsUi.DIMMED
            setOnClickListener { if (why != null) say(why) else go() }
        }
        action(R.id.act_files, "offer") {
            sendTo = e
            sendToFp = e.fp
            try {
                pickFiles.launch(arrayOf("*/*"))
            } catch (x: ActivityNotFoundException) {
                sendTo = null
                say(getString(R.string.no_file_picker))
            }
        }
        action(R.id.act_message, "text") { startActivity(ChatActivity.intent(this, e.fp)) }
        action(R.id.act_ring, "ring") { ring(e) }
        action(R.id.act_clip, "clip") { sendClipboard(e) }.apply {
            visibility = if ("clipboard" in e.caps || e.caps.isEmpty()) View.VISIBLE else View.GONE
        }
        action(R.id.act_remote, "input") {
            Prefs.remoteTarget = PeersActivity.MESH_PREFIX + e.fp
            startActivity(Intent(this@MainActivity, RemoteActivity::class.java))
        }.apply {
            visibility = if ("input" in e.caps) View.VISIBLE else View.GONE
        }
        v.findViewById<View>(R.id.more).setOnClickListener { more(e) }
        renderTransfers(v, e.fp)
        // Select: a tick instead of the buttons, and a tap ticks it
        v.findViewById<CompoundButton>(R.id.tick).apply {
            visibility = if (selecting) View.VISIBLE else View.GONE
            isChecked = e.fp in ticked
        }
        if (selecting) {
            for (id in listOf(R.id.actions, R.id.pause, R.id.more)) v.findViewById<View>(id).visibility = View.GONE
            v.setOnClickListener {
                allMine = false
                if (!ticked.remove(e.fp)) ticked += e.fp
                render()
            }
        }
        return v
    }

    // --- files on their way (docs/mesh.md §9.10) -----------------------------------------

    private fun renderAllTransfers() {
        if (!::b.isInitialized) return
        for ((fp, card) in cards) renderTransfers(card, fp)
    }

    /**
     * A device's transfers on its card, updated in place (so Cancel stays put
     * as the numbers move): progress, time left and speed, or how it ended;
     * and sends still waiting in the outbox, which can be cancelled too.
     */
    private fun renderTransfers(card: View, fp: String) {
        val box = card.findViewById<LinearLayout>(R.id.transfers)
        val list = Mesh.transfers(fp)
        val waiting = Mesh.node?.outbox?.forPeer(fp).orEmpty()
            .filter { it.optString("kind") == "file" && list.none { t -> t.id == it.optString("id") } }
        val rows = list.map { t ->
            val line = getString(if (t.dir == "out") R.string.transfer_sending else R.string.transfer_receiving) + " · " +
                dev.droplet.app.mesh.Transfers.progressText(t)
            TransferRow(t.id, t.name, line, t.percent, show = t.state in setOf("active", "waiting"),
                cancellable = t.state in setOf("active", "waiting"))
        } + waiting.map { j ->
            val why = j.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            TransferRow(j.getString("id"), j.optString("name"),
                if (why != null) getString(R.string.transfer_waiting, why) else getString(R.string.transfer_queued),
                0, show = false, cancellable = true)
        }
        val want = rows.map { it.id }.toSet()
        for (i in box.childCount - 1 downTo 0) if (box.getChildAt(i).tag !in want) box.removeViewAt(i)
        for (r in rows) {
            val row = (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it.tag == r.id }
                ?: box.inflate(R.layout.item_transfer).also { nv ->
                    nv.tag = r.id
                    nv.findViewById<View>(R.id.cancel).setOnClickListener { cancelTransfer(r.id, r.name) }
                    box.addView(nv)
                }
            row.findViewById<TextView>(R.id.name).text = r.name
            row.findViewById<TextView>(R.id.status).text = r.line
            row.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress).apply {
                visibility = if (r.show) View.VISIBLE else View.GONE
                setProgressCompat(r.percent, false)
            }
            row.findViewById<View>(R.id.cancel).visibility = if (r.cancellable) View.VISIBLE else View.GONE
        }
        box.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private data class TransferRow(val id: String, val name: String, val line: String, val percent: Int,
                                   val show: Boolean, val cancellable: Boolean)

    private fun cancelTransfer(id: String, name: String) {
        background({ Mesh.cancelTransfer(id) }) { say(getString(R.string.transfer_cancelled, name)) }
    }

    // --- several devices at once (docs/mesh.md §9.10) -------------------------------------

    private fun renderSelect(devices: Int) {
        b.select.visibility = if (devices >= 2) View.VISIBLE else View.GONE
        b.select.setText(if (selecting) R.string.home_select_done else R.string.home_select)
        b.selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        if (!selecting) return
        b.selectCount.text = when {
            allMine -> getString(R.string.home_select_mine)
            ticked.isEmpty() -> getString(R.string.home_select_none)
            else -> resources.getQuantityString(R.plurals.home_select_n, ticked.size, ticked.size)
        }
        val any = ticked.isNotEmpty()
        for (v in listOf(b.severalFiles, b.severalMessage, b.severalLink, b.severalClip)) v.alpha = if (any) 1f else PermsUi.DIMMED
    }

    private fun endSelect() {
        selecting = false
        allMine = false
        ticked.clear()
        render()
    }

    /**
     * The devices ticked; with "All my devices", your own that have [cap] on
     * here (paused ones included: theirs waits). Null, saying so, if none.
     */
    private fun severalTargets(cap: String): List<TrustList.Entry>? {
        val peers = Mesh.peers().map { it.entry }
        val out = if (allMine) Perms.ownTargets(peers, cap) else peers.filter { it.fp in ticked }
        if (out.isEmpty()) {
            say(getString(R.string.home_select_none))
            return null
        }
        return out
    }

    private fun severalFiles() {
        val targets = severalTargets(Perms.FILES) ?: return
        sendToMany = targets.map { it.fp }
        try {
            pickFiles.launch(arrayOf("*/*"))
        } catch (x: ActivityNotFoundException) {
            sendToMany = null
            say(getString(R.string.no_file_picker))
        }
    }

    private fun severalClipboard() {
        val targets = severalTargets(Perms.CLIPBOARD) ?: return
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) return say(getString(R.string.clip_empty))
        if (text.toByteArray().size > 256 * 1024) return say(getString(R.string.clip_too_big))
        background({
            val n = Mesh.node ?: error(getString(R.string.mesh_off_now))
            targets.map { e ->
                runCatching { n.clip(e.fp, text) }.fold(
                    { getString(R.string.each_sent, e.label) },
                    { getString(R.string.each_refused, e.label, it.message ?: it.javaClass.simpleName) })
            }
        }) { lines -> results(getString(R.string.home_clip_several), lines) }
    }

    /** A message to the devices ticked: each its own, and its own result. */
    private fun askMessage() {
        val targets = severalTargets(Perms.CHAT) ?: return
        val input = dialogInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "")
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.home_message_several, targets.size, targets.size))
            .setView(input.parent as View)
            .setPositiveButton(R.string.mesh_chat_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                background({
                    val n = Mesh.node ?: error(getString(R.string.mesh_off_now))
                    n.toEach(targets.map { it.fp }, EACH_WAIT_MS) { fp -> n.sendText(fp, text) }.map { eachLine(it) }
                }) { lines -> results(getString(R.string.home_message_sent_several), lines) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun eachLine(r: dev.droplet.app.mesh.MeshNode.Each): String = when (r.outcome) {
        "sent" -> getString(R.string.each_sent, r.name)
        "waiting" -> getString(R.string.each_waiting, r.name, r.text)
        "sending" -> getString(R.string.each_sending, r.name)
        else -> getString(R.string.each_refused, r.name, r.text)
    }

    /**
     * "Send a link…": to one device or several. Filled in from the
     * clipboard when it holds a web link (this screen has focus, so Android
     * lets it read the clipboard). It opens on your own devices; to someone
     * else's it waits with an Open button.
     */
    private fun askLink(targets: List<TrustList.Entry>) {
        val clip = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        val input = dialogInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            dev.droplet.app.mesh.Links.onlyUrl(clip).orEmpty())
        input.hint = getString(R.string.link_hint)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (targets.size == 1) getString(R.string.link_title, targets[0].label)
                else resources.getQuantityString(R.plurals.link_title_several, targets.size, targets.size))
            .setMessage(R.string.link_body)
            .setView(input.parent as View)
            .setPositiveButton(R.string.link_send) { _, _ ->
                val url = input.text.toString().trim()
                val bad = runCatching { dev.droplet.app.mesh.Links.checkUrl(url) }.exceptionOrNull()
                if (bad != null) return@setPositiveButton say(getString(R.string.mesh_failed, bad.message))
                background({
                    targets.map { e ->
                        runCatching { Mesh.sendLink(e.fp, url) }.fold({ r ->
                            when {
                                r.how == "link" -> getString(R.string.each_sent, e.label)
                                else -> {
                                    val j = Mesh.node?.awaitJob(r.job!!.getString("id"), EACH_WAIT_MS)
                                    when (j?.optString("state")) {
                                        dev.droplet.app.mesh.Outbox.DONE -> getString(R.string.each_sent_message, e.label)
                                        dev.droplet.app.mesh.Outbox.FAILED -> getString(R.string.each_refused, e.label, j.optString("error"))
                                        else -> getString(R.string.each_waiting, e.label,
                                            j?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" } ?: getString(R.string.transfer_queued))
                                    }
                                }
                            }
                        }, { getString(R.string.each_refused, e.label, it.message ?: it.javaClass.simpleName) })
                    }
                }) { lines -> if (lines.size == 1) say(lines[0]) else results(getString(R.string.link_sent_several), lines) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Each device's result, one line each. */
    private fun results(title: String, lines: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.ok) { _, _ -> endSelect() }
            .show()
    }

    /** What the owner calls this device here: shown instead of its name, never sent. */
    private fun askNickname(e: TrustList.Entry) {
        val input = dialogInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, e.nickname)
        input.hint = e.name
        input.filters = arrayOf(InputFilter.LengthFilter(TrustList.MAX_NAME))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.nick_title, e.name))
            .setMessage(R.string.nick_body)
            .setView(input.parent as View)
            .setPositiveButton(R.string.s_rename_go) { _, _ ->
                runCatching { Mesh.setNickname(e.fp, input.text.toString()) }
                    .onFailure { say(getString(R.string.mesh_failed, it.message)) }
                render()
            }
            .setNeutralButton(R.string.nick_clear) { _, _ ->
                runCatching { Mesh.setNickname(e.fp, "") }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A text box for a dialog, padded as Settings' rename is. Its parent is the view to give the dialog. */
    private fun dialogInput(type: Int, value: String): EditText {
        val input = EditText(this).apply {
            inputType = type
            setText(value)
            setSelection(text.length)
        }
        FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        return input
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
            v.findViewById<TextView>(R.id.title).text = n?.trust?.get(fp)?.label ?: m.optString("name")
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
            getString(R.string.link_act) to { askLink(listOf(e)) },
            getString(R.string.nick_act) to { askNickname(e) },
            getString(R.string.perm_act) to { startActivity(PermissionsActivity.intent(this, e.fp)) },
            getString(if (e.paused) R.string.perm_act_resume else R.string.perm_act_pause) to { togglePause(e) },
            getString(R.string.mesh_act_ring_stop) to { background({ Mesh.node?.ring(e.fp, stop = true) }) { } },
        )
        if (e.source == TrustList.SOURCE_PAIRED) items += getString(R.string.mesh_act_unpair) to { askUnpair(e) }
        items += getString(R.string.home_about_device) to { about(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** Pause sharing with one device, or resume it (docs/mesh.md §9.9). */
    private fun togglePause(e: TrustList.Entry) {
        val pause = !e.paused
        runCatching { Mesh.setPerms(e.fp, paused = pause) }
            .onSuccess { say(getString(if (pause) R.string.perm_paused_toast else R.string.perm_resumed_toast, e.name)) }
            .onFailure { say(getString(R.string.mesh_failed, it.message ?: it.javaClass.simpleName)) }
        render()
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
            .setTitle(e.label)
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
        private const val STATE_SEND_MANY = "send_many"
        /** How long a message or link to several devices waits for their answers before saying how each went. */
        private const val EACH_WAIT_MS = 10_000L
        const val RELEASES = "https://github.com/Ferinmtk/droplet/releases/latest"
        private const val MESH_TAG = "home"
        private const val ROUTER_TAG = "home"
        private const val RECENT = 4
        /** How long a device's last refusal stays under its name. */
        private const val REFUSAL_SHOWN_MS = 120_000L

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
