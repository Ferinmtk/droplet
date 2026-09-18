package dev.droplet.app

import android.net.http.SslCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

/**
 * The local-first pieces that need no hub: reading mDNS announcements and
 * /api/hub/info, choosing among hubs, and the pin checks (OkHttp's trust
 * manager and hostname verifier, and the WebView's SSL-error decision).
 * Real NsdManager discovery can't run on the JVM; see LocalFirstHubTest for
 * everything that talks to a hub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFirstUnitTest {
    private val id = "9b16173d305cd15a"
    private val fp = "3c103f2cda4a422ac2da8a86565f4f6664d31ca3f2e720bddc34593e14ad2094"

    private fun txt(vararg kv: Pair<String, String>): Map<String, ByteArray?> = kv.associate { it.first to it.second.toByteArray() }

    // --- TXT records ------------------------------------------------------------------

    @Test
    fun readsTheHubsAnnouncement() {
        // exactly what the T15 announces (avahi-browse -r _droplet._tcp)
        val a = Discovery.parse("192.168.100.20", 8443, txt("ts" to "https://t15.tail7375fe.ts.net", "http" to "8000",
            "name" to "t15", "fp" to fp, "id" to id))!!
        assertEquals(id, a.id)
        assertEquals(fp, a.fingerprint)
        assertEquals("t15", a.name)
        assertEquals(8000, a.httpPort)
        assertEquals("https://t15.tail7375fe.ts.net", a.tailnet)
        assertEquals("192.168.100.20:8443", a.address)
        assertEquals("https://192.168.100.20:8443", a.base)
    }

    @Test
    fun toleratesOddButHarmlessTxt() {
        // upper case hex, key case, an empty ts (no tailnet), a missing name and http port
        val a = Discovery.parse("10.0.0.5", 8443, mapOf("ID" to id.uppercase().toByteArray(), "Fp" to fp.uppercase().toByteArray(),
            "ts" to ByteArray(0), "http" to null))!!
        assertEquals(id, a.id)
        assertEquals(fp, a.fingerprint)
        assertNull(a.tailnet)
        assertNull(a.httpPort)
        assertEquals("droplet", a.name)
    }

    @Test
    fun refusesAnnouncementsItCantTrust() {
        val ok = arrayOf("id" to id, "fp" to fp)
        assertNotNull(Discovery.parse("10.0.0.5", 8443, txt(*ok)))
        // no id, no fingerprint, or malformed ones: nothing to pin, nothing to match
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("fp" to fp)))
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("id" to id)))
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("id" to "9b16173d305cd15", "fp" to fp)))
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("id" to "9b16173d305cd15z", "fp" to fp)))
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("id" to id, "fp" to fp.dropLast(1))))
        assertNull(Discovery.parse("10.0.0.5", 8443, txt("id" to id, "fp" to fp.replaceFirst('3', 'g'))))
        // unresolved, or a nonsense port
        assertNull(Discovery.parse(null, 8443, txt(*ok)))
        assertNull(Discovery.parse("10.0.0.5", 0, txt(*ok)))
        assertNull(Discovery.parse("10.0.0.5", 70000, txt(*ok)))
        // a tailnet URL that isn't https is dropped, the rest is kept
        assertNull(Discovery.parse("10.0.0.5", 8443, txt(*ok, "ts" to "http://t15.example"))!!.tailnet)
    }

    @Test
    fun picksAnAddressAUrlCanCarry() {
        val v4 = InetAddress.getByName("192.168.100.20")
        val linkLocal = InetAddress.getByName("fe80::1")
        val global = InetAddress.getByName("2001:db8::20")
        assertEquals("192.168.100.20", Discovery.bestAddress(listOf(linkLocal, global, v4)))
        assertEquals(global, InetAddress.getByName(Discovery.bestAddress(listOf(linkLocal, global))))
        assertNull(Discovery.bestAddress(listOf(linkLocal)))
        assertEquals("[2001:db8::20]:8443", hostPort("2001:db8::20", 8443))
    }

    @Test
    fun listsThePairedHubFirstAndEachHubOnce() {
        fun hub(id: String, name: String, host: String) = Announced(id, fp, name, host, 8443, 8000, null)
        val other = hub("aaaaaaaaaaaaaaaa", "a-box", "10.0.0.9")
        val mine = hub(id, "t15", "192.168.100.20")
        val mineAgain = hub(id, "t15", "192.168.100.21")  // the same hub seen on a second interface
        val zed = hub("bbbbbbbbbbbbbbbb", "zed", "10.0.0.8")
        assertEquals(listOf(mine, other, zed), Discovery.arrange(listOf(zed, other, mine, mineAgain), id))
        assertEquals(listOf(other, mine, zed), Discovery.arrange(listOf(zed, mine, other), null))
    }

    // --- /api/hub/info ------------------------------------------------------------------

    @Test
    fun readsHubInfo() {
        val i = HubInfo.parse("""{"fingerprint":"$fp","id":"$id","lan":{"addresses":["192.168.100.20"],"http_port":8000,"https_port":8443},
            "name":"t15","pin":false,"tailnet":"https://t15.tail7375fe.ts.net"}""")!!
        assertEquals(id, i.id)
        assertEquals(fp, i.fingerprint)
        assertEquals(listOf("192.168.100.20:8443"), i.lan)
        assertEquals("https://t15.tail7375fe.ts.net", i.tailnet)
        assertEquals("t15", i.name)
        assertFalse(i.pin)

        // no LAN listener, no tailnet, a PIN; and a hub with no network reports loopback
        val bare = HubInfo.parse("""{"fingerprint":null,"id":"$id","lan":{"addresses":["127.0.0.1"],"http_port":8000,"https_port":null},
            "name":"t15","pin":true,"tailnet":null}""")!!
        assertNull(bare.fingerprint)
        assertTrue(bare.lan.isEmpty())
        assertNull(bare.tailnet)
        assertTrue(bare.pin)
        assertTrue(HubInfo.parse("""{"id":"$id","lan":{"addresses":["127.0.0.1"],"https_port":8443}}""")!!.lan.isEmpty())

        assertNull(HubInfo.parse("<html>not droplet</html>"))
        assertNull(HubInfo.parse("""{"id":"nope"}"""))
    }

    // --- pins ---------------------------------------------------------------------------

    private fun cert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.trimIndent().byteInputStream()) as X509Certificate

    @Test
    fun fingerprintIsSha256OfDer() {
        // openssl x509 -outform der | sha256sum
        assertEquals(FP_A, Pinning.fingerprint(cert(CERT_A)))
        assertEquals(FP_B, Pinning.fingerprint(cert(CERT_B)))
        assertTrue(Pinning.isFingerprint(FP_A))
        assertFalse(Pinning.isFingerprint(FP_A.uppercase()))
        assertFalse(Pinning.isFingerprint(null))
    }

    @Test
    fun trustManagerAcceptsOnlyThePinnedLeaf() {
        val tm = Pinning.PinnedTrustManager(FP_A)
        tm.checkServerTrusted(arrayOf(cert(CERT_A)), "ECDHE_ECDSA")
        // a chain whose leaf is another certificate fails, even with the pinned one further up
        val e = assertThrows(Pinning.Mismatch::class.java) { tm.checkServerTrusted(arrayOf(cert(CERT_B), cert(CERT_A)), "ECDHE_ECDSA") }
        assertEquals(FP_B, e.seen)
        assertEquals(FP_A, e.expected)
        assertThrows(java.security.cert.CertificateException::class.java) { tm.checkServerTrusted(arrayOf(), "RSA") }
        assertThrows(java.security.cert.CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert(CERT_A)), "RSA") }
        assertEquals(0, tm.acceptedIssuers.size)
        assertThrows(IllegalArgumentException::class.java) { Pinning.PinnedTrustManager("not a pin") }
    }

    @Test
    fun hostnameVerifierLooksAtTheCertificateNotTheName() {
        val v = Pinning.PinnedHostnameVerifier(FP_A)
        assertTrue(v.verify("192.168.100.20", session(cert(CERT_A))))
        assertTrue(v.verify("anything.example", session(cert(CERT_A))))
        assertFalse(v.verify("192.168.100.20", session(cert(CERT_B))))
        assertFalse(v.verify("192.168.100.20", session(null)))
        assertFalse(v.verify("192.168.100.20", null))
    }

    private fun session(leaf: X509Certificate?): SSLSession = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SSLSession::class.java)) { _, m, _ ->
        if (m.name == "getPeerCertificates") {
            leaf?.let { arrayOf<java.security.cert.Certificate>(it) } ?: throw javax.net.ssl.SSLPeerUnverifiedException("none")
        } else null
    } as SSLSession

    @Test
    fun webViewProceedsOnlyForThePin() {
        val a = SslCertificate(cert(CERT_A))
        assertTrue(Pinning.webViewMayProceed(a, FP_A))
        assertFalse(Pinning.webViewMayProceed(a, FP_B))
        assertFalse(Pinning.webViewMayProceed(a, null))
        assertFalse(Pinning.webViewMayProceed(null, FP_A))
    }

    @Test
    fun webViewDecisionWorksBeforeApi29() {
        // Android 8-9 have no SslCertificate.getX509Certificate(): the DER comes
        // from the framework's saved state. The framework's own saveState (the
        // same code on those versions) is what fills the bundle here.
        val saved = SslCertificate.saveState(SslCertificate(cert(CERT_A)))
        assertEquals(FP_A, Pinning.fromSavedState(saved)?.let { Pinning.fingerprint(it) })
        assertNull(Pinning.fromSavedState(android.os.Bundle()))
        assertNull(Pinning.fromSavedState(null))
    }

    @Test
    fun onlyRequestsThatNeverArrivedAreRetriedBlindly() {
        assertTrue(Hub.neverArrived(java.net.ConnectException("refused")))
        assertTrue(Hub.neverArrived(java.net.UnknownHostException("t15")))
        assertTrue(Hub.neverArrived(javax.net.ssl.SSLHandshakeException("pin")))
        assertTrue(Hub.neverArrived(java.net.SocketTimeoutException("failed to connect to /192.168.100.20 (port 8443) after 1500ms")))
        assertTrue(Hub.neverArrived(java.io.IOException("x", Pinning.Mismatch(FP_A, FP_B))))
        // a read timeout may come after the hub acted on it
        assertFalse(Hub.neverArrived(java.net.SocketTimeoutException("timeout")))
        assertFalse(Hub.neverArrived(java.io.IOException("unexpected end of stream")))
    }

    companion object {
        const val FP_A = "3c5bb9730d0130525ca70957b62751d678f6c9baa1af272f05e935e7718c7848"
        const val FP_B = "c32ccbfe71f6a8330630b2eee9b8b3a58dc933a958380d2917d7802ed1ffba10"
        const val CERT_A = """
            -----BEGIN CERTIFICATE-----
            MIIBiTCCAS+gAwIBAgIUM/yUf976dizbRtZ8I1RwVvzwMWowCgYIKoZIzj0EAwIw
            GTEXMBUGA1UEAwwOZHJvcGxldC10ZXN0LWEwIBcNMjYwOTE4MTYwMjAwWhgPMjEy
            NjA4MjUxNjAyMDBaMBkxFzAVBgNVBAMMDmRyb3BsZXQtdGVzdC1hMFkwEwYHKoZI
            zj0CAQYIKoZIzj0DAQcDQgAEpvQC9quMGO9r7VM3A+UQQyI1nXKu8NOX1rc16vVq
            L9wsBaE9l9MPf6S2IOVgSiKeU6sq5YcoxCYH2PGKi52836NTMFEwHQYDVR0OBBYE
            FIODC24uwlPwZBNOgzJ+7wgM/DVvMB8GA1UdIwQYMBaAFIODC24uwlPwZBNOgzJ+
            7wgM/DVvMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgIfG6TsKa
            5uZuFeAf6fNAWj99bgO6R8nvkUHUFSesFeICIQCstuGKBSI1lWhRUORE1a1GC6xS
            DnEJuLAeUFLHoAjwzQ==
            -----END CERTIFICATE-----
            """
        const val CERT_B = """
            -----BEGIN CERTIFICATE-----
            MIIBijCCAS+gAwIBAgIUEwUp6Y7Fjb1Wwlfa0MfClEF88fwwCgYIKoZIzj0EAwIw
            GTEXMBUGA1UEAwwOZHJvcGxldC10ZXN0LWIwIBcNMjYwOTE4MTYwMjAwWhgPMjEy
            NjA4MjUxNjAyMDBaMBkxFzAVBgNVBAMMDmRyb3BsZXQtdGVzdC1iMFkwEwYHKoZI
            zj0CAQYIKoZIzj0DAQcDQgAEnaRjz6A3vDdJu57gzFfhHUwXRe+Pdx6VeLegUeX0
            /T5QmtOuDb3k28sUzPdf4Fj5k4XwpVBXJENdt1lFIbZTEaNTMFEwHQYDVR0OBBYE
            FAaREJILy3Wbu23dcMtcVjqRSbFDMB8GA1UdIwQYMBaAFAaREJILy3Wbu23dcMtc
            VjqRSbFDMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAPesX6Nz
            Q9W13H46mThrvKfQ8bpN+7HnB9NZ37lZiwEHAiEAlEovxKZ3jG5L5LWKAY8OgAzJ
            /jrlDwFTHLanvtjxens=
            -----END CERTIFICATE-----
            """
    }
}
