package dev.droplet.app.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The phone's TV remote against tests/fake_tv.py: a pretend Google TV built
 * from androidtvremote2's own protobufs, speaking the TV side of the protocol
 * over real TLS on real sockets, as a separate process. It computes the
 * pairing secret itself from both certificates, trusts droplet's
 * certificate only after pairing, pings, and reports state like a TV.
 *
 *   DROPLET_TEST_TV_PY=<hub venv>/bin/python ./gradlew testReleaseUnitTest --tests '*TvFakeTvTest*'
 *
 * What only the real TV can show (timing, what a given key does on a TCL)
 * is in the README's checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvFakeTvTest {
    private val python = System.getProperty("droplet.testTvPy").orEmpty()
    private val script = File("../../tests/fake_tv.py").absoluteFile
    private lateinit var tmp: File
    private lateinit var identity: TvIdentity
    private lateinit var certFile: File
    private lateinit var stateFile: File
    private var port = 0
    private var fake: Process? = null
    private val links = mutableListOf<TvLink>()

    private val fast = TvLink.Timing(connectMs = 3_000, startMs = 3_000, idleMs = 8_000, backoffFirstMs = 300,
        backoffMaxMs = 1_500, graceMs = 2_000)

    @Before
    fun setUp() {
        assumeTrue("set DROPLET_TEST_TV_PY to a Python with androidtvremote2 (the hub's venv)", python.isNotEmpty())
        assertTrue("fake_tv.py at $script", script.isFile)
        TvIdentity.keystoreAllowed = false
        tmp = File(System.getProperty("java.io.tmpdir"), "tvfake-" + System.nanoTime().toString(36)).apply { mkdirs() }
        identity = TvIdentity.loadOrCreate(File(tmp, "phone"))
        certFile = File(tmp, "client.pem").apply { writeText(identity.pem) }
        stateFile = File(tmp, "state.json")
        port = freePortPair()
    }

    @After
    fun tearDown() {
        if (python.isEmpty()) return
        links.forEach { it.stop() }
        fake?.let { stopFake(it) }
        tmp.deleteRecursively()
    }

    // --- the fake TV ----------------------------------------------------------------------

    private fun freePortPair(): Int {
        repeat(50) {
            val p = ServerSocket(0).use { it.localPort }
            if (p < 65000 && runCatching { ServerSocket(p + 1).close() }.isSuccess) return p
        }
        error("no free port pair")
    }

    private fun startFake(paired: Boolean = false): Process {
        stateFile.delete()
        val cmd = mutableListOf(python, script.path, "--client-cert", certFile.path, "--port", port.toString(),
            "--state", stateFile.path)
        if (paired) cmd += "--paired"
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(File(tmp, "fake.log")).start()
        fake = p
        waitFor("the fake TV listening", 15_000) { File(tmp, "fake.log").takeIf { it.exists() }?.readText()?.contains("listening") == true }
        return p
    }

    private fun stopFake(p: Process) {
        signal(p, "CONT")
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    private fun signal(p: Process, sig: String) {
        // Process.pid() is Java 9+, which the Android compile classpath doesn't show
        val pid = Process::class.java.getMethod("pid").invoke(p) as Long
        Runtime.getRuntime().exec(arrayOf("kill", "-$sig", pid.toString())).waitFor()
    }

    private fun state(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    private fun log(): List<List<Any?>> {
        val a = state().optJSONArray("log") ?: JSONArray()
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { e -> (0 until e.length()).map { e.get(it) } } }
    }

    private fun events(kind: String) = log().filter { it.firstOrNull() == kind }

    private fun waitFor(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (ok()) return
            Thread.sleep(50)
        }
        fail("timed out waiting for $what; fake log: ${File(tmp, "fake.log").takeIf { it.exists() }?.readText()?.takeLast(2000)}")
    }

    private fun codeOnScreen(): String {
        waitFor("a code on the fake TV's screen") { state().optString("code").length == 6 }
        return state().getString("code")
    }

    /** Pairs for real: the fake shows a code, droplet types it in. Returns the TV's certificate fingerprint. */
    private fun pair(): String {
        val pairing = TvPairing(identity, "127.0.0.1", port, "droplet (robo)")
        val started = pairing.start()
        assertEquals("Fake Google TV", started.certName)
        assertEquals("aa:bb:cc:dd:ee:01", started.mac)
        assertEquals("Fake Google TV", started.serverName)
        val r = pairing.finish(codeOnScreen())
        assertTrue(r is TvPairing.Result.Paired)
        waitFor("the fake to trust droplet") { state().optBoolean("paired") }
        return TvIdentity.sha256Hex((r as TvPairing.Result.Paired).serverCertDer)
    }

    private fun connect(pin: String): TvLink {
        val l = TvLink(identity, "127.0.0.1", port, pin, { println("link: $it") }, fast)
        links += l
        l.start()
        waitFor("the link to connect") { l.state.value.connected }
        return l
    }

    // --- pairing ------------------------------------------------------------------------------

    @Test
    fun pairsWithTheCodeOnScreen() {
        startFake()
        val pairing = TvPairing(identity, "127.0.0.1", port, "droplet (robo)")
        pairing.start()
        assertEquals(listOf("pairing_request", "droplet (robo)"), events("pairing_request").single())
        val code = codeOnScreen()

        // a typo: caught on the phone, nothing sent, the TV's code stays up
        val typo = (if (code[0] == 'F') "0" else "F") + code.substring(1)
        assertEquals(TvPairing.Result.WrongCode, pairing.finish(typo))
        Thread.sleep(300)
        assertEquals(code, state().optString("code"))
        assertTrue(events("bad_secret").isEmpty())
        assertFalse(state().optBoolean("paired"))

        // typed again, right
        val r = pairing.finish(code.lowercase())
        assertTrue(r is TvPairing.Result.Paired)
        waitFor("paired") { state().optBoolean("paired") }
        assertEquals(1, events("paired").size)
        // the pin is the certificate the fake presented on the pairing port (and presents on the remote port)
        val pin = TvIdentity.sha256Hex((r as TvPairing.Result.Paired).serverCertDer)
        val l = connect(pin)
        assertTrue(l.state.value.on == true)
    }

    @Test
    fun aCodeTheTvDoesntAcceptEndsThePairing() {
        startFake()
        val pairing = TvPairing(identity, "127.0.0.1", port, "droplet (robo)")
        pairing.start()
        val code = codeOnScreen()
        // a code whose check digits are right for another nonce: it passes the phone's
        // check, the secret goes out, and the TV turns it down
        val server = TvSecret.rsa(pairing.serverCert!!)
        var other: String? = null
        for (n in 0 until 65536) {
            val nonce = "%04X".format(n)
            if (nonce == code.substring(2)) continue
            val sha = MessageDigest.getInstance("SHA-256")
            listOf(identity.publicKey.modulus, identity.publicKey.publicExponent, server.modulus, server.publicExponent)
                .forEach { sha.update(TvSecret.unsigned(it)) }
            sha.update(byteArrayOf(nonce.substring(0, 2).toInt(16).toByte(), nonce.substring(2).toInt(16).toByte()))
            other = "%02X".format(sha.digest()[0].toInt() and 0xff) + nonce
            break
        }
        try {
            pairing.finish(other!!)
            fail("the TV accepted a wrong secret")
        } catch (e: TvException) {
            assertEquals(TvException.Kind.REFUSED, e.kind)
        }
        waitFor("the fake to log the bad secret") { events("bad_secret").isNotEmpty() }
        assertFalse(state().optBoolean("paired"))
        // start again: a new code, and it pairs
        pair()
    }

    @Test
    fun anUnpairedClientIsRefusedOnTheRemotePort() {
        startFake()
        val l = TvLink(identity, "127.0.0.1", port, "00".repeat(32), { println("link: $it") }, fast)
        links += l
        l.start()
        // the pin is wrong too, but the fake refuses the client first... or droplet the fake:
        // either way it stops and says why, rather than retrying forever
        waitFor("the link to give up") { l.state.value.phase in setOf(TvLink.Phase.NEEDS_PAIRING, TvLink.Phase.IDENTITY_CHANGED) }
        assertFalse(l.isRunning)
    }

    @Test
    fun unreachableTvIsReportedAndRetried() {
        val l = TvLink(identity, "127.0.0.1", port, "00".repeat(32), { println("link: $it") }, fast)
        links += l
        l.start()
        waitFor("can't reach", 8_000) { l.state.value.phase == TvLink.Phase.UNREACHABLE }
        assertTrue(l.isRunning)
        assertFalse(l.key(TvCatalog.keyCode("HOME")))
    }

    // --- the remote -----------------------------------------------------------------------------

    @Test
    fun keysTextAppsPowerAndState() {
        startFake()
        val pin = pair()
        val l = connect(pin)
        // the state the TV reported when the remote started
        waitFor("state") { l.state.value.volume != null && l.state.value.app != null }
        assertEquals(true, l.state.value.on)
        assertEquals("com.google.android.apps.tv.launcherx", l.state.value.app)
        assertEquals(TvLink.Volume(12, 100, false), l.state.value.volume)
        assertEquals("FakeTCL 43P", l.state.value.model)

        fun key(name: String) = assertTrue(l.key(TvCatalog.keyCode(name)))

        key("DPAD_UP")
        key("DPAD_RIGHT")
        waitFor("two keys") { events("key").size >= 2 }
        assertEquals(listOf("key", "DPAD_UP", "SHORT"), events("key")[0])
        assertEquals(listOf("key", "DPAD_RIGHT", "SHORT"), events("key")[1])

        // volume: the TV reports the new level back
        key("VOLUME_UP")
        waitFor("volume 13") { l.state.value.volume?.level == 13 }
        key("VOLUME_DOWN")
        key("VOLUME_DOWN")
        waitFor("volume 11") { l.state.value.volume?.level == 11 }
        key("MUTE")
        waitFor("muted") { l.state.value.volume?.muted == true }
        assertEquals(listOf("key", "VOLUME_MUTE", "SHORT"), events("key").last())

        // a long press: START_LONG now, END_LONG no sooner than LONG_MIN_MS after
        val t0 = System.currentTimeMillis()
        val press = l.startLong(TvCatalog.keyCode("DPAD_CENTER"))
        assertNotNull(press)
        l.endLong(press!!)   // let go at once: the end still waits
        waitFor("the long press to end") { events("key").lastOrNull() == listOf("key", "DPAD_CENTER", "END_LONG") }
        assertTrue("held ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 >= TvLink.LONG_MIN_MS)
        assertEquals(listOf("key", "DPAD_CENTER", "START_LONG"), events("key").dropLast(1).last())

        // text, as the IME batch edit the library sends (the fake focused a field)
        assertTrue(l.text(TvCatalog.checkText("hello Nairobi")))
        waitFor("text") { events("text").isNotEmpty() }
        assertEquals(listOf("text", "hello Nairobi"), events("text").single())

        // an app from the catalogue: the TV reports it in front
        val netflix = TvCatalog.launch("netflix") as TvCatalog.Launch.Link
        assertTrue(l.link(netflix.link))
        waitFor("netflix in front") { l.state.value.app == "com.netflix.ninja" }
        assertEquals(listOf("launch", "market://launch?id=com.netflix.ninja"), events("launch").single())
        assertEquals("Netflix", TvCatalog.appName(l.state.value.app))
        // an https link
        assertTrue(l.link((TvCatalog.launch("https://www.youtube.com/watch?v=abc") as TvCatalog.Launch.Link).link))
        waitFor("the link") { events("launch").size == 2 }
        assertEquals("https://www.youtube.com/watch?v=abc", events("launch")[1][1])
        // Home puts the launcher back
        key("HOME")
        waitFor("home") { l.state.value.app == "com.google.android.apps.tv.launcherx" }

        // power: off, then on, reported both ways
        key("POWER")
        waitFor("off") { l.state.value.on == false }
        assertTrue(l.state.value.connected)   // a TV in standby still talks
        key("POWER")
        waitFor("on") { l.state.value.on == true }
    }

    @Test
    fun pingsKeepTheConnectionUp() {
        startFake()
        val l = connect(pair())
        // quiet for longer than the idle limit: only the TV's pings (every 5 s) keep it up
        Thread.sleep(fast.idleMs + 4_000L)
        assertTrue(l.state.value.connected)
        assertEquals(1, events("remote_connected").size)
        assertTrue(events("remote_disconnected").isEmpty())
    }

    @Test
    fun reconnectsWhenTheTvGoesAwayAndComesBack() {
        val p = startFake()
        val l = connect(pair())
        val phases = java.util.Collections.synchronizedList(mutableListOf<TvLink.Phase>())
        val watcher = Thread {
            var last: TvLink.Phase? = null
            while (!Thread.currentThread().isInterrupted) {
                val ph = l.state.value.phase
                if (ph != last) { phases += ph; last = ph }
                try { Thread.sleep(20) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        // the TV goes away: frozen, as a TV that lost its network (no FIN, no RST, silence)
        signal(p, "STOP")
        waitFor("the link to notice", fast.idleMs + 3_000L) { !l.state.value.connected }
        waitFor("can't reach", 15_000) { l.state.value.phase == TvLink.Phase.UNREACHABLE }
        assertFalse(l.key(TvCatalog.keyCode("HOME")))   // nothing is queued for later
        // and comes back
        signal(p, "CONT")
        waitFor("back", 15_000) { l.state.value.connected }
        watcher.interrupt()
        watcher.join(2_000)
        // the watcher samples every 20 ms: record where the link is now, so a sample it missed doesn't count
        l.state.value.phase.let { if (phases.lastOrNull() != it) phases += it }
        assertTrue(phases.toString(), phases.containsAll(listOf(TvLink.Phase.CONNECTED, TvLink.Phase.CONNECTING,
            TvLink.Phase.UNREACHABLE)))
        assertEquals(TvLink.Phase.CONNECTED, phases.last())
        assertTrue(l.key(TvCatalog.keyCode("DPAD_DOWN")))
        waitFor("the key after reconnecting") { events("key").lastOrNull() == listOf("key", "DPAD_DOWN", "SHORT") }
        // a key while it was away never reached the TV
        assertTrue(events("key").none { it[1] == "HOME" })
    }

    @Test
    fun aTvThatForgetsDropletAsksForPairingAgain() {
        val p = startFake()
        val l = connect(pair())
        // the user removes droplet in the TV's settings
        signal(p, "USR1")
        waitFor("pair again", 10_000) { l.state.value.phase == TvLink.Phase.NEEDS_PAIRING }
        assertFalse(l.isRunning)
        assertTrue(events("refused_client").isNotEmpty())
        // pairing again brings it back, with the same certificates on both sides
        val pin = pair()
        val again = connect(pin)
        assertTrue(again.key(TvCatalog.keyCode("DPAD_LEFT")))
        waitFor("a key") { events("key").lastOrNull() == listOf("key", "DPAD_LEFT", "SHORT") }
    }

    @Test
    fun aDifferentTvAtTheSameAddressIsntTalkedTo() {
        var p = startFake()
        val l = connect(pair())
        // replaced by another TV (a reset one, or another device that took the address): a new certificate
        stopFake(p)
        p = startFake(paired = true)
        waitFor("the certificate check", 15_000) { l.state.value.phase == TvLink.Phase.IDENTITY_CHANGED }
        assertFalse(l.isRunning)
        // nothing reached the impostor
        assertTrue(events("remote_connected").isEmpty())
        assertTrue(p.isAlive)
    }

    // --- the app's own TV list, end to end --------------------------------------------------------

    @Test
    fun theAppMarksATvThatForgotIt() {
        val p = startFake()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        dev.droplet.app.Prefs.init(app)
        Tv.init(app)
        Tv.dirOverride = File(tmp, "phone")
        Tv.timing = fast
        Tv.browser = { _, _ -> dev.droplet.app.Discovery.Handle { } }
        try {
            val pairing = TvPairing(Tv.identity(), "127.0.0.1", port, Tv.clientName())
            val started = pairing.start()
            val r = pairing.finish(codeOnScreen()) as TvPairing.Result.Paired
            val tv = Tv.adopt("127.0.0.1", port, started.certName ?: "TV", started.mac, null, r.serverCertDer)
            val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            main.idle()
            Tv.hold("test")
            val until = System.currentTimeMillis() + 10_000
            while (Tv.link.value?.state?.value?.connected != true && System.currentTimeMillis() < until) {
                main.idle(); Thread.sleep(50)
            }
            assertTrue(Tv.link.value?.state?.value?.connected == true)
            // the model the TV reported is kept with it
            val until1 = System.currentTimeMillis() + 5_000
            while (Tv.store.get(tv.id)?.model == null && System.currentTimeMillis() < until1) Thread.sleep(50)
            assertEquals("FakeTCL 43P", Tv.store.get(tv.id)?.model)
            signal(p, "USR1")
            val until2 = System.currentTimeMillis() + 10_000
            while (Tv.store.get(tv.id)?.paired != false && System.currentTimeMillis() < until2) {
                main.idle(); Thread.sleep(50)
            }
            val lost = Tv.store.get(tv.id)!!
            assertFalse(lost.paired)
            assertEquals(Tv.LOST_FORGOT, lost.lost)
            Tv.release("test")
            main.idleFor(java.time.Duration.ofSeconds(6))
            assertEquals(null, Tv.link.value)
        } finally {
            Tv.dirOverride = null
            Tv.browser = null
            Tv.timing = TvLink.Timing()
        }
    }
}
