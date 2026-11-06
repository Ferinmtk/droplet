package dev.droplet.app.mesh

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The iPhone link (docs/iphone.md): the droplet web app on an iPhone talks to
 * this phone over a WebRTC data channel, with no server in between. The
 * transport (ICE-lite, DTLS with the mesh certificate, SCTP) is the Go
 * library in android/iphonelink; this is what goes over the channel (§3),
 * as the reference's webrtc/protocol.py, and [IphoneBridge] makes a
 * connected iPhone a link like any other in the [MeshNode], as its bridge.py.
 *
 * Every text frame is one JSON object; binary frames carry file data. This
 * phone speaks first, as soon as the channel opens:
 *
 *     S → {"t":"server-hello","v":1,"id","name","os":"android","fp":<mesh fp>,"nonce":nS}
 *
 * A paired browser proves who it is (its key never leaves it):
 *
 *     C → {"t":"auth","v":1,"key":<base64 SPKI>,"name","sig":<base64>}
 *         sig = ECDSA P-256/SHA-256 over "droplet-webrtc-auth-v1" LF fpK LF fpS LF fpD LF nS
 *     S → {"t":"welcome",…}   or   {"t":"auth-failed","error","paired":bool}
 *
 * fpD is the browser's own DTLS certificate's fingerprint as this side saw it,
 * so the proof is bound to this very DTLS session. An unpaired browser pairs
 * as in docs/mesh.md §9.3, holding the one-time token from a QR code this
 * phone is showing. Then text, links, rings, the clipboard and files go as
 * docs/iphone.md §3.3–3.5 says.
 */
object Iphone {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 1739            // UDP: the mesh's TCP port number
    const val APP_URL = "https://droplet.noxeratech.com/app/"
    const val TOKEN_TTL_MS = 600_000L        // a QR code lets a browser ask to pair for this long
    val FEATURES = listOf("cancel", "link", "rename")

    const val CHUNK = 16 * 1024              // data per binary frame (plus the 8-byte id)
    const val HIGH_WATER = 1024 * 1024L      // stop sending while this much is buffered
    const val MAX_FRAME = 256 * 1024         // bytes of UTF-8 in one frame
    const val MAX_FILE = 64L * 1024 * 1024 * 1024
    const val AUTH_TIMEOUT_MS = 30_000L      // an unauthenticated channel that isn't pairing closes after this
    const val ACK_TIMEOUT_MS = 60_000L
    val FILE_ID = Regex("^[0-9a-f]{32}$")

    fun encode(msg: JSONObject): String = msg.toString()

    fun fits(msg: JSONObject): Boolean = encode(msg).toByteArray(Charsets.UTF_8).size <= MAX_FRAME

    fun authTranscript(fpKey: String, fpServer: String, fpDtls: String, nonce: String): ByteArray =
        listOf("droplet-webrtc-auth-v1", fpKey, fpServer, fpDtls, nonce).joinToString("\n").toByteArray(Charsets.US_ASCII)

    fun b64url(raw: ByteArray): String = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** The QR code's payload (docs/iphone.md §2). */
    fun payload(name: String, peerId: String, fp: String, addresses: List<String>, port: Int, token: String): JSONObject =
        JSONObject().put("v", 1).put("n", name.take(40)).put("i", peerId).put("f", b64url(MeshPairing.unhex(fp)))
            .put("a", JSONArray(addresses.take(4))).put("p", port).put("t", token)

    /** The link the QR code holds: the web app, with everything in the fragment (never sent to the website). */
    fun link(appUrl: String, payload: JSONObject): String =
        "${appUrl.trimEnd('#')}#pair=${b64url(payload.toString().toByteArray(Charsets.UTF_8))}"
}

