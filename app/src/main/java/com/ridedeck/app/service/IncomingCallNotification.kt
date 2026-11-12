package com.ridedeck.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.ridedeck.app.R

/**
 * The ringing-call notification, separate from Telecom so its intent wiring can be tested without a
 * call. It always carries an ordinary content intent: when Android 14 denies the full-screen intent,
 * a tap on the heads-up or in the shade still opens RideDeck.
 */
internal object IncomingCallNotification {
    const val CHANNEL = "calls"
    const val ID = 42

    fun build(
        context: Context,
        caller: String?,
        contentIntent: PendingIntent,
        fullScreenIntent: PendingIntent?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.call_incoming))
            .setContentText(caller ?: context.getString(R.string.phone_unknown))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
        if (fullScreenIntent != null) builder.setFullScreenIntent(fullScreenIntent, true)
        return builder.build()
    }
}
