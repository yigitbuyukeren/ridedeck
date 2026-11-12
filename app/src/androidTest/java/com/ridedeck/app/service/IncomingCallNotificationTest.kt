package com.ridedeck.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Builds the ringing notification from fake data only; no Telecom call or outgoing action is made. */
class IncomingCallNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Test
    fun deniedFullScreenIntentStillLeavesTheOrdinaryTapTarget() {
        val open = openIntent()

        val notification = IncomingCallNotification.build(
            context = context,
            caller = "Test Rider",
            contentIntent = open,
            fullScreenIntent = null,
        )

        assertSame(open, notification.contentIntent)
        assertNull(notification.fullScreenIntent)
        assertEquals("Test Rider", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun allowedFullScreenIntentKeepsTheSameOrdinaryTapTarget() {
        val open = openIntent()

        val notification = IncomingCallNotification.build(
            context = context,
            caller = null,
            contentIntent = open,
            fullScreenIntent = open,
        )

        assertSame(open, notification.contentIntent)
        assertSame(open, notification.fullScreenIntent)
        assertEquals(
            context.getString(R.string.phone_unknown),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }
}
