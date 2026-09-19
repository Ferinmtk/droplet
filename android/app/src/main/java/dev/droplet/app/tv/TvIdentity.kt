package dev.droplet.app.tv

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.droplet.app.mesh.Der
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * The client certificate a TV remembers droplet by: RSA 2048 and
 * self-signed, as androidtvremote2 makes it (CN and a DNS name, CA:TRUE with
 * path length 0; the TV hashes its modulus and exponent into the pairing
 * secret). A TV that paired with it lets it in on the remote port from then
 * on; a new certificate means pairing again.
 *
 * Like the mesh key (MeshIdentity), the private key is made in the Android
 * Keystore and never leaves it, once a TLS handshake over loopback has shown
 * that the Keystore key really signs TLS handshakes on this phone, in both
 * TLS 1.2 and 1.3. Where it doesn't (no Keystore, as on the JVM in tests, or
 * a firmware that refuses), the key is a PKCS#8 file in the app's private
 * storage, and [storage] says so.
 *
 * It's separate from the hub's certificate on purpose: the phone talks to
 * the TV itself, so the TV lists it as a second remote.
 */
class TvIdentity private constructor(
    val cert: X509Certificate,
    val der: ByteArray,
    val key: PrivateKey,
    /** [STORAGE_KEYSTORE] or [STORAGE_FILE]. */
    val storage: String,
    /** Why the key isn't in the Keystore, when it isn't. */
    val fallbackReason: String?,
) {
    val fingerprint: String = sha256Hex(der)
    val publicKey: RSAPublicKey get() = cert.publicKey as RSAPublicKey
    val pem: String get() = toPem(der)

    val keyManager: TvKeyManager by lazy { TvKeyManager(key, arrayOf(cert)) }

    /** A TLS context that presents this certificate and checks the TV with [trust]. */
    fun sslContext(trust: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trust), SecureRandom()) }

    companion object {
        const val STORAGE_KEYSTORE = "keystore"
        const val STORAGE_FILE = "file"
        /** The certificate's name. The TV shows the name sent in the pairing request instead. */
        const val CERT_NAME = "droplet-android"
        private const val ALIAS = "droplet-tv"
        private val NOT_BEFORE: Instant = Instant.parse("2020-01-01T00:00:00Z")

        /** Tests: skip the Android Keystore (the JVM has none, and probing it is slow). */
        @Volatile var keystoreAllowed = true

        fun sha256Hex(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun toPem(der: ByteArray): String =
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"

        fun parse(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        /**
         * The identity kept in [dir] (app-private), made on first use.
         * Blocking: making one generates an RSA key and runs handshakes.
         */
        @Synchronized
        fun loadOrCreate(dir: File): TvIdentity {
            dir.mkdirs()
            val meta = File(dir, "identity.json")
            val saved = runCatching { JSONObject(meta.readText()) }.getOrNull()
            if (saved != null) runCatching { return load(dir, saved) }
            val made = create(dir)
            val out = JSONObject().put("storage", made.storage)
                .put("cert", Base64.getEncoder().encodeToString(made.der))
            made.fallbackReason?.let { out.put("fallback", it) }
            writePrivate(meta, out.toString().toByteArray())
            return made
        }

        /** Throws the identity away: every TV has to pair again. */
        @Synchronized
        fun delete(dir: File) {
            File(dir, "identity.json").delete()
            File(dir, "key.p8").delete()
            if (keystoreAllowed) runCatching { keystore().deleteEntry(ALIAS) }
        }

        private fun load(dir: File, j: JSONObject): TvIdentity {
            val der = Base64.getDecoder().decode(j.getString("cert"))
            val cert = parse(der)
            val storage = j.getString("storage")
            val key = when (storage) {
                STORAGE_KEYSTORE -> keystore().getKey(ALIAS, null) as? PrivateKey ?: error("the Keystore key is gone")
                STORAGE_FILE -> KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(dir, "key.p8").readBytes()))
                else -> error("unknown key storage")
            }
            val id = TvIdentity(cert, der, key, storage, j.optString("fallback").ifEmpty { null })
            check(id.verifyOwn()) { "the key doesn't match the certificate" }
            return id
        }

        private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun create(dir: File): TvIdentity {
            var reason: String?
            if (keystoreAllowed) {
                try {
                    val made = createInKeystore()
                    selfTest(made)
                    File(dir, "key.p8").delete()
                    return made
                } catch (e: Throwable) {
                    reason = "${e.javaClass.simpleName}: ${e.message}".take(200)
                    runCatching { keystore().deleteEntry(ALIAS) }
                }
            } else reason = "the Android Keystore isn't used here"
            val kp = KeyPairGenerator.getInstance("RSA").apply {
                initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), SecureRandom())
            }.generateKeyPair()
            writePrivate(File(dir, "key.p8"), kp.private.encoded)
            val (cert, der) = certificate(kp)
            return TvIdentity(cert, der, kp.private, STORAGE_FILE, reason)
        }

        private fun createInKeystore(): TvIdentity {
            runCatching { keystore().deleteEntry(ALIAS) }
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(
                // TLS signs through Conscrypt's upcalls: PKCS#1 v1.5 as "encrypt with the private
                // key", PSS as raw RSA over a block it padded itself (padding NONE); the certificate
                // itself is SHA256withRSA
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_DECRYPT)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            val kp = kpg.generateKeyPair()
            val (cert, der) = certificate(kp)
            return TvIdentity(cert, der, kp.private, STORAGE_KEYSTORE, null)
        }

        /** The self-signed certificate, the way androidtvremote2's certificate_generator makes it. */
        internal fun certificate(kp: KeyPair): Pair<X509Certificate, ByteArray> {
            val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
            serial[0] = (serial[0].toInt() and 0x7f or 0x01).toByte()   // positive, and never zero
            val now = Instant.now()
            // a start in the past, for a TV whose clock is behind; 20 years on keeps
            // UTCTime, which every parser reads
            val tbs = tbsCertificate(CERT_NAME, BigInteger(1, serial), minOf(NOT_BEFORE, now),
                now.plus(365L * 20, ChronoUnit.DAYS), kp.public.encoded)
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(kp.private)
                update(tbs)
                sign()
            }
            val der = Der.seq(tbs, SHA256_WITH_RSA, Der.bits(sig))
            val cert = parse(der)
            cert.verify(kp.public)
            return cert to der
        }

        private val SHA256_WITH_RSA = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.tlv(0x05, ByteArray(0)))

        /**
         * X.509 v3: subject and issuer CN=[cn]; basicConstraints CA:TRUE,
         * pathlen 0 (not critical); subjectAltName DNS:[cn]. The same
         * extensions as the library's certificate, so a TV sees the same
         * shape of certificate from droplet on the phone as from the hub.
         */
        internal fun tbsCertificate(cn: String, serial: BigInteger, notBefore: Instant, notAfter: Instant, spki: ByteArray): ByteArray {
            val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(cn))))
            val extensions = Der.seq(
                Der.seq(Der.oid("2.5.29.19"), Der.octets(Der.seq(Der.bool(true), Der.int(0)))),
                Der.seq(Der.oid("2.5.29.17"), Der.octets(Der.seq(Der.tlv(0x82, cn.toByteArray(Charsets.US_ASCII))))),
            )
            return Der.seq(
                Der.explicit(0, Der.int(2)),
                Der.int(serial),
                SHA256_WITH_RSA,
                name,
                Der.seq(Der.time(notBefore), Der.time(notAfter)),
                name,
                spki,
                Der.explicit(3, extensions),
            )
        }

        /**
         * Mutual TLS with itself over loopback, in TLS 1.2 and (where the
         * phone has it) TLS 1.3: server and client both sign with the key.
         * Throws if either can't.
         */
        private fun selfTest(id: TvIdentity) {
            val ctx = id.sslContext(AcceptAnyTv())
            val supported = ctx.supportedSSLParameters.protocols.toSet()
            val versions = listOf("TLSv1.2", "TLSv1.3").filter { it in supported }
            check("TLSv1.2" in versions) { "no TLS 1.2" }
            for (v in versions) handshake(ctx, id, v)
        }

        private fun handshake(ctx: SSLContext, id: TvIdentity, version: String) {
            val server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.needClientAuth = true
            server.enabledProtocols = arrayOf(version)
            server.soTimeout = 10_000
            var serverError: Throwable? = null
            var seenByServer: String? = null
            val t = thread(name = "tv-selftest") {
                try {
                    (server.accept() as SSLSocket).use { s ->
                        s.soTimeout = 10_000
                        s.startHandshake()
                        seenByServer = sha256Hex(s.session.peerCertificates[0].encoded)
                        s.outputStream.write(1)
                        s.outputStream.flush()
                    }
                } catch (e: Throwable) {
                    serverError = e
                }
            }
            try {
                val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 10_000 }
                (ctx.socketFactory.createSocket(raw, "localhost", server.localPort, true) as SSLSocket).use { s ->
                    s.enabledProtocols = arrayOf(version)
                    s.startHandshake()
                    check(s.inputStream.read() == 1) { "no answer over $version" }
                }
                t.join(10_000)
            } finally {
                runCatching { server.close() }
            }
            serverError?.let { throw it }
            check(seenByServer == id.fingerprint) { "the client didn't present its certificate over $version" }
        }

        internal fun writePrivate(f: File, data: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "couldn't save ${f.name}" }
            }
        }
    }

    /** Whether the key signs what the certificate's public key verifies. */
    private fun verifyOwn(): Boolean {
        val probe = "droplet-tv-key-check".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(probe)
            sign()
        }
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(cert.publicKey)
            update(probe)
            verify(sig)
        }
    }
}

