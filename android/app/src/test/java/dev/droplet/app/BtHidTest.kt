package dev.droplet.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import dev.droplet.app.HidController.Phase

/**
 * The HID state machine (HidController) against a fake Bluetooth stack:
 * permission and adapter gating, firmware without the HID device service,
 * a refused registration, connecting, switching hosts, sending, and letting
 * go. Then the real backend (AndroidHidBackend) under Robolectric's
 * Bluetooth shadows for what those can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BtHidTest {

    class FakeProxy : HidProxy {
        var events: HidEvents? = null
        var registerResult = true
        var connectResult = true
        var registers = 0
        var unregisters = 0
        val connects = mutableListOf<String>()
        val disconnects = mutableListOf<String>()
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        /** Everything, in order, to check what comes before what. */
        val log = mutableListOf<String>()

        override fun register(events: HidEvents): Boolean {
            registers++
            this.events = events
            log += "register"
            return registerResult
        }

        override fun unregister() {
            unregisters++
            log += "unregister"
        }

        override fun connect(host: HidHost): Boolean {
            connects += host.address
            log += "connect ${host.address}"
            return connectResult
        }

        override fun disconnect(host: HidHost): Boolean {
            disconnects += host.address
            log += "disconnect ${host.address}"
            return true
        }

        override fun send(host: HidHost, id: Int, report: ByteArray): Boolean {
            sent += id to report
            log += "send $id"
            return true
        }
    }

    class FakeBackend : HidBackend {
        override var apiAvailable = true
        override var hasAdapter = true
        var permit = true
        var on = true
        var openResult = true
        var answer = true
        var opens = 0
        val closed = mutableListOf<HidProxy>()
        val proxy = FakeProxy()
        var watcher: ((Boolean) -> Unit)? = null
        var lost: (() -> Unit)? = null
        var pending: ((HidProxy) -> Unit)? = null
        var hosts = listOf<HidHost>()

        override fun permitted() = permit
        override fun enabled() = on
        override fun bonded() = hosts

        override fun open(onProxy: (HidProxy) -> Unit, onLost: () -> Unit): Boolean {
            opens++
            lost = onLost
            if (!openResult) return false
            if (answer) onProxy(proxy) else pending = onProxy
            return true
        }

        override fun close(proxy: HidProxy) {
            closed += proxy
        }

        override fun watchAdapter(onChange: ((on: Boolean) -> Unit)?) {
            watcher = onChange
        }
    }

    private val tv = HidHost("AA:BB:CC:DD:EE:01", "TCL 43")
    private val pc = HidHost("AA:BB:CC:DD:EE:02", "maryanne")

    private lateinit var fake: FakeBackend
    private lateinit var hid: HidController

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val phase get() = hid.state.value.phase

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.btSupport = null
        Prefs.btLastHost = null
        Prefs.btLastHostName = null
        fake = FakeBackend()
        hid = HidController(fake, Handler(Looper.getMainLooper())) { it.run() }
    }

    /** Held, registered, and Android has said so. */
    private fun ready() {
        hid.hold("t")
        idle()
        fake.proxy.events!!.onAppStatus(true)
        idle()
        assertEquals(Phase.READY, phase)
    }

    private fun connected(host: HidHost = tv) {
        ready()
        hid.connect(host)
        fake.proxy.events!!.onConnection(host, BluetoothProfile.STATE_CONNECTED)
        idle()
        assertTrue(hid.state.value.connected)
    }

    // --- getting registered -----------------------------------------------------------------

    @Test
    fun nothingHappensUntilHeld() {
        assertEquals(Phase.IDLE, phase)
        assertEquals(0, fake.opens)
    }

    @Test
    fun registersWhenHeld() {
        hid.hold("t")
        idle()
        assertEquals(Phase.STARTING, phase)
        assertEquals(1, fake.proxy.registers)
        fake.proxy.events!!.onAppStatus(true)
        idle()
        assertEquals(Phase.READY, phase)
        assertEquals(Prefs.BT_SUPPORTED, Prefs.btSupport)
    }

    @Test
    fun withoutPermissionNothingIsTouched() {
        fake.permit = false
        hid.hold("t")
        idle()
        assertEquals(Phase.NO_PERMISSION, phase)
        assertEquals(0, fake.opens)
        // granted: trying again registers
        fake.permit = true
        hid.retry()
        idle()
        assertEquals(1, fake.proxy.registers)
    }

    @Test
    fun oldAndroidAndNoAdapter() {
        fake.apiAvailable = false
        hid.hold("t")
        assertEquals(Phase.OLD_ANDROID, phase)
        val other = FakeBackend().apply { hasAdapter = false }
        val h = HidController(other, Handler(Looper.getMainLooper())) { it.run() }
        h.hold("t")
        assertEquals(Phase.NO_ADAPTER, h.state.value.phase)
        assertEquals(0, fake.opens + other.opens)
    }

    @Test
    fun bluetoothOffThenOn() {
        fake.on = false
        hid.hold("t")
        idle()
        assertEquals(Phase.BLUETOOTH_OFF, phase)
        assertEquals(0, fake.opens)
        fake.on = true
        fake.watcher!!(true)
        idle()
        assertEquals(1, fake.proxy.registers)
    }

    @Test
    fun firmwareWithoutTheServiceSaysSo() {
        fake.openResult = false
        hid.hold("t")
        idle()
        assertEquals(Phase.UNSUPPORTED, phase)
        assertEquals(Prefs.BT_UNSUPPORTED, Prefs.btSupport)
    }

    @Test
    fun aServiceThatNeverComesIsUnsupported() {
        fake.answer = false
        hid.hold("t")
        idle()
        assertEquals(Phase.STARTING, phase)
        advance(HidController.PROXY_TIMEOUT_MS + 100)
        assertEquals(Phase.UNSUPPORTED, phase)
        assertEquals(Prefs.BT_UNSUPPORTED, Prefs.btSupport)
        // if it turns up after all, it's handed straight back
        fake.pending!!(fake.proxy)
        idle()
        assertEquals(listOf<HidProxy>(fake.proxy), fake.closed)
        assertEquals(0, fake.proxy.registers)
        assertEquals(Phase.UNSUPPORTED, phase)
    }

    @Test
    fun aRefusedRegistrationSaysSoAndCanBeRetried() {
        fake.proxy.registerResult = false
        hid.hold("t")
        idle()
        assertEquals(Phase.REFUSED, phase)
        assertEquals(Prefs.BT_REFUSED, Prefs.btSupport)
        assertEquals(1, fake.closed.size)
        fake.proxy.registerResult = true
        hid.retry()
        idle()
        fake.proxy.events!!.onAppStatus(true)
        idle()
        assertEquals(Phase.READY, phase)
        assertEquals(Prefs.BT_SUPPORTED, Prefs.btSupport)
    }

    @Test
    fun aRegistrationThatNeverAnswersIsRefused() {
        hid.hold("t")
        idle()
        advance(HidController.PROXY_TIMEOUT_MS + 100)
        assertEquals(Phase.REFUSED, phase)
    }

    @Test
    fun anUnsupportedPhoneKeepsAConfirmedResult() {
        Prefs.btSupport = Prefs.BT_SUPPORTED
        fake.proxy.registerResult = false
        hid.hold("t")
        idle()
        // "refused" after it once worked is another app, not the firmware
        assertEquals(Prefs.BT_SUPPORTED, Prefs.btSupport)
    }

    // --- connecting ---------------------------------------------------------------------------

    @Test
    fun connectsAndRemembersTheHost() {
        ready()
        hid.connect(tv)
        assertEquals(listOf(tv.address), fake.proxy.connects)
        assertEquals(Phase.CONNECTING, phase)
        fake.proxy.events!!.onConnection(tv, BluetoothProfile.STATE_CONNECTED)
        idle()
        assertEquals(HidController.State(Phase.CONNECTED, tv), hid.state.value)
        assertEquals(tv.address, Prefs.btLastHost)
        assertEquals(tv.name, Prefs.btLastHostName)
        // connecting again to the same one does nothing
        hid.connect(tv)
        assertEquals(1, fake.proxy.connects.size)
    }

    @Test
    fun aHostCanConnectByItself() {
        ready()
        fake.proxy.events!!.onConnection(pc, BluetoothProfile.STATE_CONNECTING)
        idle()
        assertEquals(Phase.CONNECTING, phase)
        fake.proxy.events!!.onConnection(pc, BluetoothProfile.STATE_CONNECTED)
        idle()
        assertEquals(pc, hid.state.value.host)
        assertEquals(pc.address, Prefs.btLastHost)
    }

    @Test
    fun connectingBeforeRegisteringWaitsForIt() {
        hid.hold("t")
        hid.connect(tv)
        idle()
        assertTrue(fake.proxy.connects.isEmpty())
        fake.proxy.events!!.onAppStatus(true)
        idle()
        assertEquals(listOf(tv.address), fake.proxy.connects)
        assertEquals(Phase.CONNECTING, phase)
    }

    @Test
    fun aHostThatNeverAnswers() {
        ready()
        hid.connect(tv)
        advance(HidController.CONNECT_TIMEOUT_MS + 100)
        assertEquals(HidController.State(Phase.READY, null, HidController.Problem.NO_ANSWER), hid.state.value)
        assertEquals(listOf(tv.address), fake.proxy.disconnects)
        assertEquals(tv, hid.lastTried)
    }

    @Test
    fun aRefusedConnection() {
        ready()
        hid.connect(tv)
        fake.proxy.events!!.onConnection(tv, BluetoothProfile.STATE_DISCONNECTED)
        idle()
        assertEquals(HidController.Problem.CONNECT_FAILED, hid.state.value.problem)
        assertEquals(Phase.READY, phase)
        // and one the stack won't even start
        fake.proxy.connectResult = false
        hid.connect(pc)
        assertEquals(HidController.State(Phase.READY, null, HidController.Problem.CONNECT_FAILED), hid.state.value)
    }

    @Test
    fun switchingHostsDropsTheFirst() {
        connected(tv)
        hid.connect(pc)
        assertEquals(listOf(tv.address), fake.proxy.disconnects)
        assertEquals(listOf(tv.address), fake.proxy.connects)   // pc waits for tv to go
        fake.proxy.events!!.onConnection(tv, BluetoothProfile.STATE_DISCONNECTED)
        idle()
        assertEquals(listOf(tv.address, pc.address), fake.proxy.connects)
        assertEquals(Phase.CONNECTING, phase)
        assertNull(hid.state.value.problem)
        fake.proxy.events!!.onConnection(pc, BluetoothProfile.STATE_CONNECTED)
        idle()
        assertEquals(pc, hid.state.value.host)
    }

    @Test
    fun disconnecting() {
        connected()
        hid.disconnect()
        assertEquals(listOf(tv.address), fake.proxy.disconnects)
        fake.proxy.events!!.onConnection(tv, BluetoothProfile.STATE_DISCONNECTED)
        idle()
        assertEquals(HidController.State(Phase.READY), hid.state.value)
    }

    // --- sending --------------------------------------------------------------------------

    @Test
    fun reportsGoOnlyToAConnectedHost() {
        ready()
        assertFalse(hid.send(HidReports.ID_KEYBOARD, HidReports.keyboard(0)))
        assertFalse(hid.keyboard.key("ArrowRight"))
        assertTrue(fake.proxy.sent.isEmpty())
        hid.connect(tv)
        fake.proxy.events!!.onConnection(tv, BluetoothProfile.STATE_CONNECTED)
        idle()
        assertTrue(hid.keyboard.key("ArrowRight"))
        assertEquals(listOf(1, 1), fake.proxy.sent.map { it.first })
        assertEquals(0x4F, fake.proxy.sent[0].second[2].toInt())
        assertEquals(0, fake.proxy.sent[1].second[2].toInt())
        hid.keyboard.consumer(HidKeys.CONSUMER.getValue("MediaPlayPause"))
        assertEquals(listOf(1, 1, 3, 3), fake.proxy.sent.map { it.first })
        hid.mouse.move(10f, 0f)
        assertEquals(2, fake.proxy.sent.last().first)
    }

    // --- letting go -----------------------------------------------------------------------

    @Test
    fun releasingUnregistersAfterAGrace() {
        connected()
        hid.release("t")
        advance(HidController.GRACE_MS - 500)
        assertEquals(0, fake.proxy.unregisters)
        advance(1_000)
        assertEquals(listOf("register", "connect ${tv.address}", "disconnect ${tv.address}", "unregister"), fake.proxy.log)
        assertEquals(listOf<HidProxy>(fake.proxy), fake.closed)
        assertNull(fake.watcher)
        assertEquals(Phase.IDLE, phase)
    }

    @Test
    fun anotherHolderWithinTheGraceKeepsIt() {
        connected()
        hid.release("t")
        advance(500)
        hid.hold("remote")   // the screen turned, or the remote took over
        advance(HidController.GRACE_MS * 2)
        assertEquals(0, fake.proxy.unregisters)
        assertTrue(hid.state.value.connected)
    }

    @Test
    fun aHeldButtonIsLetGoBeforeDisconnecting() {
        connected()
        hid.mouse.button(HidReports.BUTTON_LEFT, true)
        hid.release("t")
        advance(HidController.GRACE_MS + 100)
        val tail = fake.proxy.log.takeLast(4)
        assertEquals(listOf("send 2", "send 2", "disconnect ${tv.address}", "unregister"), tail)
        assertEquals(0, fake.proxy.sent.last().second[0].toInt())
        assertEquals(0, hid.mouse.buttons)
    }

    @Test
    fun bluetoothSwitchedOffMeanwhile() {
        connected()
        fake.on = false
        fake.watcher!!(false)
        assertEquals(Phase.BLUETOOTH_OFF, phase)
        assertFalse(hid.state.value.connected)
        assertEquals(1, fake.closed.size)
        // and back on: registers again by itself
        fake.on = true
        fake.watcher!!(true)
        idle()
        assertEquals(2, fake.proxy.registers)
    }

    @Test
    fun unregisteredByAndroidWhileHeldRegistersAgain() {
        ready()
        fake.proxy.events!!.onAppStatus(false)
        idle()
        assertEquals(2, fake.proxy.registers)
        assertEquals(Phase.STARTING, phase)
    }

    @Test
    fun theServiceGoingAway() {
        connected()
        fake.lost!!()
        idle()
        assertEquals(Phase.IDLE, phase)
        assertFalse(hid.send(1, HidReports.keyboard(0)))
        // the stack restarted: once it settles, the phone registers again
        advance(HidController.RESTART_MS + 100)
        assertEquals(2, fake.opens)
        assertEquals(2, fake.proxy.registers)
    }

    @Test
    fun bondedNeedsPermissionAndBluetooth() {
        fake.hosts = listOf(tv, pc)
        assertEquals(listOf(tv, pc), hid.bonded())
        fake.permit = false
        assertEquals(emptyList<HidHost>(), hid.bonded())
        fake.permit = true
        fake.on = false
        assertEquals(emptyList<HidHost>(), hid.bonded())
    }

    // --- the real backend, under Robolectric's shadows -------------------------------------------

    private fun realController(): HidController {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return HidController(AndroidHidBackend(app), Handler(Looper.getMainLooper())) { it.run() }
    }

    private fun adapter(): BluetoothAdapter =
        ApplicationProvider.getApplicationContext<Application>().getSystemService(BluetoothManager::class.java).adapter

    @Test
    fun realBackendAsksForNearbyDevicesFirst() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        val h = realController()
        h.hold("t")
        idle()
        assertEquals(Phase.NO_PERMISSION, h.state.value.phase)
        assertEquals(emptyList<HidHost>(), h.bonded())
        h.release("t")
    }

    @Test
    fun realBackendWithBluetoothOff() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        shadowOf(adapter()).setEnabled(false)
        val h = realController()
        h.hold("t")
        idle()
        assertEquals(Phase.BLUETOOTH_OFF, h.state.value.phase)
        h.release("t")
        advance(HidController.GRACE_MS + 100)
    }

    @Test
    fun realBackendWithoutTheHidServiceNeverCrashes() {
        // Robolectric's stack has no HID device service: the proxy never connects,
        // which is what a phone whose firmware leaves it out does
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        shadowOf(adapter()).setEnabled(true)
        val h = realController()
        h.hold("t")
        idle()
        advance(HidController.PROXY_TIMEOUT_MS + 100)
        assertEquals(Phase.UNSUPPORTED, h.state.value.phase)
        assertEquals(Prefs.BT_UNSUPPORTED, Prefs.btSupport)
        h.release("t")
        advance(HidController.GRACE_MS + 100)
        assertEquals(Phase.IDLE, h.state.value.phase)
    }
}
