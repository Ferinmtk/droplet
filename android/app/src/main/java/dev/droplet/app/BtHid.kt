package dev.droplet.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The phone as a Bluetooth keyboard and mouse (issue #36), with Android's
 * BluetoothHidDevice (Android 9+). Nothing is installed on the computer or
 * TV, and no Wi-Fi or hub is involved.
 *
 * The phone is a HID device only while something holds it: the Bluetooth
 * remote screen, or the presentation remote aimed at a Bluetooth host. When
 * the last holder lets go (after a short grace, so turning the phone doesn't
 * drop the connection) it disconnects and unregisters. Android itself also
 * drops the registration when droplet leaves the screen.
 *
 * Everything here runs on the main thread; reports go out on one background
 * thread, in order.
 */
object BtHid {
    private var ctl: HidController? = null

    /** The controller; the first use builds the real one. Tests put in their own. */
    var controller: HidController
        get() = ctl ?: HidController(AndroidHidBackend(appContext())).also { ctl = it }
        set(v) {
            ctl = v
        }

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun appContext(): Context = app ?: error("BtHid.init wasn't called")

    val state: StateFlow<HidController.State> get() = controller.state

    /**
     * The runtime permissions this needs, Android 12+'s "Nearby devices":
     * CONNECT to register, list paired devices and connect to them, ADVERTISE
     * to make the phone visible for pairing. Not SCAN: the phone never
     * searches, the computer or TV does. Before Android 12 the install-time
     * BLUETOOTH and BLUETOOTH_ADMIN cover it all.
     */
    val PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else emptyArray()

    /** True when the permissions are granted (always, before Android 12). */
    fun permitted(context: Context): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** True when this Android version has the HID device API at all. */
    val apiAvailable: Boolean get() = Build.VERSION.SDK_INT >= 28

    fun hold(tag: String) = controller.hold(tag)
    fun release(tag: String) = controller.release(tag)
}

/** A short word for the phase, for a screen's header. */
fun HidController.Phase.label(): Int = when (this) {
    HidController.Phase.IDLE -> R.string.bt_short_idle
    HidController.Phase.STARTING -> R.string.bt_short_starting
    HidController.Phase.READY -> R.string.bt_short_ready
    HidController.Phase.CONNECTING -> R.string.bt_short_connecting
    HidController.Phase.CONNECTED -> R.string.bt_short_connected
    HidController.Phase.BLUETOOTH_OFF -> R.string.bt_short_off
    HidController.Phase.NO_PERMISSION -> R.string.bt_short_permission
    HidController.Phase.OLD_ANDROID, HidController.Phase.NO_ADAPTER,
    HidController.Phase.UNSUPPORTED, HidController.Phase.REFUSED -> R.string.bt_short_unavailable
}

/** A computer, TV or other host, by its Bluetooth address. */
data class HidHost(val address: String, val name: String)

/** What the platform does; the real one is [AndroidHidBackend], tests bring a fake. */
interface HidBackend {
    val apiAvailable: Boolean
    val hasAdapter: Boolean
    fun permitted(): Boolean
    fun enabled(): Boolean

    /** Paired devices, for the "connect to" list. */
    fun bonded(): List<HidHost>

    /**
     * Asks for the HID device service. False if the phone refuses outright;
     * otherwise [onProxy] comes with it (or never, on firmware without it),
     * and [onLost] if the service goes away later.
     */
    fun open(onProxy: (HidProxy) -> Unit, onLost: () -> Unit): Boolean

    fun close(proxy: HidProxy)

    /** Follows the adapter being switched on and off; null stops following. */
    fun watchAdapter(onChange: ((on: Boolean) -> Unit)?)
}

interface HidProxy {
    /** Registers the app with the combined descriptor; false if the stack refuses. */
    fun register(events: HidEvents): Boolean
    fun unregister()
    fun connect(host: HidHost): Boolean
    fun disconnect(host: HidHost): Boolean
    fun send(host: HidHost, id: Int, report: ByteArray): Boolean
}

interface HidEvents {
    fun onAppStatus(registered: Boolean)
    fun onConnection(host: HidHost, state: Int)
}

/**
 * The state machine behind [BtHid]: registration, connecting to a host, and
 * sending reports.
 */
class HidController(
    private val backend: HidBackend,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "droplet-hid").apply { isDaemon = true } },
) {
    enum class Phase {
        /** Nobody needs it: not registered. */
        IDLE,
        /** Android 8: no HID device API. */
        OLD_ANDROID,
        NO_ADAPTER,
        NO_PERMISSION,
        BLUETOOTH_OFF,
        /** Getting the service and registering. */
        STARTING,
        /** The phone's firmware doesn't offer the HID device service. */
        UNSUPPORTED,
        /** The service is there but wouldn't register us (firmware, or another app has it). */
        REFUSED,
        /** Registered: a host can connect, or be connected to. */
        READY,
        CONNECTING,
        CONNECTED,
    }

    enum class Problem { CONNECT_FAILED, NO_ANSWER }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** The host connected, or being connected to. */
        val host: HidHost? = null,
        /** What went wrong with the last attempt to connect, until the next one. */
        val problem: Problem? = null,
    ) {
        val connected: Boolean get() = phase == Phase.CONNECTED && host != null
        val registered: Boolean get() = phase == Phase.READY || phase == Phase.CONNECTING || phase == Phase.CONNECTED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val holders = mutableSetOf<String>()
    private var proxy: HidProxy? = null
    private var opening = false
    private var registering = false
    private var registered = false
    /** The host to connect to once registered (or once the current one has gone). */
    private var wanted: HidHost? = null

    private val stopLater = Runnable { if (holders.isEmpty()) stop() }
    private val proxyTimeout = Runnable {
        if (opening) {
            opening = false
            Log.w(TAG, "HID device service never connected")
            Prefs.btSupport = Prefs.BT_UNSUPPORTED
            publish(Phase.UNSUPPORTED)
        }
    }
    private val registerTimeout = Runnable {
        if (registering) {
            registering = false
            Log.w(TAG, "registerApp never answered")
            if (Prefs.btSupport != Prefs.BT_SUPPORTED) Prefs.btSupport = Prefs.BT_REFUSED
            dropProxy()
            publish(Phase.REFUSED)
        }
    }
    private val connectTimeout = Runnable {
        val s = _state.value
        if (s.phase == Phase.CONNECTING) {
            s.host?.let { h -> proxyCall { it.disconnect(h) } }
            wanted = null
            _state.value = State(Phase.READY, null, Problem.NO_ANSWER)
        }
    }

    /** The host the last connect was for, so the screen can name it when something goes wrong. */
    var lastTried: HidHost? = null
        private set

    val keyboard = HidKeyboard(::send)
    val mouse = HidMouse(::send)

    // --- holding --------------------------------------------------------------------

    fun hold(tag: String) {
        main.removeCallbacks(stopLater)
        val first = holders.isEmpty()
        holders.add(tag)
        if (first) backend.watchAdapter { on -> adapterChanged(on) }
        start()
    }

    fun release(tag: String) {
        if (!holders.remove(tag) || holders.isNotEmpty()) return
        main.removeCallbacks(stopLater)
        main.postDelayed(stopLater, GRACE_MS)
    }

    /** Tries again after a problem, or after a permission was granted or Bluetooth switched on. */
    fun retry() {
        if (holders.isEmpty()) return
        val p = _state.value.phase
        if (p == Phase.UNSUPPORTED || p == Phase.REFUSED) {
            dropProxy()
            publish(Phase.IDLE)
        }
        start()
    }

    fun bonded(): List<HidHost> = if (backend.hasAdapter && backend.permitted() && backend.enabled()) {
        runCatching { backend.bonded() }.getOrDefault(emptyList())
    } else emptyList()

    // --- connecting -------------------------------------------------------------------

    /** Connects to [host] (registering first if need be), dropping any other host. */
    fun connect(host: HidHost) {
        Prefs.btLastHost = host.address
        Prefs.btLastHostName = host.name
        lastTried = host
        val s = _state.value
        if ((s.phase == Phase.CONNECTED || s.phase == Phase.CONNECTING) && s.host?.address == host.address) return
        wanted = host
        if (!registered) {
            start()
            return
        }
        val current = s.host
        if (current != null && s.phase == Phase.CONNECTED) {
            // one host at a time: the other goes first, and this one follows (see onConnection)
            proxyCall { it.disconnect(current) }
            return
        }
        dial(host)
    }

    fun disconnect() {
        wanted = null
        main.removeCallbacks(connectTimeout)
        val h = _state.value.host ?: return
        proxyCall { it.disconnect(h) }
    }

    private fun dial(host: HidHost) {
        wanted = null
        val ok = proxyCall { it.connect(host) } ?: false
        if (!ok) {
            _state.value = State(Phase.READY, null, Problem.CONNECT_FAILED)
            return
        }
        _state.value = State(Phase.CONNECTING, host)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    // --- sending ----------------------------------------------------------------------

    /** Sends one input report to the connected host; false if none is connected. */
    fun send(id: Int, report: ByteArray): Boolean {
        val s = _state.value
        val host = s.host
        val p = proxy
        if (!s.connected || host == null || p == null) return false
        io.execute {
            try {
                if (!p.send(host, id, report)) Log.w(TAG, "report $id not sent")
            } catch (e: Exception) {
                Log.w(TAG, "report $id failed: $e")
            }
        }
        return true
    }

    // --- registration -------------------------------------------------------------------

    private fun start() {
        if (holders.isEmpty()) return
        if (registered || registering || opening) return
        when {
            !backend.apiAvailable -> return publish(Phase.OLD_ANDROID)
            !backend.hasAdapter -> return publish(Phase.NO_ADAPTER)
            !backend.permitted() -> return publish(Phase.NO_PERMISSION)
            !backend.enabled() -> return publish(Phase.BLUETOOTH_OFF)
        }
        val p = proxy
        if (p != null) return register(p)
        publish(Phase.STARTING)
        opening = true
        val asked = try {
            backend.open(onProxy = { got -> main.post { gotProxy(got) } }, onLost = { main.post { lostProxy() } })
        } catch (e: SecurityException) {
            opening = false
            return publish(Phase.NO_PERMISSION)
        } catch (e: Exception) {
            // firmware that throws instead of saying no
            Log.w(TAG, "getProfileProxy(HID_DEVICE) failed: $e")
            false
        }
        if (!asked) {
            opening = false
            Log.w(TAG, "getProfileProxy(HID_DEVICE) refused")
            Prefs.btSupport = Prefs.BT_UNSUPPORTED
            return publish(Phase.UNSUPPORTED)
        }
        main.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
    }

    private fun gotProxy(p: HidProxy) {
        main.removeCallbacks(proxyTimeout)
        val expected = opening
        opening = false
        if (!expected || holders.isEmpty()) {
            // too late (timed out or no longer wanted): hand it back
            runCatching { backend.close(p) }
            if (holders.isEmpty()) publish(Phase.IDLE)
            return
        }
        proxy = p
        register(p)
    }

    private fun register(p: HidProxy) {
        publish(Phase.STARTING)
        registering = true
        val ok = try {
            p.register(events)
        } catch (e: SecurityException) {
            registering = false
            return publish(Phase.NO_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "registerApp failed: $e")
            false
        }
        if (!ok) {
            registering = false
            Log.w(TAG, "registerApp refused")
            if (Prefs.btSupport != Prefs.BT_SUPPORTED) Prefs.btSupport = Prefs.BT_REFUSED
            dropProxy()
            return publish(Phase.REFUSED)
        }
        main.postDelayed(registerTimeout, PROXY_TIMEOUT_MS)
    }

    private val events = object : HidEvents {
        override fun onAppStatus(registered: Boolean) {
            main.post { appStatus(registered) }
        }

        override fun onConnection(host: HidHost, state: Int) {
            main.post { connection(host, state) }
        }
    }

    private fun appStatus(now: Boolean) {
        main.removeCallbacks(registerTimeout)
        registering = false
        registered = now
        if (now) {
            Prefs.btSupport = Prefs.BT_SUPPORTED
            if (holders.isEmpty()) return stop()
            publish(Phase.READY)
            wanted?.let { dial(it) }
        } else {
            // unregistered: by us, or by Android when droplet left the screen
            main.removeCallbacks(connectTimeout)
            mouse.reset()
            if (_state.value.phase !in PROBLEMS) publish(if (holders.isEmpty()) Phase.IDLE else Phase.STARTING)
            if (holders.isNotEmpty()) start()
        }
    }

    private fun connection(host: HidHost, st: Int) {
        if (!registered) return
        val s = _state.value
        when (st) {
            BluetoothProfile.STATE_CONNECTED -> {
                main.removeCallbacks(connectTimeout)
                mouse.reset()
                Prefs.btLastHost = host.address
                Prefs.btLastHostName = host.name
                _state.value = State(Phase.CONNECTED, host)
            }
            BluetoothProfile.STATE_CONNECTING -> {
                if (s.phase != Phase.CONNECTED) _state.value = State(Phase.CONNECTING, host)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (s.host != null && s.host.address != host.address) return
                main.removeCallbacks(connectTimeout)
                mouse.reset()
                val failed = s.phase == Phase.CONNECTING && wanted == null
                _state.value = State(Phase.READY, null, if (failed) Problem.CONNECT_FAILED else null)
                wanted?.let { if (it.address != host.address) dial(it) else wanted = null }
            }
        }
    }

    private fun adapterChanged(on: Boolean) {
        if (holders.isEmpty()) return
        if (on) {
            if (_state.value.phase == Phase.BLUETOOTH_OFF) publish(Phase.IDLE)
            start()
        } else {
            // the stack takes the service down with it
            main.removeCallbacks(connectTimeout)
            main.removeCallbacks(proxyTimeout)
            main.removeCallbacks(registerTimeout)
            opening = false
            registering = false
            registered = false
            proxy?.let { runCatching { backend.close(it) } }
            proxy = null
            mouse.reset()
            publish(Phase.BLUETOOTH_OFF)
        }
    }

    private fun lostProxy() {
        proxy = null
        registered = false
        registering = false
        main.removeCallbacks(registerTimeout)
        main.removeCallbacks(connectTimeout)
        mouse.reset()
        if (_state.value.phase in PROBLEMS) return
        val on = backend.enabled()
        publish(if (on) Phase.IDLE else Phase.BLUETOOTH_OFF)
        // the Bluetooth stack restarted: once it's settled, register again (an adapter
        // switched off comes back through adapterChanged instead)
        if (on && holders.isNotEmpty()) {
            main.removeCallbacks(restart)
            main.postDelayed(restart, RESTART_MS)
        }
    }

    private val restart = Runnable { start() }

    /** Lets go of everything: disconnects, unregisters, closes the service. */
    private fun stop() {
        main.removeCallbacks(stopLater)
        main.removeCallbacks(restart)
        main.removeCallbacks(proxyTimeout)
        main.removeCallbacks(registerTimeout)
        main.removeCallbacks(connectTimeout)
        backend.watchAdapter(null)
        wanted = null
        opening = false
        registering = false
        val s = _state.value
        val p = proxy
        if (p != null) {
            if (s.connected && s.host != null) {
                // a button held for a drag is let go before the host loses us
                if (mouse.buttons != 0) proxyCall { it.send(s.host, HidReports.ID_MOUSE, HidReports.mouse(0)) }
                proxyCall { it.disconnect(s.host) }
            }
            if (registered) proxyCall { it.unregister() }
        }
        registered = false
        dropProxy()
        publish(Phase.IDLE)
        mouse.reset()
    }

    private fun dropProxy() {
        val p = proxy ?: return
        proxy = null
        registered = false
        runCatching { backend.close(p) }
    }

    private fun publish(phase: Phase) {
        _state.value = State(phase, if (phase == Phase.CONNECTED || phase == Phase.CONNECTING) _state.value.host else null)
    }

    private fun <T> proxyCall(f: (HidProxy) -> T): T? {
        val p = proxy ?: return null
        return try {
            f(p)
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission withdrawn: $e")
            null
        } catch (e: Exception) {
            Log.w(TAG, "HID call failed: $e")
            null
        }
    }

    companion object {
        private const val TAG = "droplet-hid"
        /** How long the registration outlives its last holder (a screen turning, one screen handing to another). */
        const val GRACE_MS = 2_500L
        /** How long the HID service gets to appear, and to answer registerApp. */
        const val PROXY_TIMEOUT_MS = 4_000L
        /** How long after the HID service went away to ask for it again. */
        const val RESTART_MS = 1_000L
        /** How long a host gets to accept a connection. */
        const val CONNECT_TIMEOUT_MS = 15_000L
        private val PROBLEMS = setOf(Phase.UNSUPPORTED, Phase.REFUSED, Phase.NO_PERMISSION, Phase.OLD_ANDROID, Phase.NO_ADAPTER)
    }
}

/**
 * The real platform: BluetoothAdapter and BluetoothHidDevice. Every call is
 * behind HidController's checks for the Bluetooth permission, and a
 * SecurityException (the permission withdrawn meanwhile) is caught there.
 */
@SuppressLint("MissingPermission")
class AndroidHidBackend(private val context: Context) : HidBackend {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var receiver: BroadcastReceiver? = null

    override val apiAvailable: Boolean get() = Build.VERSION.SDK_INT >= 28
    override val hasAdapter: Boolean get() = adapter != null
    override fun permitted(): Boolean = BtHid.permitted(context)
    override fun enabled(): Boolean = adapter?.isEnabled == true

    override fun bonded(): List<HidHost> =
        adapter?.bondedDevices.orEmpty().map { it.toHost() }.sortedBy { it.name.lowercase() }

    override fun open(onProxy: (HidProxy) -> Unit, onLost: () -> Unit): Boolean {
        val a = adapter ?: return false
        if (Build.VERSION.SDK_INT < 28) return false
        return a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE && p is BluetoothHidDevice) onProxy(AndroidHidProxy(a, p, context))
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) onLost()
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    override fun close(proxy: HidProxy) {
        if (Build.VERSION.SDK_INT < 28 || proxy !is AndroidHidProxy) return
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy.hid)
    }

    override fun watchAdapter(onChange: ((on: Boolean) -> Unit)?) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (onChange == null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> onChange(true)
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onChange(false)
                }
            }
        }
        // a system broadcast: no other app can send it
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }
}

