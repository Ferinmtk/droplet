package dev.droplet.app.tv

/**
 * The pairing messages ("polo", port 6467): polo.proto's OuterMessage, a
 * proto2 message, so every field that's set is written, defaults included.
 * Field numbers are androidtvremote2's polo.proto.
 */
internal object Polo {
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    private const val PROTOCOL_VERSION = 2
    private const val ENCODING_HEXADECIMAL = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    // OuterMessage fields
    private const val F_PROTOCOL_VERSION = 1
    private const val F_STATUS = 2
    private const val F_PAIRING_REQUEST = 10
    private const val F_PAIRING_REQUEST_ACK = 11
    private const val F_OPTIONS = 20
    private const val F_CONFIGURATION = 30
    private const val F_CONFIGURATION_ACK = 31
    private const val F_SECRET = 40
    private const val F_SECRET_ACK = 41

    private fun outer() = ProtoWriter().int(F_PROTOCOL_VERSION, PROTOCOL_VERSION).int(F_STATUS, STATUS_OK)

    private fun hexEncoding() = ProtoWriter().int(1, ENCODING_HEXADECIMAL).int(2, SYMBOL_LENGTH)

    /** Asks to pair; [clientName] is what the TV shows as the remote's name. */
    fun pairingRequest(clientName: String): ByteArray =
        outer().message(F_PAIRING_REQUEST, ProtoWriter().string(1, "atvremote").string(2, clientName)).toByteArray()

    /** After the TV's pairing_request_ack: droplet types in a 6-character hex code. */
    fun options(): ByteArray =
        outer().message(F_OPTIONS, ProtoWriter().message(1, hexEncoding()).int(3, ROLE_INPUT)).toByteArray()

    /** After the TV's options: the same, as the configuration; its ack means the code is on screen. */
    fun configuration(): ByteArray =
        outer().message(F_CONFIGURATION, ProtoWriter().message(1, hexEncoding()).int(2, ROLE_INPUT)).toByteArray()

    fun secret(secret: ByteArray): ByteArray =
        outer().message(F_SECRET, ProtoWriter().bytes(1, secret)).toByteArray()

    enum class Kind { PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, OTHER }

    /** A message from the TV: its status and which step it answers. */
    data class Reply(val status: Int, val kind: Kind, val serverName: String?)

    fun parse(data: ByteArray): Reply {
        val m = ProtoFields.parse(data)
        // proto2 default for a missing status is STATUS_OK, the enum's first value
        val status = if (m.has(F_STATUS)) m.int(F_STATUS) else STATUS_OK
        val kind = when {
            m.has(F_PAIRING_REQUEST_ACK) -> Kind.PAIRING_REQUEST_ACK
            m.has(F_OPTIONS) -> Kind.OPTIONS
            m.has(F_CONFIGURATION_ACK) -> Kind.CONFIGURATION_ACK
            m.has(F_SECRET_ACK) -> Kind.SECRET_ACK
            else -> Kind.OTHER
        }
        val name = m.message(F_PAIRING_REQUEST_ACK)?.string(1)?.takeIf { it.isNotBlank() }
        return Reply(status, kind, name)
    }
}

/**
 * The remote channel's messages (port 6466): remotemessage.proto's
 * RemoteMessage, a proto3 message, so fields at their default (0, "",
 * false) are left out. What droplet sends mirrors androidtvremote2's
 * RemoteProtocol exactly, down to the device info it announces.
 */
internal object RemoteMsg {
    // RemoteMessage fields
    private const val F_CONFIGURE = 1
    private const val F_SET_ACTIVE = 2
    private const val F_ERROR = 3
    private const val F_PING_REQUEST = 8
    private const val F_PING_RESPONSE = 9
    private const val F_KEY_INJECT = 10
    private const val F_IME_KEY_INJECT = 20
    private const val F_IME_BATCH_EDIT = 21
    private const val F_START = 40
    private const val F_SET_VOLUME_LEVEL = 50
    private const val F_APP_LINK = 90

