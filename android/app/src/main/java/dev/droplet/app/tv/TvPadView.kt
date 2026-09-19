package dev.droplet.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import dev.droplet.app.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The touchpad: swipes become D-pad presses (one per [STEP_DP] of travel,
 * so a long swipe moves several places), a tap is OK, and holding still is
 * a long OK. As the web remote's touchpad.
 */
class TvPadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Listener {
        /** One step of a swipe; [repeat] for the second and later steps of the same swipe. */
        fun step(key: String, repeat: Boolean)
        fun tap()
        fun holdStart()
        fun holdEnd()
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_card) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = color(R.color.r_line_2) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_accent_soft) }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = color(R.color.r_accent) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 13 * resources.displayMetrics.scaledDensity; color = color(R.color.r_dim)
    }
    private val rect = RectF()
    private val path = Path()
    private val hintText = context.getString(R.string.tv_pad_hint)

    // the gesture in progress
    private var active = false
    private var x0 = 0f
    private var y0 = 0f
    private var ax = 0f
    private var ay = 0f
    private var fx = 0f
    private var fy = 0f
    private var t0 = 0L
    private var moved = false
    private var held = false
    private var steps = 0
    /** Which arrow flashes, and until when. */
    private var flash: String? = null
    private var flashUntil = 0L

    private val holdCheck = Runnable {
        if (active && !moved) {
            held = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.holdStart()
            invalidate()
        }
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.tv_pad_desc)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(dp / 2, dp / 2, width - dp / 2, height - dp / 2)
        canvas.drawRoundRect(rect, 28 * dp, 28 * dp, card)
        canvas.drawRoundRect(rect, 28 * dp, 28 * dp, edge)
        val now = SystemClock.uptimeMillis()
        val lit = flash.takeIf { now < flashUntil }
        val m = 26 * dp
        val s = 10 * dp
        chevron(canvas, width / 2f, m, 0f, -1f, s, lit == "DPAD_UP")
        chevron(canvas, width - m, height / 2f, 1f, 0f, s, lit == "DPAD_RIGHT")
        chevron(canvas, width / 2f, height - m, 0f, 1f, s, lit == "DPAD_DOWN")
        chevron(canvas, m, height / 2f, -1f, 0f, s, lit == "DPAD_LEFT")
        if (!active) canvas.drawText(hintText, width / 2f, height / 2f + hint.textSize / 3, hint)
        if (active) {
            val r = if (held) 30 * dp else 22 * dp
            canvas.drawCircle(fx, fy, r, dot)
            canvas.drawCircle(fx, fy, r, dotEdge)
        }
        if (lit != null) postInvalidateDelayed(max(0L, flashUntil - now))
    }

    private fun chevron(canvas: Canvas, x: Float, y: Float, ux: Float, uy: Float, s: Float, on: Boolean) {
        arrow.color = color(if (on) R.color.r_accent else R.color.r_line_2)
        val px = -uy
        val py = ux
        path.reset()
        path.moveTo(x - ux * s * 0.5f + px * s, y - uy * s * 0.5f + py * s)
        path.lineTo(x + ux * s * 0.5f, y + uy * s * 0.5f)
        path.lineTo(x - ux * s * 0.5f - px * s, y - uy * s * 0.5f - py * s)
        canvas.drawPath(path, arrow)
    }

    @SuppressLint("ClickableViewAccessibility")  // a tap also comes through performClick, for TalkBack
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = true
                x0 = e.x; y0 = e.y; ax = e.x; ay = e.y; fx = e.x; fy = e.y
                t0 = SystemClock.uptimeMillis()
                moved = false; held = false; steps = 0
                postDelayed(holdCheck, HOLD_MS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                fx = e.x; fy = e.y
                if (!moved && hypot(e.x - x0, e.y - y0) > SLOP_DP * dp) {
                    moved = true
                    removeCallbacks(holdCheck)
                }
                if (!held) {
                    val dx = e.x - ax
                    val dy = e.y - ay
                    if (max(abs(dx), abs(dy)) >= STEP_DP * dp) {
                        val key = if (abs(dx) > abs(dy)) (if (dx > 0) "DPAD_RIGHT" else "DPAD_LEFT")
                        else (if (dy > 0) "DPAD_DOWN" else "DPAD_UP")
                        listener?.step(key, steps > 0)
                        steps++
                        flash = key
                        flashUntil = SystemClock.uptimeMillis() + 120
                        ax = e.x; ay = e.y
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return true
                removeCallbacks(holdCheck)
                active = false
                if (held) listener?.holdEnd()
                else if (e.actionMasked == MotionEvent.ACTION_UP && !moved && SystemClock.uptimeMillis() - t0 < HOLD_MS) performClick()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.tap()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }

    companion object {
        const val STEP_DP = 34f
        const val SLOP_DP = 10f
        const val HOLD_MS = 600L
    }
}
