package dev.droplet.app.mesh

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature

/**
 * Direct pairing with no hub (docs/mesh.md §9.3): both screens show the same
 * 4-digit code, derived from both fingerprints and two committed nonces.
 *
 *     transcript = "droplet-pair-v1" LF fpI LF fpR LF nA LF nB      (ASCII, lowercase hex)
 *     code       = SHA-256("droplet-pair-code-v1" LF transcript)[0..8] big-endian mod 10000
 *
 * The initiator is bound to nA (by its commitment) before it sees nB, and
 * the responder picks nB before it sees nA, so a man in the middle gets one
 * guess in 10,000 per attempt, and each attempt shows the owner a request.
 */
object MeshPairing {
    const val WAITING = "waiting"
    const val ACCEPTED = "accepted"
    const val DENIED = "denied"
    const val EXPIRED = "expired"
    const val CANCELLED = "cancelled"

    const val REQUEST_TTL_MS = 300_000L
    private const val MAX_OPEN = 3
    private const val MAX_PER_MINUTE = 20
    private val NONCE = Regex("^[0-9a-f]{64}$")
    private val REQUEST = Regex("^[0-9a-f]{32}$")
    private val random = SecureRandom()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun newNonce(): String = ByteArray(32).also { random.nextBytes(it) }.let(::hex)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    fun commitment(nonceHex: String): String = hex(sha256(unhex(nonceHex)))

    fun transcript(fpI: String, fpR: String, nI: String, nR: String): ByteArray =
        listOf("droplet-pair-v1", fpI, fpR, nI, nR).joinToString("\n").toByteArray(Charsets.US_ASCII)

    fun code(fpI: String, fpR: String, nI: String, nR: String): String {
        val h = sha256("droplet-pair-code-v1\n".toByteArray(Charsets.US_ASCII) + transcript(fpI, fpR, nI, nR))
        return "%04d".format(BigInteger(1, h.copyOf(8)).mod(BigInteger.valueOf(10_000)).toInt())
    }

    /** ECDSA-SHA256 (DER) over [data] by the key in [certDer]; RSA with PKCS#1 v1.5 accepted too, like the reference. */
    fun verify(certDer: ByteArray, signature: ByteArray, data: ByteArray): Boolean = try {
        val key = MeshIdentity.parse(certDer).publicKey
        val alg = when (key.algorithm) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            else -> null
        }
        alg != null && Signature.getInstance(alg).run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (e: Exception) {
        false
    }

    class PairError(message: String) : Exception(message)

    /** A confirmed incoming request, waiting for the owner. */
    data class Request(val request: String, val id: String, val name: String, val os: String, val fp: String,
                       val code: String, val created: Long, val state: String, val der: ByteArray? = null)

    // --- the responder -------------------------------------------------------------

    /** Pairing requests other devices make to this phone. */
    class Incoming(private val ownFp: String, private val clock: () -> Long = System::currentTimeMillis) {
        private class R(val request: String, val created: Long, val id: String, val name: String, val os: String,
                        val fp: String, val der: ByteArray, val commit: String, val nonceR: String) {
            var state = "new"
            var code: String? = null
        }

        private val reqs = LinkedHashMap<String, R>()
        private val posts = ArrayList<Long>()
        /** Called once a request is confirmed and waits for the owner. */
        @Volatile var onReady: ((Request) -> Unit)? = null
        /** Called when a waiting request ends by itself (cancelled, expired). */
        @Volatile var onGone: ((String) -> Unit)? = null

        private fun prune() {
            val now = clock()
            val it = reqs.values.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if ((r.state == "new" || r.state == WAITING) && now - r.created > REQUEST_TTL_MS) {
                    r.state = EXPIRED
                    onGone?.invoke(r.request)
                }
                if (now - r.created > REQUEST_TTL_MS * 2) it.remove()
            }
        }

