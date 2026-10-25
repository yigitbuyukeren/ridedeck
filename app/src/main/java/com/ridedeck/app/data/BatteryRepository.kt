package com.ridedeck.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BatteryState(val percent: Int = 0, val tempC: Float = 0f, val charging: Boolean = false, val hasReading: Boolean = false)

class BatteryRepository(private val context: Context) {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = publish(intent)
    }

    private fun publish(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) _state.value = BatteryState(level * 100 / scale, temp, charging, hasReading = true)
    }

    fun start() {
        if (registered) return
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        sticky?.let(::publish)
        registered = true
    }
}
