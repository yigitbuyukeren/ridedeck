package com.ridedeck.app.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SosContact(val name: String, val number: String)

enum class SosReason { AUTO, MANUAL, TEST }

enum class SosPhase { IDLE, COUNTDOWN, SENDING, SENT, PARTIAL, UNKNOWN, FAILED }

enum class SosError { NO_CONTACTS, NO_PERMISSION, NO_SIM, SEND_FAILED }

data class SosUi(
    val phase: SosPhase = SosPhase.IDLE,
    val reason: SosReason = SosReason.AUTO,
    val secondsLeft: Int = 0,
    /** Names the message went to; empty for a test. */
    val sentTo: List<String> = emptyList(),
    val error: SosError? = null,
    val failedTo: List<String> = emptyList(),
    val unknownTo: List<String> = emptyList(),
)

object SosMath {
    const val MAX_CONTACTS = 3
    const val AUTO_SECONDS = 30
    const val MANUAL_SECONDS = 5
    const val TEST_SECONDS = 8

    /** A location older than this is sent as "last known" rather than "now". */
    const val FRESH_FIX_MS = 2 * 60_000L

    fun seconds(reason: SosReason): Int = when (reason) {
        SosReason.AUTO -> AUTO_SECONDS
        SosReason.MANUAL -> MANUAL_SECONDS
        SosReason.TEST -> TEST_SECONDS
    }

    /** Keeps a leading + and digits only. Null when it cannot be a phone number. */
    fun normalizeNumber(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in 7..15) return null
        return (if (trimmed.startsWith("+")) "+" else "") + digits
    }

    /** Adds [new] unless it is already there (same digits) or the list is full. */
    fun addContact(list: List<SosContact>, new: SosContact): List<SosContact> {
        val number = normalizeNumber(new.number) ?: return list
        if (list.size >= MAX_CONTACTS || list.any { it.number.filter(Char::isDigit).takeLast(10) == number.filter(Char::isDigit).takeLast(10) }) return list
        return list + SosContact(new.name.trim().ifBlank { number }, number)
    }

    /** OpenStreetMap link: opens in any browser, no app or account needed on the receiving phone. */
    fun locationLink(lat: Double, lon: Double): String {
        val la = "%.5f".format(Locale.US, lat)
        val lo = "%.5f".format(Locale.US, lon)
        return "https://www.openstreetmap.org/?mlat=$la&mlon=$lo"
    }

    /**
     * Head plus location line. The Turkish strings are ASCII-only on purpose: any non-GSM-7 character
     * switches the SMS to UCS-2 (70 characters, 67 per part), while the worst case here fits one 160-character GSM-7 SMS.
     */
    fun compose(head: String, link: String?, stale: Boolean, none: String, staleFmt: String, fmt: String): String {
        val where = when {
            link == null -> none
            stale -> String.format(Locale.US, staleFmt, link)
            else -> String.format(Locale.US, fmt, link)
        }
        return "$head $where"
    }

    fun toJson(list: List<SosContact>): String = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
    }.toString()

    fun fromJson(json: String?): List<SosContact> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val number = normalizeNumber(o.optString("number")) ?: return@mapNotNull null
                SosContact(o.optString("name").ifBlank { number }, number)
            }.fold(emptyList()) { list, contact -> addContact(list, contact) }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}

/**
 * What a starting countdown needs from the phone: a visible notification, a full-screen intent, a
 * screen wake and — when notifications are unavailable — a best-effort activity start. Pure booleans
 * so every combination is covered by JVM tests; [RideSafety] only supplies the platform facts.
 */
data class SosAlertPlan(
    val postNotification: Boolean,
    val fullScreenIntent: Boolean,
    val wakeScreen: Boolean,
    val launchApp: Boolean,
)

