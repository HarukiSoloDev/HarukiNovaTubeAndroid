package com.harukisolodev.harukistream.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/** Handles notification actions without cancelling a whole WorkManager lane chain. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_QUEUE_ITEM) return
        val queueId = intent.getStringExtra(EXTRA_QUEUE_ID).orEmpty()
        if (queueId.isBlank()) return

        val store = DownloadQueueStore(context)
        val stored = store.get(queueId)
        store.updateState(queueId, "CANCELLED")

        // Stop active network reads immediately. The durable CANCELLED state also makes
        // a queued/not-yet-started worker exit successfully without cancelling descendants.
        DownloadCancellationRegistry.cancel(queueId)

        // Remove the foreground progress notification immediately instead of waiting for
        // the next worker heartbeat. WorkManager will clean up its foreground service after
        // the worker observes the cancellation and exits.
        stored?.workId?.let { workId ->
            runCatching {
                val workerId = UUID.fromString(workId)
                val notificationId = (workerId.hashCode() and 0x3FFFFFFF).coerceAtLeast(1)
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_QUEUE_ITEM = "com.harukisolodev.harukistream.action.CANCEL_QUEUE_ITEM"
        const val EXTRA_QUEUE_ID = "queue_id"
    }
}
