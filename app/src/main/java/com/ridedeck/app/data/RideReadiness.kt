package com.ridedeck.app.data

/** One row in the stationary "ready to ride?" check. UI text is localized later. */
enum class ReadinessKind {
    PRECISE_LOCATION,
    GPS,
    INTERNET,
    OFFLINE_MAP,
    VOICE,
    NOTIFICATIONS,
    MEDIA,
    SOS,
}

enum class ReadinessStatus {
    /** The fact is present and usable. */
    READY,

    /** A required fact is absent. */
    MISSING,

    /** An optional feature is absent or merely configured; this is not a safety guarantee. */
    OPTIONAL,

    /** The input fact was not known; never report this as ready. */
    UNKNOWN,
}

/** Truthful, localized-later explanation of why a row has its status. */
enum class ReadinessDetail {
    LOCATION_FINE_GRANTED,
    LOCATION_COARSE_ONLY,
    LOCATION_DENIED,
    LOCATION_PRECISE_MISSING,
    LOCATION_UNKNOWN,

    GPS_ENABLED,
    GPS_DISABLED,
    GPS_WAITING_FIX,
    GPS_PERMISSION_MISSING,
    GPS_PERMISSION_UNKNOWN,
    GPS_UNKNOWN,

    INTERNET_VALIDATED,
    INTERNET_UNVALIDATED,
    INTERNET_OFFLINE,
    INTERNET_UNKNOWN,

    OFFLINE_COVERED,
    OFFLINE_NOT_COVERED,
    OFFLINE_NO_AREAS,
    OFFLINE_POSITION_UNKNOWN,
    OFFLINE_AREAS_UNKNOWN,
    OFFLINE_STORAGE_UNVERIFIED,

    VOICE_READY,
    VOICE_NETWORK_REQUIRED,
    VOICE_NETWORK_UNAVAILABLE,
    VOICE_LANGUAGE_MISSING,
    VOICE_ENGINE_NOT_READY,
    VOICE_UNKNOWN,

    NOTIFICATIONS_GRANTED,
    NOTIFICATIONS_DENIED,
    NOTIFICATIONS_UNKNOWN,

    MEDIA_AVAILABLE,
    MEDIA_UNAVAILABLE,
    MEDIA_UNKNOWN,

    SOS_CONFIGURED_UNVERIFIED,
    SOS_NOT_CONFIGURED,
    SOS_PERMISSION_MISSING,
    SOS_PERMISSION_UNKNOWN,
    SOS_UNKNOWN,
}

data class ReadinessItem(
    val kind: ReadinessKind,
    val status: ReadinessStatus,
    val detail: ReadinessDetail,
)

data class RideReadinessInput(
    val fineLocationGranted: Boolean? = null,
    val coarseLocationGranted: Boolean? = null,
    val gpsEnabled: Boolean? = null,
    /** Null means freshness could not be observed; false means enabled but no usable fix yet. */
    val hasFreshFix: Boolean? = null,
    val internet: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
    /** Only a known fix may be used for the offline snapshot; the assessment never calls route coverage. */
    val knownPosition: GeoPoint? = null,
    /** Null means the stored-area list itself is unknown; empty means it is known to be empty. */
    val offlineAreas: List<OfflineArea>? = null,
    /**
     * Prefs list entries are metadata. The native MapLibre region/tile store is unverified by TECH-002,
     * so inside a saved area alone cannot prove usable offline tiles.
     */
    val offlineStorageVerified: Boolean? = null,
    val tts: TtsFacts = TtsFacts(),
    val notificationsGranted: Boolean? = null,
    val mediaAccess: Boolean? = null,
    val sosContactsConfigured: Boolean? = null,
    val sosSmsGranted: Boolean? = null,
)

data class RideReadiness(val items: List<ReadinessItem>) {
    fun item(kind: ReadinessKind): ReadinessItem = items.first { it.kind == kind }
}

