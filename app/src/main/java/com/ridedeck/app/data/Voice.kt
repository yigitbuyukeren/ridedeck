package com.ridedeck.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import com.ridedeck.app.util.Prefs

/** Anything that can say a sentence out loud; lets the navigation logic run in plain unit tests. */
interface Speaker {
    fun formatResponse(text: String, kind: VoiceResponseKind = VoiceResponseKind.ACTION): String = text
    fun setSpeechRate(rate: Float) {}
    fun speak(text: String)

    /** Says [text] after whatever is being said (a turn-by-turn prompt is never cut off by it). */
    fun speakQueued(text: String) = speak(text)

    /** Says [text] after whatever is being said, then calls [then] (at once when there is nothing to say). */
    fun speakThen(text: String, then: () -> Unit) {
        speakQueued(text)
        then()
    }

    fun stop()
    fun shutdown()
}

enum class VoiceResponseMode { MINIMAL, NORMAL, DETAILED }
enum class VoiceResponseKind { ACTION, INFORMATION }

object VoiceResponseFormatter {
    fun format(
        message: String,
        mode: VoiceResponseMode,
        kind: VoiceResponseKind = VoiceResponseKind.ACTION,
    ): String = when (mode) {
        // A short action acknowledgement is fine; facts and answers must keep their useful content.
        VoiceResponseMode.MINIMAL -> if (kind == VoiceResponseKind.ACTION) "Tamam." else firstSentence(message)
        VoiceResponseMode.NORMAL -> firstSentence(message)
        VoiceResponseMode.DETAILED -> message
    }

    private fun firstSentence(message: String): String =
        message.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().ifBlank { message }
}

/** Turn-by-turn announcements that duck the music while speaking. */
class Voice(context: Context, private val onTurkishVoices: (List<String>) -> Unit = {}) : Speaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            tts.language = Locale.getDefault()
            runCatching { tts.language = Locale.forLanguageTag("tr-TR") }
            runCatching { tts.setSpeechRate(Prefs.voiceSpeechRate(context)) }
            val turkish = availableTurkishVoices()
            onTurkishVoices(turkish)
            Prefs.voiceTtsName(context)?.let(::selectTurkishVoice)
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                // A navigation prompt may cut a queued sentence off; its follow-up must still run.
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
            })
            ready = true
        }
    }

    override fun setSpeechRate(rate: Float) { if (ready) runCatching { tts.setSpeechRate(rate.coerceIn(0.75f, 1.25f)) } }

    fun availableTurkishVoices(): List<String> = if (!ready) emptyList() else runCatching {
        tts.voices.orEmpty().filter { it.locale.language.equals("tr", ignoreCase = true) }.map { it.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun selectTurkishVoice(name: String?): Boolean {
        if (!ready) return false
        if (name == null) return runCatching { tts.voice = null; tts.language = Locale.forLanguageTag("tr-TR"); true }.getOrDefault(false)
        val voice = runCatching { tts.voices.orEmpty().firstOrNull { it.name == name && it.locale.language.equals("tr", true) } }.getOrNull() ?: return false
        return runCatching { tts.voice = voice; true }.getOrDefault(false)
    }

    private val thenCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val utteranceSessions = java.util.concurrent.ConcurrentHashMap<String, AudioSessionManager.Token>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var thenCounter = 0
    private var speechSession: AudioSessionManager.Token? = null

    private fun acquireSpeechSession(): AudioSessionManager.Token {
        speechSession?.takeIf(AudioSessionManager.process::isCurrent)?.let { return it }
        lateinit var token: AudioSessionManager.Token
        token = AudioSessionManager.process.acquire(AudioSessionManager.Owner.TTS) { revokeSpeechSession(token) }
        speechSession = token
        runCatching { audio.requestAudioFocus(focusRequest) }
        return token
    }

    private fun revokeSpeechSession(token: AudioSessionManager.Token) {
        if (speechSession != token) return
        speechSession = null
        utteranceSessions.entries.removeAll { it.value == token }
        thenCallbacks.clear()
        if (ready) runCatching { tts.stop() }
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
    }

    private fun trackUtterance(id: String) { utteranceSessions[id] = acquireSpeechSession() }

    override fun speak(text: String) {
        if (!ready || text.isBlank()) return
        // Remove old continuations before QUEUE_FLUSH invokes their stop callbacks.
        utteranceSessions.clear()
        thenCallbacks.clear()
        val id = "speech-${thenCounter++}"
        trackUtterance(id)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun speakQueued(text: String) {
        if (!ready || text.isBlank()) return
        val id = "queued-${thenCounter++}"
        trackUtterance(id)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    override fun speakThen(text: String, then: () -> Unit) {
        if (!ready || text.isBlank()) { then(); return }
        val id = "then-${thenCounter++}"
        thenCallbacks[id] = then
        trackUtterance(id)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun finished(id: String?) {
        val token = id?.let(utteranceSessions::remove) ?: return
        id.let(thenCallbacks::remove)?.let { callback -> mainHandler.post(callback) }
        if (utteranceSessions.values.none { it == token } && speechSession == token) {
            speechSession = null
            runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            AudioSessionManager.process.release(token)
        }
    }

    override fun stop() {
        utteranceSessions.clear()
        thenCallbacks.clear()
        if (ready) tts.stop()
        val token = speechSession
        speechSession = null
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        token?.let(AudioSessionManager.process::release)
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
    }
}
