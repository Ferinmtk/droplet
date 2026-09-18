package dev.droplet.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's media for other devices (docs/remote.md §3.2): what's playing
 * in every app with a media session, published as `state` "media", and
 * `media` actions applied through each app's transport controls.
 *
 * Android only hands out other apps' media sessions to a notification
 * listener, so this rides on MirrorService's notification access.
 *
 * Everything runs on the main thread, where the session callbacks arrive.
 * State goes out on change, at most once a second, and not at all while
 * nothing but the position moves as expected: other devices advance the
 * position themselves while the status is Playing.
 */
object MediaBridge {
    private const val MIN_GAP_MS = 1_000L
    private const val SETTLE_MS = 150L       // metadata and state usually change together
    private const val DRIFT_S = 2.0          // a position jump bigger than this is a seek
    private const val ART_LIMIT = 64 * 1024  // the protocol's cap on a data: URL

    private val main = Handler(Looper.getMainLooper())
    @SuppressLint("StaticFieldLeak")  // the application context, set only while connected
    private var app: Context? = null
    private var sessions: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var volumeReceiver: BroadcastReceiver? = null
    private val labels = HashMap<String, String>()
    private val art = LinkedHashMap<String, String?>()

    private var lastSentAt = 0L
    private var queued = false
    private var lastSignature: String? = null
    private var lastPosition: Double? = null
    private var lastPositionAt = 0L
    private var lastPlaying = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watch(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
        override fun onSessionDestroyed() = schedule()
    }

    private val publishNow = Runnable {
        queued = false
        publish()
    }

    fun start(context: Context) {
        main.post { startOnMain(context.applicationContext) }
    }

    fun stop() {
        main.post { stopOnMain() }
    }

    fun act(context: Context, msg: JSONObject) {
        main.post { runCatching { actOnMain(context.applicationContext, msg) } }
    }

    // --- watching ----------------------------------------------------------------

