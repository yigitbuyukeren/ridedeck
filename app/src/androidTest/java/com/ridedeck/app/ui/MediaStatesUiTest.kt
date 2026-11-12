package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.MediaRepository
import com.ridedeck.app.data.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Media permission, empty and playing presentation with fake callbacks; no player or system role is touched. */
class MediaStatesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media by lazy { MediaRepository(context) }
    private fun string(id: Int) = context.getString(id)

    private fun renderAt(width: Dp, height: Dp, fontScale: Float = 2f, content: @Composable () -> Unit) {
        val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
        val androidDensity = Density(context.createConfigurationContext(configuration))
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides androidDensity) {
                    Box(Modifier.size(width, height)) { content() }
                }
            }
        }
    }

    private fun assertTarget(description: String, container: DpRect? = null) {
        val node = compose.onNodeWithContentDescription(description)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description target width ${bounds.right - bounds.left}", bounds.right - bounds.left >= 64.dp)
        assertTrue("$description target height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
        if (container != null) {
            assertTrue(
                "$description target must stay inside the card",
                bounds.left >= container.left && bounds.right <= container.right &&
                    bounds.top >= container.top && bounds.bottom <= container.bottom,
            )
        }
    }

    @Test
    fun notificationAccessPromptExplainsAndCallsSettingsOnce() {
        var grants = 0
        var resumes = 0
        compose.setContent {
            MotoTheme {
                MediaCard(
                    state = MediaState(hasAccess = false),
                    media = media,
                    onGrantAccess = { grants++ },
                    onOpenNowPlaying = {},
                    onResumeLast = { resumes++ },
                    modifier = Modifier.size(560.dp, 300.dp),
                )
            }
        }

        compose.onNodeWithText(string(R.string.media_grant_prompt)).assertExists()
        compose.onNodeWithText(string(R.string.media_access_settings_hint)).assertExists()
        compose.onNodeWithText(string(R.string.grant_access)).performClick()

        compose.runOnIdle {
            assertEquals(1, grants)
            assertEquals(0, resumes)
        }
    }

    @Test
    fun emptySessionExplainsAndStartsLastAppOnce() {
        var resumes = 0
        compose.setContent {
            MotoTheme {
                MediaCard(
                    state = MediaState(hasAccess = true, hasSession = false),
                    media = media,
                    onGrantAccess = {},
                    onOpenNowPlaying = {},
                    onResumeLast = { resumes++ },
                    modifier = Modifier.size(560.dp, 300.dp),
                )
            }
        }

        compose.onNodeWithText(string(R.string.media_not_playing)).assertExists()
        compose.onNodeWithText(string(R.string.media_empty_hint)).assertExists()
        compose.onNodeWithText(string(R.string.play)).performClick()

        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test
    fun fullScreenPermissionAndEmptyStatesUseTheSameFakeActionsOnce() {
        var grants = 0
        var resumes = 0
        val state = mutableStateOf(MediaState(hasAccess = false))
        compose.setContent {
            MotoTheme {
                NowPlayingScreen(
                    state = state.value,
                    media = media,
                    onGrantAccess = { grants++ },
                    onResumeLast = { resumes++ },
                    modifier = Modifier.size(760.dp, 360.dp),
                )
            }
        }

        compose.onNodeWithText(string(R.string.media_access_settings_hint)).assertExists()
        compose.onNodeWithText(string(R.string.grant_access)).performClick()
        compose.runOnIdle { assertEquals(1, grants) }

        compose.runOnIdle { state.value = MediaState(hasAccess = true, hasSession = false) }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.media_not_playing)).assertExists()
        compose.onNodeWithText(string(R.string.media_empty_hint)).assertExists()
        compose.onNodeWithText(string(R.string.play)).performClick()
        compose.runOnIdle {
            assertEquals(1, grants)
            assertEquals(1, resumes)
        }
    }

    @Test
    fun longTrackTitleOpensNowPlayingOnce() {
        var opens = 0
        val longTitle = "Bu çok uzun bir parça adı: gece sürüşü için hazırlanmış özel bir kayıt"
        compose.setContent {
            MotoTheme {
                MediaCard(
                    state = MediaState(
                        hasAccess = true,
                        hasSession = true,
                        title = longTitle,
                        artist = "Uzun sanatçı adı",
                        durationMs = 240_000,
                    ),
                    media = media,
                    onGrantAccess = {},
                    onOpenNowPlaying = { opens++ },
                    onResumeLast = {},
                    modifier = Modifier.size(560.dp, 300.dp),
                )
            }
        }

        compose.onNodeWithText(longTitle).assertExists()
        compose.onNodeWithText(longTitle).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun fullScreenPlayingShowsLongTitleAndTimes() {
        val longTitle = "Bu çok uzun bir parça adı: gece sürüşü için hazırlanmış özel bir kayıt"
        compose.setContent {
            MotoTheme {
                NowPlayingScreen(
                    state = MediaState(
                        hasAccess = true,
                        hasSession = true,
                        title = longTitle,
                        artist = "Uzun sanatçı adı",
                        durationMs = 3_600_000,
                    ),
                    media = media,
                    onGrantAccess = {},
                    onResumeLast = {},
                    modifier = Modifier.size(760.dp, 360.dp),
                )
            }
        }

        compose.onNodeWithText(longTitle).assertExists()
        compose.onNodeWithText("0:00").assertExists()
        compose.onNodeWithText("60:00").assertExists()
    }

    @Test
    fun emptyCardBackgroundFollowsDayNightPalette() {
        val day = mutableStateOf(true)
        compose.setContent {
            MotoTheme(isDay = day.value) {
                MediaCard(
                    state = MediaState(hasAccess = true, hasSession = false),
                    media = media,
                    onGrantAccess = {},
                    onOpenNowPlaying = {},
                    onResumeLast = {},
                    modifier = Modifier.size(560.dp, 300.dp).testTag(MEDIA_CARD),
                )
            }
        }
        compose.waitForIdle()
        val dayPixels = compose.onNodeWithTag(MEDIA_CARD).captureToImage().toPixelMap()

        compose.runOnIdle { day.value = false }
        compose.waitForIdle()
        val nightPixels = compose.onNodeWithTag(MEDIA_CARD).captureToImage().toPixelMap()

        val dayBackground = averageBrightness(dayPixels, 0.03f, 0.5f)
        val nightBackground = averageBrightness(nightPixels, 0.03f, 0.5f)
        assertTrue("day media card should be light, was $dayBackground", dayBackground > 0.65f)
        assertTrue("night media card should be dark, was $nightBackground", nightBackground < 0.30f)
    }

    @Test
    fun cardPermissionPromptAt240x180KeepsHelpReachableAndActionVisibleAtAndroidFont2() {
        renderAt(240.dp, 180.dp) {
            MediaCard(
                state = MediaState(hasAccess = false),
                media = media,
                onGrantAccess = {},
                onOpenNowPlaying = {},
                onResumeLast = {},
                modifier = Modifier.fillMaxSize().testTag(MEDIA_CARD),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.media_access_settings_hint)).performScrollTo().assertIsDisplayed()
        val card = compose.onNodeWithTag(MEDIA_CARD).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText(string(R.string.grant_access)).getUnclippedBoundsInRoot()
        val actionHeight = action.bottom - action.top
        assertTrue("action height $actionHeight must stay in 64..84 dp", actionHeight >= 64.dp && actionHeight <= 84.dp)
        assertTrue("action must stay inside the card", action.top >= card.top && action.bottom <= card.bottom)
    }

    @Test
    fun narrowCardAtAndroidFont2HidesVolumeButKeepsThreeLargeTransportTargets() {
        renderAt(240.dp, 180.dp) {
            MediaCard(
                state = MediaState(
                    hasAccess = true, hasSession = true, title = "Track", artist = "Artist",
                    durationMs = 240_000,
                ),
                media = media,
                onGrantAccess = {},
                onOpenNowPlaying = {},
                onResumeLast = {},
                modifier = Modifier.fillMaxSize().testTag(MEDIA_CARD),
            )
        }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription(string(R.string.volume_down)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.volume_up)).assertCountEquals(0)
        val card = compose.onNodeWithTag(MEDIA_CARD).getUnclippedBoundsInRoot()
        assertTarget(string(R.string.media_previous), card)
        assertTarget(string(R.string.play), card)
        assertTarget(string(R.string.media_next), card)
    }

    @Test
    fun wideCardAtAndroidFont2KeepsAllFiveLargeTransportTargets() {
        renderAt(560.dp, 180.dp) {
            MediaCard(
                state = MediaState(
                    hasAccess = true, hasSession = true, title = "Track", artist = "Artist",
                    durationMs = 240_000,
                ),
                media = media,
                onGrantAccess = {},
                onOpenNowPlaying = {},
                onResumeLast = {},
                modifier = Modifier.fillMaxSize().testTag(MEDIA_CARD),
            )
        }
        compose.waitForIdle()

        val card = compose.onNodeWithTag(MEDIA_CARD).getUnclippedBoundsInRoot()
        for (description in listOf(
            R.string.volume_down, R.string.media_previous, R.string.play,
            R.string.media_next, R.string.volume_up,
        )) {
            assertTarget(string(description), card)
        }
    }

    @Test
    fun narrowFullScreenColumnAtAndroidFont2ReflowsControlsToAFullWidthBar() {
        renderAt(600.dp, 320.dp) {
            NowPlayingScreen(
                state = MediaState(
                    hasAccess = true, hasSession = true, title = "Track", artist = "Artist",
                    durationMs = 240_000,
                ),
                media = media,
                onGrantAccess = {},
                onResumeLast = {},
                modifier = Modifier.fillMaxSize().testTag(FULL_SCREEN),
            )
        }
        compose.waitForIdle()

        val parent = compose.onNodeWithTag(FULL_SCREEN).getUnclippedBoundsInRoot()
        assertTarget(string(R.string.volume_down), parent)
        assertTarget(string(R.string.media_previous), parent)
        assertTarget(string(R.string.play), parent)
        assertTarget(string(R.string.media_next), parent)
        assertTarget(string(R.string.volume_up), parent)
        compose.onNodeWithText("0:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4:00").performScrollTo().assertIsDisplayed()
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

    private fun brightness(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        const val MEDIA_CARD = "mediaCardStates"
        const val FULL_SCREEN = "fullScreenMedia"
    }
}
