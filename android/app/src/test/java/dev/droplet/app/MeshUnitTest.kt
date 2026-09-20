package dev.droplet.app

import dev.droplet.app.mesh.MeshFiles
import dev.droplet.app.mesh.MeshHost
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.MeshPairing
import dev.droplet.app.mesh.MeshTls
import dev.droplet.app.mesh.TrustList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.time.Instant

/**
 * The mesh peer's own pieces (docs/mesh.md §9), with no other device: the
 * certificate profile, the pairing arithmetic (against vectors from the
 * Linux reference), Range parsing, and the server's rules for who may do
 * what, over real TLS sockets on loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshUnitTest {
    private lateinit var tmp: File
    private val nodes = mutableListOf<MeshNode>()

    @Before
    fun setUp() {
        MeshIdentity.keystoreAllowed = false   // Robolectric has no Android Keystore
        tmp = Files.createTempDirectory("mesh-unit").toFile()
    }

    @After
    fun tearDown() {
        nodes.forEach { it.close() }
        tmp.deleteRecursively()
    }

    // --- identity -------------------------------------------------------------------

    @Test
    fun certificateFollowsTheProfile() {
        val id = MeshIdentity.loadOrCreate(File(tmp, "a"))
        val c = id.cert
        assertEquals(3, c.version)
        assertEquals("SHA256withECDSA", c.sigAlgName)
        assertEquals("EC", c.publicKey.algorithm)
        assertEquals(c.subjectX500Principal, c.issuerX500Principal)
        assertEquals("CN=droplet-peer-${id.localId}", c.subjectX500Principal.name)
        c.verify(c.publicKey)
        assertEquals(-1, c.basicConstraints)   // not a CA
        assertTrue(c.keyUsage[0])              // digitalSignature
        assertEquals(1, c.keyUsage.count { it })
        assertEquals(setOf("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), c.extendedKeyUsage.toSet())
        assertEquals(setOf("2.5.29.19", "2.5.29.15"), c.criticalExtensionOIDs)
        assertTrue("2.5.29.14" in c.nonCriticalExtensionOIDs)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), c.notBefore.toInstant())
        assertTrue(c.notAfter.toInstant().isAfter(Instant.parse("2050-01-01T00:00:00Z")))
        c.checkValidity()
        assertTrue(c.serialNumber.signum() > 0)
        assertEquals(MeshIdentity.sha256Hex(c.encoded), id.fp)
        assertTrue(TrustList.FINGERPRINT.matches(id.fp))
        // the PEM round-trips, and a second load is the same identity
        assertEquals(id.fp, MeshIdentity.sha256Hex(MeshIdentity.pemToDer(id.pem)))
        val again = MeshIdentity.loadOrCreate(File(tmp, "a"))
        assertEquals(id.fp, again.fp)
        assertEquals(MeshIdentity.STORAGE_FILE, again.storage)
        // and the pairing signature verifies with the certificate's key
        val data = "droplet".toByteArray()
        assertTrue(MeshPairing.verify(id.der, id.sign(data), data))
        assertFalse(MeshPairing.verify(id.der, id.sign(data), "other".toByteArray()))
    }

    @Test
    fun badPemIsRefused() {
        for (bad in listOf("", "hello", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")) {
            try {
                MeshIdentity.pemToDer(bad)
                fail("$bad was accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        val a = MeshIdentity.loadOrCreate(File(tmp, "a")).pem
        val two = a + a
        try {
            MeshIdentity.pemToDer(two)
            fail("two certificates were accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- pairing --------------------------------------------------------------------

    @Test
    fun pairingMatchesTheReference() {
        // from agent/droplet_agent/mesh/pairing.py
        assertEquals("deb0e38ced1e41de6f92e70e80c418d2d356afaaa99e26f5939dbc7d3ef4772a", MeshPairing.commitment("33".repeat(32)))
        assertEquals("1916", MeshPairing.code("11".repeat(32), "22".repeat(32), "33".repeat(32), "44".repeat(32)))
        assertEquals("1669", MeshPairing.code("a1".repeat(32), "b2".repeat(32), "c3".repeat(32), "d4".repeat(32)))
        assertEquals("droplet-pair-v1\na\nb\nc\nd", String(MeshPairing.transcript("a", "b", "c", "d")))
    }

    @Test
    fun responderChecksTheProof() {
        val r = MeshIdentity.loadOrCreate(File(tmp, "r"))
        val i = MeshIdentity.loadOrCreate(File(tmp, "i"))
        val incoming = MeshPairing.Incoming(r.fp)
        val nI = MeshPairing.newNonce()
        fun open() = incoming.open(JSONObject().put("id", "0123456789abcdef").put("name", "laptop").put("os", "linux")
            .put("cert", i.pem).put("commit", MeshPairing.commitment(nI)), "fedcba9876543210", "phone")
        // malformed
        assertEquals(400, incoming.open(JSONObject(), "x", "y").first)
        assertEquals(409, incoming.open(JSONObject().put("id", "0123456789abcdef").put("cert", r.pem)
            .put("commit", "00".repeat(32)), "x", "y").first)
        // a wrong nonce, then a wrong signature: dropped
        var (st, out) = open()
        assertEquals(200, st)
        assertEquals(r.fp, out.getString("fp"))
        assertEquals(403, incoming.confirm(out.getString("request"), JSONObject().put("nonce", MeshPairing.newNonce()).put("sig", "")).first)
        assertEquals(404, incoming.confirm(out.getString("request"), JSONObject()).first)
        open().let { st = it.first; out = it.second }
        val badSig = i.sign("something else".toByteArray())
        assertEquals(403, incoming.confirm(out.getString("request"), JSONObject().put("nonce", nI)
            .put("sig", android.util.Base64.encodeToString(badSig, android.util.Base64.NO_WRAP))).first)
        // the real thing
        open().let { st = it.first; out = it.second }
        val nR = out.getString("nonce")
        val sig = i.sign(MeshPairing.transcript(i.fp, r.fp, nI, nR))
        var ready: MeshPairing.Request? = null
        incoming.onReady = { ready = it }
        assertEquals(200, incoming.confirm(out.getString("request"), JSONObject().put("nonce", nI)
            .put("sig", android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP))).first)
        assertEquals(MeshPairing.code(i.fp, r.fp, nI, nR), ready!!.code)
        assertEquals("waiting", incoming.status(out.getString("request")).second.getString("state"))
        val answered = incoming.answer(out.getString("request"), true)!!
        assertEquals(i.fp, MeshIdentity.sha256Hex(answered.der!!))
        assertEquals("accepted", incoming.status(out.getString("request")).second.getString("state"))
        assertEquals(404, incoming.status("0".repeat(32)).first)
    }

    @Test
    fun pairingIsRateLimited() {
        val r = MeshIdentity.loadOrCreate(File(tmp, "r"))
        val i = MeshIdentity.loadOrCreate(File(tmp, "i"))
        val incoming = MeshPairing.Incoming(r.fp)
        fun open() = incoming.open(JSONObject().put("id", "0123456789abcdef").put("cert", i.pem)
            .put("commit", MeshPairing.commitment(MeshPairing.newNonce())), "fedcba9876543210", "phone").first
        repeat(3) { assertEquals(200, open()) }
        assertEquals("three open requests at most", 429, open())
    }

    // --- files ----------------------------------------------------------------------

    @Test
    fun rangesLikeTheReference() {
        fun r(h: String?, size: Long) = MeshFiles.parseRange(h, size)
        assertEquals(MeshFiles.Range.Whole, r(null, 100))
        assertEquals(MeshFiles.Range.Span(10, 99), r("bytes=10-", 100))
        assertEquals(MeshFiles.Range.Span(10, 20), r("bytes=10-20", 100))
        assertEquals(MeshFiles.Range.Span(10, 99), r("bytes=10-500", 100))
        assertEquals(MeshFiles.Range.Span(90, 99), r("bytes=-10", 100))
        assertEquals(MeshFiles.Range.Span(0, 99), r("bytes=-500", 100))
        assertEquals(MeshFiles.Range.Bad, r("bytes=100-", 100))
        assertEquals(MeshFiles.Range.Bad, r("bytes=20-10", 100))
        assertEquals(MeshFiles.Range.Bad, r("bytes=-0", 100))
        assertEquals(MeshFiles.Range.Whole, r("bytes=1-2,4-5", 100))
        assertEquals(MeshFiles.Range.Whole, r("items=1-2", 100))
    }

    @Test
    fun namesCantEscape() {
        assertEquals("passwd", MeshFiles.safeName("../../etc/passwd"))
        assertEquals("x.txt", MeshFiles.safeName("C:\\Windows\\x.txt"))
        assertEquals("hidden", MeshFiles.safeName(".hidden"))
        assertEquals("file", MeshFiles.safeName(".."))
        assertEquals("ab", MeshFiles.safeName("a\u0000b"))
        assertTrue(MeshFiles.safeName("x".repeat(300) + ".jpg").let { it.endsWith(".jpg") && it.toByteArray().size <= 200 })
        assertEquals("photo (2).jpg", MeshDownloads.unique("photo.jpg") { it == "photo (2).jpg" })
    }

    // --- the server's rules, over real sockets ------------------------------------------------

    internal open class QuietHost : MeshHost {
        override fun deviceName() = "unit"
        override fun hubDeviceId(): String? = null
        override fun hubId(): String? = null
        override fun caps() = listOf<String>()
        override fun lastStates() = mapOf<String, Any?>()
        override fun localAddresses() = listOf<String>()
        override fun onText(entry: TrustList.Entry, body: String, ts: Double) = Unit
        override fun onRing(entry: TrustList.Entry) = Unit
        override fun onRingStop(entry: TrustList.Entry) = Unit
        override fun onClip(entry: TrustList.Entry, text: String) = Unit
        override fun onNotify(entry: TrustList.Entry, msg: JSONObject) = Unit
        override fun onNotifyRemoved(entry: TrustList.Entry, key: String) = Unit
        override fun onRemote(entry: TrustList.Entry, msg: JSONObject, reply: (JSONObject) -> Boolean) = Unit
        override fun saveFile(entry: TrustList.Entry, part: File, name: String, mime: String) = part.path
        override fun hubConnected() = false
        override fun hubOnline(deviceId: String) = false
        override fun hubSend(msg: JSONObject) = false
        override fun hubText(deviceId: String, body: String) = throw IOException("no hub")
        override fun hubUpload(deviceId: String, source: String, name: String, mime: String, size: Long) = throw IOException("no hub")
        override fun hubRing(deviceId: String, stop: Boolean) = throw IOException("no hub")
        override fun openSource(source: String, from: Long): InputStream = File(source).inputStream().also { it.skip(from) }
        override fun sourceSize(source: String) = File(source).takeIf { it.isFile }?.length()
        override fun spool(source: String, dest: File) { File(source).copyTo(dest, true) }
    }

    private fun node(name: String): MeshNode =
        MeshNode(QuietHost(), File(tmp, name), null, port = 0, bindAddress = InetAddress.getLoopbackAddress())
            .also { it.start(); nodes += it }

    /** One request as [who] (null: no certificate); the status, or null if TLS refused it. */
    private fun request(port: Int, who: MeshIdentity?, path: String, method: String = "GET", body: String? = null): Int? = try {
        val c = MeshTls.client(who, null)
        val req = Request.Builder().url("https://127.0.0.1:$port$path").apply {
            if (method == "POST") post((body ?: "").toRequestBody("application/json".toMediaType()))
        }.build()
        c.newCall(req).execute().use { it.code }
    } catch (e: IOException) {
        null
    }

    @Test
    fun theServerLetsInOnlyTrustedPeers() {
        val phone = node("phone")
        val port = phone.listeningPort
        val stranger = MeshIdentity.loadOrCreate(File(tmp, "stranger"))
        val friend = MeshIdentity.loadOrCreate(File(tmp, "friend"))

        // no certificate: pairing only, everything else 403 before any body is read
        assertEquals(403, request(port, null, "/mesh"))
        assertEquals(403, request(port, null, "/mesh/files/" + "0".repeat(32)))
        assertEquals(400, request(port, null, "/mesh/pair", "POST", "{}"))
        assertEquals(404, request(port, null, "/mesh/pair/" + "0".repeat(32)))

        // a certificate that isn't trusted fails the handshake: not even a 403
        val before = phone.refused
        assertNull(request(port, stranger, "/mesh/files/" + "0".repeat(32)))
        assertNull(request(port, stranger, "/mesh"))
        assertTrue(phone.refused > before)

        // trusted: in (and an offer that isn't there, or isn't for it, is a 404)
        phone.trust.addPaired(TrustList.makeEntry(peerId = "0123456789abcdef", name = "friend", certPem = friend.pem,
            source = TrustList.SOURCE_PAIRED))
        val client = MeshTls.client(friend, null)
        fun friendly(path: String) = client.newCall(Request.Builder().url("https://127.0.0.1:$port$path").build()).execute().use { it.code }
        assertEquals(404, friendly("/mesh/files/" + "0".repeat(32)))
        assertEquals(404, friendly("/nothing"))

        // unpaired: refused again, even on a TLS session it could resume
        phone.trust.remove(friend.fp)
        val after = try {
            friendly("/mesh/files/" + "0".repeat(32))
        } catch (e: IOException) {
            null
        }
        assertTrue("an unpaired peer gets nothing (got $after)", after == null || after == 403)
    }

    @Test
    fun theClientChecksTheServersFingerprint() {
        val phone = node("phone")
        val other = MeshIdentity.loadOrCreate(File(tmp, "other"))
        val me = MeshIdentity.loadOrCreate(File(tmp, "me"))
        // the right fingerprint: through; any other: refused in the handshake, with nothing sent
        val ok = MeshTls.client(null, phone.identity.fp).newCall(Request.Builder()
            .url("https://127.0.0.1:${phone.listeningPort}/mesh/pair/" + "0".repeat(32)).build()).execute().use { it.code }
        assertTrue(ok == 404 || ok == 403)
        try {
            MeshTls.client(me, other.fp).newCall(Request.Builder()
                .url("https://127.0.0.1:${phone.listeningPort}/mesh/pair").post("{}".toRequestBody()).build()).execute().close()
            fail("a server with the wrong certificate was accepted")
        } catch (e: IOException) {
            assertNotNull(dev.droplet.app.mesh.MeshTlsErrors.mismatch(e))
        }
    }

    @Test
    fun twoPhonesPairAndTalk() {
        val a = node("a")
        val b = node("b")
        // b's owner answers the request when it arrives
        b.incoming.onReady = { r -> Thread { b.pairAnswer(r.request, true) }.start() }
        val og = a.pairStart("127.0.0.1", b.listeningPort, b.identity.fp)
        val done = java.util.concurrent.CountDownLatch(1)
        a.pairConfirm(og.request!!, true) { done.countDown() }
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(MeshPairing.ACCEPTED, og.state)
        assertNotNull(a.trust.get(b.identity.fp))
        assertNotNull(b.trust.get(a.identity.fp))
        // a text, acknowledged, and in both chats
        val job = a.sendText(b.identity.fp, "hello b")
        val j = a.awaitJob(job.getString("id"), 20_000)!!
        assertEquals("done", j.getString("state"))
        assertEquals("lan", j.getString("route"))
        assertTrue(b.chat.recent(a.identity.fp).any { it.getString("body") == "hello b" && it.getString("dir") == "in" })
        // unpairing tells the other side
        assertTrue(a.unpair(b.identity.fp))
        val end = System.currentTimeMillis() + 5_000
        while (b.trust.get(a.identity.fp) != null && System.currentTimeMillis() < end) Thread.sleep(50)
        assertNull(b.trust.get(a.identity.fp))
    }

    /**
     * A real peer on the LAN (DROPLET_TEST_REAL_PEER=host:port:fingerprint):
     * the phone's TLS client shakes hands with it, pinned to its fingerprint,
     * and closes. Not a byte of HTTP is sent, so it's safe against a device in use.
     */
    @Test
    fun handshakeOnlyWithARealPeer() {
        val spec = System.getProperty("droplet.testRealPeer").orEmpty()
        org.junit.Assume.assumeTrue("set DROPLET_TEST_REAL_PEER to shake hands with a real peer", spec.isNotEmpty())
        val (host, port, fp) = spec.split(":").let { Triple(it[0], it[1].toInt(), it[2]) }
        val tm = MeshTls.ClientTrust(fp)
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        java.net.Socket().use { raw ->
            raw.connect(java.net.InetSocketAddress(host, port), 5_000)
            raw.soTimeout = 5_000
            (ctx.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).use { s ->
                s.startHandshake()
                assertEquals(fp, MeshTls.peerFingerprint(s.session))
            }
        }
        // and a wrong pin is refused in the handshake
        val wrong = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(MeshTls.ClientTrust("00".repeat(32))), null) }
        try {
            java.net.Socket().use { raw ->
                raw.connect(java.net.InetSocketAddress(host, port), 5_000)
                (wrong.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
            }
            fail("a wrong pin was accepted")
        } catch (e: IOException) {
            assertNotNull(dev.droplet.app.mesh.MeshTlsErrors.mismatch(e))
        }
    }

    @Test
    fun parsesAddresses() {
        assertEquals("192.168.1.20" to 1739, PeersActivity.parseAddress("192.168.1.20"))
        assertEquals("192.168.1.20" to 1740, PeersActivity.parseAddress(" 192.168.1.20:1740 "))
        assertEquals("fd00::1" to 1741, PeersActivity.parseAddress("[fd00::1]:1741"))
        assertEquals("fd00::1" to 1739, PeersActivity.parseAddress("fd00::1"))
        assertNull(PeersActivity.parseAddress("not an address"))
        assertNull(PeersActivity.parseAddress("1.2.3.4:99999"))
        assertTrue(MeshNode.isTailnet("100.86.152.66"))
        assertFalse(MeshNode.isTailnet("100.128.0.1"))
        assertTrue(MeshNode.isTailnet("fd7a:115c:a1e0::1"))
        assertFalse(MeshNode.isTailnet("192.168.1.2"))
        assertEquals(listOf("192.168.1.2", "fd00::1"), TrustList.cleanAddresses(listOf("192.168.1.2", "fe80::1", "0.0.0.0",
            "224.0.0.1", "example.com", "fd00:0::1", "192.168.1.2")))
        assertEquals(listOf("fd7a:115c:a1e0::1", "1:0:2::", "2001:db8::1:0:0:1"),
            TrustList.cleanAddresses(listOf("fd7a:115c:a1e0:0:0:0:0:1", "1:0:2:0:0:0:0:0", "2001:db8:0:0:1:0:0:1")))
        // the TXT records other peers announce
        val seen = NsdPeerDirectory.parse("192.168.1.9", 1739, mapOf("id" to "0123456789ab".toByteArray(),
            "fp" to "ab".repeat(32).toByteArray(), "name" to "t15".toByteArray(), "os" to "linux".toByteArray(),
            "caps" to "media,input".toByteArray(), "hub" to "".toByteArray(), "v" to "1".toByteArray()))!!
        assertEquals(listOf("input", "media"), seen.caps)
        assertNull(NsdPeerDirectory.parse("192.168.1.9", 1739, mapOf("id" to "zz".toByteArray())))
    }
}
