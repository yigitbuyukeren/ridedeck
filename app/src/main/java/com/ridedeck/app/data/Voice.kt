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
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Read-only runtime facts; null means the fact could not be observed. Control readiness stays private. */
data class TtsFacts(
    val engineInitialized: Boolean? = null,
    val languageAvailable: Boolean? = null,
    /** Null means the network requirement could not be observed; never treat it as offline-ready. */
    val selectedVoiceRequiresNetwork: Boolean? = null,
)

object TtsVoicePolicy {
    private fun code(value: String): String = Locale.forLanguageTag(value).language.ifBlank { value.lowercase() }

    /**
     * A saved voice of another language is ignored, so an app language change immediately falls back
     * to an installed voice of the new language rather than keeping the old one.
     */
    fun choose(preferredName: String?, voices: List<TtsVoice>, language: String): TtsVoice? {
        val wanted = code(language)
        val matching = voices.filter { code(it.language) == wanted }
        return matching.firstOrNull { it.name == preferredName }
            ?: matching.firstOrNull { !it.requiresNetwork }
            ?: matching.firstOrNull()
    }

    fun startupPlan(initialized: Boolean, preferredName: String?, voices: List<TtsVoice>, language: String): TtsStartupPlan {
        if (!initialized) return TtsStartupPlan(ready = false, voices = emptyList(), selected = null)
        val wanted = code(language)
        val available = voices.filter { code(it.language) == wanted }.distinctBy { it.name }.sortedBy { it.name }
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
    @Volatile private var engineReady = false
    @Volatile private var closed = false
    /** The app's current guidance language, kept even while the engine is still initializing. */
    @Volatile private var languageTag = GuidanceLanguage.current(appContext)
    @Volatile private var activeLanguageTag: String? = null
    /** Deferred while an SOS prompt plays so an app-language change never cuts an emergency message. */
    @Volatile private var pendingLanguageTag: String? = null
    private val initLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _runtimeFacts = MutableStateFlow(TtsFacts())
    val runtimeFacts: StateFlow<TtsFacts> = _runtimeFacts.asStateFlow()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status -> mainHandler.post { initializeTts(status) } }
    }

    /**
     * Re-targets the live engine after an Android app-language change. No engine re-creation or
     * process restart is needed: the current utterance is stopped and the new locale/voice is applied.
     */
    fun applyLanguage(tag: String) {
        val normalized = GuidanceLanguage.tag(tag)
        onMain {
            if (closed) return@onMain
            languageTag = normalized
            // A rapid second switch must replace the deferred target, not leave the previous one behind.
            pendingLanguageTag = normalized
            if (!engineReady) return@onMain
            if (activeLanguageTag == normalized && ready) {
                pendingLanguageTag = null
                return@onMain
            }
            // Never flush an SOS message just because the driver changed the app language.
            if (session.activePriority == SpeechPriority.SOS) return@onMain
            pendingLanguageTag = null
            session.stop()
            applyTtsLanguage(normalized, persistSelection = false)
        }
    }

    /** Applies a language change that waited for SOS speech to finish. Runs on main only. */
    private fun applyPendingLanguage() {
        val pending = pendingLanguageTag ?: return
        if (closed || !engineReady || session.activePriority != null) return
        pendingLanguageTag = null
        if (activeLanguageTag == pending && ready) return
        session.stop()
        applyTtsLanguage(pending, persistSelection = false)
    }

    private fun initializeTts(status: Int) = synchronized(initLock) {
        if (closed) return@synchronized
        if (status != TextToSpeech.SUCCESS) {
            engineReady = false
            ready = false
            _runtimeFacts.value = TtsFacts(engineInitialized = false)
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
            engineReady = false
            ready = false
            _runtimeFacts.value = TtsFacts(engineInitialized = false)
            runCatching { onVoices(emptyList()) }
            return@synchronized
        }
        engineReady = true
        // The latest language wins if the configuration changed while the engine was initializing.
        applyTtsLanguage(languageTag, persistSelection = true)
    }

    private fun applyTtsLanguage(tag: String, persistSelection: Boolean) {
        val locale = Locale.forLanguageTag(tag)
        val languageCode = GuidanceLanguage.languageCode(tag)
        val languageReady = runCatching { tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        runCatching { tts.setSpeechRate(Prefs.voiceSpeechRate(appContext)) }

        // Android exposes the voice list only after a successful init. Publish readiness before
        // using the same public selection API that settings changes use.
        ready = true
        val platformVoices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptyList())
        val voiceOptions = platformVoices.map {
            TtsVoice(it.name, it.locale.language, it.isNetworkConnectionRequired)
        }
        val plan = TtsVoicePolicy.startupPlan(true, Prefs.voiceTtsName(appContext), voiceOptions, languageCode)
        val chosen = plan.selected
        val selected = chosen?.let { option -> platformVoices.firstOrNull { it.name == option.name } }
        val voiceReady = selected != null && runCatching { tts.setVoice(selected) == TextToSpeech.SUCCESS }.getOrDefault(false)
        ready = voiceReady || languageReady
        activeLanguageTag = tag
        // The engine's active voice (including its default language voice) may be network-backed;
        // a failed read stays null so the assessment reports UNKNOWN rather than READY.
        val activeVoiceRequiresNetwork = runCatching { tts.voice?.isNetworkConnectionRequired }.getOrNull()
        _runtimeFacts.value = TtsFacts(
            engineInitialized = true,
            languageAvailable = languageReady || voiceReady,
            selectedVoiceRequiresNetwork = activeVoiceRequiresNetwork,
        )
        // A language switch must not overwrite the driver's explicit voice choice with a policy default.
        if (voiceReady && persistSelection) Prefs.setVoiceTtsName(appContext, chosen?.name)
        runCatching { onVoices(if (ready) plan.voices.map { it.name } else emptyList()) }
    }

    override fun setSpeechRate(rate: Float) { if (ready && !closed) runCatching { tts.setSpeechRate(rate.coerceIn(0.75f, 1.25f)) } }

    fun availableVoices(): List<String> = if (!ready || closed) emptyList() else runCatching {
        val code = GuidanceLanguage.languageCode(languageTag)
        tts.voices.orEmpty().filter { GuidanceLanguage.languageCode(it.locale.toLanguageTag()) == code }.map { it.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun selectVoice(name: String?): Boolean {
        if (!ready || closed) return false
        if (name == null) {
            val languageReady = runCatching { tts.setLanguage(Locale.forLanguageTag(languageTag)) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            if (languageReady) {
                val activeVoiceRequiresNetwork = runCatching { tts.voice?.isNetworkConnectionRequired }.getOrNull()
                _runtimeFacts.value = _runtimeFacts.value.copy(
                    languageAvailable = true,
                    selectedVoiceRequiresNetwork = activeVoiceRequiresNetwork,
                )
            }
            return languageReady
        }
        val code = GuidanceLanguage.languageCode(languageTag)
        val voice = runCatching {
            tts.voices.orEmpty().firstOrNull { GuidanceLanguage.languageCode(it.locale.toLanguageTag()) == code && it.name == name }
        }.getOrNull() ?: return false
        val voiceReady = runCatching { tts.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
        if (voiceReady) {
            _runtimeFacts.value = _runtimeFacts.value.copy(
                languageAvailable = true,
                selectedVoiceRequiresNetwork = voice.isNetworkConnectionRequired,
            )
        }
        return voiceReady
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
        if (closed || text.isBlank()) return
        // A language change deferred behind SOS speech lands as soon as the line is free.
        applyPendingLanguage()
        if (!ready) return
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_RINGTONE) return
        session.announce(text, priority)
    }

    private fun finished(id: String?) {
        // TTS reports completion on a binder thread; settle the bookkeeping on main.
        onMain {
            if (!closed) {
                session.complete(id)
                applyPendingLanguage()
            }
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
            engineReady = false
            ready = false
            _runtimeFacts.value = TtsFacts(engineInitialized = false)
            runCatching { tts.shutdown() }
        }
    }
}
