package com.ridedeck.app.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.hasPermission
import kotlin.math.sqrt

/** Transport is injectable so Android callback tests never reach a modem. */
interface SmsGateway {
    fun parts(text: String): ArrayList<String>
    fun submit(number: String, parts: ArrayList<String>, sent: ArrayList<android.app.PendingIntent>)
}

private class NoDefaultSmsSim : RuntimeException()

private class PlatformSmsGateway(context: Context) : SmsGateway {
    private val subscription = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
    private val manager: SmsManager
    init {
        if (!SmsSubscriptionPolicy.usable(subscription)) throw NoDefaultSmsSim()
        manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
        else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription)
    }
    override fun parts(text: String): ArrayList<String> = manager.divideMessage(text)
    override fun submit(number: String, parts: ArrayList<String>, sent: ArrayList<android.app.PendingIntent>) {
        if (parts.size > 1) manager.sendMultipartTextMessage(number, null, parts, sent, null)
        else manager.sendTextMessage(number, null, parts.single(), sent.single(), null)
    }
}

/** One submission per contact. Only SENT callbacks count as sent; receipt by the contact is not claimed. */
class AndroidSmsSender(
    private val context: Context,
    private val gatewayFactory: () -> SmsGateway = { PlatformSmsGateway(context) },
    private val allowed: () -> Boolean = { context.hasPermission(android.Manifest.permission.SEND_SMS) },
    private val timeoutMs: Long = 60_000L,
) : SmsSender {
    override suspend fun send(numbers: List<String>, text: String): SmsReport = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        val targets = numbers.distinct().take(SosMath.MAX_CONTACTS)
        fun failed(problem: SmsProblem? = null) = SmsReport(targets.associateWith { SmsStatus.FAILED }, problem)
        if (targets.isEmpty() || text.isBlank()) return@withContext failed()
        if (!allowed()) return@withContext failed(SmsProblem.PERMISSION)
        val gateway: SmsGateway
        val parts: ArrayList<String>
        try {
            gateway = gatewayFactory()
            parts = gateway.parts(text)
            if (parts.isEmpty()) return@withContext failed()
        } catch (_: NoDefaultSmsSim) { return@withContext failed(SmsProblem.NO_DEFAULT_SIM) }
        catch (_: SecurityException) { return@withContext failed(SmsProblem.PERMISSION) }
        catch (_: RuntimeException) { return@withContext failed() }
        val trackers = targets.map { SmsPartTracker(parts.size) }
        val complete = kotlinx.coroutines.CompletableDeferred<Unit>()
        val action = context.packageName + ".SMS_SENT." + java.util.UUID.randomUUID()
        val intents = mutableListOf<android.app.PendingIntent>()
        var permissionLost = false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (intent.action != action) return
                val recipient = intent.getIntExtra("recipient", -1)
                val part = intent.getIntExtra("part", -1)
                trackers.getOrNull(recipient)?.result(part, resultCode == android.app.Activity.RESULT_OK)
                if (trackers.all { it.complete }) complete.complete(Unit)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            targets.forEachIndexed { index, number ->
                val callbacks = ArrayList((parts.indices).map { part ->
                    android.app.PendingIntent.getBroadcast(context, index * parts.size + part,
                        android.content.Intent(action).setPackage(context.packageName).putExtra("recipient", index).putExtra("part", part),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT).also { intents += it }
                })
                try {
                    if (!allowed()) { permissionLost = true; throw SecurityException() }
                    gateway.submit(number, parts, callbacks)
                } catch (failure: RuntimeException) {
                    if (failure is SecurityException) permissionLost = true
                    parts.indices.forEach { trackers[index].result(it, false) }
                    Log.w("RideDeck", "SMS submission failed (${failure.javaClass.simpleName})")
                }
            }
            if (trackers.all { it.complete }) complete.complete(Unit)
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { complete.await() }
            SmsReport(targets.mapIndexed { index, number -> number to trackers[index].status() }.toMap(),
                if (permissionLost) SmsProblem.PERMISSION else null)
        } finally {
            context.unregisterReceiver(receiver)
            intents.forEach { it.cancel() }
        }
    }
}

/** Loud beep on the alarm stream, plus a buzz. The alarm volume is raised for the countdown and put back. */
class AndroidSosAlarm(private val context: Context) : SosAlarm {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tone: ToneGenerator? = null
    private var savedVolume: Int? = null

    override fun beep() {
        alarmAction {
            if (savedVolume == null) {
                savedVolume = Prefs.sosSavedAlarmVolume(context).takeIf { it >= 0 } ?: audio.getStreamVolume(AudioManager.STREAM_ALARM)
                // Written down first: if the process dies mid-countdown the next start puts the volume back.
                Prefs.setSosSavedAlarmVolume(context, savedVolume!!)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        }
        // A volume/DND restriction must not suppress the independent tone and vibration attempts.
        alarmAction {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
        }
        alarmAction { vibrator()?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private inline fun alarmAction(block: () -> Unit) {
        try { block() } catch (e: RuntimeException) {
            Log.w("RideDeck", "SOS alarm failed (${e.javaClass.simpleName})")
        }
    }

    override fun stop() {
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        runCatching { vibrator()?.cancel() }
        val volume = savedVolume ?: Prefs.sosSavedAlarmVolume(context).takeIf { it >= 0 }
        savedVolume = null
        if (volume == null || runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }.isSuccess) {
            Prefs.setSosSavedAlarmVolume(context, -1)
        } // A denied volume restore keeps the recovery marker for the next start.
    }

    /** At start-up: a countdown that never finished left the alarm stream at full volume. */
    fun restoreVolumeIfPending() {
        val v = Prefs.sosSavedAlarmVolume(context)
        if (v < 0) return
        if (runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }.isSuccess) Prefs.setSosSavedAlarmVolume(context, -1)
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
}

/** Feeds [CrashDetector] from the accelerometer. Only listens while the dashboard is on screen. */
class CrashSensor(
    context: Context,
    private val speedKmh: () -> Int?,
    config: () -> ProfileConfig,
    private val onCrash: () -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = CrashDetector({ config().crashImpactG }, { config().crashMovingKmh }, { config().crashNeedsTilt })
    private var listening = false

    val supported: Boolean get() = accelerometer != null

    fun start() {
        if (listening || accelerometer == null) return
        detector.reset()
        listening = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        detector.reset()
        if (!listening) return
        sensors.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        val speed = speedKmh() ?: run { detector.reset(); return }
        val gx = event.values[0] / SensorManager.GRAVITY_EARTH
        val gy = event.values[1] / SensorManager.GRAVITY_EARTH
        val gz = event.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (detector.onSample(event.timestamp / 1_000_000L, g, speed, gx, gy, gz)) onCrash()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
