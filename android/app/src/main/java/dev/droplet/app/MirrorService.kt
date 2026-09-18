package dev.droplet.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notification mirroring: forwards this phone's notifications (and their
 * removal) to the hub, where other devices show them in the Phone card.
 * The system runs this once the user grants Notification access.
 */
class MirrorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val posted = LinkedHashMap<String, JSONObject>()
    private val removed = LinkedHashSet<String>()
    /** Keys the hub knows about, so removals are only sent for those. */
    private val forwarded = LinkedHashSet<String>()
    private var sync = false
    private var flushJob: Job? = null
    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        connected = true
        // start the hub's list for this phone afresh with what's showing now
        val now = runCatching { activeNotifications }.getOrNull().orEmpty()
        synchronized(lock) {
            posted.clear()
            removed.clear()
            forwarded.clear()
            sync = true
            now.sortedBy { it.postTime }.forEach { sbn -> describe(sbn)?.let { posted[sbn.key] = it } }
        }
        scheduleFlush(0)
        // media control rides on this access: announce it
        Live.refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        Live.refresh()
    }

    override fun onDestroy() {
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val item = describe(sbn) ?: return
        synchronized(lock) {
            removed.remove(sbn.key)
            posted.remove(sbn.key)  // re-insert so the newest is last
            posted[sbn.key] = item
            while (posted.size > MAX_BATCH) posted.remove(posted.keys.first())
        }
        noteSeen(sbn.packageName)
        scheduleFlush(FLUSH_DELAY_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) {
            if (posted.remove(sbn.key) == null && sbn.key !in forwarded) return
            removed += sbn.key
        }
        scheduleFlush(FLUSH_DELAY_MS)
    }

    /** What the hub gets for a notification, or null if it shouldn't be mirrored. */
    private fun describe(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in ALWAYS_SKIP || pkg in Prefs.excluded) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // "local only" is an app saying: don't bridge this to other devices
        if (n.flags and Notification.FLAG_LOCAL_ONLY != 0) return null
        if (n.category in SKIP_CATEGORIES) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
            .ifEmpty { extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty() }
        if (title.isEmpty() && text.isEmpty()) return null
        return JSONObject()
            .put("key", sbn.key)
            .put("package", pkg)
            .put("app", label(sbn))
            .put("title", title.take(200))
            .put("text", text.take(1000))
            .put("time", sbn.postTime / 1000.0)
    }

    private fun label(sbn: StatusBarNotification): String = labels.getOrPut(sbn.packageName) {
        val pm = packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrNull()
            // apps this one can't see (package visibility) still carry their info in the notification
            ?: sbn.notification.extras?.let { BundleCompat.getParcelable(it, "android.appInfo", ApplicationInfo::class.java) }
                ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: sbn.packageName
    }

    private fun noteSeen(pkg: String) {
        val seen = Prefs.seenPackages
        if (pkg !in seen) Prefs.seenPackages = (seen + pkg).toList().takeLast(200).toSet()
    }

    private fun scheduleFlush(after: Long) {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(after)
            flush()
        }
    }

    private suspend fun flush() {
        if (Prefs.hubUrl == null || !Hub.hasDevice()) {
            // no named device to file them under; nothing to do until there is
            return
        }
        val (items, gone, fresh) = synchronized(lock) {
            Triple(posted.values.toList(), removed.toList(), sync)
        }
        if (items.isEmpty() && gone.isEmpty() && !fresh) return
        val body = JSONObject().put("posted", JSONArray(items)).put("removed", JSONArray(gone))
        if (fresh) body.put("sync", true)
        val ok = runCatching { Hub.postJson("/api/phone/notifications", body) }.isSuccess
        synchronized(lock) {
            if (ok) {
                items.forEach { item ->
                    val key = item.getString("key")
                    if (posted[key] === item) posted.remove(key)
                    forwarded += key
                }
                gone.forEach { removed.remove(it); forwarded.remove(it) }
                while (forwarded.size > 200) forwarded.remove(forwarded.first())
                if (fresh) sync = false
            }
        }
        StatusReporter.maybeSend(this)
        if (!ok) {
            // hub unreachable: keep the batch (capped) and try again later
            delay(RETRY_MS)
            flushJob = null
            scheduleFlush(0)
        }
    }

    companion object {
        private const val FLUSH_DELAY_MS = 1_500L
        private const val RETRY_MS = 60_000L
        private const val MAX_BATCH = 50

        @Volatile
        var connected = false
            private set

        /** Noisy system sources nobody wants on another screen. */
        private val ALWAYS_SKIP = setOf("android", "com.android.systemui", "com.android.providers.downloads")
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        )

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, MirrorService::class.java).flattenToString()
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { it == me } == true
        }

        fun accessSettingsIntent(context: Context): android.content.Intent =
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, MirrorService::class.java).flattenToString())
            } else {
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

    }
}
