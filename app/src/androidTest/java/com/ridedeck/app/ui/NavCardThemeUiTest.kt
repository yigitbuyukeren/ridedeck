package com.ridedeck.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Day/night regression for the dashboard's static navigation map tile (no live navigation). */
class NavCardThemeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun staticMapTileAndTitleFollowDashboardTheme() {
        val day = mutableStateOf(true)
        compose.setContent {
            MotoTheme(isDay = day.value) {
                NavCard(
                    nav = NavUi(), places = emptyList(), weather = null,
                    me = LocationState(), isDay = day.value, lowPower = false,
                    onOpenMap = {}, onGoTo = {},
                    modifier = Modifier.size(420.dp, 260.dp).testTag(NAV_CARD),
                )
            }
        }
        compose.waitForIdle()
        val dayPixels = compose.onNodeWithTag(NAV_CARD).captureToImage().toPixelMap()

        compose.runOnIdle { day.value = false }
        compose.waitForIdle()
        val nightPixels = compose.onNodeWithTag(NAV_CARD).captureToImage().toPixelMap()

        val dayTile = averageBrightness(dayPixels, 0.88f, 0.58f)
        val nightTile = averageBrightness(nightPixels, 0.88f, 0.58f)
        assertTrue("day static map tile should be light, was $dayTile", dayTile > 0.65f)
        assertTrue("night static map tile should stay dark, was $nightTile", nightTile < 0.30f)

        val dayTitle = brightnessRange(dayPixels, 0.07f, 0.55f, 0.08f, 0.22f)
        val nightTitle = brightnessRange(nightPixels, 0.07f, 0.55f, 0.08f, 0.22f)
        assertTrue("day title needs dark text on the light tile, darkest was ${dayTitle.first}", dayTitle.first < 0.25f)
        assertTrue("night title needs light text on the dark tile, lightest was ${nightTitle.second}", nightTitle.second > 0.75f)
    }

    private fun averageBrightness(pixels: PixelMap, xFraction: Float, yFraction: Float): Float {
        val cx = (pixels.width * xFraction).toInt()
        val cy = (pixels.height * yFraction).toInt()
        var sum = 0f
        var count = 0
        for (y in (cy - 4)..(cy + 4)) {
            if (y !in 0 until pixels.height) continue
            for (x in (cx - 4)..(cx + 4)) {
                if (x !in 0 until pixels.width) continue
                sum += brightness(pixels[x, y])
                count++
            }
        }
        return sum / count
    }

    private fun brightnessRange(
        pixels: PixelMap,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Pair<Float, Float> {
        var min = 1f
        var max = 0f
        for (y in (pixels.height * top).toInt() until (pixels.height * bottom).toInt()) {
            for (x in (pixels.width * left).toInt() until (pixels.width * right).toInt()) {
                val value = brightness(pixels[x, y])
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        return min to max
    }

    private fun brightness(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        const val NAV_CARD = "navCardTheme"
    }
}
