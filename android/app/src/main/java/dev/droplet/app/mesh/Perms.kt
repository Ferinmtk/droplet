package dev.droplet.app.mesh

import org.json.JSONObject

/**
 * Per-device permissions and Pause (docs/mesh.md §9.9), as the Linux
 * agent's mesh/perms.py, the reference.
 *
 * Each trusted peer has a **relation**, the owner's answer to "Is this your
 * device, or someone else's?", and a switch per **capability**. A peer can
 * also be **paused**: nothing goes to it and nothing from it is taken, except
 * the few messages that keep the link and its status working. A **global
 * pause** does that for every peer at once.
 *
 * Enforcement is local and both ways: this phone checks what it sends and
 * what it accepts, whatever the peer says. What a peer tells us about its own
 * switches (`perm`, in its hello and as a message) is only a hint for the UI,
 * and for not sending what it would refuse anyway.
 */
object Perms {
    const val OWN_DEVICE = "own"
    const val OTHER_DEVICE = "other"
    val RELATIONS = listOf(OWN_DEVICE, OTHER_DEVICE)

    const val FILES = "files"
    const val CHAT = "chat"
    const val CLIPBOARD = "clipboard"
    const val NOTIFY = "notify"
    const val CONTROL = "control"
    const val RING = "ring"
    const val ACCESS = "access"

    /** Every capability, in the order screens show them. */
    val CAPABILITIES = listOf(FILES, CHAT, CLIPBOARD, NOTIFY, CONTROL, RING, ACCESS)

    /** The words in "<name> doesn't allow <noun> from you". */
    val NOUNS = mapOf(
        FILES to "files", CHAT to "messages", CLIPBOARD to "the clipboard", NOTIFY to "notifications",
        CONTROL to "remote control", RING to "ringing", ACCESS to "SMS, files and commands",
    )

    /**
     * About this device only: the switch says what the peer may do here. What
     * this phone may do to the peer is the peer's own switch, which it enforces
     * (and tells us, as a hint).
     */
    val INBOUND_ONLY = setOf(CONTROL, RING, ACCESS)

    val OWN: Map<String, Boolean> = CAPABILITIES.associateWith { true }
    val OTHER: Map<String, Boolean> = mapOf(FILES to true, CHAT to true, CLIPBOARD to false, NOTIFY to false,
        CONTROL to false, RING to true, ACCESS to false)

    /**
     * Always allowed, even paused: what keeps the link up, says why something
     * was refused, and lets either side unpair. ack and nack answer what was
     * sent before the pause.
     */
    val ALWAYS = setOf("hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "error",
        "rpc-result")

    fun defaults(relation: String?): Map<String, Boolean> = if (relation == OTHER_DEVICE) OTHER else OWN

    fun cleanRelation(v: Any?): String = if (v is String && v in RELATIONS) v else OWN_DEVICE

    /** Every capability, as a bool: the relation's default where [v] doesn't say. */
    fun cleanAllow(v: Any?, relation: String = OWN_DEVICE): Map<String, Boolean> {
        val out = LinkedHashMap(defaults(relation))
        when (v) {
            is JSONObject -> for (c in CAPABILITIES) (v.opt(c) as? Boolean)?.let { out[c] = it }
            is Map<*, *> -> for (c in CAPABILITIES) (v[c] as? Boolean)?.let { out[c] = it }
        }
        return out
    }

    fun allowJson(allow: Map<String, Boolean>): JSONObject = JSONObject().apply { for (c in CAPABILITIES) put(c, allow[c] ?: true) }

    /**
     * The capability a message needs, either way. Null: it needs none (but
     * still stops while paused, unless it's in [ALWAYS]).
     */
    fun capability(msg: JSONObject): String? = when (msg.optString("t")) {
        "text" -> CHAT
        "offer", "file", "file-end" -> FILES
        "clip" -> CLIPBOARD
        "notify", "notify-removed" -> NOTIFY
        "input", "media", "cmd" -> CONTROL
        "ring", "ring-stop" -> RING
        "rpc" -> if ((msg.opt("method") as? String).orEmpty().startsWith("media.")) CONTROL else ACCESS
        // what's playing is part of remote control; the battery level is just there
        "state" -> if (msg.opt("kind") == "media") CONTROL else null
        else -> null
    }

    /** Why a message may not pass, and the capability it needed ("" for none). */
    data class No(val why: String, val cap: String)

    /**
     * Whether [msg] may come from ([direction] "in") or go to ("out") the peer
     * [entry], by this phone's own settings. Null if it may.
     */
    fun check(entry: TrustList.Entry?, msg: JSONObject, pausedAll: Boolean = false, direction: String = "in"): No? {
        val t = msg.optString("t")
        if (t in ALWAYS) return null
        val cap = capability(msg)
        if (pausedAll || entry?.paused == true) return No(PAUSED, cap.orEmpty())
        if (direction == "out" && cap in INBOUND_ONLY && t != "state") return null   // the peer decides
        if (cap != null && !allowed(entry, cap)) return No(DENIED, cap)
        return null
    }

    fun allowed(entry: TrustList.Entry?, cap: String): Boolean = entry?.allow?.get(cap) ?: (entry != null)

    /** What this phone tells a peer about how it treats it: `perm` (a hint for its UI). */
    fun remoteView(entry: TrustList.Entry?, pausedAll: Boolean): JSONObject = JSONObject()
        .put("paused", pausedAll || entry?.paused == true)
        .put("allow", allowJson(entry?.allow ?: OWN))

    /** What a peer said about how it treats this phone. */
    data class Remote(val paused: Boolean, val allow: Map<String, Boolean>)

    /** A peer's `perm`, cleaned. Null if it sent none (an older peer: everything as before). */
    fun parseRemote(v: Any?): Remote? {
        if (v !is JSONObject) return null
        val a = v.optJSONObject("allow")
        val allow = LinkedHashMap<String, Boolean>()
        if (a != null) for (c in CAPABILITIES) (a.opt(c) as? Boolean)?.let { allow[c] = it }
        return Remote(v.opt("paused") == true, allow)
    }

    /** What the peer said it would do with [cap] from us: "paused", "denied" or null. */
    fun remoteRefuses(remote: Remote?, cap: String?): String? = when {
        remote == null -> null
        remote.paused -> PAUSED
        cap != null && remote.allow[cap] == false -> DENIED
        else -> null
    }

    /** What the sender shows: "Brian's laptop doesn't allow the clipboard from you". */
    fun refusalText(name: String, why: String, cap: String?): String =
        if (why == PAUSED) "$name paused sharing with you"
        else "$name doesn't allow ${NOUNS[cap.orEmpty()] ?: cap?.ifEmpty { null } ?: "that"} from you"

    /** Why this phone itself won't send it. */
    fun localText(name: String, why: String, cap: String?, pausedAll: Boolean = false): String {
        if (why == PAUSED) return if (pausedAll) "everything is paused on this device: resume to send"
            else "$name is paused: resume it to send"
        val noun = NOUNS[cap.orEmpty()] ?: cap?.ifEmpty { null } ?: "that"
        return "${noun.replaceFirstChar { it.uppercase() }} with $name is switched off here"
    }

    const val PAUSED = "paused"
    const val DENIED = "denied"
}

/**
 * Not sent: this phone's own settings say no ([local]), or the peer said it
 * would refuse. A [NoRoute], so screens that show why something didn't go
 * show this too.
 */
class Refused(text: String, val why: String, val cap: String?, val local: Boolean) : NoRoute(text)
