package dev.droplet.app

import dev.droplet.app.mesh.MeshNode
import dev.droplet.app.mesh.TrustList
import org.json.JSONObject

/**
 * Notification mirroring over the mesh (docs/mesh.md §9.4): this phone's
 * notifications go straight to your paired computers as `notify`, and their
 * removal as `notify-removed`, with or without a hub.
 *
 * Only computers that say they show them (the `notify` cap) get them. What
 * reaches here has already passed [MirrorService]'s filter (Apps not to
 * mirror, ongoing, group summaries, local-only); this adds the mesh's own
 * rules: alerting ones only, no repeats of what a computer already shows,
 * and a cap on bursts.
 *
 * It never keeps the radio awake: a notification goes over a link that's
 * already open, and a computer with none is dialled only when a notification
 * arrives, and at most once every [DIAL_EVERY_MS]. A link opened this way
 * closes on its own once idle (MeshNode.IDLE_CLOSE_MS).
 *
 * Not thread-safe by itself: [posted], [removed] and [flush] lock.
 */
class NotifyMirror(
    private val node: () -> MeshNode?,
    private val enabled: () -> Boolean,
    private val excluded: () -> Set<String>,
    /** Runs a dial (it blocks) off the caller's thread. */
    private val background: (() -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Which peers get them; [wants] but for tests, where every peer is a JVM "android". */
    private val target: (TrustList.Entry) -> Boolean = { wants(it) },
) {
    private val lock = Any()
    /** Waiting to go: key → the `notify` message, newest last. */
    private val pending = LinkedHashMap<String, JSONObject>()
    private val pendingPkg = HashMap<String, String>()
    /** Removals waiting to go: key → the computers that were sent it. */
    private val gone = LinkedHashMap<String, Set<String>>()
    /** What each key last looked like, and which computers have it. */
    private val sent = LinkedHashMap<String, Sent>()
    private val dialledAt = HashMap<String, Long>()
    private var tokens = BURST.toDouble()
    private var refilledAt = now()

    private class Sent(val digest: Int, val to: MutableSet<String>)

    /**
     * A notification was posted or updated. [item] is what [MirrorService]
     * made of it (key, package, app, title, text). [alerting] is false for one
     * the phone itself shows silently: a computer shouldn't pop up for it.
     */
    fun posted(item: JSONObject, alerting: Boolean = true) {
        if (!enabled() || !alerting) return
        val key = item.optString("key").ifEmpty { return }
        val msg = JSONObject().put("t", "notify").put("key", key.take(MAX_KEY))
            .put("app", item.optString("app").take(80))
            .put("title", item.optString("title").take(200))
            .put("text", item.optString("text").take(1000))
        synchronized(lock) {
            val before = sent[key]
            // an app posting the same notification again (a progress tick, a re-sort): nothing new to show
            if (before != null && before.digest == digest(msg) && before.to.isNotEmpty()) return
            gone.remove(key)
            pending.remove(key)
            pending[key] = msg
            pendingPkg[key] = item.optString("package")
            while (pending.size > MAX_PENDING) {
                val oldest = pending.keys.first()
                pending.remove(oldest)
                pendingPkg.remove(oldest)
            }
        }
    }

    /** A notification went from the phone: take it away where it was shown. */
    fun removed(key: String) {
        synchronized(lock) {
            if (pending.remove(key) != null) pendingPkg.remove(key)
            val s = sent.remove(key) ?: return
            if (s.to.isNotEmpty()) gone[key] = s.to.toSet()
        }
    }

    /** Anything to send? */
    fun hasWork(): Boolean = synchronized(lock) { pending.isNotEmpty() || gone.isNotEmpty() }

    /**
     * The computers that show this phone's notifications, and may have them:
     * not paused, Notifications on for that device, and (as it said) taking
     * them from this phone (docs/mesh.md §9.9).
     */
    fun targets(n: MeshNode): List<TrustList.Entry> = n.trust.all().filter { target(it) && n.mayGo(it.fp, PROBE) }

    /**
     * Sends what's waiting: over open links at once, and to a computer with
     * none by dialling it in the background. Returns how many messages went
     * out over links already open (the dialled ones follow).
     */
    fun flush(): Int {
        val n = node()
        if (n == null || !enabled()) {
            // nobody to send to now; a notification shown later is better than a stale burst
            synchronized(lock) { pending.clear(); pendingPkg.clear(); gone.clear() }
            return 0
        }
        val (posts, removals) = synchronized(lock) {
            val skip = excluded()
            refill()
            val ok = pending.entries.filter { (pendingPkg[it.key] ?: "") !in skip }.map { it.value }
            // a burst: the newest ones go, the rest are dropped rather than queued
            val allowed = tokens.toInt().coerceAtLeast(0)
            val posts = if (ok.size > allowed) {
                log("mesh: ${ok.size - allowed} notifications not mirrored (too many at once)")
                ok.takeLast(allowed)
            } else ok
            tokens -= posts.size
            pending.clear()
            pendingPkg.clear()
            val removals = LinkedHashMap(gone)
            gone.clear()
            posts to removals
        }
        if (posts.isEmpty() && removals.isEmpty()) return 0
        var onLinks = 0
        val computers = targets(n)
        for (e in computers) {
            val msgs = ArrayList<JSONObject>()
            for ((key, to) in removals) if (e.fp in to) msgs += JSONObject().put("t", "notify-removed").put("key", key.take(MAX_KEY))
            msgs += posts
            if (msgs.isEmpty()) continue
            val link = n.openLink(e.fp)
            if (link != null) {
                for (m in msgs) if (link.send(m)) {
                    onLinks++
                    record(m, e.fp)
                }
                continue
            }
            if (posts.isEmpty() || !mayDial(e.fp)) continue  // a removal alone isn't worth waking a link
            background {
                val l = runCatching { n.direct(e.fp) }.getOrNull()
                if (l == null) {
                    log("mesh: ${e.name} isn't reachable; its notifications weren't mirrored")
                    return@background
                }
                if (!n.mayGo(e.fp, PROBE)) return@background   // its welcome said it doesn't take them now
                for (m in msgs) if (l.send(m)) record(m, e.fp)
            }
        }
        return onLinks
    }

    private fun mayDial(fp: String): Boolean = synchronized(lock) {
        val t = now()
        val last = dialledAt[fp]
        if (last != null && t - last < DIAL_EVERY_MS) return false
        dialledAt[fp] = t
        true
    }

    private fun record(m: JSONObject, fp: String) = synchronized(lock) {
        val key = m.getString("key")
        if (m.getString("t") == "notify-removed") return@synchronized
        val s = sent[key]?.takeIf { it.digest == digest(m) } ?: Sent(digest(m), mutableSetOf()).also { sent.remove(key) }
        s.to += fp
        sent[key] = s
        while (sent.size > MAX_SENT) sent.remove(sent.keys.first())
    }

    private fun refill() {
        val t = now()
        tokens = (tokens + (t - refilledAt) / REFILL_MS.toDouble()).coerceAtMost(BURST.toDouble())
        refilledAt = t
    }

    private fun digest(m: JSONObject) = listOf(m.optString("app"), m.optString("title"), m.optString("text")).hashCode()

    companion object {
        /** The cap a computer announces when it shows mirrored notifications. */
        const val CAP = "notify"
        val COMPUTERS = setOf("linux", "windows", "macos")
        /** At most this many at once… */
        const val BURST = 10
        /** …then one more every this long (20 a minute). */
        const val REFILL_MS = 3_000L
        /** Notifications arriving within this long go out together. */
        const val FLUSH_DELAY_MS = 1_000L
        /** A computer with no open link is dialled at most this often. */
        const val DIAL_EVERY_MS = 60_000L
        private const val MAX_PENDING = 50
        private const val MAX_SENT = 300
        private const val MAX_KEY = 200

        fun wants(e: TrustList.Entry): Boolean = e.os in COMPUTERS && CAP in e.caps

        /** What every message here needs: the "notify" capability. */
        private val PROBE: JSONObject get() = JSONObject().put("t", "notify")
    }
}