/** One browser's channel: the Go transport's connection in the app, a fake in tests. */
interface IphoneChannel {
    /** The browser's IP address. */
    val address: String
    /** SHA-256 of the browser's DTLS certificate, lowercase hex. */
    val dtlsFp: String
    fun sendText(text: String): Boolean
    fun sendBinary(data: ByteArray): Boolean
    /** Bytes waiting to go. */
    fun buffered(): Long
    fun close()
}

/** What a connection needs from the rest of the app ([IphoneBridge] implements it). */
interface IphoneHost {
    /** id, name and fp (the mesh fingerprint). */
    fun serverInfo(): Triple<String, String, String>
    /** A trusted browser's entry. */
    fun browser(fp: String): TrustList.Entry?
    fun tokenOk(token: Any?): Boolean
    fun pairOpen(body: JSONObject): Pair<Int, JSONObject>
    fun pairConfirm(rid: String, body: JSONObject): Pair<Int, JSONObject>
    fun pairStatus(rid: String): String
    fun pairCancel(rid: String)
    fun authenticated(conn: IphoneConn)
    fun deliver(conn: IphoneConn, msg: JSONObject)
    /** A file arrived whole, in [part]: keep it (it's moved or copied away). */
    fun received(conn: IphoneConn, fid: String, part: File, name: String, mime: String)
    fun alreadyReceived(conn: IphoneConn, fid: String): Boolean
    /** Where files arriving are written while they come. */
    fun incomingDir(): File
    fun closed(conn: IphoneConn)
    /** How this phone treats the browser, for its welcome. */
    fun perm(fp: String): JSONObject?
    /** The answer refusing a file it offers, or null to take it. */
    fun refuseFile(conn: IphoneConn, msg: JSONObject): JSONObject?
    /** A file on its way: start ("in"), progress, or its end (state done/failed/cancelled). */
    fun transfer(conn: IphoneConn, fid: String, dir: String? = null, name: String? = null, size: Long? = null,
                 done: Long? = null, state: String? = null, error: String? = null)
    /** The name the browser says it has now: null, or the name it's known by here. */
    fun renamed(conn: IphoneConn, fp: String, name: Any?): String?
    fun schedule(delayMs: Long, run: () -> Unit)
    fun log(msg: String)
}

/** One browser's channel, as docs/iphone.md §3. Frames arrive in order on one thread. */
class IphoneConn(private val host: IphoneHost, val channel: IphoneChannel) {
    private class Incoming(val id: String, val name: String, val mime: String, val size: Long, val part: File,
                           val out: FileOutputStream) {
        var got = 0L
    }

    private class Wait {
        val done = CountDownLatch(1)
        @Volatile var ok = false
        @Volatile var why = ""
        fun set(ok: Boolean, why: String) {
            if (done.count == 0L) return
            this.ok = ok
            this.why = why
            done.countDown()
        }
        val isDone get() = done.count == 0L
    }

    val nonce: String = MeshPairing.hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
    /** The browser's key fingerprint, once it has proved who it is. */
    @Volatile var fp: String? = null
        private set
    @Volatile var name = ""
        private set
    @Volatile var closed = false
        private set
    /** The open pairing request. */
    @Volatile var pairing: String? = null
        private set
    /** What the app understands besides v1 (its auth's "features"). */
    @Volatile var features: List<String> = emptyList()
        private set
    /** Set by the bridge: the node's link for it, once it's authenticated. */
    @Volatile var link: Any? = null
    val address get() = channel.address
    private val incoming = ConcurrentHashMap<String, Incoming>()     // the first 16 hex of the id
    private val waiting = ConcurrentHashMap<String, Wait>()
    private val sendLock = Any()

    // --- sending ------------------------------------------------------------------

    fun send(msg: JSONObject): Boolean {
        if (closed) return false
        val frame = Iphone.encode(msg)
        if (frame.toByteArray(Charsets.UTF_8).size > Iphone.MAX_FRAME) {
            host.log("iphone: not sending a ${msg.optString("t")} too large for the channel")
            return false
        }
        return runCatching { channel.sendText(frame) }.getOrDefault(false)
    }

