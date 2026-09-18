package dev.droplet.app

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.max

/**
 * The touchpad's gestures, as the web touchpad has them (static/remote.js):
 *
 * - one finger slides the pointer, with acceleration
 * - tap: left click; two-finger tap: right click; three-finger tap: middle
 * - double-tap: double click; double-tap and hold (or slide): drag
 * - two fingers sliding: scroll, in the natural direction (content follows the fingers)
 *
 * Distances are in dp, times from the events' own clock, so a phone with a
 * dense screen feels the same as one without.
 */
class TouchpadGestures(
    /** dp per screen pixel (1 / density). */
    private val dpPerPx: Float,
    private val sink: Sink,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    interface Sink {
        /** Moves the pointer by counts (already accelerated; fractions are the sink's to carry). */
        fun move(dx: Float, dy: Float)

        fun button(button: Int, down: Boolean)

        fun click(button: Int, times: Int)

        /** Wheel steps: [vertical] positive scrolls up, [horizontal] positive scrolls right (HID's convention). */
        fun scroll(vertical: Float, horizontal: Float)

        /** Something the user should feel or see. */
        fun feedback(what: Feedback) {}
    }

    enum class Feedback { TAP, RIGHT_CLICK, MIDDLE_CLICK, DRAG_START, DRAG_END, SCROLL_START }

    /** Pointer speed: 1 is normal. */
    var speed = 1f

    private enum class Mode { PENDING, MOVE, DRAG, MULTI, SCROLL }

    private class Pt(var x: Float, var y: Float, var t: Long)

    private class Gesture(val t0: Long) {
        var maxPts = 1
        var moved = 0f
        var mode = Mode.PENDING
        var heldX = 0f
        var heldY = 0f
        /** This touch came soon after a tap: a double click, or a drag if held or slid. */
        var second = false
    }

    private val pts = HashMap<Int, Pt>()
    private var g: Gesture? = null
    private var pendingTap = false

    // a lone tap waits a moment, as it may be the first half of a double-tap or of a drag
    private val tapTimer = Runnable {
        if (pendingTap) {
            pendingTap = false
            sink.click(HidReports.BUTTON_LEFT, 1)
        }
    }
    private val holdTimer = Runnable { startDrag() }

    val dragging: Boolean get() = g?.mode == Mode.DRAG

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_POINTER_UP -> pts.remove(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP -> {
                // the last finger is up, whatever came before
                pts.clear()
                end(e.eventTime, cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> {
                pts.clear()
                end(e.eventTime, cancelled = true)
            }
        }
        return true
    }

    /** Stops whatever is going on (the pad went away, or the connection did): a drag lets go, a waiting tap is dropped. */
    fun cancel() {
        handler.removeCallbacks(tapTimer)
        handler.removeCallbacks(holdTimer)
        pendingTap = false
        if (g?.mode == Mode.DRAG) {
            sink.button(HidReports.BUTTON_LEFT, false)
            sink.feedback(Feedback.DRAG_END)
        }
        g = null
        pts.clear()
    }

    private fun down(e: MotionEvent) {
        pts.clear()
        pts[e.getPointerId(0)] = Pt(e.getX(0), e.getY(0), e.eventTime)
        handler.removeCallbacks(holdTimer)
        val gest = Gesture(e.eventTime)
        g = gest
        if (pendingTap) {
            handler.removeCallbacks(tapTimer)
            pendingTap = false
            gest.second = true
            handler.postDelayed(holdTimer, HOLD_MS)
        }
    }

    private fun pointerDown(e: MotionEvent) {
        val i = e.actionIndex
        pts[e.getPointerId(i)] = Pt(e.getX(i), e.getY(i), e.eventTime)
        val gest = g ?: return
        gest.maxPts = max(gest.maxPts, pts.size)
        if (gest.mode == Mode.DRAG) return
        handler.removeCallbacks(holdTimer)
        if (gest.second) {
            // the earlier tap still counts
            gest.second = false
            sink.click(HidReports.BUTTON_LEFT, 1)
        }
        gest.mode = Mode.MULTI
    }

    private fun move(e: MotionEvent) {
        val gest = g ?: return
        var sumX = 0f
        var sumY = 0f
        var n = 0
        var dt = 0L
        for (i in 0 until e.pointerCount) {
            val p = pts[e.getPointerId(i)] ?: continue
            val dx = (e.getX(i) - p.x) * dpPerPx
            val dy = (e.getY(i) - p.y) * dpPerPx
            dt = e.eventTime - p.t
            p.x = e.getX(i)
            p.y = e.getY(i)
            p.t = e.eventTime
            gest.moved += hypot(dx, dy)
            sumX += dx
            sumY += dy
            n++
        }
        if (n == 0) return

        if (gest.mode == Mode.MULTI || gest.mode == Mode.SCROLL) {
            if (gest.mode == Mode.MULTI && gest.moved > SLOP_DP * 2 && pts.size >= 2) {
                gest.mode = Mode.SCROLL
                sink.feedback(Feedback.SCROLL_START)
            }
            if (gest.mode != Mode.SCROLL || pts.size < 2) return
            // the fingers' average movement; natural: the content follows them
            val cx = sumX / n
            val cy = sumY / n
            sink.scroll(vertical = cy / SCROLL_STEP_DP, horizontal = -cx / SCROLL_STEP_DP)
            return
        }
        if (pts.size != 1) return

        val gain = Accel.gain(sumX, sumY, dt, speed)
        val ax = sumX * gain
        val ay = sumY * gain
        when (gest.mode) {
            Mode.PENDING -> {
                gest.heldX += ax
                gest.heldY += ay
                if (gest.moved <= SLOP_DP) return
                if (gest.second) startDrag() else gest.mode = Mode.MOVE
                // what moved inside the slop isn't lost
                sink.move(gest.heldX, gest.heldY)
            }
            Mode.MOVE, Mode.DRAG -> sink.move(ax, ay)
            else -> Unit
        }
    }

    private fun startDrag() {
        val gest = g ?: return
        if (gest.mode == Mode.DRAG) return
        handler.removeCallbacks(holdTimer)
        gest.mode = Mode.DRAG
        sink.button(HidReports.BUTTON_LEFT, true)
        sink.feedback(Feedback.DRAG_START)
    }

    private fun end(time: Long, cancelled: Boolean) {
        val gest = g ?: return
        g = null
        handler.removeCallbacks(holdTimer)
        if (gest.mode == Mode.DRAG) {
            sink.button(HidReports.BUTTON_LEFT, false)
            sink.feedback(Feedback.DRAG_END)
            return
        }
        if (cancelled) {
            if (gest.second) sink.click(HidReports.BUTTON_LEFT, 1)
            return
        }
        val tap = time - gest.t0 < TAP_MS && gest.moved < SLOP_DP * gest.maxPts
        if (tap && gest.maxPts == 1) {
            sink.feedback(Feedback.TAP)
            if (gest.second) {
                sink.click(HidReports.BUTTON_LEFT, 2)
                return
            }
            pendingTap = true
            handler.postDelayed(tapTimer, DOUBLE_MS)
            return
        }
        if (gest.second) sink.click(HidReports.BUTTON_LEFT, 1)
        if (tap && gest.mode == Mode.MULTI) {
            val right = gest.maxPts == 2
            sink.click(if (right) HidReports.BUTTON_RIGHT else HidReports.BUTTON_MIDDLE, 1)
            sink.feedback(if (right) Feedback.RIGHT_CLICK else Feedback.MIDDLE_CLICK)
        }
    }

    companion object {
        /** How far (dp) a finger may wander and still tap. */
        const val SLOP_DP = 7f
        /** The longest tap, ms. */
        const val TAP_MS = 260L
        /** A second touch this soon after a tap may become a double click or a drag. */
        const val DOUBLE_MS = 230L
        /** ...and holding it this long starts a drag. */
        const val HOLD_MS = 180L
        /** Finger travel (dp) per wheel step. */
        const val SCROLL_STEP_DP = 24f
    }
}

/**
 * The touchpad surface: every touch on it (and on its hint) goes to
 * [gestures], and the screen around it doesn't scroll or steal them.
 */
class TouchpadView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : android.widget.FrameLayout(context, attrs) {
    var gestures: TouchpadGestures? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = gestures != null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = gestures ?: return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        g.onTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    // accessibility: a tap on the pad is a click
    override fun performClick(): Boolean = super.performClick()
}
