package com.ridedeck.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import com.ridedeck.app.RideDeckApplication
import com.ridedeck.app.data.RideFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Started only by a visible, user-initiated ride. No boot receiver, sticky restart, overlay or background-location permission. */
class RideTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracking get() = (application as RideDeckApplication).rideTracking
    private var request = 0L
    private val close: () -> Unit = { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = tracking.session
        val incoming = intent?.getLongExtra(EXTRA_REQUEST, 0L) ?: 0L
        if (intent?.action == ACTION_STOP) {
            if (controller.isActive(incoming)) controller.stop()
            else if (request == 0L) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !controller.accepts(incoming)) {
            if (controller.isActive(incoming)) return START_NOT_STICKY
            if (request == 0L) stopSelf()
            return START_NOT_STICKY
        }
        request = incoming
        tracking.closeService = close
        val failure = tracking.prerequisiteFailure()
        if (failure != null) { controller.stop(failure); return START_NOT_STICKY }
        try {
            // Promotion precedes any database work or GPS subscription; permission races are caught here.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(incoming),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            if (!controller.serviceStarted(incoming)) { close(); return START_NOT_STICKY }
        } catch (_: SecurityException) {
            controller.stop(RideFailure.PERMISSION)
            return START_NOT_STICKY
        } catch (_: RuntimeException) {
            controller.stop(RideFailure.START_FAILED)
            return START_NOT_STICKY
        }
        scope.launch {
            while (controller.isActive(incoming)) {
                controller.tick(tracking.location.state.value, tracking.trips.error.value, tracking.navigating)
                delay(1_000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(token: Long): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ride_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 31,
            Intent(this, RideTrackingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REQUEST, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.ride_active))
            .setContentText(getString(R.string.ride_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.ride_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (tracking.closeService === close) tracking.closeService = null
        tracking.session.serviceDestroyed(request)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "ride_tracking"
        const val NOTIFICATION_ID = 30
        const val ACTION_OPEN = "com.ridedeck.app.OPEN_RIDE"
        internal const val ACTION_START = "com.ridedeck.app.START_RIDE"
        internal const val ACTION_STOP = "com.ridedeck.app.STOP_RIDE"
        internal const val EXTRA_REQUEST = "ride_request"

        fun launch(context: Context, request: Long) {
            context.startForegroundService(Intent(context, RideTrackingService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_REQUEST, request))
        }
    }
}
