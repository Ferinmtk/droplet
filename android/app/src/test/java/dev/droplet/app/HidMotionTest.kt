package dev.droplet.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Mouse and keyboard packing: carry, splitting, clamping, buttons, wheel and pan, typing. */
class HidMotionTest {

    /** Reports as sent: (report id, bytes). */
    private val sent = mutableListOf<Pair<Int, ByteArray>>()
    private val out: (Int, ByteArray) -> Boolean = { id, r -> sent += id to r; true }

    private fun mouseReports() = sent.filter { it.first == HidReports.ID_MOUSE }.map { it.second }
    private fun sum(axis: Int) = mouseReports().sumOf { it[axis].toInt() }

    // --- carry and split ---------------------------------------------------------------

    @Test
    fun carryKeepsTheFraction() {
        val c = Carry()
        assertEquals(0, c.add(0.4f))
        assertEquals(0, c.add(0.4f))
        assertEquals(1, c.add(0.4f))   // 1.2
        assertEquals(0, c.add(0.7f))   // 0.9
        assertEquals(1, c.add(0.1f))   // 1.0
        // negative moves are rounded towards zero too, and carried the same way
        val n = Carry()
        assertEquals(0, n.add(-0.6f))
        assertEquals(-1, n.add(-0.6f))
        assertEquals(0, n.add(-0.1f))  // -0.3
        // a hundred tiny moves add up to what they are
        val t = Carry()
        var total = 0
        repeat(100) { total += t.add(0.25f) }
        assertEquals(25, total)
    }

    @Test
    fun carryIgnoresNonsenseAndCaps() {
        val c = Carry()
        assertEquals(0, c.add(Float.NaN))
        assertEquals(0, c.add(Float.POSITIVE_INFINITY))
        assertEquals(Carry.MAX_WHOLE.toInt(), c.add(1e9f))
        // nothing built up behind the cap
        assertEquals(0, c.add(0f))
        c.reset()
        assertEquals(0, c.add(0.9f))
    }

    @Test
    fun splitStaysInRangeAndAddsUp() {
        for ((dx, dy) in listOf(0 to 0, 1 to 0, 127 to -127, 128 to 0, 300 to -50, -1000 to 999, 5 to 4000, -254 to 254)) {
            val steps = Split.move(dx, dy)
            assertEquals("$dx,$dy", dx, steps.sumOf { it[0] })
            assertEquals("$dx,$dy", dy, steps.sumOf { it[1] })
            for (s in steps) assertTrue("$dx,$dy", abs(s[0]) <= 127 && abs(s[1]) <= 127)
            // as few reports as the range allows
            val need = maxOf((abs(dx) + 126) / 127, (abs(dy) + 126) / 127)
            assertEquals("$dx,$dy", need, steps.size)
        }
        assertEquals(emptyList<Int>(), Split.axis(0))
        assertEquals(listOf(-100), Split.axis(-100))
        assertEquals(listOf(100, 100, 100), Split.axis(300))
    }

    @Test
    fun splitIsEven() {
        // a straight line stays straight: every step in the same proportion, within one count
        val steps = Split.move(400, 100)
        assertEquals(4, steps.size)
        for (s in steps) {
            assertEquals(100, s[0])
            assertEquals(25, s[1])
        }
    }

    // --- the mouse -------------------------------------------------------------------------

    @Test
    fun smallMovesCarry() {
        val m = HidMouse(out)
        m.move(0.5f, -0.5f)
        assertTrue(mouseReports().isEmpty())   // nothing whole yet: nothing sent
        m.move(0.5f, -0.5f)
        assertEquals(1, mouseReports().size)
        assertArrayEquals(HidReports.mouse(0, 1, -1), mouseReports()[0])
    }

    @Test
    fun bigMovesSplitIntoSeveralReports() {
        val m = HidMouse(out)
        m.move(1000.6f, -300.2f)
        val r = mouseReports()
        assertEquals(8, r.size)
        assertEquals(1000, sum(1))
        assertEquals(-300, sum(2))
        for (x in r) {
            assertTrue(abs(x[1].toInt()) <= 127)
            assertTrue(abs(x[2].toInt()) <= 127)
            assertEquals(0, x[3].toInt())
            assertEquals(0, x[4].toInt())
        }
        // the fractions that were left go out with the next move
        sent.clear()
        m.move(0.5f, -0.9f)
        assertEquals(1, sum(1))
        assertEquals(-1, sum(2))
    }

    @Test
    fun buttonsAreABitmask() {
        val m = HidMouse(out)
        m.button(HidReports.BUTTON_LEFT, true)
        m.button(HidReports.BUTTON_RIGHT, true)
        m.button(HidReports.BUTTON_RIGHT, true)   // already down: no report
        m.move(10f, 0f)                            // a drag carries the held buttons
        m.button(HidReports.BUTTON_LEFT, false)
        m.button(HidReports.BUTTON_RIGHT, false)
        assertEquals(listOf(0x01, 0x03, 0x03, 0x02, 0x00), mouseReports().map { it[0].toInt() })
        assertEquals(0, m.buttons)
    }

    @Test
    fun clicksPressAndRelease() {
        val m = HidMouse(out)
        m.click(HidReports.BUTTON_LEFT, 2)
        assertEquals(listOf(1, 0, 1, 0), mouseReports().map { it[0].toInt() })
        sent.clear()
        // a right click during a left drag keeps left held
        m.button(HidReports.BUTTON_LEFT, true)
        m.click(HidReports.BUTTON_RIGHT)
        assertEquals(listOf(1, 3, 1), mouseReports().map { it[0].toInt() })
    }

