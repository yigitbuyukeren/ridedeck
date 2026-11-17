package com.ridedeck.app.ui

import androidx.compose.ui.graphics.Color
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.speedLimitSpeech
import com.ridedeck.app.VoiceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure UI contrast and policy checks: no Android context, no Compose rule. Every number comes from
 * the real palette/helper implementation, so a low-contrast accent or a permission/voice regression
 * fails here before it reaches a rider.
 */
class UiContrastTest {
    private val day = motoPalette(true)
    private val night = motoPalette(false)

    @Test fun relativeLuminanceMatchesTheWcagEndpoints() {
        assertEquals(0f, relativeLuminance(Color.Black), 0.0001f)
        assertEquals(1f, relativeLuminance(Color.White), 0.0001f)
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.0001f)
    }

    @Test fun dayAndNightCardTextClearAaAndMostlyAaa() {
        listOf("day" to day, "night" to night).forEach { (name, palette) ->
            val text = contrastRatio(palette.text, palette.card)
            assertTrue("$name primary text contrast $text", text >= 7f)
            // Secondary text is alpha-composited; an opaque lower bound is checked on the tertiary
            // colour the day palette uses for the same role.
            assertTrue("$name tertiary text contrast", contrastRatio(palette.textTertiary, palette.card) >= 4.5f)
        }
    }

    @Test fun filledButtonsChooseTheHigherContrastForeground() {
        listOf(
            "green" to MotoColors.Green,
            "orange" to MotoColors.Orange,
            "blue" to MotoColors.Blue,
            "red" to MotoColors.Red,
        ).forEach { (name, fill) ->
            val foreground = readableOn(fill)
            val ratio = contrastRatio(fill, foreground)
            assertTrue("$name fill contrast $ratio", ratio >= 4.5f)
            // All four accents are light enough that black wins; this is the current measured result.
            assertEquals("$name should use black content", Color.Black, foreground)
        }
        // Dark fills keep white glyphs.
        assertEquals(Color.White, readableOn(night.card))
        assertEquals(Color.White, readableOn(Color(0xFF101114)))
        assertEquals(Color.Black, readableOn(Color.White))
        assertEquals(Color.Black, readableOn(Color(0xFFE3E7EE)))
    }

    @Test fun warningAndStatusTextStayReadableOnThemedCards() {
        assertTrue(
            "day warning on card",
            contrastRatio(warningColor(day), day.card) >= 4.5f,
        )
        assertTrue(
            "day warning on cardHigh",
            contrastRatio(warningColor(day), day.cardHigh) >= 4.5f,
        )
        assertTrue(
            "night warning on card",
            contrastRatio(warningColor(night), night.card) >= 4.5f,
        )
        assertTrue(
            "day success on card",
            contrastRatio(successColor(day), day.card) >= 4.5f,
        )
        assertTrue(
            "day success on cardHigh",
            contrastRatio(successColor(day), day.cardHigh) >= 4.5f,
        )
        assertTrue(
            "day danger on card",
            contrastRatio(dangerColor(day), day.card) >= 4.5f,
        )
        assertTrue(
            "night warning on card",
            contrastRatio(warningColor(night), night.card) >= 4.5f,
        )
        assertTrue(
            "night success on card",
            contrastRatio(successColor(night), night.card) >= 4.5f,
        )
        assertTrue(
            "night danger on card",
            contrastRatio(dangerColor(night), night.card) >= 4.5f,
        )
    }

    @Test fun speedLimitSpeechUsesTheRidersUnit() {
        val metric = speedLimitSpeech(96, miles = false)
        assertEquals(96, metric.value)
        assertEquals(R.string.speed_unit, metric.unitRes)

        val imperial = speedLimitSpeech(100, miles = true)
        assertEquals(62, imperial.value)
        assertEquals(R.string.speed_unit_mph, imperial.unitRes)
    }

    @Test fun voiceGateBlocksBackgroundWeatherAndThermalSpeech() {
        assertFalse(VoiceGate.maySpeak(onScreen = false, callIdle = true))
        assertFalse(VoiceGate.maySpeak(onScreen = true, callIdle = false))
        assertFalse(VoiceGate.maySpeak(onScreen = true, callIdle = true, featureEnabled = false))
        assertTrue(VoiceGate.maySpeak(onScreen = true, callIdle = true))
        assertTrue(VoiceGate.maySpeak(onScreen = true, callIdle = true, featureEnabled = true))
    }

    @Test fun speedLimitSpeechMayContinueWhileNavigationRuns() {
        assertTrue(VoiceGate.maySpeakSpeedLimit(navigating = true, callIdle = true))
        assertFalse(VoiceGate.maySpeakSpeedLimit(navigating = false, callIdle = true))
        assertFalse(VoiceGate.maySpeakSpeedLimit(navigating = true, callIdle = false))
    }

    @Test fun permissionPolicyOnlyAsksOnceTheSystemStoppedShowingTheRationale() {
        assertEquals(
            PermissionRequestAction.NONE,
            PermissionRequestPolicy.decide(granted = true, showRationale = false, askedBefore = false),
        )
        assertEquals(
            "the first ask must still show the system dialog",
            PermissionRequestAction.REQUEST,
            PermissionRequestPolicy.decide(granted = false, showRationale = false, askedBefore = false),
        )
        assertEquals(
            PermissionRequestAction.REQUEST,
            PermissionRequestPolicy.decide(granted = false, showRationale = true, askedBefore = true),
        )
        assertEquals(
            "permanently denied: only Settings can fix it",
            PermissionRequestAction.OPEN_APP_SETTINGS,
            PermissionRequestPolicy.decide(granted = false, showRationale = false, askedBefore = true),
        )
        assertEquals(
            "coarse-only location needs the system page even on the first tap",
            PermissionRequestAction.OPEN_APP_SETTINGS,
            PermissionRequestPolicy.decide(granted = false, showRationale = false, askedBefore = false, settingsOnly = true),
        )
    }

    @Test fun offlineCardKnowsWhenEveryOfferedRadiusIsBlocked() {
        val center = GeoPoint(41.0, 29.0)
        assertTrue(offlineRadiiBlocked(center))
        assertFalse("an empty radius list is not a blocked all-case", offlineRadiiBlocked(center, emptyList()))
    }

    @Test fun messageBannerMovesOutOfTheGuidedMapButStaysOnTheDashboard() {
        assertTrue(messageBannerVisible(showSettings = false, showMap = false, navigating = false))
        assertTrue(messageBannerVisible(showSettings = false, showMap = false, navigating = true))
        assertTrue(messageBannerVisible(showSettings = false, showMap = true, navigating = false))
        assertFalse(messageBannerVisible(showSettings = false, showMap = true, navigating = true))
        assertFalse(messageBannerVisible(showSettings = true, showMap = false, navigating = false))
    }
}
