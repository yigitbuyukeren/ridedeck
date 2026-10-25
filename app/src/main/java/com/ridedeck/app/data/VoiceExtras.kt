package com.ridedeck.app.data

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent

/** Whether Turkish recognition works without a network on this phone. */
enum class OfflineVoice { UNKNOWN, INSTALLED, MISSING, UNSUPPORTED }

object OfflineVoiceCheck {
    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")

    /** Asks the recogniser which languages it has on the device (Android 13+); [onResult] runs on the main thread. */
    fun check(context: Context, onResult: (OfflineVoice) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) { onResult(OfflineVoice.UNKNOWN); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.checkRecognitionSupport(intent(), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val turkish = { l: List<String> -> l.any { it.startsWith("tr", ignoreCase = true) } }
                onResult(
                    when {
                        turkish(support.installedOnDeviceLanguages) -> OfflineVoice.INSTALLED
                        turkish(support.supportedOnDeviceLanguages) || turkish(support.pendingOnDeviceLanguages) -> OfflineVoice.MISSING
                        else -> OfflineVoice.UNSUPPORTED
                    }
                )
                r.destroy()
            }

            override fun onError(error: Int) { onResult(OfflineVoice.UNKNOWN); r.destroy() }
        })
    }

    /** Asks the system to download the Turkish on-device model (Android 13+). */
    fun download(context: Context) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.triggerModelDownload(intent())
        Handler(Looper.getMainLooper()).postDelayed({ r.destroy() }, 5000)
    }
}

/**
 * A double press of a Bluetooth headset's play/pause (or hook) key starts the voice assistant. While it is on, the
 * dashboard holds a media session so it sees the key; a single press is passed on to the music after a short wait.
 */
class VoiceButtonTrigger(
    private val context: Context,
    private val onDoublePress: () -> Unit,
    private val onSinglePress: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var lastDownAt = 0L
    private var pendingSingle: Runnable? = null

    fun start() {
        if (session != null) return
        val s = MediaSession(context, "RideDeckVoiceButton")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
                if (event.keyCode !in KEYS) return false
                val now = event.eventTime
                if (now - lastDownAt <= DOUBLE_MS && pendingSingle != null) {
                    handler.removeCallbacks(pendingSingle!!)
                    pendingSingle = null
                    lastDownAt = 0L
                    onDoublePress()
                } else {
                    lastDownAt = now
                    pendingSingle = Runnable { pendingSingle = null; onSinglePress() }.also { handler.postDelayed(it, DOUBLE_MS) }
                }
                return true
            }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                .build()
        )
        s.isActive = true
        session = s
    }

    fun stop() {
        pendingSingle?.let { handler.removeCallbacks(it) }
        pendingSingle = null
        session?.isActive = false
        session?.release()
        session = null
    }

    private companion object {
        const val DOUBLE_MS = 450L
        val KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
}
