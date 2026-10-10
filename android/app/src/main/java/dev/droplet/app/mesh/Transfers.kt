package dev.droplet.app.mesh

import java.util.ArrayDeque

/**
 * Files on their way, either way: how far each has got, how fast, and how
 * long it has left (docs/mesh.md §9.11), as the reference's
 * mesh/transfers.py.
 *
 * The numbers are real byte counts: for a file this phone sends, the bytes
 * it has served (the furthest byte of `GET /mesh/files/<id>`); for one it
 * receives, the bytes it has saved. What the home screen and the
 * notifications show of a transfer comes from here, and [MeshNode.cancel]
 * stops one by its id (an offer's id, which is also the outbox job's).
 *
 * A finished transfer is kept for a little while ([KEEP_MS]), so a screen
 * that looks a moment later still sees "Sent" or "Cancelled".
 */
class Transfers(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** One transfer as screens see it. [rate] in bytes a second and [eta] in seconds: null when unknown or stalled. */
    data class View(val id: String, val dir: String, val fp: String, val peer: String, val name: String, val size: Long,
                    val done: Long, val state: String, val error: String?, val route: String?, val percent: Int,
                    val rate: Long?, val eta: Long?) {
        val active: Boolean get() = state == ACTIVE
    }

    private class Item(val id: String, val dir: String, val fp: String, val peer: String, val name: String,
                       val size: Long, var done: Long, var state: String, var error: String?, val route: String?,
                       val started: Long, var ended: Long?)

    private val items = LinkedHashMap<String, Item>()
    private val samples = HashMap<String, ArrayDeque<Pair<Long, Long>>>()
    /** Called (outside the lock) when something changed: a screen redraws, at most a few times a second. */
    @Volatile var onChange: (() -> Unit)? = null

    /** A transfer begins (or starts again, resuming: what it had stays unless [done] says more). */
    fun start(id: String, dir: String, fp: String, peer: String, name: String, size: Long, route: String? = null, done: Long = 0) {
        val now = clock()
        synchronized(this) {
            val old = items[id]
            val had = if (old != null && old.state != CANCELLED) old.done else 0L
            val t = Item(id, dir, fp, peer, name, maxOf(0L, size), maxOf(done, had), ACTIVE, null, route,
                old?.started ?: now, null)
            items[id] = t
            samples[id] = ArrayDeque<Pair<Long, Long>>().apply { add(now to t.done) }
        }
        onChange?.invoke()
    }

    fun progress(id: String, done: Long) {
        val now = clock()
        synchronized(this) {
            val t = items[id] ?: return
            if (t.state != ACTIVE) return
            t.done = if (t.size > 0) done.coerceIn(0, t.size) else maxOf(done, 0)
            val s = samples.getOrPut(id) { ArrayDeque() }
            s.add(now to t.done)
            while (s.size > 2 && now - s.first.first > WINDOW_MS) s.removeFirst()
        }
        onChange?.invoke()
    }

    fun finish(id: String, state: String, error: String? = null) {
        synchronized(this) {
            val t = items[id] ?: return
            t.state = state
            t.error = error
            t.ended = clock()
            if (state == DONE) t.done = t.size
        }
        onChange?.invoke()
    }

    fun get(id: String): View? = synchronized(this) { items[id]?.let { view(it, clock()) } }

    fun active(): List<View> = list().filter { it.active }

    /** Every transfer, active ones first (oldest first), then the recently finished. */
    fun list(): List<View> {
        val now = clock()
        synchronized(this) {
            items.values.filter { it.ended != null && now - it.ended!! > KEEP_MS }.map { it.id }.forEach {
                items.remove(it)
                samples.remove(it)
            }
            return items.values.sortedWith(compareBy({ it.state != ACTIVE }, { it.started })).map { view(it, now) }
        }
    }

    private fun view(t: Item, now: Long): View {
        val percent = if (t.size > 0) (t.done * 100 / t.size).toInt() else if (t.state == DONE) 100 else 0
        var rate: Double? = null
        val s = samples[t.id]
        if (t.state == ACTIVE && s != null && s.size >= 2) {
            val (t0, d0) = s.first
            val (t1, d1) = s.last
            // quiet for a while: it's stalled, not still going at the last speed
            if (now - t1 < STALLED_MS && t1 > t0) rate = (d1 - d0) * 1000.0 / (t1 - t0)
        }
        val r = rate?.toLong()?.takeIf { it > 0 }
        val left = t.size - t.done
        val eta = if (rate != null && rate > 0 && left > 0) (left / rate + 0.5).toLong() else null
        return View(t.id, t.dir, t.fp, t.peer, t.name, t.size, t.done, t.state, t.error, t.route, percent, r, eta)
    }

    companion object {
        const val ACTIVE = "active"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        const val WAITING = "waiting"
        const val KEEP_MS = 120_000L
        const val WINDOW_MS = 4_000L
        const val STALLED_MS = 3_000L
        /** What a cancel by this device's owner records ("Cancelled", not "Cancelled: …"). */
        const val CANCELLED_HERE = "cancelled here"

        /** "976.6 KB", "12 bytes", as the reference writes sizes. */
        fun sizeText(bytes: Long): String {
            var n = bytes.toDouble()
            for (unit in listOf("bytes", "KB", "MB", "GB")) {
                if (n < 1000 || unit == "GB") {
                    if (unit == "bytes") return "${n.toLong()} $unit"
                    return String.format(java.util.Locale.ROOT, "%.1f %s", n, unit).replace(".0 ", " ")
                }
                n /= 1024
            }
            return ""
        }

        /** "12 s left", "3 min left", "1 h 5 min left". */
        fun etaText(seconds: Long?): String {
            if (seconds == null || seconds < 0) return ""
            val s = seconds
            if (s < 60) return "${maxOf(s, 1)} s left"
            if (s < 3600) return "${(s + 30) / 60} min left"
            return "${s / 3600} h ${(s % 3600) / 60} min left"
        }

        /** One transfer, in a line: "45% · 12 s left · 3.2 MB/s", or how it ended. */
        fun progressText(t: View): String = when (t.state) {
            DONE -> if (t.dir == "out") "Sent" else "Received"
            CANCELLED -> if (!t.error.isNullOrEmpty() && t.error != CANCELLED_HERE) "Cancelled: ${t.error}" else "Cancelled"
            FAILED -> "Failed: ${t.error?.ifEmpty { null } ?: "it stopped"}"
            WAITING -> "Stopped at ${t.percent}%: it carries on when it can"
            else -> if (t.route == "hub" && t.done == 0L) "Sending through the hub…" else {
                val bits = mutableListOf("${t.percent}%")
                if (t.eta != null) bits += etaText(t.eta)
                if (t.rate != null) bits += "${sizeText(t.rate)}/s" else if (t.done == 0L) bits += "starting…"
                bits.joinToString(" · ")
            }
        }
    }
}
