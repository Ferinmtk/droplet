package dev.droplet.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.droplet.app.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The round D-pad: four arrow segments around an OK button, like the web
 * remote's. It only reports touches ([Listener]); what a touch sends (a
 * press, a repeat, a long press) is up to the screen. TalkBack sees five
 * buttons, and a keyboard's arrows and Enter work while it has focus.
 */
class DpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Zone(val key: String) { UP("DPAD_UP"), RIGHT("DPAD_RIGHT"), DOWN("DPAD_DOWN"), LEFT("DPAD_LEFT"), OK("DPAD_CENTER") }

    interface Listener {
        fun down(zone: Zone)
        /** [cancelled]: the finger slid off, or the gesture was taken over; no short press then. */
        fun up(zone: Zone, cancelled: Boolean)
    }

    var listener: Listener? = null
    private var pressed: Zone? = null

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val dp = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_card) }
    private val ringEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = color(R.color.r_line_2)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_accent_soft) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = color(R.color.r_text)
    }
    private val okFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_card_2) }
    private val okPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.r_accent) }
    private val okEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = color(R.color.r_accent)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 20 * resources.displayMetrics.scaledDensity; isFakeBoldText = true
        color = color(R.color.r_text)
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1 * dp; color = color(R.color.r_line) }
    private val oval = RectF()
    private val path = Path()

    private val helper = Access()

    init {
        isFocusable = true
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, helper)
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val outer get() = min(width, height) / 2f - 2 * dp
    private val inner get() = outer * 0.38f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // square, as wide as it may be
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    /** Which part of the pad is at (x, y), or null outside it. */
    fun zoneAt(x: Float, y: Float): Zone? {
        val dx = x - cx
        val dy = y - cy
        val d = hypot(dx, dy)
        if (d > outer + 8 * dp) return null
        if (d <= inner) return Zone.OK
        val deg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360   // 0 = right, 90 = down
        return when {
            deg >= 315 || deg < 45 -> Zone.RIGHT
            deg < 135 -> Zone.DOWN
            deg < 225 -> Zone.LEFT
            else -> Zone.UP
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = outer
        canvas.drawCircle(cx, cy, r, ring)
        // the pressed segment lights up
        pressed?.takeIf { it != Zone.OK }?.let { z ->
            val start = when (z) { Zone.RIGHT -> -45f; Zone.DOWN -> 45f; Zone.LEFT -> 135f; else -> 225f }
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, start, 90f, true, glow)
        }
        // the lines between segments, from the OK ring out
        for (a in listOf(45.0, 135.0, 225.0, 315.0)) {
            val rad = Math.toRadians(a)
            val c = Math.cos(rad).toFloat()
            val s = Math.sin(rad).toFloat()
            canvas.drawLine(cx + c * (inner + 10 * dp), cy + s * (inner + 10 * dp), cx + c * (r - 14 * dp), cy + s * (r - 14 * dp), divider)
        }
        canvas.drawCircle(cx, cy, r, ringEdge)
        // chevrons
        val mid = (inner + r) / 2
        val size = min(12 * dp, (r - inner) * 0.18f)
        chevron(canvas, cx, cy - mid, 0f, -1f, size)
        chevron(canvas, cx + mid, cy, 1f, 0f, size)
        chevron(canvas, cx, cy + mid, 0f, 1f, size)
        chevron(canvas, cx - mid, cy, -1f, 0f, size)
        // OK
        val ok = pressed == Zone.OK
        canvas.drawCircle(cx, cy, inner, if (ok) okPressed else okFill)
        canvas.drawCircle(cx, cy, inner, okEdge)
        label.color = color(if (ok) R.color.r_on_accent else R.color.r_text)
        val bounds = Rect()
        label.getTextBounds("OK", 0, 2, bounds)
        canvas.drawText("OK", cx, cy + bounds.height() / 2f, label)
        if (isFocused) canvas.drawCircle(cx, cy, r - 1.5f * dp, okEdge)
    }

    /** A chevron at (x, y) pointing along (ux, uy). */
    private fun chevron(canvas: Canvas, x: Float, y: Float, ux: Float, uy: Float, s: Float) {
        // perpendicular
        val px = -uy
        val py = ux
        path.reset()
        path.moveTo(x - ux * s * 0.5f + px * s, y - uy * s * 0.5f + py * s)
        path.lineTo(x + ux * s * 0.5f, y + uy * s * 0.5f)
        path.lineTo(x - ux * s * 0.5f - px * s, y - uy * s * 0.5f - py * s)
        canvas.drawPath(path, arrow)
    }

    @SuppressLint("ClickableViewAccessibility")  // TalkBack goes through the virtual buttons (Access)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val z = zoneAt(e.x, e.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                press(z)
            }
            MotionEvent.ACTION_MOVE -> {
                val z = pressed ?: return true
                // slid off onto another part, or off the pad: that press is abandoned
                if (zoneAt(e.x, e.y) != z) release(cancelled = true)
            }
            MotionEvent.ACTION_UP -> release(cancelled = false)
            MotionEvent.ACTION_CANCEL -> release(cancelled = true)
        }
        return true
    }

    private fun press(z: Zone) {
        pressed = z
        invalidate()
        listener?.down(z)
    }

    private fun release(cancelled: Boolean) {
        val z = pressed ?: return
        pressed = null
        invalidate()
        listener?.up(z, cancelled)
    }

    // --- keyboards (a Bluetooth keyboard, a D-pad on a Chromebook) ---

    private fun keyZone(keyCode: Int): Zone? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Zone.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Zone.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Zone.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Zone.RIGHT
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> Zone.OK
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val z = keyZone(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) press(z)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        keyZone(keyCode) ?: return super.onKeyUp(keyCode, event)
        release(cancelled = false)
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** Five virtual buttons for TalkBack; activating one is a short press. */
    private inner class Access : ExploreByTouchHelper(this) {
        private val names = mapOf(Zone.UP to R.string.tv_up, Zone.RIGHT to R.string.tv_right, Zone.DOWN to R.string.tv_down,
            Zone.LEFT to R.string.tv_left, Zone.OK to R.string.tv_ok)

        override fun getVirtualViewAt(x: Float, y: Float): Int = zoneAt(x, y)?.ordinal ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            Zone.entries.forEach { ids.add(it.ordinal) }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val z = Zone.entries[id]
            node.contentDescription = context.getString(names.getValue(z))
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.isClickable = true
            val r = outer
            val m = (inner + r) / 2
            val half = if (z == Zone.OK) inner else (r - inner) / 2
            val (x, y) = when (z) {
                Zone.UP -> cx to cy - m
                Zone.DOWN -> cx to cy + m
                Zone.LEFT -> cx - m to cy
                Zone.RIGHT -> cx + m to cy
                Zone.OK -> cx to cy
            }
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt()))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val z = Zone.entries[id]
            listener?.down(z)
            listener?.up(z, cancelled = false)
            return true
        }
    }
}
