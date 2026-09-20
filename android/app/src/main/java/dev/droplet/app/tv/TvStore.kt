package dev.droplet.app.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** A TV this phone paired with (directly, not through the hub). */
data class PairedTv(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = TvCatalog.API_PORT,
    /** From the TV's certificate or its mDNS record: for Wake-on-LAN and following it to a new address. */
    val mac: String? = null,
    /** Its mDNS instance name, to follow it when DHCP moves it. */
    val mdns: String? = null,
    /** SHA-256 of the TV's certificate from pairing: only that certificate is accepted after. */
    val pin: String,
    /** False once the TV has forgotten droplet (or its certificate changed): pair again. */
    val paired: Boolean = true,
    /** Why it needs pairing again, for the UI. */
    val lost: String? = null,
    val model: String? = null,
    val added: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("mac", mac).put("mdns", mdns).put("pin", pin).put("paired", paired).put("lost", lost)
        .put("model", model).put("added", added)

    companion object {
        fun fromJson(j: JSONObject): PairedTv? = runCatching {
            PairedTv(
                id = j.getString("id"),
                name = j.optString("name").ifEmpty { "TV" },
                host = j.getString("host"),
                port = j.optInt("port", TvCatalog.API_PORT).takeIf { it in 1..65534 } ?: TvCatalog.API_PORT,
                mac = TvCatalog.normMac(j.optString("mac").takeIf { !j.isNull("mac") }),
                mdns = j.optString("mdns").takeIf { !j.isNull("mdns") && it.isNotEmpty() },
                pin = j.getString("pin").also { require(Regex("^[0-9a-f]{64}$").matches(it)) },
                paired = j.optBoolean("paired", true),
                lost = j.optString("lost").takeIf { !j.isNull("lost") && it.isNotEmpty() },
                model = j.optString("model").takeIf { !j.isNull("model") && it.isNotEmpty() },
                added = j.optLong("added", System.currentTimeMillis()),
            )
        }.getOrNull()
    }
}

/** The paired TVs, in `tvs.json` in the app's private storage. */
class TvStore(private val dir: File) {
    private val file get() = File(dir, "tvs.json")

    @Synchronized
    fun all(): List<PairedTv> {
        val arr = runCatching { JSONObject(file.readText()).getJSONArray("tvs") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(PairedTv::fromJson) }
    }

    fun get(id: String?): PairedTv? = id?.let { i -> all().firstOrNull { it.id == i } }

    /** Adds or replaces (by id). */
    @Synchronized
    fun put(tv: PairedTv) = write(all().filter { it.id != tv.id } + tv)

    @Synchronized
    fun remove(id: String) = write(all().filter { it.id != id })

    @Synchronized
    fun update(id: String, change: (PairedTv) -> PairedTv): PairedTv? {
        val list = all()
        val tv = list.firstOrNull { it.id == id } ?: return null
        val changed = change(tv)
        if (changed != tv) write(list.map { if (it.id == id) changed else it })
        return changed
    }

    /**
     * The TV paired before that this new pairing is, if any: same MAC, same
     * mDNS name, or same address. Pairing it again refreshes that entry.
     */
    fun sameTv(host: String, mac: String?, mdns: String?): PairedTv? = all().firstOrNull {
        (mac != null && it.mac == mac) || (mdns != null && it.mdns == mdns) || it.host == host
    }

    private fun write(list: List<PairedTv>) {
        dir.mkdirs()
        val data = JSONObject().put("tvs", JSONArray().apply { list.forEach { put(it.toJson()) } })
        TvIdentity.writePrivate(file, data.toString(1).toByteArray())
    }
}

/**
 * Wake-on-LAN, best effort: a TV in deep standby may answer it; many
 * Google TVs keep the network up in standby and never need it. The same
 * packets as the hub's tv.py sends.
 */
object TvWake {
    fun magicPacket(mac: String): ByteArray {
        val m = TvCatalog.normMac(mac) ?: throw IllegalArgumentException("not a MAC address")
        val bytes = m.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
    }

    /** Where to send it: the broadcast address, and the TV's /24 broadcast (more Wi-Fi bridges pass that on). */
    fun targets(host: String?): List<String> {
        val out = mutableListOf("255.255.255.255")
        val ip = host?.let { TvCatalog.literal(it) }
        if (ip is Inet4Address && ip.isSiteLocalAddress) {
            val b = ip.address
            out += "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.255"
        }
        return out
    }

    /** Blocking (sockets): off the main thread. True if a packet went out. */
    fun send(mac: String, host: String?): Boolean {
        val packet = magicPacket(mac)
        var sent = false
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                for (t in targets(host)) for (port in intArrayOf(9, 7)) {
                    runCatching {
                        s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port))
                        sent = true
                    }
                }
            }
        }
        return sent
    }
}
