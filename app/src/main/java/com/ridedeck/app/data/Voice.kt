package com.ridedeck.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.ridedeck.app.util.Prefs

/**
 * What may interrupt what. A lower priority never cuts into a higher one: it waits (briefly) or is dropped;
 * a higher one flushes whatever is playing. Equal priorities replace each other (a newer turn prompt supersedes).
 */
enum class SpeechPriority { MESSAGE, WEATHER, SPEED_LIMIT, NAVIGATION, SOS }

/** Anything that can say a sentence out loud; lets the navigation logic run in plain unit tests. */
interface Speaker {
    fun setSpeechRate(rate: Float) {}
    fun speak(text: String)
    /** Same as [speak]; implementations that queue use the priority, plain test doubles may ignore it. */
    fun speak(text: String, priority: SpeechPriority) = speak(text)
    fun stop()
    fun shutdown()
}

data class TtsVoice(val name: String, val language: String, val requiresNetwork: Boolean)
data class TtsStartupPlan(val ready: Boolean, val voices: List<TtsVoice>, val selected: TtsVoice?)

object TtsVoicePolicy {
    /** A saved voice of another language is ignored, so a phone language change falls back to the policy. */
    fun choose(preferredName: String?, voices: List<TtsVoice>, language: String): TtsVoice? {
        val matching = voices.filter { it.language.equals(language, ignoreCase = true) }
        return matching.firstOrNull { it.name == preferredName }
            ?: matching.firstOrNull { !it.requiresNetwork }
            ?: matching.firstOrNull()
    }

    fun startupPlan(initialized: Boolean, preferredName: String?, voices: List<TtsVoice>, language: String): TtsStartupPlan {
        if (!initialized) return TtsStartupPlan(ready = false, voices = emptyList(), selected = null)
        val available = voices.filter { it.language.equals(language, ignoreCase = true) }.distinctBy { it.name }.sortedBy { it.name }
        return TtsStartupPlan(ready = true, voices = available, selected = choose(preferredName, available, language))
    }
}

/** Turn-by-turn announcements that duck the music while speaking. */
class Voice(context: Context, private val onVoices: (List<String>) -> Unit = {}) : Speaker {
    private val appContext = context.applicationContext
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop() }
    }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
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
            runCatching { onVoices(emptyList()) }
            return@synchronized
        }
        runCatching { tts.setAudioAttributes(attributes) }
        val progressListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        }
        if (runCatching { tts.setOnUtteranceProgressListener(progressListener); true }.getOrDefault(false).not()) {
            ready = false
            runCatching { onVoices(emptyList()) }
            return@synchronized
        }
        val languageReady = runCatching { tts.setLanguage(GuidanceLanguage.currentLocale()) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        runCatching { tts.setSpeechRate(Prefs.voiceSpeechRate(context)) }

        // Android exposes the voice list only after a successful init. Publish readiness before
        // using the same public selection API that settings changes use.
        ready = true
        val platformVoices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
        val voiceOptions = platformVoices.map {
            TtsVoice(it.name, it.locale.language, it.isNetworkConnectionRequired)
        }
        val plan = TtsVoicePolicy.startupPlan(true, Prefs.voiceTtsName(context), voiceOptions, GuidanceLanguage.currentLocale().language)
        val chosen = plan.selected
        val selected = chosen?.let { option -> platformVoices.firstOrNull { it.name == option.name } }
        val voiceReady = selected != null && runCatching { tts.setVoice(selected) == TextToSpeech.SUCCESS }.getOrDefault(false)
        ready = voiceReady || languageReady
        if (voiceReady) Prefs.setVoiceTtsName(context, chosen?.name)
        runCatching { onVoices(if (ready) plan.voices.map { it.name } else emptyList()) }
    }

    override fun setSpeechRate(rate: Float) { if (ready && !closed) runCatching { tts.setSpeechRate(rate.coerceIn(0.75f, 1.25f)) } }

    fun availableVoices(): List<String> = if (!ready || closed) emptyList() else runCatching {
        tts.voices.orEmpty().filter { it.locale.language.equals(GuidanceLanguage.currentLocale().language, ignoreCase = true) }.map { it.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun selectVoice(name: String?): Boolean {
        if (!ready || closed) return false
        if (name == null) return runCatching { tts.setLanguage(GuidanceLanguage.currentLocale()) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        val voice = runCatching { tts.voices.orEmpty().firstOrNull { it.name == name && it.locale.language.equals(GuidanceLanguage.currentLocale().language, true) } }.getOrNull() ?: return false
        return runCatching { tts.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    private var timeout: Runnable? = null
    private val session = SpeechSession(
        requestFocus = { runCatching { audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false) },
        abandonFocus = { runCatching { audio.abandonAudioFocusRequest(focusRequest) }; Unit },
        speak = { text, id -> runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS }.getOrDefault(false) },
        stopEngine = { if (ready) runCatching { tts.stop() }; Unit },
        changed = { id ->
            timeout?.let(mainHandler::removeCallbacks)
            timeout = null
            if (id != null) {
                if (!noisyRegistered) {
                    noisyRegistered = runCatching {
                        ContextCompat.registerReceiver(appContext, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                    }.isSuccess
                }
                timeout = Runnable { sessionTimeout(id) }.also { mainHandler.postDelayed(it, 60_000L) }
            } else if (noisyRegistered) {
                runCatching { appContext.unregisterReceiver(noisy) }
                noisyRegistered = false
            }
        },
    )

    private fun sessionTimeout(id: String) { session.timeout(id) }

    fun suspendForCall(suspended: Boolean) = onMain { if (!closed) session.suspendForCall(suspended) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    override fun speak(text: String) = speak(text, SpeechPriority.NAVIGATION)

    override fun speak(text: String, priority: SpeechPriority) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { speak(text, priority) }; return }
        if (!ready || closed || text.isBlank()) return
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_RINGTONE) return
        session.announce(text, priority)
    }

    private fun finished(id: String?) {
        // TTS reports completion on a binder thread; settle the bookkeeping on main.
        onMain {
            if (!closed) session.complete(id)
        }
    }

    override fun stop() {
        onMain { if (!closed) stopOnMain() }
    }

    private fun stopOnMain() {
        session.stop()
    }

    override fun shutdown() {
        val shouldShutdown = synchronized(initLock) {
            if (closed) false else { closed = true; true }
        }
        if (!shouldShutdown) return
        onMain {
            stopOnMain()
            session.close()
            ready = false
            runCatching { tts.shutdown() }
        }
    }
}
