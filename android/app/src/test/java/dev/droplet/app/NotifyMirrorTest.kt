package dev.droplet.app

import android.app.NotificationManager
import dev.droplet.app.mesh.MeshHost
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.TrustList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.Collections

/**
 * Notification mirroring over the mesh, with no hub: what [NotifyMirror]
 * sends, to whom, and what it holds back, between two real nodes over TLS
 * on loopback (the "computer" is a JVM node that records what it's shown).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifyMirrorTest {
    private lateinit var tmp: File
    private val nodes = mutableListOf<MeshNode>()
    private val shown: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    private val removed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var clock = 1_000_000L
    private var enabled = true
    private var excluded = setOf<String>()
    private val dials = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        MeshIdentity.keystoreAllowed = false
        tmp = Files.createTempDirectory("notify-mirror").toFile()
    }

    @After
    fun tearDown() {
        nodes.forEach { it.close() }
        tmp.deleteRecursively()
    }

    private open inner class Computer(private val caps: List<String>) : MeshUnitTest.QuietHost() {
        override fun caps() = caps
        override fun onNotify(entry: TrustList.Entry, msg: JSONObject) { shown += msg }
        override fun onNotifyRemoved(entry: TrustList.Entry, key: String) { removed += key }
    }

    private fun node(name: String, host: MeshHost): MeshNode =
        MeshNode(host, File(tmp, name), null, port = 0, retryEveryMs = 60_000, bindAddress = InetAddress.getLoopbackAddress())
            .also { it.start(); nodes += it }

    /** A phone and a computer that trust each other; the phone knows where the computer listens. */
    private fun pair(computerCaps: List<String> = listOf("notify")): Pair<MeshNode, MeshNode> {
        val phone = node("phone${nodes.size}", MeshUnitTest.QuietHost())
        val computer = node("computer${nodes.size}", Computer(computerCaps))
        phone.trust.addPaired(TrustList.makeEntry(peerId = computer.peerId, name = "laptop", certPem = computer.identity.pem,
            source = TrustList.SOURCE_PAIRED, lan = listOf("127.0.0.1"), port = computer.listeningPort, os = "linux",
            caps = computerCaps))
        computer.trust.addPaired(TrustList.makeEntry(peerId = phone.peerId, name = "phone", certPem = phone.identity.pem,
            source = TrustList.SOURCE_PAIRED, port = phone.listeningPort))
        return phone to computer
    }

    /** Every JVM node says it's "android"; here the computer is told apart by its cap alone. */
    private fun mirror(phone: MeshNode) = NotifyMirror(
        node = { phone }, enabled = { enabled }, excluded = { excluded },
        background = { dials += it }, now = { clock }, target = { "notify" in it.caps },
    )

    private fun item(key: String, title: String = "Mum", text: String = "Dinner at 7", pkg: String = "com.chat") =
        JSONObject().put("key", key).put("package", pkg).put("app", "Chat").put("title", title).put("text", text)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, cond())
    }

    private fun runDials() {
        val todo = dials.toList()
        dials.clear()
        todo.forEach { it() }
    }

    @Test
    fun onlyComputersThatShowThemAreTargets() {
        fun entry(os: String, caps: List<String>) = TrustList.Entry("0123456789abcdef", "x", "f".repeat(64), "", "paired", os = os, caps = caps)
        assertTrue(NotifyMirror.wants(entry("linux", listOf("notify"))))
        assertTrue(NotifyMirror.wants(entry("windows", listOf("input", "notify"))))
        assertFalse(NotifyMirror.wants(entry("windows", listOf("input"))))      // switched off there, or too old
        assertFalse(NotifyMirror.wants(entry("android", listOf("notify"))))     // another phone
    }

    @Test
    fun silentAndDoNotDisturbOnesStayOnThePhone() {
        assertTrue(MirrorService.quiet(NotificationManager.IMPORTANCE_LOW, true))
        assertTrue(MirrorService.quiet(NotificationManager.IMPORTANCE_HIGH, false))
        assertFalse(MirrorService.quiet(NotificationManager.IMPORTANCE_DEFAULT, true))
        assertFalse(MirrorService.quiet(NotificationManager.IMPORTANCE_UNSPECIFIED, true))
    }

    @Test
    fun aNotificationIsDialledForThenGoesOverTheOpenLinkAndIsTakenAway() {
        val (phone, computer) = pair()
        val m = mirror(phone)
        m.posted(item("k1"))
        assertEquals(0, m.flush())            // no link yet: dialled in the background, not here
        assertEquals(1, dials.size)
        runDials()
        waitFor("shown on the computer") { shown.size == 1 }
        val got = shown.single()
        assertEquals("notify", got.getString("t"))
        assertEquals("k1", got.getString("key"))
        assertEquals("Chat", got.getString("app"))
        assertEquals("Mum", got.getString("title"))
        assertEquals("Dinner at 7", got.getString("text"))
        assertFalse(got.has("package"))

        // the link is open now: the next one goes straight over it
        m.posted(item("k2", title = "Bob"))
        assertEquals(1, m.flush())
        assertTrue(dials.isEmpty())
        waitFor("the second one") { shown.size == 2 }

        // the same notification posted again unchanged isn't shown again; changed, it is
        m.posted(item("k2", title = "Bob"))
        assertFalse(m.hasWork())
        m.posted(item("k2", title = "Bob", text = "and again"))
        assertEquals(1, m.flush())
        waitFor("the update") { shown.size == 3 }

        // gone from the phone: gone from the computer
        m.removed("k1")
        assertEquals(1, m.flush())
        waitFor("taken away") { removed == listOf("k1") }
        // one never sent isn't taken away anywhere
        m.removed("never")
        assertFalse(m.hasWork())
        assertEquals(phone.identity.fp, computer.trust.get(phone.identity.fp)?.fp)
    }

    @Test
    fun excludedSwitchedOffAndOtherPeersGetNothing() {
        val (phone, _) = pair(computerCaps = listOf("input"))   // a computer that doesn't show them
        val m = mirror(phone)
        m.posted(item("k1"))
        m.flush()
        assertTrue(dials.isEmpty())

        val (phone2, _) = pair()
        val m2 = mirror(phone2)
        excluded = setOf("com.bank")
        m2.posted(item("k1", pkg = "com.bank"))
        m2.flush()
        assertTrue(dials.isEmpty())           // an excluded app's text never leaves the phone
        m2.posted(item("k2"), alerting = false)
        assertFalse(m2.hasWork())             // silent on the phone, silent everywhere
        enabled = false
        m2.posted(item("k3"))
        assertFalse(m2.hasWork())
        Thread.sleep(300)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun aComputerThatIsntThereIsDialledAtMostOnceAMinute() {
        val phone = node("phone", MeshUnitTest.QuietHost())
        val away = node("away", Computer(listOf("notify")))
        phone.trust.addPaired(TrustList.makeEntry(peerId = away.peerId, name = "away", certPem = away.identity.pem,
            source = TrustList.SOURCE_PAIRED, lan = listOf("127.0.0.1"), port = 1, os = "linux", caps = listOf("notify")))
        val m = mirror(phone)
        m.posted(item("k1"))
        m.flush()
        assertEquals(1, dials.size)
        runDials()                             // nobody answers
        m.posted(item("k2"))
        m.flush()
        assertTrue("dialled again within a minute", dials.isEmpty())
        clock += NotifyMirror.DIAL_EVERY_MS
        m.posted(item("k3"))
        m.flush()
        assertEquals(1, dials.size)
    }

    @Test
    fun aBurstIsCappedAndTheNewestGo() {
        val (phone, _) = pair()
        val m = mirror(phone)
        m.posted(item("warm"))
        m.flush()
        runDials()
        waitFor("the link") { shown.size == 1 }
        clock += NotifyMirror.REFILL_MS * NotifyMirror.BURST   // the bucket is full again
        repeat(25) { m.posted(item("b$it", text = "message $it")) }
        assertEquals(NotifyMirror.BURST, m.flush())
        waitFor("the burst") { shown.size == 1 + NotifyMirror.BURST }
        assertEquals((15 until 25).map { "b$it" }, shown.drop(1).map { it.getString("key") })
        // straight after, nothing left in the bucket; a few seconds later, one more
        m.posted(item("late"))
        assertEquals(0, m.flush())
        clock += NotifyMirror.REFILL_MS
        m.posted(item("later"))
        assertEquals(1, m.flush())
    }
}
