package dev.droplet.app.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The remote channel to one paired TV (port 6466), kept open while
 * something uses it and re-opened with backoff when it drops.
 *
 * Talks the TV side's way, as androidtvremote2 does: the TV sends its
 * configuration and droplet answers with the features both have; the TV
 * says which are active and droplet agrees; then "remote start" (on or
 * off), the volume and the app in front, and a ping every 5 seconds when
 * nothing else is said, answered at once. Sixteen seconds of silence means
 * the connection is dead, and it's opened again.
 *
 * Keys, text and app links go out on a writer thread, so the UI never
 * touches the socket. A long press is START_LONG when the finger has been
 * down long enough and END_LONG when it lifts, but never sooner than
 * [LONG_MIN_MS] after the start, so the TV always sees a long press.
 *
 * A TV that refuses droplet's certificate in the handshake has forgotten
 * it: the link stops and says [Phase.NEEDS_PAIRING]. A TV whose own
 * certificate isn't the paired one stops it with [Phase.IDENTITY_CHANGED].
 */
class TvLink(
    private val identity: TvIdentity,
    host: String,
    private val port: Int,
    /** The TV's certificate fingerprint from pairing. */
    private val pin: String,
    private val log: (String) -> Unit = {},
    private val timing: Timing = Timing(),
) {
    /** Timeouts and delays, shorter in tests. */
    data class Timing(
        val connectMs: Int = 8_000,
        /** How long the TV may take to say "remote start" after the handshake. */
        val startMs: Int = 10_000,
        /** Silence after which the connection counts as dead (the TV pings every 5 s). */
        val idleMs: Int = 16_000,
        val backoffFirstMs: Long = 1_000,
        val backoffMaxMs: Long = 30_000,
        /** How long a dropped connection shows as "connecting" rather than "can't reach". */
        val graceMs: Long = 12_000,
    )

    enum class Phase { STOPPED, CONNECTING, CONNECTED, UNREACHABLE, NEEDS_PAIRING, IDENTITY_CHANGED }

    data class Volume(val level: Int, val max: Int, val muted: Boolean)

    data class State(
        val phase: Phase = Phase.STOPPED,
        val on: Boolean? = null,
        val app: String? = null,
        val volume: Volume? = null,
        /** "TCL 43P635", from the TV's configure message. */
        val model: String? = null,
        val error: String? = null,
    ) {
        val connected: Boolean get() = phase == Phase.CONNECTED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var host: String = host
        private set

    private val lock = Object()
    @Volatile private var running = false
    /** Which start() the loop thread belongs to: a loop from before a stop() and start() ends itself. */
    @Volatile private var runId = 0
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var kicked = false
    private var loop: Thread? = null
    /** Bumped on every new connection: a long press started on an old one isn't ended on the new one. */
    private val generation = AtomicInteger()
    private val queued = AtomicInteger()
    private var writer: ScheduledExecutorService? = null

    // what the TV told this connection
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var features = RemoteMsg.WANTED
    /** When a connection last came up (or the link started), for the grace before "can't reach". */
    @Volatile private var upAt = 0L
    /** Handshakes the TV closed without a word, in a row (see [classify]). */
    private var silentRefusals = 0

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val id = ++runId
            kicked = false
            silentRefusals = 0
            upAt = System.currentTimeMillis()
            writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-writer").apply { isDaemon = true } }
            _state.update { State(phase = Phase.CONNECTING, model = it.model) }
            loop = thread(name = "tv-link", isDaemon = true) { run(id) }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            runId++
            lock.notifyAll()
            writer?.shutdownNow()
            writer = null
            _state.update { it.copy(phase = Phase.STOPPED, error = null) }
        }
        closeSocket()
        loop?.interrupt()
        loop = null
    }

    val isRunning: Boolean get() = running

    /** Try now rather than at the next backoff step: a key press while it's down, the TV announcing itself. */
    fun kick() {
        synchronized(lock) {
            kicked = true
            lock.notifyAll()
        }
    }

    /** The TV moved (DHCP): connect there from now on. */
    fun moveTo(newHost: String) {
        if (newHost == host) return
        host = newHost
        closeSocket()
        kick()
    }

    // --- sending ------------------------------------------------------------------------

    /** Keys waiting to go out. The UI skips a repeat when the TV is behind, so it doesn't play out a backlog. */
    val backlog: Int get() = queued.get()

    /** A key press ([RemoteMsg.SHORT]) or half of a long one. False when the TV isn't connected. */
    fun key(code: Int, direction: Int = RemoteMsg.SHORT): Boolean = send(RemoteMsg.key(code, direction))

    fun text(text: String): Boolean = send(RemoteMsg.text(text, imeCounter, fieldCounter))

    /** Opens [link] on the TV: an https link, or market://launch?id=<package>. */
    fun link(link: String): Boolean = send(RemoteMsg.appLink(link))

    /** A long press has begun; returns a token for [endLong], or null when the TV isn't connected. */
    fun startLong(code: Int): LongPress? {
        val gen = generation.get()
        if (!key(code, RemoteMsg.START_LONG)) return null
        return LongPress(code, gen, System.nanoTime())
    }

    class LongPress internal constructor(val code: Int, internal val generation: Int, internal val startedNs: Long)

    /** Lets go of a long press, no sooner than [LONG_MIN_MS] after it began, and only on the same connection. */
    fun endLong(press: LongPress) {
        val heldMs = (System.nanoTime() - press.startedNs) / 1_000_000
        val wait = (LONG_MIN_MS - heldMs).coerceAtLeast(0)
        val w = writer ?: return
        runCatching {
            w.schedule({
                if (generation.get() == press.generation) write(RemoteMsg.key(press.code, RemoteMsg.END_LONG))
            }, wait, TimeUnit.MILLISECONDS)
        }
    }

    private fun send(message: ByteArray): Boolean {
        if (!state.value.connected) {
            kick()
            return false
        }
        val w = writer ?: return false
        queued.incrementAndGet()
        return try {
            w.execute {
                try {
                    write(message)
                } finally {
                    queued.decrementAndGet()
                }
            }
            true
        } catch (e: Exception) {   // shut down
            queued.decrementAndGet()
            false
        }
    }

    private fun write(message: ByteArray) {
        val s = socket ?: return
        try {
            synchronized(s) { Frames.write(s.outputStream, message) }
        } catch (e: IOException) {
            log("write failed: ${e.message}")
            runCatching { s.close() }   // the reader notices and reconnects
        }
    }

    // --- the connection loop ---------------------------------------------------------------

    private fun alive(id: Int) = running && runId == id

    private fun run(id: Int) {
        var delay = timing.backoffFirstMs
        while (alive(id)) {
            val outcome = try {
                connectOnce(id)
            } catch (e: Exception) {   // e.g. an address Java can't parse
                TvException(TvException.Kind.UNREACHABLE, e.message ?: e.javaClass.simpleName, e)
            }
            synchronized(lock) {
                if (!alive(id)) return
                when (outcome.kind) {
                    TvException.Kind.NEEDS_PAIRING, TvException.Kind.IDENTITY_CHANGED -> {
                        log("stopped: ${outcome.message}")
                        running = false
                        writer?.shutdownNow()
                        writer = null
                        val phase = if (outcome.kind == TvException.Kind.NEEDS_PAIRING) Phase.NEEDS_PAIRING else Phase.IDENTITY_CHANGED
                        _state.update { it.copy(phase = phase, error = outcome.message) }
                        return
                    }
                    else -> Unit
                }
                val wasUp = outcome.message == LOST
                if (wasUp) delay = LOST_RETRY_MS
                val grace = System.currentTimeMillis() - upAt < timing.graceMs
                _state.update { it.copy(phase = if (grace) Phase.CONNECTING else Phase.UNREACHABLE, error = outcome.message) }
                log("not connected (${outcome.kind}: ${outcome.message}); again in $delay ms")
                if (!kicked) {
                    try {
                        lock.wait(delay)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                delay = if (kicked || wasUp) timing.backoffFirstMs else (delay * 2).coerceAtMost(timing.backoffMaxMs)
                kicked = false
            }
        }
    }

    /**
     * One connection, from the handshake until it drops. Always ends in a
     * [TvException] saying why; [LOST] when it had been up.
     */
    private fun connectOnce(id: Int): TvException {
        val trust = AcceptAnyTv(pin)
        val target = host
        val s = try {
            openTls(identity, trust, target, port, timing.connectMs)
        } catch (e: TvException) {
            return noteRefusal(e)
        }
        socket = s
        generation.incrementAndGet()
        imeCounter = 0
        fieldCounter = 0
        features = RemoteMsg.WANTED
        var started = false
        try {
            s.soTimeout = timing.startMs
            while (alive(id)) {
                val data = try {
                    Frames.read(s.inputStream)
                } catch (e: SocketTimeoutException) {
                    return if (started) {
                        TvException(TvException.Kind.CLOSED, LOST)
                    } else TvException(TvException.Kind.TIMEOUT, "the TV didn't start the remote")
                } ?: return if (started) TvException(TvException.Kind.CLOSED, LOST)
                    else noteRefusal(TvException(TvException.Kind.CLOSED, "the TV closed the connection"))
                val msg = try {
                    RemoteMsg.parse(data)
                } catch (e: ProtoException) {
                    log("skipped a message droplet can't read: ${e.message}")
                    continue   // as the library does: one bad message isn't the end
                }
                if (msg is RemoteMsg.In.Start && !started) {
                    started = true
                    silentRefusals = 0
                    upAt = System.currentTimeMillis()
                    s.soTimeout = timing.idleMs
                    synchronized(lock) { if (alive(id)) _state.update { it.copy(phase = Phase.CONNECTED, on = msg.started, error = null) } }
                    log("connected to $target")
                    continue
                }
                handle(s, msg)
            }
            return TvException(TvException.Kind.CLOSED, "stopped")
        } catch (e: IOException) {
            val why = classify(e, trust, started)
            return if (started && why.kind != TvException.Kind.NEEDS_PAIRING) TvException(TvException.Kind.CLOSED, LOST, e)
            else noteRefusal(why)
        } finally {
            socket = null
            runCatching { s.close() }
            if (started) {
                upAt = System.currentTimeMillis()
                synchronized(lock) { if (alive(id)) _state.update { it.copy(phase = Phase.CONNECTING) } }
            }
        }
    }

    /**
     * A TV that drops the connection in the handshake, or before "remote
     * start", without a TLS alert, three times running: that's a TV
     * refusing the certificate too (androidtvremote2 counts any TLS error as
     * "pair again"; droplet waits for a pattern, so one hiccup doesn't).
     */
    private fun noteRefusal(e: TvException): TvException {
        val silent = e.kind == TvException.Kind.CLOSED || (e.kind == TvException.Kind.UNREACHABLE && e.cause is TlsClosed)
        if (!silent) {
            silentRefusals = 0
            return e
        }
        silentRefusals++
        return if (silentRefusals >= SILENT_REFUSALS) {
            TvException(TvException.Kind.NEEDS_PAIRING, "the TV keeps closing the connection before the remote starts", e)
        } else e
    }

    private fun handle(s: SSLSocket, msg: RemoteMsg.In) {
        when (msg) {
            is RemoteMsg.In.Configure -> {
                features = RemoteMsg.WANTED and msg.features
                val model = listOf(msg.vendor, msg.model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
                _state.update { it.copy(model = model ?: it.model) }
                reply(s, RemoteMsg.configure(features))
            }
            is RemoteMsg.In.SetActive -> reply(s, RemoteMsg.setActive(features))
            is RemoteMsg.In.Ping -> reply(s, RemoteMsg.pingResponse(msg.val1))
            is RemoteMsg.In.Start -> _state.update { it.copy(on = msg.started) }
            is RemoteMsg.In.Volume -> _state.update { it.copy(volume = Volume(msg.level, msg.max, msg.muted)) }
            is RemoteMsg.In.App -> _state.update { it.copy(app = msg.pkg.ifEmpty { null }) }
            is RemoteMsg.In.ImeCounters -> {
                imeCounter = msg.ime
                fieldCounter = msg.field
            }
            RemoteMsg.In.Error -> log("the TV reported an error")
            RemoteMsg.In.Other -> Unit
        }
    }

    /** Answers go out on the reader thread, in order, before the next message is read. */
    private fun reply(s: SSLSocket, message: ByteArray) {
        synchronized(s) { Frames.write(s.outputStream, message) }
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close() } }
    }

    /** The TLS layer closed without an alert (see [classify]). */
    class TlsClosed(cause: Throwable) : IOException(cause.message, cause)

    companion object {
        /** A long press lasts at least this long on the TV (tv.py's LONG_PRESS). */
        const val LONG_MIN_MS = 900L
        private const val LOST_RETRY_MS = 300L
        private const val SILENT_REFUSALS = 3
        private const val LOST = "the connection dropped"

        /** TLS alerts a server sends when it doesn't accept the client's certificate. */
        private val AUTH_ALERTS = listOf(
            "bad_certificate", "unsupported_certificate", "certificate_revoked", "certificate_expired",
            "certificate_unknown", "unknown_ca", "access_denied", "certificate_required", "handshake_failure",
        )

        /**
         * What an I/O error on the TV's ports means. A TLS alert about the
         * certificate, before the remote has started, is the TV refusing
         * droplet's certificate (in TLS 1.2 it comes in the handshake; in
         * TLS 1.3 on the first read after it). A TLS layer closed without an
         * alert is a [TlsClosed] (see [noteRefusal]). The rest is the network.
         */
        internal fun classify(e: Throwable, trust: AcceptAnyTv, started: Boolean): TvException {
            if (trust.mismatch) return TvException(TvException.Kind.IDENTITY_CHANGED, "the TV's certificate isn't the one it paired with", e)
            if (e is TvException) return e
            val text = generateSequence(e) { it.cause }.take(8).mapNotNull { it.message?.lowercase() }.joinToString(" | ")
            val ssl = generateSequence(e) { it.cause }.take(8).any { it is SSLException }
            if (!started && ssl && AUTH_ALERTS.any { it in text } && ("alert" in text || "sslv3" in text || "tlsv1" in text)) {
                return TvException(TvException.Kind.NEEDS_PAIRING, "the TV refused droplet's certificate (${e.message})", e)
            }
            if (e is SocketTimeoutException) return TvException(TvException.Kind.TIMEOUT, "the TV stopped answering", e)
            if (!started && ssl && generateSequence(e) { it.cause }.take(8).any { it is EOFException } ||
                !started && ssl && ("terminated the handshake" in text || "connection closed" in text || "eof" in text)) {
                return TvException(TvException.Kind.UNREACHABLE, "the TV closed the TLS handshake", TlsClosed(e))
            }
            return TvException(TvException.Kind.UNREACHABLE, e.message ?: e.javaClass.simpleName, e)
        }
    }
}
