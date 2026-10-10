package dev.droplet.app.mesh

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pairing on a phone's hotspot, where mDNS doesn't reach (docs/mesh.md §9.10).
 *
 * A phone serving a hotspot doesn't announce itself to the devices that
 * joined it, and may not hear them either; but it's always their default
 * gateway. So while a Pair screen is open, a device asks its gateway who it
 * is, saying who it is itself:
 *
 *     POST https://<gateway>:1739/mesh/pair/hello      (no client certificate)
 *     {"v":1, "id", "name", "os", "fp", "port"}  →  200 the same fields, the answerer's
 *
 * The asker checks the answer's `fp` is the certificate presented in TLS
 * and lists the answerer for pairing; the answerer lists the asker at the
 * address it asked from, for a minute. Both are hints, like mDNS: pairing
 * (§9.3) and its 4-digit code are unchanged, and nothing pairs by itself.
 */
object Hotspot {
    const val HELLO_PATH = "/mesh/pair/hello"
    const val KNOCK_TTL_MS = 60_000L
    const val MAX_KNOCKS = 8
    const val MAX_HELLOS = 60
    const val FOUND_TTL_MS = 30_000L
    const val HELLO_EVERY_MS = 10_000L
    const val QUIET_FIRST_MS = 5_000L
    const val QUIET_MAX_MS = 60_000L
    private val FP = Regex("^[0-9a-f]{64}$")
    private val OSES = setOf("android", "windows", "linux", "macos")
    private const val MAX_BODY = 65536L

    /** A hello's fields, or null if it isn't a usable one. */
    fun parse(body: JSONObject?, address: String): Seen? {
        body ?: return null
        val id = body.opt("id") as? String ?: return null
        val fp = body.opt("fp") as? String ?: return null
        if (!TrustList.PEER_ID.matches(id) || !FP.matches(fp)) return null
        val os = (body.opt("os") as? String)?.takeIf { it in OSES } ?: ""
        val port = (body.opt("port") as? Int)?.takeIf { it in 1..65535 } ?: MeshNode.DEFAULT_PORT
        return Seen(fp, id, TrustList.cleanName(body.opt("name") as? String, id).take(64), os, emptyList(), "", port, listOf(address))
    }

    fun me(id: String, fp: String, name: String, port: Int): JSONObject = JSONObject().put("v", 1).put("id", id)
        .put("name", TrustList.cleanName(name, id).take(63)).put("os", "android").put("fp", fp).put("port", port)

    /** The answering side: devices that asked who this one is, listed for a minute. */
    class Knocks(private val ownFp: String, private val clock: () -> Long = System::currentTimeMillis) {
        private val seen = LinkedHashMap<String, Pair<Seen, Long>>()
        private val answered = ArrayList<Long>()
        /** A device not listed before asked. */
        @Volatile var onNew: (() -> Unit)? = null

        fun answer(body: JSONObject?, address: String?, mine: JSONObject): Pair<Int, JSONObject> {
            var fresh = false
            synchronized(this) {
                val now = clock()
                answered.removeAll { now - it >= 60_000 }
                if (answered.size >= MAX_HELLOS) return 429 to JSONObject().put("error", "too many requests; wait a minute")
                answered += now
                prune(now)
                val who = if (address.isNullOrEmpty()) null else parse(body, address)
                if (who != null && who.fp != ownFp && (who.fp in seen || seen.size < MAX_KNOCKS)) {
                    fresh = who.fp !in seen
                    seen[who.fp] = who to now
                }
            }
            if (fresh) onNew?.invoke()
            return 200 to mine
        }

        private fun prune(now: Long) {
            seen.values.removeAll { now - it.second > KNOCK_TTL_MS }
        }

        @Synchronized
        fun peers(): List<Seen> {
            prune(clock())
            return seen.values.map { it.first }
        }
    }

    private val CLIENT = MeshTls.BASE.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build()

    /**
     * Asks the device at [host]:[port] who it is (saying who this one is,
     * [mine]). Null: no droplet device there, an older one (404), or an
     * answer that doesn't match the certificate it presented. Blocks.
     */
    fun ask(host: String, port: Int, mine: JSONObject): Seen? {
        val (c, tm) = MeshTls.clientWithTrust(null, null, CLIENT)
        val req = okhttp3.Request.Builder().url("https://${hostPort(host, port)}$HELLO_PATH")
            .header("User-Agent", MeshPairing.USER_AGENT)
            .post(mine.toString().toRequestBody("application/json".toMediaType())).build()
        c.newCall(req).execute().use { r ->
            val leaf = tm.lastLeaf
            if (r.code != 200 || leaf == null) return null
            val src = r.body?.source() ?: return null
            src.request(MAX_BODY)
            val text = src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY))
            val who = parse(runCatching { JSONObject(text) }.getOrNull(), host) ?: return null
            return who.takeIf { it.fp == MeshIdentity.sha256Hex(leaf) }
        }
    }

    /** The asking side: which gateways answered, and when to ask each again. */
    class GatewayScan(private val clock: () -> Long = System::currentTimeMillis) {
        private val found = HashMap<String, Pair<Seen, Long>>()        // gateway → (who, until)
        private val next = HashMap<String, Pair<Long, Long>>()         // gateway → (when to ask again, the wait)

        @Synchronized
        fun due(gateways: List<String>, force: Boolean = false): List<String> {
            val now = clock()
            found.keys.retainAll(gateways.toSet())
            next.keys.retainAll(gateways.toSet())
            return gateways.filter { force || (next[it]?.first ?: 0L) <= now }
        }

        @Synchronized
        fun result(gateway: String, seen: Seen?) {
            val now = clock()
            if (seen != null) {
                found[gateway] = seen to now + FOUND_TTL_MS
                next[gateway] = (now + HELLO_EVERY_MS) to 0L
                return
            }
            found.remove(gateway)
            val wait = ((next[gateway]?.second ?: 0L) * 2).coerceIn(QUIET_FIRST_MS, QUIET_MAX_MS)
            next[gateway] = (now + wait) to wait
        }

        @Synchronized
        fun peers(): List<Seen> {
            val now = clock()
            return found.values.filter { it.second > now }.map { it.first }
        }
    }
}