object SosAlertPolicy {
    fun plan(
        notificationsEnabled: Boolean,
        notificationPermission: Boolean,
        fullScreenIntentAllowed: Boolean,
        screenOff: Boolean,
        canStartActivity: Boolean,
    ): SosAlertPlan {
        val notifications = notificationsEnabled && notificationPermission
        return SosAlertPlan(
            postNotification = notifications,
            // A full-screen intent without a notification has nothing to attach to.
            fullScreenIntent = notifications && fullScreenIntentAllowed,
            // Waking the screen only helps when there is a countdown to show: a notification or a
            // permitted activity launch. Otherwise it would light an empty lock screen.
            wakeScreen = screenOff && (notifications || canStartActivity),
            launchApp = !notifications && canStartActivity,
        )
    }
}

/** Why SMS cannot leave the phone right now, so Settings can say it plainly. */
enum class SosBlocker { NO_CONTACTS, NO_SMS_PERMISSION, NO_TELEPHONY, NO_SIM }

/**
 * The SOS countdown must be readable over the keyguard and light the screen; everything else —
 * the dashboard, a result screen — must not, or the dashboard would sit on the lock screen.
 */
object SosLockScreenPolicy {
    fun showOverKeyguard(phase: SosPhase): Boolean = phase == SosPhase.COUNTDOWN
}

/** Warnings about how visible the countdown can be; none of them blocks the SMS or the alarm. */
enum class SosAlertWarning { NOTIFICATIONS_OFF, POST_NOTIFICATIONS_DENIED, FULL_SCREEN_INTENT_DENIED }

/**
 * Lock-screen visibility is a warning, not a gate: with notifications off the beep, the voice and the
 * SMS still run. The card shows every problem so the driver can fix it before a trip.
 */
data class SosAlertPermissionFacts(
    val notificationsEnabled: Boolean = true,
    /** Always true below API 33, where POST_NOTIFICATIONS does not exist. */
    val postNotificationsGranted: Boolean = true,
    /** Always true below API 34, where full-screen intents are not user-restricted. */
    val fullScreenIntentAllowed: Boolean = true,
) {
    val warnings: List<SosAlertWarning>
        get() = buildList {
            if (!notificationsEnabled) add(SosAlertWarning.NOTIFICATIONS_OFF)
            else if (!postNotificationsGranted) add(SosAlertWarning.POST_NOTIFICATIONS_DENIED)
            // The full-screen intent is a separate special access; name it whenever it is refused.
            if (!fullScreenIntentAllowed) add(SosAlertWarning.FULL_SCREEN_INTENT_DENIED)
        }
}

/**
 * Honest readiness: a saved contact and the SMS permission are not enough when the device has no
 * telephony or the default SMS subscription is unusable. The triggers stay off in that case instead
 * of promising a message the phone cannot send.
 */
object SosReadiness {
    /** True when a message could be handed to the radio at all. */
    fun sendable(smsPermission: Boolean, telephony: Boolean, defaultSmsUsable: Boolean): Boolean =
        smsPermission && telephony && defaultSmsUsable

    /** Crash detection and the SOS button only make sense when somebody can be reached. */
    fun triggersReady(contacts: Int, smsPermission: Boolean, telephony: Boolean, defaultSmsUsable: Boolean): Boolean =
        contacts > 0 && sendable(smsPermission, telephony, defaultSmsUsable)

    /** The SIM/telephony warning, independent of contacts and permission; null when the radio looks usable. */
    fun blocker(contacts: Int, smsPermission: Boolean, telephony: Boolean, defaultSmsUsable: Boolean): SosBlocker? = when {
        contacts <= 0 -> SosBlocker.NO_CONTACTS
        !telephony -> SosBlocker.NO_TELEPHONY
        !defaultSmsUsable -> SosBlocker.NO_SIM
        !smsPermission -> SosBlocker.NO_SMS_PERMISSION
        else -> null
    }
}

/**
 * Decides from accelerometer samples and GPS speed whether a vehicle has crashed:
 * a hard impact while moving, followed by the phone staying still and the vehicle not moving. A pothole
 * (impact but the vehicle rolls on), a hard stop (no impact) and a vehicle knocked while parked (never
 * moving) all stay quiet. A pothole followed by a red light looks the same by speed and shaking alone, so
 * the alarm can additionally require the phone to have tipped over ([needsTilt]) or the impact to have been
 * twice the threshold; the tilt is read from the gravity direction when the caller supplies it.
 */
