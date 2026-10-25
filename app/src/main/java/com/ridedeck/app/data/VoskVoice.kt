package com.ridedeck.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import com.ridedeck.app.util.Prefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

enum class VoskModelState { MISSING, DOWNLOADING, READY, ERROR }
data class VoskDownloadProgress(val downloaded: Long = 0, val total: Long = -1) {
    val fraction: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Holds the optional model outside the APK. Vosk's native library is packaged; its 35 MB model is opt-in. */
class VoskModelStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "speech/vosk-tr-0.3")
    private val archive = File(app.cacheDir, "vosk-tr-0.3.zip.part")
    private val installing = File(app.filesDir, "speech/vosk-tr-0.3.installing")
    private val _state = MutableStateFlow(if (valid(root)) VoskModelState.READY else VoskModelState.MISSING)
    val state: StateFlow<VoskModelState> = _state
    private val _progress = MutableStateFlow(VoskDownloadProgress())
    val progress: StateFlow<VoskDownloadProgress> = _progress
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    @Volatile private var cached: Model? = null

    fun isReady(): Boolean = valid(root)

    suspend fun download() = withContext(Dispatchers.IO) {
        if (isReady()) { _state.value = VoskModelState.READY; return@withContext }
        _state.value = VoskModelState.DOWNLOADING
        _progress.value = VoskDownloadProgress()
        _error.value = null
        var phase = "download"
        try {
            archive.parentFile?.mkdirs()
            archive.delete()
            Http.download(MODEL_URL, archive) { downloaded, total -> _progress.value = VoskDownloadProgress(downloaded, total) }
            phase = "install"
            installing.deleteRecursively()
            installing.mkdirs()
            ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', entry.name)
                    if (relative.isBlank()) { zip.closeEntry(); continue }
                    val target = File(installing, relative).canonicalFile
                    require(target.toPath().startsWith(installing.canonicalFile.toPath())) { "Invalid model archive" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                }
            }
            require(valid(installing)) { "The downloaded model is incomplete" }
            root.deleteRecursively()
            check(installing.renameTo(root)) { "Could not install speech model" }
            archive.delete()
            _state.value = VoskModelState.READY
        } catch (e: Exception) {
            Log.e("RideDeck", "Vosk model $phase failed (${e.javaClass.simpleName})")
            _error.value = e.message?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName
            archive.delete()
            installing.deleteRecursively()
            _state.value = if (isReady()) VoskModelState.READY else VoskModelState.ERROR
            throw e
        }
    }

    @Synchronized fun openModel(): Model? {
        if (!isReady()) return null
        return cached ?: try { Model(root.absolutePath).also { cached = it } }
        catch (e: Exception) { Log.e("RideDeck", "Vosk model open failed (${e.javaClass.simpleName})"); throw e }
    }

    @Synchronized fun delete() {
        cached?.close()
        cached = null
        root.deleteRecursively()
        _state.value = VoskModelState.MISSING
    }

    fun refresh() { _state.value = if (isReady()) VoskModelState.READY else VoskModelState.MISSING }

    // The Turkish small model is Vosk's v1 layout (final.mdl + mfcc.conf at the root).
    // Newer models use the v2 am/ and conf/ directories.
    private fun valid(dir: File): Boolean =
        (File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile) ||
            (File(dir, "final.mdl").isFile && File(dir, "mfcc.conf").isFile)

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
    }
}

