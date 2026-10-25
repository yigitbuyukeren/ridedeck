package com.ridedeck.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Local, streaming KWS. The controller is used from the main thread; capture and inference run off-thread. */
class SherpaWakeWord(
    context: Context,
    private val canRun: () -> Boolean,
    private val onWake: () -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    private var token: AudioSessionManager.Token? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var pausedForHandoff = false
    private var lastWakeAt = 0L
    private var retryAfterMs = 0L
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::start)
            return
        }
        synchronized(lock) {
            if (isRunning.get() || pausedForHandoff || android.os.SystemClock.elapsedRealtime() < retryAfterMs || !canRun() || !hasMicPermission()) return
            val runId = ++generation
            isRunning.set(true)
            lateinit var acquired: AudioSessionManager.Token
            acquired = AudioSessionManager.process.acquire(AudioSessionManager.Owner.SHERPA_WAKE) {
                stopRun(runId, releaseOwner = false)
            }
            token = acquired
            worker = thread(name = "RideDeckWakeKws", start = true) { capture(runId) }
        }
    }

    /** Prevents the owner-release callback after a match from reopening KWS during STT handoff. */
    fun pauseForCommand() {
        pausedForHandoff = true
        stop()
    }

    fun resumeAfterCommand() {
        pausedForHandoff = false
        start()
    }

    fun stop() {
        val runId = synchronized(lock) { generation }
        stopRun(runId, releaseOwner = true)
    }

    @SuppressLint("MissingPermission") // start() checks the runtime grant; SecurityException is handled below.
    private fun capture(runId: Long) {
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var localRecorder: AudioRecord? = null
        var wakeDetected = false
        try {
            if (!hasMicPermission()) throw SecurityException("Microphone permission was revoked")
            val model = "wake/"
            spotter = KeywordSpotter(
                assetManager = app.assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model + "encoder.int8.onnx",
                            decoder = model + "decoder.int8.onnx",
                            joiner = model + "joiner.int8.onnx",
                        ),
                        tokens = model + "tokens.txt",
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    keywordsFile = model + "keywords.txt",
                    keywordsThreshold = 0.35f,
                    keywordsScore = 1.5f,
                ),
            )
            stream = spotter.createStream()
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBytes > 0) { "AudioRecord buffer unavailable" }
            localRecorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, SAMPLE_RATE / 5 * 2),
            )
            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            synchronized(lock) {
                if (!isCurrent(runId)) return
                recorder = localRecorder
            }
            localRecorder.startRecording()
            val shortBuffer = ShortArray(SAMPLE_RATE / 10)
            captureLoop@ while (isCurrent(runId)) {
                val count = localRecorder.read(shortBuffer, 0, shortBuffer.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { shortBuffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    if (spotter.getResult(stream).keyword.isNotBlank()) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (WakeWordTriggerPolicy.accept(now, lastWakeAt)) {
                            lastWakeAt = now
                            wakeDetected = true
                            break@captureLoop
                        }
                        spotter.reset(stream)
                    }
                }
            }
        } catch (error: Exception) {
            if (isCurrent(runId)) {
                retryAfterMs = android.os.SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                Log.w(TAG, "Local wake listener stopped", error)
            }
        } finally {
            runCatching { stream?.release() }
            runCatching { spotter?.release() }
            if (localRecorder != null) {
                runCatching { if (localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) localRecorder.stop() }
                runCatching { localRecorder.release() }
            }
            synchronized(lock) {
                if (generation == runId) {
                    recorder = null
                    worker = null
                    isRunning.set(false)
                    if (wakeDetected) pausedForHandoff = true
                    token?.let(AudioSessionManager.process::release)
                    token = null
                }
            }
            if (wakeDetected) main.post { if (synchronized(lock) { generation == runId }) onWake() }
        }
    }

    private fun stopRun(runId: Long, releaseOwner: Boolean) {
        synchronized(lock) {
            if (runId != generation) return
            generation++
            isRunning.set(false)
            recorder?.let { record ->
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            }
            worker?.interrupt()
            recorder = null
            worker = null
            val activeToken = token
            token = null
            if (releaseOwner && activeToken != null) AudioSessionManager.process.release(activeToken)
        }
    }

    private fun isCurrent(runId: Long): Boolean = synchronized(lock) {
        generation == runId && isRunning.get() && canRun()
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        app,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val SAMPLE_RATE = 16_000
        const val COOLDOWN_MS = 2_500L
        private const val RETRY_DELAY_MS = 30_000L
        private const val TAG = "RideDeckKws"
    }
}

internal object WakeWordTriggerPolicy {
    fun accept(nowMs: Long, previousMs: Long, cooldownMs: Long = SherpaWakeWord.COOLDOWN_MS): Boolean =
        previousMs == 0L || nowMs - previousMs >= cooldownMs
}
