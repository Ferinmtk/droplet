package dev.droplet.app.tv

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Why talking to the TV didn't work, in terms the UI can explain. */
class TvException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** Nothing answered at that address (off, asleep with no network standby, another network). */
        UNREACHABLE,
        /** The TV stopped answering part way. */
        TIMEOUT,
        /** The TV closed the connection: its pairing screen was closed, or it turned the code down. */
        CLOSED,
        /** The TV said no (a status other than OK). */
        REFUSED,
        /** The TV doesn't know droplet's certificate (any more): pair again. */
        NEEDS_PAIRING,
        /** The TV presented a certificate other than the one it paired with. */
        IDENTITY_CHANGED,
        /** Something that isn't the Android TV Remote protocol. */
        PROTOCOL,
    }
}

/**
 * Opens a TLS connection to the TV with droplet's client certificate and
 * [trust] checking the TV's. The connect and the handshake are bounded by
 * [timeoutMs]; an unreachable address would otherwise hang for minutes.
 */
internal fun openTls(identity: TvIdentity, trust: AcceptAnyTv, host: String, port: Int, timeoutMs: Int): SSLSocket {
    val raw = Socket()
    try {
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
    } catch (e: IOException) {
        runCatching { raw.close() }
        throw TvException(TvException.Kind.UNREACHABLE, "can't connect to $host:$port: ${e.message}", e)
    }
    val tls = identity.sslContext(trust).socketFactory.createSocket(raw, host, port, true) as SSLSocket
    try {
        tls.startHandshake()
    } catch (e: IOException) {
        runCatching { tls.close() }
        throw TvLink.classify(e, trust, started = false)
    }
    return tls
}

/**
 * Pairing with a TV (the "polo" handshake on the pairing port, 6467), as
 * androidtvremote2 does it: ask to pair, agree on a 6-character hex code,
 * and once the TV shows it, prove the code was typed here by sending the
 * secret derived from it and both certificates (TvSecret).
 *
 * Blocking: call [start] and [finish] off the main thread. A wrong code is
 * caught before anything is sent ([Result.WrongCode]), and the TV's screen
 * stays up for another try; anything else ends the pairing.
 */
