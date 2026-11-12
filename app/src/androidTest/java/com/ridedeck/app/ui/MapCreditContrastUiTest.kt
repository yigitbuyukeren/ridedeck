package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The fixed map credit must stay legible on both light day tiles and black night tiles. */
class MapCreditContrastUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creditHasADarkBackingAndLightGlyphsOnDayAndNightBackdrops() {
        val backdrop = mutableStateOf(Color.White)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(backdrop.value), contentAlignment = Alignment.Center) {
                MapCredit(Modifier.testTag(CREDIT))
            }
        }
        compose.waitForIdle()
        val day = captureCredit()

        compose.runOnIdle { backdrop.value = Color.Black }
        compose.waitForIdle()
        val night = captureCredit()

        for ((name, pixels) in listOf("day" to day, "night" to night)) {
            val dark = darkFraction(pixels)
            assertTrue("$name credit needs dark backing, dark fraction=$dark", dark > 0.40f)
            assertTrue("$name credit needs dark backing, min=${minBrightness(pixels)}", minBrightness(pixels) < 0.20f)
            assertTrue("$name credit needs white glyphs, max=${maxBrightness(pixels)}", maxBrightness(pixels) > 0.90f)
            assertTrue("$name credit glyph pixels=${brightPixels(pixels)}", brightPixels(pixels) > 20)
        }
    }

    @Test
    fun fontScaleIsCappedSoTheFixedFooterStaysShallow() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 2f)) {
                Box(Modifier.background(Color.Black)) {
                    MapCredit(Modifier.testTag(CREDIT))
                }
            }
        }
        val bounds = compose.onNodeWithTag(CREDIT).getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("credit footer height $height should stay small", height <= 22.dp)
    }

    @Test
    fun narrowFooterKeepsTheWholeCreditWithoutVisualOverflowWithAndroidFontScale2() {
        compose.setContent {
            val context = LocalContext.current
            val currentConfiguration = LocalConfiguration.current
            val scaledContext = remember(context, currentConfiguration) {
                val configuration = Configuration(currentConfiguration).apply { fontScale = 2f }
                context.createConfigurationContext(configuration)
            }
            CompositionLocalProvider(LocalDensity provides Density(scaledContext)) {
                Box(Modifier.width(220.dp).background(Color.White)) {
                    MapCredit(Modifier.testTag(CREDIT))
                }
            }
        }
        compose.onNodeWithText(CREDIT_TEXT).assertExists()
        val node = compose.onNodeWithText(CREDIT_TEXT).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(CREDIT_TEXT, layout.layoutInput.text.text)
        assertEquals(Int.MAX_VALUE, layout.layoutInput.maxLines)
        assertTrue("credit must wrap rather than ellipsize", layout.layoutInput.overflow == TextOverflow.Clip)
        assertFalse("credit must not clip or ellipsize: ${layout.lineCount} lines", layout.hasVisualOverflow)
    }

    private fun captureCredit(): PixelMap =
        compose.onNodeWithTag(CREDIT).captureToImage().toPixelMap()

    private fun darkFraction(pixels: PixelMap): Float {
        var dark = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (brightness(pixels[x, y]) < 0.25f) dark++
            }
        }
        return dark.toFloat() / (pixels.width * pixels.height)
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

    private fun brightPixels(pixels: PixelMap): Int {
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (brightness(pixels[x, y]) > 0.75f) count++
            }
        }
        return count
    }

    private fun brightness(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        const val CREDIT = "mapCreditContrast"
        const val CREDIT_TEXT = "OpenFreeMap © OpenMapTiles · © OpenStreetMap"
    }
}
