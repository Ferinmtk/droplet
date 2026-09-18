package dev.droplet.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The live connection to the hub (docs/remote.md): one WebSocket that lets
 * other devices control this phone's media, read and send its SMS, browse
 * its files and sync the clipboard, and that the presentation remote sends
 * key presses over.
 *
 * It's up while something holds it: the Stay connected service, or the
 * remote screen while it's open. It reconnects with backoff (1 s, 2, 4 … 30)
 * and straight away when the network comes back. Nothing polls: between
 * messages the only traffic is a WebSocket ping every 25 s.
 */
object Live {
    enum class Status { OFF, NO_HUB, UNNAMED, NO_NETWORK, CONNECTING, CONNECTED, RETRYING }

    data class Peer(val id: String, val caps: Set<String>)

    data class Snapshot(
        val status: Status = Status.OFF,
        val deviceId: String? = null,
        val deviceName: String? = null,
        val peers: Map<String, Peer> = emptyMap(),
        val caps: Set<String> = emptySet(),
        val error: String? = null,
    ) {
        val connected get() = status == Status.CONNECTED
        /** Other devices with a live connection (browser tabs or helpers). */
        val othersOnline get() = peers.keys.count { it != deviceId }
    }

    private const val PING_SECONDS = 25L
    private const val MAX_BACKOFF_S = 30L
    private const val RPC_BUDGET_MS = 28_000L  // the hub gives up after 30 s

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Messages a screen may care about: hub errors (e.g. "slim isn't connected"). */
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @SuppressLint("StaticFieldLeak")  // the application context, which lives as long as the process
    private lateinit var app: Context

    // guarded by `this`
    private val holders = mutableSetOf<String>()
    private var socket: WebSocket? = null
    private var generation = 0
    private var attempt = 0
    private var retry: Job? = null
    private var connectedTo: Pair<String, String>? = null  // hub, token of the open socket
    private var rejected: String? = null  // a token the hub turned away (device removed)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    @Volatile private var networkUp = true

    // OkHttp sends no Origin header, which is what the hub wants from helpers
    private val client: OkHttpClient by lazy {
        Hub.client.newBuilder()
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)  // the handshake; the socket itself has no read timeout
            .build()
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    // --- who wants it up ------------------------------------------------------

    /** Keeps the connection up until [release] with the same tag. */
    @Synchronized
    fun hold(tag: String) {
        val first = holders.isEmpty()
        if (!holders.add(tag)) return
        if (first) {
            watchNetwork(true)
            connect()
        }
    }

    @Synchronized
    fun release(tag: String) {
        if (!holders.remove(tag) || holders.isNotEmpty()) return
        watchNetwork(false)
        disconnect(Status.OFF)
    }

    /** Try now if it's down (network back, an alarm fired, the app opened). */
    @Synchronized
    fun kick() {
        if (holders.isEmpty()) return
        val s = _state.value.status
        if (s == Status.CONNECTED || s == Status.CONNECTING) return
        attempt = 0
        connect()
    }

    /**
     * Re-announce if what this phone can do changed (a permission granted, a
     * switch flipped), or if the hub or the device token did. The hello can't
     * be amended, so that means a fresh connection.
     */
    @Synchronized
    fun refresh() {
        if (holders.isEmpty()) return
        val s = _state.value
        val target = Prefs.hubUrl?.let { h -> Hub.deviceToken(h)?.let { h to it } }
        val stale = s.status == Status.CONNECTED && (Caps.current(app) != s.caps || target != connectedTo)
        if (stale || s.status !in setOf(Status.CONNECTED, Status.CONNECTING)) {
            attempt = 0
            connect()
        }
    }

    // --- sending --------------------------------------------------------------

    /** Sends [msg] if connected; false if it couldn't go. */
    fun send(msg: JSONObject): Boolean {
        val ws = synchronized(this) { socket.takeIf { _state.value.connected } } ?: return false
        return ws.send(msg.toString())
    }

    fun sendState(kind: String, data: JSONObject?) =
        send(JSONObject().put("t", "state").put("kind", kind).put("data", data ?: JSONObject.NULL))

    // --- connecting -----------------------------------------------------------

