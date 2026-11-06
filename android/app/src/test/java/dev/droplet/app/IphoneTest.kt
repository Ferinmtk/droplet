package dev.droplet.app

import android.util.Base64
import dev.droplet.app.mesh.Iphone
import dev.droplet.app.mesh.IphoneBridge
import dev.droplet.app.mesh.IphoneChannel
import dev.droplet.app.mesh.IphoneConn
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.Outbox
import dev.droplet.app.mesh.Perms
import dev.droplet.app.mesh.TrustList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The iPhone link's protocol (docs/iphone.md §3) on Android, as the
 * reference's agent/tests/test_webrtc.py: a fake web app (its key made the
 * way WebCrypto makes one) on a fake channel, against a real node. Pairing
 * with the same code on both sides, the challenge bound to the DTLS session,
 * messages and files both ways, the clipboard, and unpairing. The transport
 * itself (Go, Pion) is tested against real browsers by
 * android/iphonelink/test_browser.mjs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IphoneTest {
    private lateinit var tmp: File
    private val nodes = mutableListOf<MeshNode>()

    @Before
    fun setUp() {
        MeshIdentity.keystoreAllowed = false
        tmp = Files.createTempDirectory("iphone").toFile()
    }

    @After
    fun tearDown() {
        nodes.forEach { it.iphone?.close(); it.close() }
        tmp.deleteRecursively()
    }

    private class Host(private val downloads: File) : MeshUnitTest.QuietHost() {
        val texts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val clips: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun deviceName() = "Pixel"
        override fun localAddresses() = listOf("192.168.1.20", "100.101.102.103")
        override fun onText(entry: TrustList.Entry, body: String, ts: Double) { texts += body }
        override fun applyClip(entry: TrustList.Entry, text: String): String? { clips += text; return null }
        override fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String): String {
            downloads.mkdirs()
            val dest = File(downloads, name)
            part.copyTo(dest, true)
            return dest.path
        }
    }

    /** The web app: a P-256 key, its own DTLS fingerprint, and what the phone sent it. */
    private class Browser(override val dtlsFp: String = MeshPairing.newNonce()) : IphoneChannel {
        val key: KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val spki: String = Base64.encodeToString(key.public.encoded, Base64.NO_WRAP)
        val fp: String = MeshIdentity.sha256Hex(key.public.encoded)
        val got = LinkedBlockingQueue<Any>()
        @Volatile var closed = false
        lateinit var conn: IphoneConn

        override val address = "192.168.1.50"
        override fun sendText(text: String): Boolean { got.put(text); return !closed }
        override fun sendBinary(data: ByteArray): Boolean { got.put(data); return !closed }
        override fun buffered() = 0L
        override fun close() { closed = true }

        fun send(msg: JSONObject) = conn.text(msg.toString())

        /** WebCrypto's signature: raw r‖s, 64 bytes. */
        fun sign(data: ByteArray, raw: Boolean = true): String {
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(data); sign() }
            return Base64.encodeToString(if (raw) derToRaw(der) else der, Base64.NO_WRAP)
        }

        /** The next text frame of type [t] (others before it are skipped). */
        fun next(t: String, ms: Long = 10_000): JSONObject {
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) fail("no $t")
                val f = got.poll(left, TimeUnit.MILLISECONDS) ?: fail("no $t")
                if (f is String) JSONObject(f).let { if (it.optString("t") == t) return it }
            }
        }

        /** ASN.1 SEQUENCE { INTEGER r, INTEGER s } → r‖s, 32 bytes each. */
        private fun derToRaw(der: ByteArray): ByteArray {
            fun to32(v: ByteArray): ByteArray {
                val b = BigInteger(1, v).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - b.size) + b
            }
            val rLen = der[3].toInt()
            val r = der.copyOfRange(4, 4 + rLen)
            val sLen = der[5 + rLen].toInt()
            val s = der.copyOfRange(6 + rLen, 6 + rLen + sLen)
            return to32(r) + to32(s)
        }
    }

    private fun setup(): Triple<MeshNode, IphoneBridge, Host> {
        val dir = File(tmp, "phone-${nodes.size}")
        val h = Host(File(dir, "downloads"))
        val n = MeshNode(h, dir, null, port = 0, retryEveryMs = 60_000, bindAddress = InetAddress.getLoopbackAddress())
        n.start()
        nodes += n
        val bridge = IphoneBridge(n, h)
        bridge.port = 1739
        n.iphone = bridge
        return Triple(n, bridge, h)
    }

    private fun connect(bridge: IphoneBridge, b: Browser): JSONObject {
        b.conn = bridge.opened(b)
        return b.next("server-hello")
    }

    /** Pairs [b] the way the web app does; returns the code both screens showed. */
    private fun pair(node: MeshNode, bridge: IphoneBridge, b: Browser, relation: String = Perms.OWN_DEVICE): String {
        connect(bridge, b)
        val nC = MeshPairing.newNonce()
        b.send(JSONObject().put("t", "pair").put("v", 1).put("key", b.spki).put("name", "Ferrin's iPhone").put("os", "ios")
            .put("token", bridge.newToken()).put("commit", MeshPairing.commitment(nC)))
        val got = b.next("pair-nonce")
        val nR = got.getString("nonce")
        b.send(JSONObject().put("t", "pair-confirm").put("nonce", nC)
            .put("sig", b.sign(MeshPairing.transcript(b.fp, node.identity.fp, nC, nR))))
        assertEquals("waiting", b.next("pair-state").getString("state"))
        val req = node.incoming.waiting().single()
        assertTrue(req.browser)
        val code = MeshPairing.code(b.fp, node.identity.fp, nC, nR)
        assertEquals("both screens show the same code", code, req.code)
        node.pairAnswer(req.request, true, relation)
        assertEquals("accepted", b.next("pair-state").getString("state"))
        return code
    }

    private fun auth(node: MeshNode, b: Browser): JSONObject {
        b.send(JSONObject().put("t", "auth").put("v", 1).put("key", b.spki).put("name", "Ferrin's iPhone")
            .put("features", org.json.JSONArray(listOf("cancel", "link", "rename")))
            .put("sig", b.sign(Iphone.authTranscript(b.fp, node.identity.fp, b.dtlsFp, b.conn.nonce))))
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val f = b.got.poll(1, TimeUnit.SECONDS) as? String ?: continue
            val m = JSONObject(f)
            if (m.optString("t") == "welcome" || m.optString("t") == "auth-failed") return m
        }
        fail("no answer to auth")
        throw AssertionError()
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    // --- the QR code ---------------------------------------------------------------

    @Test
    fun the_qr_code_says_where_this_phone_is_and_what_to_pin() {
        val (node, bridge) = setup()
        val link = bridge.qrLink()!!
        assertTrue(link.startsWith("https://droplet.noxeratech.com/app/#pair="))
        val data = JSONObject(String(Base64.decode(link.substringAfter("#pair="), Base64.URL_SAFE), Charsets.UTF_8))
        assertEquals(1, data.getInt("v"))
        assertEquals("Pixel", data.getString("n"))
        assertEquals(node.peerId, data.getString("i"))
        assertEquals(node.identity.fp, MeshPairing.hex(Base64.decode(data.getString("f"), Base64.URL_SAFE)))
        assertEquals(listOf("192.168.1.20", "100.101.102.103"), (0 until data.getJSONArray("a").length()).map { data.getJSONArray("a").getString(it) })
        assertEquals(1739, data.getInt("p"))
        assertTrue(bridge.tokenOk(data.getString("t")))
        assertFalse(bridge.tokenOk("not-a-token"))
    }

    // --- pairing and who it is ---------------------------------------------------------

    @Test
    fun an_iphone_pairs_with_the_same_code_and_then_proves_who_it_is() {
        val (node, bridge) = setup()
        val b = Browser()
        val hello = connect(bridge, b)
        assertEquals(node.identity.fp, hello.getString("fp"))
        assertEquals("android", hello.getString("os"))
        // not paired yet
        b.send(JSONObject().put("t", "auth").put("v", 1).put("key", b.spki).put("name", "x")
            .put("sig", b.sign(Iphone.authTranscript(b.fp, node.identity.fp, b.dtlsFp, hello.getString("nonce")))))
        assertFalse(b.next("auth-failed").getBoolean("paired"))
        b.conn.close()

        val code = pair(node, bridge, b)
        assertEquals(4, code.length)
        val e = node.trust.get(b.fp)!!
        assertTrue(e.isBrowser)
        assertEquals("Ferrin's iPhone", e.name)
        assertEquals("", e.certPem)
        assertFalse("a browser is never a TLS peer", node.trusted(b.fp))

        val welcome = auth(node, b)
        assertEquals("welcome", welcome.getString("t"))
        assertEquals(node.peerId, welcome.getString("id"))
        assertNotNull(welcome.optJSONObject("perm"))
        waitFor("the link") { node.openLink(b.fp) != null }
        assertEquals("webrtc", node.route(e))
    }

    @Test
    fun a_pairing_needs_a_token_from_a_qr_code_shown_here() {
        val (_, bridge) = setup()
        val b = Browser()
        connect(bridge, b)
        b.send(JSONObject().put("t", "pair").put("v", 1).put("key", b.spki).put("name", "x").put("token", "made-up")
            .put("commit", MeshPairing.commitment(MeshPairing.newNonce())))
        assertTrue(b.next("pair-failed").getString("error").contains("expired"))
    }

    @Test
    fun the_proof_is_bound_to_this_dtls_session() {
        val (node, bridge) = setup()
        val b = Browser()
        pair(node, bridge, b)
        b.conn.close()
        // someone relaying the app's messages has their own DTLS certificate
        val relay = Browser(dtlsFp = MeshPairing.newNonce())
        val hello = connect(bridge, relay)
        relay.send(JSONObject().put("t", "auth").put("v", 1).put("key", b.spki).put("name", "x")
            .put("sig", b.sign(Iphone.authTranscript(b.fp, node.identity.fp, b.dtlsFp, hello.getString("nonce")))))
        val no = relay.next("auth-failed")
        assertTrue(no.getBoolean("paired"))
        assertTrue(relay.closed)
        // and a DER signature is fine too
        val again = Browser(dtlsFp = b.dtlsFp)
        val h2 = connect(bridge, again)
        again.send(JSONObject().put("t", "auth").put("v", 1).put("key", b.spki).put("name", "x")
            .put("sig", b.sign(Iphone.authTranscript(b.fp, node.identity.fp, b.dtlsFp, h2.getString("nonce")), raw = false)))
        assertEquals("welcome", again.next("welcome").getString("t"))
    }

    // --- messages, files and the clipboard -------------------------------------------------------

    private fun paired(): Triple<MeshNode, Browser, Host> {
        val (node, bridge, h) = setup()
        val b = Browser()
        pair(node, bridge, b)
        assertEquals("welcome", auth(node, b).getString("t"))
        waitFor("the link") { node.openLink(b.fp) != null }
        return Triple(node, b, h)
    }

    @Test
    fun messages_go_both_ways() {
        val (node, b, h) = paired()
        b.send(JSONObject().put("t", "text").put("id", "msg-0001").put("body", "hi from the iPhone").put("ts", 1.0))
        assertEquals("msg-0001", b.next("ack").getString("id"))
        waitFor("the text") { h.texts.contains("hi from the iPhone") }

        val job = node.sendText(b.fp, "hello iPhone")
        val got = b.next("text")
        assertEquals("hello iPhone", got.getString("body"))
        b.send(JSONObject().put("t", "ack").put("id", got.getString("id")))
        waitFor("sent") { node.outbox.get(job.getString("id"))?.optString("state") == Outbox.DONE }
    }

    @Test
    fun a_file_from_the_iphone_is_saved_and_acknowledged() {
        val (_, b, _) = paired()
        val data = ByteArray(40_000).also { SecureRandom().nextBytes(it) }
        val fid = MeshPairing.newNonce().take(32)
        b.send(JSONObject().put("t", "file").put("id", fid).put("name", "Homework.jpg").put("size", data.size).put("mime", "image/jpeg"))
        val key = MeshPairing.unhex(fid.take(16))
        var at = 0
        while (at < data.size) {
            val n = minOf(Iphone.CHUNK, data.size - at)
            b.conn.binary(key + data.copyOfRange(at, at + n))
            at += n
        }
        b.send(JSONObject().put("t", "file-end").put("id", fid))
        assertEquals(fid, b.next("ack").getString("id"))
        val saved = File(tmp, "phone-0/downloads/Homework.jpg")
        assertArrayEquals(data, saved.readBytes())
        // sent again (the ack was lost): acknowledged, not saved twice
        b.send(JSONObject().put("t", "file").put("id", fid).put("name", "Homework.jpg").put("size", data.size).put("mime", "image/jpeg"))
        assertEquals(fid, b.next("ack").getString("id"))
    }

    @Test
    fun a_file_that_comes_short_is_refused() {
        val (_, b, _) = paired()
        val fid = MeshPairing.newNonce().take(32)
        b.send(JSONObject().put("t", "file").put("id", fid).put("name", "x.bin").put("size", 100).put("mime", "application/octet-stream"))
        b.conn.binary(MeshPairing.unhex(fid.take(16)) + ByteArray(60))
        b.send(JSONObject().put("t", "file-end").put("id", fid))
        assertTrue(b.next("nack").getString("error").contains("60 of 100"))
    }

    @Test
    fun a_file_goes_to_the_iphone_over_the_channel() {
        val (node, b, _) = paired()
        val src = File(tmp, "Payslip_October.pdf").apply { writeBytes(ByteArray(50_000).also { SecureRandom().nextBytes(it) }) }
        val job = node.sendFile(b.fp, src.path, src.name, "application/pdf", src.length())
        val offer = b.next("file")
        val fid = offer.getString("id")
        assertEquals("Payslip_October.pdf", offer.getString("name"))
        assertEquals(src.length(), offer.getLong("size"))
        val out = java.io.ByteArrayOutputStream()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val f = b.got.poll(5, TimeUnit.SECONDS) ?: break
            if (f is ByteArray) {
                assertEquals(fid.take(16), MeshPairing.hex(f.copyOf(8)))
                assertTrue(f.size <= 8 + Iphone.CHUNK)
                out.write(f, 8, f.size - 8)
            } else if (JSONObject(f as String).optString("t") == "file-end") break
        }
        assertArrayEquals(src.readBytes(), out.toByteArray())
        b.send(JSONObject().put("t", "ack").put("id", fid))
        waitFor("sent") { node.outbox.get(job.getString("id"))?.optString("state") == Outbox.DONE }
    }

    @Test
    fun the_iphones_clipboard_is_applied_and_answered() {
        val (_, b, h) = paired()
        b.send(JSONObject().put("t", "clip").put("id", "clip-0001").put("text", "M-PESA code QJK7XH2L5P"))
        assertEquals("clip-0001", b.next("ack").getString("id"))
        assertEquals(listOf("M-PESA code QJK7XH2L5P"), h.clips.toList())
    }

    @Test
    fun someone_elses_iphone_gets_no_clipboard_in() {
        val (node, bridge, h) = setup()
        val b = Browser()
        pair(node, bridge, b, relation = Perms.OTHER_DEVICE)
        auth(node, b)
        waitFor("the link") { node.openLink(b.fp) != null }
        b.send(JSONObject().put("t", "clip").put("id", "clip-0002").put("text", "secret"))
        val no = b.next("nack")
        assertEquals("clipboard", no.getString("cap"))
        assertTrue(h.clips.isEmpty())
    }

    @Test
    fun unpairing_tells_a_connected_iphone() {
        val (node, b, _) = paired()
        node.unpair(b.fp)
        b.next("unpair")
        assertNull(node.trust.get(b.fp))
    }

    @Test
    fun the_iphone_saying_unpair_removes_it() {
        val (node, b, _) = paired()
        b.send(JSONObject().put("t", "unpair"))
        waitFor("removed") { node.trust.get(b.fp) == null }
    }

    @Test
    fun a_paired_iphone_survives_a_restart() {
        val (node, bridge, _) = setup()
        val b = Browser()
        pair(node, bridge, b)
        val again = TrustList(File(tmp, "phone-0/trust.json"), node.identity.fp)
        val e = again.get(b.fp)!!
        assertTrue(e.isBrowser)
        assertEquals(b.spki, e.key)
    }
}
