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
import java.net.InetSocketAddress
import java.net.Socket
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
    /**
     * The default gateways of the Wi-Fi this phone is on (IPv4, not VPNs or
     * mobile data). On a laptop's or another phone's hotspot, that's the
     * device serving it.
     */
    fun gateways(): List<String> = emptyList()

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

    /** Pause everything (docs/mesh.md §9.9): kept by the host, so it survives a restart. */
    fun pausedEverything(): Boolean = false
    fun setPausedEverything(on: Boolean) {}
}

open class NoRoute(message: String) : Exception(message)

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
    private val accepted = ConcurrentHashMap<String, Long>()   // fp → when this phone accepted its pairing request (System.nanoTime)
    private val kick = java.util.concurrent.Semaphore(0)
    @Volatile var stopped = false; private set
    private var server: MeshServer? = null
    val listeningPort: Int get() = server?.port ?: 0
    val refused: Int get() = server?.refused?.get() ?: 0
    @Volatile private var announced: Map<String, String> = emptyMap()
    /** (gateway, fingerprint): that gateway turned out not to be that peer, on this network. */
    private val gatewayMisses = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    /** A gateway with nothing listening: when to look again, and the wait after that. */
    private val gatewayQuiet = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val gatewayProbing = AtomicBoolean(false)
    /** The network moved: look at the gateway on the next housekeeping round, not 30 s later. */
    private val networkMoved = AtomicBoolean(true)
    /** What each linked peer said about how it treats this phone (its `perm`): a hint only. */
    val remotePerm = ConcurrentHashMap<String, Perms.Remote>()
    /** fp → the last thing it refused, and why. */
    val refusals = ConcurrentHashMap<String, Refusal>()
    /** (fp, type) → when this phone last told it no. */
    private val refusedAt = ConcurrentHashMap<Pair<String, String>, Long>()

    /** Something a peer didn't take from this phone, and why ("paused" or "denied"). */
    data class Refusal(val re: String, val cap: String?, val why: String, val text: String, val ts: Long)

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

    /** Our hello (or welcome) to the peer [fp]: the caps it may use here, and how we treat it. */
    fun hello(t: String = "hello", fp: String? = null): JSONObject {
        val out = JSONObject().put("t", t).put("id", peerId).put("name", host.deviceName())
            .put("caps", JSONArray(host.caps())).put("os", "android").put("v", 1).put("port", listeningPort)
        if (fp != null) out.put("caps", JSONArray(capsFor(fp))).put("perm", permFor(fp))
        return out
    }

    // --- permissions (Perms, docs/mesh.md §9.9) ------------------------------------

    val pausedAll: Boolean get() = runCatching { host.pausedEverything() }.getOrDefault(false)

    /** Tests: false makes this node send whatever it's asked, as an older peer would, ignoring what peers said. */
    @Volatile internal var heedHints = true

    /** The caps a peer is told about: only what it may use here (mDNS and the roster still say everything). */
    fun capsFor(fp: String): List<String> {
        val e = trust.get(fp)
        if (e == null || e.paused || pausedAll) return emptyList()
        return host.caps().filter { c -> CAP_NEEDS[c]?.let { Perms.allowed(e, it) } ?: true }
    }

    fun permFor(fp: String): JSONObject = Perms.remoteView(trust.get(fp), pausedAll)

    /**
     * Throws [Refused] unless [msg] may go to the peer: by this phone's own
     * settings, then by what the peer said it would take (a hint, so nothing
     * goes that it would only refuse).
     */
    fun maySend(fp: String, msg: JSONObject, entry: TrustList.Entry? = null) {
        val e = entry ?: trust.get(fp)
        val all = pausedAll
        val name = e?.name ?: "that device"
        Perms.check(e, msg, all, "out")?.let { throw Refused(Perms.localText(name, it.why, it.cap, all), it.why, it.cap, true) }
        // only while a link is open: its hello brought the peer's latest word, and a peer that
        // changed its mind while away says so in the hello of the next link
        val remote = if (heedHints && openLink(fp) != null) remotePerm[fp] else null
        val cap = Perms.capability(msg)
        val why = Perms.remoteRefuses(remote, cap)
        if (why != null && msg.optString("t") !in Perms.ALWAYS) throw Refused(Perms.refusalText(name, why, cap), why, cap, false)
    }

    /** Whether [msg] may go to the peer now ([maySend] without the reason). */
    fun mayGo(fp: String, msg: JSONObject): Boolean = try {
        maySend(fp, msg)
        true
    } catch (e: Refused) {
        false
    }

    /** Chat and files: refused at once when a switch says no; a pause only makes them wait. */
    private fun mayQueue(fp: String, msg: JSONObject, entry: TrustList.Entry) {
        try {
            maySend(fp, msg, entry)
        } catch (e: Refused) {
            if (e.why != Perms.PAUSED) throw e
        }
    }

    /** The owner changed what a peer may do, or paused or resumed it. Returns the entry. */
    fun setPerms(fp: String, relation: String? = null, allow: Map<String, Boolean>? = null, paused: Boolean? = null): TrustList.Entry {
        val e = trust.setPerms(fp, relation, allow, paused)
        host.log("mesh: ${e.name}: ${if (e.paused) "paused" else "sharing"}" +
            if (relation != null || allow != null) "; " + e.allow.entries.joinToString(", ") { "${it.key} ${if (it.value) "on" else "off"}" } else "")
        tellPerms(listOf(fp))
        host.onChanged()
        return e
    }

    /** Pause everything, or resume it. */
    fun pauseEverything(on: Boolean) {
        host.setPausedEverything(on)
        host.log(if (on) "mesh: everything paused" else "mesh: everything resumed")
        tellPerms(links.keys.toList())
        host.onChanged()
    }

    /** Tell linked peers how they're treated now (perm), and send what waited for a resume. */
    private fun tellPerms(fps: List<String>) {
        for (fp in fps) {
            val link = openLink(fp) ?: continue
            val p = permFor(fp)
            link.send(JSONObject().put("t", "perm").put("paused", p.getBoolean("paused")).put("allow", p.getJSONObject("allow"))
                .put("caps", JSONArray(capsFor(fp))))
        }
        kick()
    }

    /**
     * Say no to the sender, so it can show why. Acknowledged messages get an
     * answer for that id: a nack for good when the capability is off, `refused`
     * when paused (it waits, and goes on resume). The rest get one `refused`
     * every few seconds at most.
     */
    private fun refuse(link: Link, entry: TrustList.Entry, msg: JSONObject, no: Perms.No) {
        val t = msg.optString("t")
        val text = Perms.refusalText(host.deviceName(), no.why, no.cap)
        host.log("mesh: refused $t from ${entry.name}: ${if (no.why == Perms.PAUSED) "paused" else "${no.cap} is off"}")
        val mid = (msg.opt("id") as? String)?.takeIf { it.length <= 64 }
        if (mid != null && t in setOf("text", "offer", "clip", "file")) {
            link.send(if (no.why == Perms.DENIED) JSONObject().put("t", "nack").put("id", mid).put("error", text)
                .put("cap", no.cap).put("why", no.why)
            else JSONObject().put("t", "refused").put("re", t).put("id", mid).put("cap", no.cap).put("why", no.why)
                .put("error", text))
            return
        }
        val key = link.fp to t
        val now = System.nanoTime() / 1_000_000
        synchronized(refusedAt) {
            val last = refusedAt[key]
            if (last != null && now - last < REFUSE_EVERY_MS) return
            refusedAt[key] = now
        }
        link.send(JSONObject().put("t", "refused").put("re", t).put("cap", no.cap).put("why", no.why).put("error", text))
    }

    private fun gotRemotePerm(fp: String, v: Any?) {
        val got = Perms.parseRemote(v)
        if (got == null) {
            remotePerm.remove(fp)      // an older peer: it takes everything, as before
            return
        }
        remotePerm[fp] = got
        if (!got.paused) refusals[fp]?.takeIf { it.why == Perms.PAUSED }?.let { refusals.remove(fp, it) }
    }

    private fun gotRefused(link: Link, entry: TrustList.Entry, msg: JSONObject) {
        val why = (msg.opt("why") as? String)?.takeIf { it == Perms.PAUSED || it == Perms.DENIED } ?: Perms.DENIED
        val cap = (msg.opt("cap") as? String)?.takeIf { it in Perms.CAPABILITIES }
        val text = ((msg.opt("error") as? String)?.ifEmpty { null } ?: Perms.refusalText(entry.name, why, cap)).take(200)
        refusals[link.fp] = Refusal(msg.optString("re").take(20), cap, why, text, System.currentTimeMillis())
        host.log("mesh: ${entry.name} refused: $text")
        (msg.opt("id") as? String)?.let { oid ->
            acks[oid]?.takeIf { it.fp == link.fp }?.let { w ->
                w.ok = false
                w.error = "$why: $text"
                w.done.countDown()
            }
            offers.resolve(link.fp, oid, false, "$why: $text")
        }
        host.onChanged()
    }

    /**
     * A message that came through the hub: why it's refused ("paused" or
     * "denied"), or null. The sender is the hub's device `from.id`; one this
     * phone trusts gets its own switches, any other device of the hub's is your
     * own (the same user's hub), except that Pause everything stops it all.
     */
    fun checkHubMessage(msg: JSONObject): String? {
        val sender = msg.optJSONObject("from")?.opt("id") as? String
        val entry = sender?.let { id -> trust.all().firstOrNull { it.id == id } }
        if (entry == null) return if (pausedAll && msg.optString("t") !in Perms.ALWAYS) Perms.PAUSED else null
        return Perms.check(entry, msg, pausedAll, "in")?.why
    }

    /**
     * Whether a broadcast (clip, state) may go to the hub, which hands it to
     * every device it has. Not while everything is paused, and a clipboard not
     * while any device the hub lists is paused or has the clipboard off here.
     */
    fun hubMayShare(msg: JSONObject): Boolean {
        if (pausedAll) return false
        val cap = Perms.capability(msg)
        if (cap != Perms.CLIPBOARD) return true
        val hubId = host.hubId() ?: return true
        return trust.all().none { it.hub == hubId && (it.paused || !Perms.allowed(it, cap)) }
    }

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
                if (link.outbound && !link.keep && now - link.lastUsed > IDLE_CLOSE_MS) link.close(1000, "idle")
            }
            val round = ++n % 6 == 0
            if (round) runCatching { refreshAnnouncement() }
            if ((networkMoved.getAndSet(false) || round) && gatewayProbing.compareAndSet(false, true)) {
                // its own thread: a dial can take a while, and the links above still need watching
                thread(name = "mesh-gateway", isDaemon = true) {
                    try {
                        probeGateways()
                    } catch (e: Exception) {
                        host.log("mesh: looking for a paired device at the gateway: $e")
                    } finally {
                        gatewayProbing.set(false)
                    }
                }
            }
        }
    }

    /**
     * The Wi-Fi changed (joined, left, or its addresses or routes moved).
     * [newNetwork]: a different network, so what was learnt about its
     * gateway no longer holds (hotspots often reuse the same address).
     */
    fun networkChanged(newNetwork: Boolean) {
        if (newNetwork) gatewayMisses.clear()
        gatewayQuiet.clear()
        networkMoved.set(true)
    }

    private fun currentGateways(): List<String> = runCatching { host.gateways() }.getOrDefault(emptyList())

    /**
     * Links with a paired device that is this Wi-Fi's gateway: a laptop or
     * a phone serving the hotspot this phone joined. Android doesn't announce
     * on a hotspot it serves, and laptops' hotspots may not pass mDNS on, so
     * neither side would find the other; the device serving it is always the
     * gateway, though. Only the device whose certificate is pinned for that
     * peer gets a link, and it stays open (it's how the other side knows this
     * phone is there). A gateway that turned out not to be a peer isn't tried
     * for it again until the network changes; one with nothing listening is
     * looked at less and less often (up to every 5 minutes), for the battery.
     */
    fun probeGateways(): Link? {
        val gateways = currentGateways()
        gatewayMisses.removeAll { it.first !in gateways }
        gatewayQuiet.keys.retainAll(gateways.toSet())
        val now = System.currentTimeMillis()
        for (gw in gateways) {
            if (stopped) return null
            if ((gatewayQuiet[gw]?.first ?: 0L) > now) continue
            if (links.values.any { l -> synchronized(l) { l.any { it.address == gw && !it.closed } } }) continue
            val closedPorts = HashSet<Int>()
            var listening = false
            for (entry in trust.all()) {
                if (stopped) return null
                val port = entry.port ?: DEFAULT_PORT
                if (openLink(entry.fp) != null || (gw to entry.fp) in gatewayMisses || port in closedPorts) continue
                if (!answers(gw, port)) {
                    closedPorts += port
                    continue
                }
                listening = true
                var linked = false
                val link = synchronized(dialLocks.getOrPut(entry.fp) { Any() }) {
                    if (openLink(entry.fp) != null) { linked = true; null } else dial(entry, gw, port, "lan")
                }
                if (linked) continue
                if (link == null) {
                    gatewayMisses += gw to entry.fp
                    continue
                }
                link.keep = true
                gatewayQuiet.remove(gw)
                host.log("mesh: link open with ${entry.name} at the gateway ($gw): its hotspot")
                return link
            }
            if (listening || closedPorts.isEmpty()) gatewayQuiet.remove(gw)
            else {
                val wait = ((gatewayQuiet[gw]?.second ?: (GATEWAY_EVERY_MS / 2)) * 2).coerceAtMost(GATEWAY_QUIET_MAX_MS)
                gatewayQuiet[gw] = (now + wait) to wait
            }
        }
        return null
    }

    /** Tests: whether [gateway] turned out not to be the peer [fp] on this network. */
    internal fun missedAtGateway(gateway: String, fp: String): Boolean = (gateway to fp) in gatewayMisses

    private fun answers(address: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), LAN_TIMEOUT_MS.toInt()) }
        true
    } catch (e: java.io.IOException) {
        false
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
        /** Not closed when idle: a link with the device serving this hotspot. */
        @Volatile var keep = false
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
                // at once: a write to a peer that's gone may be stuck, and a close frame would queue behind it
                conn.shutdown()
                finished()
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
        link.send(hello(fp = entry.fp))
        if (!link.ready.await(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS) || link.closed) {
            link.close(1008, "no welcome")
            return null
        }
        trust.learn(entry.fp, address = address, port = port, tailnet = kind == "tailnet")
        accepted.remove(entry.fp)
        return link
    }

    private fun candidates(entry: TrustList.Entry): List<Triple<String, Int, String>> {
        val out = ArrayList<Triple<String, Int, String>>()
        val port = entry.port ?: DEFAULT_PORT
        directory?.peers()?.filter { it.fp == entry.fp }?.forEach { s ->
            s.addresses.forEach { a -> out += Triple(a, s.port, if (isTailnet(a)) "tailnet" else "lan") }
        }
        entry.lan.forEach { a -> out += Triple(a, port, if (isTailnet(a)) "tailnet" else "lan") }
        // a device serving the hotspot may not announce itself on it, but it's the hotspot's gateway
        currentGateways().filter { (it to entry.fp) !in gatewayMisses }.forEach { out += Triple(it, port, "lan") }
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
                    if (kind == "lan" && address in currentGateways()) link.keep = true
                    host.log("mesh: link open with ${entry.name} over $kind ($address:$port)")
                    return link
                }
            }
        }
        return null
    }

    /** Sends to every peer with an open link (state, for instance) that may have it. */
    fun broadcast(msg: JSONObject): Boolean {
        var sent = false
        for (fp in links.keys) {
            val link = openLink(fp) ?: continue
            if (mayGo(fp, msg)) sent = link.send(msg) || sent
        }
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
                gotRemotePerm(link.fp, msg.opt("perm"))
                if (t == "hello") {
                    link.send(hello("welcome", link.fp))
                    host.log("mesh: ${entry.name} connected from ${link.address}")
                }
                link.ready.countDown()
                val now = trust.get(link.fp) ?: entry
                for ((kind, data) in host.lastStates()) {
                    val st = JSONObject().put("t", "state").put("kind", kind).put("data", data ?: JSONObject.NULL)
                    if (Perms.check(now, st, pausedAll, "out") == null) link.send(st)
                }
                kick()
                host.onChanged()
            }
            return
        }
        val current = trust.get(link.fp) ?: return
        if (t == "perm") {
            gotRemotePerm(link.fp, msg)
            msg.optJSONArray("caps")?.let { trust.learn(link.fp, caps = strings(it)) }
            kick()     // a resume: what waited for it goes now
            host.onChanged()
            return
        }
        if (t == "refused") {
            gotRefused(link, current, msg)
            return
        }
        Perms.check(current, msg, pausedAll, "in")?.let {
            refuse(link, current, msg, it)
            return
        }
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
        maySend(fp, msg, e)
        direct(fp)?.let { if (it.send(msg)) return it.kind }
        if (hubHasItLive(e) && host.hubSend(JSONObject(msg.toString()).put("to", e.id))) return "hub"
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    fun ring(fp: String, stop: Boolean = false): String {
        val e = entry(fp)
        val msg = JSONObject().put("t", if (stop) "ring-stop" else "ring")
        maySend(fp, msg, e)
        direct(fp)?.let { if (it.send(msg)) return it.kind }
        if (hubKnows(e)) {
            host.hubRing(e.id, stop)
            return "hub"
        }
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    fun clip(fp: String, text: String): String {
        val e = entry(fp)
        require(text.isNotEmpty() && text.toByteArray().size <= 256 * 1024) { "clipboard text must be 1 byte to 256 KB" }
        val msg = JSONObject().put("t", "clip").put("text", text)
        maySend(fp, msg, e)
        direct(fp)?.let { if (it.send(msg)) return it.kind }
        // the hub has no addressed clipboard message: it goes to all your devices' clipboards,
        // so not while any of them shouldn't have it
        if (hubHasItLive(e) && hubMayShare(msg) && host.hubSend(msg)) return "hub"
        throw NoRoute("${e.name} isn't reachable directly, and not through the hub either")
    }

    // --- sending: chat and files (routes 1–5) --------------------------------------

    fun sendText(fp: String, body: String): JSONObject {
        val e = entry(fp)
        require(body.isNotBlank() && body.toByteArray().size <= MAX_TEXT) { "a message must be 1 byte to 64 KB of text" }
        mayQueue(fp, JSONObject().put("t", "text"), e)
        val job = outbox.addText(fp, e.name, body)
        kick()
        return job
    }

    /** A file from a content URI or a path; [size] as the source reports it. */
    fun sendFile(fp: String, source: String, name: String, mime: String, size: Long, spooled: Boolean = false): JSONObject {
        val e = entry(fp)
        mayQueue(fp, JSONObject().put("t", "offer"), e)
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
    private fun keepWaiting(job: JSONObject, error: String, retry: Boolean, attempts: Int? = null) {
        var fields = arrayOf<Pair<String, Any?>>("state" to Outbox.QUEUED, "error" to error, "retry" to retry)
        // in the same update: someone waiting for the job sees the reason with the attempt
        if (attempts != null) fields += arrayOf<Pair<String, Any?>>("attempts" to attempts)
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
        try {
            maySend(e.fp, JSONObject().put("t", if (job.getString("kind") == "text") "text" else "offer"), e)
        } catch (r: Refused) {
            if (r.why != Perms.PAUSED) {
                finish(job, Outbox.FAILED, error = r.message)
                return "done"
            }
            // paused, here or there: it waits, and goes on resume (a perm, or Resume here)
            keepWaiting(job, "waiting: ${r.message}", retry = false, attempts = job.optInt("attempts") + 1)
            return "wait"
        }
        outbox.update(id, "state" to Outbox.SENDING, "attempts" to job.optInt("attempts") + 1)
        val link = direct(e.fp)
        if (link == null && justAccepted(e.fp)) {
            // it may not have heard our yes yet: look again soon rather than in retryEveryMs
            thread(isDaemon = true) { Thread.sleep(PAIR_RETRY_MS); kick() }
        }
        if (link != null) {
            val got = if (job.getString("kind") == "text") directText(link, job) else directFile(link, job)
            if (got == "ok") {
                finish(job, Outbox.DONE, route = link.kind)
                return "done"
            }
            if (got.startsWith("paused:")) {
                // the peer paused sharing with us: wait for its perm saying it resumed
                keepWaiting(job, "waiting: " + got.removePrefix("paused:").trim(), retry = false)
                remotePerm.compute(e.fp) { _, old -> (old ?: Perms.Remote(true, emptyMap())).copy(paused = true) }
                return "wait"
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
            if (!w.ok && w.error.startsWith("paused:")) return w.error
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
            if (!ok && error.startsWith("paused:")) return error
            return if (ok) "ok" else "refused: $error"
        } finally {
            offers.remove(job.getString("id"))
        }
    }

    override fun serveFile(out: OutputStream, oid: String, fp: String, req: MeshServer.Request,
                           sendHead: (Int, Map<String, String>) -> Unit) {
        try {
            maySend(fp, JSONObject().put("t", "offer"))
        } catch (e: Refused) {
            if (e.local) {
                sendHead(403, mapOf("Content-Length" to "0"))   // paused (or switched off) since it was offered
                return
            }
        }
        offers.serve(out, oid, fp, req.header("range"), req.method, sendHead)
    }

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

    /**
     * The owner compared the codes: [yes] they match. Waits for the other side
     * in the background. [relation]: whether the other device is the owner's
     * ("own") or someone else's ("other"), which sets what it may do (Perms).
     */
    fun pairConfirm(rid: String, yes: Boolean, relation: String = Perms.OWN_DEVICE, onDone: (String) -> Unit = {}) {
        val og = outgoing[rid] ?: throw IllegalArgumentException("no such pairing request")
        if (!yes) {
            thread(isDaemon = true) { og.cancel() }
            og.state = MeshPairing.CANCELLED
            onDone(og.state)
            return
        }
        require(relation in Perms.RELATIONS) { "relation must be \"own\" or \"other\"" }
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
                            tailnetIp = og.host.takeIf { isTailnet(it) }, relation = relation)
                        trust.addPaired(entry)
                        host.log("mesh: paired with ${entry.name} (${entry.fp}), ${describeRelation(relation)}")
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

    /** Whether this phone accepted the peer's pairing request less than [PAIR_GRACE_MS] ago. */
    internal fun justAccepted(fp: String): Boolean {
        val at = accepted[fp] ?: return false
        if ((System.nanoTime() - at) / 1_000_000 <= PAIR_GRACE_MS) return true
        accepted.remove(fp, at)
        return false
    }

    /**
     * The owner's answer to a device asking to pair; and, accepting, whether
     * it's theirs ("own") or someone else's ("other").
     */
    fun pairAnswer(rid: String, accept: Boolean, relation: String = Perms.OWN_DEVICE): MeshPairing.Request {
        if (accept) require(relation in Perms.RELATIONS) { "relation must be \"own\" or \"other\"" }
        val r = incoming.answer(rid, accept) ?: throw IllegalArgumentException("no such pairing request waiting (it may have expired)")
        if (accept) {
            trust.addPaired(TrustList.makeEntry(peerId = r.id, name = r.name, certPem = MeshIdentity.toPem(r.der!!),
                source = TrustList.SOURCE_PAIRED, os = r.os, relation = relation))
            accepted[r.fp] = System.nanoTime()
            host.log("mesh: paired with ${r.name} (${r.fp}), ${describeRelation(relation)}")
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
        const val GATEWAY_EVERY_MS = 30_000L
        const val GATEWAY_QUIET_MAX_MS = 300_000L
        const val IDLE_PING_MS = 20_000L
        const val DEAD_AFTER_MS = 60_000L
        const val MAX_TEXT = 64 * 1024
        /**
         * Accepting a pairing request trusts the other device at once, but it trusts this one
         * only when its own owner has confirmed the code and it has polled for the answer:
         * seconds, or minutes, later. Until then its TLS refuses this device's certificate. So
         * for this long after accepting, a peer that can't be reached directly is tried again
         * every [PAIR_RETRY_MS] instead of every retryEveryMs.
         */
        const val PAIR_GRACE_MS = 120_000L
        const val PAIR_RETRY_MS = 2_000L
        /** A stream of refused messages (input, say) gets one `refused` this often, per type. */
        const val REFUSE_EVERY_MS = 5_000L

        /**
         * This phone's caps, and the capability a peer needs to be told of each
         * (docs/mesh.md §9.9): media is remote control of the phone; SMS and
         * browsing its files are "access".
         */
        val CAP_NEEDS = mapOf("input" to Perms.CONTROL, "media" to Perms.CONTROL, "lock" to Perms.CONTROL,
            "screenshot" to Perms.CONTROL, "clipboard" to Perms.CLIPBOARD, "notify" to Perms.NOTIFY,
            "sms" to Perms.ACCESS, "files" to Perms.ACCESS)

        fun describeRelation(relation: String) = if (relation == Perms.OTHER_DEVICE) "someone else's" else "your own device"

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
