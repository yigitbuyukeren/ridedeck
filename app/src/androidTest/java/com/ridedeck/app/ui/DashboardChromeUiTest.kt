package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.BatteryState
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Dashboard chrome owned by the navigation/layout pass: the navigation card's active and inactive
 * hierarchy, the guided summary rows, and the dock/status targets at a real font-2 scale.
 */
class DashboardChromeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int) = context.getString(id)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun renderAt(width: Dp, height: Dp, content: @Composable () -> Unit) =
        renderAtScale(width, height, 2f, content)

    private fun renderAtScale(width: Dp, height: Dp, fontScale: Float, content: @Composable () -> Unit) {
        val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
        val density = Density(context.createConfigurationContext(configuration))
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.size(width, height)) { content() }
                }
            }
        }
    }

    private fun assertTargetAtLeast64(description: String, node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description width is ${bounds.right - bounds.left}", bounds.right - bounds.left >= 64.dp)
        assertTrue("$description height is ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
    }

    private fun assertInside(description: String, node: SemanticsNodeInteraction, container: DpRect) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description left ${bounds.left} < ${container.left}", bounds.left >= container.left - 0.5.dp)
        assertTrue("$description right ${bounds.right} > ${container.right}", bounds.right <= container.right + 0.5.dp)
        assertTrue("$description top ${bounds.top} < ${container.top}", bounds.top >= container.top - 0.5.dp)
        assertTrue("$description bottom ${bounds.bottom} > ${container.bottom}", bounds.bottom <= container.bottom + 0.5.dp)
    }

    private fun lineCount(node: SemanticsNodeInteraction): Int {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount ?: 0
    }

    @Test fun inactiveNavCardKeepsTitleAndQuickActionsAt64InsideTheCardAtFont2() {
        var opened = 0
        var goTo: Place? = null
        val places = listOf(
            SavedPlace(PlaceKind.HOME, Place("Ev", "", GeoPoint(41.0, 29.0))),
            SavedPlace(PlaceKind.WORK, Place("İş", "", GeoPoint(41.1, 29.1))),
        )
        renderAt(380.dp, 170.dp) {
            NavCard(
                nav = NavUi(), places = places, weather = null, me = LocationState(),
                isDay = false, lowPower = false,
                onOpenMap = { opened++ }, onGoTo = { goTo = it },
                modifier = Modifier.size(380.dp, 170.dp).testTag("home-nav-card"),
            )
        }
        val card = compose.onNodeWithTag("home-nav-card").getUnclippedBoundsInRoot()
        compose.onNodeWithText(string(R.string.nav_title), substring = true).assertIsDisplayed()
        val home = compose.onNodeWithText(string(R.string.go_home))
        val work = compose.onNodeWithText(string(R.string.go_work))
        assertTargetAtLeast64("home action", home)
        assertTargetAtLeast64("work action", work)
        assertInside("home action", home, card)
        assertInside("work action", work, card)

        home.performClick()
        compose.runOnIdle {
            assertEquals("Ev", goTo?.name)
            assertEquals(0, opened)
        }
    }

    @Test fun guidedCardKeepsProgressPinnedAndTheFullManeuverScrollableAtFont2() {
        val instruction = mutableStateOf(
            "Atatürk Caddesi üzerinden sağa dönün, köprüyü geçtikten sonra sahildeki kavşaktan sola dönün ve otoparka girin",
        )
        renderAt(380.dp, 170.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp).testTag("guided-card")) {
                NavGuidedContent(
                    icon = maneuverIcon(10),
                    distance = "140 m",
                    instruction = instruction.value,
                    isDay = false,
                    remainingDistance = "2,4 km",
                    arrival = "14:32",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val card = compose.onNodeWithTag("guided-card").getUnclippedBoundsInRoot()
        val scroll = compose.onNodeWithTag("nav-maneuver-scroll").getUnclippedBoundsInRoot()
        val progress = compose.onNodeWithText("14:32")
        progress.assertIsDisplayed()
        assertInside("arrival", progress, card)
        assertTrue("arrival overlaps the scrolling maneuver area", progress.getUnclippedBoundsInRoot().top >= scroll.bottom - 0.5.dp)

        val tr = compose.onNodeWithText(instruction.value, substring = true)
        assertTrue("guided instruction should lay out beyond two lines", lineCount(tr) > 2)
        compose.onNodeWithTag("nav-maneuver-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        tr.assertIsDisplayed()
        val trBounds = tr.getUnclippedBoundsInRoot()
        assertTrue("TR instruction right ${trBounds.right}", trBounds.right <= card.right + 0.5.dp)
        progress.assertIsDisplayed()

        val english = "Turn right onto Long Waterfront Avenue, cross the bridge and take the second exit at the " +
            "roundabout before entering the covered parking area."
        compose.runOnIdle { instruction.value = english }
        compose.waitForIdle()
        val en = compose.onNodeWithText(english, substring = true)
        compose.onNodeWithTag("nav-maneuver-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        en.assertIsDisplayed()
        val enBounds = en.getUnclippedBoundsInRoot()
        assertTrue("EN instruction right ${enBounds.right}", enBounds.right <= card.right + 0.5.dp)
        progress.assertIsDisplayed()
    }

    @Test fun guidedCardReusesTheExistingDayManeuverScrim() {
        val isDay = mutableStateOf(true)
        renderAtScale(380.dp, 170.dp, 1f) {
            Box(Modifier.fillMaxSize().background(Color(0xFFDCE4EE))) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    NavGuidedContent(
                        icon = maneuverIcon(10),
                        distance = "140 m",
                        instruction = "Büyükkuyu Sokağına doğru sağa dönün",
                        isDay = isDay.value,
                        remainingDistance = "2,4 km",
                        arrival = "14:32",
                        modifier = Modifier.fillMaxSize(),
                        maneuverModifier = Modifier.testTag("nav-maneuver"),
                    )
                }
            }
        }
        compose.waitForIdle()
        val dayMean = meanBrightness(compose.onNodeWithTag("nav-maneuver").captureToImage().toPixelMap())
        compose.runOnIdle { isDay.value = false }
        compose.waitForIdle()
        val nightMean = meanBrightness(compose.onNodeWithTag("nav-maneuver").captureToImage().toPixelMap())
        assertTrue("day maneuver row needs its existing dark scrim, mean was $dayMean", dayMean < 0.65f)
        assertTrue("day $dayMean should be darker than night $nightMean", dayMean + 0.20f < nightMean)
    }

    private fun meanBrightness(pixels: PixelMap): Float {
        var sum = 0f
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                sum += 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
                count++
            }
        }
        return sum / count
    }

    @Test fun dockTilesStayAt64AndTheStatusIsPinnedAt411Font2() {
        renderAt(96.dp, 411.dp) {
            Dock(
                battery = BatteryState(percent = 85, tempC = 31.4f, charging = true, hasReading = true),
                onNavigation = {}, onMusic = {}, onPhone = {}, onSettings = {},
                active = DockTab.NAV,
            )
        }
        val dock = compose.onNodeWithTag("dock").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("dock-status")
        status.assertIsDisplayed()
        assertInside("dock status", status, dock)
        val statusBounds = status.getUnclippedBoundsInRoot()
        assertTrue("dock status must have a measured height", statusBounds.bottom > statusBounds.top)

        val labels = listOf(
            string(R.string.nav_title),
            string(R.string.dock_music),
            string(R.string.perm_phone),
            string(R.string.apps_settings),
        )
        // At font-2 the inherited 24 sp line height makes the status block taller, so the rail scrolls;
        // every target is still measured at >=64 dp and stays inside the dock after scrolling.
        labels.forEach { label ->
            val tile = compose.onNodeWithContentDescription(label)
            tile.performScrollTo().assertIsDisplayed()
            assertTargetAtLeast64(label, tile)
            assertInside("$label after scroll", tile, dock)
            status.assertIsDisplayed()
        }

        val battery = compose.onNodeWithContentDescription(string(R.string.battery_status_charging_fmt, 85))
        battery.assertIsDisplayed()
        assertInside("battery status", battery, statusBounds)
    }

    @Test fun dockTilesAndStatusDoNotOverlapAtFont1() {
        renderAtScale(96.dp, 411.dp, 1f) {
            Dock(
                battery = BatteryState(percent = 85, tempC = 31.4f, charging = true, hasReading = true),
                onNavigation = {}, onMusic = {}, onPhone = {}, onSettings = {},
                active = DockTab.NAV,
            )
        }
        val dock = compose.onNodeWithTag("dock").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("dock-status")
        status.assertIsDisplayed()
        assertInside("dock status", status, dock)
        val statusBounds = status.getUnclippedBoundsInRoot()
        listOf(
            string(R.string.nav_title),
            string(R.string.dock_music),
            string(R.string.perm_phone),
            string(R.string.apps_settings),
        ).forEach { label ->
            val tile = compose.onNodeWithContentDescription(label)
            assertTargetAtLeast64(label, tile)
            assertInside(label, tile, dock)
        }
        val lastTile = compose.onNodeWithContentDescription(string(R.string.apps_settings)).getUnclippedBoundsInRoot()
        assertTrue("last tile overlaps the status block", lastTile.bottom <= statusBounds.top + 0.5.dp)
    }

    @Test fun dockKeepsEvery64TargetReachableAndTheStatusPinnedAt320Height() {
        renderAt(96.dp, 320.dp) {
            Dock(
                battery = BatteryState(percent = 12, tempC = 46.5f, charging = false, hasReading = true),
                onNavigation = {}, onMusic = {}, onPhone = {}, onSettings = {},
                active = DockTab.MUSIC,
            )
        }
        val dock = compose.onNodeWithTag("dock").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("dock-status")
        status.assertIsDisplayed()
        assertInside("pinned dock status", status, dock)

        val labels = listOf(
            string(R.string.nav_title),
            string(R.string.dock_music),
            string(R.string.perm_phone),
            string(R.string.apps_settings),
        )
        labels.forEach { label ->
            val tile = compose.onNodeWithContentDescription(label)
            // The action rail scrolls on a 320 dp screen, so each tile is brought fully into view first.
            tile.performScrollTo().assertIsDisplayed()
            assertTargetAtLeast64(label, tile)
            assertInside("$label after scroll", tile, dock)
            status.assertIsDisplayed()
        }
        assertInside("pinned dock status after rail scroll", status, dock)
    }
}
