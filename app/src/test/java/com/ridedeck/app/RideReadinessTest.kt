package com.ridedeck.app

import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.ReadinessDetail
import com.ridedeck.app.data.ReadinessKind
import com.ridedeck.app.data.ReadinessStatus
import com.ridedeck.app.data.RideReadinessAssessment
import com.ridedeck.app.data.RideReadinessInput
import com.ridedeck.app.data.TtsFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideReadinessTest {
    private val here = GeoPoint(41.0, 29.0)
    private val area = OfflineArea("home", here, 30, 1L)

    @Test
    fun unknownFactsAreNeverReportedReady() {
        val readiness = RideReadinessAssessment.assess(RideReadinessInput())

        assertTrue(readiness.items.none { it.status == ReadinessStatus.READY })
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.GPS).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.INTERNET).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.OFFLINE_MAP).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.VOICE).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.NOTIFICATIONS).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.MEDIA).status)
        assertEquals(ReadinessStatus.UNKNOWN, readiness.item(ReadinessKind.SOS).status)
    }

    @Test
    fun completeConfiguredFactsAreReadyExceptSos() {
        val readiness = RideReadinessAssessment.assess(
            RideReadinessInput(
                fineLocationGranted = true,
                coarseLocationGranted = true,
                gpsEnabled = true,
                hasFreshFix = true,
                internet = ConnectivityStatus.VALIDATED,
                knownPosition = here,
                offlineAreas = listOf(area),
                offlineStorageVerified = true,
                tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = false),
                notificationsGranted = true,
                mediaAccess = true,
                sosContactsConfigured = true,
                sosSmsGranted = true,
            )
        )

        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.GPS).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.INTERNET).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.OFFLINE_MAP).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.VOICE).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.NOTIFICATIONS).status)
        assertEquals(ReadinessStatus.READY, readiness.item(ReadinessKind.MEDIA).status)
        assertEquals(ReadinessStatus.OPTIONAL, readiness.item(ReadinessKind.SOS).status)
        assertEquals(ReadinessDetail.SOS_CONFIGURED_UNVERIFIED, readiness.item(ReadinessKind.SOS).detail)
    }

    @Test
    fun missingRequiredFactsAreMissingAndOptionalOnesAreOptional() {
        val readiness = RideReadinessAssessment.assess(
            RideReadinessInput(
                fineLocationGranted = false,
                coarseLocationGranted = true,
                gpsEnabled = false,
                hasFreshFix = false,
                internet = ConnectivityStatus.OFFLINE,
                offlineAreas = emptyList(),
                offlineStorageVerified = true,
                tts = TtsFacts(engineInitialized = false, languageAvailable = false),
                notificationsGranted = false,
                mediaAccess = false,
                sosContactsConfigured = false,
            )
        )

        assertEquals(ReadinessDetail.LOCATION_COARSE_ONLY, readiness.item(ReadinessKind.PRECISE_LOCATION).detail)
        assertEquals(ReadinessStatus.MISSING, readiness.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessDetail.GPS_PERMISSION_MISSING, readiness.item(ReadinessKind.GPS).detail)
        assertEquals(ReadinessStatus.MISSING, readiness.item(ReadinessKind.INTERNET).status)
        assertEquals(ReadinessStatus.OPTIONAL, readiness.item(ReadinessKind.OFFLINE_MAP).status)
        assertEquals(ReadinessDetail.OFFLINE_NO_AREAS, readiness.item(ReadinessKind.OFFLINE_MAP).detail)
        assertEquals(ReadinessDetail.VOICE_ENGINE_NOT_READY, readiness.item(ReadinessKind.VOICE).detail)
        assertEquals(ReadinessStatus.OPTIONAL, readiness.item(ReadinessKind.NOTIFICATIONS).status)
        assertEquals(ReadinessStatus.OPTIONAL, readiness.item(ReadinessKind.MEDIA).status)
        assertEquals(ReadinessDetail.SOS_NOT_CONFIGURED, readiness.item(ReadinessKind.SOS).detail)
    }

    @Test
    fun missingCoarseFactStillMarksPreciseLocationAsMissingWithoutClaimingDenied() {
        val item = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = false)
        ).item(ReadinessKind.PRECISE_LOCATION)

        assertEquals(ReadinessStatus.MISSING, item.status)
        assertEquals(ReadinessDetail.LOCATION_PRECISE_MISSING, item.detail)
    }

    @Test
    fun unknownIsNotTreatedAsOffline() {
        val item = RideReadinessAssessment.assess(
            RideReadinessInput(internet = ConnectivityStatus.UNKNOWN)
        ).item(ReadinessKind.INTERNET)

        assertEquals(ReadinessStatus.UNKNOWN, item.status)
        assertEquals(ReadinessDetail.INTERNET_UNKNOWN, item.detail)
    }

    @Test
    fun offlineSnapshotUsesOnlyKnownPositionAndStoredAreas() {
        val empty = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = emptyList(), offlineStorageVerified = true)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.OPTIONAL, empty.status)
        assertEquals(ReadinessDetail.OFFLINE_NO_AREAS, empty.detail)

        val outside = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = GeoPoint(39.0, 32.0), offlineAreas = listOf(area), offlineStorageVerified = true)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.OPTIONAL, outside.status)
        assertEquals(ReadinessDetail.OFFLINE_NOT_COVERED, outside.detail)

        val covered = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = listOf(area), offlineStorageVerified = true)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.READY, covered.status)
        assertEquals(ReadinessDetail.OFFLINE_COVERED, covered.detail)
    }

    @Test
    fun offlinePositionAndStoredAreaListAreDistinctUnknowns() {
        val positionUnknown = RideReadinessAssessment.assess(
            RideReadinessInput(offlineAreas = listOf(area), offlineStorageVerified = true)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessDetail.OFFLINE_POSITION_UNKNOWN, positionUnknown.detail)

        val areasUnknown = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = null, offlineStorageVerified = true)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessDetail.OFFLINE_AREAS_UNKNOWN, areasUnknown.detail)
    }

    @Test
    fun savedAreaMetadataAloneCannotProveOfflineTiles() {
        val unverified = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = listOf(area), offlineStorageVerified = null)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.UNKNOWN, unverified.status)
        assertEquals(ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED, unverified.detail)

        val failedVerification = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = listOf(area), offlineStorageVerified = false)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.UNKNOWN, failedVerification.status)
        assertEquals(ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED, failedVerification.detail)
    }

    @Test
    fun gpsNeedsFinePermissionProviderAndFreshFixForReady() {
        val ready = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = true, gpsEnabled = true, hasFreshFix = true)
        ).item(ReadinessKind.GPS)
        assertEquals(ReadinessStatus.READY, ready.status)

        val waiting = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = true, gpsEnabled = true, hasFreshFix = false)
        ).item(ReadinessKind.GPS)
        assertEquals(ReadinessStatus.UNKNOWN, waiting.status)
        assertEquals(ReadinessDetail.GPS_WAITING_FIX, waiting.detail)

        val freshnessUnknown = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = true, gpsEnabled = true, hasFreshFix = null)
        ).item(ReadinessKind.GPS)
        assertEquals(ReadinessStatus.UNKNOWN, freshnessUnknown.status)

        val noPermission = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = false, gpsEnabled = true, hasFreshFix = true)
        ).item(ReadinessKind.GPS)
        assertEquals(ReadinessStatus.MISSING, noPermission.status)
        assertEquals(ReadinessDetail.GPS_PERMISSION_MISSING, noPermission.detail)

        val disabled = RideReadinessAssessment.assess(
            RideReadinessInput(fineLocationGranted = true, gpsEnabled = false, hasFreshFix = true)
        ).item(ReadinessKind.GPS)
        assertEquals(ReadinessStatus.MISSING, disabled.status)
        assertEquals(ReadinessDetail.GPS_DISABLED, disabled.detail)
    }

    @Test
    fun ttsUsesEngineLanguageAndVoiceNetworkFacts() {
        val languageMissing = RideReadinessAssessment.assess(
            RideReadinessInput(tts = TtsFacts(engineInitialized = true, languageAvailable = false))
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.MISSING, languageMissing.status)
        assertEquals(ReadinessDetail.VOICE_LANGUAGE_MISSING, languageMissing.detail)

        val networkReady = RideReadinessAssessment.assess(
            RideReadinessInput(
                internet = ConnectivityStatus.VALIDATED,
                tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = true),
            )
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.READY, networkReady.status)
        assertEquals(ReadinessDetail.VOICE_NETWORK_REQUIRED, networkReady.detail)

        val networkMissing = RideReadinessAssessment.assess(
            RideReadinessInput(
                internet = ConnectivityStatus.OFFLINE,
                tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = true),
            )
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.MISSING, networkMissing.status)

        val networkUnknown = RideReadinessAssessment.assess(
            RideReadinessInput(
                internet = ConnectivityStatus.UNKNOWN,
                tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = true),
            )
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.UNKNOWN, networkUnknown.status)
    }

    @Test
    fun voiceReportsKnownMissingEngineBeforeUnknownLanguage() {
        val failedEngine = RideReadinessAssessment.assess(
            RideReadinessInput(tts = TtsFacts(engineInitialized = false, languageAvailable = null))
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.MISSING, failedEngine.status)
        assertEquals(ReadinessDetail.VOICE_ENGINE_NOT_READY, failedEngine.detail)

        val languageUnobserved = RideReadinessAssessment.assess(
            RideReadinessInput(tts = TtsFacts(engineInitialized = true, languageAvailable = null))
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.UNKNOWN, languageUnobserved.status)
        assertEquals(ReadinessDetail.VOICE_UNKNOWN, languageUnobserved.detail)
    }

    @Test
    fun unobservedDefaultVoiceNetworkRequirementIsUnknownNotReady() {
        val item = RideReadinessAssessment.assess(
            RideReadinessInput(
                internet = ConnectivityStatus.VALIDATED,
                tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = null),
            )
        ).item(ReadinessKind.VOICE)
        assertEquals(ReadinessStatus.UNKNOWN, item.status)
        assertEquals(ReadinessDetail.VOICE_UNKNOWN, item.detail)
    }

    @Test
    fun knownEmptyOfflineAreasAreOptionalWithoutFixOrStorageVerification() {
        val noFix = RideReadinessAssessment.assess(
            RideReadinessInput(offlineAreas = emptyList(), knownPosition = null, offlineStorageVerified = null)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.OPTIONAL, noFix.status)
        assertEquals(ReadinessDetail.OFFLINE_NO_AREAS, noFix.detail)

        val unverifiedStorage = RideReadinessAssessment.assess(
            RideReadinessInput(knownPosition = here, offlineAreas = listOf(area), offlineStorageVerified = null)
        ).item(ReadinessKind.OFFLINE_MAP)
        assertEquals(ReadinessStatus.UNKNOWN, unverifiedStorage.status)
        assertEquals(ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED, unverifiedStorage.detail)
    }

    @Test
    fun sosConfigurationIsOptionalAndNeverAReadinessGuarantee() {
        val configured = RideReadinessAssessment.assess(
            RideReadinessInput(sosContactsConfigured = true, sosSmsGranted = true)
        ).item(ReadinessKind.SOS)
        assertEquals(ReadinessStatus.OPTIONAL, configured.status)
        assertFalse(configured.status == ReadinessStatus.READY)

        val smsMissing = RideReadinessAssessment.assess(
            RideReadinessInput(sosContactsConfigured = true, sosSmsGranted = false)
        ).item(ReadinessKind.SOS)
        assertEquals(ReadinessDetail.SOS_PERMISSION_MISSING, smsMissing.detail)

        val smsUnknown = RideReadinessAssessment.assess(
            RideReadinessInput(sosContactsConfigured = true, sosSmsGranted = null)
        ).item(ReadinessKind.SOS)
        assertEquals(ReadinessStatus.UNKNOWN, smsUnknown.status)
        assertEquals(ReadinessDetail.SOS_PERMISSION_UNKNOWN, smsUnknown.detail)
    }
}
