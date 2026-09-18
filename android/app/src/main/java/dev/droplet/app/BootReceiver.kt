package dev.droplet.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings "Stay connected" back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            Prefs.stayConnected && Prefs.hubUrl != null) {
            runCatching { ConnectionService.start(context) }
        }
    }
}
