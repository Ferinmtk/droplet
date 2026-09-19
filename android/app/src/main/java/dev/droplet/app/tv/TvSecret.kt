package dev.droplet.app.tv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * The pairing secret: what proves to the TV that the code on its screen was
 * typed into this client, over this TLS connection.
 *
 * SHA-256 over the client certificate's RSA modulus and exponent, the TV's
 * modulus and exponent, then the code's last four hex digits (two bytes,
 * the TV's random nonce). The code's first two digits are the first byte of
 * that hash: droplet checks them before sending anything, so a typo is
 * caught on the phone and the TV's code stays up for another try.
 *
 * Each number is its minimal unsigned big-endian bytes. That is what
 * androidtvremote2 hashes (`bytes.fromhex(f"{n:X}")`, with a "0" in front
 * of the exponent's odd-length hex) for every key it can handle, and what
 * Google's pairing library (polo) hashes for every key. TvProtocolTest
 * checks the result against vectors made by the library's own
 * async_finish_pairing.
 */
object TvSecret {
    private val HEX6 = Regex("^[0-9A-Fa-f]{6}$")

    /** Minimal unsigned big-endian bytes: BigInteger's two's complement without its sign byte. */
    internal fun unsigned(n: BigInteger): ByteArray {
        require(n.signum() > 0) { "not a positive number" }
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    fun rsa(cert: X509Certificate): RSAPublicKey =
        cert.publicKey as? RSAPublicKey ?: throw IllegalArgumentException("not an RSA certificate")

    /**
     * The secret for [code], or null when the code can't be the one on the
     * TV's screen (its check byte doesn't match). [code] is 6 hex digits,
     * either case.
     */
    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        require(HEX6.matches(code)) { "the code is 6 hex digits" }
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(unsigned(client.modulus))
        sha.update(unsigned(client.publicExponent))
        sha.update(unsigned(server.modulus))
        sha.update(unsigned(server.publicExponent))
        sha.update(byteArrayOf(code.substring(2, 4).toInt(16).toByte(), code.substring(4, 6).toInt(16).toByte()))
        val hash = sha.digest()
        return if ((hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) hash else null
    }
}
