package com.ridedeck.app.util

import android.content.Context
import androidx.core.content.edit
import com.ridedeck.app.data.BikeType
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.ProactiveKind

object Prefs {
    private const val FILE = "ridedeck"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_READ_MESSAGES = "read_messages_aloud"
    private const val KEY_RECORD_TRIPS = "record_trips"
    private const val KEY_SPEED_LIMIT = "speed_limit"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_PLACES = "saved_places"
    private const val KEY_AVOID_HIGHWAYS = "route_avoid_highways"
    private const val KEY_AVOID_TOLLS = "route_avoid_tolls"
    private const val KEY_AVOID_HILLS = "route_avoid_hills"
    private const val KEY_AVOID_BAD_SURFACES = "route_avoid_bad_surfaces"
    private const val KEY_BIKE_TYPE = "route_bike_type"
    private const val KEY_VEHICLE = "vehicle_profile"
    private const val KEY_SOS_ALARM_VOLUME = "sos_saved_alarm_volume"
    private const val KEY_SOS_CONTACTS = "sos_contacts"
    private const val KEY_SOS_CRASH = "sos_crash"
    private const val KEY_SOS_BUTTON = "sos_button"
    private const val KEY_THERMAL = "thermal_guard"
    private const val KEY_WEATHER = "weather_enabled"
    private const val KEY_RAIN_VOICE = "rain_voice"
    private const val KEY_MILES = "units_miles"
    private const val KEY_PINNED_APPS = "pinned_apps"
    private const val KEY_DASH_SLOTS = "dash_slots"
    private const val KEY_VOICE_REPLIES = "voice_replies"
    private const val KEY_VOICE_HISTORY = "voice_history"
    private const val KEY_VOICE_WAKE = "voice_wake"
    private const val KEY_VOICE_BUTTON = "voice_button"
    private const val KEY_VOICE_HISTORY_ON = "voice_history_on"
    private const val KEY_VOICE_HELMET_MIC = "voice_helmet_mic"
    private const val KEY_NICKNAMES = "voice_nicknames"
    private const val KEY_VOICE_PROACTIVE = "voice_proactive"
    private const val KEY_VOICE_CONTINUOUS = "voice_continuous"
    private const val KEY_VOICE_MISSES = "voice_misses"
    private const val KEY_VOICE_ENGINE = "voice_engine"
    private const val KEY_VOICE_ROUTINES = "voice_routines"
    private const val KEY_WAKE_MISSES = "wake_misses"
    private const val KEY_SETTINGS_HINT = "settings_hint_count"
    private const val KEY_MESSAGE_APPS_SEEN = "message_apps_seen"
    private const val KEY_MESSAGE_APPS_MUTED = "message_apps_muted"

    /** How many launches showed the "hold for Settings" hint; -1 once the rider has used the long press. */
    fun settingsHintCount(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SETTINGS_HINT, 0)

