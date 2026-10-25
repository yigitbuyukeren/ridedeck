package com.ridedeck.app.data

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.KeyEvent
import com.ridedeck.app.util.hasPermission

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
 * "Hey RideDeck", experimental and off by default: while the dashboard is on screen it listens with the
 * on-device recogniser only (never the network one) for the wake phrase, restarting after every short session.
 * It needs Android 13+ with the Turkish offline model; without them it stays silent.
 */
class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit,
    private val canRun: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var session: AudioSessionManager.Token? = null
    private var failures = 0
    private var lastWakeAt = 0L

    companion object {
        private val PHRASES = listOf("hey ridedeck", "hey ride deck", "hey raydek", "hey ray dek")
        private const val RESTART_MS = 400L
        private const val MAX_RETRY_MS = 30_000L
        private const val WAKE_COOLDOWN_MS = 2_000L

        /** True when the heard text contains the wake phrase (loosely, since the recogniser spells it any way). */
        fun matches(heard: String): Boolean {
            val t = Turkish.normalize(heard).replace('ş', 's')
            return PHRASES.any { t.contains(it.replace('ş', 's')) } || t.replace(" ", "").contains("heyridedeck")
        }

        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    fun start() {
        if (running || !eligible()) return
        running = true
        paused = false
        lateinit var token: AudioSessionManager.Token
        token = AudioSessionManager.process.acquire(AudioSessionManager.Owner.ANDROID_WAKE) { suspendSession(token) }
        session = token
        listenOnce()
    }

    fun stop() {
        running = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        session?.let(::releaseSession)
    }

    /** The assistant is using the microphone: wait. */
    fun pause() {
        paused = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        session?.let(::releaseSession)
    }

    fun resume() {
        if (!paused) return
        if (!eligible()) { stop(); return }
        paused = false
        if (!running) return
        lateinit var token: AudioSessionManager.Token
        token = AudioSessionManager.process.acquire(AudioSessionManager.Owner.ANDROID_WAKE) { suspendSession(token) }
        session = token
        handler.postDelayed(::listenOnce, RESTART_MS)
    }

    private fun suspendSession(token: AudioSessionManager.Token) {
        if (session != token) return
        paused = true
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        releaseSession(token)
    }

    private fun releaseSession(token: AudioSessionManager.Token) {
        if (session != token) return
        session = null
        AudioSessionManager.process.release(token)
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    @TargetApi(33)
    private fun listenOnce() {
        val token = session ?: return
        if (!running || paused || !AudioSessionManager.process.isCurrent(token)) return
        if (!eligible()) { stop(); return }
        destroyRecognizer()
        try {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
            private fun check(bundle: Bundle?): Boolean {
                if (session != token || !AudioSessionManager.process.isCurrent(token)) return false
                if (!eligible()) { stop(); return false }
                val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (heard.any(::matches) && System.currentTimeMillis() - lastWakeAt >= WAKE_COOLDOWN_MS) {
                    lastWakeAt = System.currentTimeMillis()
                    failures = 0
                    pause()
                    onWake()
                    return true
                }
                return false
            }

            override fun onPartialResults(partialResults: Bundle?) { check(partialResults) }
            override fun onResults(results: Bundle?) { if (!check(results)) { failures = 0; again(RESTART_MS) } }
            override fun onError(error: Int) {
                if (session != token) return
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) stop()
                else again(VoiceRetryBackoff.delay(++failures, RESTART_MS, MAX_RETRY_MS))
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            )
        } catch (_: RuntimeException) {
            suspendSession(token)
        }
    }

    private fun again(delayMs: Long) {
        if (!running || paused || session?.let(AudioSessionManager.process::isCurrent) != true) return
        if (!eligible()) { stop(); return }
        handler.postDelayed({ if (eligible()) listenOnce() else stop() }, delayMs)
    }

    private fun eligible(): Boolean =
        available(context) && context.hasPermission(Manifest.permission.RECORD_AUDIO) && canRun()

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