object RideReadinessAssessment {
    fun assess(input: RideReadinessInput): RideReadiness = RideReadiness(
        listOf(
            location(input),
            gps(input),
            internet(input.internet),
            offlineMap(input),
            voice(input.tts, input.internet),
            notifications(input.notificationsGranted),
            media(input.mediaAccess),
            sos(input.sosContactsConfigured, input.sosSmsGranted),
        )
    )

    private fun location(input: RideReadinessInput): ReadinessItem {
        val fine = input.fineLocationGranted
        val coarse = input.coarseLocationGranted
        val (status, detail) = when {
            fine == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.LOCATION_UNKNOWN
            fine -> ReadinessStatus.READY to ReadinessDetail.LOCATION_FINE_GRANTED
            coarse == true -> ReadinessStatus.MISSING to ReadinessDetail.LOCATION_COARSE_ONLY
            coarse == false -> ReadinessStatus.MISSING to ReadinessDetail.LOCATION_DENIED
            else -> ReadinessStatus.MISSING to ReadinessDetail.LOCATION_PRECISE_MISSING
        }
        return ReadinessItem(ReadinessKind.PRECISE_LOCATION, status, detail)
    }

    private fun gps(input: RideReadinessInput): ReadinessItem {
        val (status, detail) = when {
            input.fineLocationGranted == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.GPS_PERMISSION_UNKNOWN
            !input.fineLocationGranted -> ReadinessStatus.MISSING to ReadinessDetail.GPS_PERMISSION_MISSING
            input.gpsEnabled == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.GPS_UNKNOWN
            !input.gpsEnabled -> ReadinessStatus.MISSING to ReadinessDetail.GPS_DISABLED
            input.hasFreshFix == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.GPS_UNKNOWN
            !input.hasFreshFix -> ReadinessStatus.UNKNOWN to ReadinessDetail.GPS_WAITING_FIX
            else -> ReadinessStatus.READY to ReadinessDetail.GPS_ENABLED
        }
        return ReadinessItem(ReadinessKind.GPS, status, detail)
    }

    private fun internet(status: ConnectivityStatus): ReadinessItem = when (status) {
        ConnectivityStatus.VALIDATED -> ReadinessItem(ReadinessKind.INTERNET, ReadinessStatus.READY, ReadinessDetail.INTERNET_VALIDATED)
        ConnectivityStatus.UNVALIDATED -> ReadinessItem(ReadinessKind.INTERNET, ReadinessStatus.MISSING, ReadinessDetail.INTERNET_UNVALIDATED)
        ConnectivityStatus.OFFLINE -> ReadinessItem(ReadinessKind.INTERNET, ReadinessStatus.MISSING, ReadinessDetail.INTERNET_OFFLINE)
        ConnectivityStatus.UNKNOWN -> ReadinessItem(ReadinessKind.INTERNET, ReadinessStatus.UNKNOWN, ReadinessDetail.INTERNET_UNKNOWN)
    }

    private fun offlineMap(input: RideReadinessInput): ReadinessItem {
        val areas = input.offlineAreas
        val position = input.knownPosition
        val (status, detail) = when {
            areas == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.OFFLINE_AREAS_UNKNOWN
            // A known empty list is a complete answer even when no fix or storage check exists yet.
            areas.isEmpty() -> ReadinessStatus.OPTIONAL to ReadinessDetail.OFFLINE_NO_AREAS
            position == null -> ReadinessStatus.UNKNOWN to ReadinessDetail.OFFLINE_POSITION_UNKNOWN
            input.offlineStorageVerified != true -> ReadinessStatus.UNKNOWN to ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED
            OfflinePlan.covered(areas, position) -> ReadinessStatus.READY to ReadinessDetail.OFFLINE_COVERED
            else -> ReadinessStatus.OPTIONAL to ReadinessDetail.OFFLINE_NOT_COVERED
        }
        return ReadinessItem(ReadinessKind.OFFLINE_MAP, status, detail)
    }

