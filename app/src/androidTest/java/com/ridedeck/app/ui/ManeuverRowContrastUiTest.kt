package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Contrast regression for the live-map manoeuvre row without MapLibre, network or a full screen. */
class ManeuverRowContrastUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dayManeuverTextGetsADarkScrimAndNightKeepsItsCurrentRow() {
        val day = mutableStateOf(true)
        compose.setContent {
            MotoTheme(isDay = day.value) {
                Box(
                    Modifier.background(Color(0xFFDCE4EE)).padding(20.dp).testTag(SAMPLE)
                ) {
                    ManeuverRow(
                        icon = maneuverIcon(10),
                        distance = "140 m",
                        instruction = INSTRUCTION,
                        isDay = day.value,
                    )
                }
            }
        }
        compose.waitForIdle()
        val dayPixels = compose.onNodeWithTag(SAMPLE).captureToImage().toPixelMap()
        // The whole sample has a light margin, so check the real instruction node itself.
        val instructionPixels = compose.onNodeWithText(INSTRUCTION).captureToImage().toPixelMap()

        compose.runOnIdle { day.value = false }
        compose.waitForIdle()
        val nightPixels = compose.onNodeWithTag(SAMPLE).captureToImage().toPixelMap()

        val dayMean = meanBrightness(dayPixels)
        val nightMean = meanBrightness(nightPixels)
        assertTrue("day row needs a dark scrim, mean was $dayMean", dayMean < 0.65f)
        assertTrue(
            "day $dayMean should be clearly darker than the same row at night $nightMean",
            dayMean + 0.25f < nightMean,
        )
        assertTrue("instruction glyphs should stay bright on the day scrim", maxBrightness(instructionPixels) > 0.80f)
        assertTrue("instruction text should sit on a dark backdrop", minBrightness(instructionPixels) < 0.30f)
    }

    private fun meanBrightness(pixels: PixelMap): Float {
        var sum = 0f
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                sum += brightness(pixels[x, y])
                count++
            }
        }
        return sum / count
    }

    private fun maxBrightness(pixels: PixelMap): Float {
        var max = 0f
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val value = brightness(pixels[x, y])
                if (value > max) max = value
            }
        }
        return max
    }

    private fun minBrightness(pixels: PixelMap): Float {
        var min = 1f
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val value = brightness(pixels[x, y])
                if (value < min) min = value
            }
        }
        return min
    }

    private fun brightness(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        const val SAMPLE = "maneuverRowContrast"
        const val INSTRUCTION = "Büyükkuyu Sokağına doğru sağa dönün"
    }
}
