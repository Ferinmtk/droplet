package dev.droplet.app.tv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.droplet.app.Discovery
import dev.droplet.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The phone's own TV remote (mesh step 4): pairs with Android TV / Google
 * TV sets directly and talks to them over the Android TV Remote protocol v2,
 * so it works with the hub down. The hub's TV remote (tv.py, the web page's
 * TV card) is separate and unchanged; a TV paired with both lists them as
 * two remotes.
 *
 * Holds the paired TVs, the client identity, and one live [TvLink] to the
 * chosen TV while a screen holds it ([hold]/[release]). While held, it also
 * watches mDNS for the TV, to follow it to a new address and to retry at
 * once when it announces itself.
 */
object Tv {
    const val SERVICE_TYPE = "_androidtvremote2._tcp"
    private const val TAG = "droplet-tv"
    private const val RELEASE_GRACE_MS = 5_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    /** Blocking work (the identity, the store, Wake-on-LAN), in order, off the main thread. */
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-io").apply { isDaemon = true } }

    /** Tests: where the identity and the TV list live. */
    @Volatile var dirOverride: File? = null
    /** Tests: replaces mDNS browsing. */
    @Volatile var browser: ((Context, (List<Found>) -> Unit) -> Discovery.Handle)? = null
    @Volatile var log: (String) -> Unit = { Log.i(TAG, it) }
    @Volatile var timing: TvLink.Timing = TvLink.Timing()

    val dir: File get() = dirOverride ?: File(app.filesDir, "tv")
    val store: TvStore get() = TvStore(dir)

    @Volatile private var identity: Pair<File, TvIdentity>? = null

    /** Bumped when the list of TVs changes. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The live link to the chosen TV, while held. */
    private val _link = MutableStateFlow<TvLink?>(null)
    val link: StateFlow<TvLink?> = _link.asStateFlow()
    private var linkTv: String? = null

    private val holders = mutableSetOf<String>()
    private val stopLater = Runnable {
        if (holders.isEmpty()) {
            stopLink()
            watch?.stop()
            watch = null
        }
    }
    private var watch: Discovery.Handle? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The client identity, made on first use. Blocking. */
    fun identity(): TvIdentity = synchronized(this) {
        val d = dir
        identity?.takeIf { it.first == d }?.second ?: TvIdentity.loadOrCreate(d).also { identity = d to it }
    }

    /** What the TV shows as this remote's name while pairing. */
    fun clientName(): String {
        val phone = (Prefs.meshDeviceName ?: Build.MODEL).orEmpty().trim().take(40)
        return if (phone.isEmpty()) "droplet" else "droplet ($phone)"
    }

    // --- the TVs ----------------------------------------------------------------------------

    fun tvs(): List<PairedTv> = store.all().sortedBy { it.name.lowercase() }

    /** The TV the remote shows: the one chosen last, or the only one. */
    fun selected(): PairedTv? {
        val all = store.all()
        return all.firstOrNull { it.id == Prefs.tvSelected } ?: all.firstOrNull()
    }

    fun select(id: String) {
        Prefs.tvSelected = id
        if (holders.isNotEmpty()) main.post { restartLink() }
        _changes.update { it + 1 }
    }

    /** Saves a freshly paired TV (or refreshes one paired before) and makes it the chosen one. */
    fun adopt(host: String, port: Int, name: String, mac: String?, mdns: String?, serverCertDer: ByteArray): PairedTv {
        val pin = TvIdentity.sha256Hex(serverCertDer)
        val before = store.sameTv(host, mac, mdns)
        val tv = PairedTv(
            id = before?.id ?: randomId(),
            name = name,
            host = host,
            port = port,
            mac = mac ?: before?.mac,
            mdns = mdns ?: before?.mdns,
            pin = pin,
            paired = true,
            lost = null,
            model = before?.model,
            added = before?.added ?: System.currentTimeMillis(),
        )
        store.put(tv)
        Prefs.tvSelected = tv.id
        main.post {
            _changes.update { it + 1 }
            if (holders.isNotEmpty()) restartLink()
        }
        return tv
    }

    fun forget(id: String) {
        store.remove(id)
        if (Prefs.tvSelected == id) Prefs.tvSelected = null
        main.post {
            if (linkTv == id) stopLink()
            _changes.update { it + 1 }
        }
    }

    private fun randomId(): String = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // --- the live link ------------------------------------------------------------------------

    /** Keeps a link to the chosen TV open while anything holds it. Main thread. */
    fun hold(tag: String) {
        main.removeCallbacks(stopLater)
        holders += tag
        if (_link.value == null || linkTv != selected()?.id) restartLink() else _link.value?.let { if (!it.isRunning) restartLink() }
        startWatching()
    }

