package com.ridedeck.app.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ridedeck.app.util.Prefs

/** Two short pulses standing in for a spoken turn instruction while a phone call occupies the audio. */
object NavCue {
    private val pattern = longArrayOf(0, 120, 90, 120)

    /** Copy of the off/on waveform in milliseconds; exposed so a JVM test can pin the two-pulse shape. */
    internal fun waveform(): LongArray = pattern.copyOf()

    fun pulse(context: Context) {
        if (!Prefs.hapticsEnabled(context)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        } ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
