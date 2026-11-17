package com.ridedeck.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ridedeck.app.RideDeckApplication

/**
 * The "İyiyim" / "Kapat" buttons of the SOS notification. Not exported: the only senders are the
 * PendingIntents this app put on its own notification, so no other app can cancel an alert.
 */
class SosActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val safety = (context.applicationContext as? RideDeckApplication)?.rideSafety ?: return
        try {
            when (intent.action) {
                ACTION_CANCEL -> safety.cancelFromNotification()
                ACTION_DISMISS -> safety.dismissFromNotification()
            }
        } catch (e: RuntimeException) {
            // A receiver must never take the process down; the countdown keeps its own alarm going.
            Log.w("RideDeck", "SOS notification action failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.ridedeck.app.action.SOS_CANCEL"
        const val ACTION_DISMISS = "com.ridedeck.app.action.SOS_DISMISS"
    }
}