class TvPairing(
    private val identity: TvIdentity,
    val host: String,
    /** The TV's remote port; pairing is on the next one up. */
    val apiPort: Int,
    private val clientName: String,
    private val timeoutMs: Int = TIMEOUT_MS,
) : Closeable {
    private var socket: SSLSocket? = null
    private val trust = AcceptAnyTv()

    /** The TV's certificate, once connected. */
    var serverCert: X509Certificate? = null
        private set

    /** The name in the TV's certificate, and its MAC (for Wake-on-LAN), once connected. */
    data class Started(val certName: String?, val mac: String?, val serverName: String?)

    sealed class Result {
        /** The code can't be the one on the TV (its check digits don't match). Nothing was sent. */
        data object WrongCode : Result()
        /** Paired: the TV now trusts droplet's certificate. [serverCertDer] is the TV's, to pin. */
        class Paired(val serverCertDer: ByteArray) : Result()
    }

    /** Connects and asks the TV to show a code. */
    fun start(): Started {
        val s = openTls(identity, trust, host, apiPort + 1, timeoutMs)
        socket = s
        try {
            val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw TvException(TvException.Kind.PROTOCOL, "the TV sent no certificate")
            TvSecret.rsa(cert)   // not RSA: not a TV this protocol knows
            serverCert = cert
            send(Polo.pairingRequest(clientName))
            val ack = expect(Polo.Kind.PAIRING_REQUEST_ACK)
            send(Polo.options())
            expect(Polo.Kind.OPTIONS)
            send(Polo.configuration())
            expect(Polo.Kind.CONFIGURATION_ACK)
            val (name, mac) = TvCatalog.nameAndMac(commonName(cert))
            return Started(name, mac, ack.serverName)
        } catch (e: IllegalArgumentException) {
            close()
            throw TvException(TvException.Kind.PROTOCOL, e.message ?: "not a TV", e)
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    /**
     * Checks [code] and, if it can be the TV's, sends the secret and waits
     * for the TV's answer. Throws [TvException] when the TV refuses or the
     * pairing is over (then start again).
     */
    fun finish(code: String): Result {
        socket ?: throw TvException(TvException.Kind.CLOSED, "pairing isn't running")
        val server = serverCert ?: throw TvException(TvException.Kind.CLOSED, "pairing isn't running")
        val secret = TvSecret.compute(identity.publicKey, TvSecret.rsa(server), TvCatalog.checkCode(code))
            ?: return Result.WrongCode
        try {
            send(Polo.secret(secret))
            expect(Polo.Kind.SECRET_ACK)
        } catch (e: IOException) {
            close()
            throw e
        }
        close()
        // the certificate from the handshake this secret was computed for (the session is gone once closed)
        return Result.Paired(server.encoded)
    }

    private fun send(message: ByteArray) {
        val s = socket ?: throw TvException(TvException.Kind.CLOSED, "not connected")
        try {
            Frames.write(s.outputStream, message)
        } catch (e: IOException) {
            throw TvException(TvException.Kind.CLOSED, "the TV closed the pairing: ${e.message}", e)
        }
    }

    private fun expect(kind: Polo.Kind): Polo.Reply {
        val s = socket ?: throw TvException(TvException.Kind.CLOSED, "not connected")
        val data = try {
            Frames.read(s.inputStream)
        } catch (e: SocketTimeoutException) {
            throw TvException(TvException.Kind.TIMEOUT, "the TV didn't answer", e)
        } catch (e: ProtoException) {
            throw TvException(TvException.Kind.PROTOCOL, e.message ?: "bad message", e)
        } catch (e: EOFException) {
            throw TvException(TvException.Kind.CLOSED, "the TV closed the pairing", e)
        } catch (e: SSLException) {
            throw TvException(TvException.Kind.CLOSED, "the TV closed the pairing: ${e.message}", e)
        } catch (e: IOException) {
            throw TvException(TvException.Kind.CLOSED, "the TV closed the pairing: ${e.message}", e)
        } ?: throw TvException(TvException.Kind.CLOSED, "the TV closed the pairing")
        val reply = try {
            Polo.parse(data)
        } catch (e: ProtoException) {
            throw TvException(TvException.Kind.PROTOCOL, e.message ?: "bad message", e)
        }
        if (reply.status != Polo.STATUS_OK) {
            throw TvException(TvException.Kind.REFUSED, "the TV answered status ${reply.status}")
        }
        if (reply.kind != kind) throw TvException(TvException.Kind.PROTOCOL, "expected $kind, got ${reply.kind}")
        return reply
    }

    /** Ends the pairing; the TV closes its code screen. */
    override fun close() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    companion object {
        /** Each step of the handshake is answered by the TV itself, so this doesn't wait on a person. */
        const val TIMEOUT_MS = 10_000

        /** The CN in an X.500 name, RFC 2253 escaping undone. */
        fun commonName(cert: X509Certificate): String? {
            val dn = cert.subjectX500Principal.getName("RFC2253")
            var i = 0
            while (i < dn.length) {
                // each RDN: type=value, separated by unescaped commas
                val start = i
                val sb = StringBuilder()
                var escaped = false
                while (i < dn.length) {
                    val c = dn[i]
                    if (escaped) { sb.append(c); escaped = false } else if (c == '\\') escaped = true
                    else if (c == ',' || c == '+') break
                    else sb.append(c)
                    i++
                }
                i++
                val part = sb.toString()
                if (dn.regionMatches(start, "CN=", 0, 3, ignoreCase = true)) return part.substring(3)
            }
            return null
        }
    }
}