@SuppressLint("MissingPermission")
internal fun BluetoothDevice.toHost(): HidHost = HidHost(address, runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address)

@RequiresApi(28)
@SuppressLint("MissingPermission")
private class AndroidHidProxy(
    private val adapter: BluetoothAdapter,
    val hid: BluetoothHidDevice,
    private val context: Context,
) : HidProxy {

    override fun register(events: HidEvents): Boolean {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "droplet", "droplet mouse and keyboard", "droplet",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR,
        )
        // best effort, 11.25 ms latency: the interrupt channel's usual setting for keyboards and mice
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11_250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
        return hid.registerApp(sdp, null, qos, ContextCompat.getMainExecutor(context), Callback(events))
    }

    override fun unregister() {
        hid.unregisterApp()
    }

    override fun connect(host: HidHost) = hid.connect(adapter.getRemoteDevice(host.address))
    override fun disconnect(host: HidHost) = hid.disconnect(adapter.getRemoteDevice(host.address))
    override fun send(host: HidHost, id: Int, report: ByteArray) =
        hid.sendReport(adapter.getRemoteDevice(host.address), id, report)

    private inner class Callback(private val events: HidEvents) : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            events.onAppStatus(registered)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            events.onConnection(device.toHost(), state)
        }

        // the host asks for a report's current value: nothing is held between reports
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = HidReports.inputSize(id.toInt())
            if (type != BluetoothHidDevice.REPORT_TYPE_INPUT || size < 0) {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                return
            }
            val n = if (bufferSize in 1 until size) bufferSize else size
            hid.replyReport(device, type, id, ByteArray(n))
        }

        // keyboard LEDs and the like: accepted, and not needed
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }
}
