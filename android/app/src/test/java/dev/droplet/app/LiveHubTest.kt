package dev.droplet.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone side of docs/remote.md, end to end: the app's real code (Live,
 * the bridges, the remote screen) on Robolectric, connected to a real droplet
 * hub, driven by a fake controller that is also an `input` + `clipboard`
 * helper. Runs only when DROPLET_TEST_HUB points at a hub, e.g.
 *
 *   DROPLET_PORT=8814 DROPLET_PUSH=0 DROPLET_HOME=$(mktemp -d) python app.py
 *   DROPLET_TEST_HUB=http://127.0.0.1:8814 ./gradlew testReleaseUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveHubTest {
    private val hub = System.getProperty("droplet.testHub").orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
    private lateinit var app: Application
    private lateinit var ctl: Controller
    private var ctlToken = ""
    private var phoneId = ""

    @Before
    fun setUp() {
        assumeTrue("set DROPLET_TEST_HUB to run the live tests", hub.isNotEmpty())
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        if (hub.isEmpty()) return
        Live.release("test")
        if (::ctl.isInitialized) ctl.ws.close(1000, null)
    }

    // --- helpers -------------------------------------------------------------------

    // the main looper runs on a fake clock: move it along with the real time spent polling
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; live: ${Live.state.value}")
    }

    /** Registers a device over HTTP the way a browser does; returns (id, token). */
    private fun register(name: String): Pair<String, String> {
        val res = http.newCall(Request.Builder().url("$hub/api/device")
            .post(JSONObject().put("name", name).toString().toRequestBody("application/json".toMediaType())).build()).execute()
        val token = res.headers("Set-Cookie").first { it.startsWith("droplet_device=") }.substringAfter('=').substringBefore(';')
        val id = JSONObject(res.body!!.string()).getString("id")
        return id to token
    }

    private fun get(path: String, token: String): JSONObject =
        JSONObject(http.newCall(Request.Builder().url(hub + path).header("Authorization", "Bearer $token").build())
            .execute().body!!.string())

    /** The fake controller: a device with its own socket that also takes input and clipboard. */
    private inner class Controller(token: String) {
        val inbox = LinkedBlockingQueue<JSONObject>()
        val ws: WebSocket = http.newWebSocket(Request.Builder().url(hub.replace("http", "ws") + "/ws")
            .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("t", "hello").put("caps", JSONArray(listOf("input", "clipboard")))
                    .put("platform", "linux").put("app", "fake-ctl").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.put(JSONObject(text))
            }
        })
        private var rpcs = 0

        fun send(msg: JSONObject) = assertTrue(ws.send(msg.toString()))

        /** The next message matching [match], idling the phone's main thread meanwhile. */
        fun expect(what: String, timeoutMs: Long = 15_000, match: (JSONObject) -> Boolean): JSONObject {
            // frames can arrive in any order; unmatched ones wait for a later expect
            held.firstOrNull(match)?.let { held.remove(it); return it }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                idle()
                val m = inbox.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (match(m)) return m
                held += m
            }
            throw AssertionError("controller never got $what; live: ${Live.state.value}; held: ${held.map { it.optString("t") + "/" + it.optString("kind") }}")
        }

        private val held = ArrayList<JSONObject>()

        fun rpc(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 35_000): JSONObject {
            val id = "r${++rpcs}"
            send(JSONObject().put("t", "rpc").put("id", id).put("to", phoneId).put("method", method).put("params", params))
            return expect("rpc-result $id for $method", timeoutMs) { it.optString("t") == "rpc-result" && it.optString("id") == id }
        }
    }

    // --- the test -------------------------------------------------------------------

    @Test
    fun phoneTakesPartInTheRemoteProtocol() {
        // an ordinary connected network
        val conn = app.getSystemService(android.net.ConnectivityManager::class.java)
        val nc = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(nc).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(conn).setNetworkCapabilities(conn.activeNetwork, nc)

        // what the owner would grant in Settings
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        app.getSystemService(android.telephony.TelephonyManager::class.java).let {
            shadowOf(it).setIsSmsCapable(true)
            shadowOf(it).setDeviceSmsCapable(true)
        }
        // All files access (Robolectric can't evaluate the real app-op check)
        Caps.isStorageManager = { true }
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            "${app.packageName}/${MirrorService::class.java.name}")
        Robolectric.setupContentProvider(FakeSms::class.java, "sms")
        Robolectric.setupContentProvider(FakeContacts::class.java, "com.android.contacts")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 77).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING))

        // a playing media session for the bridge to find
        val session = MediaSession(app, "test")
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song Title")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "The Artist")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "The Album")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 210_000)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,
                android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888))
            .build()
        val playing = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 42_000, 1f)
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO)
            .build()
        session.setMetadata(metadata)
        session.setPlaybackState(playing)
        session.isActive = true
        // Robolectric's controller doesn't read the session back; tell it what the session holds
        val controller = MediaController(app, session.sessionToken)
        shadowOf(controller).setMetadata(metadata)
        shadowOf(controller).setPlaybackState(playing)
        val msm = app.getSystemService(MediaSessionManager::class.java)
        shadowOf(msm).addController(controller)

        // files in the phone's shared storage
        val top = Environment.getExternalStorageDirectory()
        File(top, "Download/Holiday").mkdirs()
        File(top, "Download/zeta.txt").writeText("zeta")
        File(top, "Download/alpha.pdf").writeText("%PDF pretend")
        File(top, "Download/.hidden").writeText("no")
        File(top, "DCIM/Camera").mkdirs()
        File(top, "secret.txt").writeText("outside the roots")

        // --- linking: the phone becomes a device named in "another browser" ------
        val (id, browserToken) = register("robo-phone-${System.nanoTime() % 100000}")
        phoneId = id
        val code = JSONObject(http.newCall(Request.Builder().url("$hub/api/device/link-code")
            .header("Authorization", "Bearer $browserToken").post(ByteArray(0).toRequestBody()).build())
            .execute().body!!.string()).getString("code")
        Prefs.hubUrl = hub
        val linkedName = Hub.link(code)
        assertTrue(linkedName.startsWith("robo-phone-"))
        assertNotNull("link stores the token as the WebView cookie", Hub.deviceToken())
        assertEquals(phoneId, Hub.me().getJSONObject("device").getString("id"))

        val (_, t) = register("robo-ctl-${System.nanoTime() % 100000}")
        ctlToken = t
        ctl = Controller(ctlToken)
        ctl.expect("welcome") { it.optString("t") == "welcome" }

        // --- hello: caps that work and are permitted -----------------------------
        Live.hold("test")
        waitFor("the phone to connect") { Live.state.value.connected }
        val presence = ctl.expect("the phone's presence with its caps") {
            it.optString("t") == "presence" && it.getJSONObject("devices").has(phoneId)
        }.getJSONObject("devices").getJSONObject(phoneId)
        val caps = presence.getJSONArray("caps").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(setOf("clipboard", "files", "media", "sms"), caps)
        assertEquals("android", presence.getJSONArray("apps").getJSONObject(0).getString("platform"))
        assertEquals(1, Live.state.value.othersOnline)

        // --- battery state -----------------------------------------------------
        val battery = ctl.expect("battery state") { it.optString("t") == "state" && it.optString("kind") == "battery" }
        assertEquals(phoneId, battery.getString("device"))
        assertEquals(77, battery.getJSONObject("data").getInt("level"))
        assertTrue(battery.getJSONObject("data").getBoolean("charging"))

        // --- media: state, then actions -----------------------------------------
        val media = ctl.expect("media state") { it.optString("t") == "state" && it.optString("kind") == "media" }
            .getJSONObject("data")
        val player = media.getJSONArray("players").getJSONObject(0)
        assertEquals(app.packageName, player.getString("id"))
        assertEquals("Playing", player.getString("status"))
        assertEquals("Song Title", player.getString("title"))
        assertEquals("The Artist", player.getString("artist"))
        assertEquals("The Album", player.getString("album"))
        assertEquals(210.0, player.getDouble("length"), 0.01)
        assertTrue(player.getDouble("position") >= 42.0)
        assertTrue(player.getBoolean("can_seek") && player.getBoolean("can_next") && !player.getBoolean("can_previous"))
        val art = player.getString("art")
        assertTrue("art is a JPEG data URL", art.startsWith("data:image/jpeg;base64,"))
        assertTrue("art fits 64 KB", art.length <= 64 * 1024)
        assertEquals(app.packageName, media.getString("active"))
        val vol = media.getJSONObject("volume")
        assertTrue(vol.getDouble("level") in 0.0..1.0)

        fun mediaAction(action: String, value: Any? = null) = ctl.send(JSONObject().put("t", "media").put("to", phoneId)
            .put("action", action).apply { if (value != null) put("value", value) })
        // Robolectric's MediaController doesn't forward transport controls to the
        // session, so these only prove the actions are taken without upsetting anything
        for (action in listOf("play-pause", "next", "previous", "stop")) mediaAction(action)
        mediaAction("seek", 90.5)
        mediaAction("volume", 0.4)
        val am = app.getSystemService(android.media.AudioManager::class.java)
        waitFor("volume") {
            am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ==
                Math.round(0.4 * am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).toInt()
        }
        mediaAction("mute", true)
        waitFor("mute") { am.isStreamMute(android.media.AudioManager.STREAM_MUSIC) }
        mediaAction("mute", false)  // (no value toggles; Robolectric does not model ADJUST_TOGGLE_MUTE)
        waitFor("unmute") { !am.isStreamMute(android.media.AudioManager.STREAM_MUSIC) }
        assertTrue(Live.state.value.connected)
        // a status change goes out again
        val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 50_000, 0f)
            .setActions(PlaybackState.ACTION_PLAY_PAUSE).build()
        shadowOf(controller).setPlaybackState(paused)
        shadowOf(controller).executeOnPlaybackStateChanged(paused)
        ctl.expect("media state after pausing") {
            it.optString("kind") == "media" &&
                it.getJSONObject("data").getJSONArray("players").getJSONObject(0).getString("status") == "Paused"
        }

        // --- files --------------------------------------------------------------
        val roots = ctl.rpc("files.roots").getJSONObject("result").getJSONArray("roots")
        val rootPaths = (0 until roots.length()).map { roots.getJSONObject(it).getString("path") }
        assertTrue(rootPaths.containsAll(listOf("/Download", "/DCIM/Camera")))
        assertEquals("Camera", (0 until roots.length()).map { roots.getJSONObject(it) }.first { it.getString("path") == "/DCIM/Camera" }.getString("name"))

        val listing = ctl.rpc("files.list", JSONObject().put("path", "/Download")).getJSONObject("result")
        assertEquals("/Download", listing.getString("path"))
        val names = listing.getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("Holiday", "alpha.pdf", "zeta.txt"), names.map { it.getString("name") })
        assertTrue(names[0].getBoolean("dir") && !names[1].getBoolean("dir"))
        assertEquals(4L, names[2].getLong("size"))

        for (bad in listOf("/Download/../secret.txt", "/../..", "/secret.txt", "Download", "/DCIM", "/Download/./x")) {
            val r = ctl.rpc("files.list", JSONObject().put("path", bad))
            assertTrue("$bad must be refused: $r", r.has("error") && !r.has("result"))
        }
        val nope = ctl.rpc("files.get", JSONObject().put("path", "/Download/../secret.txt"))
        assertTrue(nope.has("error"))

        val got = ctl.rpc("files.get", JSONObject().put("path", "/Download/alpha.pdf")).getJSONObject("result")
        assertTrue(got.getBoolean("ok"))
        assertEquals("alpha.pdf", got.getString("name"))
        val inbox = get("/api/files", ctlToken).getJSONArray("inbox")
        val landed = (0 until inbox.length()).map { inbox.getJSONObject(it) }.first { it.getString("name") == "alpha.pdf" }
        assertEquals(12L, landed.getLong("size"))
        assertEquals(linkedName, landed.getString("from"))

        // --- sms ------------------------------------------------------------------
        val threads = ctl.rpc("sms.threads", JSONObject().put("limit", 50)).getJSONObject("result").getJSONArray("threads")
        assertEquals(2, threads.length())
        val first = threads.getJSONObject(0)
        assertEquals("7", first.getString("id"))            // newest thread first
        assertEquals("+254700000001", first.getString("address"))
        assertEquals("Wanjiru", first.getString("name"))    // from contacts
        assertEquals("See you at 6", first.getString("snippet"))
        assertEquals(1, first.getInt("unread"))
        assertEquals(1_700_000_300.0, first.getDouble("ts"), 0.001)
        assertTrue(threads.getJSONObject(1).isNull("name"))

        val msgs = ctl.rpc("sms.thread", JSONObject().put("id", "7").put("limit", 100)).getJSONObject("result").getJSONArray("messages")
        assertEquals(listOf("Hi", "Hello!", "See you at 6"), (0 until msgs.length()).map { msgs.getJSONObject(it).getString("body") })
        assertFalse(msgs.getJSONObject(0).getBoolean("out"))
        assertTrue(msgs.getJSONObject(1).getBoolean("out"))

        // sending itself is covered by SmsSendTest (Robolectric's SDK 35 SmsManager can't divide messages)
        val badAddress = ctl.rpc("sms.send", JSONObject().put("address", "rm -rf /").put("body", "x"))
        assertTrue(badAddress.has("error"))

        // --- clipboard, both ways, without echo ------------------------------------
        val cm = app.getSystemService(ClipboardManager::class.java)
        ctl.send(JSONObject().put("t", "clip").put("text", "copied on the PC ${System.nanoTime()}"))
        waitFor("the PC's clip on the phone") { cm.primaryClip?.getItemAt(0)?.text?.startsWith("copied on the PC") == true }
        assertEquals("the phone doesn't send back what it was given", ClipBridge.Result.SAME, ClipBridge.send(app, manual = false))

        val phoneText = "copied on the phone ${System.nanoTime()}"
        cm.setPrimaryClip(ClipData.newPlainText("x", phoneText))
        ClipBridge.onForeground(app)
        assertEquals(phoneText, ctl.expect("the phone's clip") { it.optString("t") == "clip" }.getString("text"))
        assertEquals(ClipBridge.Result.SAME, ClipBridge.send(app, manual = false))

        // --- a switched-off capability is re-announced and refused ------------------
        Prefs.capSms = false
        Live.refresh()
        ctl.expect("presence without sms") {
            it.optString("t") == "presence" && it.getJSONObject("devices").optJSONObject(phoneId)
                ?.getJSONArray("caps")?.toString()?.contains("sms") == false &&
                it.getJSONObject("devices").optJSONObject(phoneId)?.getJSONArray("caps")?.length() == 3
        }
        waitFor("reconnected") { Live.state.value.connected }
        assertTrue(ctl.rpc("sms.threads").has("error"))
        Prefs.capSms = true
        Live.refresh()
        waitFor("reconnected with sms") { Live.state.value.connected && "sms" in Live.state.value.caps }

        // --- the presentation remote -----------------------------------------------
        val remote = Robolectric.buildActivity(RemoteActivity::class.java).setup()
        val activity = remote.get()
        waitFor("the remote to pick the controller") {
            activity.findViewById<android.widget.TextView>(R.id.target).text.startsWith("robo-ctl")
        }
        fun key(code: Int, repeat: Int = 0) {
            activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat))
            activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0))
        }
        fun expectKey(k: String) {
            val m = ctl.expect("key $k") { it.optString("t") == "input" }
            assertEquals(phoneId, m.getJSONObject("from").getString("id"))
            val ev = m.getJSONArray("ev").getJSONObject(0)
            assertEquals("key", ev.getString("k"))
            assertEquals(k, ev.getString("key"))
        }
        key(KeyEvent.KEYCODE_VOLUME_UP)
        expectKey("ArrowRight")
        key(KeyEvent.KEYCODE_VOLUME_DOWN)
        expectKey("ArrowLeft")
        key(KeyEvent.KEYCODE_VOLUME_UP, repeat = 1)  // a held key doesn't race through the deck
        activity.findViewById<android.view.View>(R.id.start).performClick()
        expectKey("F5")
        activity.findViewById<android.view.View>(R.id.black).performClick()
        expectKey("b")
        activity.findViewById<android.view.View>(R.id.end).performClick()
        expectKey("Escape")
        activity.findViewById<android.view.View>(R.id.next).performClick()
        expectKey("ArrowRight")
        assertTrue("screen stays on", activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        remote.pause().stop().destroy()

        // --- reconnecting ------------------------------------------------------------
        Prefs.hubUrl = "http://127.0.0.1:9"  // nothing listens there
        Live.refresh()
        waitFor("a failed connect") { Live.state.value.status == Live.Status.RETRYING || Live.state.value.status == Live.Status.UNNAMED }
        Prefs.hubUrl = hub
        Live.kick()
        waitFor("back online") { Live.state.value.connected }
        session.release()
    }

    /** A tiny Telephony SMS provider: two threads, newest first. */
    class FakeSms : ContentProvider() {
        private val rows = listOf(
            // _id, thread_id, address, body, date, type, read
            arrayOf<Any?>(5L, 7L, "+254700000001", "See you at 6", 1_700_000_300_000L, 1, 0),
            arrayOf<Any?>(4L, 3L, "+254700000009", "Old news", 1_700_000_250_000L, 1, 1),
            arrayOf<Any?>(3L, 7L, "+254700000001", "Hello!", 1_700_000_200_000L, 2, 1),
            arrayOf<Any?>(2L, 7L, "+254700000001", "draft", 1_700_000_150_000L, 3, 1),
            arrayOf<Any?>(1L, 7L, "+254700000001", "Hi", 1_700_000_100_000L, 1, 1),
        )
        private val cols = listOf("_id", "thread_id", "address", "body", "date", "type", "read")

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val proj = projection ?: cols.toTypedArray()
            val c = MatrixCursor(proj)
            rows.filter { it[5] != 3 }  // the app asks for "type != 3"
                .filter { selection?.contains("thread_id") != true || it[1].toString() == args!![0] }
                .forEach { r -> c.addRow(proj.map { r[cols.indexOf(it)] }) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    /** Contacts: knows one number. */
    class FakeContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val c = MatrixCursor(arrayOf("display_name"))
            if (uri.lastPathSegment == "+254700000001") c.addRow(arrayOf("Wanjiru"))
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