    @Synchronized
    private fun connect() {
        retry?.cancel()
        retry = null
        closeSocket()
        val hub = Prefs.hubUrl
        val token = hub?.let { Hub.deviceToken(it) }
        when {
            holders.isEmpty() -> return publish(Snapshot(Status.OFF))
            hub == null -> return publish(Snapshot(Status.NO_HUB))
            !networkUp -> return publish(Snapshot(Status.NO_NETWORK))
            token == null || token == rejected -> return publish(Snapshot(Status.UNNAMED))
        }
        val caps = Caps.current(app)
        val url = Hub.socketUrl(hub)!!
        val req = Request.Builder().url(url).header("User-Agent", Hub.userAgent).apply {
            Hub.authHeaders(hub!!, hub).forEach { (k, v) -> header(k, v) }
        }.build()
        val gen = ++generation
        connectedTo = hub!! to token!!
        publish(_state.value.copy(status = Status.CONNECTING, caps = caps, error = null))
        socket = client.newWebSocket(req, Listener(gen, caps))
    }

    @Synchronized
    private fun disconnect(status: Status) {
        retry?.cancel()
        retry = null
        closeSocket()
        publish(Snapshot(status))
    }

    private fun closeSocket() {
        generation++  // callbacks from the old socket are ignored from here on
        socket?.let { runCatching { it.close(1000, null) } }
        socket = null
        connectedTo = null
        stopFeatures()
    }

    @Synchronized
    private fun dropped(gen: Int, error: String?, unnamed: Boolean = false) {
        if (gen != generation) return
        generation++  // onClosing, onClosed and onFailure can all follow; act once
        if (unnamed) rejected = connectedTo?.second
        socket = null
        connectedTo = null
        stopFeatures()
        if (holders.isEmpty()) return publish(Snapshot(Status.OFF))
        if (!networkUp) return publish(Snapshot(Status.NO_NETWORK))
        // a rejected token stays rejected; a new one (the page named the phone again) is tried by refresh/kick
        if (unnamed) return publish(Snapshot(Status.UNNAMED, error = error))
        publish(Snapshot(Status.RETRYING, error = error))
        val wait = (1L shl attempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_S)
        attempt++
        retry?.cancel()
        retry = scope.launch {
            delay(wait * 1000)
            synchronized(this@Live) { if (socket == null) connect() }
        }
    }

    private fun publish(s: Snapshot) {
        _state.value = s
    }

