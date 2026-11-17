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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a delivered command must do to the service that owns this ride. */
internal enum class RideServiceCommand { START, STOP, IGNORE, STOP_SERVICE }

/** Started only by a visible, user-initiated ride. No boot receiver, sticky restart, overlay or background-location permission. */
class RideTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracking get() = (application as RideDeckApplication).rideTracking
    private var request = 0L
    private var lastStartId = 0
    private var tickJob: Job? = null

    // stopSelfResult, not stopSelf: a queued newer start keeps the service alive.
    private val close: () -> Unit = {
        tickJob?.cancel()
        tickJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val controller = tracking.session
        val incoming = intent?.getLongExtra(EXTRA_REQUEST, 0L) ?: 0L
        // A duplicate/stale command must not rewrite the ongoing notification's stop token.
        val notificationToken = when {
            controller.accepts(incoming) -> incoming
            request != 0L -> request
            else -> incoming
        }
        // startForegroundService() obliges every delivery to promote, including a stop that arrives
        // before its start. Do it first; permission/prerequisite failures are handled after promotion.
        val promotionFailure = promote(notificationToken)
        when (decide(intent?.action, incoming, request,
            accepts = controller.accepts(incoming),
            active = controller.isActive(incoming),
            running = controller.state.value.running)) {
            RideServiceCommand.START -> start(incoming, promotionFailure)
            RideServiceCommand.STOP -> {
                tickJob?.cancel()
                tickJob = null
                controller.stop()
            }
            RideServiceCommand.IGNORE -> Unit
            RideServiceCommand.STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun start(incoming: Long, promotionFailure: RideFailure?) {
        val controller = tracking.session
        request = incoming
        tracking.closeService = close
        if (promotionFailure != null) {
            // The placeholder promotion already met the startForegroundService() deadline; end the ride.
            // A total promotion failure has no notification at all, so stop now rather than after the flush.
            controller.stop(promotionFailure)
            close()
            return
        }
        val failure = tracking.prerequisiteFailure()
        if (failure != null) { controller.stop(failure); return }
        try {
            if (!controller.serviceStarted(incoming)) { close(); return }
        } catch (_: SecurityException) {
            controller.stop(RideFailure.PERMISSION)
            return
        } catch (_: RuntimeException) {
            controller.stop(RideFailure.START_FAILED)
            return
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (controller.isActive(incoming)) {
                controller.tick(tracking.location.state.value, tracking.trips.error.value, tracking.navigating)
                delay(1_000L)
            }
        }
    }

    /**
     * Posts a valid foreground notification and returns null, or returns the failure that should end
     * the ride. A location foreground service without while-in-use location throws on Android 14+;
     * the untyped placeholder still satisfies the startForegroundService() obligation before we stop.
     */
    private fun promote(token: Long): RideFailure? {
        val notification = notification(token)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            null
        } catch (_: SecurityException) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
                RideFailure.PERMISSION
            } catch (_: RuntimeException) {
                RideFailure.START_FAILED
            }
        } catch (_: RuntimeException) {
            RideFailure.START_FAILED
        }
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
            .setSmallIcon(R.drawable.ic_stat_ridedeck)
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
        tickJob?.cancel()
        tickJob = null
        scope.cancel()
        if (tracking.closeService === close) tracking.closeService = null
        // Releases the service's GPS owner through the controller and never touches a newer token.
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

        /**
         * Commands are serial on the main thread. A command only stops the service when no ride it
         * owns is running, so a late stop or a duplicate start cannot kill a newer session.
         */
        internal fun decide(
            action: String?,
            incoming: Long,
            request: Long,
            accepts: Boolean,
            active: Boolean,
            running: Boolean,
        ): RideServiceCommand = when {
            action == ACTION_START && accepts -> RideServiceCommand.START
            action == ACTION_STOP && active -> RideServiceCommand.STOP
            active || (request != 0L && running) -> RideServiceCommand.IGNORE
            else -> RideServiceCommand.STOP_SERVICE
        }

        fun launch(context: Context, request: Long) {
            context.startForegroundService(Intent(context, RideTrackingService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_REQUEST, request))
        }
    }
}
