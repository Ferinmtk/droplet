package dev.droplet.app.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant

/**
 * The allowlists and checks, against the hub's tv.py (the same keys, apps
 * and rules), the client identity, and the paired-TV store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvCatalogTest {
    private fun invalid(block: () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (e: TvCatalog.Invalid) {
            // expected
        }
    }

    /** tv.py's KEYS and APPS, read from the hub's own source, so the two can't drift apart. */
    private val tvPy: String by lazy { File("../../tv.py").readText() }

    @Test
    fun keysAreTvPysKeys() {
        val block = tvPy.substringAfter("KEYS = {k: k for k in (").substringBefore(")}")
        val names = Regex("\"([A-Z_]+)\"").findAll(block).map { it.groupValues[1] }.toMutableSet()
        if ("*\"0123456789\"" in block) names += (0..9).map { it.toString() }
        names += "MUTE"   // KEYS["MUTE"] = "VOLUME_MUTE"
        assertTrue(tvPy.contains("KEYS[\"MUTE\"] = \"VOLUME_MUTE\""))
        assertEquals(names, TvCatalog.KEYS.keys)
        // the codes are Android's KEYCODE_ numbers, which the protocol uses
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_CENTER, TvCatalog.keyCode("DPAD_CENTER"))
        assertEquals(android.view.KeyEvent.KEYCODE_VOLUME_MUTE, TvCatalog.keyCode("mute"))
        assertEquals(android.view.KeyEvent.KEYCODE_TV_INPUT, TvCatalog.keyCode("TV_INPUT"))
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, TvCatalog.keyCode("MEDIA_FAST_FORWARD"))
        assertEquals(android.view.KeyEvent.KEYCODE_0, TvCatalog.keyCode("0"))
        for ((name, code) in TvCatalog.KEYS) {
            val android = android.view.KeyEvent::class.java.getField("KEYCODE_" + if (name == "MUTE") "VOLUME_MUTE" else name).getInt(null)
            assertEquals(name, android, code)
        }
        invalid { TvCatalog.keyCode("KEYCODE_POWER") }
        invalid { TvCatalog.keyCode("MUTE_MIC") }
        invalid { TvCatalog.keyCode("") }
    }

    @Test
    fun appsAreTvPysApps() {
        val block = tvPy.substringAfter("APPS = {").substringBefore("\n}")
        val apps = Regex("\"(\\w+)\": \\{\"name\": \"([^\"]+)\", \"(package|key)\": \"([^\"]+)\"\\}").findAll(block)
            .map { m -> TvCatalog.App(m.groupValues[1], m.groupValues[2],
                pkg = m.groupValues[4].takeIf { m.groupValues[3] == "package" },
                key = m.groupValues[4].takeIf { m.groupValues[3] == "key" }) }.toList()
        assertEquals(8, apps.size)
        assertEquals(apps, TvCatalog.APPS)
        assertEquals("YouTube", TvCatalog.appName("com.google.android.youtube.tv"))
        assertEquals("Home", TvCatalog.appName("com.google.android.apps.tv.launcherx"))
        assertEquals("com.example.other", TvCatalog.appName("com.example.other"))
        assertNull(TvCatalog.appName(""))
    }

    @Test
    fun launchTakesTheCatalogueOrHttpsLinks() {
        assertEquals(TvCatalog.Launch.Link("market://launch?id=com.netflix.ninja"), TvCatalog.launch("netflix"))
        assertEquals(TvCatalog.Launch.Link("market://launch?id=com.google.android.youtube.tv"), TvCatalog.launch(" YouTube "))
        assertEquals(TvCatalog.Launch.Key("HOME"), TvCatalog.launch("home"))
        assertEquals(TvCatalog.Launch.Link("https://www.youtube.com/watch?v=x"), TvCatalog.launch("https://www.youtube.com/watch?v=x"))
        // the https-only rule, as tv.py's check_url
        for (bad in listOf("http://example.com", "intent://x#Intent;end", "market://launch?id=com.evil", "file:///sdcard/x",
            "content://x/y", "javascript:alert(1)", "https://", "https:///path", "https://user@host/", "https://host/a b",
            "https://ho\u0000st/", "com.netflix.ninja", "", "HTTPS://example.com")) {
            invalid { TvCatalog.launch(bad) }
        }
        invalid { TvCatalog.checkUrl("https://" + "a".repeat(2050)) }
        assertEquals("https://example.com/x?y=1#z", TvCatalog.checkUrl("  https://example.com/x?y=1#z "))
    }

    @Test
    fun codesAreTidiedAndChecked() {
        assertEquals("AB12CD", TvCatalog.checkCode("ab12cd"))
        assertEquals("AB12CD", TvCatalog.checkCode(" ab-12 cd "))
        for (bad in listOf("", "ABC", "ABCDEFG", "ABCDEG", "12 34 5")) invalid { TvCatalog.checkCode(bad) }
    }

    @Test
    fun textIsPrintableAndBounded() {
        assertEquals("hello world", TvCatalog.checkText("hello world"))
        assertEquals("tabgone", TvCatalog.checkText("tab\tgone"))   // Python: "\t".isprintable() is False
        assertEquals("ab", TvCatalog.checkText("a\u0000\u001bb\n"))
        assertEquals("café 😀", TvCatalog.checkText("café 😀"))
        invalid { TvCatalog.checkText("\n\t") }
        invalid { TvCatalog.checkText("") }
        assertEquals(500, TvCatalog.checkText("x".repeat(500)).length)
        invalid { TvCatalog.checkText("x".repeat(501)) }
        // 500 emoji are 500 characters, as Python counts them
        assertEquals(1000, TvCatalog.checkText("😀".repeat(500)).length)
    }

    @Test
    fun onlyAddressesOnThisNetwork() {
        for (ok in listOf("192.168.100.12", "10.0.0.5", "172.16.3.4", "172.31.255.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1", "::1", "[fd00::5]")) {
            TvCatalog.checkHost(ok)
        }
        for (bad in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "2001:4860:4860::8888", "0.0.0.0",
            "::", "192.168.1.256", "")) {
            invalid { TvCatalog.checkHost(bad) }
        }
        // a name counts only if everything it resolves to is local
        val local = { _: String -> listOf(InetAddress.getByName("192.168.1.20")) }
        val mixed = { _: String -> listOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("1.1.1.1")) }
        assertEquals("tv.lan", TvCatalog.checkHost("tv.lan.", local))
        invalid { TvCatalog.checkHost("tv.lan", mixed) }
        invalid { TvCatalog.checkHost("tv.lan") { throw java.net.UnknownHostException() } }
        invalid { TvCatalog.checkHost("not a host", local) }
    }

    @Test
    fun macsAndCertificateNames() {
        assertEquals("aa:bb:cc:dd:ee:01", TvCatalog.normMac("AA-BB-CC-DD-EE-01"))
        assertEquals("aa:bb:cc:dd:ee:01", TvCatalog.normMac("aabbccddee01"))
        assertNull(TvCatalog.normMac("00:00:00:00:00:00"))
        assertNull(TvCatalog.normMac("ff:ff:ff:ff:ff:ff"))
        assertNull(TvCatalog.normMac("nope"))
        assertEquals("Fake Google TV" to "aa:bb:cc:dd:ee:01", TvCatalog.nameAndMac("atvremote/fake/fake/Fake Google TV/AA:BB:CC:DD:EE:01"))
        assertEquals("SHIELD Android TV" to "0c:79:55:8f:ef:36", TvCatalog.nameAndMac("atvremote/darcy/darcy/SHIELD Android TV/0C:79:55:8F:EF:36"))
        assertEquals("plain" to null, TvCatalog.nameAndMac("plain"))
    }

    @Test
    fun wakeOnLanPacket() {
        val p = TvWake.magicPacket("0C:79:55:8F:EF:36")
        assertEquals(102, p.size)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, p.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(byteArrayOf(0x0c, 0x79, 0x55, 0x8f.toByte(), 0xef.toByte(), 0x36), p.copyOfRange(6 + i * 6, 12 + i * 6))
        assertEquals(listOf("255.255.255.255", "192.168.100.255"), TvWake.targets("192.168.100.12"))
        assertEquals(listOf("255.255.255.255"), TvWake.targets("100.70.1.2"))
    }

    @Test
    fun serviceNamesAreUnescaped() {
        assertEquals("Living room TV", Tv.unescape("Living\\032room\\032TV"))
        assertEquals("Living room TV", Tv.unescape("Living room TV"))
        assertEquals("a.b\\c", Tv.unescape("a\\.b\\\\c"))
        assertEquals("café", Tv.unescape("caf\\195\\169"))
        val f = Tv.parseFound("Living\\032room\\032TV", "192.168.100.12", 6466, mapOf("bt" to "0C:79:55:8F:EF:36".toByteArray()))!!
        assertEquals("Living room TV", f.name)
        assertEquals("0c:79:55:8f:ef:36", f.mac)
        assertNull(Tv.parseFound("x", "8.8.8.8", 6466, emptyMap()))
        assertNull(Tv.parseFound("x", null, 6466, emptyMap()))
    }

    // --- the identity ---------------------------------------------------------------------------

    @Test
    fun identityIsTheLibrarysKindOfCertificate() {
        TvIdentity.keystoreAllowed = false
        val dir = File(System.getProperty("java.io.tmpdir"), "tvid-" + System.nanoTime()).apply { mkdirs() }
        try {
            val id = TvIdentity.loadOrCreate(dir)
            assertEquals(TvIdentity.STORAGE_FILE, id.storage)
            val key = id.cert.publicKey as RSAPublicKey
            assertEquals(2048, key.modulus.bitLength())
            assertEquals(BigInteger.valueOf(65537), key.publicExponent)
            assertEquals("SHA256withRSA", id.cert.sigAlgName)
            id.cert.verify(key)   // self-signed
            assertEquals(id.cert.subjectX500Principal, id.cert.issuerX500Principal)
            assertEquals("CN=${TvIdentity.CERT_NAME}", id.cert.subjectX500Principal.name)
            assertEquals(0, id.cert.basicConstraints)            // CA:TRUE, pathlen 0, as the library's
            assertEquals(listOf(listOf<Any>(2, TvIdentity.CERT_NAME)), id.cert.subjectAlternativeNames.map { it.toList() })
            assertTrue(id.cert.notBefore.toInstant() <= Instant.now())
            assertTrue(id.cert.notAfter.toInstant() > Instant.now().plusSeconds(3600L * 24 * 365 * 19))
            assertTrue(id.pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
            // kept: the same certificate next time, and the key file private
            val again = TvIdentity.loadOrCreate(dir)
            assertEquals(id.fingerprint, again.fingerprint)
            // a key that doesn't match the certificate isn't used: a new identity is made
            val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            TvIdentity.writePrivate(File(dir, "key.p8"), other.private.encoded)
            val fresh = TvIdentity.loadOrCreate(dir)
            assertTrue(fresh.fingerprint != id.fingerprint)
            TvIdentity.delete(dir)
            assertFalse(File(dir, "identity.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storeKeepsPairedTvs() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tvstore-" + System.nanoTime()).apply { mkdirs() }
        try {
            val s = TvStore(dir)
            assertTrue(s.all().isEmpty())
            val tv = PairedTv("a1b2c3d4", "Living room TV", "192.168.100.12", mac = "aa:bb:cc:dd:ee:01",
                mdns = "Living room TV", pin = "ab".repeat(32))
            s.put(tv)
            assertEquals(listOf(tv), s.all())
            assertEquals(tv, s.sameTv("192.168.100.99", "aa:bb:cc:dd:ee:01", null))
            assertEquals(tv, s.sameTv("192.168.100.12", null, null))
            assertNull(s.sameTv("192.168.100.99", null, "Bedroom TV"))
            s.update(tv.id) { it.copy(paired = false, lost = Tv.LOST_FORGOT) }
            assertEquals(false, s.get(tv.id)!!.paired)
            assertEquals(Tv.LOST_FORGOT, s.get(tv.id)!!.lost)
            // a broken entry is skipped, not the whole list
            File(dir, "tvs.json").writeText("{\"tvs\":[{\"id\":\"x\"}," + s.get(tv.id)!!.toJson() + "]}")
            assertEquals(1, s.all().size)
            s.remove(tv.id)
            assertTrue(s.all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
