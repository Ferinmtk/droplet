package dev.droplet.app

/**
 * What the phone says it is when it acts as a Bluetooth keyboard and mouse
 * (BtHid), and the reports it sends.
 *
 * One report descriptor with three reports:
 *
 * | id | report   | bytes | layout                                          |
 * |----|----------|-------|-------------------------------------------------|
 * | 1  | keyboard | 8     | modifiers, reserved, six key usages (boot)      |
 * | 2  | mouse    | 5     | buttons, x, y (boot), wheel, AC Pan             |
 * | 3  | consumer | 2     | one consumer usage, little-endian, 0 = released |
 *
 * The Bluetooth HID spec reserves report ids 1 and 2 for the boot keyboard
 * and boot mouse, and a host in boot protocol reads only their first 8 and 3
 * bytes. So both put the boot layout first: a host that switches to boot
 * protocol (a BIOS, a simple TV) still reads them right.
 *
 * X and Y are 8 bits (-127..127) for the same reason; bigger moves are split
 * into several reports (see [MouseMotion]).
 *
 * The data handed to BluetoothHidDevice.sendReport excludes the report id:
 * the stack puts it in front.
 */
object HidReports {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3

    const val KEYBOARD_SIZE = 8
    const val MOUSE_SIZE = 5
    const val CONSUMER_SIZE = 2

    /** Keyboard LEDs the host may set (output report 1): Num, Caps, Scroll, Compose, Kana. */
    const val KEYBOARD_OUTPUT_SIZE = 1

    const val MAX_KEYS = 6

    // mouse buttons, as bits of the report's first byte
    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04
    const val BUTTON_BACK = 0x08
    const val BUTTON_FORWARD = 0x10

    /** The range of one report's X, Y, wheel and pan. */
    const val AXIS_MAX = 127

    /** The highest consumer usage the consumer report can carry. */
    const val CONSUMER_MAX = 0x3FF

    /** Keyboard usage "ErrorRollOver": more keys held than the report has room for. */
    private const val ROLLOVER = 0x01

    val DESCRIPTOR: ByteArray = bytes(
        // --- report 1: keyboard ---------------------------------------------------------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,             // Collection (Application)
        0x85, ID_KEYBOARD,      //   Report ID (1)
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,             //   Usage Minimum (Left Control)
        0x29, 0xE7,             //   Usage Maximum (Right GUI)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x08,             //   Report Count (8)
        0x81, 0x02,             //   Input (Data, Variable, Absolute): the eight modifier bits
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x01,             //   Input (Constant): the reserved byte
        0x05, 0x08,             //   Usage Page (LEDs)
        0x19, 0x01,             //   Usage Minimum (Num Lock)
        0x29, 0x05,             //   Usage Maximum (Kana)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x05,             //   Report Count (5)
        0x91, 0x02,             //   Output (Data, Variable, Absolute): the five LEDs
        0x75, 0x03,             //   Report Size (3)
        0x95, 0x01,             //   Report Count (1)
        0x91, 0x01,             //   Output (Constant): padding to a byte
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (Application, 0x65)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (0x65)
        0x75, 0x08,             //   Report Size (8)
        0x95, MAX_KEYS,         //   Report Count (6)
        0x81, 0x00,             //   Input (Data, Array, Absolute): the keys held
        0xC0,                   // End Collection

        // --- report 2: mouse ------------------------------------------------------------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, ID_MOUSE,         //   Report ID (2)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (Button 1)
        0x29, 0x05,             //     Usage Maximum (Button 5)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x75, 0x01,             //     Report Size (1)
        0x95, 0x05,             //     Report Count (5)
        0x81, 0x02,             //     Input (Data, Variable, Absolute): five buttons
        0x75, 0x03,             //     Report Size (3)
        0x95, 0x01,             //     Report Count (1)
        0x81, 0x01,             //     Input (Constant): padding to a byte
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x03,             //     Report Count (3)
        0x81, 0x06,             //     Input (Data, Variable, Relative): x, y, wheel
        0x05, 0x0C,             //     Usage Page (Consumer)
        0x0A, 0x38, 0x02,       //     Usage (AC Pan, 0x0238)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x01,             //     Report Count (1)
        0x81, 0x06,             //     Input (Data, Variable, Relative): horizontal scroll
        0xC0,                   //   End Collection
        0xC0,                   // End Collection

        // --- report 3: consumer control (media keys, TV Home and Back) ------------------
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1, 0x01,             // Collection (Application)
        0x85, ID_CONSUMER,      //   Report ID (3)
        0x19, 0x00,             //   Usage Minimum (0: none)
        0x2A, 0xFF, 0x03,       //   Usage Maximum (0x03FF)
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF, 0x03,       //   Logical Maximum (0x03FF)
        0x75, 0x10,             //   Report Size (16)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x00,             //   Input (Data, Array, Absolute): the one usage pressed
        0xC0,                   // End Collection
    )

    /**
     * Keyboard report: [modifiers] is a bit set of HidKeys.MOD_*, [keys] the
     * usages held. More than six keys fill every slot with ErrorRollOver, as
     * the HID spec asks.
     */
    fun keyboard(modifiers: Int, vararg keys: Int): ByteArray {
        val r = ByteArray(KEYBOARD_SIZE)
        r[0] = (modifiers and 0xFF).toByte()
        val held = keys.filter { it != 0 }
        if (held.size > MAX_KEYS) {
            for (i in 0 until MAX_KEYS) r[2 + i] = ROLLOVER.toByte()
        } else {
            held.forEachIndexed { i, k -> r[2 + i] = (k and 0xFF).toByte() }
        }
        return r
    }

    /** Mouse report. Every axis is clamped to ±127; split bigger moves first (see [MouseMotion]). */
    fun mouse(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x1F).toByte(),
        dx.coerceIn(-AXIS_MAX, AXIS_MAX).toByte(),
        dy.coerceIn(-AXIS_MAX, AXIS_MAX).toByte(),
        wheel.coerceIn(-AXIS_MAX, AXIS_MAX).toByte(),
        pan.coerceIn(-AXIS_MAX, AXIS_MAX).toByte(),
    )

    /** Consumer report: [usage] held, or 0 for released. */
    fun consumer(usage: Int): ByteArray {
        require(usage in 0..CONSUMER_MAX) { "consumer usage out of range: $usage" }
        return byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte())
    }

    /** The size of input report [id] (for GET_REPORT), or -1 if there's no such report. */
    fun inputSize(id: Int): Int = when (id) {
        ID_KEYBOARD -> KEYBOARD_SIZE
        ID_MOUSE -> MOUSE_SIZE
        ID_CONSUMER -> CONSUMER_SIZE
        else -> -1
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
