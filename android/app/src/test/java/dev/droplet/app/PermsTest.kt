package dev.droplet.app

import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshServer
import dev.droplet.app.mesh.Outbox
import dev.droplet.app.mesh.Perms
import dev.droplet.app.mesh.Refused
import dev.droplet.app.mesh.TrustList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Per-device permissions and Pause (docs/mesh.md §9.9), the Android port of
 * the reference's agent/tests/test_perms.py: each capability tried both ways
 * between two real nodes over TLS on loopback, in each of four states
 * (allowed, switched off for that device, that device paused, everything
 * paused). The receiver enforces its own settings whatever the sender does,
 * and the sender its own; what a peer says (`perm`) is only a hint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermsTest {
    private lateinit var tmp: File
    private val nodes = mutableListOf<MeshNode>()

    @Before
    fun setUp() {
        MeshIdentity.keystoreAllowed = false
        tmp = Files.createTempDirectory("perms").toFile()
    }

    @After
    fun tearDown() {
        nodes.forEach { it.close() }
        tmp.deleteRecursively()
    }

    /** A device that records what it was given, with Pause everything of its own. */
    internal class Host(private val name: String) : MeshUnitTest.QuietHost() {
        @Volatile var paused = false
        val texts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val rings: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val clips: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val notifies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val remotes: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val files: MutableList<String> = Collections.synchronizedList(mutableListOf())
        // the hub, when a test gives this device one
        @Volatile var hub: String? = null
        @Volatile var hubUp = false
        val online: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val hubSent: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val hubTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun log(msg: String) { logs += msg }
        override fun deviceName() = name
        override fun caps() = listOf("clipboard", "files", "media", "sms")
        override fun pausedEverything() = paused
        override fun setPausedEverything(on: Boolean) { paused = on }
        override fun onText(entry: TrustList.Entry, body: String, ts: Double) { texts += body }
        override fun onRing(entry: TrustList.Entry) { rings += entry.name }
        override fun onClip(entry: TrustList.Entry, text: String) { clips += text }
        override fun onNotify(entry: TrustList.Entry, msg: JSONObject) { notifies += msg }
        override fun onRemote(entry: TrustList.Entry, msg: JSONObject, reply: (JSONObject) -> Boolean) { remotes += msg }
        override fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String): String {
            files += name
            return part.path
        }
        override fun hubId() = hub
        override fun hubConnected() = hubUp
        override fun hubOnline(deviceId: String) = hubUp && deviceId in online
        override fun hubSend(msg: JSONObject): Boolean { hubSent += msg; return hubUp }
        override fun hubText(deviceId: String, body: String) {
            if (!hubUp) throw IOException("no hub")
            hubTexts += body
        }
        override fun hubRing(deviceId: String, stop: Boolean) { if (!hubUp) throw IOException("no hub") }

        fun got(): Int = texts.size + rings.size + clips.size + notifies.size + remotes.size + files.size
    }

    private val hosts = HashMap<MeshNode, Host>()
    private val MeshNode.h: Host get() = hosts.getValue(this)
    private var made = 0

    private fun node(name: String): MeshNode {
        val h = Host(name)
        return MeshNode(h, File(tmp, "$name-${made++}"), null, port = 0, retryEveryMs = 60_000,
            bindAddress = InetAddress.getLoopbackAddress()).also { it.start(); nodes += it; hosts[it] = h }
    }

    private fun entryFor(of: MeshNode, source: String = TrustList.SOURCE_PAIRED, hub: String? = null) =
        TrustList.makeEntry(peerId = of.peerId, name = of.h.deviceName(), certPem = of.identity.pem, source = source,
            lan = listOf("127.0.0.1"), port = of.listeningPort, os = "linux", caps = of.h.caps(), hub = hub)

    /** Two devices that trust each other, paired directly (or from [hub]'s roster) and own: as before. */
    private fun trustEachOther(a: MeshNode, b: MeshNode, hub: String? = null) {
        if (hub == null) {
            a.trust.addPaired(entryFor(b))
            b.trust.addPaired(entryFor(a))
        } else {
            a.trust.syncRoster(listOf(entryFor(b, TrustList.SOURCE_ROSTER, hub)), hub)
            b.trust.syncRoster(listOf(entryFor(a, TrustList.SOURCE_ROSTER, hub)), hub)
        }
    }

    private fun pair(): Pair<MeshNode, MeshNode> {
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b)
        return a to b
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    private fun closeAll() {
        nodes.forEach { it.close() }
        nodes.clear()
    }

    /** [node]'s settings about [peer]: "allowed" (as paired), "denied" ([cap] off), "paused", "global". */
    private fun setState(node: MeshNode, peer: MeshNode, cap: String, state: String) {
        when (state) {
            "denied" -> node.setPerms(peer.identity.fp, allow = mapOf(cap to false))
            "paused" -> node.setPerms(peer.identity.fp, paused = true)
            "global" -> node.pauseEverything(true)
        }
    }

    private fun waitJob(n: MeshNode, job: JSONObject): JSONObject = n.awaitJob(job.getString("id"), 20_000)
        ?: throw AssertionError("the job never settled")

    private fun aFile(): File = File(tmp, "photo-${System.nanoTime()}.jpg").apply {
        writeBytes(ByteArray(5000).also { SecureRandom().nextBytes(it) })
    }

    // --- the model -------------------------------------------------------------------------------

    @Test
    fun defaultsForYourOwnDeviceAndSomeoneElses() {
        assertEquals(Perms.CAPABILITIES.associateWith { true }, Perms.defaults("own"))
        assertEquals(mapOf("files" to true, "chat" to true, "clipboard" to false, "notify" to false, "control" to false,
            "ring" to true, "access" to false), Perms.defaults("other"))
        // anything else, or nothing at all, is your own device: what every peer was before
        assertEquals("own", Perms.cleanRelation(null))
        assertEquals("own", Perms.cleanRelation("guest"))
        assertEquals(Perms.OWN + ("clipboard" to false),
            Perms.cleanAllow(JSONObject().put("clipboard", false).put("bogus", false).put("files", "yes"), "own"))
    }

    @Test
    fun whatEachMessageNeeds() {
        fun cap(json: String) = Perms.capability(JSONObject(json))
        val cases = mapOf(
            """{"t":"text"}""" to "chat", """{"t":"offer"}""" to "files", """{"t":"file"}""" to "files",
            """{"t":"clip"}""" to "clipboard", """{"t":"notify"}""" to "notify", """{"t":"notify-removed"}""" to "notify",
            """{"t":"input"}""" to "control", """{"t":"media"}""" to "control", """{"t":"cmd"}""" to "control",
            """{"t":"ring"}""" to "ring", """{"t":"ring-stop"}""" to "ring",
            """{"t":"rpc","method":"sms.list"}""" to "access", """{"t":"rpc","method":"files.get"}""" to "access",
            """{"t":"rpc","method":"media.play"}""" to "control", """{"t":"state","kind":"media"}""" to "control",
            """{"t":"state","kind":"battery"}""" to null, """{"t":"unpair"}""" to null, """{"t":"something-new"}""" to null,
        )
        for ((json, want) in cases) assertEquals(json, want, cap(json))
    }

    @Test
    fun whatAlwaysGoesEvenPaused() {
        val e = entryFor(node("x")).copy(paused = true)
        for (t in listOf("hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "rpc-result")) {
            assertNull(t, Perms.check(e, JSONObject().put("t", t)))
        }
        assertEquals(Perms.No("paused", ""), Perms.check(e, JSONObject().put("t", "state").put("kind", "battery")))
        assertEquals(Perms.No("paused", "ring"), Perms.check(e.copy(paused = false), JSONObject().put("t", "ring"), pausedAll = true))
    }

    @Test
    fun anEntryFromBeforePermissionsIsYourOwnDevice() {
        val n = node("x")
        val other = node("y")
        val e = entryFor(other).toJson()
        e.remove("relation"); e.remove("allow"); e.remove("paused")
        val file = File(tmp, "old-trust.json")
        file.writeText(JSONObject().put("v", 1).put("peers", JSONObject().put(e.getString("fp"), e)).toString())
        val got = TrustList(file, n.identity.fp).get(e.getString("fp"))!!
        assertEquals("own", got.relation)
        assertEquals(Perms.OWN, got.allow)
        assertFalse(got.paused)
    }

    @Test
    fun theRosterKeepsTheOwnersSwitches() {
        val hub = "ab".repeat(8)
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b, hub)
        assertEquals("the hub's devices are your own", "own", a.trust.get(b.identity.fp)!!.relation)
        a.setPerms(b.identity.fp, allow = mapOf("clipboard" to false), paused = true)
        a.trust.syncRoster(listOf(entryFor(b, TrustList.SOURCE_ROSTER, hub).copy(name = "b renamed")), hub)
        val got = a.trust.get(b.identity.fp)!!
        assertEquals("b renamed", got.name)
        assertTrue(got.paused)
        assertEquals(false, got.allow["clipboard"])
    }

    @Test
    fun changingTheRelationStartsFromItsDefaults() {
        val (a, b) = pair()
        val fp = b.identity.fp
        a.setPerms(fp, allow = mapOf("files" to false))
        assertEquals(Perms.OTHER, a.setPerms(fp, relation = "other").allow)
        assertEquals(Perms.OWN + ("ring" to false), a.setPerms(fp, relation = "own", allow = mapOf("ring" to false)).allow)
        assertTrue(runCatching { a.setPerms(fp, allow = mapOf("teleport" to true)) }.exceptionOrNull()?.message!!.contains("no capability"))
        assertTrue(runCatching { a.setPerms(fp, relation = "friend") }.exceptionOrNull()?.message!!.contains("relation"))
        // kept across a restart
        assertEquals(Perms.OWN + ("ring" to false), TrustList(File(tmp, "a-0/trust.json"), a.identity.fp).get(fp)!!.allow)
    }

    // --- the matrix: what the receiver takes -------------------------------------------------------

    private val states = listOf("allowed", "denied", "paused", "global")

    /** a sends b something needing [cap], ignoring any hint. "taken", or the refusal a got back: (why, text). */
    private fun receive(a: MeshNode, b: MeshNode, cap: String): Any {
        val fp = b.identity.fp
        a.heedHints = false
        if (cap == Perms.FILES || cap == Perms.CHAT) {
            val job = if (cap == Perms.FILES) aFile().let { a.sendFile(fp, it.path, it.name, "image/jpeg", it.length()) }
                else a.sendText(fp, "hello")
            val got = waitJob(a, job)
            return when (got.getString("state")) {
                Outbox.DONE -> "taken"
                Outbox.FAILED -> Perms.DENIED to got.getString("error")
                else -> {
                    assertTrue(got.toString(), got.getString("error").startsWith("waiting: "))
                    Perms.PAUSED to got.getString("error")
                }
            }
        }
        val link = a.direct(fp) ?: throw AssertionError("no link")
        val msg = when (cap) {
            Perms.CLIPBOARD -> JSONObject().put("t", "clip").put("text", "secret")
            Perms.NOTIFY -> JSONObject().put("t", "notify").put("key", "k1").put("app", "Chat").put("title", "Mum").put("text", "hi")
            Perms.CONTROL -> JSONObject().put("t", "media").put("action", "play")
            Perms.RING -> JSONObject().put("t", "ring")
            else -> JSONObject().put("t", "rpc").put("id", "r1").put("method", "files.list").put("params", JSONObject())
        }
        a.refusals.remove(fp)
        link.send(msg)
        val h = b.h
        fun taken() = when (cap) {
            Perms.NOTIFY -> h.notifies.isNotEmpty()
            Perms.RING -> h.rings.isNotEmpty()
            Perms.CLIPBOARD -> h.clips.isNotEmpty()
            else -> h.remotes.any { it.optString("t") == msg.optString("t") }
        }
        waitFor("$cap taken or refused", 5_000) { taken() || a.refusals[fp] != null }
        if (taken()) return "taken"
        val r = a.refusals[fp]!!
        assertEquals(cap, r.cap)
        assertEquals(msg.optString("t"), r.re)
        return r.why to r.text
    }

    @Test
    fun theReceiverEnforcesItsOwnSettings() {
        for (cap in Perms.CAPABILITIES) for (state in states) {
            val what = "$cap, $state"
            val (a, b) = pair()
            setState(b, a, cap, state)
            val got = receive(a, b, cap)
            when (state) {
                "allowed" -> assertEquals(what, "taken", got)
                "denied" -> assertEquals(what, Perms.DENIED to "b doesn't allow ${Perms.NOUNS[cap]} from you", got)
                else -> {
                    // refused for now, and files and messages wait for the resume rather than fail
                    val (why, text) = got as Pair<*, *>
                    assertEquals(what, Perms.PAUSED, why)
                    assertTrue("$what: $text", "b paused sharing with you" in text.toString())
                }
            }
            if (state != "allowed") {
                Thread.sleep(100)
                assertEquals("$what: nothing of it reached b", 0, b.h.got())
                assertTrue(what, b.chat.recent(a.identity.fp).none { it.optString("dir") == "in" })
            }
            closeAll()
        }
    }

    // --- the matrix: what the sender sends ----------------------------------------------------------

    /** a sends b something needing [cap] the normal way: "sent", "waiting", or the [Refused]. */
    private fun send(a: MeshNode, b: MeshNode, cap: String): Any {
        val fp = b.identity.fp
        val job = try {
            when (cap) {
                Perms.FILES -> aFile().let { a.sendFile(fp, it.path, it.name, "text/plain", it.length()) }
                Perms.CHAT -> a.sendText(fp, "hi")
                Perms.CLIPBOARD -> { a.clip(fp, "copied"); return "sent" }
                Perms.CONTROL -> { a.sendLive(fp, JSONObject().put("t", "media").put("action", "play")); return "sent" }
                Perms.RING -> { a.ring(fp); return "sent" }
                else -> {
                    a.maySend(fp, if (cap == Perms.NOTIFY) JSONObject().put("t", "notify").put("key", "k")
                        else JSONObject().put("t", "rpc").put("id", "r").put("method", "sms.list"))
                    return "sent"
                }
            }
        } catch (e: Refused) {
            return e
        }
        val got = waitJob(a, job)
        return when (got.getString("state")) {
            Outbox.DONE -> "sent"
            Outbox.QUEUED -> "waiting"
            else -> got.optString("error")
        }
    }

    @Test
    fun theSenderEnforcesItsOwnSettings() {
        for (cap in Perms.CAPABILITIES) for (state in states) {
            val what = "$cap, $state"
            val (a, b) = pair()
            setState(a, b, cap, state)
            val got = send(a, b, cap)
            when {
                // a switch about what b may do *here* doesn't stop this phone using b: b decides that
                state == "allowed" || (state == "denied" && cap in Perms.INBOUND_ONLY) -> assertEquals(what, "sent", got)
                state == "denied" -> {
                    assertTrue("$what: $got", got is Refused && got.local && got.why == "denied")
                    assertTrue("$what: $got", "switched off here" in (got as Refused).message!!)
                }
                cap == Perms.FILES || cap == Perms.CHAT -> {
                    assertEquals("$what: it waits for the resume, it doesn't fail", "waiting", got)
                    val job = a.outbox.queued().single()
                    assertTrue(job.toString(), job.getString("error").startsWith("waiting: "))
                    assertTrue(job.toString(), (if (state == "global") "everything is paused" else "b is paused") in job.getString("error"))
                }
                else -> assertTrue("$what: $got", got is Refused && got.local && got.why == "paused")
            }
            closeAll()
        }
    }

    @Test
    fun theSenderRespectsWhatTheReceiverSaid() {
        for (cap in Perms.CAPABILITIES) {
            val (a, b) = pair()
            assertNotNull(a.direct(b.identity.fp))
            b.setPerms(a.identity.fp, allow = mapOf(cap to false))
            waitFor("a hears b's perm for $cap") { a.remotePerm[b.identity.fp]?.allow?.get(cap) == false }
            val got = send(a, b, cap)
            assertTrue("$cap: $got", got is Refused && !got.local)
            assertEquals(cap, "b doesn't allow ${Perms.NOUNS[cap]} from you", (got as Refused).message)
            closeAll()
        }
    }

    // --- pausing and resuming --------------------------------------------------------------------------

    @Test
    fun messagesToAPausedDeviceWaitAndGoOnResume() {
        val (a, b) = pair()
        val fp = b.identity.fp
        a.setPerms(fp, paused = true)
        val job = a.sendText(fp, "after the meeting")
        val got = waitJob(a, job)
        assertEquals(Outbox.QUEUED, got.getString("state"))
        assertEquals("waiting: b is paused: resume it to send", got.getString("error"))
        assertFalse(a.setPerms(fp, paused = false).paused)
        assertNotNull(a.outbox.await(job.getString("id"), 10_000) { it.optString("state") == Outbox.DONE })
        assertEquals(listOf("after the meeting"), b.h.texts)
    }

    @Test
    fun aDeviceThatPausedUsGetsOurMessagesWhenItResumes() {
        val (a, b) = pair()
        assertNotNull(a.direct(b.identity.fp))
        b.setPerms(a.identity.fp, paused = true)
        waitFor("a hears it") { a.remotePerm[b.identity.fp]?.paused == true }
        val job = a.sendText(b.identity.fp, "are you there")
        val got = waitJob(a, job)
        assertEquals("waiting: b paused sharing with you", got.getString("error"))
        b.setPerms(a.identity.fp, paused = false)      // its perm says so, and what waited goes
        assertNotNull(a.outbox.await(job.getString("id"), 10_000) { it.optString("state") == Outbox.DONE })
        assertEquals(listOf("are you there"), b.h.texts)
    }

    @Test
    fun aRefusedForNowKeepsTheMessageQueued() {
        // b paused a without a hint getting through first (a ignores hints): its refusal keeps it queued
        val (a, b) = pair()
        a.heedHints = false
        b.setPerms(a.identity.fp, paused = true)
        val job = a.sendText(b.identity.fp, "later")
        val got = waitJob(a, job)
        assertEquals(Outbox.QUEUED, got.getString("state"))
        assertEquals("waiting: b paused sharing with you", got.getString("error"))
        assertTrue(b.h.texts.isEmpty())
    }

    @Test
    fun pauseEverythingStopsTheClipboardToEveryoneAndSaysSo() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        trustEachOther(a, b)
        trustEachOther(a, c)
        for (peer in listOf(b, c)) assertNotNull(a.direct(peer.identity.fp))
        assertTrue(a.broadcast(JSONObject().put("t", "clip").put("text", "one")))
        waitFor("both have it") { b.h.clips == listOf("one") && c.h.clips == listOf("one") }
        a.pauseEverything(true)
        assertTrue(a.pausedAll)
        assertFalse(a.broadcast(JSONObject().put("t", "clip").put("text", "two")))
        // each is told, so its screen can say "paused by a"
        waitFor("both hear it") {
            b.remotePerm[a.identity.fp]?.paused == true && c.remotePerm[a.identity.fp]?.paused == true
        }
        a.pauseEverything(false)
        waitFor("resumed") { b.remotePerm[a.identity.fp]?.paused == false }
        assertTrue(a.broadcast(JSONObject().put("t", "clip").put("text", "three")))
        waitFor("the next one") { b.h.clips == listOf("one", "three") }
    }

    @Test
    fun theClipboardSkipsSomeoneElsesDevice() {
        val a = node("a")
        val mine = node("mine")
        val guest = node("guest")
        trustEachOther(a, mine)
        trustEachOther(a, guest)
        a.setPerms(guest.identity.fp, relation = "other")
        for (peer in listOf(mine, guest)) assertNotNull(a.direct(peer.identity.fp))
        assertTrue(a.broadcast(JSONObject().put("t", "clip").put("text", "my password")))
        waitFor("mine has it") { mine.h.clips.isNotEmpty() }
        Thread.sleep(300)
        assertTrue(guest.h.clips.isEmpty())
    }

    @Test
    fun helloTellsEachPeerOnlyWhatItMayUse() {
        val (a, b) = pair()
        a.setPerms(b.identity.fp, relation = "other")
        var hello = a.hello("welcome", b.identity.fp)
        // someone else's: no clipboard, and no media, SMS or files on this phone
        assertEquals(0, hello.getJSONArray("caps").length())
        assertFalse(hello.getJSONObject("perm").getBoolean("paused"))
        assertEquals(Perms.OTHER, Perms.cleanAllow(hello.getJSONObject("perm").getJSONObject("allow")))
        a.setPerms(b.identity.fp, relation = "own")
        hello = a.hello("welcome", b.identity.fp)
        assertEquals(listOf("clipboard", "files", "media", "sms"), MeshNode.strings(hello.getJSONArray("caps")))
        a.setPerms(b.identity.fp, allow = mapOf("access" to false))
        assertEquals(listOf("clipboard", "media"), MeshNode.strings(a.hello("welcome", b.identity.fp).getJSONArray("caps")))
        a.setPerms(b.identity.fp, paused = true)
        assertEquals(0, a.hello("welcome", b.identity.fp).getJSONArray("caps").length())
        // a link opened now carries it: b learns how a treats it, and its caps
        assertNotNull(b.direct(a.identity.fp))
        waitFor("b hears it") { b.remotePerm[a.identity.fp]?.paused == true }
        assertTrue(b.trust.get(a.identity.fp)!!.caps.isEmpty())
        // mDNS (and the roster) still announce everything
        assertEquals("clipboard,files,media,sms", a.txt()["caps"])
    }

    @Test
    fun anOlderPeerWithNoPermIsTreatedAsBefore() {
        assertNull("no perm at all", Perms.parseRemote(null))
        assertNull(Perms.remoteRefuses(null, "clipboard"))
        val (a, b) = pair()
        b.trust.setPerms(a.identity.fp, allow = mapOf("clipboard" to false))
        assertNotNull(a.direct(b.identity.fp))
        // as if b's hello had carried no perm (an older peer): a sends as before, and b still refuses
        a.remotePerm.remove(b.identity.fp)
        assertEquals("lan", a.clip(b.identity.fp, "x"))
        waitFor("b's refusal") { a.refusals[b.identity.fp]?.cap == "clipboard" }
        assertTrue(b.h.clips.isEmpty())
    }

    @Test
    fun refusalsOfAStreamAreSaidOnceInAWhile() {
        val (a, b) = pair()
        b.setPerms(a.identity.fp, allow = mapOf("control" to false))
        a.heedHints = false
        val link = a.direct(b.identity.fp)!!
        repeat(30) { link.send(JSONObject().put("t", "media").put("action", "volume_up")) }
        waitFor("one refusal") { a.refusals[b.identity.fp] != null }
        Thread.sleep(500)
        val told = a.h.logs.filter { it.startsWith("mesh: b refused:") }
        assertEquals(told.toString(), 1, told.size)
        assertEquals("denied", a.refusals[b.identity.fp]!!.why)
        assertEquals("control", a.refusals[b.identity.fp]!!.cap)
        assertTrue(b.h.remotes.isEmpty())
    }

    @Test
    fun aPausedDeviceCantFetchAFileOfferedBefore() {
        val (a, b) = pair()
        a.setPerms(b.identity.fp, paused = true)
        val heads = mutableListOf<Int>()
        a.serveFile(java.io.ByteArrayOutputStream(), "0".repeat(32), b.identity.fp,
            MeshServer.Request("GET", "/mesh/files/" + "0".repeat(32), emptyMap()), { status, _ -> heads += status })
        assertEquals(listOf(403), heads)
    }

    // --- pairing asks: your device or someone else's ------------------------------------------------------

    @Test
    fun pairingAsSomeoneElsesDeviceOnBothSides() {
        val a = node("a")
        val b = node("b")
        val og = a.pairStart("127.0.0.1", b.listeningPort, b.identity.fp)
        val req = b.incoming.waiting().single()
        // each side chooses for itself, and neither tells the other
        b.pairAnswer(req.request, true, "other")
        val done = CountDownLatch(1)
        a.pairConfirm(og.request!!, true, "own") { done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals("other", b.trust.get(a.identity.fp)!!.relation)
        assertEquals(Perms.OTHER, b.trust.get(a.identity.fp)!!.allow)
        assertEquals("own", a.trust.get(b.identity.fp)!!.relation)
        assertEquals(Perms.OWN, a.trust.get(b.identity.fp)!!.allow)
        // b's choice reaches a only as a hint about how b treats it
        assertNotNull(a.direct(b.identity.fp))
        waitFor("a hears how b treats it") { a.remotePerm[b.identity.fp]?.allow?.get("clipboard") == false }
        assertEquals("own", a.trust.get(b.identity.fp)!!.relation)
        // a relation that isn't one is refused
        assertTrue(runCatching { a.pairConfirm(og.request!!, true, "deskmate") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { b.pairAnswer("0".repeat(32), true, "friend") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun pairingWithNobodyToAskIsYourOwnDevice() {
        val a = node("a")
        val b = node("b")
        val og = a.pairStart("127.0.0.1", b.listeningPort, b.identity.fp)
        b.pairAnswer(b.incoming.waiting().single().request, true)
        val done = CountDownLatch(1)
        a.pairConfirm(og.request!!, true) { done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals("own", b.trust.get(a.identity.fp)!!.relation)
        assertEquals("own", a.trust.get(b.identity.fp)!!.relation)
    }

    // --- through the hub ------------------------------------------------------------------------------------

    @Test
    fun theHubRouteFollowsTheSameSwitches() {
        val hub = "ab".repeat(8)
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b, hub)
        b.close()
        a.h.hub = hub
        a.h.hubUp = true
        a.h.online += b.peerId
        val fp = b.identity.fp
        assertEquals("hub", a.sendLive(fp, JSONObject().put("t", "media").put("action", "play")))
        a.setPerms(fp, paused = true)
        assertTrue(runCatching { a.sendLive(fp, JSONObject().put("t", "media")) }.exceptionOrNull() is Refused)
        assertTrue(runCatching { a.ring(fp) }.exceptionOrNull() is Refused)
        val job = a.sendText(fp, "later")
        assertEquals(Outbox.QUEUED, waitJob(a, job).getString("state"))
        assertTrue("nothing through the hub while paused", a.h.hubTexts.isEmpty())
        a.setPerms(fp, paused = false)
        assertEquals("hub", a.outbox.await(job.getString("id"), 10_000) { it.optString("state") == Outbox.DONE }!!.getString("route"))
        // a clipboard through the hub reaches every device it has: not while one shouldn't have it
        assertTrue(a.hubMayShare(JSONObject().put("t", "clip")))
        a.setPerms(fp, allow = mapOf("clipboard" to false))
        assertFalse(a.hubMayShare(JSONObject().put("t", "clip")))
        assertTrue(a.hubMayShare(JSONObject().put("t", "state").put("kind", "battery")))
        assertTrue(runCatching { a.clip(fp, "x") }.exceptionOrNull() is Refused)
        a.pauseEverything(true)
        assertFalse(a.hubMayShare(JSONObject().put("t", "state").put("kind", "battery")))
    }

    @Test
    fun messagesThroughTheHubAreCheckedAgainstTheSender() {
        val hub = "ab".repeat(8)
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b, hub)
        val from = JSONObject().put("id", b.peerId).put("name", "b")
        fun check(t: String, sender: JSONObject = from, method: String? = null) =
            a.checkHubMessage(JSONObject().put("t", t).put("from", sender).apply { method?.let { put("method", it) } })
        assertNull(check("media"))
        a.setPerms(b.identity.fp, relation = "other")
        assertEquals("denied", check("media"))
        assertEquals("denied", check("clip"))
        assertEquals("denied", check("rpc", method = "sms.list"))
        // a device of the hub's this phone doesn't know (the hub's web page): the same user's
        assertNull(check("media", JSONObject().put("id", "0123456789ab")))
        a.pauseEverything(true)
        assertEquals("paused", check("media", JSONObject().put("id", "0123456789ab")))
    }
}
