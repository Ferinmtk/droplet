package dev.droplet.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.droplet.app.mesh.MeshHost
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.NoRoute
import dev.droplet.app.mesh.Outbox
import dev.droplet.app.mesh.PeerDirectory
import dev.droplet.app.mesh.TrustList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * This phone as a mesh peer (docs/mesh.md): the [MeshNode] and everything
 * Android around it. The node runs while something holds it: the Stay
 * connected service (so other devices can reach the phone), and screens
 * that send (peers, chat, share, the presentation remote).
 *
 * It ties the mesh to the rest of the app: what arrives goes to the same
 * handlers as from the hub (the ringer, the clipboard, the media, SMS and
 * files bridges), the hub's live connection announces this phone to the
 * roster and fetches it, and chat and files fall back to the hub's routes.
 */
@SuppressLint("StaticFieldLeak")  // the application context
object Mesh {
    enum class Status { OFF, STARTING, RUNNING, FAILED }

    data class Snapshot(
        val status: Status = Status.OFF,
        val port: Int = 0,
        val fp: String? = null,
        val peers: Int = 0,
        val links: Int = 0,
        val keyStorage: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Bumped whenever peers, links, pairing or the outbox change: screens redraw. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Incoming pairing requests, for a screen that's open to answer them. */
    private val _pairRequests = MutableSharedFlow<MeshPairing.Request>(extraBufferCapacity = 8)
    val pairRequests: SharedFlow<MeshPairing.Request> = _pairRequests.asSharedFlow()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Live control goes out one at a time, in order (key presses). */
    private val liveOut = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-live").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private lateinit var app: Context

    @Volatile var node: MeshNode? = null
        private set
    private val holders = mutableSetOf<String>()
    private var generation = 0

    // --- seams for the tests (Robolectric has no NsdManager and no MediaStore) ---------

    @VisibleForTesting @Volatile var directoryFactory: (Context) -> PeerDirectory? = { NsdPeerDirectory(it) }
    @VisibleForTesting @Volatile var dirOverride: File? = null
    @VisibleForTesting @Volatile var portOverride: Int? = null
    @VisibleForTesting @Volatile var bindAddress: InetAddress? = null
    @VisibleForTesting @Volatile var retryMs = 15_000L
    @VisibleForTesting @Volatile var addresses: (() -> List<String>)? = null
    /** Where a received file goes; by default Downloads/droplet through MediaStore. */
    @VisibleForTesting @Volatile var saver: (Context, File, String, String) -> Saved = MeshDownloads::save
    @VisibleForTesting @Volatile var log: (String) -> Unit = { android.util.Log.i("droplet-mesh", it) }

    class Saved(val uri: Uri?, val where: String)

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun dir(): File = dirOverride ?: File(app.filesDir, "mesh")

    // --- running ------------------------------------------------------------------

    /** Keeps the mesh up until [release] with the same tag (if it's switched on). */
    @Synchronized
    fun hold(tag: String) {
        if (!holders.add(tag) || holders.size > 1) return
        start()
    }

    @Synchronized
    fun release(tag: String) {
        if (!holders.remove(tag) || holders.isNotEmpty()) return
        stop()
    }

    /** Tests only: forget every holder and stop, so the next test starts from nothing. */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun resetForTests() {
        holders.clear()
        stop()
    }

    /** The switch in Settings changed. */
    @Synchronized
    fun enabledChanged() {
        stop()
        if (holders.isNotEmpty()) start()
    }

    @Synchronized
    private fun start() {
        if (!Prefs.meshEnabled || node != null || _state.value.status == Status.STARTING) return
        _state.value = Snapshot(Status.STARTING)
        val gen = ++generation
        scope.launch {
            val n = try {
                MeshNode(host, dir(), directoryFactory(app), portOverride, retryMs, bindAddress).also { it.start() }
            } catch (e: Exception) {
                log("mesh: couldn't start: $e")
                synchronized(this@Mesh) {
                    if (gen == generation) _state.value = Snapshot(Status.FAILED, error = e.message ?: e.javaClass.simpleName)
                }
                return@launch
            }
            synchronized(this@Mesh) {
                // stopped (or stopped and started again) meanwhile: this one isn't wanted
                if (gen != generation || holders.isEmpty() || !Prefs.meshEnabled) {
                    n.close()
                    if (gen == generation) _state.value = Snapshot(Status.OFF)
                    return@launch
                }
                node = n
                n.chat.onAdded = { bump() }
            }
            publish()
            if (Live.state.value.connected) syncRoster()
            rosterLoop()
        }
    }

    @Synchronized
    private fun stop() {
        generation++
        node?.close()
        node = null
        Presence.release("mesh")
        _state.value = Snapshot(Status.OFF)
        bump()
    }

    private fun rosterLoop() = scope.launch {
        while (isActive && node != null) {
            delay(ROSTER_EVERY_MS)
            if (Live.state.value.connected) syncRoster()
        }
    }

    private fun publish() {
        val n = node ?: return
        val links = n.linkCount()
        _state.value = Snapshot(Status.RUNNING, n.listeningPort, n.identity.fp, n.trust.all().size, links,
            n.identity.storage)
        // the media bridge runs while a direct link can see it
        if (links > 0) Presence.hold(app, "mesh") else Presence.release("mesh")
        bump()
    }

    private fun bump() {
        _changes.value = _changes.value + 1
    }

    // --- the hub --------------------------------------------------------------------

    private val rosterLock = Mutex()

    /** The live connection to the hub is up: remember who this phone is there, announce, fetch the roster. */
    fun onHubUp(deviceId: String?, deviceName: String?) {
        if (deviceId != null && TrustList.PEER_ID.matches(deviceId) && deviceId != Prefs.meshDeviceId) Prefs.meshDeviceId = deviceId
        if (!deviceName.isNullOrBlank() && deviceName != Prefs.meshDeviceName) Prefs.meshDeviceName = deviceName
        node?.refreshAnnouncement()
        syncRoster()
        node?.kick()
    }

    fun onRosterChanged() = syncRoster()

    /** Announce first, then the roster (docs/mesh.md §9.6). The cached roster keeps working if this fails. */
    fun syncRoster() {
        scope.launch {
            val n = node ?: return@launch
            val hubId = Prefs.hubId ?: return@launch
            rosterLock.withLock {
                try {
                    Hub.meshAnnounce(n.announceBody())
                    n.applyRoster(Hub.meshRoster(), hubId)
                } catch (e: Exception) {
                    log("mesh: couldn't update the roster from the hub: ${e.message}")
                }
            }
            publish()
        }
    }

    /** Forget this hub's roster (Settings → Forget this hub); directly paired peers stay. */
    fun forgetHub(hubId: String?) {
        hubId ?: return
        scope.launch { node?.trust?.syncRoster(emptyList(), hubId) }
    }

    fun capsChanged() {
        node?.refreshAnnouncement()
    }

    fun broadcast(msg: JSONObject): Boolean = node?.broadcast(msg) ?: false

    /**
     * This phone's clipboard to your devices that take one, directly. When
     * the hub already took it to all of them ([viaHub]), only peers with an
     * open link get a direct copy too; otherwise each is reached directly,
     * in the background. True if at least one open link had it at once.
     */
    fun clipToPeers(text: String, viaHub: Boolean): Boolean {
        val n = node ?: return false
        val msg = JSONObject().put("t", "clip").put("text", text)
        val peers = n.trust.all().filter { "clipboard" in it.caps }
        var sent = false
        val rest = ArrayList<String>()
        for (e in peers) {
            val link = n.openLink(e.fp)
            if (link != null) sent = link.send(msg) || sent else if (!viaHub) rest += e.fp
        }
        if (rest.isNotEmpty()) scope.launch { for (fp in rest) runCatching { n.direct(fp)?.send(msg) } }
        return sent
    }

    /**
     * Like [clipToPeers] with no hub, but waits: each peer that takes a
     * clipboard is reached directly (dialled if need be). Blocks; the names
     * of the devices that got it.
     */
    fun clipToPeersNow(text: String): List<String> {
        val n = node ?: return emptyList()
        val msg = JSONObject().put("t", "clip").put("text", text)
        return n.trust.all().filter { "clipboard" in it.caps }
            .filter { e -> runCatching { n.direct(e.fp)?.send(msg) == true }.getOrDefault(false) }
            .map { it.name }
    }

    // --- for screens ------------------------------------------------------------------

    data class PeerView(val entry: TrustList.Entry, val route: String)

    /** Peers being looked for right now (see [probe]), so screens can say "looking" rather than "offline". */
    private val probing = ConcurrentHashMap.newKeySet<String>()

    fun isProbing(fp: String): Boolean = fp in probing

    /**
     * Tries to open a link to each peer that has none, in the background, so
     * the home screen shows who's really reachable (a link is only opened when
     * something is sent otherwise). A peer that answers is then "on Wi-Fi".
     */
    fun probe() {
        val n = node ?: return
        for (e in n.trust.all()) {
            if (n.openLink(e.fp) != null || !probing.add(e.fp)) continue
            bump()
            scope.launch {
                try {
                    runCatching { n.direct(e.fp) }
                } finally {
                    probing.remove(e.fp)
                    bump()
                }
            }
        }
    }

    fun peers(): List<PeerView> {
        val n = node ?: return emptyList()
        return n.trust.all().map { PeerView(it, n.route(it)) }
    }

    fun peer(fp: String): TrustList.Entry? = node?.trust?.get(fp)

    /** A trusted peer that is this hub device id, if the mesh knows it. */
    fun peerById(id: String): TrustList.Entry? = node?.trust?.all()?.firstOrNull { it.id == id }

    fun describeRoute(context: Context, route: String): String = context.getString(when (route) {
        "lan" -> R.string.mesh_route_lan
        "tailnet" -> R.string.mesh_route_tailnet
        "seen" -> R.string.mesh_route_seen
        "hub" -> R.string.mesh_route_hub
        "hub-mailbox" -> R.string.mesh_route_mailbox
        else -> R.string.mesh_route_offline
    })

    private fun running(): MeshNode = node ?: throw NoRoute(app.getString(R.string.mesh_off_now))

    /** Live control to a peer, in order, off the main thread. [done] gets the route or the error (on the main thread). */
    fun sendLive(fp: String, msg: JSONObject, done: (Result<String>) -> Unit = {}) {
        scope.launch(liveOut) {
            val r = runCatching { running().sendLive(fp, msg) }
            android.os.Handler(Looper.getMainLooper()).post { done(r) }
        }
    }

    // --- the host: what the node needs from the app ---------------------------------------

    private val quietUntil = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    private val host = object : MeshHost {
        override fun deviceName(): String = Prefs.meshDeviceName ?: Build.MODEL ?: "Android"
        override fun hubDeviceId(): String? = Prefs.meshDeviceId?.takeIf { Prefs.hasHub }
        override fun hubId(): String? = Prefs.hubId?.takeIf { Prefs.hasHub }
        override fun caps(): List<String> = Caps.current(app).sorted()
        override fun lastStates(): Map<String, Any?> = HashMap(States.last)
        override fun localAddresses(): List<String> = addresses?.invoke() ?: lanAddresses()
        override fun log(msg: String) = Mesh.log(msg)
        override fun onChanged() {
            publish()
            bump()
        }

        override fun onText(entry: TrustList.Entry, body: String, ts: Double) {
            if (ChatActivity.showing == entry.fp) return
            notify("chat-${entry.fp.take(16)}", NotificationCompat.Builder(app, Notifs.CH_INBOX)
                .setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(entry.name)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(activity(ChatActivity.intent(app, entry.fp), entry.fp.hashCode())))
        }

        override fun onRing(entry: TrustList.Entry) {
            val id = "mesh-" + ByteArray(6).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            Ringer.start(app, Ring(id, entry.name, System.currentTimeMillis() / 1000.0))
        }

        override fun onRingStop(entry: TrustList.Entry) = Ringer.stop(app, tellHub = false)

        override fun onClip(entry: TrustList.Entry, text: String) {
            if (Prefs.capClipboard) ClipBridge.apply(app, text)
        }

        override fun onNotify(entry: TrustList.Entry, msg: JSONObject) {
            val key = msg.optString("key").ifEmpty { null } ?: return
            val app0 = msg.optString("app").ifEmpty { entry.name }.take(40)
            val title = msg.optString("title").ifEmpty { app0 }
            notify("peer-${entry.fp.take(16)}-$key", NotificationCompat.Builder(app, Notifs.CH_PEERS)
                .setSmallIcon(R.drawable.ic_device)
                .setContentTitle(title)
                .setContentText(msg.optString("text"))
                .setSubText("$app0 · ${entry.name}")
                .setAutoCancel(true))
        }

        override fun onNotifyRemoved(entry: TrustList.Entry, key: String) {
            NotificationManagerCompat.from(app).cancel("peer-${entry.fp.take(16)}-$key", 0)
        }

        override fun onRemote(entry: TrustList.Entry, msg: JSONObject, reply: (JSONObject) -> Boolean) {
            val t = msg.optString("t")
            val caps = Caps.current(app)
            fun refuse(error: String) {
                // one answer per peer and kind every few seconds: a stream of mouse moves gets one
                val k = "${entry.fp}:$t"
                val now = System.currentTimeMillis()
                if ((quietUntil[k] ?: 0) > now) return
                quietUntil[k] = now + 5_000
                reply(JSONObject().put("t", "error").put("re", t).put("error", error))
            }
            when (t) {
                "input" -> refuse(app.getString(R.string.mesh_no_input))
                "cmd" -> refuse(app.getString(R.string.mesh_no_cmd))
                "media" -> if ("media" in caps) MediaBridge.act(app, msg)
                    else refuse(app.getString(R.string.live_err_off, app.getString(R.string.cap_media)))
                "rpc" -> scope.launch { reply(Rpc.answer(app, msg, caps, sender(entry.fp))) }
            }
        }

        override fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String): String {
            val saved = saver(app, part, name, mime)
            // the home screen lists it under Received
            runCatching {
                Received.add(Received.Item(saved.where.substringAfterLast('/'), entry.name, entry.fp, saved.uri?.toString(),
                    mime, saved.where, System.currentTimeMillis()))
            }
            bump()
            val view = saved.uri?.let {
                Intent(Intent.ACTION_VIEW).setDataAndType(it, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            NotificationManagerCompat.from(app).cancel("recv-${entry.fp.take(16)}-$name", 0)
            notify("file-${saved.where}", NotificationCompat.Builder(app, Notifs.CH_INBOX)
                .setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(app.getString(R.string.mesh_file_from, entry.name))
                .setContentText(saved.where)
                .setAutoCancel(true)
                .apply { view?.let { setContentIntent(activity(it, saved.where.hashCode())) } })
            return saved.where
        }

        override fun onReceiving(entry: TrustList.Entry, name: String, got: Long, size: Long) {
            val tag = "recv-${entry.fp.take(16)}-$name"
            if (got < 0) {
                NotificationManagerCompat.from(app).cancel(tag, 0)
                return
            }
            val pct = if (size > 0) (got * 100 / size).toInt() else 100
            notify(tag, NotificationCompat.Builder(app, Notifs.CH_TRANSFERS)
                .setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(app.getString(R.string.mesh_receiving, entry.name))
                .setContentText(name)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true))
        }

        override fun onPairRequest(req: MeshPairing.Request) {
            _pairRequests.tryEmit(req)
            notify("pair-${req.request}", NotificationCompat.Builder(app, Notifs.CH_INBOX)
                .setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(app.getString(R.string.mesh_pair_request, req.name))
                .setContentText(app.getString(R.string.mesh_pair_request_code, req.code))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(activity(PeersActivity.answerIntent(app, req.request), req.request.hashCode())))
        }

        override fun onPairGone(request: String) {
            NotificationManagerCompat.from(app).cancel("pair-$request", 0)
        }

        // the hub's routes (3 and 4)
        override fun hubConnected(): Boolean {
            if (!Prefs.hasHub || !Hub.hasDevice()) return false
            if (Live.state.value.connected || Router.current() != null) return true
            // off the main thread, look for it (a share with Stay connected off)
            return Looper.myLooper() != Looper.getMainLooper() && Router.resolveBlocking() != null
        }

        override fun hubOnline(deviceId: String): Boolean = Live.state.value.connected && deviceId in Live.state.value.peers
        override fun hubSend(msg: JSONObject): Boolean = Live.send(msg)
        override fun hubText(deviceId: String, body: String) = Hub.sendText(body, deviceId)
        override fun hubRing(deviceId: String, stop: Boolean) = Hub.ringDevice(deviceId, stop)
        override fun hubUpload(deviceId: String, source: String, name: String, mime: String, size: Long) {
            val saved = Hub.upload(app, listOf(Outgoing(uriOf(source), name, size, mime)), deviceId) { _, _ -> }
            if (saved.isEmpty()) throw HubException("The hub didn't save it")
        }

        override fun openSource(source: String, from: Long): InputStream {
            if (!source.startsWith("content:")) return FileInputStream(source).also { it.channel.position(from) }
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse(source), "r")
                ?: throw java.io.IOException("can't read it any more")
            val input = FileInputStream(pfd.fileDescriptor)
            return try {
                input.channel.position(from)
                object : java.io.FilterInputStream(input) {
                    override fun close() {
                        super.close()
                        pfd.close()
                    }
                }
            } catch (e: Exception) {
                // a pipe, not a file: read up to the offset
                input.close()
                pfd.close()
                app.contentResolver.openInputStream(Uri.parse(source))!!.also { s ->
                    var left = from
                    while (left > 0) {
                        val n = s.skip(left)
                        if (n <= 0) {
                            if (s.read() < 0) throw java.io.IOException("shorter than expected")
                            left--
                        } else left -= n
                    }
                }
            }
        }

        override fun sourceSize(source: String): Long? {
            if (!source.startsWith("content:")) return File(source).takeIf { it.isFile }?.length()
            val uri = Uri.parse(source)
            runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
                }
            }
            return runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } } }.getOrNull()
        }

        override fun spool(source: String, dest: File) {
            openSource(source, 0).use { i -> dest.outputStream().use { i.copyTo(it) } }
        }
    }

    private fun uriOf(source: String): Uri = if (source.startsWith("content:")) Uri.parse(source) else Uri.fromFile(File(source))

    /** files.get over a direct link: the file goes back to the requester as a mesh offer. */
    private fun sender(fp: String) = FileBridge.Sender { file, mime, progress ->
        val n = running()
        val job = n.sendFile(fp, file.path, file.name, mime ?: "application/octet-stream", file.length())
        val id = job.getString("id")
        while (true) {
            val j = n.awaitJob(id, 1_000) ?: throw RpcError("The transfer was lost")
            progress(n.jobSent(id), file.length())
            when (j.optString("state")) {
                Outbox.DONE -> return@Sender file.name
                Outbox.FAILED -> throw RpcError(j.optString("error").ifEmpty { "The transfer failed" })
                Outbox.QUEUED -> if (j.optInt("attempts") > 0 && !j.optBoolean("retry")) {
                    throw RpcError(app.getString(R.string.mesh_kept_for_later))
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") file.name
    }

    @SuppressLint("MissingPermission")
    private fun notify(tag: String, b: NotificationCompat.Builder) {
        if (Notifs.allowed(app)) NotificationManagerCompat.from(app).notify(tag, 0, b.setColor(0xFF38BDF8.toInt()).build())
    }

    private fun activity(intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(app, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * This phone's addresses on the Wi-Fi (or Ethernet): what peers on the LAN
     * can reach. Not mobile data, VPNs or Tailscale (the roster carries that).
     */
    fun lanAddresses(): List<String> {
        val out = ArrayList<InetAddress>()
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val nc = cm.getNetworkCapabilities(net) ?: continue
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                cm.getLinkProperties(net)?.linkAddresses?.forEach { out += it.address }
            }
        }
        if (out.isEmpty()) runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                val name = ni.name.lowercase()
                if (!ni.isUp || ni.isLoopback || !(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("wl") ||
                        name.startsWith("en") || name.startsWith("swlan") || name.startsWith("ap"))) continue
                out += ni.inetAddresses.toList()
            }
        }
        return out.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            .sortedBy { it !is Inet4Address }
            .map { TrustList.text(it) }
            .filter { !MeshNode.isTailnet(it) }
            .distinct()
    }

    private const val ROSTER_EVERY_MS = 10 * 60_000L
}