    fun close() {
        if (closed) return
        closed = true
        if (pairing != null && fp == null) host.pairCancel(pairing!!)
        for (f in incoming.values) {
            runCatching { f.out.close() }
            f.part.delete()
        }
        incoming.clear()
        waiting.values.forEach { it.set(false, "the connection closed") }
        runCatching { channel.close() }
        host.closed(this)
    }

    // --- arriving -------------------------------------------------------------------

    fun opened() {
        val (id, myName, myFp) = host.serverInfo()
        send(JSONObject().put("t", "server-hello").put("v", Iphone.PROTOCOL_VERSION).put("id", id).put("name", myName)
            .put("os", "android").put("fp", myFp).put("nonce", nonce))
        host.schedule(Iphone.AUTH_TIMEOUT_MS) {
            if (fp == null && pairing == null && !closed) {
                host.log("iphone: $address didn't say who it is; closing")
                close()
            }
        }
    }

    fun binary(data: ByteArray) {
        if (closed || fp == null || data.size < 8) return
        chunk(data)
    }

    fun text(data: String) {
        if (closed || data.length > Iphone.MAX_FRAME) return     // characters ≤ bytes
        val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (val t = msg.optString("t")) {
            "ping" -> send(JSONObject().put("t", "pong"))
            "auth" -> auth(msg)
            "pair" -> pair(msg)
            "pair-confirm" -> pairConfirm(msg)
            "pair-cancel" -> pairing?.let { host.pairCancel(it) }
            else -> {
                if (fp == null) return     // nothing else before it has said who it is
                when (t) {
                    "file" -> file(msg)
                    "file-end" -> fileEnd(msg)
                    "ack", "nack" -> {
                        (msg.opt("id") as? String)?.let { waiting[it] }?.set(t == "ack", msg.optString("error").take(200))
                        host.deliver(this, msg)
                    }
                    "refused" -> {
                        // the app paused sharing with this phone: a file it was sent waits, and goes on resume
                        (msg.opt("id") as? String)?.let { waiting[it] }?.let { w ->
                            val why = if (msg.optString("why") == Perms.PAUSED) "paused" else "denied"
                            w.set(false, "$why: ${msg.optString("error").take(200)}")
                        }
                        host.deliver(this, msg)
                    }
                    "cancel" -> {
                        cancelled(msg)
                        host.deliver(this, msg)
                    }
                    "text", "unpair", "ring", "ring-stop", "perm", "link", "rename" -> host.deliver(this, msg)
                    "clip" -> if (msg.opt("text") is String) host.deliver(this, msg)
                        else (msg.opt("id") as? String)?.let { send(JSONObject().put("t", "nack").put("id", it.take(64)).put("error", "no text")) }
                }
            }
        }
    }

    // --- who it is -------------------------------------------------------------------

