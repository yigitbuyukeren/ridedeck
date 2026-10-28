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
    fun setSpeechRate(rate: Float) {}
    fun speak(text: String)
    fun stop()
    fun shutdown()
}

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

    /** Utterances handed to the engine and not yet finished; audio focus is held while any remain. */
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var counter = 0

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    override fun speak(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { speak(text) }; return }
        if (!ready || closed || text.isBlank()) return
        // QUEUE_FLUSH cuts the previous prompt off: its completion callback no longer matters.
        pending.clear()
        val id = "speech-${counter++}"
        pending.add(id)
        runCatching { audio.requestAudioFocus(focusRequest) }
        val status = runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (status != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(id: String?) {
        // TTS reports completion on a binder thread; settle the bookkeeping on main.
        onMain {
            if (!closed && id != null && pending.remove(id) && pending.isEmpty()) {
                runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            }
        }
    }

    override fun stop() {
        onMain { if (!closed) stopOnMain() }
    }

    private fun stopOnMain() {
        pending.clear()
        if (ready) runCatching { tts.stop() }
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
    }

    override fun shutdown() {
        val shouldShutdown = synchronized(initLock) {
            if (closed) false else { closed = true; true }
        }
        if (!shouldShutdown) return
        onMain {
            stopOnMain()
            ready = false
            runCatching { tts.shutdown() }
        }
    }
}
