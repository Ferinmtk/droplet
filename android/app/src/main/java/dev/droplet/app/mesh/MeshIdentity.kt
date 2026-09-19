package dev.droplet.app.mesh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * This phone's mesh identity (docs/mesh.md §1, §9.1): an EC P-256 key and a
 * long-lived self-signed certificate. Its fingerprint, the SHA-256 of the
 * certificate's DER, *is* the peer.
 *
 * The key is made in the Android Keystore and never leaves it: TLS and the
 * pairing signature use it through the Keystore (Conscrypt signs TLS
 * handshakes with such keys by calling back into the Keystore provider).
 * Before the identity is kept, a TLS handshake over loopback proves the
 * Keystore key really works for TLS on this phone, both as a server and
 * as a client. Where it doesn't (no Keystore, as on the JVM in tests, or a
 * firmware that refuses), the key is a PKCS#8 file in the app's private
 * storage instead, and [storage] says so.
 */
class MeshIdentity private constructor(
    val cert: X509Certificate,
    val der: ByteArray,
    val key: PrivateKey,
    /** A random 16-hex id, the peer id when this phone isn't a device on a hub. */
    val localId: String,
    /** [STORAGE_KEYSTORE] or [STORAGE_FILE]. */
    val storage: String,
    /** Why the key isn't in the Keystore, when it isn't. */
    val fallbackReason: String?,
) {
    val fp: String = sha256Hex(der)
    val pem: String get() = toPem(der)

    /** ECDSA with SHA-256, DER-encoded, as §9.3 wants. */
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(data)
        sign()
    }

    val keyManager: MeshKeyManager by lazy { MeshKeyManager(key, arrayOf(cert)) }

    companion object {
        const val STORAGE_KEYSTORE = "keystore"
        const val STORAGE_FILE = "file"
        private const val ALIAS = "droplet-mesh"
        private val NOT_BEFORE: Instant = Instant.parse("2020-01-01T00:00:00Z")

        /** Tests: skip the Android Keystore (Robolectric has none, and probing it is slow). */
        @Volatile var keystoreAllowed = true

        fun sha256Hex(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun toPem(der: ByteArray): String =
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"

        /** The DER of exactly one PEM certificate, parsed for real. Throws IllegalArgumentException otherwise. */
        fun pemToDer(pem: String?): ByteArray {
            require(pem != null && pem.split("-----BEGIN CERTIFICATE-----").size == 2) { "not a single PEM certificate" }
            val body = pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
            require(pem.contains("-----END CERTIFICATE-----")) { "not a PEM certificate" }
            val der = try {
                Base64.decode(body.filterNot { it.isWhitespace() }, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("not a PEM certificate")
            }
            parse(der)   // a PEM wrapper around garbage must not be trusted
            return der
        }

        fun parse(der: ByteArray): X509Certificate = try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            throw IllegalArgumentException("not a valid certificate: ${e.message}")
        }

        /**
         * This phone's identity, made on first use in [dir] (app-private
         * storage). Blocking: the first call makes a key and runs a handshake.
         */
        @Synchronized
        fun loadOrCreate(dir: File): MeshIdentity {
            dir.mkdirs()
            val meta = File(dir, "identity.json")
            val saved = runCatching { JSONObject(meta.readText()) }.getOrNull()
            if (saved != null) runCatching { return load(dir, saved) }
            // nothing usable: a new identity (a new fingerprint; peers pair again)
            val localId = saved?.optString("id")?.takeIf { Regex("[0-9a-f]{16}").matches(it) } ?: randomHex(8)
            val made = create(dir, localId)
            val out = JSONObject().put("id", localId).put("storage", made.storage)
                .put("cert", Base64.encodeToString(made.der, Base64.NO_WRAP))
            made.fallbackReason?.let { out.put("fallback", it) }
            writePrivate(meta, out.toString().toByteArray())
            return made
        }

        private fun load(dir: File, j: JSONObject): MeshIdentity {
            val der = Base64.decode(j.getString("cert"), Base64.DEFAULT)
            val cert = parse(der)
            val storage = j.getString("storage")
            val key = when (storage) {
                STORAGE_KEYSTORE -> keystore().getKey(ALIAS, null) as? PrivateKey ?: error("the Keystore key is gone")
                STORAGE_FILE -> KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(File(dir, "key.p8").readBytes()))
                else -> error("unknown key storage")
            }
            val id = MeshIdentity(cert, der, key, j.getString("id"), storage, j.optString("fallback").ifEmpty { null })
            // the key and the certificate must belong together
            check(id.verifyOwn()) { "the key doesn't match the certificate" }
            return id
        }

        private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun create(dir: File, localId: String): MeshIdentity {
            var reason: String? = null
            if (keystoreAllowed) {
                try {
                    val made = createInKeystore(localId)
                    selfTest(made)
                    File(dir, "key.p8").delete()
                    return made
                } catch (e: Throwable) {
                    reason = "${e.javaClass.simpleName}: ${e.message}".take(200)
                    runCatching { keystore().deleteEntry(ALIAS) }
                }
            } else reason = "the Android Keystore isn't used here"
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            writePrivate(File(dir, "key.p8"), kp.private.encoded)
            val (cert, der) = certificate(kp, localId)
            return MeshIdentity(cert, der, kp.private, localId, STORAGE_FILE, reason)
        }

        private fun createInKeystore(localId: String): MeshIdentity {
            runCatching { keystore().deleteEntry(ALIAS) }
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // NONE: TLS signs a digest it computed itself (Conscrypt's NONEwithECDSA upcall);
                    // SHA-256: the certificate and the pairing proof
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .build()
            )
            val kp = kpg.generateKeyPair()
            val (cert, der) = certificate(kp, localId)
            return MeshIdentity(cert, der, kp.private, localId, STORAGE_KEYSTORE, null)
        }

        /** A self-signed certificate per §9.1, signed by [kp]'s private key. */
        private fun certificate(kp: KeyPair, localId: String): Pair<X509Certificate, ByteArray> {
            val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
            serial[0] = (serial[0].toInt() and 0x7f or 0x01).toByte()   // positive, and never zero
            val now = Instant.now()
            // a fixed start in the past, so a peer whose clock is behind still accepts it;
            // 30 years on, like the Linux agent (OpenSSL checks a trust anchor's dates too)
            val tbs = Der.tbsCertificate("droplet-peer-$localId", BigInteger(1, serial),
                minOf(NOT_BEFORE, now), now.plus(365L * 30, ChronoUnit.DAYS), kp.public)
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(tbs)
                sign()
            }
            val der = Der.certificate(tbs, sig)
            val cert = parse(der)
            cert.verify(kp.public)
            return cert to der
        }

        /**
         * Mutual TLS with itself over loopback: the server and the client both
         * sign with the key. Throws if either can't.
         */
        private fun selfTest(id: MeshIdentity) {
            val tm = MeshTls.AcceptAll()
            val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(id.keyManager), arrayOf(tm), null) }
            val server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.needClientAuth = true
            server.soTimeout = 10_000
            var serverError: Throwable? = null
            var seenByServer: String? = null
            val t = thread(name = "mesh-selftest") {
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
                    s.startHandshake()
                    check(sha256Hex(s.session.peerCertificates[0].encoded) == id.fp) { "the server showed another certificate" }
                    check(s.inputStream.read() == 1) { "no answer over TLS" }
                }
                t.join(10_000)
            } finally {
                runCatching { server.close() }
            }
            serverError?.let { throw it }
            check(seenByServer == id.fp) { "the client didn't present its certificate" }
        }

        private fun randomHex(n: Int): String =
            ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

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
        val probe = "droplet-mesh-key-check".toByteArray()
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(cert.publicKey)
            update(probe)
            verify(sign(probe))
        }
    }
}