/** Vosk implementation of the same one-shot interface used by the assistant's test fakes. */
class VoskVoiceInput(
    private val context: Context,
    private val models: VoskModelStore,
    private val biasing: () -> List<String>,
) : VoiceAssistant.SpeechListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val micRouter = BluetoothMicRouter(audio) { Prefs.voiceHelmetMic(context) }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
    @Volatile private var service: SpeechService? = null
    @Volatile private var cancelled = false
    private var quiet = false
    private var onPartial: (String) -> Unit = {}
    private var onResult: (VoiceHeard) -> Unit = {}
    private var latest = ""
    private var timeout: Runnable? = null
    @Volatile private var session: AudioSessionManager.Token? = null

    override fun available(): Boolean = models.isReady()
    override fun start(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = begin(false, false, onPartial, onResult)
    override fun startDictation(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = begin(false, true, onPartial, onResult)
    override fun startQuiet(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = begin(true, false, onPartial, onResult)

    private fun begin(quiet: Boolean, dictation: Boolean, partial: (String) -> Unit, result: (VoiceHeard) -> Unit) {
        cancel()
        lateinit var token: AudioSessionManager.Token
        val owner = if (quiet) AudioSessionManager.Owner.FOLLOW_UP_STT else AudioSessionManager.Owner.VOSK_STT
        token = AudioSessionManager.process.acquire(owner) { cancelSession(token) }
        session = token
        this.quiet = quiet
        onPartial = partial
        onResult = result
        latest = ""
        cancelled = false
        audio.requestAudioFocus(focus)
        micRouter.start()
        worker.execute {
            try {
                if (cancelled || !AudioSessionManager.process.isCurrent(token)) return@execute
                val model = models.openModel() ?: error("Vosk model is not installed")
                if (cancelled || !AudioSessionManager.process.isCurrent(token)) return@execute
                val recognizer = if (dictation) Recognizer(model, 16_000f) else Recognizer(model, 16_000f, commandGrammar())
                recognizer.setMaxAlternatives(5)
                val speech = SpeechService(recognizer, 16_000f)
                service = speech
                if (cancelled || !AudioSessionManager.process.isCurrent(token)) {
                    if (service === speech) service = null
                    runCatching { speech.stop() }
                    runCatching { speech.shutdown() }
                    return@execute
                }
                if (!quiet) main.post { VoiceInputTone.play(context, false) }
                speech.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        if (!AudioSessionManager.process.isCurrent(token)) return
                        parse(hypothesis).firstOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
                            latest = text
                            main.post { if (!cancelled && AudioSessionManager.process.isCurrent(token)) onPartial(text) }
                        }
                    }
                    override fun onResult(hypothesis: String?) {
                        if (!AudioSessionManager.process.isCurrent(token)) return
                        val heard = parse(hypothesis)
                        heard.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
                            latest = text
                            main.post { if (!cancelled && AudioSessionManager.process.isCurrent(token)) onPartial(text) }
                            armFinish(token)
                        }
                    }
                    override fun onFinalResult(hypothesis: String?) { finish(token, parse(hypothesis)) }
                    override fun onError(exception: Exception?) { finish(token, latest.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(), VoiceFailure.ENGINE) }
                    override fun onTimeout() { finish(token, latest.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(), VoiceFailure.TIMEOUT) }
                })
                armNoSpeechTimeout(token)
            } catch (e: Exception) {
                Log.e("RideDeck", "Vosk recognition failed (${e.javaClass.simpleName})")
                finish(token, emptyList(), VoiceFailure.ENGINE)
            }
        }
    }

    private fun armFinish(token: AudioSessionManager.Token) = main.post {
        if (!AudioSessionManager.process.isCurrent(token)) return@post
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { finish(token, latest.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()) }
            .also { main.postDelayed(it, END_SILENCE_MS) }
    }

    private fun armNoSpeechTimeout(token: AudioSessionManager.Token) = main.post {
        if (!AudioSessionManager.process.isCurrent(token)) return@post
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { finish(token, emptyList()) }.also { main.postDelayed(it, MAX_LISTEN_MS) }
    }

    private fun finish(token: AudioSessionManager.Token, alternatives: List<String>, failure: VoiceFailure? = null) = main.post {
        if (cancelled || !AudioSessionManager.process.isCurrent(token)) return@post
        cancelled = true
        timeout?.let(main::removeCallbacks); timeout = null
        stopService(token)
        if (!quiet) VoiceInputTone.play(context, true)
        onResult(VoiceHeard(alternatives.distinct().take(5), failure = failure))
    }

    override fun cancel() {
        session?.let(::cancelSession)
    }

    private fun cancelSession(token: AudioSessionManager.Token) {
        if (session != token) return
        cancelled = true
        timeout?.let(main::removeCallbacks); timeout = null
        stopService(token)
    }

    private fun stopService(token: AudioSessionManager.Token) {
        if (session != token) return
        session = null
        val running = service
        service = null
        if (running != null) { runCatching { running.stop() }; runCatching { running.shutdown() } }
        micRouter.stop()
        runCatching { audio.abandonAudioFocusRequest(focus) }
        AudioSessionManager.process.release(token)
    }

    private fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        json.optString("partial").trim().takeIf(String::isNotBlank)?.let { return listOf(it) }
        val alternatives = json.optJSONArray("alternatives") ?: return listOf(json.optString("text").trim())
        return (0 until alternatives.length()).mapNotNull { i -> alternatives.optJSONObject(i)?.optString("text")?.trim() }
            .filter(String::isNotBlank)
    }

    private fun commandGrammar(): String {
        val phrases = LinkedHashSet(COMMAND_PHRASES)
        biasing().asSequence().map(String::trim).filter(String::isNotBlank).distinct().take(MAX_NAMES).forEach { name ->
            phrases += name
            phrases += listOf("git $name", "$name'e git", "$name'a git", "$name ara", "$name'yi ara", "$name'i ara",
                "$name'ye mesaj at", "$name'e mesaj at", "$name aç", "aç $name")
        }
        return JSONArray().apply { phrases.forEach(::put); put("[unk]") }.toString()
    }

    companion object {
        private const val END_SILENCE_MS = 1_250L
        private const val MAX_LISTEN_MS = 12_000L
        private const val MAX_NAMES = 100
        private val COMMAND_PHRASES = listOf(
            "eve git", "işe git", "eve götür", "işe götür", "rotayı başlat", "navigasyonu bitir", "rotayı durdur",
            "haritayı aç", "ayarları aç", "yakındaki benzinliği bul", "benzinlik ara", "kafe ara", "eczane ara",
            "hastane ara", "otopark ara", "sonraki şarkıyı çal", "önceki şarkıyı çal", "müziği aç", "müziği durdur",
            "sesi aç", "sesi kıs", "hava nasıl", "yarın hava nasıl", "saat kaç", "pil kaç", "hızım kaç",
            "kaç dakika sonra varırım", "sıradaki dönüş ne", "başka rota bul", "otoyollardan kaçın", "ücretli yollardan kaçın",
            "burayı ev olarak kaydet", "burayı iş olarak kaydet", "son mesajı oku", "cevap yaz", "evet", "hayır",
            "iptal", "vazgeç", "tekrar et", "geri al", "sus", "yirmi dakika sonra hatırlat",
        )
    }
}