        /** Step 1: `POST /mesh/pair`. Returns (status, body). */
        @Synchronized
        fun open(body: JSONObject?, myId: String, myName: String): Pair<Int, JSONObject> {
            body ?: return 400 to err("expected a JSON object")
            val peerId = body.opt("id") as? String
            val commit = body.opt("commit") as? String
            if (peerId == null || !TrustList.PEER_ID.matches(peerId)) return 400 to err("bad id")
            if (commit == null || !NONCE.matches(commit)) return 400 to err("bad commit")
            val der = try {
                MeshIdentity.pemToDer(body.opt("cert") as? String)
            } catch (e: IllegalArgumentException) {
                return 400 to err("bad certificate: ${e.message}")
            }
            val fp = MeshIdentity.sha256Hex(der)
            if (fp == ownFp) return 409 to err("that's this device")
            val now = clock()
            posts.removeAll { now - it >= 60_000 }
            if (posts.size >= MAX_PER_MINUTE) return 429 to err("too many pairing requests; wait a minute")
            posts.add(now)
            prune()
            if (reqs.values.count { it.state == "new" || it.state == WAITING } >= MAX_OPEN) {
                return 429 to err("too many pairing requests are waiting; answer those first")
            }
            val rid = hex(ByteArray(16).also { random.nextBytes(it) })
            val nonceR = newNonce()
            val name = TrustList.cleanName(body.opt("name") as? String, peerId)
            val os = ((body.opt("os") as? String) ?: "").filter { it.isLetterOrDigit() }.take(20)
            reqs[rid] = R(rid, now, peerId, name, os, fp, der, commit, nonceR)
            return 200 to JSONObject().put("v", 1).put("request", rid).put("nonce", nonceR).put("id", myId)
                .put("name", myName).put("os", "android").put("fp", ownFp)
        }

        /** Step 2: the reveal and the signature. */
        fun confirm(rid: String, body: JSONObject?): Pair<Int, JSONObject> {
            val ready: Request
            synchronized(this) {
                prune()
                val r = reqs[rid]
                if (r == null || r.state != "new") return 404 to err("no such request")
                val nonceI = body?.opt("nonce") as? String
                val sig = body?.opt("sig") as? String
                var ok = nonceI != null && NONCE.matches(nonceI) &&
                    MessageDigest.isEqual(commitment(nonceI).toByteArray(), r.commit.toByteArray())
                if (ok) {
                    val raw = sig?.let { runCatching { decodeStrict(it) }.getOrNull() } ?: ByteArray(0)
                    ok = verify(r.der, raw, transcript(r.fp, ownFp, nonceI!!, r.nonceR))
                }
                if (!ok) {
                    reqs.remove(rid)
                    return 403 to err("the pairing proof didn't check out")
                }
                r.code = code(r.fp, ownFp, nonceI!!, r.nonceR)
                r.state = WAITING
                ready = public(r)
            }
            onReady?.invoke(ready)
            return 200 to JSONObject().put("state", WAITING)
        }

        @Synchronized
        fun status(rid: String): Pair<Int, JSONObject> {
            prune()
            val r = reqs[rid] ?: return 404 to JSONObject().put("state", EXPIRED)
            return 200 to JSONObject().put("state", if (r.state == "new") WAITING else r.state)
        }

        fun cancel(rid: String): Pair<Int, JSONObject> {
            val out: Pair<Int, JSONObject>
            var gone = false
            synchronized(this) {
                val r = reqs[rid] ?: return 404 to JSONObject().put("state", EXPIRED)
                if (r.state == "new" || r.state == WAITING) {
                    gone = r.state == WAITING
                    r.state = CANCELLED
                }
                out = 200 to JSONObject().put("state", r.state)
            }
            if (gone) onGone?.invoke(rid)
            return out
        }

        @Synchronized
        fun waiting(): List<Request> {
            prune()
            return reqs.values.filter { it.state == WAITING }.sortedBy { it.created }.map { public(it) }
        }

        /** The owner's answer. Returns the request, with its certificate, if it was waiting. */
        @Synchronized
        fun answer(rid: String, accept: Boolean): Request? {
            prune()
            val r = reqs[rid] ?: return null
            if (r.state != WAITING) return null
            r.state = if (accept) ACCEPTED else DENIED
            return public(r).copy(der = r.der)
        }

        private fun public(r: R) = Request(r.request, r.id, r.name, r.os, r.fp, r.code ?: "", r.created,
            if (r.state == "new") WAITING else r.state)

        private fun err(m: String) = JSONObject().put("error", m)