    @Test
    fun resetLetsGo() {
        val m = HidMouse(out)
        m.button(HidReports.BUTTON_LEFT, true)
        m.move(0.7f, 0f)
        sent.clear()
        m.reset()
        assertEquals(listOf(0), mouseReports().map { it[0].toInt() })
        sent.clear()
        m.move(0.7f, 0f)      // the 0.7 from before the reset is gone
        assertTrue(mouseReports().isEmpty())
        m.reset()             // nothing held: nothing sent
        assertTrue(mouseReports().isEmpty())
    }

    @Test
    fun wheelAndPanAccumulate() {
        val m = HidMouse(out)
        repeat(3) { m.scroll(0.4f, 0f) }
        assertEquals(1, sum(3))            // 1.2 steps: one sent, 0.2 kept
        repeat(2) { m.scroll(0.4f, 0f) }
        assertEquals(2, sum(3))            // 2.0
        sent.clear()
        val n = HidMouse(out)
        n.scroll(-0.5f, -0.5f)
        n.scroll(-0.5f, -0.5f)
        assertEquals(-1, sum(3))
        assertEquals(-1, sum(4))
        assertEquals(0, sum(1))
        assertEquals(0, sum(2))
        sent.clear()
        // a fling's worth: split, all of it arrives
        n.scroll(300f, -10f)
        assertEquals(300, sum(3))
        assertEquals(-10, sum(4))
        assertEquals(3, mouseReports().size)
        assertTrue(mouseReports().all { abs(it[3].toInt()) <= 127 })
    }

    // --- the keyboard ---------------------------------------------------------------------

    @Test
    fun typingPressesAndReleasesEachKey() {
        val k = HidKeyboard(out)
        val skipped = k.type("Hi!\n")
        assertEquals("", skipped)
        val r = sent.map { it.second.toList() }
        assertTrue(sent.all { it.first == HidReports.ID_KEYBOARD })
        assertEquals(
            listOf(
                HidReports.keyboard(HidKeys.MOD_SHIFT, 0x0B), HidReports.keyboard(0),   // H
                HidReports.keyboard(0, 0x0C), HidReports.keyboard(0),                  // i
                HidReports.keyboard(HidKeys.MOD_SHIFT, 0x1E), HidReports.keyboard(0),  // !
                HidReports.keyboard(0, 0x28), HidReports.keyboard(0),                  // Enter
            ).map { it.toList() },
            r,
        )
    }

    @Test
    fun typingSkipsWhatHasNoKey() {
        val k = HidKeyboard(out)
        assertEquals("é👋", k.type("café 👋"))
        // c, a, f, space: four presses and four releases, nothing for é or the emoji
        assertEquals(8, sent.size)
        assertEquals(listOf(0x06, 0, 0x04, 0, 0x09, 0, 0x2C, 0), sent.map { it.second[2].toInt() })
    }

    @Test
    fun windowsLineEndsAreOneEnter() {
        val k = HidKeyboard(out)
        k.type("a\r\nb\rc")
        assertEquals(listOf(0x04, 0x28, 0x05, 0x28, 0x06), sent.map { it.second[2].toInt() }.filter { it != 0 })
    }

    @Test
    fun stickyModifiersJoinEveryKey() {
        val k = HidKeyboard(out)
        k.type("C", HidKeys.MOD_CTRL)
        assertArrayEquals(HidReports.keyboard(HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x06), sent[0].second)
        sent.clear()
        k.key("Tab", HidKeys.MOD_ALT)
        assertArrayEquals(HidReports.keyboard(HidKeys.MOD_ALT, 0x2B), sent[0].second)
        assertArrayEquals(HidReports.keyboard(0), sent[1].second)
        sent.clear()
        // the Windows key on its own: modifiers only, no key
        k.modifiers(HidKeys.MOD_META)
        assertArrayEquals(byteArrayOf(0x08, 0, 0, 0, 0, 0, 0, 0), sent[0].second)
        assertArrayEquals(ByteArray(8), sent[1].second)
        sent.clear()
        assertTrue(!k.key("NoSuchKey"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun consumerKeysPressAndRelease() {
        val k = HidKeyboard(out)
        k.consumer(HidKeys.CONSUMER.getValue("BrowserHome"))
        assertEquals(listOf(HidReports.ID_CONSUMER, HidReports.ID_CONSUMER), sent.map { it.first })
        assertArrayEquals(byteArrayOf(0x23, 0x02), sent[0].second)
        assertArrayEquals(byteArrayOf(0, 0), sent[1].second)
    }

    @Test
    fun accelerationCurve() {
        // slow and careful: about 1:1
        val slow = Accel.gain(1f, 0f, 16)
        assertTrue(slow in 0.85f..1.1f)
        // a quick swipe travels much further
        val fast = Accel.gain(40f, 0f, 16)
        assertTrue(fast > 3f && fast <= 3.45f)
        // speed scales it, and no movement is no gain
        assertEquals(2 * slow, Accel.gain(1f, 0f, 16, speed = 2f), 1e-6f)
        assertEquals(0f, Accel.gain(0f, 0f, 16))
        // events closer than a 120 Hz frame count as one frame apart
        assertEquals(Accel.gain(8f, 0f, 8), Accel.gain(8f, 0f, 1))
    }
}
