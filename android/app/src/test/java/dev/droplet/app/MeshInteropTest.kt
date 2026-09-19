package dev.droplet.app

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.MeshTls
import dev.droplet.app.mesh.TrustList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The phone's mesh peer against the real Linux reference peer, both ways
 * (docs/mesh.md §9). The phone is the app's own code on Robolectric (Mesh,
 * the node, the bridges); the Linux agent is `droplet-agent run --dry-run`
 * in a throwaway HOME, driven through its control socket and, for raw
 * links, its own TLS and WebSocket code (src/test/python/mesh_agent.py).
 * Everything goes over this machine's LAN address.
 *
 *   python3 -m venv /tmp/v && /tmp/v/bin/pip install ./agent
 *   DROPLET_TEST_AGENT_PY=/tmp/v/bin/python ./gradlew testReleaseUnitTest --tests '*MeshInteropTest*'
 *
 * The roster test also needs a throwaway hub, and its pid, since it stops it:
 *
 *   DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8881 DROPLET_LAN_TLS_PORT=8882 DROPLET_HOST=0.0.0.0 DROPLET_PUSH=0 python app.py &
 *   DROPLET_TEST_HUB=http://127.0.0.1:8881 DROPLET_TEST_HUB_PID=$! DROPLET_TEST_AGENT_PY=… ./gradlew …
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshInteropTest {
    private val python = System.getProperty("droplet.testAgentPy").orEmpty()
    private val hub = System.getProperty("droplet.testHub").orEmpty()
    private val hubPid = System.getProperty("droplet.testHubPid").orEmpty()
    private val script = File("src/test/python/mesh_agent.py").absoluteFile
    private lateinit var app: Application
    private lateinit var tmp: File
    private lateinit var lan: String
    private val agents = mutableListOf<Agent>()
    private val phoneLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var phonePort = 0

    @Before
    fun setUp() {
        assumeTrue("set DROPLET_TEST_AGENT_PY to run the mesh interop tests", python.isNotEmpty())
        app = ApplicationProvider.getApplicationContext()
        tmp = File(System.getProperty("droplet.testScratch").orEmpty().ifEmpty { System.getProperty("java.io.tmpdir") },
            "mi-" + System.nanoTime().toString(36)).apply { mkdirs() }
        lan = System.getProperty("droplet.testLanIp").orEmpty().ifEmpty { lanAddress() }
        // an ordinary Wi-Fi network (Robolectric's default has no INTERNET capability)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val nc = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(nc).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(nc).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, nc)
        Router.discover = { _, _ -> emptyList() }

        // the phone: its own code, with test seams only where Robolectric has no Android service
        MeshIdentity.keystoreAllowed = false
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.directoryFactory = { null }
        phonePort = ServerSocket(0).use { it.localPort }   // fixed across restarts, so peers find it again
        Mesh.portOverride = phonePort
        Mesh.addresses = { listOf(lan) }
        Mesh.retryMs = 3_000
        Mesh.log = { phoneLog += it; println("phone: $it") }
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
        Mesh.release("test")
        Live.release("test")
        Router.reset()
        Hub.clearToken()
        Prefs.forgetHub()
        agents.forEach { it.stop() }
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.addresses = null
        if (!failed) tmp.deleteRecursively()
    }

    private var failed = true

    // --- helpers --------------------------------------------------------------------

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

    private fun py(vararg args: String, input: String? = null, timeoutS: Long = 90): String {
        val p = ProcessBuilder(listOf(python, script.path) + args).redirectErrorStream(true).start()
        input?.let { p.outputStream.write(it.toByteArray()) }
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("mesh_agent.py ${args.first()} timed out", p.waitFor(timeoutS, TimeUnit.SECONDS))
        return out.trim()
    }

    private inner class Agent(val name: String, maxRate: Int = 0) {
        val root = File(tmp, name)
        // the control socket's path must stay short (Unix sockets): a runtime dir of its own under /tmp
        val runtime = File("/tmp/claude-1000", "dr-" + name + "-" + System.nanoTime().toString(36))
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

        fun stop(kill: Boolean = false) {
            val p = proc ?: return
            if (kill) p.destroyForcibly() else p.destroy()
            p.waitFor(15, TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly().waitFor()
            proc = null
            if (!kill) runtime.deleteRecursively()
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
        fun trusts(fp: String): String? = File(root, "h/.config/droplet-agent/mesh/trust.json").let { f ->
            if (!f.exists()) null else JSONObject(f.readText()).getJSONObject("peers").optJSONObject(fp)?.getString("source")
        }
    }

    private fun agent(name: String, maxRate: Int = 0) = Agent(name, maxRate).also { agents += it }

    private fun phone(): MeshNode {
        Mesh.hold("test")
        waitFor("the phone's mesh to start", 30_000) { Mesh.node != null }
        return Mesh.node!!
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bigFile(name: String, mb: Int): File = File(tmp, name).apply {
        val r = SecureRandom()
        outputStream().use { o -> repeat(mb) { o.write(ByteArray(1 shl 20).also { r.nextBytes(it) }) } }
    }

    private fun requestPhone(who: String?, path: String, method: String = "GET", body: String? = null): String =
        py("request", who ?: "-", lan, phonePort.toString(), method, path, *listOfNotNull(body).toTypedArray())

    /** Pairs the phone (initiator) with [a], checking both show the same code. */
    private fun phonePairs(node: MeshNode, a: Agent) {
        val og = node.pairStart(lan, a.port, a.fp)
        val incoming = a.status().getJSONArray("incoming")
        assertEquals(1, incoming.length())
        assertEquals("both sides show the same code", og.code, incoming.getJSONObject(0).getString("code"))
        assertEquals("robo-phone", incoming.getJSONObject(0).getString("name"))
        val done = java.util.concurrent.CountDownLatch(1)
        node.pairConfirm(og.request!!, true) { done.countDown() }
        val ans = a.ctl(JSONObject().put("cmd", "pair-answer").put("request", incoming.getJSONObject(0).getString("request")).put("accept", true))
        assertEquals("accepted", ans.getString("state"))
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(MeshPairing.ACCEPTED, og.state)
        assertEquals(TrustList.SOURCE_PAIRED, node.trust.get(a.fp)?.source)
        assertEquals("paired", a.trusts(node.identity.fp))
    }

    // --- no hub: the phone and a Linux agent, directly -------------------------------------

    @Test
    fun directWithTheLinuxAgent() {
        val a = agent("linux-a", maxRate = 4 * 1024 * 1024)
        a.start()
        val node = phone()
        val clipboard = app.getSystemService(ClipboardManager::class.java)

        // --- TLS: fingerprints both ways, strangers refused ---------------------------
        assertEquals("the agent sees the phone's certificate", node.identity.fp, py("handshake", lan, phonePort.toString()))
        assertEquals("the agent's untrusted certificate fails the phone's handshake",
            true, requestPhone(a.root.path, "/mesh/files/" + "0".repeat(32)).startsWith("refused"))
        assertEquals("403", requestPhone(null, "/mesh"))
        assertEquals("403", requestPhone(null, "/mesh/files/" + "0".repeat(32)))
        assertEquals("400", requestPhone(null, "/mesh/pair", "POST", "{}"))
        val stranger = try {
            MeshTls.client(node.identity, a.fp).newCall(Request.Builder().url("https://$lan:${a.port}/mesh/files/" + "0".repeat(32)).build())
                .execute().use { it.code }
        } catch (e: IOException) {
            null
        }
        assertNull("the phone's untrusted certificate fails the agent's handshake", stranger)
        try {
            MeshTls.client(node.identity, "ab".repeat(32)).newCall(Request.Builder().url("https://$lan:${a.port}/mesh/pair").build()).execute().close()
            throw AssertionError("a server with another certificate was accepted")
        } catch (e: IOException) {
            assertNotNull(dev.droplet.app.mesh.MeshTlsErrors.mismatch(e))
        }

        // --- pairing: the phone asks -------------------------------------------------
        phonePairs(node, a)

        // --- text, both ways --------------------------------------------------------------
        val t1 = node.sendText(a.fp, "hello from android")
        val j1 = node.awaitJob(t1.getString("id"), 20_000)!!
        assertEquals("done", j1.getString("state"))
        assertEquals("lan", j1.getString("route"))
        waitFor("the agent stores it") { a.chat().any { it.getString("body") == "hello from android" && it.getString("dir") == "in" } }
        val t2 = a.ctl(JSONObject().put("cmd", "text").put("peer", "robo-phone").put("body", "hello from linux").put("wait", 20))
        assertEquals(t2.toString(), "done", t2.getString("state"))
        assertEquals("lan", t2.getString("route"))
        assertTrue(node.chat.recent(a.fp).any { it.getString("body") == "hello from linux" && it.getString("dir") == "in" })

        // --- ring and clip, both ways -----------------------------------------------------
        assertEquals("lan", a.ctl(JSONObject().put("cmd", "ring").put("peer", "robo-phone")).optString("route"))
        waitFor("the phone rings") { Ringer.ringing?.from == "linux-a" }
        a.ctl(JSONObject().put("cmd", "ring").put("peer", "robo-phone").put("stop", true))
        waitFor("the phone stops") { Ringer.ringing == null }
        assertEquals("lan", node.ring(a.fp))
        waitFor("the agent rings") { "ring (dry run)" in a.logText() }
        node.ring(a.fp, stop = true)
        waitFor("the agent stops") { "ring stopped (dry run)" in a.logText() }
        a.ctl(JSONObject().put("cmd", "clip").put("peer", "robo-phone").put("text", "copied on linux"))
        waitFor("the phone's clipboard") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copied on linux" }
        assertEquals("lan", node.clip(a.fp, "copied on android"))
        waitFor("the agent's clipboard") { "would set 17 characters: 'copied on android'" in a.logText() }

        // --- media and rpc, answered by the phone's bridges ------------------------------------
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            "${app.packageName}/${MirrorService::class.java.name}")
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        Caps.isStorageManager = { true }
        Robolectric.setupContentProvider(LiveHubTest.FakeSms::class.java, "sms")
        Robolectric.setupContentProvider(LiveHubTest.FakeContacts::class.java, "com.android.contacts")
        val top = Environment.getExternalStorageDirectory()
        File(top, "Download").mkdirs()
        File(top, "Download/alpha.pdf").writeText("%PDF pretend")
        val media = a.ctl(JSONObject().put("cmd", "send").put("peer", "robo-phone")
            .put("msg", JSONObject().put("t", "media").put("action", "volume").put("value", 0.4)))
        assertEquals(media.toString(), "lan", media.getString("route"))
        val am = app.getSystemService(AudioManager::class.java)
        waitFor("the phone's media bridge sets the volume") {
            am.getStreamVolume(AudioManager.STREAM_MUSIC) == Math.round(0.4 * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt()
        }
        val replies = py("link", a.root.path, lan, phonePort.toString(), node.identity.fp,
            JSONObject().put("t", "rpc").put("id", "r1").put("method", "files.roots").toString(),
            JSONObject().put("t", "rpc").put("id", "r2").put("method", "sms.threads").put("params", JSONObject().put("limit", 10)).toString(),
            JSONObject().put("t", "rpc").put("id", "r3").put("method", "files.get").put("params", JSONObject().put("path", "/Download/alpha.pdf")).toString(),
            JSONObject().put("t", "rpc").put("id", "r4").put("method", "files.list").put("params", JSONObject().put("path", "/Download/../x")).toString(),
            JSONObject().put("t", "input").put("ev", JSONArray().put(JSONObject().put("k", "key").put("key", "F5"))).toString(),
            JSONObject().put("t", "ping").toString(),
        ).lines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        fun reply(id: String) = replies.first { it.optString("t") == "rpc-result" && it.optString("id") == id }
        assertEquals("welcome", replies.first().getString("t"))
        assertEquals(node.peerId, replies.first().getString("id"))
        assertTrue(reply("r1").getJSONObject("result").getJSONArray("roots").toString().contains("/Download"))
        assertEquals(2, reply("r2").getJSONObject("result").getJSONArray("threads").length())
        assertTrue(reply("r3").toString(), reply("r3").getJSONObject("result").getBoolean("ok"))
        assertTrue(reply("r4").has("error"))
        assertTrue("input is refused cleanly", replies.any { it.optString("t") == "error" && it.optString("re") == "input" })
        assertTrue(replies.any { it.optString("t") == "pong" })
        assertTrue("files.get comes back to the requester as a mesh file", replies.any { it.optString("t") == "offer" && it.optString("name") == "alpha.pdf" })
        assertEquals("%PDF pretend", File(a.root, "link-downloads/alpha.pdf").readText())

        // --- a 20 MB file, phone → agent, interrupted and resumed -------------------------------
        val big = bigFile("big-out.bin", 20)
        node.offerRate = 4L * 1024 * 1024
        val job = node.sendFile(a.fp, big.path, "big-out.bin", "application/octet-stream", big.length())
        fun agentPart() = a.downloads().listFiles()?.firstOrNull { it.name.endsWith(".part") }?.length() ?: 0L
        waitFor("the transfer is under way", 30_000) { agentPart() > 5L * 1024 * 1024 }
        a.stop(kill = true)   // the receiver dies mid-transfer
        val cut = agentPart()
        assertTrue("interrupted part-way ($cut)", cut in 1 until big.length())
        a.start()
        val done = node.awaitJob(job.getString("id"), 120_000)!!
        waitFor("delivered", 120_000) { node.outbox.get(job.getString("id"))?.getString("state") == "done" }
        assertEquals(sha(big), sha(File(a.downloads(), "big-out.bin")))
        val resumed = Regex("resuming \\S+ at byte (\\d+)").find(a.logText())
        assertNotNull("the agent resumed rather than starting again", resumed)
        assertTrue(resumed!!.groupValues[1].toLong() >= cut - 1)
        assertTrue(done.toString(), a.downloads().listFiles()!!.none { it.name.startsWith(".droplet-") })
        node.offerRate = 0

        // --- a 20 MB file, agent → phone, interrupted (the phone's mesh restarts) and resumed ----------
        val big2 = bigFile("big-in.bin", 20)
        val sent = a.ctl(JSONObject().put("cmd", "send-file").put("peer", "robo-phone").put("path", big2.path).put("wait", 0))
        val incoming = File(Mesh.dir(), "incoming")
        fun phonePart() = incoming.listFiles()?.firstOrNull { it.name.endsWith(".part") }?.length() ?: 0L
        waitFor("the phone receives", 30_000) { phonePart() > 5L * 1024 * 1024 }
        Mesh.release("test")   // the phone's mesh stops mid-transfer
        Thread.sleep(500)
        val cut2 = phonePart()
        assertTrue("interrupted part-way ($cut2)", cut2 in 1 until big2.length())
        val node2 = phone()
        val saved = File(tmp, "phone-downloads/big-in.bin")
        waitFor("the agent offers it again and the phone finishes", 120_000) { saved.exists() && saved.length() == big2.length() }
        assertEquals(sha(big2), sha(saved))
        assertTrue("the phone resumed rather than starting again",
            phoneLog.any { Regex("resuming \\S+ at byte (\\d+)").find(it)?.groupValues?.get(1)?.toLong()?.let { n -> n >= cut2 - 1 } == true })
        val sentJob = a.ctl(JSONObject().put("cmd", "job").put("id", sent.getString("id")).put("wait", 30))
        assertEquals(sentJob.toString(), "done", sentJob.getString("state"))

        // --- unpair, and pairing the other way: the agent asks ----------------------------------
        assertTrue("the agent was told", node2.unpair(a.fp))
        waitFor("gone from both trust lists", 10_000) { a.trusts(node2.identity.fp) == null && node2.trust.get(a.fp) == null }
        assertTrue(requestPhone(a.root.path, "/mesh").startsWith("refused"))

        val start = a.ctl(JSONObject().put("cmd", "pair-start").put("target", "$lan:$phonePort"))
        assertEquals(start.toString(), node2.identity.fp, start.getJSONObject("peer").getString("fp"))
        val req = node2.incoming.waiting().single()
        assertEquals("both sides show the same code", start.getString("code"), req.code)
        assertEquals("linux-a", req.name)
        a.ctl(JSONObject().put("cmd", "pair-confirm").put("request", start.getString("request")).put("yes", true))
        node2.pairAnswer(req.request, true)
        waitFor("the agent sees it accepted") {
            a.ctl(JSONObject().put("cmd", "pair-status").put("request", start.getString("request"))).getString("state") == "accepted"
        }
        assertEquals("paired", a.trusts(node2.identity.fp))
        val t3 = a.ctl(JSONObject().put("cmd", "text").put("peer", "robo-phone").put("body", "paired the other way").put("wait", 20))
        assertEquals("done", t3.getString("state"))
        failed = false
    }

    // --- with a hub: the roster, then the hub goes down ----------------------------------------

    @Test
    fun rosterThroughTheHubThenWithout() {
        assumeTrue("set DROPLET_TEST_HUB and DROPLET_TEST_HUB_PID to run the roster test", hub.isNotEmpty() && hubPid.isNotEmpty())
        val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        // the agent joins the hub (from 127.0.0.1, which the hub trusts as its own machine)
        val b = agent("linux-b")
        val setup = ProcessBuilder(python, script.path, "cli", b.root.path, "setup", "--hub", hub, "--name", "linux-b-${System.nanoTime() % 100000}")
            .redirectErrorStream(true).start()
        val setupOut = setup.inputStream.bufferedReader().readText()
        assertTrue(setupOut, setup.waitFor(60, TimeUnit.SECONDS) && setup.exitValue() == 0)
        b.start()
        // the phone joins too, as a device named on the hub
        val res = http.newCall(Request.Builder().url("$hub/api/device")
            .post(JSONObject().put("name", "robo-phone").toString().toRequestBody("application/json".toMediaType())).build()).execute()
        val token = res.headers("Set-Cookie").first { it.startsWith("droplet_device=") }.substringAfter('=').substringBefore(';')
        res.close()
        Prefs.hubUrl = hub
        Hub.setToken(token)
        val node = phone()
        Live.hold("test")
        waitFor("the phone's live connection", 30_000) { Live.state.value.connected }

        // the roster: each trusts the other through the hub, with no pairing
        waitFor("the phone trusts the agent through the roster", 30_000) { node.trust.get(b.fp)?.source == TrustList.SOURCE_ROSTER }
        waitFor("the agent trusts the phone through the roster", 30_000) { b.trusts(node.identity.fp) == "roster" }
        val entry = node.trust.get(b.fp)!!
        assertEquals(Prefs.hubId, entry.hub)
        assertEquals("the phone's peer id is its hub device id", Live.state.value.deviceId, node.peerId)
        val viaHub = node.sendText(b.fp, "roster hello")
        val j = node.awaitJob(viaHub.getString("id"), 20_000)!!
        assertEquals("done", j.getString("state"))
        assertEquals("directly, not through the hub", "lan", j.getString("route"))

        // the hub goes down: direct keeps working, both ways
        ProcessBuilder("kill", "-TERM", hubPid).start().waitFor()
        waitFor("the hub is down", 15_000) {
            runCatching { http.newCall(Request.Builder().url("$hub/api/hub/info").build()).execute().close() }.isFailure
        }
        val t = node.sendText(b.fp, "no hub needed")
        assertEquals("lan", node.awaitJob(t.getString("id"), 20_000)!!.getString("route"))
        waitFor("the agent has it") { b.chat().any { it.getString("body") == "no hub needed" } }
        val back = b.ctl(JSONObject().put("cmd", "text").put("peer", node.peerId).put("body", "still here").put("wait", 20))
        assertEquals(back.toString(), "lan", back.getString("route"))
        assertTrue(node.chat.recent(b.fp).any { it.getString("body") == "still here" })
        val f = bigFile("small.bin", 3)
        val fj = node.sendFile(b.fp, f.path, "small.bin", "application/octet-stream", f.length())
        assertEquals("lan", node.awaitJob(fj.getString("id"), 60_000)!!.getString("route"))
        waitFor("the agent saved it") { File(b.downloads(), "small.bin").let { it.exists() && sha(it) == sha(f) } }
        assertEquals("lan", node.ring(b.fp))
        waitFor("the agent rings") { "ring (dry run)" in b.logText() }
        failed = false
    }
}
