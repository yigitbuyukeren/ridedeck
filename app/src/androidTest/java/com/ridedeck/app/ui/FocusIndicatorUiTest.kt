package com.ridedeck.app.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Keyboard/D-pad focus must be visible without changing the existing click/haptic path. */
class FocusIndicatorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun focusedPillButtonShowsNightPaletteRing() {
        renderWithConverterFont2 {
            MotoTheme(isDay = false) {
                Row {
                    PillButton(
                        text = "Focus",
                        color = Color.Black,
                        onClick = {},
                        modifier = Modifier.size(240.dp, 72.dp).testTag(PILL),
                    )
                    PillButton(
                        text = "Idle",
                        color = Color.Black,
                        onClick = {},
                        modifier = Modifier.size(240.dp, 72.dp).testTag(IDLE),
                    )
                }
            }
        }
        compose.waitForIdle()
        focusByTag(PILL)
        compose.onNodeWithTag(PILL).assertIsFocused()
        compose.onNodeWithTag(IDLE).assertIsNotFocused()
        val focused = edgeBrightness(compose.onNodeWithTag(PILL).captureToImage().toPixelMap(), max = true)
        val idle = edgeBrightness(compose.onNodeWithTag(IDLE).captureToImage().toPixelMap(), max = true)

        assertTrue("night focused edge should stand out from idle, focused=$focused idle=$idle", focused > idle + 0.5f)
        assertTrue("night focus ring should be clearly visible, was $focused", focused > 0.8f)
    }

    @Test
    fun focusedPillButtonShowsDayPaletteRing() {
        renderWithConverterFont2 {
            MotoTheme(isDay = true) {
                Row {
                    PillButton(
                        text = "Focus",
                        color = Color.White,
                        textColor = Color.Black,
                        onClick = {},
                        modifier = Modifier.size(240.dp, 72.dp).testTag(PILL),
                    )
                    PillButton(
                        text = "Idle",
                        color = Color.White,
                        textColor = Color.Black,
                        onClick = {},
                        modifier = Modifier.size(240.dp, 72.dp).testTag(IDLE),
                    )
                }
            }
        }
        compose.waitForIdle()
        focusByTag(PILL)
        compose.onNodeWithTag(PILL).assertIsFocused()
        compose.onNodeWithTag(IDLE).assertIsNotFocused()
        val focused = edgeBrightness(compose.onNodeWithTag(PILL).captureToImage().toPixelMap(), max = false)
        val idle = edgeBrightness(compose.onNodeWithTag(IDLE).captureToImage().toPixelMap(), max = false)

        assertTrue("day focused edge should stand out from idle, focused=$focused idle=$idle", focused < idle - 0.5f)
        assertTrue("day focus ring should be clearly visible, was $focused", focused < 0.2f)
    }

    @Test
    fun focusedPressKeyAlsoShowsRing() {
        renderWithConverterFont2 {
            MotoTheme(isDay = false) {
                Box(
                    Modifier.size(120.dp).background(Color.Black)
                        .testTag(KEY)
                        .pressKey(enabled = true, onClick = {}, onLongClick = null)
                )
            }
        }
        compose.waitForIdle()
        val before = maxBrightness(compose.onNodeWithTag(KEY).captureToImage().toPixelMap(), 0f, 0.05f, 0.2f, 0.8f)

        focusByTag(KEY)
        compose.onNodeWithTag(KEY).assertIsFocused()
        val after = maxBrightness(compose.onNodeWithTag(KEY).captureToImage().toPixelMap(), 0f, 0.05f, 0.2f, 0.8f)

        assertTrue("pressKey focus should share the visible ring, before=$before after=$after", after > before + 0.5f)
    }

    @Test
    fun hapticClickableKeepsSingleClickAction() {
        var clicks = 0
        renderWithConverterFont2 {
            MotoTheme {
                Box(Modifier.size(160.dp).testTag(HAPTIC_CLICK).hapticClickable { clicks++ })
            }
        }

        compose.onNodeWithTag(HAPTIC_CLICK).performTouchInput { click() }
        compose.runOnIdle { assertEquals("hapticClickable must still click once", 1, clicks) }
    }

    @Test
    fun focusedHapticClickableAlsoShowsRing() {
        renderWithConverterFont2 {
            MotoTheme(isDay = false) {
                Box(
                    Modifier.size(160.dp).background(Color.Black)
                        .testTag(HAPTIC)
                        .hapticClickable {}
                )
            }
        }
        compose.waitForIdle()
        val before = maxBrightness(compose.onNodeWithTag(HAPTIC).captureToImage().toPixelMap(), 0f, 0.05f, 0.2f, 0.8f)

        focusByTag(HAPTIC)
        compose.onNodeWithTag(HAPTIC).assertIsFocused()
        val after = maxBrightness(compose.onNodeWithTag(HAPTIC).captureToImage().toPixelMap(), 0f, 0.05f, 0.2f, 0.8f)

        assertTrue("hapticClickable focus should share the ring, before=$before after=$after", after > before + 0.5f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tabMovesFocusBetweenPressTargetsWithoutClicking() {
        var clicks = 0
        renderWithConverterFont2 {
            MotoTheme {
                Column {
                    Box(Modifier.size(120.dp).testTag(FIRST).pressClickable(onClick = { clicks++ }))
                    Box(Modifier.size(120.dp).testTag(SECOND).pressClickable(onClick = { clicks++ }))
                }
            }
        }

        focusByTag(FIRST)
        compose.onNodeWithTag(FIRST).assertIsFocused()

        compose.onNodeWithTag(FIRST).performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()

        compose.onNodeWithTag(SECOND).assertIsFocused()
        compose.runOnIdle { assertEquals("focus alone must not click", 0, clicks) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dpadRightMovesFocusBetweenPressTargetsWithoutClicking() {
        var clicks = 0
        renderWithConverterFont2 {
            MotoTheme {
                Row {
                    Box(Modifier.size(120.dp).testTag(FIRST).pressClickable(onClick = { clicks++ }))
                    Box(Modifier.size(120.dp).testTag(SECOND).pressClickable(onClick = { clicks++ }))
                }
            }
        }

        focusByTag(FIRST)
        compose.onNodeWithTag(FIRST).assertIsFocused()

        compose.onNodeWithTag(FIRST).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNodeWithTag(SECOND).assertIsFocused()
        compose.runOnIdle { assertEquals("D-pad focus alone must not click", 0, clicks) }
    }

    @Test
    fun focusAloneDoesNotClickOrPulse() {
        var clicks = 0
        val pulses = AtomicInteger()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                pulses.incrementAndGet()
            }
        }
        renderWithConverterFont2 {
            MotoTheme {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    Box(Modifier.size(120.dp).testTag(FOCUS_ONLY).pressClickable { clicks++ })
                }
            }
        }

        focusByTag(FOCUS_ONLY)
        compose.onNodeWithTag(FOCUS_ONLY).assertIsFocused()

        compose.runOnIdle {
            assertEquals("focus alone must not click", 0, clicks)
            assertEquals("focus alone must not pulse", 0, pulses.get())
        }
    }

    @Test
    fun disabledPressTargetStaysDisabledAndDoesNotClick() {
        var clicks = 0
        renderWithConverterFont2 {
            MotoTheme {
                Box(Modifier.size(120.dp).testTag(DISABLED).pressClickable(enabled = false) { clicks++ })
            }
        }

        compose.onNodeWithTag(DISABLED).assertIsNotEnabled()
        compose.onNodeWithTag(DISABLED).performTouchInput { click() }
        compose.runOnIdle { assertEquals("disabled target must not click", 0, clicks) }
    }

    private fun focusByTag(tag: String) {
        // Compose test's AndroidComposeView is not the Android focus target until it is made
        // focusable in touch mode; the real dashboard owns view focus already.
        compose.runOnUiThread {
            val root = compose.activity.window.decorView
            root.requestFocus()
            findComposeView(root)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocusFromTouch()
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun findComposeView(view: View): View? = when {
        view.javaClass.name.contains("AndroidComposeView", ignoreCase = true) -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findComposeView(view.getChildAt(it)) }
        else -> null
    }

    private fun renderWithConverterFont2(content: @Composable () -> Unit) {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val androidDensity = Density(context.createConfigurationContext(configuration))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides androidDensity) {
                content()
            }
        }
    }

    /** Sample the left edge on the vertical centre only, avoiding the pill's rounded corners. */
    private fun edgeBrightness(pixels: PixelMap, max: Boolean): Float {
        val y = pixels.height / 2
        var value = if (max) 0f else 1f
        for (x in 0 until (pixels.width * 0.03f).toInt()) {
            val pixel = pixels[x, y]
            val brightness = 0.2126f * pixel.red + 0.7152f * pixel.green + 0.0722f * pixel.blue
            value = if (max) maxOf(value, brightness) else minOf(value, brightness)
        }
        return value
    }

    private fun maxBrightness(
        pixels: PixelMap,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Float = brightnessRange(pixels, left, right, top, bottom).second

    private fun minBrightness(
        pixels: PixelMap,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Float = brightnessRange(pixels, left, right, top, bottom).first

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
                val color = pixels[x, y]
                val value = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        return min to max
    }

    private companion object {
        const val PILL = "focusedPill"
        const val IDLE = "idlePill"
        const val HAPTIC = "focusedHaptic"
        const val HAPTIC_CLICK = "hapticClickTarget"
        const val KEY = "focusedKey"
        const val FIRST = "firstPressTarget"
        const val SECOND = "secondPressTarget"
        const val FOCUS_ONLY = "focusOnlyTarget"
        const val DISABLED = "disabledPressTarget"
    }
}