    private class Listener(private val gen: Int, private val caps: Set<String>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject()
                .put("t", "hello")
                .put("caps", JSONArray(caps.sorted()))
                .put("platform", "android")
                .put("app", Hub.userAgent)
                .toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            handle(gen, msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            // 1008 "name this device first": the token isn't (or is no longer) a device
            dropped(gen, reason.ifBlank { null }, unnamed = code == 1008 && "name" in reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(gen, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = when {
                // the PIN gate redirects to /login (OkHttp may have followed it)
                response != null && (response.request.url.encodedPath == "/login" ||
                    response.header("Location")?.contains("/login") == true) -> app.getString(R.string.live_err_pin)
                response != null -> app.getString(R.string.live_err_http, response.code)
                else -> t.message ?: t.javaClass.simpleName
            }
            dropped(gen, why)
        }
    }

    // --- incoming -------------------------------------------------------------

    private fun handle(gen: Int, msg: JSONObject) {
        when (msg.optString("t")) {
            "welcome" -> {
                synchronized(this) {
                    if (gen != generation) return
                    attempt = 0
                    val device = msg.optJSONObject("device")
                    publish(_state.value.copy(status = Status.CONNECTED, error = null,
                        deviceId = device?.optString("id"), deviceName = device?.optString("name"),
                        peers = peers(msg.optJSONObject("devices"))))
                }
                startFeatures()
            }
            "presence" -> synchronized(this) {
                if (gen == generation) publish(_state.value.copy(peers = peers(msg.optJSONObject("devices"))))
            }
            "media" -> if ("media" in _state.value.caps) MediaBridge.act(app, msg)
            "clip" -> if ("clipboard" in _state.value.caps) ClipBridge.apply(app, msg.optString("text"))
            "rpc" -> scope.launch { answer(msg) }
            "error" -> _events.tryEmit(msg)
            else -> Unit  // pong, state from other devices, and anything newer: ignored
        }
    }

    private fun peers(devices: JSONObject?): Map<String, Peer> {
        devices ?: return emptyMap()
        return devices.keys().asSequence().associateWith { id ->
            val caps = devices.optJSONObject(id)?.optJSONArray("caps")
            Peer(id, (0 until (caps?.length() ?: 0)).map { caps!!.optString(it) }.toSet())
        }
    }

    private suspend fun answer(msg: JSONObject) {
        val id = msg.optString("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val from = msg.optJSONObject("from")
        val caps = _state.value.caps
        val reply = JSONObject().put("t", "rpc-result").put("id", id)
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droplet:rpc").apply { acquire(RPC_BUDGET_MS + 2_000) }
        try {
            val result = withTimeoutOrNull(RPC_BUDGET_MS) {
                when (method.substringBefore('.')) {
                    "files" -> if ("files" in caps) FileBridge.call(app, method, params, from)
                        else throw RpcError(app.getString(R.string.live_err_off, app.getString(R.string.cap_files)))
                    "sms" -> if ("sms" in caps) SmsBridge.call(app, method, params)
                        else throw RpcError(app.getString(R.string.live_err_off, app.getString(R.string.cap_sms)))
                    else -> throw RpcError("The phone doesn't know $method")
                }
            } ?: throw RpcError(app.getString(R.string.live_err_slow))
            reply.put("result", result)
        } catch (e: RpcError) {
            reply.put("error", e.message)
        } catch (e: SecurityException) {
            reply.put("error", app.getString(R.string.live_err_permission))
        } catch (e: Exception) {
            reply.put("error", e.message ?: e.javaClass.simpleName)
        } finally {
            if (wake.isHeld) wake.release()
        }
        send(reply)
    }

    // --- what runs while connected ---------------------------------------------

    private var batteryReceiver: BroadcastReceiver? = null
    private var lastBattery: Pair<Int, Boolean>? = null

    private fun startFeatures() {
        val caps = _state.value.caps
        if ("media" in caps) MediaBridge.start(app)
        // the hub forgets a helper's state when it disconnects, so say it again
        lastBattery = null
        watchBattery(true)
    }

    private fun stopFeatures() {
        MediaBridge.stop()
        watchBattery(false)
    }

    private fun watchBattery(on: Boolean) {
        batteryReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        batteryReceiver = null
        if (!on) return
        // fires on every small change (voltage, temperature); only level and charging go out
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (level < 0) return
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val now = level * 100 / scale to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
                if (now == lastBattery) return
                if (sendState("battery", JSONObject().put("level", now.first).put("charging", now.second))) {
                    lastBattery = now
                }
                // the hub's Phone card reads the HTTP copy (rate-limited inside)
                scope.launch { StatusReporter.maybeSend(app) }
            }
        }.also {
            ContextCompat.registerReceiver(app, it, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun watchNetwork(on: Boolean) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        if (!on) return
        network = cm.activeNetwork
        networkUp = cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                synchronized(this@Live) {
                    networkUp = true
                    val s = _state.value.status
                    // registering reports the current network straight away: nothing to do then
                    if (net == network && (s == Status.CONNECTED || s == Status.CONNECTING)) return
                    network = net
                    // a different network (Wi-Fi <-> mobile) strands the old socket: start over now
                    attempt = 0
                    connect()
                }
            }

            override fun onLost(net: Network) {
                synchronized(this@Live) {
                    networkUp = cm.activeNetwork?.let { it != net } == true
                    if (!networkUp) {
                        network = null
                        disconnect(Status.NO_NETWORK)
                    }
                }
            }
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }
}

/** An RPC failure whose message is meant for the person at the other end. */
class RpcError(message: String) : Exception(message)

/** One line for Settings and the notification, e.g. "Connected · 3 devices online". */
fun Live.Snapshot.describe(context: Context): String = when (status) {
    Live.Status.OFF -> context.getString(R.string.live_off)
    Live.Status.NO_HUB -> context.getString(R.string.live_no_hub)
    Live.Status.UNNAMED -> context.getString(R.string.live_unnamed)
    Live.Status.NO_NETWORK -> context.getString(R.string.conn_no_network)
    Live.Status.CONNECTING -> context.getString(R.string.conn_connecting)
    Live.Status.CONNECTED -> if (othersOnline == 0) context.getString(R.string.live_alone)
        else context.resources.getQuantityString(R.plurals.live_online, othersOnline, othersOnline)
    Live.Status.RETRYING -> context.getString(R.string.live_retrying) + (error?.let { " ($it)" } ?: "")
}
