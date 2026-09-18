package dev.droplet.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/**
 * "Stay connected": a foreground service that asks the hub every 15 seconds
 * whether another device is ringing this phone, and (optionally) whether
 * files or messages have arrived for it. It also reports the battery.
 *
 * Polls are driven by exact alarms rather than a sleeping thread, so they
 * keep happening when the screen is off and the CPU would otherwise sleep;
 * each poll holds a short wake lock. In deep Doze Android stretches the
 * alarms out (to roughly a minute), which is the price of not holding the
 * CPU awake all day.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkUp = true
    private var state = ""
    private var pollText = ""
    private var live = Live.Snapshot()
    private var lastInboxCheck = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(this, Notifs.ID_CONNECTION, notification(getString(R.string.conn_connecting)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) {
            // Android refused a foreground start from the background (e.g. a
            // sticky restart); the next alarm or app launch brings it back
            stopSelf()
            return
        }
        running = true
        // keep the route fresh: move between the Wi-Fi and the tailnet as the phone does
        Router.hold(ROUTER_TAG)
        watchNetwork()
        // the live connection (remote control) lives as long as this service
        Live.hold(LIVE_TAG)
        scope.launch {
            Live.state.collect { s ->
                val came = s.connected && !live.connected
                live = s
                showState()
                // rings aren't on the socket; check at once after (re)connecting
                if (came) kick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.stayConnected || !Prefs.hasHub) {
            stopSelf()
            return START_NOT_STICKY
        }
        kick()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Live.release(LIVE_TAG)
        Router.release(ROUTER_TAG)
        cancelAlarm(this)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    /** Run a poll now, unless one is already running. */
    private fun kick() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droplet:poll").apply { acquire(30_000) }
            try {
                pollOnce()
                // while ringing, check often so Stop on another device silences this one quickly
                while (Ringer.ringing != null && networkUp) {
                    delay(3_000)
                    pollOnce()
                }
            } finally {
                if (wake.isHeld) wake.release()
                scheduleNext()
            }
        }
    }

    private fun pollOnce() {
        if (!networkUp) {
            setState(getString(R.string.conn_no_network))
            return
        }
        if (!Hub.hasDevice()) {
            setState(getString(R.string.conn_unnamed))
            return
        }
        // an exact alarm is what still fires in Doze; use it to retry a dropped socket too
        Live.kick()
        try {
            val ring = Hub.ring()
            when {
                ring != null && ring.id != Prefs.silencedRing -> Ringer.start(this, ring)
                ring == null && Ringer.ringing != null -> Ringer.stop(this, tellHub = false)
            }
            val now = SystemClock.elapsedRealtime()
            if (Prefs.notifyInbox && now - lastInboxCheck > 30_000) {
                lastInboxCheck = now
                checkInbox(Hub.files())
            }
            StatusReporter.maybeSend(this)
            setState(getString(R.string.conn_ok, Router.shortLabel(this) ?: ""))
        } catch (e: PairingRequired) {
            setState(getString(R.string.conn_not_let_in, Router.hubLabel()))
        } catch (e: HubUnreachable) {
            setState(Router.describe(this))
        } catch (e: IOException) {
            setState(getString(R.string.conn_unreachable))
        } catch (e: Exception) {
            setState(e.message ?: getString(R.string.conn_unreachable))
        }
    }

    // --- files and messages for this phone ----------------------------------

    @SuppressLint("MissingPermission")
    private fun checkInbox(files: JSONObject) {
        val inbox = files.optJSONArray("inbox")
        val items = (0 until (inbox?.length() ?: 0)).map { inbox!!.getJSONObject(it) }
        val unreadJson = files.optJSONObject("unread") ?: JSONObject()
        val unread = unreadJson.keys().asSequence().associateWith { unreadJson.optInt(it) }
        val names = Hub.devices(files).associate { it.id to it.name }

        val primed = Prefs.inboxPrimed
        val seen = Prefs.seenInbox
        val seenUnread = Prefs.seenUnread
        // the page flashes these itself while it's open
        val quiet = !primed || MainActivity.visible || !Notifs.allowed(this)
        val nm = NotificationManagerCompat.from(this)

        if (!quiet) {
            items.filter { it.optString("name") !in seen }
                .groupBy { it.optString("from", "someone") }
                .forEach { (from, list) ->
                    val fileNames = list.map { it.optString("name") }
                    val n = inboxNotification(
                        resources.getQuantityString(R.plurals.inbox_files, list.size, from, list.size),
                        fileNames.take(3).joinToString(", ") + if (fileNames.size > 3) " +${fileNames.size - 3}" else "",
                        "/", "inbox-$from")
                    nm.notify("inbox-$from".hashCode(), n)
                }
            for ((id, count) in unread) {
                if (count > (seenUnread[id] ?: 0)) {
                    val who = names[id] ?: "a device"
                    nm.notify("chat-$id".hashCode(), inboxNotification(
                        getString(R.string.inbox_message, who),
                        resources.getQuantityString(R.plurals.inbox_unread, count, count),
                        "/#chat-$id", "chat-$id"))
                }
            }
        }
        // read elsewhere: drop the stale notification
        for (id in seenUnread.keys) if ((unread[id] ?: 0) == 0) nm.cancel("chat-$id".hashCode())

        Prefs.seenInbox = items.map { it.optString("name") }.toSet()
        Prefs.seenUnread = unread
        Prefs.inboxPrimed = true
    }

    private fun inboxNotification(title: String, text: String, path: String, key: String): Notification =
        NotificationCompat.Builder(this, Notifs.CH_INBOX)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF38BDF8.toInt())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(Notifs.openApp(this, path, key.hashCode()))
            .build()

    // --- the persistent notification -----------------------------------------

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, Notifs.CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(getString(R.string.conn_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(Notifs.openApp(this))
            .addAction(R.drawable.ic_remote, getString(R.string.remote_action), Notifs.openRemote(this))
            .apply {
                if (Prefs.capClipboard) addAction(R.drawable.ic_clip, getString(R.string.clip_action), Notifs.sendClipboard(this@ConnectionService))
            }
            .addAction(0, getString(R.string.settings), Notifs.openSettings(this))
            .build()

    /** What the polls found; shown unless the live connection has something better to say. */
    private fun setState(text: String) {
        pollText = text
        showState()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun showState() {
        val text = if (live.connected) {
            live.describe(this) + (Router.shortLabel(this)?.let { " · $it" } ?: "")
        } else pollText.ifEmpty { getString(R.string.conn_connecting) }
        val key = text + Prefs.capClipboard
        if (key == state) return
        state = key
        if (Notifs.allowed(this)) NotificationManagerCompat.from(this).notify(Notifs.ID_CONNECTION, notification(text))
    }

    // --- scheduling ------------------------------------------------------------

    private fun scheduleNext() {
        if (!running) return
        // offline: the network callback kicks a poll when it's back; the long
        // alarm is only a safety net
        val delay = if (networkUp) POLL_MS else 5 * 60_000L
        val am = getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delay
        val pi = alarmIntent(this)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkUp = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkUp = true
                kick()
            }

            override fun onLost(network: Network) {
                networkUp = cm.activeNetwork?.let { it != network } == true
                if (!networkUp) setState(getString(R.string.conn_no_network))
            }
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    companion object {
        private const val POLL_MS = 15_000L
        private const val ACTION_POLL = "dev.droplet.app.POLL"
        private const val LIVE_TAG = "service"
        private const val ROUTER_TAG = "service"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            cancelAlarm(context)
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        private fun alarmIntent(context: Context): PendingIntent =
            PendingIntent.getService(context, 0,
                Intent(context, ConnectionService::class.java).setAction(ACTION_POLL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }
    }
}
