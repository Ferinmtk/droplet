package dev.droplet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The report descriptor, read back the way a host reads it: a small HID
 * descriptor parser (short items only, as the HID 1.11 spec §6.2.2 has them)
 * checks it's well formed, and then decodes what the packers produce using
 * nothing but the descriptor. If the two ever disagree, a host would read
 * garbage; this catches it.
 */
class HidReportsTest {

    // --- a minimal HID report descriptor parser ---------------------------------------------

    /** One Input/Output field: [count] items of [size] bits at [offset] within report [reportId]. */
    data class Field(
        val reportId: Int,
        val kind: Int,          // 8 Input, 9 Output, 0xB Feature
        val flags: Int,
        val offset: Int,
        val size: Int,
        val count: Int,
        val usages: List<Int>,  // page << 16 | id, expanded from min..max
        val logMin: Int,
        val logMax: Int,
    ) {
        val constant get() = flags and 0x01 != 0
        val variable get() = flags and 0x02 != 0
        val relative get() = flags and 0x04 != 0
    }

    data class Collection(val type: Int, val usage: Int, val depth: Int, val reportIds: MutableSet<Int> = mutableSetOf())

    class Parsed(val fields: List<Field>, val collections: List<Collection>) {
        fun bits(reportId: Int, kind: Int) = fields.filter { it.reportId == reportId && it.kind == kind }.sumOf { it.size * it.count }
        fun reportIds() = fields.map { it.reportId }.toSet()
    }

