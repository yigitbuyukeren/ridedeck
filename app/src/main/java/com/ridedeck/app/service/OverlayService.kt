package com.ridedeck.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import com.ridedeck.app.data.LocationRepository
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.SpeedUtil
import com.ridedeck.app.util.UnitMath
import com.ridedeck.app.util.hasPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Slim speed/clock strip drawn over other apps (e.g. fullscreen Maps). */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var location: LocationRepository
    private var bar: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speedView: TextView
    private lateinit var clockView: TextView

    private val tick = object : Runnable {
        override fun run() {
            val loc = location.state.value
            // RideDeck already shows the speed itself: the strip is only for when another app is on screen.
            bar?.visibility = if (appInForeground) View.GONE else View.VISIBLE
            val fresh = System.currentTimeMillis() - loc.fixAtMs < 5000
            val shown = if (fresh) location.displayKmh() else null
            speedView.text = if (shown == null) getString(R.string.overlay_speed_unknown)
            else getString(R.string.overlay_speed_fmt, UnitMath.speed(shown.roundToInt(), Prefs.milesUnit(this@OverlayService)))
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A location foreground service may only start with the location permission (Android 14+ throws otherwise).
        if (!Settings.canDrawOverlays(this) || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (bar == null) addBar()
        location.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        location = LocationRepository(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        location.stop()
        bar?.let { windowManager.removeView(it) }
        bar = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "overlay"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channelId, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RideDeck")
            .setContentText(getString(R.string.overlay_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun addBar() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        speedView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        clockView = TextView(this).apply {
            setTextColor(0xFF8E8E93.toInt()); textSize = 20f; setPadding(dp(18), 0, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt()); cornerRadius = dp(26).toFloat()
            }
            addView(speedView)
            addView(clockView)
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        windowManager.addView(layout, params)
        bar = layout
    }

    companion object {
        /** Set by the activity: true between onResume and onPause. */
        @Volatile var appInForeground = false

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context) || !context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
