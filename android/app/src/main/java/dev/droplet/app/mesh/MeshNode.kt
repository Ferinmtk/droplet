package dev.droplet.app.mesh

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** A peer announcing itself on the LAN as `_droplet-peer._tcp` (docs/mesh.md §2). A hint only. */
data class Seen(val fp: String, val id: String, val name: String, val os: String, val caps: List<String>,
                val hub: String, val port: Int, val addresses: List<String>)

/** mDNS: announcing this phone, and the peers announcing themselves. */
interface PeerDirectory {
    fun start(port: Int, txt: Map<String, String>, onSeen: (Seen) -> Unit)
    fun update(txt: Map<String, String>)
    fun peers(): List<Seen>
    fun close()
}

/**
 * Where the rest of the app meets the mesh: what the phone does with what
 * arrives, and the hub routes (3 and 4). Every hub call may throw.
 */
interface MeshHost {
    fun deviceName(): String
    fun hubDeviceId(): String?
    fun hubId(): String?
    fun caps(): List<String>
    fun lastStates(): Map<String, Any?>
    fun localAddresses(): List<String>

    fun onText(entry: TrustList.Entry, body: String, ts: Double)
    fun onRing(entry: TrustList.Entry)
    fun onRingStop(entry: TrustList.Entry)
    fun onClip(entry: TrustList.Entry, text: String)
    fun onNotify(entry: TrustList.Entry, msg: JSONObject)
    fun onNotifyRemoved(entry: TrustList.Entry, key: String)
    /** input, media, cmd, rpc from a peer; [reply] answers over the mesh. */
    fun onRemote(entry: TrustList.Entry, msg: JSONObject, reply: (JSONObject) -> Boolean)
    /** Gives a completed download its home (Downloads/droplet). Returns where it went, for the notification. */
    fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String): String
    fun onReceiving(entry: TrustList.Entry, name: String, got: Long, size: Long) {}
    fun onPairRequest(req: MeshPairing.Request) {}
    fun onPairGone(request: String) {}
    fun onChanged() {}
    fun log(msg: String) {}

    fun hubConnected(): Boolean
    fun hubOnline(deviceId: String): Boolean
    fun hubSend(msg: JSONObject): Boolean
    fun hubText(deviceId: String, body: String)
    fun hubUpload(deviceId: String, source: String, name: String, mime: String, size: Long)
    fun hubRing(deviceId: String, stop: Boolean)

    /** A file job's bytes from [from]: a content URI or a path. */
    fun openSource(source: String, from: Long): InputStream
    /** Its size now, or null if it's gone. */
    fun sourceSize(source: String): Long?
    /** Copies a content URI into [dest] (a job that has to wait keeps its own copy). */
    fun spool(source: String, dest: File)
}

class NoRoute(message: String) : Exception(message)

/**
 * This phone as a mesh peer (docs/mesh.md): links to other devices, what
 * arrives over them, and how messages leave. The Android port of the Linux
 * agent's node.py, the reference; the routing order is the same:
 *
 * 1. direct LAN: an open link, or a new one to an address from mDNS or one that worked before;
 * 2. direct tailnet: the peer's tailnet address, from the roster;
 * 3. through the hub, when it's connected and knows the peer;
 * 4. the hub's mailbox, when the hub knows the peer but the peer is offline;
 * 5. the outbox: kept here, and sent when the peer or the hub appears.
 *
 * Live control (`input`, `media`, `cmd`), `clip` and `ring` use 1–3 only;
 * chat and files use 1–5.
 */
