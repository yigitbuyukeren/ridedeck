package com.ridedeck.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ridedeck.app.data.CompassMath

/**
 * The phone's compass heading in degrees (null without the sensor), listening only while the screen is resumed.
 * The state is written ~10 times a second; read it inside the per-frame loop, not in composition.
 */
@Composable
internal fun rememberCompassHeading(): State<Float?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val heading = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context, lifecycle) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                val dt = if (last == 0L) 1f else (now - last) / 1000f
                SensorManager.getRotationMatrixFromVector(matrix, e.values)
                @Suppress("DEPRECATION")
                val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                val raw = CompassMath.heading(matrix, rotation) ?: return
                val prev = heading.value
                last = now
                heading.value = if (prev == null) raw else CompassMath.ease(prev, raw, dt)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        var registered = false
        fun start() {
            if (sensor != null && !registered) registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        fun stop() {
            if (registered) { sm?.unregisterListener(listener); registered = false }
            last = 0L
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    return heading
}
