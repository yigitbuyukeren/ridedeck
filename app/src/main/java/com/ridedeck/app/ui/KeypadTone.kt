package com.ridedeck.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The short DTMF "beep" the phone's own dialer makes on every key. One [ToneGenerator] is shared by the
 * whole keypad and released when the screen goes away, so a tap does not allocate anything.
 */
@Composable
fun rememberKeypadTone(): (Char) -> Unit {
    val context = LocalContext.current
    val tone = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 80) }.getOrNull()
    }
    DisposableEffect(tone) {
        onDispose { runCatching { tone?.release() } }
    }
    val audio = remember { context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    return remember(tone, audio) {
        { key ->
            val id = when (key) {
                '1' -> ToneGenerator.TONE_DTMF_1
                '2' -> ToneGenerator.TONE_DTMF_2
                '3' -> ToneGenerator.TONE_DTMF_3
                '4' -> ToneGenerator.TONE_DTMF_4
                '5' -> ToneGenerator.TONE_DTMF_5
                '6' -> ToneGenerator.TONE_DTMF_6
                '7' -> ToneGenerator.TONE_DTMF_7
                '8' -> ToneGenerator.TONE_DTMF_8
                '9' -> ToneGenerator.TONE_DTMF_9
                '0' -> ToneGenerator.TONE_DTMF_0
                '*' -> ToneGenerator.TONE_DTMF_S
                '#' -> ToneGenerator.TONE_DTMF_P
                else -> 0
            }
            // A silenced phone stays quiet, exactly like the dialer.
            if (id != 0 && tone != null && audio?.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                runCatching { tone.startTone(id, 120) }
            }
        }
    }
}