        /** Strict base64 (the reference uses validate=True). */
        private fun decodeStrict(s: String): ByteArray {
            require(Regex("^[A-Za-z0-9+/]*={0,2}$").matches(s) && s.length % 4 == 0)
            return Base64.decode(s, Base64.NO_WRAP)
        }
    }

    // --- the initiator -------------------------------------------------------------

    /** This phone asking another device to pair. Steps 1 and 2 run in [start]. */
    class Outgoing(
        private val identity: MeshIdentity,
        private val myId: String,
        private val myName: String,
        val host: String,
        val port: Int,
        private val expectFp: String?,
    ) {
        var request: String? = null; private set
        var code: String? = null; private set
        var peerId: String = ""; private set
        var peerName: String = ""; private set
        var peerOs: String = ""; private set
        /** The certificate the responder presented in TLS: what gets trusted. */
        var der: ByteArray? = null; private set
        val peerFp: String? get() = der?.let { MeshIdentity.sha256Hex(it) }
        @Volatile var state = "new"
        @Volatile var localOk = false

        // no client certificate: the responder doesn't trust this phone yet. Once
        // step 1 saw its certificate, every later call is pinned to it.
        private fun client(): Pair<OkHttpClient, MeshTls.ClientTrust> = MeshTls.clientWithTrust(null, peerFp ?: expectFp)

        private fun url(path: String) = "https://${hostPort(host, port)}$path"

        private fun call(client: Pair<OkHttpClient, MeshTls.ClientTrust>, method: String, path: String, body: JSONObject?): Triple<Int, JSONObject, ByteArray?> {
            val (c, tm) = client
            val req = okhttp3.Request.Builder().url(url(path)).header("User-Agent", USER_AGENT).apply {
                if (method == "POST") post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            }.build()
            c.newCall(req).execute().use { r ->
                // the certificate this very connection presented (a new connection checked it again)
                val leaf = tm.lastLeaf
                val text = r.body?.source()?.let { src ->
                    src.request(MAX_BODY.toLong())
                    src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY.toLong()))
                } ?: ""
                val j = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
                return Triple(r.code, j, leaf)
            }
        }

        fun start() {
            val nonceI = newNonce()
            val c = client()
            val (status, out, leaf) = call(c, "POST", "/mesh/pair", JSONObject().put("v", 1).put("id", myId)
                .put("name", myName).put("os", "android").put("cert", identity.pem).put("commit", commitment(nonceI)))
            if (status != 200) throw PairError(out.optString("error").ifEmpty { "the peer answered $status" })
            leaf ?: throw PairError("the peer presented no certificate")
            val tlsFp = MeshIdentity.sha256Hex(leaf)
            if (out.optString("fp") != tlsFp) throw PairError("the peer's answer doesn't match the certificate it presented")
            if (tlsFp == identity.fp) throw PairError("that's this device")
            val nonceR = out.opt("nonce") as? String
            val rid = out.opt("request") as? String
            if (nonceR == null || !NONCE.matches(nonceR) || rid == null || !REQUEST.matches(rid)) {
                throw PairError("the peer's answer was malformed")
            }
            val pid = out.opt("id") as? String
            if (pid == null || !TrustList.PEER_ID.matches(pid)) throw PairError("the peer sent a bad id")
            der = leaf
            val sig = identity.sign(transcript(identity.fp, tlsFp, nonceI, nonceR))
            val (s2, got, _) = call(client(), "POST", "/mesh/pair/$rid/confirm",
                JSONObject().put("nonce", nonceI).put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)))
            if (s2 != 200) throw PairError(got.optString("error").ifEmpty { "the peer answered $s2" })
            request = rid
            peerId = pid
            peerName = TrustList.cleanName(out.optString("name"), pid)
            peerOs = out.optString("os").filter { it.isLetterOrDigit() }.take(20)
            code = code(identity.fp, tlsFp, nonceI, nonceR)
            state = WAITING
        }

        fun poll(): String {
            val (_, out, _) = call(client(), "GET", "/mesh/pair/$request", null)
            val s = out.opt("state") as? String
            return if (s in setOf(WAITING, ACCEPTED, DENIED, EXPIRED, CANCELLED)) s!! else EXPIRED
        }

        fun cancel() {
            runCatching { call(client(), "POST", "/mesh/pair/$request/cancel", JSONObject()) }
        }
    }

    const val USER_AGENT = "droplet-android-mesh/1"
    private const val MAX_BODY = 65536
}

fun hostPort(host: String, port: Int): String = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
