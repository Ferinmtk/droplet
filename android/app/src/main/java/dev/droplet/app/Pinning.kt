package dev.droplet.app

import android.net.http.SslCertificate
import android.os.Build
import android.os.Bundle
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The hub's LAN certificate is self-signed, so on the LAN the app trusts it
 * by its fingerprint and nothing else (docs/local-first.md §2): a connection
 * is accepted if and only if the server's leaf certificate hashes (SHA-256,
 * DER) to the pinned value. There are no CA or hostname checks, because the
 * hub is reached by an IP that DHCP hands out.
 *
 * The tailnet keeps OkHttp's normal, fully verified client.
 */
object Pinning {
    /** A server presented a certificate other than the pinned one. */
    class Mismatch(val expected: String, val seen: String) :
        CertificateException("The hub's certificate doesn't match the one this phone paired with")

    /** Thrown on purpose by [capture] once the certificate is in hand. */
    private class Captured(val fingerprint: String) : CertificateException("captured")

    fun sha256(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun fingerprint(cert: Certificate): String = sha256(cert.encoded)

    /** A well-formed pin: 64 lowercase hex characters. */
    fun isFingerprint(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** Accepts exactly one leaf certificate. Everything else is refused. */
    class PinnedTrustManager(private val pin: String) : X509TrustManager {
        init {
            require(isFingerprint(pin)) { "not a SHA-256 fingerprint" }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
            val seen = fingerprint(leaf)
            // constant time, although the pin is no secret
            if (!MessageDigest.isEqual(seen.toByteArray(), pin.toByteArray())) throw Mismatch(pin, seen)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("This trust manager only checks the hub")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Stands in for the hostname check: the peer's leaf must be the pinned
     * certificate. The trust manager has already checked that; this is the
     * second lock, so neither can be loosened alone.
     */
    class PinnedHostnameVerifier(private val pin: String) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            val leaf = try {
                session?.peerCertificates?.firstOrNull()
            } catch (e: Exception) {
                null
            } ?: return false
            return MessageDigest.isEqual(fingerprint(leaf).toByteArray(), pin.toByteArray())
        }
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /** A client that talks only to the server holding [pin]'s certificate, sharing [base]'s pool and settings. */
    fun client(pin: String, base: OkHttpClient = Hub.client): OkHttpClient {
        if (base === Hub.client) clients[pin]?.let { return it }
        val tm = PinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val built = base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier(PinnedHostnameVerifier(pin))
            // a LAN hub answers quickly or not at all (the phone left the Wi-Fi)
            .connectTimeout(LAN_CONNECT_S, TimeUnit.SECONDS)
            .build()
        if (base === Hub.client) clients[pin] = built
        return built
    }

    /** The [Mismatch] behind a failed connection, if that's why it failed. */
    fun mismatch(t: Throwable?): Mismatch? {
        var e = t
        var depth = 0
        while (e != null && depth++ < 10) {
            if (e is Mismatch) return e
            e.suppressed.firstNotNullOfOrNull { mismatch(it) }?.let { return it }
            e = e.cause
        }
        return null
    }

    /**
     * Reads the certificate a server presents, without trusting it: the
     * handshake is always aborted, so nothing is sent. For trust on first
     * use, when someone types a LAN address and there's no pin yet.
     */
    fun capture(host: String, port: Int, timeoutMs: Int = 3000): String? {
        val tm = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                throw Captured(fingerprint(leaf))
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("not for clients")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val socket = java.net.Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val ssl = ctx.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            ssl.use { it.startHandshake() }
            null  // unreachable: the trust manager always throws
        } catch (e: Exception) {
            var c: Throwable? = e
            var found: String? = null
            while (c != null && found == null) {
                if (c is Captured) found = c.fingerprint
                c = c.cause
            }
            found
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * The X.509 certificate behind a WebView SSL error. API 29 has a getter;
     * before that the framework's saved state carries the DER bytes.
     */
    fun x509(cert: SslCertificate?): X509Certificate? {
        cert ?: return null
        if (Build.VERSION.SDK_INT >= 29) cert.x509Certificate?.let { return it }
        return fromSavedState(runCatching { SslCertificate.saveState(cert) }.getOrNull())
    }

    /** The certificate in [SslCertificate.saveState]'s bundle, which keeps the DER under "x509-certificate". */
    fun fromSavedState(saved: Bundle?): X509Certificate? {
        val der = saved?.getByteArray("x509-certificate") ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull()
    }

    /** Whether a WebView may proceed past [cert]: only if it's exactly the pinned certificate. */
    fun webViewMayProceed(cert: SslCertificate?, pin: String?): Boolean {
        if (!isFingerprint(pin)) return false
        val x = x509(cert) ?: return false
        return MessageDigest.isEqual(fingerprint(x).toByteArray(), pin!!.toByteArray())
    }

    private const val LAN_CONNECT_S = 3L
}