/** Keeps the Android recognizer as the default while allowing Vosk to be selected after downloading its model. */
class SelectableVoiceInput(
    private val context: Context,
    private val models: VoskModelStore,
    private val biasing: () -> List<String>,
) : VoiceAssistant.SpeechListener {
    private val system = VoiceInput(context, biasing)
    private val vosk = VoskVoiceInput(context, models, biasing)
    private fun selected(): VoiceAssistant.SpeechListener =
        if (Prefs.voiceEngine(context) == ENGINE_VOSK && models.isReady()) vosk else system

    override fun available(): Boolean = selected().available()
    override fun start(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = selected().start(onPartial, onResult)
    override fun startDictation(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = selected().startDictation(onPartial, onResult)
    override fun startQuiet(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = selected().startQuiet(onPartial, onResult)
    override fun cancel() { vosk.cancel(); system.cancel() }

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_VOSK = "vosk"
    }
}

/** Vosk's streaming grammar narrows the always-on decoder to the wake phrase. */
class VoskWakeWord(private val context: Context, private val models: VoskModelStore, private val onWake: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var service: SpeechService? = null
    @Volatile private var session: AudioSessionManager.Token? = null
    @Volatile private var failures = 0
    @Volatile private var lastWakeAt = 0L

    fun start() {
        if (active || !models.isReady()) return
        active = true
        lateinit var token: AudioSessionManager.Token
        token = AudioSessionManager.process.acquire(AudioSessionManager.Owner.VOSK_WAKE) { suspendSession(token) }
        session = token
        worker.execute {
            try {
                if (!active || !AudioSessionManager.process.isCurrent(token)) return@execute
                val model = models.openModel() ?: error("Vosk model is not installed")
                if (!active || !AudioSessionManager.process.isCurrent(token)) return@execute
                val grammar = "[\"hey ridedeck\", \"hey ride deck\", \"hey raydek\", \"hey ray dek\", \"[unk]\"]"
                val recognizer = Recognizer(model, 16_000f, grammar)
                val speech = SpeechService(recognizer, 16_000f)
                service = speech
                if (!active || !AudioSessionManager.process.isCurrent(token)) { stopService(); return@execute }
                speech.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { if (isCurrent(token)) checkWake(hypothesis) }
                    override fun onResult(hypothesis: String?) { if (isCurrent(token)) checkWake(hypothesis) }
                    override fun onFinalResult(hypothesis: String?) { if (isCurrent(token) && !checkWake(hypothesis)) restart(token) }
                    override fun onError(exception: Exception?) { if (isCurrent(token)) restart(token) }
                    override fun onTimeout() { if (isCurrent(token)) restart(token) }
                })
            } catch (_: Exception) { if (isCurrent(token)) stop() }
        }
    }

    private fun checkWake(raw: String?): Boolean {
        val heard = runCatching {
            JSONObject(raw.orEmpty()).let { it.optString("text").ifBlank { it.optString("partial") } }
        }.getOrDefault("")
        if (active && WakeWord.matches(heard) && System.currentTimeMillis() - lastWakeAt >= WAKE_COOLDOWN_MS) {
            lastWakeAt = System.currentTimeMillis()
            failures = 0
            stop()
            onWake()
            return true
        }
        return false
    }

    private fun restart(token: AudioSessionManager.Token) {
        if (!active || session != token) return
        active = false
        stopSession(token)
        val delay = VoiceRetryBackoff.delay(++failures, RESTART_MS, MAX_RETRY_MS)
        main.postDelayed(::start, delay)
    }

    fun stop() {
        active = false
        session?.let(::stopSession)
    }

    private fun isCurrent(token: AudioSessionManager.Token) = active && session == token && AudioSessionManager.process.isCurrent(token)

    private fun suspendSession(token: AudioSessionManager.Token) {
        if (session == token) stopSession(token)
    }

    private fun stopSession(token: AudioSessionManager.Token) {
        if (session != token) return
        session = null
        active = false
        stopService()
        AudioSessionManager.process.release(token)
    }

    private fun stopService() {
        val current = service
        service = null
        if (current != null) { runCatching { current.stop() }; runCatching { current.shutdown() } }
    }

    private companion object {
        const val RESTART_MS = 500L
        const val MAX_RETRY_MS = 30_000L
        const val WAKE_COOLDOWN_MS = 2_000L
    }
}

/** Share only the existing audible start/end cue; no tone is emitted by quiet follow-up recognition. */
private object VoiceInputTone {
    fun play(context: Context, finished: Boolean) {
        val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        tone.startTone(if (finished) android.media.ToneGenerator.TONE_PROP_ACK else android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
    }
}