    private fun auth(msg: JSONObject) {
        if (fp != null) return
        val spki: ByteArray
        val sig: ByteArray
        try {
            spki = TrustList.p256Spki(msg.opt("key"))
            val s = msg.opt("sig") as? String ?: throw IllegalArgumentException()
            require(Regex("^[A-Za-z0-9+/]*={0,2}$").matches(s) && s.length % 4 == 0)
            sig = Base64.decode(s, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            send(JSONObject().put("t", "auth-failed").put("error", "malformed").put("paired", false))
            return
        }
        val keyFp = MeshIdentity.sha256Hex(spki)
        val entry = host.browser(keyFp)
        if (entry == null) {
            send(JSONObject().put("t", "auth-failed").put("error", "this device isn't paired with it").put("paired", false))
            return
        }
        val (id, myName, myFp) = host.serverInfo()
        if (!MeshPairing.verifyKey(spki, sig, Iphone.authTranscript(keyFp, myFp, channel.dtlsFp, nonce))) {
            host.log("iphone: ${entry.name} at $address failed the challenge")
            send(JSONObject().put("t", "auth-failed").put("error", "the proof didn't check out").put("paired", true))
            close()
            return
        }
        name = host.renamed(this, keyFp, msg.opt("name")) ?: entry.name
        features = (msg.optJSONArray("features")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } } ?: emptyList()).take(20)
        fp = keyFp
        pairing = null
        val welcome = JSONObject().put("t", "welcome").put("v", Iphone.PROTOCOL_VERSION).put("id", id).put("name", myName)
            .put("os", "android").put("caps", JSONArray()).put("features", JSONArray(Iphone.FEATURES))
        host.perm(keyFp)?.let { welcome.put("perm", it) }
        send(welcome)
        host.log("iphone: ${entry.name} connected from $address")
        host.authenticated(this)
    }

    private fun pair(msg: JSONObject) {
        if (fp != null || pairing != null) return
        if (!host.tokenOk(msg.opt("token"))) {
            send(JSONObject().put("t", "pair-failed").put("error", "this pairing code has expired: show a new QR code and scan it"))
            return
        }
        val (status, out) = host.pairOpen(msg)
        if (status != 200) {
            send(JSONObject().put("t", "pair-failed").put("error", out.optString("error").ifEmpty { "refused ($status)" }))
            return
        }
        pairing = out.getString("request")
        send(JSONObject().put("t", "pair-nonce").put("request", out.getString("request")).put("nonce", out.getString("nonce")))
    }

    private fun pairConfirm(msg: JSONObject) {
        val rid = pairing ?: return
        if (fp != null) return
        val (status, out) = host.pairConfirm(rid, msg)
        if (status != 200) {
            pairing = null
            send(JSONObject().put("t", "pair-failed").put("error", out.optString("error").ifEmpty { "refused ($status)" }))
            return
        }
        send(JSONObject().put("t", "pair-state").put("state", MeshPairing.WAITING))
        watchPairing(rid, MeshPairing.WAITING)
    }

    private fun watchPairing(rid: String, last: String) {
        host.schedule(500) {
            if (closed || pairing != rid) return@schedule
            val state = host.pairStatus(rid)
            if (state != last) send(JSONObject().put("t", "pair-state").put("state", state))
            if (state == MeshPairing.WAITING) watchPairing(rid, state)
        }
    }

    // --- files arriving -----------------------------------------------------------------

    private fun file(msg: JSONObject) {
        val fid = (msg.opt("id") as? String)?.takeIf { Iphone.FILE_ID.matches(it) } ?: return
        val size = (msg.opt("size") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
        if (size == null || size < 0 || size > Iphone.MAX_FILE) {
            send(JSONObject().put("t", "nack").put("id", fid).put("error", "bad size"))
            return
        }
        host.refuseFile(this, msg)?.let {
            send(it)        // files are off for it, or it's paused: its data frames are dropped
            return
        }
        if (host.alreadyReceived(this, fid)) {
            send(JSONObject().put("t", "ack").put("id", fid))
            return
        }
        val key = fid.take(16)
        if (incoming.containsKey(key)) return
        val name = MeshFiles.safeName(msg.opt("name") as? String)
        val mime = (msg.opt("mime") as? String)?.takeIf { it.length <= 100 && '/' in it } ?: "application/octet-stream"
        val dir = host.incomingDir()
        val part: File
        val out: FileOutputStream
        try {
            dir.mkdirs()
            if (size > dir.usableSpace) {
                send(JSONObject().put("t", "nack").put("id", fid).put("error", "not enough space on the phone"))
                return
            }
            part = File(dir, ".iphone-${fp!!.take(16)}-$fid.part")
            out = FileOutputStream(part)
        } catch (e: Exception) {
            send(JSONObject().put("t", "nack").put("id", fid).put("error", "can't save it: ${e.message}"))
            return
        }
        incoming[key] = Incoming(fid, name, mime, size, part, out)
        host.transfer(this, fid, dir = "in", name = name, size = size, done = 0, state = Transfers.ACTIVE)
    }

    private fun chunk(data: ByteArray) {
        val key = MeshPairing.hex(data.copyOf(8))
        val f = incoming[key] ?: return
        val body = data.size - 8
        f.got += body
        if (f.got > f.size) {
            drop(f, "more data than it said")
            return
        }
        try {
            f.out.write(data, 8, body)
        } catch (e: Exception) {
            drop(f, "can't save it: ${e.message}")
            return
        }
        host.transfer(this, f.id, done = f.got)
    }

    /** Stop taking a file and delete what came of it; [why] (if any) goes back as a nack. */
    private fun drop(f: Incoming, why: String?, state: String = Transfers.FAILED) {
        incoming.remove(f.id.take(16))
        runCatching { f.out.close() }
        f.part.delete()
        host.transfer(this, f.id, state = state, error = why)
        if (why != null) send(JSONObject().put("t", "nack").put("id", f.id).put("error", why))
    }

    /** The app cancelled a file: one it was sending (drop what came) or one it was being sent. */
    private fun cancelled(msg: JSONObject) {
        val fid = (msg.opt("id") as? String)?.takeIf { Iphone.FILE_ID.matches(it) } ?: return
        incoming[fid.take(16)]?.takeIf { it.id == fid }?.let { drop(it, null, Transfers.CANCELLED) }
        waiting[fid]?.set(false, "cancelled: ${name.ifEmpty { "the iPhone" }} cancelled it")
    }

    /** Cancelled here: stop taking the file, delete it, and tell the app. */
    fun cancelReceive(fid: String): Boolean {
        val f = incoming[fid.take(16)]?.takeIf { it.id == fid } ?: return false
        drop(f, null, Transfers.CANCELLED)
        send(JSONObject().put("t", "cancel").put("id", fid))
        return true
    }

    fun receiving(fid: String): Boolean = incoming[fid.take(16)]?.id == fid

    private fun fileEnd(msg: JSONObject) {
        val fid = (msg.opt("id") as? String)?.takeIf { Iphone.FILE_ID.matches(it) } ?: return
        val f = incoming[fid.take(16)]?.takeIf { it.id == fid } ?: return
        if (f.got != f.size) {
            drop(f, "got ${f.got} of ${f.size} bytes")
            return
        }
        incoming.remove(fid.take(16))
        try {
            f.out.close()
            host.received(this, fid, f.part, f.name, f.mime)
        } catch (e: Exception) {
            f.part.delete()
            host.transfer(this, fid, state = Transfers.FAILED, error = e.message)
            send(JSONObject().put("t", "nack").put("id", fid).put("error", "can't save it: ${e.message}"))
            return
        }
        f.part.delete()
        host.transfer(this, fid, done = f.size, state = Transfers.DONE)
        send(JSONObject().put("t", "ack").put("id", fid))
    }

    // --- files leaving -------------------------------------------------------------------

    /**
     * Sends a file; "ok" once the browser acknowledged it, else why not: "refused: …" when it
     * refused it for good, "paused: …" or "cancelled…" as the node's delivery expects, else
     * why it stopped (it's tried again). [stop] is checked between frames.
     */
    fun sendFile(fid: String, name: String, size: Long, mime: String, open: () -> InputStream,
                 stop: () -> String? = { null }, progress: (Long) -> Unit = {}): String = synchronized(sendLock) {
        if (closed) return "the connection closed"
        val w = Wait()
        waiting[fid] = w
        try {
            if (!send(JSONObject().put("t", "file").put("id", fid).put("name", name).put("size", size).put("mime", mime))) {
                return "the connection closed"
            }
            val key = MeshPairing.unhex(fid.take(16))
            var sent = 0L
            open().use { input ->
                val buf = ByteArray(Iphone.CHUNK)
                while (sent < size) {
                    while (!closed && !w.isDone && channel.buffered() > Iphone.HIGH_WATER && stop() == null) Thread.sleep(5)
                    stop()?.let { return it }
                    if (closed || w.isDone) break
                    val n = input.read(buf, 0, minOf(Iphone.CHUNK.toLong(), size - sent).toInt())
                    if (n <= 0) return "refused: the file got shorter while it was sent"
                    if (!channel.sendBinary(key + buf.copyOf(n))) break
                    sent += n
                    progress(sent)
                }
            }
            if (w.isDone) {
                if (!w.ok && (w.why.startsWith("paused:") || w.why.startsWith("cancelled"))) return w.why
                return if (w.ok) "ok" else "refused: ${w.why}"
            }
            if (closed || sent < size) return "the connection closed at $sent bytes"
            send(JSONObject().put("t", "file-end").put("id", fid))
            val deadline = System.currentTimeMillis() + Iphone.ACK_TIMEOUT_MS
            while (!w.done.await(200, TimeUnit.MILLISECONDS)) {
                stop()?.let { return it }
                if (System.currentTimeMillis() > deadline) return "no answer"
            }
            if (w.ok) return "ok"
            if (w.why == "the connection closed" || w.why.startsWith("paused:") || w.why.startsWith("cancelled")) return w.why
            return "refused: ${w.why}"
        } catch (e: java.io.IOException) {
            return "refused: can't read it: ${e.message}"
        } finally {
            waiting.remove(fid)
        }
    }
}

