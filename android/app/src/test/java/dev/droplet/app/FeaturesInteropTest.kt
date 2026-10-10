package dev.droplet.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.Outbox
import dev.droplet.app.mesh.Transfers
import dev.droplet.app.mesh.TrustList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Progress and Cancel, links, rename and nicknames (docs/mesh.md §9.10)
 * between the phone and the real Linux reference peer, both ways: the
 * phone's own code on Robolectric (Mesh, the node, its notifications), the
 * agent `droplet-agent run --dry-run` in a throwaway HOME, driven through its
 * control socket (src/test/python/mesh_agent.py), over this machine's LAN
 * address. As MeshInteropTest:
 *
 *   python3 -m venv /tmp/v && /tmp/v/bin/pip install ./agent
 *   DROPLET_TEST_AGENT_PY=/tmp/v/bin/python ./gradlew testDebugUnitTest --tests '*FeaturesInteropTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeaturesInteropTest {
    private val python = System.getProperty("droplet.testAgentPy").orEmpty()
    private val script = File("src/test/python/mesh_agent.py").absoluteFile
    private lateinit var app: Application
    private lateinit var tmp: File
    private lateinit var lan: String
    private val agents = mutableListOf<Agent>()
    private var phonePort = 0
    private var failed = true

    @Before
    fun setUp() {
        assumeTrue("set DROPLET_TEST_AGENT_PY to run the mesh interop tests", python.isNotEmpty())
        app = ApplicationProvider.getApplicationContext()
        tmp = File(System.getProperty("droplet.testScratch").orEmpty().ifEmpty { System.getProperty("java.io.tmpdir") },
            "fi-" + System.nanoTime().toString(36)).apply { mkdirs() }
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
        Mesh.directoryFactory = { null }
        phonePort = ServerSocket(0).use { it.localPort }
        Mesh.portOverride = phonePort
        Mesh.addresses = { listOf(lan) }
        Mesh.retryMs = 3_000
        Mesh.log = { println("phone: $it") }
        val downloads = File(tmp, "phone-downloads").apply { mkdirs() }
        Mesh.saver = { _, part, name, _ ->
            val n = MeshDownloads.unique(name) { !File(downloads, it).exists() }
            part.copyTo(File(downloads, n))
            Mesh.Saved(null, File(downloads, n).path)
        }
        Prefs.meshEnabled = true
        Prefs.meshDeviceName = "robo-phone"
    }

    @After
    fun tearDown() {
        if (python.isEmpty()) return
        Mesh.inForeground = { false }
        Mesh.release("test")
        Mesh.resetForTests()
        Router.reset()
        agents.forEach { it.stop() }
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.addresses = null
        if (!failed) tmp.deleteRecursively()
    }

    // --- helpers (as MeshInteropTest) ---------------------------------------------------------

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

    private inner class Agent(val name: String, maxRate: Int = 0) {
        val root = File(tmp, name)
        val runtime = File("/tmp/claude-1000", "fi-" + name + "-" + System.nanoTime().toString(36))
        var proc: Process? = null
        val log = File(root, "agent.log")

        init {
            root.mkdirs()
            py("init", root.path, name, runtime.path)
            if (maxRate > 0) {
                val cfg = File(root, "h/.config/droplet-agent/config.json")
                val j = JSONObject(cfg.readText())
                j.getJSONObject("mesh").put("max_rate", maxRate)
                cfg.writeText(j.toString())
            }
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
        val port get() = status().getInt("port")
        fun logText() = if (log.exists()) log.readText() else ""
        fun chat(): List<JSONObject> = File(root, "h/.local/share/droplet-agent/mesh/chat.jsonl").let { f ->
            if (f.exists()) f.readLines().map { JSONObject(it) } else emptyList()
        }
        fun downloads() = File(root, "h/Downloads/droplet")
        fun peer(fp: String): JSONObject? = status().optJSONArray("peers")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("fp") == fp }
        }
        fun transfer(id: String): JSONObject? = ctl(JSONObject().put("cmd", "transfers")).optJSONArray("transfers")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        }
        fun noPartial() = downloads().listFiles().orEmpty().none { it.name.startsWith(".droplet-") }
    }

    private fun phone(): MeshNode {
        Mesh.hold("test")
        waitFor("the phone's mesh to start", 30_000) { Mesh.node != null }
        return Mesh.node!!
    }

    private fun bigFile(name: String, mb: Int): File = File(tmp, name).apply {
        val r = SecureRandom()
        outputStream().use { o -> repeat(mb) { o.write(ByteArray(1 shl 20).also { r.nextBytes(it) }) } }
    }

    private fun phonePairs(node: MeshNode, a: Agent) {
        val og = node.pairStart(lan, a.port, a.fp)
        val incoming = a.status().getJSONArray("incoming")
        val done = java.util.concurrent.CountDownLatch(1)
        node.pairConfirm(og.request!!, true) { done.countDown() }
        val ans = a.ctl(JSONObject().put("cmd", "pair-answer").put("request", incoming.getJSONObject(0).getString("request")).put("accept", true))
        assertEquals("accepted", ans.getString("state"))
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(MeshPairing.ACCEPTED, og.state)
        assertEquals(TrustList.SOURCE_PAIRED, node.trust.get(a.fp)?.source)
    }

    private fun notification(tag: String): android.app.Notification? {
        val nm = app.getSystemService(NotificationManager::class.java)
        return shadowOf(nm).activeNotifications.firstOrNull { it.tag == tag }?.notification
    }

    private fun phonePartial() = File(Mesh.dir(), "incoming").listFiles().orEmpty().any { it.name.startsWith(".droplet-") }

    // --- the test ----------------------------------------------------------------------------

    @Test
    fun progressCancelLinksAndNamesWithTheLinuxAgent() {
        val a = Agent("linux-a", maxRate = 1024 * 1024).also { agents += it }
        a.start()
        val node = phone()
        phonePairs(node, a)

        // --- each says what it understands ------------------------------------------------------
        node.direct(a.fp)
        waitFor("the agent's features") { "link" in node.featuresOf(a.fp) && "cancel" in node.featuresOf(a.fp) }
        waitFor("the phone's features, as the agent sees them") {
            a.peer(node.identity.fp)?.optJSONArray("features")?.toString()?.contains("rename") == true
        }

        // --- names: the phone renamed, the agent renamed, and a nickname that stays here ------------
        assertEquals(1, Mesh.renamePhone("robo  renamed"))
        assertEquals("robo renamed", Prefs.meshDeviceName)
        waitFor("the agent hears the new name") { a.peer(node.identity.fp)?.optString("name") == "robo renamed" }
        val r = a.ctl(JSONObject().put("cmd", "rename").put("name", "linux-renamed"))
        assertEquals(r.toString(), "linux-renamed", r.getString("name"))
        waitFor("the phone hears the agent's new name") { node.trust.get(a.fp)?.name == "linux-renamed" }
        Mesh.setNickname(a.fp, "the laptop")
        assertEquals("the laptop", node.trust.get(a.fp)!!.label)
        assertFalse("never sent", a.status().toString().contains("the laptop"))

        // --- a link, phone → agent (your own device: it opens there) ---------------------------
        val sent = Mesh.sendLink(a.fp, "https://example.com/from-phone")
        assertEquals("link", sent.how)
        waitFor("the agent has it as a link") {
            a.chat().any { it.optString("kind") == "link" && it.optString("body") == "https://example.com/from-phone" }
        }
        waitFor("and opened it") { "would open (dry run): https://example.com/from-phone" in a.logText() }

        // --- a link, agent → phone: droplet in the background, so a notification opens it ---------
        val tag = "chat-${a.fp.take(16)}"
        val l1 = a.ctl(JSONObject().put("cmd", "link").put("peer", "robo renamed").put("url", "https://example.com/from-linux"))
        assertEquals(l1.toString(), "link", l1.getString("how"))
        waitFor("the phone has it") { node.chat.recent(a.fp).any { it.optString("kind") == "link" && it.optString("body") == "https://example.com/from-linux" } }
        waitFor("a notification that opens it") { notification(tag) != null }
        val n1 = notification(tag)!!
        assertEquals("the laptop sent a link", n1.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        val tap = shadowOf(n1.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals("https://example.com/from-linux", tap.dataString)
        // with one of droplet's screens on show, it opens in the browser at once
        Mesh.inForeground = { true }
        a.ctl(JSONObject().put("cmd", "link").put("peer", "robo renamed").put("url", "https://example.com/now"))
        waitFor("the browser opens it") {
            generateSequence { shadowOf(app).nextStartedActivity }.any { it.action == Intent.ACTION_VIEW && it.dataString == "https://example.com/now" }
        }
        Mesh.inForeground = { false }
        // and a bad one is refused: never stored, never opened
        val bad = a.ctl(JSONObject().put("cmd", "link").put("peer", "robo renamed").put("url", "javascript:alert(1)"))
        assertTrue(bad.toString(), bad.optString("error").contains("only web links"))

        // --- a file, phone → agent, cancelled by the agent (the receiver) ----------------------------
        node.offerRate = 1024L * 1024
        val f1 = bigFile("to-agent-1.bin", 8)
        val j1 = node.sendFile(a.fp, f1.path, f1.name, "application/octet-stream", f1.length()).getString("id")
        waitFor("the agent receives", 30_000) { (a.transfer(j1)?.optLong("done") ?: 0L) > 300_000 }
        waitFor("the phone shows progress") { (node.transfers.get(j1)?.done ?: 0L) > 300_000 }
        val c1 = a.ctl(JSONObject().put("cmd", "cancel").put("id", j1))
        assertEquals(c1.toString(), "in", c1.getString("dir"))
        waitFor("the phone is told", 15_000) { node.outbox.get(j1)?.optString("state") == Outbox.CANCELLED }
        assertEquals("the laptop cancelled it", node.outbox.get(j1)!!.getString("error"))
        assertEquals(Transfers.CANCELLED, node.transfers.get(j1)!!.state)
        waitFor("the agent dropped its partial file") { a.noPartial() }
        assertFalse(File(a.downloads(), f1.name).exists())

        // --- a file, phone → agent, cancelled on the phone (the sender) ---------------------------------
        val f2 = bigFile("to-agent-2.bin", 8)
        val j2 = node.sendFile(a.fp, f2.path, f2.name, "application/octet-stream", f2.length()).getString("id")
        waitFor("the agent receives", 30_000) { (a.transfer(j2)?.optLong("done") ?: 0L) > 300_000 }
        assertEquals("out", Mesh.cancelTransfer(j2).dir)
        waitFor("the agent hears", 15_000) { a.transfer(j2)?.optString("state") == "cancelled" }
        assertEquals("robo renamed cancelled it", a.transfer(j2)!!.optString("error"))
        waitFor("the agent dropped its partial file") { a.noPartial() }
        assertEquals("cancelled here", node.outbox.get(j2)!!.getString("error"))
        node.offerRate = 0

        // --- a file, agent → phone, cancelled by the agent (the sender) ------------------------------------
        val f3 = bigFile("from-agent-1.bin", 8)
        val s3 = a.ctl(JSONObject().put("cmd", "send-file").put("peer", "robo renamed").put("path", f3.path).put("wait", 0))
        val j3 = s3.getString("id")
        waitFor("the phone receives", 30_000) { (node.transfers.get(j3)?.done ?: 0L) > 300_000 }
        // its notification has the progress, and a Cancel
        waitFor("the progress notification") { notification("recv-$j3") != null }
        assertEquals(app.getString(R.string.cancel), notification("recv-$j3")!!.actions.single().title.toString())
        a.ctl(JSONObject().put("cmd", "cancel").put("id", j3))
        waitFor("the phone hears", 15_000) { node.transfers.get(j3)?.state == Transfers.CANCELLED }
        assertEquals("the laptop cancelled it", node.transfers.get(j3)!!.error)
        waitFor("no partial file on the phone") { !phonePartial() }
        waitFor("the notification goes") { notification("recv-$j3") == null }
        assertFalse(File(tmp, "phone-downloads/${f3.name}").exists())

        // --- a file, agent → phone, cancelled on the phone (the receiver), from the notification -------------
        val f4 = bigFile("from-agent-2.bin", 8)
        val j4 = a.ctl(JSONObject().put("cmd", "send-file").put("peer", "robo renamed").put("path", f4.path).put("wait", 0)).getString("id")
        waitFor("the phone receives", 30_000) { (node.transfers.get(j4)?.done ?: 0L) > 300_000 }
        waitFor("the progress notification") { notification("recv-$j4") != null }
        val cancel = shadowOf(notification("recv-$j4")!!.actions.single().actionIntent).savedIntent
        TransferCancelReceiver().onReceive(app, cancel)
        waitFor("cancelled here") { node.transfers.get(j4)?.state == Transfers.CANCELLED }
        waitFor("the agent is told", 15_000) {
            a.ctl(JSONObject().put("cmd", "job").put("id", j4).put("wait", 1)).optString("state") == "cancelled"
        }
        assertEquals("robo renamed cancelled it", a.ctl(JSONObject().put("cmd", "job").put("id", j4)).optString("why"))
        waitFor("no partial file on the phone") { !phonePartial() }
        assertFalse(File(tmp, "phone-downloads/${f4.name}").exists())
        failed = false
    }
}
