package dev.droplet.app.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The peers this phone talks to (docs/mesh.md §3), as the Linux agent keeps
 * them: keyed by fingerprint, each with its full certificate.
 *
 * - "roster": vouched for by the hub, replaced wholesale by each roster
 *   fetch, kept while the hub is down.
 * - "paired": paired directly by the owner; survives roster changes and
 *   goes only when unpaired.
 */
class TrustList(private val file: File, private val ownFp: String) {
    data class Entry(
        val id: String,
        val name: String,
        val fp: String,
        val certPem: String,
        val source: String,
        val lan: List<String> = emptyList(),
        val port: Int? = null,
        val tailnetIp: String? = null,
        val os: String = "",
        val caps: List<String> = emptyList(),
        /** The hub whose roster lists it: messages may go through that hub. */
        val hub: String = "",
        val added: Long = System.currentTimeMillis() / 1000,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("fp", fp).put("cert_pem", certPem)
            .put("source", source).put("lan", JSONArray(lan)).put("port", port ?: JSONObject.NULL)
            .put("tailnet_ip", tailnetIp ?: JSONObject.NULL).put("os", os).put("caps", JSONArray(caps))
            .put("hub", hub).put("added", added)
    }

    /** Called (outside the lock) when the set of trusted certificates changed. */
    @Volatile var onChange: (() -> Unit)? = null

    private val lock = Any()
    private var peers = LinkedHashMap<String, Entry>()

    init {
        load()
    }