    private fun parse(d: ByteArray): Parsed {
        val fields = mutableListOf<Field>()
        val collections = mutableListOf<Collection>()
        val open = ArrayDeque<Collection>()
        val offsets = HashMap<Pair<Int, Int>, Int>()
        var page = 0
        var logMin = 0
        var logMax = 0
        var size = 0
        var count = 0
        var reportId = 0
        var usages = mutableListOf<Int>()
        var usageMin: Int? = null
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            if (prefix == 0xFE) fail("long items aren't used")
            val len = (prefix and 3).let { if (it == 3) 4 else it }
            val type = prefix shr 2 and 3
            val tag = prefix shr 4
            if (i + 1 + len > d.size) fail("item at $i runs past the end")
            var raw = 0L
            for (k in 0 until len) raw = raw or ((d[i + 1 + k].toLong() and 0xFF) shl (8 * k))
            val signed = when (len) {
                1 -> raw.toByte().toInt()
                2 -> raw.toShort().toInt()
                4 -> raw.toInt()
                else -> 0
            }
            val unsigned = raw.toInt()
            when (type) {
                0 -> { // main
                    when (tag) {
                        0x8, 0x9, 0xB -> {
                            assertTrue("a field outside any collection at $i", open.isNotEmpty())
                            assertTrue("report size 0 at $i", size > 0)
                            val key = reportId to tag
                            val off = offsets.getOrDefault(key, 0)
                            fields += Field(reportId, tag, unsigned, off, size, count, usages.toList(), logMin, logMax)
                            offsets[key] = off + size * count
                            open.forEach { it.reportIds += reportId }
                        }
                        0xA -> {
                            val c = Collection(unsigned, usages.firstOrNull() ?: -1, open.size)
                            collections += c
                            open.addLast(c)
                        }
                        0xC -> {
                            assertTrue("End Collection without a collection at $i", open.isNotEmpty())
                            open.removeLast()
                        }
                        else -> fail("unknown main item $tag at $i")
                    }
                    usages = mutableListOf()
                    usageMin = null
                }
                1 -> when (tag) { // global
                    0x0 -> page = unsigned
                    0x1 -> logMin = signed
                    0x2 -> logMax = if (logMin < 0) signed else unsigned
                    0x7 -> size = unsigned
                    0x8 -> {
                        assertTrue("report id 0 is reserved", unsigned in 1..255)
                        reportId = unsigned
                    }
                    0x9 -> count = unsigned
                    else -> fail("unexpected global item $tag at $i")
                }
                2 -> when (tag) { // local
                    0x0 -> usages += if (len == 4) unsigned else (page shl 16) or unsigned
                    0x1 -> usageMin = unsigned
                    0x2 -> {
                        val lo = checkNotNull(usageMin) { "Usage Maximum without Minimum at $i" }
                        assertTrue("usage range upside down at $i", unsigned >= lo)
                        for (u in lo..unsigned) usages += (page shl 16) or u
                    }
                    else -> fail("unexpected local item $tag at $i")
                }
                else -> fail("reserved item type at $i")
            }
            i += 1 + len
        }
        assertTrue("unbalanced collections: ${open.size} left open", open.isEmpty())
        return Parsed(fields, collections)
    }

    /** Decodes [report] (without its id byte) with the parsed layout: usage → value, and the array fields' contents. */
    private fun decode(p: Parsed, id: Int, report: ByteArray): Pair<Map<Int, Int>, List<Int>> {
        val values = HashMap<Int, Int>()
        val array = mutableListOf<Int>()
        for (f in p.fields.filter { it.reportId == id && it.kind == 0x8 && !it.constant }) {
            for (k in 0 until f.count) {
                var v = 0L
                for (bit in 0 until f.size) {
                    val at = f.offset + k * f.size + bit
                    if (report[at / 8].toInt() shr (at % 8) and 1 == 1) v = v or (1L shl bit)
                }
                var value = v.toInt()
                if (f.logMin < 0 && v and (1L shl (f.size - 1)) != 0L) value = (v - (1L shl f.size)).toInt()
                if (f.variable) values[f.usages.getOrElse(k) { f.usages.last() }] = value else array += value
            }
        }
        return values to array
    }

    private val parsed by lazy { parse(HidReports.DESCRIPTOR) }

    private fun usage(page: Int, id: Int) = (page shl 16) or id

    // --- structure --------------------------------------------------------------------

    @Test
    fun collectionsAreBalancedAndNamed() {
        val apps = parsed.collections.filter { it.depth == 0 }
        assertEquals(listOf(1, 1, 1), apps.map { it.type })  // Application
        assertEquals(listOf(usage(0x01, 0x06), usage(0x01, 0x02), usage(0x0C, 0x01)), apps.map { it.usage })
        assertEquals(listOf(setOf(1), setOf(2), setOf(3)), apps.map { it.reportIds.toSet() })
        // the mouse's pointer is a physical collection inside it
        val inner = parsed.collections.single { it.depth == 1 }
        assertEquals(0, inner.type)
        assertEquals(usage(0x01, 0x01), inner.usage)
    }

    @Test
    fun reportSizesMatchThePackers() {
        assertEquals(setOf(1, 2, 3), parsed.reportIds())
        assertEquals(HidReports.KEYBOARD_SIZE * 8, parsed.bits(HidReports.ID_KEYBOARD, 0x8))
        assertEquals(HidReports.MOUSE_SIZE * 8, parsed.bits(HidReports.ID_MOUSE, 0x8))
        assertEquals(HidReports.CONSUMER_SIZE * 8, parsed.bits(HidReports.ID_CONSUMER, 0x8))
        assertEquals(HidReports.KEYBOARD_OUTPUT_SIZE * 8, parsed.bits(HidReports.ID_KEYBOARD, 0x9))
        assertEquals(0, parsed.bits(HidReports.ID_MOUSE, 0x9))
        assertEquals(0, parsed.bits(HidReports.ID_CONSUMER, 0x9))
        assertTrue(parsed.fields.none { it.kind == 0xB })
        assertEquals(HidReports.KEYBOARD_SIZE, HidReports.keyboard(0).size)
        assertEquals(HidReports.MOUSE_SIZE, HidReports.mouse(0).size)
        assertEquals(HidReports.CONSUMER_SIZE, HidReports.consumer(0).size)
        for (id in 1..3) assertEquals(parsed.bits(id, 0x8) / 8, HidReports.inputSize(id))
        assertEquals(-1, HidReports.inputSize(4))
    }

    @Test
    fun bootLayoutsComeFirst() {
        // boot keyboard: modifiers byte, reserved byte, six key bytes
        val kb = parsed.fields.filter { it.reportId == 1 && it.kind == 0x8 }
        assertEquals(listOf(0, 8, 16), kb.map { it.offset })
        assertEquals((0xE0..0xE7).map { usage(0x07, it) }, kb[0].usages)
        assertTrue(kb[1].constant)
        assertEquals(6, kb[2].count)
        assertTrue(!kb[2].variable)
        // boot mouse: buttons byte, then X and Y as signed bytes
        val m = parsed.fields.filter { it.reportId == 2 && it.kind == 0x8 && !it.constant }
        assertEquals(0, m[0].offset)
        assertEquals((1..5).map { usage(0x09, it) }, m[0].usages)
        assertEquals(8, m[1].offset)
        assertEquals(listOf(usage(0x01, 0x30), usage(0x01, 0x31), usage(0x01, 0x38)), m[1].usages)
    }

    @Test
    fun rangesMatchWhatIsSent() {
        val m = parsed.fields.filter { it.reportId == 2 && it.kind == 0x8 && it.relative }
        assertEquals(2, m.size)
        for (f in m) {
            assertEquals(-HidReports.AXIS_MAX, f.logMin)
            assertEquals(HidReports.AXIS_MAX, f.logMax)
            assertEquals(8, f.size)
        }
        assertEquals(listOf(usage(0x0C, 0x238)), m[1].usages)
        val keys = parsed.fields.single { it.reportId == 1 && it.kind == 0x8 && !it.variable && !it.constant }
        val consumer = parsed.fields.single { it.reportId == 3 }
        assertEquals(HidReports.CONSUMER_MAX, consumer.logMax)
        assertEquals(0, consumer.logMin)
        // every key and consumer usage the app sends is inside the declared ranges
        val named = listOf("Enter", "Escape", "Backspace", "Tab", "Space", "Delete", "Insert", "Home", "End", "PageUp",
            "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "PrintScreen", "ContextMenu") + (1..12).map { "F$it" }
        for (n in named) assertTrue(n, HidKeys.named(n)!! in keys.logMin..keys.logMax)
        for (c in 0x20..0x7E) HidKeys.forChar(c.toChar())?.let { assertTrue("$c", it.usage in keys.logMin..keys.logMax) }
        for ((n, u) in HidKeys.CONSUMER) assertTrue(n, u in 1..consumer.logMax)
    }

    // --- the packers, decoded with the descriptor ----------------------------------------------

    @Test
    fun keyboardReportDecodes() {
        val (v, keys) = decode(parsed, 1, HidReports.keyboard(HidKeys.MOD_CTRL or HidKeys.MOD_SHIFT, 0x04, 0x05))
        assertEquals(1, v[usage(0x07, 0xE0)])  // Left Control
        assertEquals(1, v[usage(0x07, 0xE1)])  // Left Shift
        assertEquals(0, v[usage(0x07, 0xE2)])  // Left Alt
        assertEquals(0, v[usage(0x07, 0xE3)])  // Left GUI
        assertEquals(listOf(4, 5, 0, 0, 0, 0), keys)

        val (v2, _) = decode(parsed, 1, HidReports.keyboard(HidKeys.MOD_ALT or HidKeys.MOD_META))
        assertEquals(1, v2[usage(0x07, 0xE2)])
        assertEquals(1, v2[usage(0x07, 0xE3)])

        // seven keys: every slot says ErrorRollOver
        assertEquals(List(6) { 1 }, decode(parsed, 1, HidReports.keyboard(0, 4, 5, 6, 7, 8, 9, 10)).second)
        // zeros are "no key", not keys
        assertEquals(listOf(4, 0, 0, 0, 0, 0), decode(parsed, 1, HidReports.keyboard(0, 0, 4)).second)
    }

    @Test
    fun mouseReportDecodes() {
        val r = HidReports.mouse(HidReports.BUTTON_LEFT or HidReports.BUTTON_FORWARD, 100, -127, 3, -2)
        val (v, _) = decode(parsed, 2, r)
        assertEquals(1, v[usage(0x09, 1)])
        assertEquals(0, v[usage(0x09, 2)])
        assertEquals(0, v[usage(0x09, 3)])
        assertEquals(0, v[usage(0x09, 4)])
        assertEquals(1, v[usage(0x09, 5)])
        assertEquals(100, v[usage(0x01, 0x30)])
        assertEquals(-127, v[usage(0x01, 0x31)])
        assertEquals(3, v[usage(0x01, 0x38)])
        assertEquals(-2, v[usage(0x0C, 0x238)])
        // out of range is clamped, never wrapped
        val (c, _) = decode(parsed, 2, HidReports.mouse(0, 500, -500, 128, -128))
        assertEquals(127, c[usage(0x01, 0x30)])
        assertEquals(-127, c[usage(0x01, 0x31)])
        assertEquals(127, c[usage(0x01, 0x38)])
        assertEquals(-127, c[usage(0x0C, 0x238)])
        // right and middle are buttons 2 and 3
        val (b, _) = decode(parsed, 2, HidReports.mouse(HidReports.BUTTON_RIGHT or HidReports.BUTTON_MIDDLE))
        assertEquals(listOf(0, 1, 1, 0, 0), (1..5).map { b[usage(0x09, it)] })
    }

    @Test
    fun consumerReportDecodes() {
        for (u in HidKeys.CONSUMER.values + 0) {
            assertEquals(listOf(u), decode(parsed, 3, HidReports.consumer(u)).second)
        }
        try {
            HidReports.consumer(0x400)
            fail("0x400 is past the consumer report's range")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
