package dev.droplet.app.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The protocol against androidtvremote2 itself: every vector in
 * src/test/resources/tv/vectors.json was written or read by the library's
 * own code (src/test/python/tv_vectors.py). Pairing secrets for certificates
 * made by the library's generator; the bytes of every message droplet
 * sends; how every message the TV sends is read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvProtocolTest {
    private val v: JSONObject by lazy { JSONObject(File("src/test/resources/tv/vectors.json").readText()) }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** A framed vector -> the message inside (the varint length checked on the way). */
    private fun unframe(s: String): ByteArray = Frames.read(ByteArrayInputStream(hex(s)))!!

    private fun cert(der: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun pem(p: String): X509Certificate =
        cert(Base64.getMimeDecoder().decode(p.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")))

    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

    // --- the pairing secret ------------------------------------------------------------------

    @Test
    fun pairingSecretMatchesTheLibrary() {
        val cases = objects(v.getJSONArray("pairing"))
        assertEquals(3, cases.size)
        var checked = 0
        for (c in cases) {
            val client = TvSecret.rsa(pem(c.getString("client_pem")))
            val server = TvSecret.rsa(cert(Base64.getDecoder().decode(c.getString("server_der"))))
            for (g in objects(c.getJSONArray("good"))) {
                val secret = TvSecret.compute(client, server, g.getString("code"))
                assertNotNull("code ${g.getString("code")}", secret)
                assertEquals(g.getString("secret"), secret!!.hex())
                // and the message that carries it, byte for byte
                assertEquals(g.getString("framed"), Frames.frame(Polo.secret(secret)).hex())
                checked++
            }
            val bad = (0 until c.getJSONArray("bad").length()).map { c.getJSONArray("bad").getString(it) }
            assertTrue(bad.isNotEmpty())
            for (code in bad) assertNull("the library refused $code", TvSecret.compute(client, server, code))
        }
        assertEquals(15, checked)
    }

    @Test
    fun everyCheckByteButOneIsRefused() {
        // for one nonce exactly one of the 256 first bytes is right, as the library found
        val c = objects(v.getJSONArray("pairing"))[0]
        val client = TvSecret.rsa(pem(c.getString("client_pem")))
        val server = TvSecret.rsa(cert(Base64.getDecoder().decode(c.getString("server_der"))))
        val good = objects(c.getJSONArray("good"))[1].getString("code")
        val accepted = (0..255).map { "%02X".format(it) + good.substring(2) }.filter { TvSecret.compute(client, server, it) != null }
        assertEquals(listOf(good), accepted)
    }

    @Test
    fun numbersAreHashedAsMinimalUnsignedBytes() {
        assertArrayEquals(byteArrayOf(1, 0, 1), TvSecret.unsigned(BigInteger.valueOf(65537)))
        assertArrayEquals(byteArrayOf(3), TvSecret.unsigned(BigInteger.valueOf(3)))
        // a top bit set: BigInteger adds a sign byte, which isn't part of the number
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0), TvSecret.unsigned(BigInteger.valueOf(0x8000)))
        // leading zero nibble: one byte 0x0a, as polo hashes it (the library can't hash this one at all)
        assertArrayEquals(byteArrayOf(0x0a, 0xbc.toByte()), TvSecret.unsigned(BigInteger.valueOf(0x0abc)))
    }

    @Test
    fun codesAreSixHexDigits() {
        val c = objects(v.getJSONArray("pairing"))[0]
        val client = TvSecret.rsa(pem(c.getString("client_pem")))
        val server = TvSecret.rsa(cert(Base64.getDecoder().decode(c.getString("server_der"))))
        for (bad in listOf("", "12345", "1234567", "12345G", " 12345")) {
            try {
                TvSecret.compute(client, server, bad)
                fail("accepted '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- pairing messages ---------------------------------------------------------------------

    @Test
    fun pairingMessagesMatchTheLibrary() {
        val p = v.getJSONObject("polo")
        val req = p.getJSONObject("pairing_request")
        assertEquals(req.getString("framed"), Frames.frame(Polo.pairingRequest(req.getString("client_name"))).hex())
        assertEquals(p.getString("options"), Frames.frame(Polo.options()).hex())
        assertEquals(p.getString("configuration"), Frames.frame(Polo.configuration()).hex())
    }

    @Test
    fun pairingRepliesAreRead() {
        val p = v.getJSONObject("polo")
        Polo.parse(unframe(p.getString("in_pairing_request_ack"))).let {
            assertEquals(Polo.STATUS_OK, it.status)
            assertEquals(Polo.Kind.PAIRING_REQUEST_ACK, it.kind)
            assertEquals("Living room TV", it.serverName)
        }
        assertEquals(Polo.Kind.OPTIONS, Polo.parse(unframe(p.getString("in_options"))).kind)
        assertEquals(Polo.Kind.CONFIGURATION_ACK, Polo.parse(unframe(p.getString("in_configuration_ack"))).kind)
        assertEquals(Polo.Kind.SECRET_ACK, Polo.parse(unframe(p.getString("in_secret_ack"))).kind)
        Polo.parse(unframe(p.getString("in_bad_secret"))).let {
            assertEquals(Polo.STATUS_BAD_SECRET, it.status)
            assertEquals(Polo.Kind.OTHER, it.kind)
        }
        assertEquals(Polo.STATUS_ERROR, Polo.parse(unframe(p.getString("in_error"))).status)
    }

    // --- the remote channel ---------------------------------------------------------------------

    @Test
    fun handshakeAnswersMatchTheLibrary() {
        val hs = objects(v.getJSONObject("remote").getJSONArray("handshake"))
        assertEquals(3, hs.size)
        for (h in hs) {
            val cfg = RemoteMsg.parse(unframe(h.getString("in_configure"))) as RemoteMsg.In.Configure
            assertEquals(h.getInt("features"), cfg.features)
            assertEquals(h.getString("model"), cfg.model)
            assertEquals(h.getString("vendor"), cfg.vendor)
            assertEquals(h.getString("version"), cfg.version)
            val active = RemoteMsg.WANTED and cfg.features
            assertEquals(h.getString("configure"), Frames.frame(RemoteMsg.configure(active)).hex())
            val set = RemoteMsg.parse(unframe(h.getString("in_set_active"))) as RemoteMsg.In.SetActive
            assertEquals(622, set.active)
            // the answer is what droplet has active, not what the TV said
            assertEquals(h.getString("set_active"), Frames.frame(RemoteMsg.setActive(active)).hex())
        }
    }

    @Test
    fun pingIsAnsweredLikeTheLibrary() {
        val p = v.getJSONObject("remote").getJSONObject("ping")
        val ping = RemoteMsg.parse(unframe(p.getString("in"))) as RemoteMsg.In.Ping
        assertEquals(p.getInt("val1"), ping.val1)
        assertEquals(p.getString("reply"), Frames.frame(RemoteMsg.pingResponse(ping.val1)).hex())
    }

    @Test
    fun keysMatchTheLibrary() {
        val keys = objects(v.getJSONObject("remote").getJSONArray("keys"))
        assertEquals(8, keys.size)
        for (k in keys) {
            val name = if (k.getString("key") == "VOLUME_MUTE") "MUTE" else k.getString("key")
            val code = TvCatalog.keyCode(name)
            assertEquals(k.getInt("code"), code)
            val dir = mapOf("SHORT" to RemoteMsg.SHORT, "START_LONG" to RemoteMsg.START_LONG, "END_LONG" to RemoteMsg.END_LONG)
                .getValue(k.getString("direction"))
            assertEquals(k.getInt("dir"), dir)
            assertEquals(k.getString("key"), k.getString("framed"), Frames.frame(RemoteMsg.key(code, dir)).hex())
        }
    }

    @Test
    fun textMatchesTheLibrary() {
        val r = v.getJSONObject("remote")
        val counters = r.getJSONObject("in_batch_edit")
        val c = RemoteMsg.parse(unframe(counters.getString("framed"))) as RemoteMsg.In.ImeCounters
        assertEquals(counters.getInt("ime"), c.ime)
        assertEquals(counters.getInt("field"), c.field)
        val texts = objects(r.getJSONArray("texts"))
        assertEquals(6, texts.size)
        for (t in texts) {
            assertEquals(t.getString("text"), t.getString("framed"),
                Frames.frame(RemoteMsg.text(t.getString("text"), t.getInt("ime"), t.getInt("field"))).hex())
        }
    }

    @Test
    fun appLinksMatchTheLibrary() {
        val links = objects(v.getJSONObject("remote").getJSONArray("links"))
        for (l in links) {
            val target = l.getString("target")
            // the library adds market://launch?id= to anything without a scheme
            val link = if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(target)) target else TvCatalog.marketLink(target)
            assertEquals(target, l.getString("framed"), Frames.frame(RemoteMsg.appLink(link)).hex())
        }
        // the catalogue's apps go out exactly as the library sends a bare package
        val netflix = TvCatalog.launch("netflix") as TvCatalog.Launch.Link
        assertEquals(links[0].getString("framed"), Frames.frame(RemoteMsg.appLink(netflix.link)).hex())
    }

    @Test
    fun theTvsMessagesAreRead() {
        val i = v.getJSONObject("remote").getJSONObject("in")
        assertEquals(RemoteMsg.In.Start(true), RemoteMsg.parse(unframe(i.getString("start_on"))))
        // "off" is an empty RemoteStart: present, with started at its default
        assertEquals(RemoteMsg.In.Start(false), RemoteMsg.parse(unframe(i.getString("start_off"))))
        assertEquals(RemoteMsg.In.Volume(12, 100, true), RemoteMsg.parse(unframe(i.getString("volume"))))
        assertEquals(RemoteMsg.In.Volume(0, 60, false), RemoteMsg.parse(unframe(i.getString("volume_zero"))))
        assertEquals(RemoteMsg.In.App("com.google.android.youtube.tv"), RemoteMsg.parse(unframe(i.getString("app"))))
        assertEquals(RemoteMsg.In.Error, RemoteMsg.parse(unframe(i.getString("error"))))
        assertEquals(RemoteMsg.In.Other, RemoteMsg.parse(unframe(i.getString("unhandled_voice_begin"))))
    }

    // --- the wire format itself ------------------------------------------------------------------

    @Test
    fun unknownFieldsAreSkipped() {
        // a volume message with fields the protocol doesn't have, of every wire type
        val volume = ProtoWriter().int(7, 30).int(6, 100).bool(8, true).toByteArray() +
            byteArrayOf((20 shl 3 or 1).toByte()) + ByteArray(8) { 7 } +             // fixed64
            byteArrayOf((21 shl 3 or 5).toByte()) + ByteArray(4) { 9 } +             // fixed32
            ProtoWriter().int(99, 123456).string(100, "future").toByteArray()
        val msg = ProtoWriter().bytes(50, volume).int(500, 1).toByteArray()
        assertEquals(RemoteMsg.In.Volume(30, 100, true), RemoteMsg.parse(msg))
    }

    @Test
    fun repeatedNestedMessagesMerge() {
        // protobuf merges two occurrences of a message field; the second level wins where both set it
        val msg = ProtoWriter().message(50, ProtoWriter().int(7, 5).int(6, 100)).message(50, ProtoWriter().int(7, 9)).toByteArray()
        assertEquals(RemoteMsg.In.Volume(9, 100, false), RemoteMsg.parse(msg))
    }

    @Test
    fun brokenMessagesAreRefused() {
        val bad = listOf(
            byteArrayOf(0x0a, 0x05, 0x01),          // length past the end
            byteArrayOf(0x08),                      // varint cut off
            byteArrayOf(0x0b),                      // wire type 3 (groups)
            byteArrayOf(0x00, 0x01),                // field 0
            ByteArray(11) { 0xff.toByte() },        // varint of 11 bytes
        )
        for (b in bad) {
            try {
                ProtoFields.parse(b)
                fail("parsed ${b.hex()}")
            } catch (e: ProtoException) {
                // expected
            }
        }
    }

    @Test
    fun negativeIntsTakeTenBytes() {
        assertEquals("08feffffffffffffffff01", ProtoWriter().int(1, -2).toByteArray().hex())
        assertEquals(-2, ProtoFields.parse(hex("08feffffffffffffffff01")).int(1))
    }

    @Test
    fun framesAreBoundedAndWhole() {
        // a long message: two-byte length
        val big = ByteArray(300) { 1 }
        val framed = Frames.frame(big)
        assertEquals("ac02", framed.copyOfRange(0, 2).hex())
        assertArrayEquals(big, Frames.read(ByteArrayInputStream(framed)))
        // a clean end between messages
        assertNull(Frames.read(ByteArrayInputStream(ByteArray(0))))
        // cut off inside one
        try {
            Frames.read(ByteArrayInputStream(framed.copyOf(100)))
            fail("read a partial message")
        } catch (e: EOFException) {
            // expected
        }
        // more than a TV would ever send
        try {
            Frames.read(ByteArrayInputStream(hex("808008")))   // 131072
            fail("accepted a huge length")
        } catch (e: ProtoException) {
            // expected
        }
        // one byte at a time still reads whole messages
        val trickle = object : java.io.InputStream() {
            val src = ByteArrayInputStream(Frames.frame(big) + Frames.frame(byteArrayOf(5)))
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, minOf(len, 1))
        }
        assertArrayEquals(big, Frames.read(trickle))
        assertArrayEquals(byteArrayOf(5), Frames.read(trickle))
        assertFalse(Frames.read(trickle) != null)
    }
}
