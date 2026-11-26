package com.ridedeck.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.ridedeck.app.util.Units
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ridedeck.app.data.SosLockScreenPolicy
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.service.OverlayService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.ridedeck.app.ui.DashboardScreen
import com.ridedeck.app.ui.DeckTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Units.load(this)
        com.ridedeck.app.ui.MarkerStyle.load(this)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handleDialIntent(intent)
        // A hot phone dims itself (see ThermalGuard); a cool one keeps the system brightness.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.thermal.state, viewModel.sos.ui) { thermal, sos ->
                    // Never leave a dimmed screen during an SOS countdown.
                    sos.phase to if (sos.phase == SosPhase.COUNTDOWN) 1f else thermal.brightness
                }.distinctUntilChanged().collect { (phase, brightness) ->
                    window.attributes = window.attributes.also { it.screenBrightness = brightness }
                    // The countdown has to be readable over the keyguard; the dashboard must not be.
                    setSosOverLockScreen(SosLockScreenPolicy.showOverKeyguard(phase))
                }
            }
        }
        // The dashboard is made for a landscape vehicle mount.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContent {
            val isDay by viewModel.theme.isDay.collectAsStateWithLifecycle()
            DeckTheme(isDay) { DashboardScreen(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    /** Another app's tel: link: the phone screen opens with the number on the keypad. */
    private fun handleDialIntent(intent: Intent?) {
        if (intent?.action == com.ridedeck.app.service.RideTrackingService.ACTION_OPEN) viewModel.openTrips.value = true
        if (intent?.action == Intent.ACTION_DIAL && intent.data?.scheme == "tel") {
            viewModel.dialRequest.value = intent.data?.schemeSpecificPart.orEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.appInForeground = true
        viewModel.onResume()
    }

    override fun onStart() {
        super.onStart()
        // The full-screen intent can launch this activity while the device is locked; set the
        // keyguard flags before the first frame rather than waiting for the flow collector below.
        setSosOverLockScreen(SosLockScreenPolicy.showOverKeyguard(viewModel.sos.ui.value.phase))
        (application as RideDeckApplication).rideTracking.setScreenVisible(this, true)
        (application as RideDeckApplication).rideNavigation.setScreenVisible(this, true)
        (application as RideDeckApplication).rideSafety.setScreenVisible(true)
    }

    override fun onStop() {
        (application as RideDeckApplication).rideSafety.setScreenVisible(false)
        (application as RideDeckApplication).rideNavigation.setScreenVisible(this, false)
        (application as RideDeckApplication).rideTracking.setScreenVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        (application as RideDeckApplication).rideNavigation.setScreenVisible(this, false)
        (application as RideDeckApplication).rideTracking.setScreenVisible(this, false)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        OverlayService.appInForeground = false
        viewModel.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Only the countdown may sit over the keyguard and light the screen. API 26 lacks the activity
     * setters, so the window flags are used there; IDLE and the result phases clear both again.
     */
    private fun setSosOverLockScreen(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(active)
            setTurnScreenOn(active)
        } else {
            window.setFlags(
                if (active) WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON else 0,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