/**
 * Presents droplet's certificate whenever the TV asks for one, whatever CAs
 * its CertificateRequest names (usually none), and only for RSA: the stock
 * key manager would stay quiet when the CA list doesn't include this
 * self-signed certificate.
 */
class TvKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
    private fun rsa(keyType: String?) = keyType == null || keyType.equals("RSA", ignoreCase = true)
    private fun alias(keyTypes: Array<out String>?) = if (keyTypes == null || keyTypes.any { rsa(it) }) ALIAS else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)
    // the server side is only the loopback self-test
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (rsa(keyType)) ALIAS else null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = if (rsa(keyType)) ALIAS else null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (rsa(keyType)) arrayOf(ALIAS) else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (rsa(keyType)) arrayOf(ALIAS) else null
    override fun getCertificateChain(alias: String?) = if (alias == ALIAS) chain else null
    override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null

    private companion object {
        const val ALIAS = "droplet-tv"
    }
}

/**
 * The TV's certificate check. A TV's certificate is self-signed, so there's
 * no CA to check it against: while pairing, any certificate is taken (the
 * pairing secret binds both certificates, so a machine in the middle can't
 * complete it), and its fingerprint is kept. After that only that
 * certificate is accepted ([pin]); a different one aborts the handshake
 * before anything is sent and sets [mismatch].
 */
@SuppressLint("CustomX509TrustManager")  // on purpose: the TV is identified by its certificate's fingerprint
class AcceptAnyTv(private val pin: String? = null) : X509ExtendedTrustManager() {
    /** The TV's certificate from the last handshake (DER). */
    @Volatile var seen: ByteArray? = null
    /** The TV showed a certificate other than [pin]. */
    @Volatile var mismatch = false

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("the TV sent no certificate")
        seen = leaf.encoded
        if (pin != null && !MessageDigest.isEqual(TvIdentity.sha256Hex(leaf.encoded).toByteArray(), pin.toByteArray())) {
            mismatch = true
            throw java.security.cert.CertificateException("the TV's certificate changed")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    // the loopback self-test's server side: any client
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
