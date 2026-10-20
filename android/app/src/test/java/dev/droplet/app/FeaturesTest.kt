package dev.droplet.app

import dev.droplet.app.mesh.Links
import dev.droplet.app.mesh.MeshFiles
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.Outbox
import dev.droplet.app.mesh.Perms
import dev.droplet.app.mesh.Refused
import dev.droplet.app.mesh.Transfers
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
import java.net.InetAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Collections

/**
 * Progress and Cancel, links, rename and nicknames, and several devices at
 * once (docs/mesh.md §9.11): the Android port of the reference's
 * agent/tests/test_transfers.py and test_sending.py, between real nodes over
 * TLS on loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeaturesTest {
    private lateinit var tmp: File
    private val nodes = mutableListOf<MeshNode>()
    private val hosts = HashMap<MeshNode, Host>()
    private val dirs = HashMap<MeshNode, File>()
    private val MeshNode.h: Host get() = hosts.getValue(this)
    private val MeshNode.fp: String get() = identity.fp
    private var made = 0

    @Before
    fun setUp() {
        MeshIdentity.keystoreAllowed = false
        tmp = Files.createTempDirectory("features").toFile()
    }

    @After
    fun tearDown() {
        nodes.forEach { it.close() }
        tmp.deleteRecursively()
    }

    /** A device that records what reached it: messages, links (and whether they'd open), files, transfers. */
    internal class Host(@Volatile var name: String, private val downloads: File) : MeshUnitTest.QuietHost() {
        @Volatile var paused = false
        /** With a hub: the hub's say on a new name (throws when it refuses). */
        @Volatile var hubRename: ((String) -> Unit)? = null
        val texts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val links: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
        val seen: MutableList<Transfers.View> = Collections.synchronizedList(mutableListOf())

        override fun deviceName() = name
        override fun setDeviceName(name: String) {
            hubRename?.invoke(name)
            this.name = name
        }
        override fun caps() = listOf("clipboard", "files")
        override fun pausedEverything() = paused
        override fun setPausedEverything(on: Boolean) { paused = on }
        override fun onText(entry: TrustList.Entry, body: String, ts: Double) { texts += entry.label to body }
        override fun onLink(entry: TrustList.Entry, url: String, open: Boolean) { links += url to open }
        override fun onTransfer(t: Transfers.View) { seen += t }
        override fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String): String {
            downloads.mkdirs()
            val dest = File(downloads, name)
            part.copyTo(dest, true)
            return dest.path
        }
    }

    private fun node(name: String): MeshNode {
        val dir = File(tmp, "$name-${made++}")
        val h = Host(name, File(dir, "downloads"))
        return MeshNode(h, dir, null, port = 0, retryEveryMs = 60_000, bindAddress = InetAddress.getLoopbackAddress())
            .also { it.start(); nodes += it; hosts[it] = h; dirs[it] = dir }
    }

    private fun downloads(n: MeshNode) = File(dirs.getValue(n), "downloads")
    private fun noPartial(n: MeshNode) = File(dirs.getValue(n), "incoming").listFiles().orEmpty().none { it.name.startsWith(".droplet-") }

    private fun entryFor(of: MeshNode, source: String = TrustList.SOURCE_PAIRED, hub: String? = null) =
        TrustList.makeEntry(peerId = of.peerId, name = of.h.name, certPem = of.identity.pem, source = source,
            lan = listOf("127.0.0.1"), port = of.listeningPort, os = "linux", caps = of.h.caps(), hub = hub)

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

    private fun waitFor(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    private fun big(mb: Int = 4): File = File(tmp, "big-${System.nanoTime()}.bin").apply {
        val r = SecureRandom()
        outputStream().use { o -> repeat(mb) { o.write(ByteArray(1 shl 20).also { r.nextBytes(it) }) } }
    }

    private fun small(name: String, text: String): File = File(tmp, name).apply { writeText(text) }

    private fun MeshNode.sendPath(fp: String, f: File, name: String = f.name): JSONObject =
        sendFile(fp, f.path, name, "application/octet-stream", f.length())

    private fun job(n: MeshNode, id: String, ms: Long = 10_000, until: (JSONObject) -> Boolean): JSONObject? =
        n.outbox.await(id, ms, until)

    /** Closes every link between [a] and [b], and waits until both see it. */
    private fun unlink(a: MeshNode, b: MeshNode) {
        waitFor("the link closed") {
            a.openLink(b.fp)?.close(1000, "test")
            b.openLink(a.fp)?.close(1000, "test")
            a.openLink(b.fp) == null && b.openLink(a.fp) == null
        }
    }

    // --- progress -------------------------------------------------------------------------------

    @Test
    fun progressComesFromRealByteCountsOnBothSides() {
        val (a, b) = pair()
        a.offerRate = 2L * 1024 * 1024
        val src = big()
        val job = a.sendPath(b.fp, src)
        val id = job.getString("id")
        val seenA = mutableListOf<Transfers.View>()
        val seenB = mutableListOf<Transfers.View>()
        waitFor("received", 30_000) {
            a.transfers.get(id)?.let { seenA += it }
            b.transfers.get(id)?.let { seenB += it }
            b.transfers.get(id)?.state == Transfers.DONE
        }
        waitFor("delivered") { a.outbox.get(id)?.optString("state") == Outbox.DONE }
        val midA = seenA.filter { it.done in 1 until it.size }
        val midB = seenB.filter { it.done in 1 until it.size }
        assertTrue("progress part-way, on each side", midA.isNotEmpty() && midB.isNotEmpty())
        assertTrue(seenA.all { it.dir == "out" && it.peer == "b" && it.name == src.name })
        assertTrue(seenB.all { it.dir == "in" && it.peer == "a" })
        assertTrue("a speed, from real byte counts", (midA + midB).any { it.rate != null })
        assertEquals(seenB.map { it.done }, seenB.map { it.done }.sorted())
        assertEquals(Transfers.DONE, a.transfers.get(id)!!.state)
        // the screens heard of it, a few times a second at most, and of the end
        assertTrue(a.h.seen.any { it.id == id && it.state == Transfers.DONE })
        assertTrue(b.h.seen.any { it.id == id && it.state == Transfers.DONE })
        assertTrue("throttled: ${b.h.seen.size}", b.h.seen.size < 60)
    }

    // --- cancel ---------------------------------------------------------------------------------

    @Test
    fun theSenderCancelsAndTheReceiverDropsThePartialFile() {
        val (a, b) = pair()
        a.offerRate = 1024L * 1024
        val src = big()
        val id = a.sendPath(b.fp, src).getString("id")
        waitFor("under way") { (b.transfers.get(id)?.done ?: 0) > 300_000 }
        val out = a.cancel(id.take(8))
        assertEquals("out", out.dir)
        assertEquals(src.name, out.name)
        val got = job(a, id, 5_000) { it.optString("state") == Outbox.CANCELLED }!!
        assertEquals(Outbox.CANCELLED, got.getString("state"))
        assertEquals("cancelled here", got.getString("error"))
        waitFor("the receiver hears") { b.transfers.get(id)?.state == Transfers.CANCELLED }
        assertEquals("a cancelled it", b.transfers.get(id)!!.error)
        waitFor("no partial file") { noPartial(b) }
        assertFalse(File(downloads(b), src.name).exists())
        assertEquals(Transfers.CANCELLED, a.transfers.get(id)!!.state)
        assertEquals("Cancelled", Transfers.progressText(a.transfers.get(id)!!))
        // and it's gone for good: nothing is offered again
        Thread.sleep(1000)
        assertTrue(a.outbox.queued().isEmpty())
        assertFalse(File(downloads(b), src.name).exists())
    }

    @Test
    fun theReceiverCancelsAndTheSenderIsTold() {
        val (a, b) = pair()
        a.offerRate = 1024L * 1024
        val src = big()
        val id = a.sendPath(b.fp, src).getString("id")
        waitFor("under way") { (b.transfers.get(id)?.done ?: 0) > 300_000 }
        assertEquals("in", b.cancel(id).dir)
        waitFor("cancelled here") { b.transfers.get(id)?.state == Transfers.CANCELLED }
        waitFor("no partial file") { noPartial(b) }
        val got = job(a, id, 5_000) { it.optString("state") == Outbox.CANCELLED }!!
        assertEquals(Outbox.CANCELLED, got.getString("state"))
        assertEquals("b cancelled it", got.getString("error"))
        assertEquals(Transfers.CANCELLED, a.transfers.get(id)!!.state)
        assertEquals("Cancelled: b cancelled it", Transfers.progressText(a.transfers.get(id)!!))
        assertFalse(File(downloads(b), src.name).exists())
    }

    @Test
    fun anOlderSenderThatOffersItAgainIsRefused() {
        val (a, b) = pair()
        a.offerRate = 512L * 1024
        val src = big(2)
        val id = a.sendPath(b.fp, src).getString("id")
        waitFor("under way") { (b.transfers.get(id)?.done ?: 0) > 100_000 }
        b.cancel(id)
        waitFor("cancelled") { b.transfers.get(id)?.state == Transfers.CANCELLED }
        val replies = Collections.synchronizedList(mutableListOf<JSONObject>())
        b.spy = { dir, _, m -> if (dir == "out") replies += m }
        // an older sender ignores `cancel`, sees the transfer stall, and offers it again
        a.direct(b.fp)!!.send(JSONObject().put("t", "offer").put("id", id).put("name", src.name).put("size", src.length())
            .put("mime", "application/octet-stream"))
        waitFor("the nack") { replies.any { it.optString("t") == "nack" && it.optString("id") == id } }
        val nack = replies.first { it.optString("t") == "nack" }
        assertEquals("cancelled", nack.getString("why"))
        assertEquals("b cancelled it", nack.getString("error"))
        assertFalse(File(downloads(b), src.name).exists())
    }

    @Test
    fun aJobWaitingInTheOutboxCanBeCancelled() {
        val (a, b) = pair()
        a.setPerms(b.fp, paused = true)
        val src = small("later.txt", "later")
        val id = a.sendPath(b.fp, src).getString("id")
        job(a, id, 5_000) { it.optInt("attempts") > 0 }
        assertEquals("out", a.cancel(id).dir)
        assertEquals(Outbox.CANCELLED, a.outbox.get(id)!!.getString("state"))
        assertEquals(Outbox.CANCELLED, a.awaitJob(id, 0)!!.getString("state"))
        a.setPerms(b.fp, paused = false)
        Thread.sleep(800)
        assertFalse(File(downloads(b), "later.txt").exists())
        try {
            a.cancel("ffffffffffff")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no transfer"))
        }
    }

    @Test
    fun cancelIsTakenEvenWhilePausedAndOnlyForItsOwnPeer() {
        val e = entryFor(node("x")).copy(paused = true)
        assertNull(Perms.check(e, JSONObject().put("t", "cancel")))
        assertNull(Perms.check(e, JSONObject().put("t", "rename")))
        val a = node("a")
        val b = node("b")
        val c = node("c")
        trustEachOther(a, b)
        trustEachOther(a, c)
        a.setPerms(b.fp, paused = true)
        val id = a.sendPath(b.fp, small("x.txt", "x")).getString("id")
        job(a, id, 5_000) { it.optInt("attempts") > 0 }
        // c can't cancel what a sends b
        c.direct(a.fp)!!.send(JSONObject().put("t", "cancel").put("id", id))
        Thread.sleep(300)
        assertTrue(a.outbox.get(id)!!.getString("state") != Outbox.CANCELLED)
        b.direct(a.fp)!!.send(JSONObject().put("t", "cancel").put("id", id))
        assertEquals("b cancelled it", job(a, id, 5_000) { it.optString("state") == Outbox.CANCELLED }!!.getString("error"))
    }

    @Test
    fun downloadDeletesThePartialFileWhenCancelled() {
        val a = node("a")
        val (part, meta) = MeshFiles.partFiles(tmp, "f".repeat(64), "1".repeat(32))
        part.writeBytes(ByteArray(10))
        meta.writeText(JSONObject().put("size", 100).put("name", "n").put("fp", "f".repeat(64)).toString())
        try {
            MeshFiles.download(a.identity, "f".repeat(64), listOf("127.0.0.1" to 1), MeshFiles.Checked("1".repeat(32), "n", 100, "x/y"),
                tmp, cancelled = { true })
            fail()
        } catch (e: MeshFiles.Cancelled) {
            // expected
        }
        assertFalse(part.exists() || meta.exists())
    }

    @Test
    fun hellosSayWhatTheyUnderstand() {
        val (a, b) = pair()
        a.direct(b.fp)
        assertEquals(listOf("cancel", "link", "rename"), MeshNode.strings(a.hello().getJSONArray("features")))
        waitFor("b's features") { a.featuresOf(b.fp) == listOf("cancel", "link", "rename") }
        waitFor("a's features") { b.featuresOf(a.fp) == listOf("cancel", "link", "rename") }
    }

    // --- links ----------------------------------------------------------------------------------

    @Test
    fun aLinkFromYourOwnDeviceOpens() {
        val (a, b) = pair()
        val out = a.sendLink(b.fp, "https://example.com/slides")
        assertEquals("link", out.how)
        assertEquals("lan", out.route)
        waitFor("opened") { b.h.links == listOf("https://example.com/slides" to true) }
        val got = b.chat.recent(a.fp).last()
        assertEquals("link", got.getString("kind"))
        assertEquals("https://example.com/slides", got.getString("body"))
        assertTrue(got.optBoolean("opened"))
        val sent = a.chat.recent(b.fp).last()
        assertEquals("link", sent.getString("kind"))
        assertEquals("out", sent.getString("dir"))
    }

    @Test
    fun aLinkFromSomeoneElsesDeviceWaitsWithAnOpenButton() {
        val (a, b) = pair()
        b.setPerms(a.fp, relation = Perms.OTHER_DEVICE)
        assertEquals("link", a.sendLink(b.fp, "https://example.com/x").how)
        waitFor("stored") { b.chat.recent(a.fp).lastOrNull()?.getString("body") == "https://example.com/x" }
        Thread.sleep(200)
        assertEquals(listOf("https://example.com/x" to false), b.h.links)
        assertFalse(b.chat.recent(a.fp).last().optBoolean("opened"))
    }

    @Test
    fun badLinksAreRefusedBothWays() {
        val (a, b) = pair()
        try {
            a.sendLink(b.fp, "file:///etc/passwd")
            fail()
        } catch (e: Links.BadLink) {
            assertTrue(e.message!!.contains("only web links"))
        }
        // a peer that sends one anyway: refused, never stored or opened
        val replies = Collections.synchronizedList(mutableListOf<JSONObject>())
        b.spy = { dir, _, m -> if (dir == "out") replies += m }
        a.direct(b.fp)!!.send(JSONObject().put("t", "link").put("id", "linkid0001").put("url", "javascript:alert(1)").put("ts", 1))
        waitFor("the nack") { replies.any { it.optString("t") == "nack" && it.optString("id") == "linkid0001" } }
        assertTrue(b.h.links.isEmpty())
        assertTrue(b.chat.recent(a.fp).isEmpty())
    }

    @Test
    fun linksFollowTheChatSwitch() {
        val (a, b) = pair()
        a.setPerms(b.fp, allow = mapOf(Perms.CHAT to false))
        try {
            a.sendLink(b.fp, "https://example.com")
            fail()
        } catch (e: Refused) {
            assertTrue(e.message, e.message!!.contains("switched off here"))
        }
        a.setPerms(b.fp, allow = mapOf(Perms.CHAT to true))
        b.setPerms(a.fp, allow = mapOf(Perms.CHAT to false))
        // b refuses it (a doesn't know yet, or ignores the hint): not opened
        a.heedHints = false
        try {
            a.sendLink(b.fp, "https://example.com")
            fail()
        } catch (e: Refused) {
            assertTrue(e.message, e.message!!.contains("doesn't allow messages"))
        }
        assertTrue(b.h.links.isEmpty())
    }

    @Test
    fun linksToAPausedOrOlderPeerGoAsAMessage() {
        val (a, b) = pair()
        a.setPerms(b.fp, paused = true)
        val out = a.sendLink(b.fp, "https://example.com/p")
        assertEquals("message", out.how)
        val waited = job(a, out.job!!.getString("id"), 5_000) { it.optInt("attempts") > 0 }!!
        assertEquals(Outbox.QUEUED, waited.getString("state"))
        a.setPerms(b.fp, paused = false)
        waitFor("delivered on resume") { b.h.texts.any { it.second == "https://example.com/p" } }
        assertTrue("a message is never opened by itself", b.h.links.isEmpty())
        // an older peer, which doesn't say it understands `link`
        a.direct(b.fp)!!.hello!!.remove("features")
        assertEquals("message", a.sendLink(b.fp, "https://example.com/old").how)
    }

    @Test
    fun openingIsLimited() {
        val (a, b) = pair()
        for (i in 0 until 8) a.sendLink(b.fp, "https://example.com/$i")
        waitFor("all eight stored") { b.chat.recent(a.fp).size == 8 }
        assertEquals(5, b.h.links.count { it.second })
        assertEquals(3, b.h.links.count { !it.second })
    }

    // --- names ----------------------------------------------------------------------------------

    @Test
    fun renamingTellsLinkedDevicesAndTheNextHello() {
        val (a, b) = pair()
        a.direct(b.fp)
        assertEquals(1, a.rename("Ferrin's  laptop"))
        assertEquals("Ferrin's laptop", a.h.name)
        assertEquals("Ferrin's laptop", a.txt()["name"])
        assertEquals("Ferrin's laptop", a.hello().getString("name"))
        waitFor("b hears") { b.trust.get(a.fp)?.name == "Ferrin's laptop" }
        try {
            a.rename("x\u0000")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("control"))
        }
        // b not linked: it learns from the next hello
        unlink(a, b)
        assertEquals(0, a.rename("slim"))
        assertEquals("Ferrin's laptop", b.trust.get(a.fp)!!.name)
        b.direct(a.fp)
        waitFor("the next hello brings it") { b.trust.get(a.fp)?.name == "slim" }
    }

    @Test
    fun aPeerTheHubNamesIsntRenamedByItself() {
        val hub = "ab".repeat(8)
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b, hub)
        a.direct(b.fp)
        b.rename("not the hub's name")
        Thread.sleep(300)
        assertEquals("b", a.trust.get(b.fp)!!.name)
    }

    @Test
    fun renamingWithAHubGoesThroughTheHub() {
        val a = node("a")
        val asked = mutableListOf<String>()
        a.h.hubRename = { name ->
            asked += name
            if (name == "taken") throw IllegalArgumentException("taken is already a device here")
        }
        try {
            a.rename("taken")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already a device here"))
        }
        assertEquals("a", a.h.name)
        a.rename("slim")
        assertEquals(listOf("taken", "slim"), asked)
        assertEquals("slim", a.h.name)
    }

    @Test
    fun nicknamesShowHereAndAreNeverSent() {
        val (a, b) = pair()
        val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
        a.spy = { dir, _, m -> if (dir == "out") sent += m }
        val e = a.setNickname(b.fp, "Brian's  laptop")
        assertEquals("Brian's laptop", e.nickname)
        assertEquals("b", e.name)
        assertEquals("Brian's laptop", e.label)
        assertEquals(b.fp, a.trust.find("brian's laptop").single().fp)
        a.sendText(b.fp, "hi")
        a.clip(b.fp, "clip")
        a.sendLink(b.fp, "https://example.com")
        a.setPerms(b.fp, paused = false)
        a.rename("a2")
        // sent the way the app sends, so a link replaced just after the rename doesn't lose it
        b.sendText(a.fp, "hello")
        waitFor("its message, under the nickname") { a.h.texts.contains("Brian's laptop" to "hello") }
        // a restart keeps it
        assertEquals("Brian's laptop", TrustList(File(dirs.getValue(a), "trust.json"), a.fp).get(b.fp)!!.nickname)
        waitFor("sent") { sent.size >= 4 }
        val everything = sent.joinToString() + a.hello(fp = b.fp) + a.txt() + a.announceBody()
        assertFalse(everything, "Brian" in everything)
        assertEquals("", b.trust.get(a.fp)!!.nickname)
        assertEquals("", a.setNickname(b.fp, "").nickname)
        try {
            a.setNickname(b.fp, "x".repeat(50))
            fail()
        } catch (x: IllegalArgumentException) {
            assertTrue(x.message!!.contains("40 characters"))
        }
    }

    @Test
    fun aRosterFetchOrARepairKeepsTheNickname() {
        val hub = "ab".repeat(8)
        val a = node("a")
        val b = node("b")
        trustEachOther(a, b, hub)
        a.setNickname(b.fp, "the NAS")
        a.trust.syncRoster(listOf(entryFor(b, TrustList.SOURCE_ROSTER, hub)), hub)
        assertEquals("the NAS", a.trust.get(b.fp)!!.nickname)
        val c = node("c")
        a.trust.addPaired(entryFor(c))
        a.setNickname(c.fp, "Brian's phone")
        a.trust.addPaired(entryFor(c))   // paired again
        assertEquals("Brian's phone", a.trust.get(c.fp)!!.nickname)
    }

    // --- several devices at once ----------------------------------------------------------------

    @Test
    fun allMyDevicesMeansYourOwnThatAllowIt() {
        val base = entryFor(node("x"))
        val peers = listOf(
            base.copy(fp = "1"),
            base.copy(fp = "2", relation = Perms.OTHER_DEVICE, allow = Perms.OTHER),
            base.copy(fp = "3", allow = Perms.OWN + (Perms.FILES to false)),
            base.copy(fp = "4", paused = true),
        )
        assertEquals(listOf("1", "4"), Perms.ownTargets(peers, Perms.FILES).map { it.fp })
        assertEquals(listOf("1", "3", "4"), Perms.ownTargets(peers, Perms.CLIPBOARD).map { it.fp })
    }

    @Test
    fun sendingToSeveralDevicesEachGetsItsOwnResult() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val d = node("d")
        for (x in listOf(b, c, d)) trustEachOther(a, x)
        a.setPerms(c.fp, paused = true)
        d.setPerms(a.fp, allow = mapOf(Perms.FILES to false))
        a.direct(d.fp)
        waitFor("d's word") { a.remotePerm[d.fp]?.allow?.get(Perms.FILES) == false }
        val f = small("notes.txt", "for everyone")
        val got = a.toEach(listOf(b.fp, c.fp, d.fp), 20_000) { fp -> a.sendPath(fp, f) }.associateBy { it.name }
        assertEquals("sent", got.getValue("b").outcome)
        assertEquals("waiting", got.getValue("c").outcome)
        assertEquals("waiting: c is paused: resume it to send", got.getValue("c").text)
        assertEquals("refused", got.getValue("d").outcome)
        assertEquals("d doesn't allow files from you", got.getValue("d").text)
        assertEquals("for everyone", File(downloads(b), "notes.txt").readText())
        assertFalse(File(downloads(d), "notes.txt").exists())
        // each its own job: c's goes on resume
        a.setPerms(c.fp, paused = false)
        waitFor("c gets it on resume") { File(downloads(c), "notes.txt").exists() }
        assertNotNull(got.getValue("c").job)
    }
}
