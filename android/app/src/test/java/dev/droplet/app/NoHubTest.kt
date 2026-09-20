package dev.droplet.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.PeerDirectory
import dev.droplet.app.mesh.Seen
import dev.droplet.app.mesh.TrustList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * droplet with no hub anywhere, end to end: a fresh install chooses "No
 * hub", names the phone, lands on the home screen's empty state, pairs with
 * a real Linux agent (`droplet-agent run --dry-run`, a separate process in a
 * throwaway HOME) through the pairing screen, and then uses it from the home
 * screen: the device's route, a file and a message each way, a ring each
 * way (the phone's alarm path), the clipboard both ways (and the Send
 * clipboard tile), and the share sheet. The phone is the app's own screens
 * and services on Robolectric, over real sockets on this machine's LAN
 * address; mDNS is the only thing stood in for (Robolectric has no
 * NsdManager): the fake directory announces the agent the way NSD would.
 *
 *   python3 -m venv /tmp/v && /tmp/v/bin/pip install ./agent
 *   DROPLET_TEST_AGENT_PY=/tmp/v/bin/python ./gradlew testReleaseUnitTest --tests '*NoHubTest*'
 *
 * The hub user's migration also needs a throwaway hub (DROPLET_TEST_HUB).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoHubTest {
    private val python = System.getProperty("droplet.testAgentPy").orEmpty()
    private val hub = System.getProperty("droplet.testHub").orEmpty()
    private val script = File("src/test/python/mesh_agent.py").absoluteFile
    private lateinit var app: Application
    private lateinit var tmp: File
    private lateinit var lan: String
    private val agents = mutableListOf<Agent>()
    private var phonePort = 0
    /** What the phone's "mDNS" sees on the Wi-Fi. */
    @Volatile private var onWifi: List<Seen> = emptyList()
    private var failed = true

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        tmp = File(System.getProperty("droplet.testScratch").orEmpty().ifEmpty { System.getProperty("java.io.tmpdir") },
            "nh-" + System.nanoTime().toString(36)).apply { mkdirs() }
        lan = System.getProperty("droplet.testLanIp").orEmpty().ifEmpty { lanAddress() }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val nc = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(nc).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(nc).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, nc)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Router.discover = { _, _ -> emptyList() }

        MeshIdentity.keystoreAllowed = false
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.directoryFactory = {
            object : PeerDirectory {
                override fun start(port: Int, txt: Map<String, String>, onSeen: (Seen) -> Unit) = Unit
                override fun update(txt: Map<String, String>) = Unit
                override fun close() = Unit
                override fun peers() = onWifi
            }
        }
        phonePort = ServerSocket(0).use { it.localPort }
        Mesh.portOverride = phonePort
        Mesh.addresses = { listOf(lan) }
        Mesh.retryMs = 3_000
        Mesh.log = { println("phone: $it") }
        val downloads = File(tmp, "phone-downloads").apply { mkdirs() }
        Mesh.saver = { _, part, name, _ ->
            val n = MeshDownloads.unique(name) { !File(downloads, it).exists() }
            part.copyTo(File(downloads, n))
            // a content URI, as MediaStore gives, so the home screen can open it
            Mesh.Saved(Uri.parse("content://${TestFiles.AUTHORITY}/${Uri.encode(File(downloads, n).path)}"), "Download/droplet/$n")
        }
        Robolectric.setupContentProvider(TestFiles::class.java, TestFiles.AUTHORITY)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        Mesh.release("test")
        // whoever else still holds the mesh, the peer server must not outlive this test:
        // a node left running makes the next test's setup skip making its own identity
        Mesh.resetForTests()
        Live.release("test")
        Router.reset()
        Hub.clearToken()
        Prefs.forgetHub()
        Prefs.noHub = false
        agents.forEach { it.stop() }
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.addresses = null
        Mesh.directoryFactory = { NsdPeerDirectory(it) }
        SetupActivity.browser = null
        if (!failed) tmp.deleteRecursively()
    }

    // --- helpers --------------------------------------------------------------------

    private val services = mutableListOf<org.robolectric.android.controller.ServiceController<*>>()

    private fun lanAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wl") || it.name.startsWith("en") || it.name.startsWith("eth")) }
        .flatMap { it.inetAddresses.toList() }.first { it is java.net.Inet4Address }.hostAddress!!

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun py(vararg args: String, timeoutS: Long = 90): String {
        val p = ProcessBuilder(listOf(python, script.path) + args).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("mesh_agent.py ${args.first()} timed out", p.waitFor(timeoutS, TimeUnit.SECONDS))
        return out.trim()
    }

    /** A Linux agent in a throwaway HOME, driven through its control socket (see MeshInteropTest). */
    private inner class Agent(val name: String) {
        val root = File(tmp, name)
        val runtime = File("/tmp/claude-1000", "nh-" + name + "-" + System.nanoTime().toString(36))
        var proc: Process? = null
        val log = File(root, "agent.log")

        init {
            root.mkdirs()
            py("init", root.path, name, runtime.path)
        }

        fun start() {
            proc = ProcessBuilder(python, script.path, "run", root.path).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            waitFor("$name to start", 30_000) { !ctl(JSONObject().put("cmd", "status")).has("error") }
        }

        fun stop() {
            val p = proc ?: return
            p.destroy()
            p.waitFor(15, TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly().waitFor()
            proc = null
            runtime.deleteRecursively()
        }

        fun ctl(req: JSONObject): JSONObject {
            val out = py("ctl", root.path, req.toString())
            return runCatching { JSONObject(out.lines().last()) }.getOrElse { JSONObject().put("error", out) }
        }

        fun status() = ctl(JSONObject().put("cmd", "status"))
        val fp: String by lazy { py("fp", root.path) }
        fun logText() = if (log.exists()) log.readText() else ""
        fun chat(): List<JSONObject> = File(root, "h/.local/share/droplet-agent/mesh/chat.jsonl").let { f ->
            if (f.exists()) f.readLines().map { JSONObject(it) } else emptyList()
        }
        fun downloads() = File(root, "h/Downloads/droplet")
        fun trusts(fp: String): String? = File(root, "h/.config/droplet-agent/mesh/trust.json").let { f ->
            if (!f.exists()) null else JSONObject(f.readText()).getJSONObject("peers").optJSONObject(fp)?.getString("source")
        }
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Activity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    /** Runs a service the app started, as Android would (Robolectric only records the start). */
    private fun runStartedService(cls: Class<*>): Intent {
        var intent: Intent? = null
        waitFor("${cls.simpleName} to be started") {
            val next = shadowOf(app).nextStartedService
            if (next?.component?.className == cls.name) intent = next
            intent != null
        }
        @Suppress("UNCHECKED_CAST")
        val c = Robolectric.buildService(cls as Class<android.app.Service>, intent).create().startCommand(0, services.size + 1)
        services += c
        return intent!!
    }

    private fun notificationText(id: Int, tag: String? = null): String? {
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.firstOrNull { sn ->
            shadowOf(nm).activeNotifications.any { it.id == id && it.tag == tag && it.notification === sn }
        } ?: return null
        return n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() + " | " +
            n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
    }

    // --- a fresh install, no hub anywhere ------------------------------------------------

    @Test
    fun freshInstallWithNoHubPairsAndUsesALinuxAgent() {
        assumeTrue("set DROPLET_TEST_AGENT_PY to run the no-hub end-to-end test", python.isNotEmpty())
        assertFalse(Prefs.isSetUp)

        // --- first run: the choice, then naming the phone ---------------------------------
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue("not set up: the home screen hands over to setup", first.get().isFinishing)
        assertEquals(SetupActivity::class.java.name, shadowOf(first.get()).nextStartedActivity.component?.className)

        SetupActivity.browser = { _, _, _ -> Discovery.Handle { } }
        val setup = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        assertTrue("the choice comes first", setup.visible(R.id.panel_choose))
        assertTrue(setup.visible(R.id.choose_direct) && setup.visible(R.id.choose_hub))
        assertFalse(setup.visible(R.id.panel_find))
        setup.findViewById<View>(R.id.choose_direct).performClick()
        assertTrue(setup.visible(R.id.panel_direct))
        assertTrue("the phone's own name is suggested", setup.text(R.id.direct_name).isNotBlank())
        setup.findViewById<TextView>(R.id.direct_name).text = "  robo   phone "
        setup.findViewById<View>(R.id.direct_go).performClick()
        waitFor("setup to finish") { setup.isFinishing }
        assertEquals(MainActivity::class.java.name, shadowOf(setup).nextStartedActivity.component?.className)
        assertTrue(Prefs.noHub)
        assertTrue(Prefs.isSetUp)
        assertFalse(Prefs.hasHub)
        assertTrue("Stay connected is on, to run the peer server", Prefs.stayConnected)
        assertEquals("robo phone", Prefs.meshDeviceName)
        assertTrue("the mesh identity was made", File(Mesh.dir(), "identity").listFiles()?.isNotEmpty() == true)
        val madeFp = MeshIdentity.loadOrCreate(File(Mesh.dir(), "identity")).fp

        // --- Stay connected: the peer server, with no hub and no nagging ------------------------
        runStartedService(ConnectionService::class.java)
        waitFor("the peer server", 30_000) { Mesh.node != null }
        val node = Mesh.node!!
        assertEquals("the identity made in setup is the one used", madeFp, node.identity.fp)
        assertEquals(phonePort, node.listeningPort)
        waitFor("the notification says it's ready") { notificationText(Notifs.ID_CONNECTION)?.contains("Ready to pair") == true }
        assertEquals(Live.Status.NO_HUB, Live.state.value.status)

        // --- the home screen: empty ------------------------------------------------------------
        val homeCtl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val home = homeCtl.get()
        homeScreen = home
        idle()
        assertFalse(home.isFinishing)
        assertTrue("the empty state", home.visible(R.id.empty))
        assertEquals(0, home.findViewById<LinearLayout>(R.id.devices).childCount)
        assertFalse("no hub, no Hub tile", home.visible(R.id.tile_hub))
        assertTrue(home.visible(R.id.tile_tv) && home.visible(R.id.tile_bt))
        assertEquals("This phone: robo phone", home.text(R.id.me))
        assertFalse(home.visible(R.id.notice))
        home.findViewById<View>(R.id.pair).performClick()
        assertEquals(PeersActivity::class.java.name, shadowOf(home).nextStartedActivity.component?.className)

        // --- pairing with the agent, through the pairing screen ----------------------------------
        val a = Agent("linux-a").also { agents += it }
        a.start()
        val st = a.status()
        onWifi = listOf(Seen(a.fp, st.getString("id"), "linux-a", "linux", listOf("clipboard", "input", "media"), "",
            st.getInt("port"), listOf(lan)))
        val pairCtl = Robolectric.buildActivity(PeersActivity::class.java).setup()
        val pair = pairCtl.get()
        waitFor("the agent listed on the Wi-Fi") { pair.findViewById<LinearLayout>(R.id.nearby).childCount == 1 }
        assertFalse(pair.visible(R.id.nearby_none))
        assertEquals("linux-a", pair.findViewById<LinearLayout>(R.id.nearby).getChildAt(0).findViewById<TextView>(R.id.name).text.toString())
        pair.findViewById<LinearLayout>(R.id.nearby).getChildAt(0).performClick()
        waitFor("the code") { pair.visible(R.id.panel_code) && pair.text(R.id.code).all { it.isDigit() } }
        val code = pair.text(R.id.code)
        val incoming = a.status().getJSONArray("incoming")
        assertEquals(1, incoming.length())
        assertEquals("both screens show the same code", code, incoming.getJSONObject(0).getString("code"))
        assertEquals("robo phone", incoming.getJSONObject(0).getString("name"))
        pair.findViewById<View>(R.id.code_yes).performClick()
        assertTrue("waiting for the agent", pair.visible(R.id.code_wait))
        val ans = a.ctl(JSONObject().put("cmd", "pair-answer").put("request", incoming.getJSONObject(0).getString("request")).put("accept", true))
        assertEquals("accepted", ans.getString("state"))
        waitFor("paired", 30_000) { pair.visible(R.id.panel_done) }
        assertEquals("Paired with linux-a", pair.text(R.id.done_title))
        assertEquals(TrustList.SOURCE_PAIRED, node.trust.get(a.fp)?.source)
        assertEquals("paired", a.trusts(node.identity.fp))
        pair.findViewById<View>(R.id.done).performClick()
        assertTrue(pair.isFinishing)
        pairCtl.pause().stop().destroy()

        // --- back home: the device, with its route --------------------------------------------------
        homeCtl.pause().resume()
        waitFor("the device on the home screen") { home.findViewById<LinearLayout>(R.id.devices).childCount == 1 }
        assertFalse(home.visible(R.id.empty))
        assertEquals("linux-a", card().findViewById<TextView>(R.id.name).text.toString())
        waitFor("reached directly over the Wi-Fi", 30_000) { card().findViewById<TextView>(R.id.route).text.toString() == "Connected on Wi-Fi" }
        assertEquals("lan", node.route(node.trust.get(a.fp)!!))
        assertTrue("the agent takes input: Remote is offered", card().findViewById<View>(R.id.act_remote).visibility == View.VISIBLE)
        waitFor("the notification counts it") { notificationText(Notifs.ID_CONNECTION)?.contains("Ready for your 1 device") == true }

        // --- a file, phone → agent, picked from the home screen ------------------------------------
        val out = File(tmp, "holiday.bin").apply { writeBytes(ByteArray(3 shl 20).also { SecureRandom().nextBytes(it) }) }
        card().findViewById<View>(R.id.act_files).performClick()
        val picker = shadowOf(home).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        shadowOf(home).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(TestFiles.uri(out)))
        val upload = runStartedService(UploadService::class.java)
        assertEquals(PeersActivity.MESH_PREFIX + a.fp, upload.getStringExtra("to"))
        waitFor("the agent saved it", 60_000) { File(a.downloads(), "holiday.bin").let { it.exists() && it.length() == out.length() } }
        assertEquals(sha(out), sha(File(a.downloads(), "holiday.bin")))

        // --- a file, agent → phone: a notification, and Received on the home screen --------------------
        val back = File(tmp, "notes.txt").apply { writeText("from linux, with love\n") }
        val sent = a.ctl(JSONObject().put("cmd", "send-file").put("peer", "robo phone").put("path", back.path).put("wait", 30))
        assertEquals(sent.toString(), "done", sent.getString("state"))
        waitFor("Received lists it") { home.visible(R.id.received) && home.findViewById<LinearLayout>(R.id.received).childCount == 1 }
        val row = home.findViewById<LinearLayout>(R.id.received).getChildAt(0)
        assertEquals("notes.txt", row.findViewById<TextView>(R.id.title).text.toString())
        assertTrue(row.findViewById<TextView>(R.id.sub).text.toString().startsWith("from linux-a"))
        assertEquals("linux-a sent a file | Download/droplet/notes.txt", notificationText(0, "file-Download/droplet/notes.txt"))
        row.performClick()
        val view = shadowOf(home).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(TestFiles.AUTHORITY, view.data?.authority)

        // --- messages, both ways -------------------------------------------------------------------
        card().findViewById<View>(R.id.act_message).performClick()
        val chatIntent = shadowOf(home).nextStartedActivity
        assertEquals(ChatActivity::class.java.name, chatIntent.component?.className)
        val chatCtl = Robolectric.buildActivity(ChatActivity::class.java, chatIntent).setup()
        chatCtl.get().findViewById<TextView>(R.id.input).text = "hello from the home screen"
        chatCtl.get().findViewById<View>(R.id.send).performClick()
        waitFor("the agent has the message") { a.chat().any { it.getString("body") == "hello from the home screen" && it.getString("dir") == "in" } }
        chatCtl.pause().stop().destroy()
        val t = a.ctl(JSONObject().put("cmd", "text").put("peer", "robo phone").put("body", "hello from linux").put("wait", 20))
        assertEquals(t.toString(), "done", t.getString("state"))
        waitFor("a message notification") { notificationText(0, "chat-${a.fp.take(16)}") == "linux-a | hello from linux" }
        waitFor("Messages on the home screen") { home.visible(R.id.messages) && home.findViewById<LinearLayout>(R.id.messages).childCount == 1 }
        assertTrue(home.findViewById<LinearLayout>(R.id.messages).getChildAt(0).findViewById<TextView>(R.id.sub).text.startsWith("hello from linux"))

        // --- ring: the agent rings the phone (the alarm path), and Stop on the home screen ----------------
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        assertEquals("lan", a.ctl(JSONObject().put("cmd", "ring").put("peer", "robo phone")).optString("route"))
        waitFor("the phone rings") { Ringer.ringing?.from == "linux-a" }
        assertEquals("the alarm stream at full volume", audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNotNull("the full-screen ring notification", notificationText(Notifs.ID_RING))
        waitFor("the home screen says so") { home.visible(R.id.ringing) }
        assertEquals("linux-a is ringing this phone", home.text(R.id.ringing_text))
        home.findViewById<View>(R.id.ringing_stop).performClick()
        waitFor("stopped") { Ringer.ringing == null && !home.visible(R.id.ringing) }
        assertEquals("the volume is put back", 2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(notificationText(Notifs.ID_RING))
        // and the phone rings the agent
        card().findViewById<View>(R.id.act_ring).performClick()
        waitFor("the agent rings") { "ring (dry run)" in a.logText() }

        // --- the clipboard, both ways --------------------------------------------------------------------
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        a.ctl(JSONObject().put("cmd", "clip").put("peer", "robo phone").put("text", "copied on linux"))
        waitFor("the phone's clipboard") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copied on linux" }
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "copied on the phone"))
        card().findViewById<View>(R.id.act_clip).performClick()
        waitFor("the agent's clipboard") { "would set 19 characters: 'copied on the phone'" in a.logText() }
        // the Send clipboard tile (and notification action), with no hub
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "sent by the tile"))
        val tile = Robolectric.buildActivity(ClipSendActivity::class.java).setup()
        tile.windowFocusChanged(true)
        waitFor("the tile's clipboard reaches the agent", 30_000) { "would set 16 characters: 'sent by the tile'" in a.logText() }
        waitFor("the tile is done") { tile.get().isFinishing }
        assertEquals(app.getString(R.string.clip_sent), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())

        // --- the share sheet: to the agent, directly -----------------------------------------------------
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared from another app")
        val sheet = Robolectric.buildActivity(ShareActivity::class.java, share).setup()
        // the sheet's views live in a BottomSheetDialog, not in the activity's own content
        fun targets(): LinearLayout? =
            org.robolectric.shadows.ShadowDialog.getLatestDialog()?.findViewById(R.id.targets)
        waitFor("the agent in the share sheet") {
            val list = targets() ?: return@waitFor false
            list.childCount == 1 && list.getChildAt(0).findViewById<TextView>(R.id.name).text.toString() == "linux-a"
        }
        targets()!!.getChildAt(0).performClick()
        runStartedService(UploadService::class.java)
        waitFor("the agent has the shared text") { a.chat().any { it.getString("body") == "shared from another app" } }

        // --- and no hub anywhere, all along ---------------------------------------------------------------
        assertFalse(Prefs.hasHub)
        assertEquals(Live.Status.NO_HUB, Live.state.value.status)
        assertTrue(listOf("hub", "hub-mailbox").none { r -> node.chat.recent(a.fp).any { it.optString("route") == r } })
        homeCtl.pause().stop().destroy()
        failed = false
    }

    /** The home screen on test, and its first device card (redrawn as things change, so found afresh). */
    private var homeScreen: MainActivity? = null

    private fun card(): View = homeScreen!!.findViewById<LinearLayout>(R.id.devices).getChildAt(0)

    // --- a hub user updating to 1.6 --------------------------------------------------------------

    @Test
    fun aHubUserOpensOnTheHomeScreenWithTheHubTile() {
        assumeTrue("set DROPLET_TEST_HUB to run the migration test", hub.isNotEmpty())
        // as 1.5 left things: a hub, a device token, and no "no hub" choice ever made
        val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val res = http.newCall(Request.Builder().url("$hub/api/device")
            .post(JSONObject().put("name", "robo-upgrade-${System.nanoTime() % 100000}").toString()
                .toRequestBody("application/json".toMediaType())).build()).execute()
        val token = res.headers("Set-Cookie").first { it.startsWith("droplet_device=") }.substringAfter('=').substringBefore(';')
        res.close()
        Prefs.hubUrl = hub
        Prefs.hubName = "test-hub"
        Hub.setToken(token)
        assertFalse(Prefs.noHub)
        assertTrue(Prefs.isSetUp)

        val homeCtl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val home = homeCtl.get()
        idle()
        assertFalse("straight to the home screen, no setup", home.isFinishing)
        assertTrue("the Hub tile", home.visible(R.id.tile_hub))
        // the tile names the hub: "test-hub" until it answers, then the name it reports for itself
        val tileSub = home.text(R.id.tile_hub_sub)
        assertTrue("the tile names the hub, not a placeholder: '$tileSub'",
            tileSub.startsWith("test-hub") || tileSub.startsWith(Router.hubLabel()))
        home.findViewById<View>(R.id.tile_hub).performClick()
        val open = shadowOf(home).nextStartedActivity
        assertEquals(HubActivity::class.java.name, open.component?.className)

        // the web app opens on the hub, with the way back home
        val webCtl = Robolectric.buildActivity(HubActivity::class.java, open).setup()
        val web = webCtl.get()
        // whichever route the router picked (loopback here, the LAN address on a real network),
        // the page must come from this hub: same port, never somewhere else
        // the router may prefer the hub's pinned LAN HTTPS port over the plain one it was given
        val webView = web.findViewById<android.webkit.WebView>(R.id.web)
        fun loaded(u: String?) = u != null && (u.startsWith(hub) || u.startsWith(Router.current()?.base ?: hub))
        var url: String? = null
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            idle()
            url = shadowOf(webView).lastLoadedUrl
            if (loaded(url)) break
            Thread.sleep(100)
        }
        assertTrue("the page loads from the hub, but the web view has: $url", loaded(url))
        val barTitle = web.text(R.id.bar_title)
        assertTrue("the bar names the hub: '$barTitle'",
            barTitle == "Hub · test-hub" || barTitle == "Hub · " + Router.hubLabel())
        web.findViewById<View>(R.id.home).performClick()
        assertTrue("the arrow goes back home", web.isFinishing)
        webCtl.pause().stop().destroy()

        // a notification's link into the web app still lands there
        val link = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_PATH, "/#chat-0123456789abcdef")
        homeCtl.newIntent(link)
        val deep = shadowOf(home).nextStartedActivity
        assertEquals(HubActivity::class.java.name, deep.component?.className)
        assertEquals("/#chat-0123456789abcdef", deep.getStringExtra(MainActivity.EXTRA_PATH))
        homeCtl.pause().stop().destroy()
        failed = false
    }
}

/** Files handed to the app as content URIs, the way a document picker or MediaStore would. */
class TestFiles : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.droplet.test.files"
        fun uri(f: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.path)}")
        private fun file(uri: Uri) = File(Uri.decode(uri.path!!.removePrefix("/")))
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun getType(uri: Uri) = if (file(uri).name.endsWith(".txt")) "text/plain" else "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
