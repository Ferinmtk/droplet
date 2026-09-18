package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject

/** Reports battery level and charging to the hub, on change and every few minutes. */
object StatusReporter {
    private const val MIN_GAP_MS = 60_000L
    private const val MAX_GAP_MS = 5 * 60_000L

    private var lastLevel = -1
    private var lastCharging: Boolean? = null
    private var lastSent = 0L

    /** Call from a background thread. Cheap when there's nothing new to say. */
    @Synchronized
    fun maybeSend(context: Context) {
        if (!Hub.hasDevice()) return
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val now = SystemClock.elapsedRealtime()
        val due = lastSent == 0L || charging != lastCharging || now - lastSent > MAX_GAP_MS ||
            (pct != lastLevel && now - lastSent > MIN_GAP_MS)
        if (!due) return
        runCatching {
            Hub.postJson("/api/phone/status", JSONObject().put("battery", pct).put("charging", charging))
        }.onSuccess {
            lastLevel = pct
            lastCharging = charging
            lastSent = now
        }
    }
}
