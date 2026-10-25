package com.ridedeck.app.data

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.content.Context
import android.os.Handler
import android.os.Looper

enum class MicRouteTarget { PHONE, BLUETOOTH }

object BluetoothMicRoutePolicy {
    fun select(enabled: Boolean, bluetoothInputAvailable: Boolean): MicRouteTarget =
        if (enabled && bluetoothInputAvailable) MicRouteTarget.BLUETOOTH else MicRouteTarget.PHONE
}

/** Tracks headset add/remove events for one active voice session; phone mic is the safe fallback. */
class BluetoothMicRouter(
    private val audio: AudioManager,
    private val enabled: () -> Boolean,
) {
    private var active = false
    private var registered = false
    private var routed = false
    var target: MicRouteTarget = MicRouteTarget.PHONE
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        if (active) { refresh(); return }
        active = true
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())); registered = true }
        }
        refresh()
    }

    fun stop() {
        active = false
        if (registered) runCatching { audio.unregisterAudioDeviceCallback(callback) }
        registered = false
        if (routed && Build.VERSION.SDK_INT >= 31) runCatching { audio.clearCommunicationDevice() }
        routed = false
        target = MicRouteTarget.PHONE
    }

    private fun refresh() {
        if (!active || Build.VERSION.SDK_INT < 31) return
        val input = runCatching {
            audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }.getOrNull()
        target = BluetoothMicRoutePolicy.select(enabled(), input != null)
        routed = if (target == MicRouteTarget.BLUETOOTH && input != null) {
            runCatching { audio.setCommunicationDevice(input) }.getOrDefault(false)
        } else {
            if (routed) runCatching { audio.clearCommunicationDevice() }
            false
        }
        if (!routed) target = MicRouteTarget.PHONE
    }

    companion object {
        /** Null means Android could not report device availability; callers should omit that fact from speech. */
        fun isBluetoothInputAvailable(context: Context): Boolean? = runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val devices = if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices
            else audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        }.getOrNull()
    }
}
