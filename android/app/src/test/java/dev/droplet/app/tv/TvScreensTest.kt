package dev.droplet.app.tv

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.Discovery
import dev.droplet.app.Prefs
import dev.droplet.app.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The TV screens themselves. With DROPLET_SHOTS, renders them to PNGs for a
 * person to look at (like ScreensTest). With DROPLET_TEST_TV_PY, drives them
 * against tests/fake_tv.py: "Find my TV" lists the fake, tapping it asks for
 * a code, a mistyped code is refused, the right one pairs; then the remote's
 * buttons, the phone's volume keys and the keyboard field reach the TV, and
 * the TV forgetting droplet brings up "Pair again".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class TvScreensTest {
    private val shots = System.getProperty("droplet.shots").orEmpty()
    private val python = System.getProperty("droplet.testTvPy").orEmpty()
    private lateinit var app: Application
    private lateinit var tmp: File
    private var fake: Process? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.init(app)
        Tv.init(app)
        TvIdentity.keystoreAllowed = false
        tmp = File(System.getProperty("java.io.tmpdir"), "tvscreens-" + System.nanoTime().toString(36)).apply { mkdirs() }
        Tv.dirOverride = File(tmp, "tv")
        Tv.timing = TvLink.Timing(connectMs = 3_000, startMs = 3_000, idleMs = 8_000, backoffFirstMs = 300,
            backoffMaxMs = 1_500, graceMs = 2_000)
        Prefs.tvSelected = null
    }

    @After
    fun tearDown() {
        Tv.release("test")
        idleFor(6_000)
        fake?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        Tv.dirOverride = null
        Tv.browser = null
        Tv.timing = TvLink.Timing()
        tmp.deleteRecursively()
    }

    private val main get() = shadowOf(Looper.getMainLooper())
    private fun idle() = main.idle()
    private fun idleFor(ms: Long) = main.idleFor(java.time.Duration.ofMillis(ms))

    /** Waits for [ok], running the main thread meanwhile (the screens' coroutines resume there). */
    private fun waitFor(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            idle()
            if (ok()) return
            Thread.sleep(40)
        }
        fail("timed out waiting for $what")
    }

    private fun shoot(activity: Activity, name: String) {
        if (shots.isEmpty()) return
        idle()
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(shots).mkdirs()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every view under [v] whose text or description is [label]. */
    private fun find(v: View, label: String): List<View> {
        val out = mutableListOf<View>()
        if ((v is TextView && v.text?.toString() == label) || v.contentDescription?.toString() == label) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) out += find(v.getChildAt(i), label)
        return out
    }

    private fun one(a: Activity, label: String): View = find(a.window.decorView, label).firstOrNull { it.isShown }
        ?: throw AssertionError("no \"$label\" on screen; showing: " + texts(a.window.decorView))

    /** The visible texts, for a failure message. */
    private fun texts(v: View): List<String> = when {
        !v.isShown -> emptyList()
        v is TextView -> listOfNotNull(v.text?.toString()?.takeIf { it.isNotBlank() })
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    // --- screenshots ---------------------------------------------------------------------------

    @Test
    fun screens() {
        assumeTrue("set DROPLET_SHOTS to render the TV screens", shots.isNotEmpty())
        Tv.browser = { _, onChange ->
            onChange(listOf(Tv.Found("Living room TV", "192.168.100.12", 6466, "0c:79:55:8f:ef:36", "Living room TV"),
                Tv.Found("Bedroom TV", "192.168.100.31", 6466, null, "Bedroom TV")))
            Discovery.Handle { }
        }
        val find = Robolectric.buildActivity(TvPairActivity::class.java).setup().get()
        shoot(find, "tv-find")
        // the code step, as after asking the TV
        find.findViewById<View>(R.id.step_find).visibility = View.GONE
        find.findViewById<View>(R.id.step_code).visibility = View.VISIBLE
        find.findViewById<TextView>(R.id.code_body).text = find.getString(R.string.tv_code_body, "Living room TV")
        find.findViewById<View>(R.id.code).requestFocus()
        find.findViewById<TextView>(R.id.code).text = "4F2"
        find.findViewById<TextView>(R.id.code_error).apply { setText(R.string.tv_err_wrong_code); visibility = View.VISIBLE }
        shoot(find, "tv-code")

        Tv.store.put(PairedTv("a1b2c3d4", "Living room TV", "192.0.2.1", mac = "0c:79:55:8f:ef:36", pin = "ab".repeat(32),
            model = "TCL 43P635"))
        Prefs.tvMore = false
        val remoteCtl = Robolectric.buildActivity(TvActivity::class.java).setup()
        val remote = remoteCtl.get()
        // the simple view first: the essentials only
        assertFalse(remote.findViewById<View>(R.id.more_sections).isShown)
        assertFalse(remote.findViewById<View>(R.id.mode).isShown)
        assertTrue(remote.findViewById<View>(R.id.simple_volume).isShown)
        shoot(remote, "tv-remote-simple")
        waitFor("the unreachable banner", 15_000) { remote.findViewById<View>(R.id.banner).isShown }
        shoot(remote, "tv-remote-simple-unreachable")
        remote.findViewById<View>(R.id.more_toggle).performClick()
        assertTrue(Prefs.tvMore)
        assertTrue(remote.findViewById<View>(R.id.more_sections).isShown)
        assertFalse(remote.findViewById<View>(R.id.simple_volume).isShown)
        shoot(remote, "tv-remote-full")
        remote.findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 2000)
        shoot(remote, "tv-remote-lower")
        remote.findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        remote.findViewById<View>(R.id.mode_pad).performClick()
        shoot(remote, "tv-remote-touchpad")
        Prefs.tvTouchpad = false
        Prefs.tvMore = false
        remoteCtl.pause().stop().destroy()
    }

    // --- against the fake TV --------------------------------------------------------------------

    private fun startFake(clientPem: File, state: File): Int {
        val port = generateSequence { ServerSocket(0).use { it.localPort } }.first { p ->
            p < 65000 && runCatching { ServerSocket(p + 1).close() }.isSuccess
        }
        val log = File(tmp, "fake.log")
        fake = ProcessBuilder(python, File("../../tests/fake_tv.py").absolutePath, "--client-cert", clientPem.path,
            "--port", port.toString(), "--state", state.path).redirectErrorStream(true).redirectOutput(log).start()
        waitFor("the fake TV", 15_000) { log.exists() && log.readText().contains("listening") }
        return port
    }

    @Test
    fun pairAndDriveTheFakeTvThroughTheScreens() {
        assumeTrue("set DROPLET_TEST_TV_PY to drive the screens against the fake TV", python.isNotEmpty())
        val pem = File(tmp, "client.pem").apply { writeText(Tv.identity().pem) }
        val stateFile = File(tmp, "state.json")
        val port = startFake(pem, stateFile)
        fun state() = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        fun events(kind: String): List<List<Any?>> {
            val a = state().optJSONArray("log") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONArray(it) }.filter { it.getString(0) == kind }
                .map { e -> (0 until e.length()).map { e.get(it) } }
        }
        Tv.browser = { _, onChange ->
            onChange(listOf(Tv.Found("Fake Google TV", "127.0.0.1", port, null, "Fake Google TV")))
            Discovery.Handle { }
        }

        // no TV yet: the remote sends you to find one
        val first = Robolectric.buildActivity(TvActivity::class.java).setup()
        assertTrue(first.get().isFinishing)
        val next = shadowOf(first.get()).nextStartedActivity
        assertEquals(TvPairActivity::class.java.name, next.component?.className)

        // Find my TV: the only TV on the Wi-Fi is asked for a code without a tap
        val pair = Robolectric.buildActivity(TvPairActivity::class.java, next).setup().get()
        // listed at once, unless the auto-pair already moved on to asking it
        assertTrue(texts(pair.window.decorView).toString(),
            find(pair.window.decorView, "Fake Google TV").any { it.isShown } || !pair.findViewById<View>(R.id.step_find).isShown)
        println("pair screen: " + texts(pair.window.decorView))
        idleFor(2_000)
        waitFor("the code step") { pair.findViewById<View>(R.id.step_code).isShown }
        shoot(pair, "tv-code-live")
        waitFor("a code on the TV") { state().optString("code").length == 6 }
        val code = state().getString("code")

        // a typo: refused on the phone, the TV keeps its code up
        val codeField = pair.findViewById<TextView>(R.id.code)
        codeField.text = (if (code[0] == 'F') "0" else "F") + code.substring(1)   // the sixth character sends it
        waitFor("the wrong-code message") { pair.findViewById<TextView>(R.id.code_error).isShown }
        assertEquals("", codeField.text.toString())   // cleared for another try
        assertEquals(pair.getString(R.string.tv_err_wrong_code), pair.findViewById<TextView>(R.id.code_error).text.toString())
        assertEquals(code, state().optString("code"))

        // typed again, in lower case: it pairs
        codeField.text = code.lowercase()
        assertEquals(code, codeField.text.toString())   // shown in capitals
        waitFor("paired") { pair.isFinishing }
        assertTrue(state().getBoolean("paired"))
        val saved = Tv.tvs().single()
        assertEquals("Fake Google TV", saved.name)
        assertEquals("aa:bb:cc:dd:ee:01", saved.mac)   // from the TV's certificate, for Wake-on-LAN
        // straight to the remote
        val open = shadowOf(pair).nextStartedActivity
        assertEquals(TvActivity::class.java.name, open.component?.className)

        // the remote: connects, shows the TV's state; the full view, for the media keys, typing and apps
        Prefs.tvMore = true
        val ctl = Robolectric.buildActivity(TvActivity::class.java, open).setup()
        val remote = ctl.get()
        waitFor("connected") { remote.findViewById<TextView>(R.id.state).text.toString().startsWith("On") }
        waitFor("the app in front") { remote.findViewById<TextView>(R.id.state).text.toString() == "On · Home" }
        assertTrue(remote.findViewById<View>(R.id.volume_row).isShown)
        assertFalse(remote.findViewById<View>(R.id.banner).isShown)

        // the phone's volume keys drive the TV's volume
        remote.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        remote.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
        waitFor("volume up on the TV") { events("key").any { it[1] == "VOLUME_UP" } }
        waitFor("the new volume shown") { remote.findViewById<TextView>(R.id.volume_text).text.toString() == "13" }

        // the D-pad (TalkBack's click on OK is a short press), Home, and media keys
        val dpad = remote.findViewById<DpadView>(R.id.dpad)
        dpad.listener!!.down(DpadView.Zone.UP)
        dpad.listener!!.up(DpadView.Zone.UP, cancelled = false)
        waitFor("up") { events("key").any { it[1] == "DPAD_UP" } }
        find(remote.window.decorView, remote.getString(R.string.tv_home)).first { it.hasOnClickListeners() }.performClick()
        waitFor("home") { events("key").any { it[1] == "HOME" && it[2] == "SHORT" } }
        find(remote.window.decorView, remote.getString(R.string.tv_play_pause)).first { it.hasOnClickListeners() }.performClick()
        waitFor("play/pause") { events("key").any { it[1] == "MEDIA_PLAY_PAUSE" } }

        // typing
        remote.findViewById<TextView>(R.id.text).text = "hello tv"
        remote.findViewById<View>(R.id.send).performClick()
        waitFor("text") { events("text").any { it[1] == "hello tv" } }

        // an app tile
        find(remote.window.decorView, "Netflix").first { it.hasOnClickListeners() }.performClick()
        waitFor("netflix") { remote.findViewById<TextView>(R.id.state).text.toString() == "On · Netflix" }

        // power: off shows standby
        remote.findViewById<View>(R.id.power).performClick()
        waitFor("standby") { remote.findViewById<TextView>(R.id.state).text.toString() == remote.getString(R.string.tv_state_standby) }

        // the TV forgets droplet: "Pair again"
        val pid = Process::class.java.getMethod("pid").invoke(fake) as Long
        Runtime.getRuntime().exec(arrayOf("kill", "-USR1", pid.toString())).waitFor()
        waitFor("the pair-again banner") {
            remote.findViewById<View>(R.id.banner).isShown &&
                remote.findViewById<TextView>(R.id.banner_action).text.toString() == remote.getString(R.string.tv_pair_again)
        }
        assertFalse(Tv.tvs().single().paired)
        remote.findViewById<View>(R.id.banner_action).performClick()
        val again = shadowOf(remote).nextStartedActivity
        assertEquals(TvPairActivity::class.java.name, again.component?.className)
        assertEquals("127.0.0.1", again.getStringExtra(TvPairActivity.EXTRA_HOST))
        ctl.pause().stop().destroy()
        Prefs.tvMore = false
    }
}
