package dev.droplet.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Cancel on a file's progress notification (docs/mesh.md §9.10): stops the
 * transfer, deletes what had arrived, and tells the other device.
 */
class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        // null when called directly rather than by a broadcast
        val done: PendingResult? = goAsync()
        Mesh.scope.launch {
            try {
                runCatching { Mesh.cancelTransfer(id) }.onFailure { Mesh.log("mesh: cancelling $id: ${it.message}") }
            } finally {
                done?.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
