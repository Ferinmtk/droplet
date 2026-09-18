package dev.droplet.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What `/api/hub/info` says about a hub (docs/local-first.md §1). */
data class HubInfo(
    val id: String,
    val name: String?,
    val fingerprint: String?,
    /** "ip:port" of its LAN HTTPS listener, if it has one. */
    val lan: List<String>,
    val tailnet: String?,
    val pin: Boolean,
) {
    companion object {
        fun parse(text: String): HubInfo? {
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val id = j.optString("id").lowercase().takeIf { Discovery.isHubId(it) } ?: return null
            val lanJ = j.optJSONObject("lan")
            val port = lanJ?.optInt("https_port", 0) ?: 0
            val addrs = lanJ?.optJSONArray("addresses")
            val lan = if (port in 1..65535 && addrs != null) {
                (0 until addrs.length()).mapNotNull { i ->
                    // a hub with no network reports loopback, which would reach the phone itself
                    addrs.optString(i).takeIf { it.isNotBlank() && !it.startsWith("127.") && it != "::1" }?.let { hostPort(it, port) }
                }
            } else emptyList()
            return HubInfo(
                id = id,
                name = j.optString("name").takeIf { it.isNotBlank() && !j.isNull("name") }?.take(64),
                fingerprint = j.optString("fingerprint").lowercase().takeIf { !j.isNull("fingerprint") && Pinning.isFingerprint(it) },
                lan = lan,
                tailnet = j.optString("tailnet").takeIf { !j.isNull("tailnet") && it.isNotBlank() }?.let { Hub.normalize(it) },
                pin = j.optBoolean("pin"),
            )
        }
    }
}

/**
 * Picks how to reach the hub (docs/local-first.md §3): straight over the
 * Wi-Fi with the pinned certificate when the hub is there, otherwise its
 * tailnet URL with ordinary TLS. Everything that talks to the hub asks here
 * for the base URL and the HTTP client.
 *
 * Re-evaluates when the network changes (Wi-Fi joined or left, a VPN such
 * as Tailscale going up or down) while something holds it, and while on the
 * tailnet or unreachable it keeps looking for the LAN every few minutes.
 */
object Router {
    enum class Kind { LAN, TAILNET }

    /** A way to the hub: [base] is "https://192.168.100.20:8443" or the tailnet URL. */
    data class Route(val kind: Kind, val base: String, val pin: String? = null) {
        val host: String get() = Uri.parse(base).host ?: base
    }

    /** A hub that claims to be ours shows a different certificate: never used silently. */
    data class IdentityChange(val address: String, val seen: String)

