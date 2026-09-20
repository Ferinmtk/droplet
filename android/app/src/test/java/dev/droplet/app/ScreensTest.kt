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
        val a = Robolectric.buildActivity(HubActivity::class.java).setup().get()
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

    // --- the mesh -------------------------------------------------------------------------

    /** A running mesh with two trusted devices, one on the Wi-Fi not paired, and a request waiting. */
    private fun fakeMesh(): dev.droplet.app.mesh.MeshNode {
        dev.droplet.app.mesh.MeshIdentity.keystoreAllowed = false
        val tmp = java.nio.file.Files.createTempDirectory("mesh-shots").toFile()
        val laptop = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "laptop"))
        val tv = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "tv"))
        val stranger = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "stranger"))
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.portOverride = 0
        Mesh.directoryFactory = {
            object : dev.droplet.app.mesh.PeerDirectory {
                override fun start(port: Int, txt: Map<String, String>, onSeen: (dev.droplet.app.mesh.Seen) -> Unit) = Unit
                override fun update(txt: Map<String, String>) = Unit
                override fun close() = Unit
                override fun peers() = listOf(dev.droplet.app.mesh.Seen(stranger.fp, "0a1b2c3d4e5f6071", "office-pc", "windows",
                    listOf("input"), "", 1739, listOf("192.168.100.31")))
            }
        }
        Prefs.meshDeviceName = "Redmi Note 11E Pro"
        Mesh.hold("shots")
        val end = System.currentTimeMillis() + 20_000
        while (Mesh.node == null && System.currentTimeMillis() < end) Thread.sleep(50)
        val n = Mesh.node!!
        n.trust.addPaired(dev.droplet.app.mesh.TrustList.makeEntry(peerId = "1234567890abcdef", name = "slim",
            certPem = laptop.pem, source = "paired", os = "linux", caps = listOf("input", "media")))
        n.trust.syncRoster(listOf(dev.droplet.app.mesh.TrustList.makeEntry(peerId = "9b16173d305c", name = "T15",
            certPem = tv.pem, source = "roster", os = "linux", caps = listOf("input"), hub = "9b16173d305cd15a")), "9b16173d305cd15a")
        return n
    }

    private fun releaseMesh() {
        Mesh.release("shots")
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.directoryFactory = { NsdPeerDirectory(it) }
    }

    @Test
    fun peers() {
        assumeTrue(out.isNotEmpty())
        val n = fakeMesh()
        try {
            val a = Robolectric.buildActivity(PeersActivity::class.java).setup().get()
            idleFor(300)
            shoot(a, "mesh-peers")
            val fp = n.trust.all().first { it.name == "slim" }.fp
            n.chat.add(org.json.JSONObject().put("id", "a1").put("dir", "in").put("fp", fp).put("body", "Slides are up, start when you like")
                .put("ts", System.currentTimeMillis() / 1000.0 - 300))
            n.chat.add(org.json.JSONObject().put("id", "a2").put("dir", "out").put("fp", fp).put("body", "Thanks, starting now")
                .put("ts", System.currentTimeMillis() / 1000.0 - 60).put("route", "lan"))
            val c = Robolectric.buildActivity(ChatActivity::class.java, ChatActivity.intent(ApplicationProvider.getApplicationContext(), fp)).setup().get()
            idleFor(300)
            shoot(c, "mesh-chat")
            val st = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            val scroll = st.findViewById<android.widget.ScrollView>(R.id.scroll)
            var y = 0
            var v: android.view.View? = st.findViewById(R.id.mesh_switch)
            while (v != null && v !== scroll) { y += v.top; v = v.parent as? android.view.View }
            scroll.scrollTo(0, y - 200)
            shoot(st, "settings-mesh")
        } finally {
            releaseMesh()
        }
    }

    // --- without a hub: setup's choice, the home screen, pairing ----------------------------

    @Test
    fun setupChoice() {
        assumeTrue(out.isNotEmpty())
        SetupActivity.browser = { _, onChange, _ -> onChange(listOf(t15)); Discovery.Handle { } }
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        shoot(a, "setup-choose")
        a.findViewById<android.view.View>(R.id.choose_direct).performClick()
        shoot(a, "setup-direct")
        SetupActivity.browser = null
    }

    /** Another droplet device, in this process: a real peer on 127.0.0.1 to pair with and reach. */
    private fun peer(tmp: File, name: String, os: String, caps: List<String>): dev.droplet.app.mesh.MeshNode =
        dev.droplet.app.mesh.MeshNode(object : MeshUnitTest.QuietHost() {
            override fun deviceName() = name
            override fun caps() = caps
        }, File(tmp, name), null, port = 0, bindAddress = java.net.InetAddress.getLoopbackAddress()).also { it.start() }

    private fun waitMain(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            idleFor(50)
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** The phone's own mesh, set up without a hub, with [nearby] announced on the "Wi-Fi". */
    private fun noHubPhone(tmp: File, nearby: () -> List<dev.droplet.app.mesh.Seen> = { emptyList() }): dev.droplet.app.mesh.MeshNode {
        dev.droplet.app.mesh.MeshIdentity.keystoreAllowed = false
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.portOverride = 0
        Mesh.addresses = { listOf("127.0.0.1") }
        Mesh.directoryFactory = {
            object : dev.droplet.app.mesh.PeerDirectory {
                override fun start(port: Int, txt: Map<String, String>, onSeen: (dev.droplet.app.mesh.Seen) -> Unit) = Unit
                override fun update(txt: Map<String, String>) = Unit
                override fun close() = Unit
                override fun peers() = nearby()
            }
        }
        Prefs.noHub = true
        Prefs.stayConnected = true
        Prefs.meshDeviceName = "Redmi Note 11E Pro"
        Mesh.hold("shots")
        val end = System.currentTimeMillis() + 20_000
        while (Mesh.node == null && System.currentTimeMillis() < end) Thread.sleep(50)
        return Mesh.node!!
    }

    private fun pairWith(phone: dev.droplet.app.mesh.MeshNode, other: dev.droplet.app.mesh.MeshNode) {
        other.incoming.onReady = { r -> Thread { other.pairAnswer(r.request, true) }.start() }
        val og = phone.pairStart("127.0.0.1", other.listeningPort, other.identity.fp)
        val done = java.util.concurrent.CountDownLatch(1)
        phone.pairConfirm(og.request!!, true) { done.countDown() }
        check(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun homeWithoutAHub() {
        assumeTrue(out.isNotEmpty())
        val tmp = java.nio.file.Files.createTempDirectory("home-shots").toFile()
        val others = mutableListOf<dev.droplet.app.mesh.MeshNode>()
        try {
            val phone = noHubPhone(tmp)
            // a brand-new phone: nothing paired yet
            val empty = Robolectric.buildActivity(MainActivity::class.java).setup()
            idleFor(300)
            shoot(empty.get(), "home-empty")
            empty.get().findViewById<android.widget.ScrollView>(R.id.root).scrollTo(0, 2000)
            shoot(empty.get(), "home-empty-end")
            empty.pause().stop().destroy()

            // a laptop, reached directly, and a phone that's away with a message waiting for it
            val slim = peer(tmp, "slim", "linux", listOf("clipboard", "input", "media")).also { others += it }
            pairWith(phone, slim)
            val pixel = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "pixel"))
            phone.trust.addPaired(dev.droplet.app.mesh.TrustList.makeEntry(peerId = "7a7a7a7a7a7a7a7a", name = "Wanjiru's Pixel",
                certPem = pixel.pem, source = "paired", os = "android", caps = listOf("clipboard")))
            phone.sendText(pixel.fp, "See you at 6")
            val slimFp = slim.identity.fp
            val now = System.currentTimeMillis()
            phone.chat.add(org.json.JSONObject().put("id", "m1").put("dir", "in").put("fp", slimFp).put("name", "slim")
                .put("body", "The slides are in your Downloads").put("ts", now / 1000.0 - 240))
            Received.add(Received.Item("slides-final.pdf", "slim", slimFp, null, "application/pdf", "Download/droplet/slides-final.pdf", now - 180_000))
            Received.add(Received.Item("IMG_20260919_101500.jpg", "slim", slimFp, null, "image/jpeg", "Download/droplet/IMG_20260919_101500.jpg", now - 60_000))
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            waitMain("slim reached directly") {
                c.get().findViewById<android.widget.LinearLayout>(R.id.devices).childCount == 2 &&
                    phone.openLink(slimFp) != null && !Mesh.isProbing(pixel.fp)
            }
            idleFor(300)
            shoot(c.get(), "home-devices")
            c.get().findViewById<android.widget.ScrollView>(R.id.root).scrollTo(0, 2000)
            shoot(c.get(), "home-devices-end")
            c.get().findViewById<android.widget.ScrollView>(R.id.root).scrollTo(0, 0)
            // being rung
            Ringer.start(ApplicationProvider.getApplicationContext(), Ring("shot", "slim", now / 1000.0))
            waitMain("ringing") { c.get().findViewById<android.view.View>(R.id.ringing).visibility == android.view.View.VISIBLE }
            shoot(c.get(), "home-ringing")
            Ringer.stop(ApplicationProvider.getApplicationContext(), tellHub = false)
            idleFor(100)
            c.pause().stop().destroy()
        } finally {
            others.forEach { it.close() }
            releaseMesh()
            Mesh.addresses = null
            Prefs.noHub = false
            tmp.deleteRecursively()
        }
    }

    @Test
    fun homeWithAHub() {
        assumeTrue(out.isNotEmpty())
        val n = fakeMesh()
        try {
            Prefs.hubUrl = "https://t15.tail7375fe.ts.net"
            Prefs.hubName = "t15"
            Prefs.hubId = t15.id
            Prefs.hubFingerprint = t15.fingerprint
            n.trust.all().size
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            waitMain("the devices") {
                c.get().findViewById<android.widget.LinearLayout>(R.id.devices).childCount == 2 &&
                    n.trust.all().none { Mesh.isProbing(it.fp) }
            }
            idleFor(300)
            shoot(c.get(), "home-hub")
            c.get().findViewById<android.widget.ScrollView>(R.id.root).scrollTo(0, 2000)
            shoot(c.get(), "home-hub-end")
            c.pause().stop().destroy()
        } finally {
            releaseMesh()
            Prefs.forgetHub()
        }
    }

    @Test
    fun pairing() {
        assumeTrue(out.isNotEmpty())
        val tmp = java.nio.file.Files.createTempDirectory("pair-shots").toFile()
        val others = mutableListOf<dev.droplet.app.mesh.MeshNode>()
        try {
            val office = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "office"))
            val tab = dev.droplet.app.mesh.MeshIdentity.loadOrCreate(File(tmp, "tab"))
            val phone = noHubPhone(tmp) {
                listOf(dev.droplet.app.mesh.Seen(office.fp, "0a1b2c3d4e5f6071", "office-pc", "windows", listOf("input"), "", 1739,
                    listOf("192.168.100.31")),
                    dev.droplet.app.mesh.Seen(tab.fp, "1b2c3d4e5f607182", "Galaxy Tab", "android", listOf("clipboard"), "", 1739,
                        listOf("192.168.100.44")))
            }
            // another device asks to pair with this phone
            val laptop = peer(tmp, "Wanjiru's laptop", "linux", listOf("input")).also { others += it }
            Thread { runCatching { laptop.pairStart("127.0.0.1", phone.listeningPort, phone.identity.fp) } }.start()
            val end = System.currentTimeMillis() + 20_000
            while (phone.incoming.waiting().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
            val c = Robolectric.buildActivity(PeersActivity::class.java).setup()
            idleFor(500)
            shoot(c.get(), "pair-list")
            // the request's code
            c.get().findViewById<android.widget.LinearLayout>(R.id.asking).getChildAt(0).performClick()
            idleFor(200)
            shoot(c.get(), "pair-code")
            c.pause().stop().destroy()
        } finally {
            others.forEach { it.close() }
            releaseMesh()
            Mesh.addresses = null
            Prefs.noHub = false
            tmp.deleteRecursively()
        }
    }
}