class MeshNode(
    private val host: MeshHost,
    private val dir: File,
    private val directory: PeerDirectory?,
    private val port: Int? = null,
    private val retryEveryMs: Long = 15_000,
    private val bindAddress: InetAddress? = null,
) : MeshServer.Handler {
    val identity: MeshIdentity = MeshIdentity.loadOrCreate(File(dir, "identity"))
    val trust = TrustList(File(dir, "trust.json"), identity.fp)
    val incoming = MeshPairing.Incoming(identity.fp)
    val outgoing = ConcurrentHashMap<String, MeshPairing.Outgoing>()
    private val offers = MeshFiles.Offers()
    private val completed = MeshFiles.Completed(File(dir, "received.json"))
    val outbox = Outbox(File(dir, "outbox.json"))
    val chat = Chat(File(dir, "chat.jsonl"))
    private val incomingDir = File(dir, "incoming")
    private val sendingDir = File(dir, "sending")
    private val links = ConcurrentHashMap<String, MutableList<Link>>()
    val peerState = ConcurrentHashMap<String, MutableMap<String, Any?>>()
    private val dialLocks = ConcurrentHashMap<String, Any>()
    private val acks = ConcurrentHashMap<String, Waiter>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val workers = ConcurrentHashMap.newKeySet<String>()
    private val kick = java.util.concurrent.Semaphore(0)
    @Volatile var stopped = false; private set
    private var server: MeshServer? = null
    val listeningPort: Int get() = server?.port ?: 0
    val refused: Int get() = server?.refused?.get() ?: 0
    @Volatile private var announced: Map<String, String> = emptyMap()

    private class Waiter(val fp: String) {
        val done = CountDownLatch(1)
        @Volatile var ok = false
        @Volatile var error = ""
    }

    init {
        trust.onChange = { trustChanged() }
        incoming.onReady = { host.onPairRequest(it); host.onChanged() }
        incoming.onGone = { host.onPairGone(it); host.onChanged() }
    }

    // --- who we are -------------------------------------------------------------

    val peerId: String get() = host.hubDeviceId()?.takeIf { TrustList.PEER_ID.matches(it) } ?: identity.localId

    fun txt(): Map<String, String> = linkedMapOf("id" to peerId, "fp" to identity.fp,
        "name" to TrustList.cleanName(host.deviceName()).take(63), "os" to "android",
        "caps" to TrustList.cleanCaps(host.caps()).joinToString(","), "hub" to (host.hubId() ?: ""), "v" to "1")

    fun hello(t: String = "hello"): JSONObject = JSONObject().put("t", t).put("id", peerId).put("name", host.deviceName())
        .put("caps", JSONArray(host.caps())).put("os", "android").put("v", 1).put("port", listeningPort)

    /** What the hub's roster needs from this phone (POST /api/mesh/announce). */
    fun announceBody(): JSONObject = JSONObject().put("fp", identity.fp).put("cert_pem", identity.pem).put("port", listeningPort)
        .put("lan", JSONArray(TrustList.cleanAddresses(host.localAddresses().filter { !isTailnet(it) })))
        .put("os", "android").put("caps", JSONArray(host.caps())).put("v", 1)

    // --- lifecycle --------------------------------------------------------------

    fun start() {
        val s = MeshServer(MeshTls.serverContext(identity) { trust.isTrusted(it) }, this, port, bindAddress)
        server = s
        s.start()
        host.log("mesh: listening on port ${s.port} as $peerId (fingerprint ${identity.fp})")
        announced = txt()
        directory?.start(s.port, announced) { seen -> onSeen(seen) }
        thread(name = "mesh-deliver", isDaemon = true) { deliverLoop() }
        thread(name = "mesh-housekeeping", isDaemon = true) { housekeeping() }
        kick()
    }

    fun close() {
        stopped = true
        kick.release(4)
        runCatching { directory?.close() }
        server?.close()
        links.values.flatten().forEach { it.close(1001, "going away") }
    }

    fun kick() {
        if (kick.availablePermits() == 0) kick.release()
    }

    /** Announce again if the name, the hub or the caps changed. */
    fun refreshAnnouncement() {
        val t = txt()
        if (t != announced) {
            announced = t
            runCatching { directory?.update(t) }
        }
    }

    private fun housekeeping() {
        var n = 0
        while (!stopped) {
            Thread.sleep(5_000)
            if (stopped) return
            val now = System.currentTimeMillis()
            for (link in links.values.flatten()) {
                if (link is InboundLink) link.watch(now)
                if (link.outbound && now - link.lastUsed > IDLE_CLOSE_MS) link.close(1000, "idle")
            }
            if (++n % 6 == 0) runCatching { refreshAnnouncement() }
        }
    }

    // --- trust ------------------------------------------------------------------

    override fun trusted(fp: String): Boolean = trust.isTrusted(fp)

    private fun trustChanged() {
        for ((fp, list) in links) if (!trust.isTrusted(fp)) list.toList().forEach { it.close(1008, "not trusted any more") }
        for (j in outbox.queued()) if (!trust.isTrusted(j.getString("fp"))) {
            outbox.update(j.getString("id"), "state" to Outbox.FAILED, "error" to "that peer isn't trusted any more")
        }
        host.onChanged()
    }

    /** Trust exactly the hub's roster (and whoever was paired directly). */
    fun applyRoster(data: JSONObject, hubId: String): Pair<Int, Int> {
        val peers = data.optJSONArray("peers") ?: throw IllegalArgumentException("the hub's roster wasn't a list of peers")
        val entries = ArrayList<TrustList.Entry>()
        for (i in 0 until peers.length()) {
            val p = peers.optJSONObject(i) ?: continue
            if (p.optString("fp") == identity.fp) continue
            try {
                entries += TrustList.makeEntry(peerId = p.opt("id") as? String, name = p.optString("name"),
                    certPem = p.opt("cert_pem") as? String, source = TrustList.SOURCE_ROSTER,
                    lan = strings(p.optJSONArray("lan")), port = p.opt("port"),
                    tailnetIp = (p.opt("tailnet_ip") as? String), os = p.optString("os"),
                    caps = strings(p.optJSONArray("caps")), fp = p.opt("fp") as? String, hub = hubId)
            } catch (e: IllegalArgumentException) {
                host.log("mesh: skipping a roster entry for ${p.optString("name")}: ${e.message}")
            }
        }
        val (added, removed) = trust.syncRoster(entries, hubId)
        if (added > 0 || removed > 0) host.log("mesh: the hub's roster: ${entries.size} peer(s), $added new, $removed removed")
        kick()
        host.onChanged()
        return added to removed
    }

    // --- links ------------------------------------------------------------------

    abstract inner class Link(val fp: String, val address: String, val outbound: Boolean) {
        @Volatile var kind = if (isTailnet(address)) "tailnet" else "lan"
        @Volatile var hello: JSONObject? = null
        val ready = CountDownLatch(1)
        @Volatile var port: Int? = null
        val opened = System.currentTimeMillis()
        @Volatile var lastUsed = System.currentTimeMillis()
        private val finished = AtomicBoolean(false)
        val isReady get() = ready.count == 0L
        abstract val closed: Boolean
        protected abstract fun sendText(text: String): Boolean
        abstract fun close(code: Int, reason: String)

        fun send(msg: JSONObject): Boolean {
            if (closed) return false
            val text = msg.toString()
            if (text.toByteArray().size > WsServerConn.MAX_MESSAGE) {
                host.log("mesh: not sending a ${msg.optString("t")} message of ${text.length} bytes: over the frame limit")
                return false
            }
            val ok = sendText(text)
            if (ok) lastUsed = System.currentTimeMillis()
            return ok
        }

        /** Called once, when it ends. */
        protected fun finished() {
            if (!finished.compareAndSet(false, true)) return
            links[fp]?.let { list -> synchronized(list) { list.remove(this) } }
            links.computeIfPresent(fp) { _, l -> if (l.isEmpty()) null else l }
            if (isReady) host.log("mesh: link with ${hello?.optString("name") ?: fp.take(12)} ($address) closed")
            host.onChanged()
        }

        fun received(text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            lastUsed = System.currentTimeMillis()
            try {
                onMessage(this, msg)
            } catch (e: Exception) {
                host.log("mesh: handling ${msg.optString("t")} from $address: $e")
            }
        }
    }

    /** A link a peer opened to this phone's server. */
    inner class InboundLink(private val conn: WsServerConn, fp: String, address: String) : Link(fp, address, false) {
        @Volatile private var lastPing = 0L
        override val closed get() = conn.closed
        override fun sendText(text: String) = conn.sendText(text)
        override fun close(code: Int, reason: String) {
            conn.close(code, reason)
            finished()
        }

        fun run() = thread(name = "mesh-link-${fp.take(8)}", isDaemon = true) {
            try {
                while (!conn.closed) received(conn.readMessage() ?: break)
            } catch (e: Exception) {
                // the peer went away
            } finally {
                conn.shutdown()
                finished()
            }
        }

        /** §9.4: ping after 20 s of silence, give up after 60 s without hearing anything. */
        fun watch(now: Long) {
            val quiet = now - conn.lastRx
            if (quiet > DEAD_AFTER_MS) {
                host.log("mesh: link with $address went quiet; closing it")
                close(1001, "no answer")
            } else if (quiet > IDLE_PING_MS && now - lastPing > IDLE_PING_MS) {
                lastPing = now
                conn.ping()
            }
        }
    }

    /** A link this phone dialled, over OkHttp's WebSocket (which pings every 20 s). */
    inner class OutboundLink(fp: String, address: String) : Link(fp, address, true) {
        @Volatile var ws: WebSocket? = null
        @Volatile private var isClosed = false
        val open = CountDownLatch(1)
        @Volatile var failure: String? = null
        override val closed get() = isClosed
        override fun sendText(text: String) = ws?.send(text) ?: false
        override fun close(code: Int, reason: String) {
            isClosed = true
            // graceful: what was queued before (an unpair, an ack) still goes out first
            runCatching { ws?.close(code, reason) }
            finished()
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = open.countDown()
            override fun onMessage(webSocket: WebSocket, text: String) = received(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isClosed = true
                finished()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isClosed = true
                finished()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                isClosed = true
                open.countDown()
                finished()
            }
        }
    }

    private fun addLink(link: Link) {
        links.getOrPut(link.fp) { java.util.Collections.synchronizedList(ArrayList()) }.add(link)
    }

    override fun onLink(socket: SSLSocket, head: MeshServer.Request, fp: String, address: String, leftover: ByteArray) {
        val conn = WsServerConn.accept(socket, head, leftover)
        if (conn == null) {
            runCatching { socket.close() }
            return
        }
        val link = InboundLink(conn, fp, address)
        addLink(link)
        link.run()
        thread(isDaemon = true) {
            if (!link.ready.await(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) link.close(1008, "expected hello")
        }
    }

    private fun dial(entry: TrustList.Entry, address: String, port: Int, kind: String): Link? {
        val timeout = if (kind == "tailnet") TAILNET_TIMEOUT_MS else LAN_TIMEOUT_MS
        val client = MeshTls.client(identity, entry.fp).newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout * 3 + 5_000, TimeUnit.MILLISECONDS)   // the TLS and WebSocket handshakes
            .pingInterval(IDLE_PING_MS, TimeUnit.MILLISECONDS)
            .build()
        val link = OutboundLink(entry.fp, address)
        link.kind = kind
        link.port = port
        val ws = client.newWebSocket(Request.Builder().url("wss://${hostPort(address, port)}/mesh")
            .header("User-Agent", MeshPairing.USER_AGENT).build(), link.listener)
        link.ws = ws
        if (!link.open.await(timeout * 3 + 10_000, TimeUnit.MILLISECONDS) || link.closed) {
            ws.cancel()
            host.log("mesh: couldn't open a link to ${entry.name} at $address:$port: ${link.failure ?: "no answer"}")
            return null
        }
        addLink(link)
        link.send(hello())
        if (!link.ready.await(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS) || link.closed) {
            link.close(1008, "no welcome")
            return null
        }
        trust.learn(entry.fp, address = address, port = port, tailnet = kind == "tailnet")
        return link
    }

    private fun candidates(entry: TrustList.Entry): List<Triple<String, Int, String>> {
        val out = ArrayList<Triple<String, Int, String>>()
        val port = entry.port ?: DEFAULT_PORT
        directory?.peers()?.filter { it.fp == entry.fp }?.forEach { s ->
            s.addresses.forEach { a -> out += Triple(a, s.port, if (isTailnet(a)) "tailnet" else "lan") }
        }
        entry.lan.forEach { a -> out += Triple(a, port, if (isTailnet(a)) "tailnet" else "lan") }
        entry.tailnetIp?.let { out += Triple(it, port, "tailnet") }
        return out.sortedBy { it.third == "tailnet" }.distinctBy { it.first to it.second }
    }

    fun openLink(fp: String): Link? =
        links[fp]?.let { l -> synchronized(l) { l.filter { it.isReady && !it.closed }.maxByOrNull { it.opened } } }

    /** An open link with the peer (routes 1–2), dialling one if need be. Blocks. */
    fun direct(fp: String, dial: Boolean = true): Link? {
        openLink(fp)?.let { return it }
        if (!dial || stopped) return null
        val entry = trust.get(fp) ?: return null
        synchronized(dialLocks.getOrPut(fp) { Any() }) {
            openLink(fp)?.let { return it }
            for ((address, port, kind) in candidates(entry)) {
                val link = dial(entry, address, port, kind)
                if (link != null) {
                    host.log("mesh: link open with ${entry.name} over $kind ($address:$port)")
                    return link
                }
            }
        }
        return null
    }

    /** Sends to every peer with an open link (state, for instance). */
    fun broadcast(msg: JSONObject): Boolean {
        var sent = false
        for (fp in links.keys) openLink(fp)?.let { sent = it.send(msg) || sent }
        return sent
    }

    fun linkCount(): Int = links.keys.count { openLink(it) != null }

    // --- what arrives -------------------------------------------------------------

    private fun onMessage(link: Link, msg: JSONObject) {
        val entry = trust.get(link.fp)
        if (entry == null) {
            link.close(1008, "not trusted")
            return
        }
        val t = msg.optString("t")
        if (!link.isReady) {
            if ((t == "hello" && !link.outbound) || (t == "welcome" && link.outbound)) {
                link.hello = msg
                val port = (msg.opt("port") as? Int)?.takeIf { it in 1..65535 }
                if (!link.outbound) link.port = port
                trust.learn(link.fp, address = if (link.outbound) null else link.address, port = port,
                    name = msg.opt("name") as? String, peerId = msg.opt("id") as? String, os = msg.opt("os") as? String,
                    caps = msg.optJSONArray("caps")?.let { strings(it) }, tailnet = link.kind == "tailnet")
                if (t == "hello") {
                    link.send(hello("welcome"))
                    host.log("mesh: ${entry.name} connected from ${link.address}")
                }
                link.ready.countDown()
                for ((kind, data) in host.lastStates()) {
                    link.send(JSONObject().put("t", "state").put("kind", kind).put("data", data ?: JSONObject.NULL))
                }
                kick()
                host.onChanged()
            }
            return
        }
        val current = trust.get(link.fp) ?: return
        when (t) {
            "ping" -> link.send(JSONObject().put("t", "pong"))
            "text" -> recvText(link, current, msg)
            "ack", "nack" -> {
                val id = msg.opt("id") as? String ?: return
                val err = msg.optString("error").take(200)
                acks[id]?.takeIf { it.fp == link.fp }?.let { w ->
                    w.ok = t == "ack"
                    w.error = err
                    w.done.countDown()
                }
                offers.resolve(link.fp, id, t == "ack", err)
            }
            "offer" -> recvOffer(link, current, msg)
            "ring" -> host.onRing(current)
            "ring-stop" -> host.onRingStop(current)
            "notify" -> host.onNotify(current, msg)
            "notify-removed" -> (msg.opt("key") as? String)?.let { host.onNotifyRemoved(current, it) }
            "unpair" -> if (current.source == TrustList.SOURCE_PAIRED) {
                host.log("mesh: ${current.name} unpaired from this phone")
                trust.remove(link.fp)
            } else {
                host.log("mesh: ${current.name} asked to unpair, but the hub vouches for it; remove it on the hub")
            }
            "state" -> {
                val kind = msg.optString("kind").take(20)
                if (kind.isNotEmpty()) peerState.getOrPut(link.fp) { ConcurrentHashMap() }[kind] = msg.opt("data")
            }
            "clip" -> (msg.opt("text") as? String)?.let { if (it.isNotEmpty()) host.onClip(current, it) }
            "input", "media", "cmd", "rpc" -> {
                val out = JSONObject(msg.toString())
                out.remove("to")
                // who sent it is the authenticated peer, whatever the message says
                out.put("from", JSONObject().put("id", current.id).put("name", current.name))
                host.onRemote(current, out) { reply -> link.send(reply) || (direct(link.fp)?.send(reply) ?: false) }
            }
            // hello, welcome, pong, rpc-result and anything newer: nothing to do
        }
    }

    private fun recvText(link: Link, entry: TrustList.Entry, msg: JSONObject) {
        val mid = msg.opt("id") as? String ?: return
        if (!Regex("^[0-9A-Za-z_-]{8,64}$").matches(mid)) return
        val body = msg.opt("body") as? String
        if (body.isNullOrEmpty() || body.toByteArray(Charsets.UTF_8).size > MAX_TEXT) {
            link.send(JSONObject().put("t", "nack").put("id", mid).put("error", "empty or too long"))
            return
        }
        val ts = (msg.opt("ts") as? Number)?.toDouble() ?: (System.currentTimeMillis() / 1000.0)
        val new = chat.add(JSONObject().put("id", "${link.fp.take(16)}:$mid").put("dir", "in").put("fp", link.fp)
            .put("peer", entry.id).put("name", entry.name).put("body", body).put("ts", ts))
        link.send(JSONObject().put("t", "ack").put("id", mid))
        if (new) host.onText(entry, body, ts)
    }

    private fun recvOffer(link: Link, entry: TrustList.Entry, msg: JSONObject) {
        val c = try {
            MeshFiles.checkOffer(msg)
        } catch (e: MeshFiles.DownloadError) {
            link.send(JSONObject().put("t", "nack").put("id", msg.opt("id") as? String ?: "").put("error", e.message))
            return
        }
        if (completed.has(link.fp, c.id) != null) {
            link.send(JSONObject().put("t", "ack").put("id", c.id))   // already here: the last ack was lost
            return
        }
        val key = "${link.fp}:${c.id}"
        if (!downloading.add(key)) return
        val hosts = ArrayList<Pair<String, Int>>()
        hosts += link.address to (link.port ?: entry.port ?: DEFAULT_PORT)
        candidates(entry).forEach { (a, p, _) -> if ((a to p) !in hosts) hosts += a to p }
        thread(name = "mesh-download", isDaemon = true) { download(entry, c, hosts, key) }
    }

    private fun download(entry: TrustList.Entry, c: MeshFiles.Checked, hosts: List<Pair<String, Int>>, key: String) {
        var reply: JSONObject? = null
        try {
            host.log("mesh: receiving ${c.name} (${c.size} bytes) from ${entry.name}")
            var lastNote = 0L
            val part = MeshFiles.download(identity, entry.fp, hosts, c, incomingDir, log = host::log, stopped = { stopped },
                sleep = { ms -> var left = ms; while (left > 0 && !stopped) { Thread.sleep(minOf(left, 200)); left -= 200 } },
                onProgress = { got, size ->
                val now = System.currentTimeMillis()
                if (now - lastNote > 500 || got == size) {
                    lastNote = now
                    host.onReceiving(entry, c.name, got, size)
                }
            })
            val where = host.saveFile(entry, part, c.name, c.mime)
            part.delete()
            completed.add(entry.fp, c.id, where)
            host.log("mesh: saved ${c.name} from ${entry.name} to $where")
            reply = JSONObject().put("t", "ack").put("id", c.id)
        } catch (e: MeshFiles.DownloadError) {
            host.log("mesh: receiving ${c.name} from ${entry.name} failed: ${e.message}")
            if (e.permanent) reply = JSONObject().put("t", "nack").put("id", c.id).put("error", e.message)
        } catch (e: Exception) {
            host.log("mesh: receiving ${c.name} failed: $e")
            // saving failed (no space, storage gone): the part stays, a re-offer resumes
        } finally {
            downloading.remove(key)
            host.onReceiving(entry, c.name, -1, c.size)
        }
        reply?.let { r ->
            if (direct(entry.fp)?.send(r) != true) host.log("mesh: couldn't tell ${entry.name} about ${c.name}; it will offer it again")
        }
    }

    // --- sending: live messages (routes 1–3) --------------------------------------

    private fun hubKnows(entry: TrustList.Entry): Boolean {
        val hubId = host.hubId()
        return hubId != null && entry.hub == hubId && host.hubConnected()
    }

    private fun hubHasItLive(entry: TrustList.Entry) = hubKnows(entry) && host.hubOnline(entry.id)

    private fun entry(fp: String): TrustList.Entry = trust.get(fp) ?: throw NoRoute("that peer isn't trusted")

    /** input, media, cmd: direct, else through the hub. Returns the route. Blocks. */
    fun sendLive(fp: String, msg: JSONObject): String {
        val e = entry(fp)
        direct(fp)?.let { if (it.send(msg)) return it.kind }
        if (hubHasItLive(e) && host.hubSend(JSONObject(msg.toString()).put("to", e.id))) return "hub"
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    fun ring(fp: String, stop: Boolean = false): String {
        val e = entry(fp)
        direct(fp)?.let { if (it.send(JSONObject().put("t", if (stop) "ring-stop" else "ring"))) return it.kind }
        if (hubKnows(e)) {
            host.hubRing(e.id, stop)
            return "hub"
        }
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    fun clip(fp: String, text: String): String {
        val e = entry(fp)
        require(text.isNotEmpty() && text.toByteArray().size <= 256 * 1024) { "clipboard text must be 1 byte to 256 KB" }
        direct(fp)?.let { if (it.send(JSONObject().put("t", "clip").put("text", text))) return it.kind }
        // the hub has no addressed clipboard message: it goes to all your devices' clipboards
        if (hubHasItLive(e) && host.hubSend(JSONObject().put("t", "clip").put("text", text))) return "hub"
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    // --- sending: chat and files (routes 1–5) --------------------------------------

    fun sendText(fp: String, body: String): JSONObject {
        val e = entry(fp)
        require(body.isNotBlank() && body.toByteArray().size <= MAX_TEXT) { "a message must be 1 byte to 64 KB of text" }
        val job = outbox.addText(fp, e.name, body)
        kick()
        return job
    }

    /** A file from a content URI or a path; [size] as the source reports it. */
    fun sendFile(fp: String, source: String, name: String, mime: String, size: Long, spooled: Boolean = false): JSONObject {
        val e = entry(fp)
        val job = outbox.addFile(fp, e.name, source, MeshFiles.safeName(name), mime.ifEmpty { "application/octet-stream" },
            size, spooled)
        kick()
        return job
    }

    /** Waits for a job to be delivered, fail, or find no route (not a transfer being retried). */
    fun awaitJob(id: String, timeoutMs: Long): JSONObject? = outbox.await(id, timeoutMs) { j ->
        val s = j.optString("state")
        s == Outbox.DONE || s == Outbox.FAILED || (s == Outbox.QUEUED && j.optInt("attempts") > 0 && !j.optBoolean("retry"))
    }

    /** Serving files: bytes a second, 0 for no limit. */
    var offerRate: Long
        get() = offers.maxRate
        set(v) { offers.maxRate = v }

    /** Bytes of a file job served so far, for progress. */
    fun jobSent(id: String): Long = offers.get(id)?.sent ?: 0L

    private fun deliverLoop() {
        while (!stopped) {
            kick.tryAcquire(retryEveryMs, TimeUnit.MILLISECONDS)
            kick.drainPermits()
            if (stopped) return
            for (fp in outbox.queued().map { it.getString("fp") }.distinct()) {
                if (!workers.add(fp)) continue
                thread(name = "mesh-deliver", isDaemon = true) { workPeer(fp) }
            }
        }
    }

    /** Delivers one peer's jobs in order, until one has to wait for a route. */
    private fun workPeer(fp: String) {
        try {
            while (!stopped) {
                val jobs = synchronized(workers) { outbox.forPeer(fp) }
                if (jobs.isEmpty()) break
                if (attempt(jobs[0]) == "wait") break
            }
        } catch (e: Exception) {
            host.log("mesh: delivering to ${fp.take(12)}: $e")
        } finally {
            workers.remove(fp)
        }
    }

    private fun finish(job: JSONObject, state: String, route: String? = null, error: String? = null) {
        outbox.update(job.getString("id"), "state" to state, "route" to route, "error" to error)
        if ((state == Outbox.DONE || state == Outbox.FAILED) && job.optBoolean("spooled")) File(job.getString("source")).delete()
        if (state == Outbox.DONE && job.getString("kind") == "text") {
            val e = trust.get(job.getString("fp"))
            chat.add(JSONObject().put("id", job.getString("id")).put("dir", "out").put("fp", job.getString("fp"))
                .put("peer", e?.id).put("name", e?.name ?: job.optString("peer")).put("body", job.getString("body"))
                .put("ts", job.optDouble("created")).put("route", route))
        }
        if (state == Outbox.DONE) host.log("mesh: ${if (job.getString("kind") == "text") "message" else job.optString("name")} to ${job.optString("peer")} delivered ($route)")
        else if (state == Outbox.FAILED) host.log("mesh: ${job.getString("kind")} to ${job.optString("peer")} failed: $error")
        host.onChanged()
    }

    private fun fileChanged(job: JSONObject): String? {
        val now = runCatching { host.sourceSize(job.getString("source")) }.getOrNull() ?: return "${job.optString("name")} is gone"
        return if (now != job.getLong("size")) "${job.optString("name")} changed after it was sent" else null
    }

    /** The job waits: a file keeps its own copy from here, since a URI grant dies with the process. */
    private fun keepWaiting(job: JSONObject, error: String, retry: Boolean) {
        var fields = arrayOf<Pair<String, Any?>>("state" to Outbox.QUEUED, "error" to error, "retry" to retry)
        if (job.getString("kind") == "file" && !job.optBoolean("spooled")) {
            val dest = File(sendingDir, "${job.getString("id")}-${MeshFiles.safeName(job.optString("name"))}")
            try {
                sendingDir.mkdirs()
                host.spool(job.getString("source"), dest)
                if (dest.length() == job.getLong("size")) {
                    fields += arrayOf<Pair<String, Any?>>("source" to dest.path, "spooled" to true)
                } else dest.delete()
            } catch (e: Exception) {
                dest.delete()
            }
        }
        outbox.update(job.getString("id"), *fields)
        host.onChanged()
    }

    private fun attempt(job: JSONObject): String {
        val id = job.getString("id")
        val e = trust.get(job.getString("fp"))
        if (e == null) {
            finish(job, Outbox.FAILED, error = "that peer isn't trusted any more")
            return "done"
        }
        if (job.getString("kind") == "file") fileChanged(job)?.let {
            finish(job, Outbox.FAILED, error = it)
            return "done"
        }
        outbox.update(id, "state" to Outbox.SENDING, "attempts" to job.optInt("attempts") + 1)
        val link = direct(e.fp)
        if (link != null) {
            val got = if (job.getString("kind") == "text") directText(link, job) else directFile(link, job)
            if (got == "ok") {
                finish(job, Outbox.DONE, route = link.kind)
                return "done"
            }
            if (got.startsWith("refused:")) {
                finish(job, Outbox.FAILED, error = got.removePrefix("refused:").trim().ifEmpty { "the peer refused it" })
                return "done"
            }
            // the peer is there but it didn't finish: try again soon, directly
            keepWaiting(job, got, retry = true)
            thread(isDaemon = true) { Thread.sleep(3_000); kick() }
            return "wait"
        }
        val err = if (hubKnows(e)) {
            try {
                if (job.getString("kind") == "text") host.hubText(e.id, job.getString("body"))
                else host.hubUpload(e.id, job.getString("source"), job.getString("name"), job.getString("mime"), job.getLong("size"))
                finish(job, Outbox.DONE, route = if (host.hubOnline(e.id)) "hub" else "hub-mailbox")
                return "done"
            } catch (x: Exception) {
                host.log("mesh: through the hub failed: $x")
                "the hub: ${x.message}"
            }
        } else "not reachable directly, and no hub knows it right now"
        keepWaiting(job, err, retry = false)
        return "wait"
    }

    private fun directText(link: Link, job: JSONObject): String {
        val w = Waiter(link.fp)
        val id = job.getString("id")
        acks[id] = w
        try {
            val msg = JSONObject().put("t", "text").put("id", id).put("body", job.getString("body")).put("ts", job.optDouble("created"))
            if (!link.send(msg)) return "the link dropped"
            if (!w.done.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "no answer"
            return if (w.ok) "ok" else "refused: ${w.error}"
        } finally {
            acks.remove(id)
        }
    }

    private fun directFile(link: Link, job: JSONObject): String {
        val source = job.getString("source")
        val offer = MeshFiles.Offer(job.getString("id"), link.fp, job.getString("name"), job.getLong("size"),
            job.getString("mime"), open = { from -> host.openSource(source, from) }, check = { fileChanged(job) })
        offers.add(offer)
        try {
            if (!link.send(offer.message())) return "the link dropped"
            while (!offer.await(1_000)) {
                if (stopped) return "stopping"
                val idle = System.currentTimeMillis() - offer.lastActivity
                // gone quiet, or the peer went away: offer it again later, and it resumes
                if (idle > STALL_MS || (link.closed && idle > 5_000)) return "stopped at ${offer.sent} bytes sent"
                if (!trust.isTrusted(link.fp)) return "refused: not trusted any more"
            }
            val (ok, error) = offer.result ?: (false to "")
            return if (ok) "ok" else "refused: $error"
        } finally {
            offers.remove(job.getString("id"))
        }
    }

    override fun serveFile(out: OutputStream, oid: String, fp: String, req: MeshServer.Request,
                           sendHead: (Int, Map<String, String>) -> Unit) =
        offers.serve(out, oid, fp, req.header("range"), req.method, sendHead)

    // --- pairing --------------------------------------------------------------------

    override fun pair(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        if (path == "/mesh/pair") {
            if (method != "POST") return 405 to JSONObject().put("error", "POST")
            return incoming.open(body, peerId, host.deviceName())
        }
        val m = Regex("^/mesh/pair/([0-9a-f]{32})(/confirm|/cancel)?$").matchEntire(path)
            ?: return 404 to JSONObject().put("error", "not found")
        val rid = m.groupValues[1]
        val action = m.groupValues[2]
        return when {
            action.isEmpty() && method == "GET" -> incoming.status(rid)
            action == "/confirm" && method == "POST" -> incoming.confirm(rid, body)
            action == "/cancel" && method == "POST" -> incoming.cancel(rid)
            else -> 405 to JSONObject().put("error", "method not allowed")
        }
    }

    /** Steps 1 and 2 with [address]:[port]. Blocks. Throws with a reason people can read. */
    fun pairStart(address: String, port: Int, expectFp: String?): MeshPairing.Outgoing {
        val og = MeshPairing.Outgoing(identity, peerId, host.deviceName(), address, port, expectFp)
        try {
            og.start()
        } catch (e: MeshPairing.PairError) {
            throw IllegalArgumentException(e.message)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException(if (MeshTlsErrors.mismatch(e) != null) "that isn't the device it claimed to be"
                else "couldn't reach $address:$port: ${e.message}")
        }
        outgoing[og.request!!] = og
        return og
    }

    /** The owner compared the codes: [yes] they match. Waits for the other side in the background. */
    fun pairConfirm(rid: String, yes: Boolean, onDone: (String) -> Unit = {}) {
        val og = outgoing[rid] ?: throw IllegalArgumentException("no such pairing request")
        if (!yes) {
            thread(isDaemon = true) { og.cancel() }
            og.state = MeshPairing.CANCELLED
            onDone(og.state)
            return
        }
        og.localOk = true
        thread(name = "mesh-pair", isDaemon = true) {
            val end = System.currentTimeMillis() + MeshPairing.REQUEST_TTL_MS
            var result = MeshPairing.EXPIRED
            while (System.currentTimeMillis() < end && !stopped) {
                val state = runCatching { og.poll() }.getOrDefault(MeshPairing.WAITING)
                if (state == MeshPairing.ACCEPTED) {
                    // both: the other side accepted, and the owner here saw the codes match
                    result = try {
                        val entry = TrustList.makeEntry(peerId = og.peerId, name = og.peerName,
                            certPem = MeshIdentity.toPem(og.der!!), source = TrustList.SOURCE_PAIRED, os = og.peerOs,
                            port = og.port, lan = if (isTailnet(og.host)) emptyList() else listOf(og.host),
                            tailnetIp = og.host.takeIf { isTailnet(it) })
                        trust.addPaired(entry)
                        host.log("mesh: paired with ${entry.name} (${entry.fp})")
                        MeshPairing.ACCEPTED
                    } catch (e: IllegalArgumentException) {
                        host.log("mesh: pairing failed: ${e.message}")
                        MeshPairing.EXPIRED
                    }
                    break
                }
                if (state == MeshPairing.DENIED || state == MeshPairing.EXPIRED || state == MeshPairing.CANCELLED) {
                    result = state
                    break
                }
                Thread.sleep(1_500)
            }
            og.state = result
            onDone(result)
            host.onChanged()
        }
    }

    fun pairAnswer(rid: String, accept: Boolean): MeshPairing.Request {
        val r = incoming.answer(rid, accept) ?: throw IllegalArgumentException("no such pairing request waiting (it may have expired)")
        if (accept) {
            trust.addPaired(TrustList.makeEntry(peerId = r.id, name = r.name, certPem = MeshIdentity.toPem(r.der!!),
                source = TrustList.SOURCE_PAIRED, os = r.os))
            host.log("mesh: paired with ${r.name} (${r.fp})")
        }
        host.onPairGone(rid)
        host.onChanged()
        return r
    }

    /** Unpairs a directly paired peer, telling it when it's reachable. Returns whether it was told. */
    fun unpair(fp: String): Boolean {
        val e = entry(fp)
        require(e.source == TrustList.SOURCE_PAIRED) {
            "${e.name} is trusted because your hub lists it. Remove it on the hub, and every device stops trusting it."
        }
        val told = direct(fp)?.send(JSONObject().put("t", "unpair")) ?: false
        if (told) Thread.sleep(200)   // let it leave before the link is closed
        trust.remove(fp)
        host.log("mesh: unpaired ${e.name}")
        return told
    }

    private fun onSeen(s: Seen) {
        if (trust.isTrusted(s.fp) && outbox.forPeer(s.fp).isNotEmpty()) kick()
        host.onChanged()
    }

    fun nearby(): List<Seen> = directory?.peers()?.filter { it.fp != identity.fp }.orEmpty()

    /** How a peer is reachable now, for screens: "lan", "tailnet", "hub", "seen" (on the LAN, no link yet), or "offline". */
    fun route(e: TrustList.Entry): String {
        openLink(e.fp)?.let { return it.kind }
        if (nearby().any { it.fp == e.fp }) return "seen"
        if (hubHasItLive(e)) return "hub"
        return "offline"
    }

    companion object {
        const val DEFAULT_PORT = 1739
        const val LAN_TIMEOUT_MS = 1_500L
        const val TAILNET_TIMEOUT_MS = 4_000L
        const val HELLO_TIMEOUT_MS = 15_000L
        const val ACK_TIMEOUT_MS = 10_000L
        const val STALL_MS = 60_000L
        const val IDLE_CLOSE_MS = 300_000L
        const val IDLE_PING_MS = 20_000L
        const val DEAD_AFTER_MS = 60_000L
        const val MAX_TEXT = 64 * 1024

        fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.opt(it) as? String }

        /** 100.64.0.0/10 or fd7a:115c:a1e0::/48. */
        fun isTailnet(address: String): Boolean {
            val ip = TrustList.ipLiteral(address) ?: return false
            val b = ip.address
            return if (ip is Inet4Address) (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            else b.size == 16 && (b[0].toInt() and 0xff) == 0xfd && (b[1].toInt() and 0xff) == 0x7a &&
                (b[2].toInt() and 0xff) == 0x11 && (b[3].toInt() and 0xff) == 0x5c && (b[4].toInt() and 0xff) == 0xa1 &&
                (b[5].toInt() and 0xff) == 0xe0
        }
    }
}

object MeshTlsErrors {
    fun mismatch(t: Throwable?): MeshTls.Mismatch? {
        var e = t
        var depth = 0
        while (e != null && depth++ < 10) {
            if (e is MeshTls.Mismatch) return e
            e.suppressed.firstNotNullOfOrNull { mismatch(it) }?.let { return it }
            e = e.cause
        }
        return null
    }
}