    fun setSettingsHintCount(context: Context, n: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_SETTINGS_HINT, n).apply()
    }

    /** Nickname -> contact (JSON), see `Nicknames`. */
    fun nicknames(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_NICKNAMES, null)

    fun setNicknames(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_NICKNAMES, json).apply()
    }

    /** The assistant may bring things up by itself (rain, low battery, arrival, a break). Off until the rider turns it on. */
    fun voiceProactive(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_PROACTIVE, false)

    fun setVoiceProactive(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_PROACTIVE, on).apply()
    }

    fun voiceProactiveKind(context: Context, kind: ProactiveKind): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("voice_proactive_${kind.name.lowercase()}", true)

    fun setVoiceProactiveKind(context: Context, kind: ProactiveKind, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("voice_proactive_${kind.name.lowercase()}", on) }
    }

    /** After an answer the assistant listens once more, quietly. */
    fun voiceContinuous(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_CONTINUOUS, true)

    fun setVoiceContinuous(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_CONTINUOUS, on).apply()
    }

    /** Sentences that were not understood (text only), newest first, for the export in Settings. */
    fun voiceMisses(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_VOICE_MISSES, null)

    fun setVoiceMisses(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_VOICE_MISSES, json).apply()
    }

    fun pinnedApps(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PINNED_APPS, null)

    fun setPinnedApps(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PINNED_APPS, json).apply()
    }

    /** Packages that posted a chat notification so far (to list them in Settings). */
    fun messageAppsSeen(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MESSAGE_APPS_SEEN, null)

    fun setMessageAppsSeen(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MESSAGE_APPS_SEEN, json).apply()
    }

    /** Chat apps the rider does not want on the dashboard or read aloud. */
    fun messageAppsMuted(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MESSAGE_APPS_MUTED, null)

    fun setMessageAppsMuted(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MESSAGE_APPS_MUTED, json).apply()
    }

    fun dashSlots(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_DASH_SLOTS, null)

    fun setDashSlots(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_DASH_SLOTS, value).apply()
    }

    /** Spoken answers from the voice assistant (on by default). */
    fun voiceReplies(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_REPLIES, true)

    fun setVoiceReplies(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_REPLIES, on).apply()
    }

    /** "Hey RideDeck" while the dashboard is on screen (experimental, off by default). */
    fun voiceWake(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_WAKE, false)

    fun setVoiceWake(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_WAKE, on).apply()
    }

    /** Converts the retired Vosk choice to Android STT while preserving every other preference. */
    fun migrateLegacyVoskEngine(context: Context) {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val previous = preferences.getString(KEY_VOICE_ENGINE, null)
        val migrated = LegacyVoskDataMigration.androidEngine(previous)
        if (migrated != previous) {
            preferences.edit().putString(KEY_VOICE_ENGINE, migrated).commit()
        }
    }

    fun voiceRoutines(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_VOICE_ROUTINES, null)

    fun setVoiceRoutines(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_VOICE_ROUTINES, json) }
    }

    fun routineSnapshot(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("voice_routine_snapshot", null)

    fun setRoutineSnapshot(context: Context, json: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            if (json == null) remove("voice_routine_snapshot") else putString("voice_routine_snapshot", json)
        }
    }

    fun voiceResponseMode(context: Context): String = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("voice_response_mode", "NORMAL") ?: "NORMAL"
    fun setVoiceResponseMode(context: Context, mode: String) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("voice_response_mode", mode) } }
    fun voiceSpeechRate(context: Context): Float = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("voice_speech_rate", 1f).coerceIn(0.75f, 1.25f)
    fun setVoiceSpeechRate(context: Context, rate: Float) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat("voice_speech_rate", rate.coerceIn(0.75f, 1.25f)) } }
    fun voiceTtsName(context: Context): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("voice_tts_name", null)
    fun setVoiceTtsName(context: Context, name: String?) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { if (name == null) remove("voice_tts_name") else putString("voice_tts_name", name) } }

    fun wakeMisses(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_WAKE_MISSES, 0)

    fun incrementWakeMisses(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val next = (prefs.getInt(KEY_WAKE_MISSES, 0) + 1).coerceAtMost(999_999)
        prefs.edit { putInt(KEY_WAKE_MISSES, next) }
        return next
    }

    /** Double press of a headset's play/pause key starts the voice assistant (off by default). */
    fun voiceButton(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_BUTTON, false)

    fun setVoiceButton(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_BUTTON, on).apply()
    }

    fun voiceHistory(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_VOICE_HISTORY, null)

    fun setVoiceHistory(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_VOICE_HISTORY, json).apply()
    }

    /** Keep the last ten spoken commands as text (on by default; nothing is sent anywhere). */
    fun voiceHistoryOn(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_HISTORY_ON, true)

    fun setVoiceHistoryOn(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_HISTORY_ON, on).apply()
    }

    /** Listen through a connected Bluetooth helmet microphone (on by default). */
    fun voiceHelmetMic(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_HELMET_MIC, true)

    fun setVoiceHelmetMic(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_HELMET_MIC, on).apply()
    }

    fun milesUnit(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_MILES, false)

    fun setMilesUnit(context: Context, miles: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_MILES, miles).apply()
    }

    fun overlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_OVERLAY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, enabled).apply()
    }

    fun readMessagesAloud(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_READ_MESSAGES, false)

    fun setReadMessagesAloud(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_READ_MESSAGES, enabled).apply()
    }

    fun recordTrips(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_RECORD_TRIPS, true)

    fun setRecordTrips(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORD_TRIPS, enabled).apply()
    }

    /** Until the user flips it themselves, the vehicle decides: on for motorcycle and car, off for a bicycle. */
    fun speedLimit(context: Context, profile: VehicleProfile): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_SPEED_LIMIT)) prefs.getBoolean(KEY_SPEED_LIMIT, true) else profile.config.speedLimitDefault
    }

    fun speedLimitChosen(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).contains(KEY_SPEED_LIMIT)

    fun setSpeedLimit(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEED_LIMIT, enabled).apply()
    }

    fun themeMode(context: Context): ThemeMode =
        runCatching {
            ThemeMode.valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_THEME, null).orEmpty())
        }.getOrDefault(ThemeMode.AUTO)

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_THEME, mode.name).apply()
    }

    /** The music app whose session was on screen last; "play" with nothing running starts this one. */
    fun lastMusicApp(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("last_music_app", null)

    fun setLastMusicApp(context: Context, packageName: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("last_music_app", packageName).apply()
    }

    fun savedPlaces(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PLACES, null)

    fun setSavedPlaces(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PLACES, json).apply()
    }

    fun weatherEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_WEATHER, true)

    fun setWeatherEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_WEATHER, enabled).apply()
    }

    fun rainVoice(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_RAIN_VOICE, true)

    fun setRainVoice(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_RAIN_VOICE, enabled).apply()
    }

    fun dialerPrompted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("dialer_prompted", false)

    fun setDialerPrompted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("dialer_prompted", true).apply()
    }

    fun thermalGuard(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_THERMAL, true)

    fun setThermalGuard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_THERMAL, enabled).apply()
    }

    fun sosContacts(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SOS_CONTACTS, null)

    fun setSosContacts(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SOS_CONTACTS, json).apply()
    }

    fun sosCrash(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SOS_CRASH, false)

    fun setSosCrash(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOS_CRASH, enabled).apply()
    }

    fun sosButton(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SOS_BUTTON, false)

    fun setSosButton(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SOS_BUTTON, enabled).apply()
    }

    /** The alarm volume to put back after an SOS countdown that was cut short by the process dying; -1 when none. */
    fun sosSavedAlarmVolume(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SOS_ALARM_VOLUME, -1)

    fun setSosSavedAlarmVolume(context: Context, volume: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_SOS_ALARM_VOLUME, volume).apply()
    }

    /** The vehicle chosen at first launch; null until then. */
    fun vehicle(context: Context): VehicleProfile? =
        VehicleProfile.fromName(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_VEHICLE, null))

    fun setVehicle(context: Context, profile: VehicleProfile) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_VEHICLE, profile.name).apply()
    }

    fun routeOptions(context: Context, profile: VehicleProfile): RouteOptions {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return RouteOptions(
            avoidHighways = p.getBoolean(KEY_AVOID_HIGHWAYS, false),
            avoidTolls = p.getBoolean(KEY_AVOID_TOLLS, false),
            profile = profile,
            avoidHills = p.getBoolean(KEY_AVOID_HILLS, false),
            avoidBadSurfaces = p.getBoolean(KEY_AVOID_BAD_SURFACES, false),
            bikeType = BikeType.entries.firstOrNull { it.name == p.getString(KEY_BIKE_TYPE, null) } ?: BikeType.CITY,
        )
    }

    fun setRouteOptions(context: Context, options: RouteOptions) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AVOID_HIGHWAYS, options.avoidHighways).putBoolean(KEY_AVOID_TOLLS, options.avoidTolls)
            .putBoolean(KEY_AVOID_HILLS, options.avoidHills).putBoolean(KEY_AVOID_BAD_SURFACES, options.avoidBadSurfaces)
            .putString(KEY_BIKE_TYPE, options.bikeType.name).apply()
    }

}
