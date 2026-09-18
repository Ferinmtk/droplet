package dev.droplet.app.mesh

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Mutual TLS between peers, with self-signed certificates and no CA
 * (docs/mesh.md §9.2). Identity is the certificate's fingerprint, nothing else.
 */

/**
 * Presents this phone's certificate whenever a TLS peer asks for one, even
 * when its CertificateRequest names no CAs (or names only the CAs it already
 * trusts, as the Linux agent's does): the stock key manager would stay quiet
 * then. Only for EC key types, so a TLS 1.2 server never picks an RSA suite
 * for this key.
 */
class MeshKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
    private fun ec(keyType: String?) = keyType == null || keyType.startsWith("EC")
    private fun alias(keyTypes: Array<out String>?) = if (keyTypes == null || keyTypes.any { ec(it) }) ALIAS else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (ec(keyType)) ALIAS else null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = if (ec(keyType)) ALIAS else null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (ec(keyType)) arrayOf(ALIAS) else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (ec(keyType)) arrayOf(ALIAS) else null
    override fun getCertificateChain(alias: String?) = if (alias == ALIAS) chain else null
    override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null

    private companion object {
        const val ALIAS = "droplet-mesh"
    }
}

object MeshTls {
    fun fingerprint(cert: java.security.cert.Certificate): String = MeshIdentity.sha256Hex(cert.encoded)

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** The leaf a TLS peer presented, or null for none. */
    fun peerFingerprint(session: SSLSession?): String? = try {
        session?.peerCertificates?.firstOrNull()?.let { fingerprint(it) }
    } catch (e: SSLPeerUnverifiedException) {
        null
    }

    /** A server showed a certificate other than the one expected. Nothing was sent to it. */
    class Mismatch(val expected: String, val seen: String) :
        CertificateException("the peer presented certificate ${seen.take(16)}…, not the expected ${expected.take(16)}…")

    /** Accepts anything: the loopback self-test only. */
    class AcceptAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * The server's check of a client certificate, at handshake time: its
     * leaf's fingerprint must be in the trust list, or the handshake fails
     * (the §9.2 "unknown CA" refusal). The trust list is asked on every
     * handshake, so pairing and unpairing take effect at once. No CA list is
     * sent, and a client with no certificate never gets here: it may pair.
     */
    class ServerTrust(private val trusted: (String) -> Boolean) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (!trusted(fingerprint(leaf))) throw CertificateException("not a trusted peer")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server only")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("server only")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("server only")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * The client's check of the server, in the handshake itself: its leaf
     * must be [expect] (null: any, for pairing by address, where the code
     * confirms who it is). A mismatch aborts the handshake before a byte of
     * HTTP is sent.
     */
    class ClientTrust(private val expect: String?) : X509ExtendedTrustManager() {
        /** The last leaf certificate a server presented (DER), for pairing: OkHttp's Handshake can't give it back. */
        @Volatile var lastLeaf: ByteArray? = null

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("the peer sent no certificate")
            val seen = fingerprint(leaf)
            lastLeaf = leaf.encoded
            if (expect != null && !same(seen, expect)) throw Mismatch(expect, seen)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client only")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun serverContext(identity: MeshIdentity, trusted: (String) -> Boolean): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(identity.keyManager), arrayOf(ServerTrust(trusted)), null) }

    /**
     * An OkHttp client for one peer: presents [identity]'s certificate (none
     * when null, for pairing) and talks only to a server whose certificate is
     * [expect]. The hostname verifier checks the fingerprint again, after
     * every handshake, resumed ones included, so neither lock opens alone.
     * Each peer gets its own TLS context, so connections are never pooled
     * across peers.
     */
    fun client(identity: MeshIdentity?, expect: String?, base: OkHttpClient = BASE): OkHttpClient =
        clientWithTrust(identity, expect, base).first

    /** [client], and its trust manager (which remembers the server's certificate). */
    fun clientWithTrust(identity: MeshIdentity?, expect: String?, base: OkHttpClient = BASE): Pair<OkHttpClient, ClientTrust> {
        val tm = ClientTrust(expect)
        val ctx = SSLContext.getInstance("TLS").apply {
            init(identity?.let { arrayOf(it.keyManager) }, arrayOf(tm), null)
        }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, session ->
                val seen = peerFingerprint(session)
                seen != null && (expect == null || same(seen, expect))
            }
            .build() to tm
    }

    /** Shared settings: HTTP/1.1 only, no redirects, a pool of its own. */
    val BASE: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}
