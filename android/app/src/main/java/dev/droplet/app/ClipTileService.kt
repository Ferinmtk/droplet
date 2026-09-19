package dev.droplet.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A quick-settings tile that sends the phone's clipboard to the other devices. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            // an action, not a toggle
            state = if (Prefs.capClipboard && (Prefs.hasHub || Prefs.noHub && Prefs.meshEnabled)) Tile.STATE_INACTIVE
                else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch() {
        val intent = Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
