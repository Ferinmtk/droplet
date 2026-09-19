package dev.droplet.app.mesh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Just enough DER to write the mesh certificate (docs/mesh.md §9.1): a
 * self-signed X.509 v3 certificate for an EC P-256 key. Hand-written rather
 * than BouncyCastle, which would add megabytes (and clash with the copy
 * Android bundles) to encode a dozen fixed structures. The result is read
 * back by Java's CertificateFactory when it's made, and by the Linux agent's
 * `cryptography` in the interop tests.
 */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xff -> { out.write(0x81); out.write(n) }
            n <= 0xffff -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun cat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, cat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, cat(parts))

    /** BigInteger.toByteArray is already the minimal two's complement form DER wants. */
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Int) = int(BigInteger.valueOf(v.toLong()))
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0))
    fun octets(v: ByteArray) = tlv(0x04, v)
    fun bits(v: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()) + v)
    fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    fun explicit(n: Int, content: ByteArray) = tlv(0xa0 or n, content)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2)
        val out = ByteArrayOutputStream()
        fun base128(v: Long) {
            val groups = ArrayList<Int>()
            var x = v
            do {
                groups.add((x and 0x7f).toInt())
                x = x shr 7
            } while (x > 0)
            for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
        }
        base128(arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { base128(it) }
        return tlv(0x06, out.toByteArray())
    }

    /** RFC 5280 §4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050. */
    fun time(t: Instant): ByteArray {
        val utc = t.atOffset(ZoneOffset.UTC)
        return if (utc.year in 1950..2049) {
            tlv(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(utc).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(utc).toByteArray(Charsets.US_ASCII))
        }
    }

    const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val COMMON_NAME = "2.5.4.3"
    private const val BASIC_CONSTRAINTS = "2.5.29.19"
    private const val KEY_USAGE = "2.5.29.15"
    private const val EXT_KEY_USAGE = "2.5.29.37"
    private const val SUBJECT_KEY_ID = "2.5.29.14"
    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"

    /**
     * The to-be-signed part of the mesh certificate (§9.1): subject and issuer
     * `CN=<cn>`, `basicConstraints` CA:FALSE (critical), `keyUsage`
     * digitalSignature (critical), `extendedKeyUsage` serverAuth and
     * clientAuth, and a subject key identifier, like the Linux agent's.
     */
    fun tbsCertificate(cn: String, serial: BigInteger, notBefore: Instant, notAfter: Instant, key: PublicKey): ByteArray {
        val spki = key.encoded   // SubjectPublicKeyInfo, DER
        // the EC point is the tail of a P-256 SubjectPublicKeyInfo: 04 || X || Y
        val point = spki.copyOfRange(spki.size - 65, spki.size)
        require(point[0] == 0x04.toByte()) { "not an uncompressed P-256 key" }
        val name = seq(set(seq(oid(COMMON_NAME), utf8(cn))))
        val alg = seq(oid(ECDSA_SHA256))
        val extensions = seq(
            // cA FALSE is the default, and DER leaves defaults out: an empty SEQUENCE
            seq(oid(BASIC_CONSTRAINTS), bool(true), octets(seq())),
            // digitalSignature is bit 0: 0x80, with the 7 unused bits after it
            seq(oid(KEY_USAGE), bool(true), octets(bits(byteArrayOf(0x80.toByte()), unused = 7))),
            seq(oid(EXT_KEY_USAGE), octets(seq(oid(SERVER_AUTH), oid(CLIENT_AUTH)))),
            seq(oid(SUBJECT_KEY_ID), octets(octets(MessageDigest.getInstance("SHA-1").digest(point)))),
        )
        return seq(
            explicit(0, int(2)),   // v3
            int(serial),
            alg,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            spki,
            explicit(3, extensions),
        )
    }

    /** The certificate: the TBS part, the algorithm again, and the (DER ECDSA) signature over the TBS part. */
    fun certificate(tbs: ByteArray, signature: ByteArray): ByteArray = seq(tbs, seq(oid(ECDSA_SHA256)), bits(signature))
}
