package dev.droplet.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifs {
    const val CH_CONNECTION = "connection"
    const val CH_TRANSFERS = "transfers"
    const val CH_RING = "ring"
    const val CH_INBOX = "inbox"

    const val ID_CONNECTION = 1
    const val ID_UPLOAD = 2
    const val ID_RING = 3
    private var nextId = 1000

    @Synchronized
    fun newId(): Int = nextId++

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_CONNECTION, context.getString(R.string.ch_connection), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.ch_connection_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_TRANSFERS, context.getString(R.string.ch_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.ch_transfers_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_RING, context.getString(R.string.ch_ring), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_ring_desc)
                    // the ringer plays its own alarm sound at full volume
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_INBOX, context.getString(R.string.ch_inbox), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ch_inbox_desc)
                },
            )
        )
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Opens the web app, optionally at a path like "/#chat-abc123". */
    fun openApp(context: Context, path: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (path != null) intent.putExtra(MainActivity.EXTRA_PATH, path)
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun openRemote(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 2, Intent(context, RemoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun sendClipboard(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 3, Intent(context, ClipSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun openSettings(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 1, Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
}