    private fun load() {
        val raw = runCatching { JSONObject(file.readText()) }.getOrNull()?.optJSONObject("peers") ?: return
        for (fp in raw.keys()) {
            val e = raw.optJSONObject(fp) ?: continue
            try {
                val entry = makeEntry(
                    peerId = e.optString("id"), name = e.optString("name"), certPem = e.optString("cert_pem"),
                    source = e.optString("source"), lan = strings(e.optJSONArray("lan")), port = e.opt("port"),
                    tailnetIp = e.optString("tailnet_ip").takeIf { !e.isNull("tailnet_ip") }, os = e.optString("os"),
                    caps = strings(e.optJSONArray("caps")), fp = fp, hub = e.optString("hub"),
                ).copy(added = e.optLong("added").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000))
                if (entry.fp != ownFp) peers[entry.fp] = entry
            } catch (x: IllegalArgumentException) {
                // a damaged entry is dropped, never trusted
            }
        }
    }

    private fun save() {
        val out = JSONObject()
        peers.values.forEach { out.put(it.fp, it.toJson()) }
        file.parentFile?.mkdirs()
        MeshIdentity.writePrivate(file, JSONObject().put("v", 1).put("peers", out).toString(1).toByteArray())
    }

    private fun changed(certs: Boolean) {
        save()
        if (certs) onChange?.let { cb -> Thread { cb() }.start() }
    }

    // --- reading -------------------------------------------------------------

    fun get(fp: String?): Entry? = synchronized(lock) { peers[fp ?: return null] }

    fun isTrusted(fp: String): Boolean = get(fp) != null

    fun all(): List<Entry> = synchronized(lock) { peers.values.sortedWith(compareBy({ it.name.lowercase() }, { it.fp })) }

    /** Peers matching a name (any case), an id, or a fingerprint prefix of 8+ hex. */
    fun find(query: String): List<Entry> {
        val q = query.trim()
        val ql = q.lowercase().replace(":", "")
        val list = all()
        val exact = list.filter { it.id == ql || it.fp == ql || it.name.equals(q, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return if (ql.length >= 8) list.filter { it.fp.startsWith(ql) } else emptyList()
    }

    // --- changing -------------------------------------------------------------

    /** Trusts a directly paired peer. A re-pair of the same device id replaces its old certificate. */
    fun addPaired(entry: Entry) {
        require(entry.source == SOURCE_PAIRED)
        require(entry.fp != ownFp) { "that's this device" }
        synchronized(lock) {
            peers.entries.removeAll { it.value.id == entry.id && it.value.source == SOURCE_PAIRED && it.key != entry.fp }
            val old = peers[entry.fp]
            peers[entry.fp] = if (old == null) entry else entry.copy(
                lan = cleanAddresses(entry.lan + old.lan), tailnetIp = entry.tailnetIp ?: old.tailnetIp,
                port = entry.port ?: old.port)
            changed(true)
        }
    }

    fun remove(fp: String): Entry? = synchronized(lock) {
        peers.remove(fp)?.also { changed(true) }
    }

    /** Makes the roster peers exactly [entries] (from hub [hubId]); paired peers stay and learn addresses. Returns (added, removed). */
    fun syncRoster(entries: List<Entry>, hubId: String): Pair<Int, Int> = synchronized(lock) {
        val fresh = entries.filter { it.fp != ownFp }.associateBy { it.fp }
        val before = peers.mapValues { it.value.certPem }
        var removed = 0
        for ((fp, e) in peers.entries.toList()) {
            if (e.source == SOURCE_ROSTER && fp !in fresh) {
                peers.remove(fp)
                removed++
            } else if (e.source == SOURCE_PAIRED && fp !in fresh && e.hub == hubId) {
                peers[fp] = e.copy(hub = "")   // still paired, but that hub no longer knows it
            }
        }
        var added = 0
        for ((fp, e) in fresh) {
            val old = peers[fp]
            peers[fp] = when {
                old == null -> e.also { added++ }
                // direct pairing is the owner's decision: it stays "paired" and learns where the peer is
                old.source == SOURCE_PAIRED -> old.copy(lan = cleanAddresses(e.lan + old.lan), port = e.port ?: old.port,
                    tailnetIp = e.tailnetIp ?: old.tailnetIp, os = e.os.ifEmpty { old.os },
                    caps = e.caps.ifEmpty { old.caps }, hub = e.hub)
                else -> e.copy(added = old.added, lan = cleanAddresses(e.lan + old.lan))
            }
        }
        changed(peers.mapValues { it.value.certPem } != before)
        added to removed
    }

    /** Records what an authenticated peer said about itself, or where it answered. */
    fun learn(fp: String, address: String? = null, port: Int? = null, name: String? = null, peerId: String? = null,
              os: String? = null, caps: List<String>? = null, tailnet: Boolean = false) = synchronized(lock) {
        val e = peers[fp] ?: return@synchronized
        var n = e
        if (address != null) {
            n = if (tailnet) n.copy(tailnetIp = cleanAddresses(listOf(address), 1).firstOrNull() ?: n.tailnetIp)
            else n.copy(lan = cleanAddresses(listOf(address) + n.lan))
        }
        if (port != null && port in 1..65535) n = n.copy(port = port)
        if (n.source == SOURCE_PAIRED) {
            // the hub names roster peers; a paired peer names itself
            if (!name.isNullOrBlank()) n = n.copy(name = cleanName(name, n.name))
            if (peerId != null && PEER_ID.matches(peerId)) n = n.copy(id = peerId)
        }
        if (!os.isNullOrBlank()) n = n.copy(os = cleanName(os).take(20))
        if (caps != null) n = n.copy(caps = cleanCaps(caps))
        if (n != e) {
            peers[fp] = n
            save()
        }
    }

    companion object {
        const val SOURCE_ROSTER = "roster"
        const val SOURCE_PAIRED = "paired"
        val PEER_ID = Regex("^[0-9a-f]{8,64}$")
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private const val MAX_LAN = 6

        private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.opt(it) as? String }

        /** A checked entry. Throws IllegalArgumentException when the id, certificate or fingerprint is wrong. */
        fun makeEntry(peerId: String?, name: String?, certPem: String?, source: String, lan: List<String> = emptyList(),
                      port: Any? = null, tailnetIp: String? = null, os: String? = "", caps: List<String> = emptyList(),
                      fp: String? = null, hub: String? = null): Entry {
            require(source == SOURCE_ROSTER || source == SOURCE_PAIRED) { "bad source" }
            require(peerId != null && PEER_ID.matches(peerId)) { "bad peer id" }
            val der = MeshIdentity.pemToDer(certPem)
            val real = MeshIdentity.sha256Hex(der)
            if (fp != null) require(fp.replace(":", "").trim().lowercase() == real) { "the fingerprint doesn't match the certificate" }
            val p = (port as? Number)?.toInt()?.takeIf { it in 1..65535 && port !is Double }
            return Entry(
                id = peerId, name = cleanName(name, peerId), fp = real, certPem = MeshIdentity.toPem(der), source = source,
                lan = cleanAddresses(lan), port = p, tailnetIp = tailnetIp?.let { cleanAddresses(listOf(it), 1).firstOrNull() },
                os = cleanName(os).take(20), caps = cleanCaps(caps),
                hub = hub?.takeIf { PEER_ID.matches(it) } ?: "",
            )
        }

        fun cleanName(v: String?, fallback: String = ""): String {
            val name = (v ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                .filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }.take(64)
            return name.ifEmpty { fallback }
        }

        fun cleanCaps(v: List<String>): List<String> =
            v.map { it.trim() }.filter { c -> c.isNotEmpty() && c.length <= 20 && c.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } }
                .toSortedSet().toList()

        /** An IP literal, never a DNS lookup. */
        fun ipLiteral(s: String?): InetAddress? {
            val t = s?.trim()?.substringBefore('%')?.removePrefix("[")?.removeSuffix("]") ?: return null
            val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(t)
            if (v4 != null) {
                if (v4.groupValues.drop(1).any { it.toInt() > 255 }) return null
                return runCatching { InetAddress.getByName(t) }.getOrNull()
            }
            if (':' in t && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return runCatching { InetAddress.getByName(t) }.getOrNull() as? Inet6Address
            }
            return null
        }

        /** The usual text form: dotted IPv4, compressed IPv6 ("fd00::1"), as Python writes them. */
        fun text(ip: InetAddress): String {
            if (ip is Inet4Address) return ip.hostAddress!!
            val b = ip.address
            val groups = (0 until 8).map { ((b[it * 2].toInt() and 0xff) shl 8) or (b[it * 2 + 1].toInt() and 0xff) }
            // the longest run of two or more zero groups becomes "::" (the first, if tied)
            var best = -1
            var bestLen = 1
            var i = 0
            while (i < 8) {
                if (groups[i] == 0) {
                    var j = i
                    while (j < 8 && groups[j] == 0) j++
                    if (j - i > bestLen) { best = i; bestLen = j - i }
                    i = j
                } else i++
            }
            val hex = groups.map { Integer.toHexString(it) }
            return if (best < 0) hex.joinToString(":")
            else hex.subList(0, best).joinToString(":") + "::" + hex.subList(best + bestLen, 8).joinToString(":")
        }

        fun cleanAddresses(items: List<String>, limit: Int = MAX_LAN): List<String> {
            val out = ArrayList<String>()
            for (a in items) {
                val ip = ipLiteral(a) ?: continue
                if (ip.isAnyLocalAddress || ip.isMulticastAddress || (ip is Inet6Address && ip.isLinkLocalAddress)) continue
                val s = text(ip)
                if (s !in out) out.add(s)
            }
            return out.take(limit)
        }
    }
}