    data class State(
        val route: Route? = null,
        val searching: Boolean = false,
        /** The last look found no way to the hub. */
        val unreachable: Boolean = false,
        val identityChanged: IdentityChange? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The hub said `403 {"pair": true}`: this device isn't let in (any more). */
    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()

    fun pairingNeeded(on: Boolean) {
        _pairing.value = on
    }

    // --- tuning, and seams for the tests -------------------------------------------

    /** How long a LAN probe may take: a hub on the same Wi-Fi answers in milliseconds. */
    @VisibleForTesting @Volatile var lanTimeoutMs = 1_500L
    /** How long to listen for mDNS announcements when looking for the LAN. */
    @VisibleForTesting @Volatile var discoveryMs = 2_000L
    /** While on the tailnet: how often to look for the LAN again. */
    @VisibleForTesting @Volatile var lanRecheckMs = 3 * 60_000L
    /** While the hub is out of reach: how often to try again. */
    @VisibleForTesting @Volatile var offlineRecheckMs = 30_000L
    /** Tests: pretend the phone has no route to some LAN addresses (it left the Wi-Fi). */
    @VisibleForTesting @Volatile var lanReachable: (address: String) -> Boolean = { true }
    /** mDNS browsing; replaced in tests, where Robolectric has no NsdManager. */
    @VisibleForTesting @Volatile
    var discover: suspend (timeoutMs: Long, enough: (Announced) -> Boolean) -> List<Announced> =
        { t, enough -> Discovery.scan(app, t, enough) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @SuppressLint("StaticFieldLeak")  // the application context
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    // --- who's asking -------------------------------------------------------------

    fun current(): Route? = _state.value.route

    /** The pinned client for the LAN, OkHttp's normal one for the tailnet. */
    fun clientFor(route: Route): OkHttpClient =
        if (route.kind == Kind.LAN) Pinning.client(route.pin!!) else Hub.client

    /**
     * The route to use now, finding one first if none is known (or [force]).
     * Null when the hub can't be reached at all. Blocks: never call it on the
     * main thread.
     */
    fun resolveBlocking(force: Boolean = false): Route? = runBlocking { resolve(force) }

    suspend fun resolve(force: Boolean = false): Route? {
        if (!force) current()?.let { return it }
        return try {
            evaluation().await()
        } catch (e: CancellationException) {
            // the evaluation was called off (setup chose a route, or the hub was forgotten)
            currentCoroutineContext().ensureActive()
            current()
        }
    }

    /**
     * A request on [failed] couldn't reach the hub. Returns the route to try
     * instead (possibly the same one, if it checks out again), or null.
     */
    fun afterFailure(failed: Route): Route? {
        val now = current()
        if (now != null && now != failed) return now
        return resolveBlocking(force = true)
    }

    /** Look again soon (network changed, the user asked, a request failed). */
    fun refresh() {
        evaluation()
    }

    /** Setup found and checked [route] itself: use it straight away. */
    fun use(route: Route) {
        cancelEvaluation()
        _state.value = State(route = route)
        lastEvaluated = System.currentTimeMillis()
    }

    /** Forget the route and any warning (a different hub, or Forget this hub). */
    fun reset() {
        cancelEvaluation()
        _state.value = State()
        _pairing.value = false
    }

    @Synchronized
    private fun cancelEvaluation() {
        running?.cancel()
        running = null
        again = false
    }

    // --- evaluating ---------------------------------------------------------------

    // guarded by `this`
    private var running: Deferred<Route?>? = null
    private var again = false
    @Volatile private var lastEvaluated = 0L

    /** The running evaluation, or a new one. A request made while one runs gets one more pass after it. */
    @Synchronized
    private fun evaluation(): Deferred<Route?> {
        running?.takeIf { it.isActive }?.let {
            again = true
            return it
        }
        again = false
        val job = scope.async {
            var route: Route?
            while (true) {
                route = evaluate()
                val more = synchronized(this@Router) { again.also { again = false } }
                if (!more) break
            }
            route
        }
        running = job
        return job
    }

    // what this evaluation noticed about the hub's identity
    @Volatile private var identity: IdentityChange? = null

    private suspend fun evaluate(): Route? {
        _state.update { it.copy(searching = true) }
        identity = null
        val route = try {
            find()
        } catch (e: CancellationException) {
            _state.update { it.copy(searching = false) }
            throw e
        } catch (e: Exception) {
            null
        }
        lastEvaluated = System.currentTimeMillis()
        // a warning only matters while the LAN can't be used
        val warning = identity.takeIf { route?.kind != Kind.LAN }
        _state.update { it.copy(route = route, searching = false, unreachable = route == null, identityChanged = warning) }
        return route
    }

    private suspend fun find(): Route? = coroutineScope {
        if (!Prefs.hasHub) return@coroutineScope null
        triedLan.clear()
        val tailnetUrl = Prefs.hubUrl
        // the tailnet answer is verified TLS; start it now, it's slower
        val tail = tailnetUrl?.let { url -> async { probeTailnet(url) } }

        // an install from before 1.2 knows only the tailnet URL: learn the
        // hub's id and pin from it (verified), or failing that from the LAN
        if (Prefs.hubId == null || Prefs.hubFingerprint == null) {
            tail?.await()  // adopts the identity when it answers
            if (Prefs.hubId == null && tailnetUrl != null) adoptByDiscovery(tailnetUrl)
        }

        val id = Prefs.hubId
        val pin = Prefs.hubFingerprint
        var lan = if (id != null && pin != null) findLan(id, pin, Prefs.lanAddresses) else null
        if (lan != null) {
            tail?.cancel()
            return@coroutineScope lan
        }
        val viaTailnet = tail?.await()
        // the tailnet may have told us the hub's current LAN address: one more try
        val fresh = Prefs.lanAddresses.filter { it !in triedLan }
        if (viaTailnet != null && id != null && pin != null && fresh.isNotEmpty()) {
            lan = findLan(id, pin, fresh, discover = false)
            if (lan != null) return@coroutineScope lan
        }
        viaTailnet
    }

    // addresses probed in the current evaluation (only one evaluation runs at a time)
    private val triedLan = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The hub on the LAN: the stored addresses and an mDNS browse run side by
     * side, and the first address that proves to be the hub (by the pin)
     * wins. Every probe is bounded by [lanTimeoutMs].
     */
    private suspend fun findLan(id: String, pin: String, stored: List<String>, discover: Boolean = true): Route? = coroutineScope {
        val results = Channel<Route?>(Channel.UNLIMITED)
        val announcements = Channel<List<Announced>>(1)
        var outstanding = 0
        fun probe(address: String) {
            if (!triedLan.add(address)) return
            outstanding++
            launch { results.send(probeLan(address, id, pin)) }
        }
        stored.forEach { probe(it) }
        var browsing = discover
        if (discover) launch {
            announcements.send(runCatching { this@Router.discover(discoveryMs) { it.id == id } }.getOrDefault(emptyList()))
        }

        var result: Route? = null
        withTimeoutOrNull(discoveryMs + lanTimeoutMs * 3 + 500) {
            while (result == null && (outstanding > 0 || browsing)) {
                select<Unit> {
                    results.onReceive { r ->
                        outstanding--
                        if (r != null) result = r
                    }
                    if (browsing) announcements.onReceive { list ->
                        browsing = false
                        for (a in list.filter { it.id == id }.distinctBy { it.address }) {
                            // our id with another certificate: warn, never use it
                            if (a.fingerprint != pin) noteIdentityChange(a.address, a.fingerprint, id) else probe(a.address)
                        }
                    }
                }
            }
        }
        coroutineContext.cancelChildren()
        result
    }

    /** GET /api/hub/info on [address] with the pin; the route if it's our hub. */
    private suspend fun probeLan(address: String, id: String, pin: String): Route? {
        if (!lanReachable(address)) return null
        val base = "https://$address"
        val client = Pinning.client(pin).newBuilder()
            .connectTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(lanTimeoutMs * 2, TimeUnit.MILLISECONDS)
            .build()
        return try {
            val info = client.get("$base/api/hub/info")?.let { HubInfo.parse(it) } ?: return null
            if (info.id != id) return null  // can't happen with the pin, unless two hubs share a certificate
            remember(info, lanAddress = address)
            Route(Kind.LAN, base, pin)
        } catch (e: IOException) {
            // something else answers there with another certificate: a different
            // machine took the address, or our hub's certificate was regenerated
            Pinning.mismatch(e)?.let { noteIdentityChange(address, it.seen, id) }
            null
        }
    }

    /**
     * Something at [address] shows certificate [seen] instead of the pin.
     * If it says it's our hub, warn (never switch silently). Nothing with a
     * token is sent to it, only a GET of the public hub info.
     */
    private suspend fun noteIdentityChange(address: String, seen: String, id: String) {
        if (!Pinning.isFingerprint(seen)) return
        val client = Pinning.client(seen).newBuilder()
            .connectTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val info = runCatching { client.get("https://$address/api/hub/info") }.getOrNull()?.let { HubInfo.parse(it) }
        if (info?.id == id) identity = IdentityChange(address, seen)
    }

    /** GET /api/hub/info over the tailnet (verified TLS); the route if it's usable. */
    private suspend fun probeTailnet(url: String): Route? {
        val client = Hub.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val res = try {
            client.newCall(Request.Builder().url("$url/api/hub/info").header("User-Agent", Hub.userAgent).build()).await()
        } catch (e: IOException) {
            return null
        }
        res.use { r ->
            // 502-504: tailscale serve is up, droplet isn't
            if (r.code >= 500) return null
            val info = if (r.isSuccessful) HubInfo.parse(r.body?.string().orEmpty()) else null
            // a hub from before local-first has no /api/hub/info: it's still usable
            info ?: return Route(Kind.TAILNET, url)
            val known = Prefs.hubId
            when {
                known == null -> {
                    // upgrading from 1.1: the pin comes from verified TLS, the best source
                    // (plain http, only ever the emulator's test hub, verifies nothing)
                    Prefs.hubId = info.id
                    Prefs.hubFingerprint = info.fingerprint
                    Prefs.pinSource = when {
                        info.fingerprint == null -> null
                        url.startsWith("https://") -> Prefs.PIN_TAILNET
                        else -> Prefs.PIN_TOFU
                    }
                }
                known != info.id -> {
                    // the tailnet name now leads to a different hub; the token isn't for it
                    identity = IdentityChange(Uri.parse(url).host ?: url, info.fingerprint ?: "")
                    return null
                }
                info.fingerprint != null && Prefs.hubFingerprint != null && info.fingerprint != Prefs.hubFingerprint ->
                    // the hub's certificate changed (or the first-use pin was wrong): the LAN stays off until re-paired
                    identity = IdentityChange(info.lan.firstOrNull() ?: info.name.orEmpty(), info.fingerprint)
                Prefs.hubFingerprint == null && info.fingerprint != null -> {
                    // the hub turned on its LAN listener since
                    Prefs.hubFingerprint = info.fingerprint
                    Prefs.pinSource = if (url.startsWith("https://")) Prefs.PIN_TAILNET else Prefs.PIN_TOFU
                }
                Prefs.pinSource == Prefs.PIN_TOFU && info.fingerprint == Prefs.hubFingerprint && url.startsWith("https://") ->
                    // the pin trusted on first use is now confirmed over verified TLS
                    Prefs.pinSource = Prefs.PIN_TAILNET
            }
            remember(info, lanAddress = null)
            return Route(Kind.TAILNET, url)
        }
    }

    /**
     * Upgrading with no tailnet reachable: trust on first use. A hub on the
     * Wi-Fi that announces our tailnet URL, and whose certificate is the one
     * it announces, becomes the pinned hub; the tailnet check confirms it
     * later (a mismatch then shows "the hub's identity changed").
     */
    private suspend fun adoptByDiscovery(tailnetUrl: String) {
        val seen = runCatching { discover(discoveryMs) { it.tailnet == tailnetUrl } }.getOrDefault(emptyList())
        val matches = seen.filter { it.tailnet == tailnetUrl }.distinctBy { it.id }
        val a = matches.singleOrNull() ?: return  // none, or ambiguous: don't guess
        val client = Pinning.client(a.fingerprint).newBuilder()
            .connectTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(lanTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val info = runCatching { client.get("${a.base}/api/hub/info") }.getOrNull()?.let { HubInfo.parse(it) } ?: return
        if (info.id != a.id || info.fingerprint != a.fingerprint) return
        Prefs.hubId = info.id
        Prefs.hubFingerprint = info.fingerprint
        Prefs.pinSource = Prefs.PIN_TOFU
        remember(info, lanAddress = a.address)
    }

    /** Keeps what the hub said about itself: name, LAN addresses (the one that worked first), tailnet URL. */
    private fun remember(info: HubInfo, lanAddress: String?) {
        info.name?.let { if (it != Prefs.hubName) Prefs.hubName = it }
        // the address that just worked, then where the hub says it is, then older hints
        val merged = (listOfNotNull(lanAddress) + info.lan + Prefs.lanAddresses).distinct()
        if (merged != Prefs.lanAddresses) Prefs.lanAddresses = merged
        if (Prefs.hubUrl == null && info.tailnet?.startsWith("https://") == true) Prefs.hubUrl = info.tailnet
    }

    // --- watching the network -------------------------------------------------------

    private val holders = mutableSetOf<String>()
    private var callbacks: List<ConnectivityManager.NetworkCallback> = emptyList()
    private var ticker: Job? = null
    private var debounce: Job? = null

    /** Keep the route fresh until [release] with the same tag (the app on screen, Stay connected, the live connection). */
    @Synchronized
    fun hold(tag: String) {
        if (!holders.add(tag) || holders.size > 1) return
        watchNetwork(true)
        ticker = scope.launch {
            while (isActive) {
                val s = _state.value
                val wait = when (s.route?.kind) {
                    Kind.LAN -> lanRecheckMs  // just a check that it's still there
                    Kind.TAILNET -> lanRecheckMs
                    null -> offlineRecheckMs
                }
                delay(wait.coerceAtLeast(100))
                // the LAN is best: look for it again while on the tailnet or offline
                if (_state.value.route?.kind != Kind.LAN && Prefs.hasHub) refresh()
            }
        }
        if (current() == null || System.currentTimeMillis() - lastEvaluated > FRESH_MS) refresh()
    }

    @Synchronized
    fun release(tag: String) {
        if (!holders.remove(tag) || holders.isNotEmpty()) return
        watchNetwork(false)
        ticker?.cancel()
        ticker = null
    }

    /** A burst of network events (Wi-Fi joins, VPN reconnects) settles before one look. */
    private fun networkChanged() {
        synchronized(this) {
            debounce?.cancel()
            debounce = scope.launch {
                delay(NETWORK_SETTLE_MS)
                refresh()
            }
        }
    }

    private fun watchNetwork(on: Boolean) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks = emptyList()
        if (!on) return
        // the default network: Wi-Fi <-> mobile, and Tailscale (a VPN) going up or down
        val default = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                if (network == last) return
                last = network
                networkChanged()
            }

            override fun onLost(network: Network) {
                if (network == last) last = null
                networkChanged()
            }
        }
        // Wi-Fi itself, which a VPN on top hides from the default-network callback
        val wifi = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
        }
        val list = mutableListOf<ConnectivityManager.NetworkCallback>()
        runCatching { cm.registerDefaultNetworkCallback(default) }.onSuccess { list += default }
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
        }.onSuccess { list += wifi }
        callbacks = list
    }

    // --- for screens ---------------------------------------------------------------

    /** The hub's name for people: "t15", or its address before it has told us. */
    fun hubLabel(): String = Prefs.hubName ?: Prefs.hubUrl?.let { Uri.parse(it).host?.substringBefore('.') } ?: "the hub"

    /** "On Wi-Fi · t15 · 192.168.100.20", "Via Tailscale", "Looking for t15…". */
    fun describe(context: Context, s: State = _state.value): String {
        val r = s.route
        return when {
            r?.kind == Kind.LAN -> context.getString(R.string.route_lan, hubLabel(), r.host)
            r != null && r.host.endsWith(".ts.net") -> context.getString(R.string.route_tailnet)
            r != null -> context.getString(R.string.route_direct, r.host)
            s.searching -> context.getString(R.string.route_searching, hubLabel())
            !Prefs.hasHub -> context.getString(R.string.live_no_hub)
            else -> context.getString(R.string.route_none, hubLabel())
        }
    }

    /** Short form for the notification: "on Wi-Fi", "via Tailscale". */
    fun shortLabel(context: Context, r: Route? = current()): String? = when {
        r == null -> null
        r.kind == Kind.LAN -> context.getString(R.string.route_short_lan)
        r.host.endsWith(".ts.net") -> context.getString(R.string.route_short_tailnet)
        else -> context.getString(R.string.route_short_direct, r.host)
    }

    private const val NETWORK_SETTLE_MS = 750L
    private const val FRESH_MS = 30_000L
}

/** GET [url]; the body on 2xx, null otherwise. Cancelling the coroutine cancels the call. */
internal suspend fun OkHttpClient.get(url: String): String? =
    newCall(Request.Builder().url(url).header("User-Agent", Hub.userAgent).build()).await().use { r ->
        if (r.isSuccessful) r.body?.string() else null
    }

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