class CrashDetector(
    private val impactG: () -> Float = { IMPACT_G },
    private val ridingKmh: () -> Int = { RIDING_KMH },
    private val needsTilt: () -> Boolean = { false },
) {
    private var lastRidingAt = Long.MIN_VALUE
    private var impactAt = 0L
    private var quietSince = 0L
    private var armed = false
    private var cooldownUntil = 0L

    // Gravity direction, smoothed slowly (what the phone looked like before) and quickly (what it looks like now).
    private var slow: FloatArray? = null
    private var fast: FloatArray? = null
    private var lastVectorAt = 0L
    private var baseline: FloatArray? = null
    private var peakG = 0f
    private var lastSampleAt: Long? = null

    fun reset() {
        lastRidingAt = Long.MIN_VALUE
        impactAt = 0L; quietSince = 0L; armed = false
        slow = null; fast = null; baseline = null; lastVectorAt = 0L; peakG = 0f; lastSampleAt = null
        // Preserve the cooldown: pausing the screen must not enable another automatic alert.
    }

    /**
     * The driver saw the alert and pressed "I am fine". The cooldown only exists so one crash cannot
     * alert twice; keeping it would swallow a *second* real crash in the next five minutes. Clearing it
     * is safe because everything belonging to the first crash is dropped too: a new alert still needs a
     * fresh moving-speed window and a new impact, so the same recorded samples cannot fire again.
     */
    fun acknowledgeCancel() {
        lastRidingAt = Long.MIN_VALUE
        impactAt = 0L; quietSince = 0L; armed = false
        cooldownUntil = 0L
        slow = null; fast = null; baseline = null; lastVectorAt = 0L; peakG = 0f
        // lastSampleAt stays: the stream keeps advancing, so replayed samples cannot re-arm the detector.
    }

    /** @param g acceleration magnitude in g, gravity included (about 1 at rest); [gx] [gy] [gz] the same as a vector, or zeros when unknown. */
    fun onSample(tMs: Long, g: Float, speedKmh: Int, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f): Boolean {
        if (!g.isFinite() || g < 0f || !gx.isFinite() || !gy.isFinite() || !gz.isFinite()) { reset(); return false }
        val previous = lastSampleAt
        if (previous != null && tMs <= previous) return false
        if (previous != null && tMs - previous > 1_000L) reset()
        lastSampleAt = tMs
        val hasVector = gx != 0f || gy != 0f || gz != 0f
        if (hasVector) trackGravity(tMs, gx, gy, gz)
        if (speedKmh >= ridingKmh()) lastRidingAt = tMs
        if (tMs < cooldownUntil) return false

        if (!armed) {
            if (g >= impactG() && lastRidingAt != Long.MIN_VALUE && tMs - lastRidingAt <= RIDING_WINDOW_MS) {
                armed = true
                impactAt = tMs
                quietSince = 0L
                peakG = g
                baseline = slow?.copyOf()
            }
            return false
        }

        if (g > peakG) peakG = g
        val sinceImpact = tMs - impactAt
        if (sinceImpact > GIVE_UP_MS || (sinceImpact >= RESUME_AFTER_MS && speedKmh >= minOf(RESUME_KMH, ridingKmh() * 3 / 5))) {
            armed = false
            return false
        }
        val quiet = speedKmh < STILL_KMH && abs(g - 1f) <= QUIET_BAND_G
        if (!quiet) {
            quietSince = 0L
            return false
        }
        if (quietSince == 0L) quietSince = tMs
        if (tMs - quietSince >= QUIET_MS) {
            if (!fellOver(hasVector)) {
                // Rolled to a stop after a bump: stay armed no longer, nothing to report.
                armed = false
                return false
            }
            armed = false
            cooldownUntil = tMs + COOLDOWN_MS
            return true
        }
        return false
    }

    /** True when the phone's orientation changed for good, or the hit was so hard that no pothole explains it. */
    private fun fellOver(hasVector: Boolean): Boolean {
        if (!needsTilt()) return true
        if (peakG >= impactG() * 2) return true
        if (!hasVector) return false
        val before = baseline ?: return false
        val now = fast ?: return false
        return angleDeg(before, now) >= TILT_MIN_DEG
    }

    private fun trackGravity(tMs: Long, x: Float, y: Float, z: Float) {
        val v = floatArrayOf(x, y, z)
        if (slow == null || fast == null) {
            slow = v.copyOf(); fast = v.copyOf(); lastVectorAt = tMs
            return
        }
        val dt = (tMs - lastVectorAt).coerceAtLeast(1L).toFloat()
        lastVectorAt = tMs
        blend(slow!!, v, dt / (SLOW_TAU_MS + dt))
        blend(fast!!, v, dt / (FAST_TAU_MS + dt))
    }

    private fun blend(into: FloatArray, v: FloatArray, alpha: Float) {
        for (i in 0..2) into[i] += alpha * (v[i] - into[i])
    }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0f || nb == 0f) return 0f
        val cos = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    companion object {
        const val IMPACT_G = 5.0f
        const val RIDING_KMH = 30
        const val RIDING_WINDOW_MS = 5_000L
        const val STILL_KMH = 5
        const val RESUME_KMH = 15
        const val RESUME_AFTER_MS = 4_000L
        const val QUIET_BAND_G = 0.35f
        const val QUIET_MS = 8_000L
        const val GIVE_UP_MS = 60_000L
        const val COOLDOWN_MS = 5 * 60_000L
        const val TILT_MIN_DEG = 25f
        const val SLOW_TAU_MS = 3_000f
        const val FAST_TAU_MS = 400f
    }
}