    private fun voice(tts: TtsFacts, internet: ConnectivityStatus): ReadinessItem {
        // A failed engine is a known missing fact even when the language could not be probed.
        if (tts.engineInitialized == null) {
            return ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.UNKNOWN, ReadinessDetail.VOICE_UNKNOWN)
        }
        if (!tts.engineInitialized) {
            return ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.MISSING, ReadinessDetail.VOICE_ENGINE_NOT_READY)
        }
        if (tts.languageAvailable == null) {
            return ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.UNKNOWN, ReadinessDetail.VOICE_UNKNOWN)
        }
        if (!tts.languageAvailable) {
            return ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.MISSING, ReadinessDetail.VOICE_LANGUAGE_MISSING)
        }
        return when (tts.selectedVoiceRequiresNetwork) {
            // The engine's default language voice is unknown here and may be network-backed.
            null -> ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.UNKNOWN, ReadinessDetail.VOICE_UNKNOWN)
            true -> when (internet) {
                ConnectivityStatus.VALIDATED -> ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.READY, ReadinessDetail.VOICE_NETWORK_REQUIRED)
                ConnectivityStatus.UNKNOWN -> ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.UNKNOWN, ReadinessDetail.VOICE_UNKNOWN)
                else -> ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.MISSING, ReadinessDetail.VOICE_NETWORK_UNAVAILABLE)
            }
            false -> ReadinessItem(ReadinessKind.VOICE, ReadinessStatus.READY, ReadinessDetail.VOICE_READY)
        }
    }

    private fun notifications(granted: Boolean?): ReadinessItem = when (granted) {
        true -> ReadinessItem(ReadinessKind.NOTIFICATIONS, ReadinessStatus.READY, ReadinessDetail.NOTIFICATIONS_GRANTED)
        false -> ReadinessItem(ReadinessKind.NOTIFICATIONS, ReadinessStatus.OPTIONAL, ReadinessDetail.NOTIFICATIONS_DENIED)
        null -> ReadinessItem(ReadinessKind.NOTIFICATIONS, ReadinessStatus.UNKNOWN, ReadinessDetail.NOTIFICATIONS_UNKNOWN)
    }

    private fun media(accessible: Boolean?): ReadinessItem = when (accessible) {
        true -> ReadinessItem(ReadinessKind.MEDIA, ReadinessStatus.READY, ReadinessDetail.MEDIA_AVAILABLE)
        false -> ReadinessItem(ReadinessKind.MEDIA, ReadinessStatus.OPTIONAL, ReadinessDetail.MEDIA_UNAVAILABLE)
        null -> ReadinessItem(ReadinessKind.MEDIA, ReadinessStatus.UNKNOWN, ReadinessDetail.MEDIA_UNKNOWN)
    }

    /** SOS configuration never proves SMS delivery or crash detection, so this row is never READY. */
    private fun sos(contacts: Boolean?, sms: Boolean?): ReadinessItem = when {
        contacts == null -> ReadinessItem(ReadinessKind.SOS, ReadinessStatus.UNKNOWN, ReadinessDetail.SOS_UNKNOWN)
        !contacts -> ReadinessItem(ReadinessKind.SOS, ReadinessStatus.OPTIONAL, ReadinessDetail.SOS_NOT_CONFIGURED)
        sms == null -> ReadinessItem(ReadinessKind.SOS, ReadinessStatus.UNKNOWN, ReadinessDetail.SOS_PERMISSION_UNKNOWN)
        !sms -> ReadinessItem(ReadinessKind.SOS, ReadinessStatus.OPTIONAL, ReadinessDetail.SOS_PERMISSION_MISSING)
        else -> ReadinessItem(ReadinessKind.SOS, ReadinessStatus.OPTIONAL, ReadinessDetail.SOS_CONFIGURED_UNVERIFIED)
    }
}