    /** Lets go; the link closes a few seconds after the last holder (a rotation doesn't drop it). Main thread. */
    fun release(tag: String) {
        holders -= tag
        if (holders.isEmpty()) {
            main.removeCallbacks(stopLater)
            main.postDelayed(stopLater, RELEASE_GRACE_MS)
        }
    }

    /** Starts (or restarts) the link to the chosen TV, if it's paired. Main thread. */
    fun restartLink() {
        stopLink()
        val tv = selected() ?: return
        if (!tv.paired) return
        linkTv = tv.id
        io.execute {
            val id = try {
                identity()
            } catch (e: Exception) {
                log("no client identity: ${e.message}")
                return@execute
            }
            main.post {
                if (linkTv != tv.id || holders.isEmpty() || _link.value != null) return@post
                val l = TvLink(id, tv.host, tv.port, tv.pin, log, timing)
                _link.value = l
                l.start()
                watchLink(tv.id, l)
            }
        }
    }

    private fun stopLink() {
        watchJob?.cancel()
        watchJob = null
        _link.value?.stop()
        _link.value = null
        linkTv = null
    }

    /** A TV that refused droplet (or showed another certificate) is marked, so the UI asks to pair again. */
    private fun watchLink(id: String, l: TvLink) {
        watchJob?.cancel()
        watchJob = scope.launch {
            var lastModel: String? = null
            l.state.collect { s ->
                if (s.phase == TvLink.Phase.NEEDS_PAIRING || s.phase == TvLink.Phase.IDENTITY_CHANGED) {
                    val why = if (s.phase == TvLink.Phase.NEEDS_PAIRING) LOST_FORGOT else LOST_IDENTITY
                    store.update(id) { it.copy(paired = false, lost = why) }
                    _changes.update { it + 1 }
                } else if (s.model != null && s.model != lastModel) {
                    lastModel = s.model
                    store.update(id) { it.copy(model = s.model) }
                }
            }
        }
    }

    const val LOST_FORGOT = "forgot"
    const val LOST_IDENTITY = "identity"

    // --- following the TV on the network ---------------------------------------------------------

    /** A TV announcing itself on the Wi-Fi. */
    data class Found(val name: String, val host: String, val port: Int, val mac: String?, val service: String)

    /** DNS-SD instance names can arrive with their escapes (`\032` for a space); undo them. */
    fun unescape(name: String): String {
        if ('\\' !in name) return name
        val sb = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        fun flush() {
            if (bytes.size() > 0) { sb.append(bytes.toByteArray().toString(Charsets.UTF_8)); bytes.reset() }
        }
        while (i < name.length) {
            val c = name[i]
            if (c == '\\' && i + 3 < name.length && name.substring(i + 1, i + 4).all(Char::isDigit)) {
                val v = name.substring(i + 1, i + 4).toInt()
                if (v <= 255) { bytes.write(v); i += 4; continue }
            }
            if (c == '\\' && i + 1 < name.length) {
                flush(); sb.append(name[i + 1]); i += 2; continue
            }
            flush()
            sb.append(c)
            i++
        }
        flush()
        return sb.toString()
    }

    fun parseFound(service: String, host: String?, port: Int, attrs: Map<String, ByteArray?>): Found? {
        if (host.isNullOrBlank() || port !in 1..65534) return null
        val ip = TvCatalog.literal(host) ?: return null
        if (!TvCatalog.isLocal(ip)) return null
        val mac = attrs.entries.firstOrNull { it.key.equals("bt", ignoreCase = true) }?.value?.toString(Charsets.UTF_8)
        val name = unescape(service).trim().ifEmpty { "TV" }.take(64)
        return Found(name, host, port, TvCatalog.normMac(mac), service)
    }

    /** Browses for TVs until the handle is stopped. [onChange] runs on the main thread. */
    fun browse(context: Context, onChange: (List<Found>) -> Unit): Discovery.Handle =
        browser?.invoke(context, onChange)
            ?: Discovery.browseNamed(context, SERVICE_TYPE, ::parseFound, { list -> onChange(list.distinctBy { it.service }) })

    private fun startWatching() {
        if (watch != null) return
        watch = runCatching { browse(app) { found -> follow(found) } }.getOrNull()
    }

    /** The chosen TV turned up: at a new address (DHCP moved it), or back on the network. */
    private fun follow(found: List<Found>) {
        val l = _link.value ?: return
        val tv = store.get(linkTv) ?: return
        val f = found.firstOrNull { (tv.mdns != null && it.service == tv.mdns) || (tv.mac != null && it.mac == tv.mac) } ?: return
        if (f.host != tv.host || f.port != tv.port) {
            store.update(tv.id) { it.copy(host = f.host, port = f.port, mdns = f.service) }
            if (f.port != tv.port) restartLink() else l.moveTo(f.host)
        } else if (!l.state.value.connected) {
            l.kick()   // it's announcing itself, so it's awake: try now
        }
    }
}