    /** Feature bits, as in androidtvremote2's remote.Feature. */
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_VOICE = 8
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512

    /** What droplet asks for: everything but voice. IME is needed to learn the app in front. */
    const val WANTED = FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    const val SHORT = 3
    const val START_LONG = 1
    const val END_LONG = 2

    private fun w(field: Int, body: ProtoWriter) = ProtoWriter().message(field, body).toByteArray()

    /** Answers the TV's configure: the features both sides have, and the library's device info. */
    fun configure(features: Int): ByteArray = w(F_CONFIGURE, ProtoWriter()
        .apply { if (features != 0) int(1, features) }
        .message(2, ProtoWriter().int(3, 1).string(4, "1").string(5, "atvremote").string(6, "1.0.0")))

    fun setActive(features: Int): ByteArray =
        w(F_SET_ACTIVE, ProtoWriter().apply { if (features != 0) int(1, features) })

    fun pingResponse(val1: Int): ByteArray =
        w(F_PING_RESPONSE, ProtoWriter().apply { if (val1 != 0) int(1, val1) })

    fun key(code: Int, direction: Int): ByteArray = w(F_KEY_INJECT, ProtoWriter()
        .apply { if (code != 0) int(1, code) }
        .apply { if (direction != 0) int(2, direction) })

    /**
     * Text for the focused text field, as the library sends it: one insert
     * whose start and end are the text's length less one, counted in code
     * points (Python's len), and the counters from the TV's last batch edit.
     */
    fun text(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        require(text.isNotEmpty()) { "no text" }
        val at = text.codePointCount(0, text.length) - 1
        val obj = ProtoWriter().apply {
            if (at != 0) { int(1, at); int(2, at) }
            string(3, text)
        }
        val edit = ProtoWriter().int(1, 1).message(2, obj)
        return w(F_IME_BATCH_EDIT, ProtoWriter()
            .apply { if (imeCounter != 0) int(1, imeCounter) }
            .apply { if (fieldCounter != 0) int(2, fieldCounter) }
            .message(3, edit))
    }

    fun appLink(link: String): ByteArray = w(F_APP_LINK, ProtoWriter().apply { if (link.isNotEmpty()) string(1, link) })

    /** A message from the TV that droplet acts on. */
    sealed class In {
        data class Configure(val features: Int, val model: String, val vendor: String, val version: String) : In()
        data class SetActive(val active: Int) : In()
        data class App(val pkg: String) : In()
        data class ImeCounters(val ime: Int, val field: Int) : In()
        data class Volume(val level: Int, val max: Int, val muted: Boolean) : In()
        data class Start(val started: Boolean) : In()
        data class Ping(val val1: Int) : In()
        data object Error : In()
        data object Other : In()
    }

    /** Which message it is, checked in the library's order (a RemoteMessage carries one). */
    fun parse(data: ByteArray): In {
        val m = ProtoFields.parse(data)
        m.message(F_CONFIGURE)?.let { c ->
            val info = c.message(2)
            return In.Configure(c.int(1), info?.string(1).orEmpty(), info?.string(2).orEmpty(), info?.string(6).orEmpty())
        }
        m.message(F_SET_ACTIVE)?.let { return In.SetActive(it.int(1)) }
        m.message(F_IME_KEY_INJECT)?.let { return In.App(it.message(1)?.string(12).orEmpty()) }
        m.message(F_IME_BATCH_EDIT)?.let { return In.ImeCounters(it.int(1), it.int(2)) }
        m.message(F_SET_VOLUME_LEVEL)?.let { return In.Volume(it.int(7), it.int(6), it.bool(8)) }
        m.message(F_START)?.let { return In.Start(it.bool(1)) }
        m.message(F_PING_REQUEST)?.let { return In.Ping(it.int(1)) }
        if (m.has(F_ERROR)) return In.Error
        return In.Other
    }
}
