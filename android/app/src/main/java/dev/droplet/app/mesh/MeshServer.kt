package dev.droplet.app.mesh

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The mesh port (docs/mesh.md §9.2): one TLS listener for the WebSocket
 * link, files and pairing.
 *
 *     wss://<phone>:<port>/mesh                   a trusted peer's link      (client certificate, trusted)
 *     GET/HEAD /mesh/files/<id>                   a file offered to that peer (client certificate, trusted)
 *     POST /mesh/pair, GET /mesh/pair/<request>,  pairing                    (no client certificate)
 *     POST /mesh/pair/<request>/confirm|/cancel
 *
 * Who may do what is settled by the handshake: a certificate outside the
 * trust list fails it ([MeshTls.ServerTrust]); a client with no certificate
 * gets only /mesh/pair*, and 403 for everything else before any body is
 * read. The fingerprint is checked again right after the handshake, which
 * also covers a resumed TLS session and a peer unpaired since.
 *
 * Hand-written on purpose, like the reference (server.py): this is all the
 * HTTP the mesh needs, and it runs on an SSLServerSocket whose client
 * certificate handling we control, which NanoHTTPD (unmaintained since
 * 2016) and Ktor's CIO server (no TLS at all) can't offer.
 */
class MeshServer(
    private val context: SSLContext,
    private val handler: Handler,
    port: Int? = null,
    bindAddress: InetAddress? = null,
) {
    interface Handler {
        fun trusted(fp: String): Boolean
        /** Takes over the socket for a WebSocket link; [leftover] is what followed the request head. */
        fun onLink(socket: SSLSocket, head: Request, fp: String, address: String, leftover: ByteArray)
        fun serveFile(out: OutputStream, oid: String, fp: String, req: Request, sendHead: (Int, Map<String, String>) -> Unit)
        fun pair(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject>
    }

    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private val server: SSLServerSocket
    val port: Int
    private val slots = Semaphore(MAX_CONNECTIONS)
    @Volatile private var stopped = false
    /** TLS handshakes that failed (untrusted certificates among them). */
    val refused = AtomicInteger()

    init {
        val candidates = if (port != null) listOf(port) else PORT_RANGE.toList()
        var bound: SSLServerSocket? = null
        var last: IOException? = null
        for (p in candidates) {
            val s = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                s.reuseAddress = true
                // no address: the wildcard, dual-stack where the phone has IPv6
                s.bind(if (bindAddress != null) InetSocketAddress(bindAddress, p) else InetSocketAddress(p), 32)
                bound = s
                break
            } catch (e: IOException) {
                runCatching { s.close() }
                last = e
                if (e !is BindException) break
            }
        }
        server = bound ?: throw IOException("no free mesh port in ${candidates.first()}–${candidates.last()}: ${last?.message}", last)
        server.wantClientAuth = true
        server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        this.port = server.localPort
    }

    fun start() {
        thread(name = "mesh-accept", isDaemon = true) { acceptLoop() }
    }

    fun close() {
        stopped = true
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (!stopped) {
            val s = try {
                server.accept() as SSLSocket
            } catch (e: IOException) {
                if (stopped) return
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching { s.close() }
                continue
            }
            thread(name = "mesh-conn", isDaemon = true) {
                try {
                    serve(s)
                } finally {
                    slots.release()
                }
            }
        }
    }

    private fun serve(s: SSLSocket) {
        val address = (s.inetAddress?.hostAddress ?: "?").removePrefix("::ffff:").substringBefore('%')
        var handedOver = false
        try {
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            try {
                s.startHandshake()
            } catch (e: IOException) {
                refused.incrementAndGet()
                return
            }
            val fp = MeshTls.peerFingerprint(s.session)
            if (fp != null && !handler.trusted(fp)) {
                // the handshake let it through (a resumed session?), but it isn't trusted now
                respond(s.outputStream, 403, JSONObject().put("error", "not trusted"), close = true)
                return
            }
            handedOver = requests(s, address, fp)
        } catch (e: Exception) {
            // a peer that went away mid-request
        } finally {
            if (!handedOver) runCatching { s.close() }
        }
    }

    /** Reads bytes until a blank line. */
    private class HeadReader(private val input: InputStream) {
        var buf = ByteArray(0)

        fun head(): ByteArray? {
            val acc = ByteArrayOutputStream()
            acc.write(buf)
            buf = ByteArray(0)
            val chunk = ByteArray(8192)
            while (true) {
                val all = acc.toByteArray()
                val end = indexOf(all, CRLFCRLF)
                if (end >= 0) {
                    buf = all.copyOfRange(end + 4, all.size)
                    return all.copyOfRange(0, end + 4)
                }
                if (all.size > MAX_HEAD) return null
                val n = input.read(chunk)
                if (n < 0) return null
                acc.write(chunk, 0, n)
            }
        }

        fun body(n: Int): ByteArray? {
            val out = ByteArrayOutputStream(n)
            val take = minOf(n, buf.size)
            out.write(buf, 0, take)
            buf = buf.copyOfRange(take, buf.size)
            val chunk = ByteArray(8192)
            while (out.size() < n) {
                val r = input.read(chunk, 0, minOf(chunk.size, n - out.size()))
                if (r < 0) return null
                out.write(chunk, 0, r)
            }
            return out.toByteArray()
        }

        private fun indexOf(a: ByteArray, b: ByteArray): Int {
            outer@ for (i in 0..a.size - b.size) {
                for (j in b.indices) if (a[i + j] != b[j]) continue@outer
                return i
            }
            return -1
        }
    }

    /** Serves requests on one connection. True once the socket was handed to a link. */
    private fun requests(s: SSLSocket, address: String, fp: String?): Boolean {
        val reader = HeadReader(s.inputStream)
        val out = s.outputStream
        repeat(MAX_REQUESTS) {
            s.soTimeout = IDLE_TIMEOUT_MS
            val raw = reader.head() ?: return false
            val req = parseHead(raw)
            if (req == null) {
                respond(out, 400, JSONObject().put("error", "bad request"), close = true)
                return false
            }
            val path = req.path
            if (path == "/mesh/pair" || path.startsWith("/mesh/pair/")) {
                var body: JSONObject? = null
                if (req.method == "POST") {
                    val n = req.header("content-length")?.toIntOrNull() ?: if (req.header("content-length") == null) 0 else -1
                    if (n !in 0..MAX_BODY || req.header("transfer-encoding") != null) {
                        respond(out, 413, JSONObject().put("error", "body too large"), close = true)
                        return false
                    }
                    val data = reader.body(n) ?: return false
                    body = try {
                        JSONObject(if (n == 0) "{}" else String(data, Charsets.UTF_8))
                    } catch (e: Exception) {
                        respond(out, 400, JSONObject().put("error", "bad JSON"), close = true)
                        return false
                    }
                } else if (req.method != "GET") {
                    respond(out, 405, JSONObject().put("error", "method not allowed"), close = true)
                    return false
                }
                val (status, answer) = handler.pair(req.method, path, body)
                val close = req.header("connection")?.lowercase() == "close"
                respond(out, status, answer, close)
                if (close) return false
                return@repeat
            }
            if (fp == null) {
                // no certificate: nothing but pairing
                respond(out, 403, JSONObject().put("error", "pair with this device first"), close = true)
                return false
            }
            if (path == "/mesh" && req.method == "GET" && req.header("upgrade")?.lowercase()?.contains("websocket") == true) {
                s.soTimeout = 0
                handler.onLink(s, req, fp, address, reader.buf)
                return true
            }
            if (path.startsWith("/mesh/files/") && (req.method == "GET" || req.method == "HEAD")) {
                s.soTimeout = 60_000
                handler.serveFile(out, path.removePrefix("/mesh/files/"), fp, req) { status, headers ->
                    val lines = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
                    headers.forEach { (k, v) -> lines.append("$k: $v\r\n") }
                    lines.append("Connection: close\r\nServer: $SERVER\r\n\r\n")
                    out.write(lines.toString().toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                out.flush()
                return false
            }
            respond(out, 404, JSONObject().put("error", "not found"), close = true)
            return false
        }
        return false
    }

    companion object {
        val PORT_RANGE = 1739..1749
        const val SERVER = "droplet-android"
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 64 * 1024
        private const val MAX_CONNECTIONS = 64
        private const val MAX_REQUESTS = 8
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 30_000
        private val CRLFCRLF = "\r\n\r\n".toByteArray()

        fun parseHead(head: ByteArray): Request? {
            val text = String(head, Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            val lines = text.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val i = line.indexOf(':')
                if (i <= 0 || line.substring(0, i) != line.substring(0, i).trim()) return null
                headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
            }
            return Request(parts[0], parts[1].substringBefore('?'), headers)
        }

        fun reason(status: Int) = when (status) {
            101 -> "Switching Protocols"; 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            410 -> "Gone"; 413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"; 426 -> "Upgrade Required"
            429 -> "Too Many Requests"; else -> "Status"
        }

        fun respond(out: OutputStream, status: Int, body: JSONObject, close: Boolean) {
            val data = body.toString().toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${data.size}\r\nConnection: ${if (close) "close" else "keep-alive"}\r\nServer: $SERVER\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1) + data)
            out.flush()
        }

        /** The 101 answer to a WebSocket upgrade, or an error status and why. */
        fun acceptKey(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
    }
}

/**
 * The server side of one WebSocket (RFC 6455) on a socket whose TLS and
 * fingerprint are already checked: text frames of JSON, at most 1 MiB a
 * message, client frames masked, no extensions or subprotocols. Pings are
 * answered; a close is echoed.
 */
class WsServerConn private constructor(
    private val socket: Socket,
    private val input: InputStream,
) {
    private val out = socket.getOutputStream()
    private val writeLock = Any()
    /**
     * Frames go out on a thread of their own, in order: a send never blocks
     * the caller (state updates come from the main thread, where Android
     * forbids network I/O), and a close goes out after what was queued before it.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mesh-ws-write").apply { isDaemon = true }
    }
    @Volatile var closed = false; private set
    @Volatile var lastRx = System.currentTimeMillis(); private set
    @Volatile private var closeSent = false

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)
    class ProtocolError(val code: Int, message: String) : IOException(message)

    companion object {
        const val MAX_MESSAGE = 1024 * 1024

        /** Answers the upgrade in [req]. Null (and an error answer sent) if it isn't a valid one. */
        fun accept(socket: SSLSocket, req: MeshServer.Request, leftover: ByteArray): WsServerConn? {
            val out = socket.outputStream
            val key = req.header("sec-websocket-key")
            val keyOk = key != null && runCatching { Base64.getDecoder().decode(key).size == 16 }.getOrDefault(false)
            val connection = req.header("connection")?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
            when {
                req.header("sec-websocket-version") != "13" -> {
                    out.write(("HTTP/1.1 426 Upgrade Required\r\nSec-WebSocket-Version: 13\r\nContent-Length: 0\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    return null
                }
                !keyOk || "upgrade" !in connection -> {
                    MeshServer.respond(out, 400, JSONObject().put("error", "bad WebSocket request"), close = true)
                    return null
                }
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${MeshServer.acceptKey(key!!)}\r\nServer: ${MeshServer.SERVER}\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = if (leftover.isEmpty()) socket.inputStream
            else java.io.SequenceInputStream(leftover.inputStream(), socket.inputStream)
            return WsServerConn(socket, input)
        }
    }

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw IOException("closed")
            got += r
        }
        return b
    }

    fun readFrame(): Frame {
        val h = readFully(2)
        lastRx = System.currentTimeMillis()
        val b0 = h[0].toInt() and 0xff
        val b1 = h[1].toInt() and 0xff
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0f
        if (b0 and 0x70 != 0) throw ProtocolError(1002, "reserved bits set")
        if (b1 and 0x80 == 0) throw ProtocolError(1002, "client frames must be masked")
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = readFully(2).let { ((it[0].toLong() and 0xff) shl 8) or (it[1].toLong() and 0xff) }
        else if (len == 127L) len = readFully(8).fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
        if (opcode >= 8 && (len > 125 || !fin)) throw ProtocolError(1002, "bad control frame")
        if (opcode in 3..7 || opcode > 10) throw ProtocolError(1002, "unknown opcode")
        if (len < 0 || len > MAX_MESSAGE) throw ProtocolError(1009, "message too big")
        val mask = readFully(4)
        val payload = readFully(len.toInt())
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        lastRx = System.currentTimeMillis()
        return Frame(fin, opcode, payload)
    }

    /**
     * Reads until a whole text message arrives (answering pings on the way).
     * Null when the peer closed. Binary messages are read and dropped.
     */
    fun readMessage(): String? {
        var parts: ByteArrayOutputStream? = null
        var binary = false
        while (true) {
            val f = try {
                readFrame()
            } catch (e: ProtocolError) {
                close(e.code, e.message ?: "")
                return null
            }
            when (f.opcode) {
                0x9 -> queue(0xA, f.payload)
                0xA -> Unit
                0x8 -> {
                    val code = if (f.payload.size >= 2) ((f.payload[0].toInt() and 0xff) shl 8) or (f.payload[1].toInt() and 0xff) else 1000
                    close(if (code in 1000..4999 && code !in setOf(1004, 1005, 1006, 1015)) code else 1000, "")
                    return null
                }
                0x1, 0x2 -> {
                    if (parts != null) {
                        close(1002, "expected a continuation")
                        return null
                    }
                    parts = ByteArrayOutputStream()
                    binary = f.opcode == 0x2
                    parts.write(f.payload)
                }
                0x0 -> {
                    if (parts == null) {
                        close(1002, "continuation without a start")
                        return null
                    }
                    parts.write(f.payload)
                }
            }
            if (parts != null && parts.size() > MAX_MESSAGE) {
                close(1009, "message too big")
                return null
            }
            if (f.opcode < 8 && f.fin && parts != null) {
                val data = parts.toByteArray()
                parts = null
                if (binary) continue   // the mesh doesn't use binary messages
                val decoder = Charsets.UTF_8.newDecoder()
                return try {
                    decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
                } catch (e: java.nio.charset.CharacterCodingException) {
                    close(1007, "not UTF-8")
                    null
                }
            }
        }
    }

    private fun send(opcode: Int, payload: ByteArray): Boolean {
        synchronized(writeLock) {
            if (closed || (closeSent && opcode != 0x8)) return false
            val n = payload.size
            val head = ByteArrayOutputStream(10)
            head.write(0x80 or opcode)
            when {
                n < 126 -> head.write(n)
                n <= 0xffff -> { head.write(126); head.write(n shr 8); head.write(n and 0xff) }
                else -> { head.write(127); for (i in 7 downTo 0) head.write(((n.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            return try {
                out.write(head.toByteArray() + payload)
                out.flush()
                true
            } catch (e: IOException) {
                shutdown()
                false
            }
        }
    }

    /** Queues a frame; false if the connection is closing or closed. */
    private fun queue(opcode: Int, payload: ByteArray): Boolean {
        if (closed || closeSent) return false
        return try {
            writer.execute { send(opcode, payload) }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    fun sendText(text: String): Boolean = queue(0x1, text.toByteArray(Charsets.UTF_8))

    fun ping(): Boolean = queue(0x9, (System.currentTimeMillis() / 1000).toString().toByteArray())

    /** A close frame after everything queued, then the socket closes. */
    fun close(code: Int = 1000, reason: String = "") {
        val r = reason.toByteArray(Charsets.UTF_8).take(120).toByteArray()
        try {
            writer.execute {
                synchronized(writeLock) {
                    if (!closed && !closeSent) {
                        closeSent = true
                        runCatching {
                            out.write(byteArrayOf(0x88.toByte(), (2 + r.size).toByte(), (code shr 8).toByte(), (code and 0xff).toByte()) + r)
                            out.flush()
                        }
                    }
                }
                shutdown()
            }
            writer.shutdown()
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            shutdown()
        }
    }

    /** Closes the socket now, whatever is queued (a peer that stopped answering). */
    fun shutdown() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        writer.shutdownNow()
    }
}