fun interface SmsSender {
    /** Confirms modem send results per number; this does not prove recipient delivery. */
    suspend fun send(numbers: List<String>, text: String): SmsReport
}

interface SosAlarm {
    /** Called once at the start and then every second of the countdown. */
    fun beep()
    fun stop()
}

/**
 * Countdown, then one SMS per contact with the location link; never a call and never a repeat.
 * Text and voice come in through [message] and [speak] so this stays plain Kotlin.
 */
class SosController(
    private val scope: CoroutineScope,
    private val location: StateFlow<LocationState>,
    private val contacts: StateFlow<List<SosContact>>,
    private val sms: SmsSender,
    private val alarm: SosAlarm,
    private val smsAllowed: () -> Boolean,
    private val speak: (SosReason, Int) -> Unit,
    private val message: (SosReason, String?, Boolean) -> String,
    private val tick: suspend () -> Unit = { delay(1000) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Countdown cancelled by the driver ("I am fine" on screen or in the notification). */
    private val onCancelled: () -> Unit = {},
) {
    private val _ui = MutableStateFlow(SosUi())
    val ui: StateFlow<SosUi> = _ui
    private var job: Job? = null
    private var generation = 0L
    private var closed = false

    fun trigger(reason: SosReason) {
        // A finished result screen must not become a permanent latch: an unconscious driver cannot
        // dismiss it, and a second crash still has to start a new countdown. Only an in-progress
        // countdown or an SMS already on the wire may not be superseded.
        val phase = _ui.value.phase
        if (closed || phase == SosPhase.COUNTDOWN || phase == SosPhase.SENDING) return
        val original = contacts.value.fold(emptyList<SosContact>()) { list, contact -> SosMath.addContact(list, contact) }
        if (reason != SosReason.TEST) {
            val problem = when {
                original.isEmpty() -> SosError.NO_CONTACTS
                !smsAllowed() -> SosError.NO_PERMISSION
                else -> null
            }
            if (problem != null) {
                _ui.value = SosUi(SosPhase.FAILED, reason, error = problem)
                return
            }
        }
        val seconds = SosMath.seconds(reason)
        _ui.value = SosUi(SosPhase.COUNTDOWN, reason, seconds)
        val request = ++generation
        runCatching { speak(reason, seconds) }
        job = scope.launch {
            try {
                runCatching { alarm.beep() }
                for (left in seconds - 1 downTo 0) {
                    tick()
                    if (request != generation) return@launch
                    _ui.update { it.copy(secondsLeft = left) }
                    if (left > 0) runCatching { alarm.beep() }
                }
                runCatching { alarm.stop() }
                finish(reason, original)
            } catch (cancelled: CancellationException) {
                if (request == generation) _ui.value = if (_ui.value.phase == SosPhase.SENDING) SosUi(SosPhase.UNKNOWN, reason, unknownTo = original.map { it.name }) else SosUi()
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _ui.value = SosUi(SosPhase.FAILED, reason, error = SosError.SEND_FAILED)
            } finally {
                if (request == generation) runCatching { alarm.stop() }
            }
        }
    }

    /** "I am fine" / cancel. */
    fun cancel() {
        if (_ui.value.phase != SosPhase.COUNTDOWN) return
        ++generation
        job?.cancel()
        runCatching { alarm.stop() }
        _ui.value = SosUi()
        // Only a cancel the driver chose re-arms crash detection; dismiss/close must not.
        runCatching { onCancelled() }
    }

    /** Closes the "sent" / "failed" screen. */
    fun dismiss() {
        if (_ui.value.phase in setOf(SosPhase.SENT, SosPhase.PARTIAL, SosPhase.UNKNOWN, SosPhase.FAILED)) _ui.value = SosUi()
    }

    fun close() {
        closed = true
        ++generation
        job?.cancel()
        runCatching { alarm.stop() }
    }

    private suspend fun finish(reason: SosReason, original: List<SosContact>) {
        if (reason == SosReason.TEST) {
            _ui.value = SosUi(SosPhase.SENT, reason)
            return
        }
        // Removed contacts are excluded; contacts added during the countdown need a new explicit alert.
        val allowed = contacts.value.mapNotNull { SosMath.normalizeNumber(it.number) }.toSet()
        val list = original.filter { it.number in allowed }
        if (list.isEmpty() || !smsAllowed()) {
            _ui.value = SosUi(SosPhase.FAILED, reason, error = if (list.isEmpty()) SosError.NO_CONTACTS else SosError.NO_PERMISSION)
            return
        }
        val fix = location.value
        val haveFix = fix.fixAtMs > 0 && fix.lat.isFinite() && fix.lon.isFinite() && fix.lat in -90.0..90.0 && fix.lon in -180.0..180.0
        val link = if (haveFix) SosMath.locationLink(fix.lat, fix.lon) else null
        val stale = haveFix && (now() - fix.fixAtMs !in 0..SosMath.FRESH_FIX_MS || fix.locationPermissionGranted == false || fix.gpsProviderEnabled == false)
        val body = message(reason, link, stale)
        _ui.value = SosUi(SosPhase.SENDING, reason)
        val report = try { sms.send(list.map { it.number }, body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _ui.value = SosUi(SosPhase.UNKNOWN, reason, unknownTo = list.map { it.name })
            return
        }
        val sent = list.filter { report.recipients[it.number] == SmsStatus.SENT }.map { it.name }
        val failed = list.filter { report.recipients[it.number] == SmsStatus.FAILED }.map { it.name }
        val unknown = list.filter { report.recipients[it.number] == null || report.recipients[it.number] == SmsStatus.UNKNOWN }.map { it.name }
        val phase = when {
            sent.size == list.size -> SosPhase.SENT
            sent.isNotEmpty() -> SosPhase.PARTIAL
            unknown.isNotEmpty() -> SosPhase.UNKNOWN
            else -> SosPhase.FAILED
        }
        _ui.value = SosUi(phase, reason, sentTo = sent, failedTo = failed, unknownTo = unknown, error = when (report.problem) {
            SmsProblem.PERMISSION -> SosError.NO_PERMISSION
            SmsProblem.NO_DEFAULT_SIM -> SosError.NO_SIM
            else -> if (phase == SosPhase.FAILED) SosError.SEND_FAILED else null
        })
    }
}
