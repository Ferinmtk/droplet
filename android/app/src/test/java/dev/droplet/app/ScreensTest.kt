package dev.droplet.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the remote and Settings screens to PNGs for a human to look at
 * (no emulator needed). Runs only when DROPLET_SHOTS names an output folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class ScreensTest {
    private val out = System.getProperty("droplet.shots").orEmpty()

    private fun shoot(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun remote() {
        assumeTrue(out.isNotEmpty())
        Prefs.init(ApplicationProvider.getApplicationContext())
        shoot(Robolectric.buildActivity(RemoteActivity::class.java).setup().get(), "remote")
    }

    private val t15 = Announced("9b16173d305cd15a", "3c103f2cda4a422ac2da8a86565f4f6664d31ca3f2e720bddc34593e14ad2094",
        "t15", "192.168.100.20", 8443, 8000, "https://t15.tail7375fe.ts.net")

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test
    fun setup() {
        assumeTrue(out.isNotEmpty())
        Router.reset()
        SetupActivity.browser = { _, onChange, _ ->
            onChange(listOf(t15, t15.copy(id = "aaaaaaaaaaaaaaaa", name = "office-pc", host = "192.168.100.31")))
            Discovery.Handle { }
        }
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        shoot(a, "setup-find")
        // the code screen, as after "Ask to join"
        val show = SetupActivity::class.java.getDeclaredMethod("showCode", Pairing.Standing.Waiting::class.java)
        show.isAccessible = true
        show.invoke(a, Pairing.Standing.Waiting("abc", "Redmi Note 11E Pro", "7156"))
        a.findViewById<android.view.View>(R.id.pin).visibility = android.view.View.VISIBLE
        shoot(a, "setup-code")
        SetupActivity.browser = null
    }

    @Test
    fun setupRepair() {
        assumeTrue(out.isNotEmpty())
        SetupActivity.browser = { _, _, _ -> Discovery.Handle { } }
        val intent = android.content.Intent(ApplicationProvider.getApplicationContext(), SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_REPAIR, true)
        val a = Robolectric.buildActivity(SetupActivity::class.java, intent).setup().get()
        idleFor(7_000)  // past "nothing yet"
        shoot(a, "setup-repair")
        SetupActivity.browser = null
    }

    @Test
    fun offline() {
        assumeTrue(out.isNotEmpty())
        Router.reset()
        Router.discover = { _, _ -> emptyList() }
        Prefs.hubName = "t15"
        Prefs.hubId = t15.id
        Prefs.hubFingerprint = t15.fingerprint
        Prefs.lanAddresses = listOf("127.0.0.1:1")
        Prefs.hubUrl = "https://t15.example.invalid"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val end = System.currentTimeMillis() + 15_000
        while (!Router.state.value.unreachable && System.currentTimeMillis() < end) { idleFor(50); Thread.sleep(50) }
        idleFor(100)
        shoot(a, "offline")
        Router.reset()
    }

    @Test
    fun settings() {
        assumeTrue(out.isNotEmpty())
        Prefs.hubUrl = "https://t15.tail7375fe.ts.net"
        Prefs.hubName = "t15"
        Prefs.hubId = t15.id
        Prefs.hubFingerprint = t15.fingerprint
        Prefs.pinSource = Prefs.PIN_TAILNET
        Router.use(Router.Route(Router.Kind.LAN, t15.base, t15.fingerprint))
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shoot(a, "settings")
        // the remote-control part, further down
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.scroll)
        val section = a.findViewById<android.view.View>(R.id.live_state)
        var y = 0
        var v: android.view.View? = section
        while (v != null && v !== scroll) { y += v.top; v = v.parent as? android.view.View }
        scroll.scrollTo(0, y - 200)
        shoot(a, "settings-remote")
        scroll.scrollTo(0, y + 900)
        shoot(a, "settings-caps")
    }

    // --- the Bluetooth mouse and keyboard ------------------------------------------------

    private val tv = HidHost("F4:6B:8C:21:0A:3E", "TCL 43 Google TV")
    private val pc = HidHost("5C:BA:EF:77:10:02", "maryanne")

    /** A fake Bluetooth stack behind BtHid, so the screens show each state. */
    private fun fakeBluetooth(configure: BtHidTest.FakeBackend.() -> Unit = {}): BtHidTest.FakeBackend {
        Prefs.init(ApplicationProvider.getApplicationContext())
        val fake = BtHidTest.FakeBackend().apply {
            hosts = listOf(tv, pc)
            configure()
        }
        BtHid.controller = HidController(fake, android.os.Handler(Looper.getMainLooper())) { it.run() }
        return fake
    }

    private fun registered(fake: BtHidTest.FakeBackend) {
        shadowOf(Looper.getMainLooper()).idle()
        fake.proxy.events?.onAppStatus(true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connected(fake: BtHidTest.FakeBackend, host: HidHost = tv) {
        registered(fake)
        BtHid.controller.connect(host)
        fake.proxy.events!!.onConnection(host, android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bluetoothScreen() = Robolectric.buildActivity(BluetoothActivity::class.java).setup()

    @Test
    fun bluetooth() {
        assumeTrue(out.isNotEmpty())
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.btLastHost = tv.address
        Prefs.btLastHostName = tv.name
        val fake = fakeBluetooth()
        val c = bluetoothScreen()
        registered(fake)
        shoot(c.get(), "bt-ready")
        c.get().findViewById<android.widget.ScrollView>(R.id.setup).scrollTo(0, 2000)
        shoot(c.get(), "bt-ready-pairing")

        // a first time: nothing used before
        Prefs.btLastHost = null
        val fresh = fakeBluetooth { hosts = emptyList() }
        val f = bluetoothScreen()
        registered(fresh)
        shoot(f.get(), "bt-first")

        Prefs.btLastHost = tv.address
        val live = fakeBluetooth()
        val a = bluetoothScreen()
        connected(live)
        shoot(a.get(), "bt-touchpad")
        a.get().findViewById<android.view.View>(R.id.tab_keys).performClick()
        shoot(a.get(), "bt-keyboard")
        a.get().findViewById<android.view.View>(R.id.tab_media).performClick()
        shoot(a.get(), "bt-media")
    }

    @Test
    fun bluetoothProblems() {
        assumeTrue(out.isNotEmpty())
        fakeBluetooth { openResult = false }
        shoot(bluetoothScreen().get(), "bt-unsupported")
        fakeBluetooth { permit = false }
        shoot(bluetoothScreen().get(), "bt-permission")
        fakeBluetooth { on = false }
        shoot(bluetoothScreen().get(), "bt-off")
        val fake = fakeBluetooth()
        val c = bluetoothScreen()
        registered(fake)
        BtHid.controller.connect(pc)
        idleFor(HidController.CONNECT_TIMEOUT_MS + 100)
        shoot(c.get(), "bt-no-answer")
    }

    @Test
    fun remoteOverBluetooth() {
        assumeTrue(out.isNotEmpty())
        val fake = fakeBluetooth()
        Prefs.remoteTarget = Prefs.BT_TARGET + pc.address
        val a = Robolectric.buildActivity(RemoteActivity::class.java).setup().get()
        connected(fake, pc)
        shoot(a, "remote-bt")
        Prefs.remoteTarget = null
    }

    @Test
    fun settingsBluetooth() {
        assumeTrue(out.isNotEmpty())
        fakeBluetooth()
        Prefs.btLastHost = pc.address
        Prefs.btLastHostName = pc.name
        Prefs.btSupport = Prefs.BT_SUPPORTED
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.scroll)
        val section = a.findViewById<android.view.View>(R.id.bt_state)
        var y = 0
        var v: android.view.View? = section
        while (v != null && v !== scroll) { y += v.top; v = v.parent as? android.view.View }
        scroll.scrollTo(0, y - 400)
        shoot(a, "settings-bt")
    }
}
