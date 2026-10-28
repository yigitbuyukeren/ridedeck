package com.ridedeck.app.util

import android.content.Context
import androidx.core.content.edit
import com.ridedeck.app.data.BikeType
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VehicleProfile

object Prefs {
    private const val FILE = "ridedeck"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_READ_MESSAGES = "read_messages_aloud"
    private const val KEY_RECORD_TRIPS = "record_trips"
    private const val KEY_SPEED_LIMIT = "speed_limit"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_PLACES = "saved_places"
    private const val KEY_RECENT_PLACES = "recent_places"
    private const val KEY_RECENT_PLACES_ON = "recent_places_on"
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
    private const val KEY_SETTINGS_HINT = "settings_hint_count"
    private const val KEY_MESSAGE_APPS_SEEN = "message_apps_seen"
    private const val KEY_MESSAGE_APPS_MUTED = "message_apps_muted"

    /** How many launches showed the "hold for Settings" hint; -1 once the rider has used the long press. */
    fun settingsHintCount(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SETTINGS_HINT, 0)

    fun setSettingsHintCount(context: Context, n: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_SETTINGS_HINT, n).apply()
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

    fun voiceSpeechRate(context: Context): Float = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("voice_speech_rate", 1f).coerceIn(0.75f, 1.25f)
    fun setVoiceSpeechRate(context: Context, rate: Float) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat("voice_speech_rate", rate.coerceIn(0.75f, 1.25f)) } }
    fun voiceTtsName(context: Context): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("voice_tts_name", null)
    fun setVoiceTtsName(context: Context, name: String?) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { if (name == null) remove("voice_tts_name") else putString("voice_tts_name", name) } }

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

    /** The last chosen destinations (cleared when the rider turns the history off). */
    fun recentPlaces(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_RECENT_PLACES, null)

    fun setRecentPlaces(context: Context, json: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_RECENT_PLACES, json).apply()
    }

    fun recentPlacesOn(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_RECENT_PLACES_ON, true)

    fun setRecentPlacesOn(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECENT_PLACES_ON, on).apply()
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

    /** Older builds had a voice assistant; forget everything it stored (transcripts, nicknames, routines, switches). */
    fun removeVoiceAssistantData(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stale = prefs.all.keys.filter { key ->
            key.startsWith("voice_") && key !in KEEP_VOICE_KEYS || key == "legacy_speech_wake_migrated"
        }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    private val KEEP_VOICE_KEYS = setOf("voice_speech_rate", "voice_tts_name")
}
