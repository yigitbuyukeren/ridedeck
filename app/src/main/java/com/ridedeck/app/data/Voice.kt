package com.ridedeck.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.os.Handler
import android.os.Looper
import com.ridedeck.app.util.Prefs

/** Anything that can say a sentence out loud; lets the navigation logic run in plain unit tests. */
interface Speaker {
    fun formatResponse(text: String, kind: VoiceResponseKind = VoiceResponseKind.SUCCESS): String = text
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
enum class VoiceResponseKind { SUCCESS, INFORMATION, PARTIAL, ERROR, ACTION }
data class VoiceActionResponse(val message: String, val kind: VoiceResponseKind = VoiceResponseKind.SUCCESS)

data class TurkishTtsVoice(val name: String, val language: String, val requiresNetwork: Boolean)
data class TurkishTtsStartupPlan(val ready: Boolean, val voices: List<TurkishTtsVoice>, val selected: TurkishTtsVoice?)

object TurkishTtsVoicePolicy {
    fun choose(preferredName: String?, voices: List<TurkishTtsVoice>): TurkishTtsVoice? {
        val turkish = voices.filter { it.language.equals("tr", ignoreCase = true) }
        return turkish.firstOrNull { it.name == preferredName }
            ?: turkish.firstOrNull { !it.requiresNetwork }
            ?: turkish.firstOrNull()
    }

    fun startupPlan(initialized: Boolean, preferredName: String?, voices: List<TurkishTtsVoice>): TurkishTtsStartupPlan {
        if (!initialized) return TurkishTtsStartupPlan(ready = false, voices = emptyList(), selected = null)
        val available = voices.filter { it.language.equals("tr", ignoreCase = true) }.distinctBy { it.name }.sortedBy { it.name }
        return TurkishTtsStartupPlan(ready = true, voices = available, selected = choose(preferredName, available))
    }
}

object TtsContinuationPolicy {
    fun <T> transferAfterFlush(callbacks: List<Pair<Long, T>>): T? =
        callbacks.maxByOrNull { it.first }?.second
}

object VoiceResponseFormatter {
    fun format(
        message: String,
        mode: VoiceResponseMode,
        kind: VoiceResponseKind = VoiceResponseKind.SUCCESS,
    ): String = when (mode) {
        // A short action acknowledgement is fine; facts and answers must keep their useful content.
        VoiceResponseMode.MINIMAL -> when (kind) {
            VoiceResponseKind.SUCCESS, VoiceResponseKind.ACTION -> "Tamam."
            VoiceResponseKind.INFORMATION, VoiceResponseKind.PARTIAL, VoiceResponseKind.ERROR -> firstSentence(message)
        }
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
    @Volatile private var closed = false
    private val initLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status -> mainHandler.post { initializeTts(context, status) } }
    }

    private fun initializeTts(context: Context, status: Int) = synchronized(initLock) {
        if (closed) return@synchronized
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            runCatching { onTurkishVoices(emptyList()) }
            return@synchronized
        }
        runCatching { tts.setAudioAttributes(attributes) }
        val progressListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            // A navigation prompt may cut a queued sentence off; its follow-up must still run.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        }
        if (runCatching { tts.setOnUtteranceProgressListener(progressListener); true }.getOrDefault(false).not()) {
            ready = false
            runCatching { onTurkishVoices(emptyList()) }
            return@synchronized
        }
        runCatching { tts.language = Locale.forLanguageTag("tr-TR") }
        runCatching { tts.setSpeechRate(Prefs.voiceSpeechRate(context)) }

        // Android exposes the voice list only after a successful init. Publish readiness before
        // using the same public selection API that settings changes use.
        ready = true
        val platformVoices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
        val voiceOptions = platformVoices.map {
            TurkishTtsVoice(it.name, it.locale.language, it.isNetworkConnectionRequired)
        }
        val plan = TurkishTtsVoicePolicy.startupPlan(true, Prefs.voiceTtsName(context), voiceOptions)
        val chosen = plan.selected
        if (chosen != null) {
            val voice = platformVoices.firstOrNull { it.name == chosen.name }
            if (voice == null || runCatching { tts.voice = voice; true }.getOrDefault(false).not()) {
                runCatching { tts.language = Locale.forLanguageTag("tr-TR") }
            }
            if (Prefs.voiceTtsName(context) != chosen.name) Prefs.setVoiceTtsName(context, chosen.name)
        } else {
            // Keep the engine's Turkish locale even when it does not enumerate installed voices.
            runCatching { tts.language = Locale.forLanguageTag("tr-TR") }
        }
        ready = plan.ready
        runCatching { onTurkishVoices(plan.voices.map { it.name }) }
    }

    override fun setSpeechRate(rate: Float) { if (ready && !closed) runCatching { tts.setSpeechRate(rate.coerceIn(0.75f, 1.25f)) } }

    fun availableTurkishVoices(): List<String> = if (!ready || closed) emptyList() else runCatching {
        tts.voices.orEmpty().filter { it.locale.language.equals("tr", ignoreCase = true) }.map { it.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun selectTurkishVoice(name: String?): Boolean {
        if (!ready || closed) return false
        if (name == null) return runCatching { tts.voice = null; tts.language = Locale.forLanguageTag("tr-TR"); true }.getOrDefault(false)
        val voice = runCatching { tts.voices.orEmpty().firstOrNull { it.name == name && it.locale.language.equals("tr", true) } }.getOrNull() ?: return false
        return runCatching { tts.voice = voice; true }.getOrDefault(false)
    }

    private val thenCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val utteranceSessions = java.util.concurrent.ConcurrentHashMap<String, AudioSessionManager.Token>()
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

    private fun submit(id: String, text: String, queueMode: Int) {
        val status = runCatching { tts.speak(text, queueMode, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (status != TextToSpeech.SUCCESS) finished(id)
    }

    override fun speak(text: String) {
        if (!ready || closed || text.isBlank()) return
        // Move the active follow-up onto the interrupting navigation prompt. If the old queued
        // answer is flushed, its continuation must wait until this higher-priority utterance ends.
        val continuation = TtsContinuationPolicy.transferAfterFlush(
            thenCallbacks.entries.mapNotNull { (id, callback) ->
                id.substringAfterLast('-').toLongOrNull()?.let { it to callback }
            },
        )
        utteranceSessions.clear()
        thenCallbacks.clear()
        val id = "speech-${thenCounter++}"
        continuation?.let { thenCallbacks[id] = it }
        trackUtterance(id)
        submit(id, text, TextToSpeech.QUEUE_FLUSH)
    }

    override fun speakQueued(text: String) {
        if (!ready || closed || text.isBlank()) return
        val id = "queued-${thenCounter++}"
        trackUtterance(id)
        submit(id, text, TextToSpeech.QUEUE_ADD)
    }

    override fun speakThen(text: String, then: () -> Unit) {
        if (!ready || closed || text.isBlank()) { then(); return }
        val id = "then-${thenCounter++}"
        thenCallbacks[id] = then
        trackUtterance(id)
        submit(id, text, TextToSpeech.QUEUE_ADD)
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
        if (ready) runCatching { tts.stop() }
        val token = speechSession
        speechSession = null
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        token?.let(AudioSessionManager.process::release)
    }

    override fun shutdown() {
        synchronized(initLock) { closed = true }
        stop()
        ready = false
        runCatching { tts.shutdown() }
    }
}
