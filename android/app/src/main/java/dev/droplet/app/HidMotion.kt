package dev.droplet.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns fractional movement into whole counts. What rounding holds back is
 * kept and added to the next movement, so slow, careful moves still get
 * somewhere and nothing is lost to rounding.
 */
class Carry {
    private var rest = 0.0

    /** Adds [v] and returns the whole part so far (towards zero); the fraction stays for next time. */
    fun add(v: Float): Int {
        if (v.isNaN() || v.isInfinite()) return 0
        rest += v
        val whole = rest.toLong().coerceIn(-MAX_WHOLE, MAX_WHOLE)
        rest -= whole
        // a runaway value can't build up a backlog: whatever is beyond the cap is dropped
        if (abs(rest) >= 1.0) rest %= 1.0
        return whole.toInt()
    }

    fun reset() {
        rest = 0.0
    }

    companion object {
        /** No single movement is worth more than this many counts (64 full reports). */
        const val MAX_WHOLE = 127L * 64
    }
}

/** Splitting relative moves into reports whose axes each fit ±[HidReports.AXIS_MAX]. */
object Split {
    /**
     * Splits the move ([dx], [dy]) into as few steps as keep both axes within
     * ±[limit], spread evenly, whose sums are exactly [dx] and [dy].
     */
    fun move(dx: Int, dy: Int, limit: Int = HidReports.AXIS_MAX): List<IntArray> {
        val n = max(parts(dx, limit), parts(dy, limit))
        if (n == 0) return emptyList()
        return List(n) { k -> intArrayOf(share(dx, k, n), share(dy, k, n)) }
    }

    /** Splits one axis ([v], e.g. wheel steps) the same way. */
    fun axis(v: Int, limit: Int = HidReports.AXIS_MAX): List<Int> {
        val n = parts(v, limit)
        return List(n) { k -> share(v, k, n) }
    }

    private fun parts(v: Int, limit: Int) = ((abs(v.toLong()) + limit - 1) / limit).toInt()

    // step k of n: the difference of two evenly spaced cumulative totals, so the steps add up exactly
    private fun share(v: Int, k: Int, n: Int): Int = (v.toLong() * (k + 1) / n - v.toLong() * k / n).toInt()
}

/**
 * Pointer acceleration, the web touchpad's curve (static/remote.js):
 * slow, small movements stay precise, quick swipes travel far.
 */
object Accel {
    /**
     * The gain for a finger moving [dx], [dy] density-independent pixels in
     * [dtMs] milliseconds, at the user's [speed] (1 = normal).
     */
    fun gain(dx: Float, dy: Float, dtMs: Long, speed: Float = 1f): Float {
        val dist = hypot(dx, dy)
        if (dist == 0f) return 0f
        // dp per ms; no two touch events are really closer than a 120 Hz frame
        val v = dist / max(dtMs, 8L)
        return speed * (0.85f + 2.6f * (1f - exp(-v / 0.7f)))
    }
}

/**
 * The mouse, as reports: buttons held, pointer moves with their fractions
 * carried, and wheel and pan steps. Everything goes out through [out].
 */
class HidMouse(private val out: (id: Int, report: ByteArray) -> Boolean) {
    private val x = Carry()
    private val y = Carry()
    private val wheel = Carry()
    private val pan = Carry()

    /** The buttons held now (HidReports.BUTTON_* bits). */
    var buttons = 0
        private set

    /** Moves the pointer by counts (fractions carry to the next move). */
    fun move(dx: Float, dy: Float): Boolean {
        val ix = x.add(dx)
        val iy = y.add(dy)
        var ok = true
        for (step in Split.move(ix, iy)) ok = send(dx = step[0], dy = step[1]) && ok
        return ok
    }

    /**
     * Scrolls by wheel steps: [vertical] positive scrolls up (away from you,
     * the HID convention), [horizontal] positive scrolls right.
     */
    fun scroll(vertical: Float, horizontal: Float): Boolean {
        val v = wheel.add(vertical)
        val h = pan.add(horizontal)
        val vs = Split.axis(v)
        val hs = Split.axis(h)
        var ok = true
        for (i in 0 until max(vs.size, hs.size)) {
            ok = send(wheel = vs.getOrElse(i) { 0 }, pan = hs.getOrElse(i) { 0 }) && ok
        }
        return ok
    }

    fun button(button: Int, down: Boolean): Boolean {
        val now = if (down) buttons or button else buttons and button.inv()
        if (now == buttons) return true
        buttons = now
        return send()
    }

    /** Clicks [button] [times] times; buttons held for a drag stay held. */
    fun click(button: Int, times: Int = 1): Boolean {
        var ok = true
        repeat(times) {
            ok = button(button, true) && ok
            ok = button(button, false) && ok
        }
        return ok
    }

    /** Lets go of everything and forgets any held-back fractions. */
    fun reset(): Boolean {
        x.reset(); y.reset(); wheel.reset(); pan.reset()
        if (buttons == 0) return true
        buttons = 0
        return send()
    }

    private fun send(dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0) =
        out(HidReports.ID_MOUSE, HidReports.mouse(buttons, dx, dy, wheel, pan))
}

/**
 * The keyboard and consumer keys, as reports. Every key is pressed and
 * released at once; a modifier-only press (Win on its own) works too.
 */
class HidKeyboard(private val out: (id: Int, report: ByteArray) -> Boolean) {

    /** Presses and releases the key [usage] with [mods] held. */
    fun tap(usage: Int, mods: Int = 0): Boolean {
        val down = out(HidReports.ID_KEYBOARD, HidReports.keyboard(mods, usage))
        val up = out(HidReports.ID_KEYBOARD, HidReports.keyboard(0))
        return down && up
    }

    /** Presses and releases a named key (docs/remote.md names); false if the name is unknown. */
    fun key(name: String, mods: Int = 0): Boolean {
        val usage = HidKeys.named(name) ?: return false
        return tap(usage, mods)
    }

    /** Presses and releases only modifiers, e.g. the Windows key on its own. */
    fun modifiers(mods: Int): Boolean = tap(0, mods)

    /**
     * Types [text] on a US layout, with [mods] (e.g. a sticky Ctrl) added to
     * every key. Returns the characters that have no key and were skipped.
     */
    fun type(text: String, mods: Int = 0): String {
        val skipped = StringBuilder()
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val stroke = if (Character.isBmpCodePoint(cp)) HidKeys.forChar(cp.toChar()) else null
            if (stroke == null) skipped.appendCodePoint(cp) else tap(stroke.usage, stroke.mods or mods)
            i += Character.charCount(cp)
        }
        return skipped.toString()
    }

    /** Presses and releases a consumer key (HidKeys.CONSUMER). */
    fun consumer(usage: Int): Boolean {
        val down = out(HidReports.ID_CONSUMER, HidReports.consumer(usage))
        val up = out(HidReports.ID_CONSUMER, HidReports.consumer(0))
        return down && up
    }
}