    private fun startOnMain(context: Context) {
        stopOnMain()
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(context, MirrorService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            app = context
            sessions = msm
            watch(msm.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // notification access was withdrawn; Caps drops "media" on the next refresh
            app = null
            sessions = null
            return
        }
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC) schedule()
            }
        }.also {
            // not public API constants, but sent by every Android version since 4.x
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        lastSignature = null  // a new connection: the hub has nothing yet
        schedule()
    }

    private fun stopOnMain() {
        main.removeCallbacks(publishNow)
        queued = false
        sessions?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsChanged) } }
        sessions = null
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
        volumeReceiver?.let { r -> app?.let { runCatching { it.unregisterReceiver(r) } } }
        volumeReceiver = null
        app = null
    }

    private fun watch(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = list
        list.forEach { it.registerCallback(controllerCallback, main) }
        schedule()
    }

    private fun schedule() {
        if (app == null || queued) return
        queued = true
        val wait = maxOf(SETTLE_MS, lastSentAt + MIN_GAP_MS - SystemClock.elapsedRealtime())
        main.postDelayed(publishNow, wait)
    }

    // --- describing ----------------------------------------------------------------

    private fun publish() {
        val context = app ?: return
        val players = JSONArray()
        var active: String? = null
        var activePosition: Double? = null
        var activePlaying = false
        val seen = HashSet<String>()
        for (c in controllers) {
            val pkg = c.packageName ?: continue
            if (!seen.add(pkg)) continue  // one entry per app; its first session is the current one
            val p = describe(context, c) ?: continue
            players.put(p)
            val playing = p.optString("status") == "Playing"
            if (active == null || (playing && !activePlaying)) {
                active = pkg
                activePlaying = playing
                activePosition = p.opt("position") as? Double
            }
        }
        val data = JSONObject()
            .put("players", players)
            .put("active", active ?: JSONObject.NULL)
            .put("volume", volume(context) ?: JSONObject.NULL)

        // unchanged apart from the position ticking along as expected: nothing to say
        val signature = JSONObject(data.toString()).also { d ->
            val arr = d.getJSONArray("players")
            for (i in 0 until arr.length()) arr.getJSONObject(i).remove("position")
        }.toString()
        val now = SystemClock.elapsedRealtime()
        if (signature == lastSignature) {
            val expected = lastPosition?.let { it + if (lastPlaying) (now - lastPositionAt) / 1000.0 else 0.0 }
            if (activePosition == null || expected == null || abs(activePosition!! - expected) < DRIFT_S) return
        }
        if (!Live.sendState("media", data)) return
        lastSignature = signature
        lastPosition = activePosition
        lastPositionAt = now
        lastPlaying = activePlaying
        lastSentAt = now
    }

    private fun describe(context: Context, c: MediaController): JSONObject? {
        val st = c.playbackState
        val md = c.metadata
        if (md == null && (st == null || st.state == PlaybackState.STATE_NONE)) return null  // a session with nothing loaded
        val status = when (st?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "Playing"
            PlaybackState.STATE_PAUSED -> "Paused"
            else -> "Stopped"
        }
        val actions = st?.actions ?: 0L
        fun can(vararg a: Long) = a.any { actions and it != 0L }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return JSONObject()
            .put("id", c.packageName)
            .put("name", label(context, c.packageName))
            .put("status", status)
            .put("title", title.orEmpty())
            .put("artist", artist.orEmpty())
            .put("album", album.orEmpty())
            .put("art", art(context, c.packageName, md, "$title|$artist|$album") ?: JSONObject.NULL)
            .put("position", position(st) ?: JSONObject.NULL)
            .put("length", if (duration > 0) duration / 1000.0 else JSONObject.NULL)
            .put("can_seek", can(PlaybackState.ACTION_SEEK_TO))
            .put("can_next", can(PlaybackState.ACTION_SKIP_TO_NEXT))
            .put("can_previous", can(PlaybackState.ACTION_SKIP_TO_PREVIOUS))
            .put("can_play", can(PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PLAY_PAUSE))
            .put("can_pause", can(PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY_PAUSE))
    }

    /** Seconds, extrapolated to now when playing (apps only report it on changes). */
    private fun position(st: PlaybackState?): Double? {
        if (st == null || st.position < 0) return null
        var ms = st.position.toDouble()
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            ms += (SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed
        }
        return (maxOf(0.0, ms) / 10).roundToInt() / 100.0
    }

    private fun volume(context: Context): JSONObject? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        val level = am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
        return JSONObject()
            .put("level", (level * 1000).roundToInt() / 1000.0)
            .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    private fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        val pm = context.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** The cover as a small JPEG data: URL (encoded once per track), an https URL, or null. */
    private fun art(context: Context, pkg: String, md: MediaMetadata?, track: String): String? {
        md ?: return null
        val bitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val key = "$pkg|$track|${bitmap?.generationId}|${bitmap?.width}|$uri"
        art[key]?.let { return it }
        if (key in art) return null
        val out = when {
            bitmap != null -> encode(bitmap)
            uri == null -> null
            uri.startsWith("https://") -> uri
            // content:// covers are readable only if the app granted access; try once
            else -> runCatching {
                val src = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
                src?.let { encode(it) }
            }.getOrNull()
        }
        art[key] = out
        while (art.size > 6) art.remove(art.keys.first())
        return out
    }

    private fun encode(src: Bitmap): String? = runCatching {
        // hardware bitmaps can't be drawn into a software canvas; copy first
        val soft = if (src.config == Bitmap.Config.HARDWARE)
            src.copy(Bitmap.Config.ARGB_8888, false) else src
        for ((side, quality) in listOf(320 to 82, 256 to 75, 192 to 70, 128 to 60)) {
            val scale = side.toFloat() / maxOf(soft.width, soft.height)
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(soft,
                (soft.width * scale).roundToInt().coerceAtLeast(1), (soft.height * scale).roundToInt().coerceAtLeast(1), true) else soft
            val bytes = ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            val url = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (url.length <= ART_LIMIT) return@runCatching url
        }
        null
    }.getOrNull()

    // --- acting ------------------------------------------------------------------

    private fun actOnMain(context: Context, msg: JSONObject) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val music = AudioManager.STREAM_MUSIC
        when (val action = msg.optString("action")) {
            "volume" -> {
                val v = msg.optDouble("value", Double.NaN).takeUnless { it.isNaN() } ?: return
                val index = (v.coerceIn(0.0, 1.0) * am.getStreamMaxVolume(music)).roundToInt()
                if (index > 0 && am.isStreamMute(music)) am.adjustStreamVolume(music, AudioManager.ADJUST_UNMUTE, 0)
                am.setStreamVolume(music, index, 0)
            }
            "mute" -> {
                val dir = when {
                    !msg.has("value") || msg.isNull("value") -> AudioManager.ADJUST_TOGGLE_MUTE
                    msg.optBoolean("value") -> AudioManager.ADJUST_MUTE
                    else -> AudioManager.ADJUST_UNMUTE
                }
                am.adjustStreamVolume(music, dir, 0)
            }
            else -> transport(am, action, msg)
        }
        schedule()
    }

    private fun transport(am: AudioManager, action: String, msg: JSONObject) {
        val wanted = msg.optString("player").takeIf { it.isNotEmpty() }
        val c = controllers.firstOrNull { it.packageName == wanted } ?: activeController()
        if (c == null) {
            // no session to talk to: a media key wakes whichever app played last
            val key = when (action) {
                "play-pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return
            }
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return
        }
        val t = c.transportControls
        when (action) {
            "play-pause" -> if (isPlaying(c)) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "stop" -> t.stop()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "seek" -> {
                // "value" per the protocol; "position" as the hub's own media card sends it
                val s = msg.optDouble("value", msg.optDouble("position", Double.NaN))
                if (!s.isNaN()) t.seekTo((maxOf(0.0, s) * 1000).toLong())
            }
        }
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING ||
        c.playbackState?.state == PlaybackState.STATE_BUFFERING

    private fun activeController(): MediaController? =
        controllers.firstOrNull { isPlaying(it) } ?: controllers.firstOrNull { it.metadata != null } ?: controllers.firstOrNull()
}
