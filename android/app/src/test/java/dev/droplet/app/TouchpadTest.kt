package dev.droplet.app

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The touchpad's gestures with synthetic touch events: taps, two- and
 * three-finger taps, double-tap, double-tap-and-hold drag, moving, and
 * two-finger scrolling. Time is Robolectric's, moved on by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TouchpadTest {
    private val got = mutableListOf<String>()
    private var moveX = 0f
    private var moveY = 0f
    private var wheel = 0f
    private var pan = 0f

    private val sink = object : TouchpadGestures.Sink {
        override fun move(dx: Float, dy: Float) {
            moveX += dx
            moveY += dy
            if (got.lastOrNull() != "move") got += "move"
        }

        override fun button(button: Int, down: Boolean) {
            got += "${name(button)} ${if (down) "down" else "up"}"
        }

        override fun click(button: Int, times: Int) {
            got += "click ${name(button)} x$times"
        }

        override fun scroll(vertical: Float, horizontal: Float) {
            wheel += vertical
            pan += horizontal
            if (got.lastOrNull() != "scroll") got += "scroll"
        }
    }

    private fun name(b: Int) = when (b) {
        HidReports.BUTTON_LEFT -> "left"
        HidReports.BUTTON_RIGHT -> "right"
        HidReports.BUTTON_MIDDLE -> "middle"
        else -> "?"
    }

    private val pad = TouchpadGestures(1f, sink)
    private var downTime = 0L

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Sends one event with the fingers at [at]; [index] is the finger going down or up for POINTER_* actions. */
    private fun send(action: Int, vararg at: Pair<Float, Float>, index: Int = 0) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = Array(at.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(at.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = at[i].first
                y = at[i].second
                pressure = 1f
                size = 1f
            }
        }
        val a = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, now, a, at.size, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        pad.onTouch(e)
        e.recycle()
    }

    private fun tap(x: Float = 100f, y: Float = 100f, holdMs: Long = 60) {
        send(MotionEvent.ACTION_DOWN, x to y)
        advance(holdMs)
        send(MotionEvent.ACTION_UP, x to y)
    }

    @Test
    fun tapIsALeftClickOnceNoSecondTapCame() {
        tap()
        assertEquals(emptyList<String>(), got)   // waiting: it may be a double-tap
        advance(TouchpadGestures.DOUBLE_MS - 10)
        assertEquals(emptyList<String>(), got)
        advance(20)
        assertEquals(listOf("click left x1"), got)
    }

    @Test
    fun aTapMayWanderALittle() {
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(30)
        send(MotionEvent.ACTION_MOVE, 103f to 102f)
        advance(30)
        send(MotionEvent.ACTION_UP, 103f to 102f)
        advance(TouchpadGestures.DOUBLE_MS + 10)
        assertEquals(listOf("click left x1"), got)
    }

    @Test
    fun doubleTapIsADoubleClick() {
        tap()
        advance(100)
        tap()
        assertEquals(listOf("click left x2"), got)
        advance(1_000)
        assertEquals(listOf("click left x2"), got)
    }

    @Test
    fun doubleTapAndHoldDrags() {
        tap()
        advance(100)
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(TouchpadGestures.HOLD_MS + 10)
        assertEquals(listOf("left down"), got)
        assertTrue(pad.dragging)
        advance(16)
        send(MotionEvent.ACTION_MOVE, 140f to 100f)
        advance(16)
        send(MotionEvent.ACTION_MOVE, 180f to 110f)
        send(MotionEvent.ACTION_UP, 180f to 110f)
        assertEquals(listOf("left down", "move", "left up"), got)
        assertTrue(moveX > 80f)
        assertTrue(moveY > 0f)
    }

    @Test
    fun doubleTapAndSlideDragsAtOnce() {
        tap()
        advance(100)
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(16)
        send(MotionEvent.ACTION_MOVE, 130f to 100f)
        assertEquals(listOf("left down", "move"), got)
        send(MotionEvent.ACTION_UP, 130f to 100f)
        assertEquals(listOf("left down", "move", "left up"), got)
    }

    @Test
    fun slidingMovesWithoutClicking() {
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(16)
        send(MotionEvent.ACTION_MOVE, 103f to 100f)   // inside the slop: held back, not lost
        assertEquals(emptyList<String>(), got)
        advance(16)
        send(MotionEvent.ACTION_MOVE, 120f to 90f)
        advance(16)
        send(MotionEvent.ACTION_UP, 120f to 90f)
        advance(1_000)
        assertEquals(listOf("move"), got)
        // everything counted, with gain around 1 or more for this pace
        assertTrue("x $moveX", moveX >= 20f * 0.85f)
        assertTrue("y $moveY", moveY <= -10f * 0.85f)
    }

    @Test
    fun fasterMovesTravelFurther() {
        send(MotionEvent.ACTION_DOWN, 0f to 0f)
        var x = 0f
        repeat(10) {
            advance(16)
            x += 2f
            send(MotionEvent.ACTION_MOVE, x to 0f)
        }
        send(MotionEvent.ACTION_UP, x to 0f)
        val slow = moveX
        moveX = 0f
        advance(1_000)
        send(MotionEvent.ACTION_DOWN, 0f to 0f)
        x = 0f
        repeat(2) {
            advance(16)
            x += 10f
            send(MotionEvent.ACTION_MOVE, x to 0f)
        }
        send(MotionEvent.ACTION_UP, x to 0f)
        // the same 20 dp, faster: further
        assertTrue("slow $slow fast $moveX", moveX > slow * 1.5f)
    }

    @Test
    fun pressingAndHoldingStillIsNotAClick() {
        tap(holdMs = TouchpadGestures.TAP_MS + 50)
        advance(1_000)
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun twoFingerTapIsARightClick() {
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(20)
        send(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 160f to 100f, index = 1)
        advance(60)
        send(MotionEvent.ACTION_POINTER_UP, 100f to 100f, 160f to 100f, index = 1)
        send(MotionEvent.ACTION_UP, 100f to 100f)
        assertEquals(listOf("click right x1"), got)
        advance(1_000)
        assertEquals(listOf("click right x1"), got)
    }

    @Test
    fun threeFingerTapIsAMiddleClick() {
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        send(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 150f to 100f, index = 1)
        send(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 150f to 100f, 200f to 100f, index = 2)
        advance(80)
        send(MotionEvent.ACTION_POINTER_UP, 100f to 100f, 150f to 100f, 200f to 100f, index = 2)
        send(MotionEvent.ACTION_POINTER_UP, 100f to 100f, 150f to 100f, index = 1)
        send(MotionEvent.ACTION_UP, 100f to 100f)
        assertEquals(listOf("click middle x1"), got)
    }

    @Test
    fun twoFingersScrollNaturally() {
        send(MotionEvent.ACTION_DOWN, 100f to 300f)
        send(MotionEvent.ACTION_POINTER_DOWN, 100f to 300f, 160f to 300f, index = 1)
        // both fingers up the pad by 120 dp, in steps; content follows, so it scrolls down (negative wheel)
        var y = 300f
        repeat(6) {
            advance(16)
            y -= 20f
            send(MotionEvent.ACTION_MOVE, 100f to y, 160f to y)
        }
        send(MotionEvent.ACTION_POINTER_UP, 100f to y, 160f to y, index = 1)
        send(MotionEvent.ACTION_UP, 100f to y)
        advance(1_000)
        assertEquals(listOf("scroll"), got)
        // the first move already passes the slop, so all 120 dp count: 5 steps down
        assertEquals(-120f / TouchpadGestures.SCROLL_STEP_DP, wheel, 0.01f)
        assertEquals(0f, pan, 0.001f)
        assertEquals(0f, moveX, 0f)
    }

    @Test
    fun twoFingersSidewaysPan() {
        send(MotionEvent.ACTION_DOWN, 300f to 300f)
        send(MotionEvent.ACTION_POINTER_DOWN, 300f to 300f, 300f to 360f, index = 1)
        var x = 300f
        repeat(5) {
            advance(16)
            x -= 24f
            send(MotionEvent.ACTION_MOVE, x to 300f, x to 360f)
        }
        send(MotionEvent.ACTION_UP, x to 300f)
        // fingers left: the content goes left, so it pans right (positive)
        assertEquals(120f / TouchpadGestures.SCROLL_STEP_DP, pan, 0.01f)
        assertEquals(0f, wheel, 0.001f)
    }

    @Test
    fun cancellingADragLetsGo() {
        tap()
        advance(100)
        send(MotionEvent.ACTION_DOWN, 100f to 100f)
        advance(TouchpadGestures.HOLD_MS + 10)
        pad.cancel()
        assertEquals(listOf("left down", "left up"), got)
        // and a tap waiting for its second half is dropped, not clicked late
        got.clear()
        tap()
        pad.cancel()
        advance(1_000)
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun densityIsTakenIntoAccount() {
        // on a 3x screen, 21 px is 7 dp: still a tap
        val dense = TouchpadGestures(1f / 3f, sink)
        val now = SystemClock.uptimeMillis()
        fun e(action: Int, x: Float) = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, 100f, 0)
        dense.onTouch(e(MotionEvent.ACTION_DOWN, 100f))
        advance(20)
        dense.onTouch(e(MotionEvent.ACTION_MOVE, 118f))
        advance(20)
        dense.onTouch(e(MotionEvent.ACTION_UP, 118f))
        advance(TouchpadGestures.DOUBLE_MS + 10)
        assertEquals(listOf("click left x1"), got)
    }
}