/**
 * The iPhone link in the node: the QR code, pairing through the node's own
 * requests, and each connected iPhone as a [MeshNode.BrowserLink] (things for
 * it wait in the outbox until it connects, which is when the app is open).
 */
class IphoneBridge(private val node: MeshNode, private val host: MeshHost, private val appUrl: String = Iphone.APP_URL) : IphoneHost {
    private val tokens = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "iphone-deliver").apply { isDaemon = true } }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "iphone-timer").apply { isDaemon = true } }
    val conns: MutableSet<IphoneConn> = ConcurrentHashMap.newKeySet()
    /** The UDP port it listens on (0 until the transport has started). */
    @Volatile var port = 0

    fun close() {
        conns.toList().forEach { it.close() }
        worker.shutdown()
        timer.shutdown()
    }

    /** A browser's channel opened. */
    fun opened(channel: IphoneChannel): IphoneConn {
        val conn = IphoneConn(this, channel)
        conns += conn
        conn.opened()
        return conn
    }

    // --- the QR code ---------------------------------------------------------------------

    fun newToken(): String {
        val token = Iphone.b64url(ByteArray(16).also { random.nextBytes(it) })
        val now = System.currentTimeMillis()
        tokens.entries.removeIf { it.value <= now }
        tokens[token] = now + Iphone.TOKEN_TTL_MS
        return token
    }

    /** LAN addresses first (IPv4: the transport is), then a tailnet address. */
    fun addresses(): List<String> {
        val found = runCatching { host.localAddresses() }.getOrDefault(emptyList()).filter { it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) }
        return found.filterNot { MeshNode.isTailnet(it) }.take(3) + found.filter { MeshNode.isTailnet(it) }.take(1)
    }

    /** The link for the QR code, or null when this phone has no address an iPhone could reach. */
    fun qrLink(): String? {
        val a = addresses()
        if (a.isEmpty() || port == 0) return null
        return Iphone.link(appUrl, Iphone.payload(host.deviceName(), node.peerId, node.identity.fp, a, port, newToken()))
    }

    // --- IphoneHost --------------------------------------------------------------------------

    override fun serverInfo() = Triple(node.peerId, host.deviceName(), node.identity.fp)

    override fun browser(fp: String): TrustList.Entry? = node.trust.get(fp)?.takeIf { it.isBrowser }

    override fun tokenOk(token: Any?): Boolean {
        if (token !is String) return false
        val exp = tokens[token] ?: return false
        return exp > System.currentTimeMillis()
    }

    override fun pairOpen(body: JSONObject): Pair<Int, JSONObject> {
        val b = JSONObject()
        for (k in listOf("key", "name", "os", "commit")) body.opt(k)?.let { b.put(k, it) }
        if (b.optString("os").isEmpty()) b.put("os", "ios")
        return node.incoming.open(b, node.peerId, host.deviceName(), browser = true)
    }

    override fun pairConfirm(rid: String, body: JSONObject): Pair<Int, JSONObject> {
        val b = JSONObject()
        for (k in listOf("nonce", "sig")) body.opt(k)?.let { b.put(k, it) }
        return node.incoming.confirm(rid, b)
    }

    override fun pairStatus(rid: String): String = node.incoming.status(rid).second.optString("state", MeshPairing.EXPIRED)

    override fun pairCancel(rid: String) {
        node.incoming.cancel(rid)
    }

    override fun authenticated(conn: IphoneConn) {
        conn.link = node.addBrowserLink(conn)
    }

    override fun deliver(conn: IphoneConn, msg: JSONObject) {
        val link = conn.link as? MeshNode.BrowserLink ?: return
        // in order, and off the transport's thread: the node may write to disk or notify
        worker.execute { link.received(msg) }
    }

    override fun received(conn: IphoneConn, fid: String, part: File, name: String, mime: String) {
        val entry = node.trust.get(conn.fp) ?: throw IllegalStateException("not trusted any more")
        val where = host.saveFile(entry, part, name, mime)
        node.browserReceived(entry, fid, name, where)
    }

    override fun alreadyReceived(conn: IphoneConn, fid: String): Boolean {
        val fp = conn.fp ?: return false
        return node.alreadyReceived(fp, fid)
    }

    override fun incomingDir(): File = node.incomingFolder

    override fun closed(conn: IphoneConn) {
        conns -= conn
        (conn.link as? MeshNode.BrowserLink)?.let { node.browserClosed(it) }
    }

    override fun perm(fp: String): JSONObject = node.permFor(fp)

    override fun refuseFile(conn: IphoneConn, msg: JSONObject): JSONObject? {
        val entry = node.trust.get(conn.fp)
        val no = Perms.check(entry, JSONObject().put("t", "file"), node.pausedAll, "in") ?: return null
        val text = Perms.refusalText(host.deviceName(), no.why, no.cap)
        host.log("iphone: refused a file from ${conn.name}: ${if (no.why == Perms.PAUSED) "paused" else "files are off"}")
        val fid = msg.optString("id")
        return if (no.why == Perms.PAUSED) JSONObject().put("t", "refused").put("re", "file").put("id", fid).put("cap", no.cap)
            .put("why", no.why).put("error", text)
        else JSONObject().put("t", "nack").put("id", fid).put("error", text).put("cap", no.cap).put("why", no.why)
    }

    override fun transfer(conn: IphoneConn, fid: String, dir: String?, name: String?, size: Long?, done: Long?,
                          state: String?, error: String?) {
        val fp = conn.fp ?: return
        node.browserTransfer(fp, conn.name, fid, dir, name, size, done, state, error)
    }

    override fun renamed(conn: IphoneConn, fp: String, name: Any?): String? {
        val e = node.trust.rename(fp, name) ?: return null
        host.log("iphone: the iPhone is called ${e.name} now")
        return e.name
    }

    override fun schedule(delayMs: Long, run: () -> Unit) {
        runCatching { timer.schedule({ runCatching(run) }, delayMs, TimeUnit.MILLISECONDS) }
    }

    override fun log(msg: String) = host.log(msg)

    /** Cancelled here: an incoming file from an iPhone. */
    fun cancelReceive(fid: String): Boolean = conns.any { it.receiving(fid) && it.cancelReceive(fid) }
}
