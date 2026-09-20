package dev.droplet.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** A hub announcing itself on the LAN as `_droplet._tcp` (docs/local-first.md §1). */
data class Announced(
    val id: String,
    val fingerprint: String,
    val name: String,
    val host: String,
    val port: Int,
    val httpPort: Int?,
    val tailnet: String?,
) {
    /** "192.168.100.20:8443", or "[fd00::1]:8443". */
    val address: String get() = hostPort(host, port)
    val base: String get() = "https://$address"
}

fun hostPort(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

/**
 * Finds droplet hubs on the Wi-Fi with Android's NsdManager.
 *
 * NsdManager's quirks, handled here: a listener can only run one discovery;
 * before Android 14 only one resolve may run at a time (a second fails with
 * FAILURE_ALREADY_ACTIVE), so resolves are queued; Android 14 replaces
 * resolving with a service-info callback that also reports address changes.
 * Nothing here needs a permission beyond INTERNET, and NsdManager handles
 * multicast itself (no MulticastLock).
 */
object Discovery {
    const val SERVICE_TYPE = "_droplet._tcp"

    // --- the TXT records -----------------------------------------------------------

    /**
     * Reads one announcement, or null if it isn't a usable droplet hub.
     * [host] is the resolved address; [attrs] the raw TXT records.
     */
    fun parse(host: String?, port: Int, attrs: Map<String, ByteArray?>): Announced? {
        fun txt(key: String): String? = attrs.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)?.trim()
        val id = txt("id")?.lowercase() ?: return null
        val fp = txt("fp")?.lowercase() ?: return null
        if (!isHubId(id) || !Pinning.isFingerprint(fp)) return null
        if (host.isNullOrBlank() || port !in 1..65535) return null
        val ts = txt("ts")?.takeIf { it.isNotEmpty() }?.let { Hub.normalize(it) }?.takeIf { it.startsWith("https://") }
        return Announced(
            id = id,
            fingerprint = fp,
            name = txt("name")?.takeIf { it.isNotEmpty() }?.take(64) ?: "droplet",
            host = host,
            port = port,
            httpPort = txt("http")?.toIntOrNull()?.takeIf { it in 1..65535 },
            tailnet = ts,
        )
    }

    fun isHubId(s: String?): Boolean = s != null && s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The address to connect to: IPv4 first (what the hub announces), then a
     * global IPv6 address. Link-local IPv6 needs an interface scope that URLs
     * can't carry, so it's skipped.
     */
    fun bestAddress(addresses: List<InetAddress>): String? =
        addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            ?: addresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress?.substringBefore('%')

    /**
     * One entry per hub (the same hub shows up once per network interface),
     * the paired hub first, then by name.
     */
    fun arrange(found: List<Announced>, pairedId: String?): List<Announced> =
        found.distinctBy { it.id }.sortedWith(compareBy<Announced>({ it.id != pairedId }, { it.name.lowercase() }, { it.address }))

    // --- browsing ------------------------------------------------------------------

    /** A running browse; [stop] ends it. */
    fun interface Handle {
        fun stop()
    }

    /**
     * Browses until [Handle.stop], calling [onChange] (on the main thread)
     * with every hub seen so far. [onError] gets NsdManager's error code if
     * discovery can't start.
     */
    fun browse(context: Context, onChange: (List<Announced>) -> Unit, onError: (Int) -> Unit = {}): Handle {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: return Handle { }.also { onError(-1) }
        return Browser(nsd, SERVICE_TYPE, { _, host, port, attrs -> parse(host, port, attrs) }, onChange, onError).also { it.start() }
    }

    /**
     * Browses [type] until [Handle.stop], with [parse] turning each resolved
     * service (its best address, port and TXT records) into a [T]. The mesh
     * uses it for `_droplet-peer._tcp`.
     */
    fun <T> browseType(context: Context, type: String, parse: (String?, Int, Map<String, ByteArray?>) -> T?,
                       onChange: (List<T>) -> Unit, onError: (Int) -> Unit = {}): Handle {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: return Handle { }.also { onError(-1) }
        return Browser(nsd, type, { _, host, port, attrs -> parse(host, port, attrs) }, onChange, onError).also { it.start() }
    }

    /** [browseType], with the service's instance name too (a TV announces itself by its name). */
    fun <T> browseNamed(context: Context, type: String, parse: (String, String?, Int, Map<String, ByteArray?>) -> T?,
                        onChange: (List<T>) -> Unit, onError: (Int) -> Unit = {}): Handle {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
            ?: return Handle { }.also { onError(-1) }
        return Browser(nsd, type, parse, onChange, onError).also { it.start() }
    }

    /**
     * Browses for up to [timeoutMs] and returns what it found, or stops as
     * soon as [enough] says so.
     */
    suspend fun scan(context: Context, timeoutMs: Long, enough: (Announced) -> Boolean = { false }): List<Announced> {
        var handle: Handle? = null
        var latest: List<Announced> = emptyList()
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    handle = browse(context, onChange = { list ->
                        latest = list
                        if (list.any(enough) && cont.isActive) cont.resume(Unit)
                    }, onError = { if (cont.isActive) cont.resume(Unit) })
                }
            }
        } finally {
            handle?.stop()
        }
        return latest
    }

    private class Browser<T>(
        private val nsd: NsdManager,
        private val type: String,
        private val parser: (String, String?, Int, Map<String, ByteArray?>) -> T?,
        private val onChange: (List<T>) -> Unit,
        private val onError: (Int) -> Unit,
    ) : NsdManager.DiscoveryListener, Handle {
        private val main = Handler(Looper.getMainLooper())
        private val mainExecutor = Executor { main.post(it) }
        private val lock = Any()
        @Volatile private var stopped = false
        @Volatile private var started = false
        // keyed by service name, which is unique per hub and survives IP changes
        private val found = LinkedHashMap<String, T>()
        // before Android 14: one resolve at a time
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        // Android 14+: one callback per service
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        fun start() {
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (e: Exception) {
                // e.g. "listener already in use", or no NSD service on this build
                main.post { onError(-1) }
            }
        }

        override fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                queue.clear()
                if (Build.VERSION.SDK_INT >= 34) {
                    callbacks.values.forEach { cb -> runCatching { nsd.unregisterServiceInfoCallback(cb) } }
                }
                callbacks.clear()
            }
            // stopping a discovery that never started throws
            if (started) runCatching { nsd.stopServiceDiscovery(this) }
        }

        private fun publish() {
            val list = synchronized(lock) { found.values.toList() }
            main.post { if (!stopped) onChange(list) }
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            started = true
            // stopped before it had started: stop it now, or it would run on
            if (stopped) runCatching { nsd.stopServiceDiscovery(this) }
        }

        override fun onDiscoveryStopped(serviceType: String?) = Unit

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            main.post { onError(errorCode) }
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(lock) {
                if (stopped) return
                if (Build.VERSION.SDK_INT >= 34) watch(info) else {
                    queue.addLast(info)
                    if (!resolving) resolveNext()
                }
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(lock) {
                found.remove(info.serviceName)
                if (Build.VERSION.SDK_INT >= 34) {
                    callbacks.remove(info.serviceName)?.let { cb -> runCatching { nsd.unregisterServiceInfoCallback(cb) } }
                }
            }
            publish()
        }

        private fun record(info: NsdServiceInfo, host: String?) {
            val hub = parser(info.serviceName.orEmpty(), host, info.port, info.attributes ?: emptyMap()) ?: return
            synchronized(lock) {
                if (stopped) return
                found[info.serviceName] = hub
            }
            publish()
        }

        @RequiresApi(34)
        private fun watch(info: NsdServiceInfo) {
            val key = info.serviceName
            if (key in callbacks) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(lock) { callbacks.remove(key) }
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    record(updated, bestAddress(updated.hostAddresses))
                }

                override fun onServiceLost() {
                    synchronized(lock) { found.remove(key) }
                    publish()
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[key] = cb
            try {
                nsd.registerServiceInfoCallback(info, mainExecutor, cb)
            } catch (e: Exception) {
                callbacks.remove(key)
            }
        }

        // --- before Android 14 -------------------------------------------------------

        private fun resolveNext() {
            val next = queue.removeFirstOrNull()
            if (next == null || stopped) {
                resolving = false
                return
            }
            resolving = true
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(next, Resolver(next, retries = 3))
            } catch (e: Exception) {
                resolveNext()
            }
        }

        private inner class Resolver(private val info: NsdServiceInfo, private val retries: Int) : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                record(resolved, resolved.host?.let { bestAddress(listOf(it)) })
                synchronized(lock) { resolveNext() }
            }

            override fun onResolveFailed(failed: NsdServiceInfo?, errorCode: Int) {
                // another resolve (maybe another app's, through the same daemon) is running: try again shortly
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retries > 0) {
                    main.postDelayed({
                        synchronized(lock) {
                            if (stopped) return@synchronized
                            try {
                                @Suppress("DEPRECATION")
                                nsd.resolveService(info, Resolver(info, retries - 1))
                            } catch (e: Exception) {
                                resolveNext()
                            }
                        }
                    }, 200)
                    return
                }
                synchronized(lock) { resolveNext() }
            }
        }
    }
}
