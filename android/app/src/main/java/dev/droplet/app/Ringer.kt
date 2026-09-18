package dev.droplet.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread

/**
 * "Find my phone": a loud alarm on the alarm stream at full volume (which
 * plays through silent mode), vibration, and a full-screen notification
 * with Stop. Everything runs on the main thread.
 */
object Ringer {
    private const val MAX_RING_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile
    var ringing: Ring? = null
        private set
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private var appContext: Context? = null

    private val timeout = Runnable { appContext?.let { stopOnMain(it, tellHub = false) } }

    fun start(context: Context, ring: Ring) {
        main.post { startOnMain(context.applicationContext, ring) }
    }

    /** Silences the phone; [tellHub] also clears the ring on the hub (the Stop button). */
    fun stop(context: Context, tellHub: Boolean) {
        main.post { stopOnMain(context.applicationContext, tellHub) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun startOnMain(context: Context, ring: Ring) {
        if (ringing?.id == ring.id || ring.id == Prefs.silencedRing) return
        if (ringing != null) silence(context)
        appContext = context
        ringing = ring

        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droplet:ring")
            .apply { acquire(MAX_RING_MS + 10_000) }

        val audio = context.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        play(context)
        vibrate(context)
        notify(context, ring)
        main.postDelayed(timeout, MAX_RING_MS)
        listeners.toList().forEach { it() }
    }

    private fun stopOnMain(context: Context, tellHub: Boolean) {
        val ring = ringing
        NotificationManagerCompat.from(context).cancel(Notifs.ID_RING)
        if (ring == null) return
        Prefs.silencedRing = ring.id
        silence(context)
        if (tellHub) thread(name = "ring-stop") { runCatching { Hub.stopRing() } }
    }

    private fun silence(context: Context) {
        main.removeCallbacks(timeout)
        runCatching { player?.stop() }
        player?.release()
        player = null
        tone?.release()
        tone = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
        NotificationManagerCompat.from(context).cancel(Notifs.ID_RING)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ringing = null
        listeners.toList().forEach { it() }
    }

    private fun play(context: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // the user's alarm sound, else their ringtone, else any sound at all
        for (type in intArrayOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, type)
                ?: RingtoneManager.getDefaultUri(type) ?: continue
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                player?.release()
                player = null
            }
        }
        // no sound files at all (some stripped ROMs): a plain beep on the alarm stream
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, MAX_RING_MS.toInt())
    }

    private fun vibrate(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, ring: Ring) {
        if (!Notifs.allowed(context)) return
        val full = PendingIntent.getActivity(context, 0,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(context, 0,
            Intent(context, StopRingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notifs.CH_RING)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(context.getString(R.string.ring_title, ring.from))
            .setContentText(context.getString(R.string.ring_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(0xFF38BDF8.toInt())
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), stop)
            .setTimeoutAfter(MAX_RING_MS + 5_000)
            .build()
        NotificationManagerCompat.from(context).notify(Notifs.ID_RING, n)
    }
}

/** The notification's Stop button (and swiping the ring away). */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ringer.stop(context, tellHub = true)
    }
}
