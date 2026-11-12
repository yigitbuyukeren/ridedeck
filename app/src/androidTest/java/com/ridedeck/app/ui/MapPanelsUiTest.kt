package com.ridedeck.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.FuelUi
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.SavedPlace
import com.ridedeck.app.util.NavApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Route preview, search, arrival and fuel panel geometry with fake data. The panels are the parts
 * of [MapScreen] that hold typed input and the primary actions; MapLibre itself is not started here.
 */
class MapPanelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * These panels are designed for the landscape ride dashboard. Request landscape on the test
     * Activity only (never a global device setting) so a shared emulator left in portrait cannot
     * change the geometry result, and restore the prior Activity value after every test.
     */
    @Before fun forceLandscapeForThisTest() {
        originalOrientation = compose.activity.requestedOrientation
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    @After fun restoreActivityOrientation() {
        compose.activity.requestedOrientation = originalOrientation
        compose.waitForIdle()
    }

    private fun string(id: Int) = context.getString(id)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun renderAt(width: Dp, height: Dp, fontScale: Float = 2f, content: @Composable () -> Unit) {
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

    /**
     * Mirrors the real dashboard parent: screen width → dock rail → 16 dp end padding → map parent,
     * and the panel width derived from that **actual** parent (not the device configuration).
     */
    private fun renderInActualMapParent(screenWidth: Dp, screenHeight: Dp, content: @Composable () -> Unit) {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val density = Density(context.createConfigurationContext(configuration))
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.size(screenWidth, screenHeight)) {
                        Row(Modifier.fillMaxSize().padding(end = 16.dp)) {
                            Box(Modifier.width(96.dp).fillMaxHeight())
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("map-parent")) {
                                Column(Modifier.fillMaxHeight().width(mapPanelWidth(maxWidth)).padding(12.dp).testTag("test-map-panel")) {
                                    content()
                                }
                            }
                        }
                    }
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

    private fun route(distanceM: Double, durationS: Double) =
        Route(shape = emptyList(), maneuvers = emptyList(), distanceM = distanceM, durationS = durationS)

    private fun place(name: String, lat: Double = 41.0, lon: Double = 29.0) =
        Place(name, "", GeoPoint(lat, lon))

    private fun savedPlaces() = listOf(
        SavedPlace(PlaceKind.HOME, place("Ev")),
        SavedPlace(PlaceKind.WORK, place("İş")),
    )

    private fun lineCount(node: SemanticsNodeInteraction): Int {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount ?: 0
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun overflowReport(layout: TextLayoutResult): String =
        "text='${layout.layoutInput.text}' lines=${layout.lineCount} size=${layout.size} " +
            "constraints=${layout.layoutInput.constraints} overflowW=${layout.didOverflowWidth} " +
            "overflowH=${layout.didOverflowHeight} ellipsized=" +
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) }

    @Test fun maneuverBannerScrollsTheFullInstructionAtFont2Short() {
        val instruction = "Atatürk Caddesi üzerinden sağa dönün, köprüyü geçtikten sonra sahildeki kavşaktan sola dönün ve otoparka girin"
        val maneuver = Maneuver(type = 10, instruction = instruction, alert = "", verbal = "", lengthM = 120.0, beginIndex = 0)
        val route = Route(shape = emptyList(), maneuvers = listOf(maneuver), distanceM = 1_000.0, durationS = 300.0)
        renderAt(356.dp, 180.dp) {
            ManeuverBanner(
                NavUi(route = route, navigating = true, maneuverIndex = 0, distToNextM = 140, remainingM = 1_000, remainingS = 300),
            )
        }
        val banner = compose.onNodeWithTag("map-maneuver-banner").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText(instruction, substring = true)
        assertTrue("banner instruction should lay out beyond two lines", lineCount(text) > 2)
        compose.onNodeWithTag("map-maneuver-banner").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        text.assertIsDisplayed()
        val bounds = text.getUnclippedBoundsInRoot()
        assertTrue("banner instruction left ${bounds.left}", bounds.left >= banner.left - 0.5.dp)
        assertTrue("banner instruction right ${bounds.right}", bounds.right <= banner.right + 0.5.dp)
        assertTrue("banner height ${banner.bottom - banner.top}", banner.bottom - banner.top <= 180.dp + 0.5.dp)
    }

    @Test fun mapPanelWidthUsesTheActualMapParentAndAdaptsItsControls() {
        // 600 dp screen minus the 96 dp dock rail and 16 dp dashboard end padding.
        val at600Screen = 600.dp - 96.dp - 16.dp
        val at500Screen = 500.dp - 96.dp - 16.dp
        assertTrue("600 screen map parent should keep the full panel", mapPanelWidth(at600Screen) == 380.dp)
        assertTrue("500 screen map parent should narrow the panel", mapPanelWidth(at500Screen) == 284.dp)
        assertTrue("narrow map parents stack the speed controls", mapUsesStackedSpeedControls(at600Screen))
        assertTrue("wide map parents keep the speed row", !mapUsesStackedSpeedControls(800.dp))

        // Tiny multi-window parents: the panel must never exceed the parent or go negative.
        listOf(300.dp, 188.dp, 100.dp, 0.dp).forEach { tiny ->
            val width = mapPanelWidth(tiny)
            assertTrue("panel $width out of bounds for parent $tiny", width >= 0.dp && width <= tiny)
        }

        // Stacked cluster: [width-84, width-12]; panel starts at 12 and needs an 8 dp gap.
        listOf(at600Screen, at500Screen).forEach { parent ->
            val panelEnd = 12.dp + mapPanelWidth(parent)
            assertTrue("panel $panelEnd collides with stacked speed controls at $parent", panelEnd <= parent - 92.dp)
        }
        val widePanelEnd = 12.dp + mapPanelWidth(800.dp)
        assertTrue("panel $widePanelEnd collides with the speed row at 800", widePanelEnd <= 800.dp - 178.dp)
    }

    @Test fun routeActionsKeep64AndScrollWhenThePanelIsTooNarrowForAWeightedRow() {
        val route = route(9_000.0, 700.0)
        renderAt(190.dp, 296.dp) {
            RouteCard(
                ui = NavUi(route = route, destination = place("Hedef")),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = false,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
            )
        }
        val card = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val start = compose.onNodeWithText(string(R.string.start))
        val cancel = compose.onNodeWithText(string(R.string.cancel))
        start.performScrollTo().assertIsDisplayed()
        assertTargetAtLeast64("tiny start", start)
        assertInside("tiny start", start, card)
        cancel.performScrollTo().assertIsDisplayed()
        assertTargetAtLeast64("tiny cancel", cancel)
        assertInside("tiny cancel", cancel, card)
    }

    @Test fun externalAppLabelStaysCompleteAtNormalFontInTheWideActionRow() {
        val label = string(R.string.open_external_label)
        renderAt(356.dp, 440.dp, 1f) {
            RouteCard(
                ui = NavUi(route = route(9_000.0, 700.0), destination = place("Hedef")),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = true,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
                externalNavApps = listOf(NavApp("Test Harita", "com.test.maps")),
            )
        }
        val action = compose.onNodeWithTag("map-external-app-action")
        action.assertIsDisplayed().assertHasClickAction()
        assertTargetAtLeast64("external app action", action)
        val text = compose.onNodeWithTag("map-external-app-label", useUnmergedTree = true)
        text.assertTextEquals(label).assertIsDisplayed()
        val layout = textLayout(text)
        val actionBounds = action.getUnclippedBoundsInRoot()
        val textBounds = text.getUnclippedBoundsInRoot()
        assertTrue(
            "external label was truncated: ${overflowReport(layout)} action=$actionBounds text=$textBounds",
            !layout.hasVisualOverflow,
        )
        assertTrue("external label must not be ellipsized: ${overflowReport(layout)}", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        assertInside("external label", text, actionBounds)
        assertTargetAtLeast64("save action", compose.onNodeWithTag("map-save-action"))
    }

    @Test fun externalAppLabelWrapsWithoutEllipsisAtFont2InANarrowPanel() {
        val label = string(R.string.open_external_label)
        renderAt(190.dp, 440.dp, 2f) {
            RouteCard(
                ui = NavUi(route = route(9_000.0, 700.0), destination = place("Hedef")),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = true,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
                externalNavApps = listOf(NavApp("Test Harita", "com.test.maps")),
            )
        }
        val action = compose.onNodeWithTag("map-external-app-action")
        action.assertIsDisplayed().assertHasClickAction()
        assertTargetAtLeast64("narrow external app action", action)
        val text = compose.onNodeWithTag("map-external-app-label", useUnmergedTree = true)
        text.assertTextEquals(label).assertIsDisplayed()
        val layout = textLayout(text)
        assertTrue("large external label should wrap", layout.lineCount >= 2)
        assertTrue("large external label was truncated: ${overflowReport(layout)}", !layout.hasVisualOverflow)
        assertInside("large external label", text, action.getUnclippedBoundsInRoot())
    }

    @Test fun externalAppActionStaysHiddenWhenMotionLockedAndSaveTargetRemains64() {
        renderAt(356.dp, 440.dp, 2f) {
            RouteCard(
                ui = NavUi(route = route(9_000.0, 700.0), destination = place("Hedef")),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = false,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
                externalNavApps = listOf(NavApp("Test Harita", "com.test.maps")),
            )
        }
        compose.onNodeWithTag("map-external-app-action").assertDoesNotExist()
        assertTargetAtLeast64("save action while locked", compose.onNodeWithTag("map-save-action"))
    }

    @Test fun routeCardKeepsActionsPinnedInANarrowMapParent() {
        val route = route(9_000.0, 700.0)
        renderInActualMapParent(500.dp, 320.dp) {
            RouteCard(
                ui = NavUi(route = route, destination = place("Uzun Bir Hedef Adı")),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = false,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
            )
        }
        val card = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val start = compose.onNodeWithText(string(R.string.start))
        val cancel = compose.onNodeWithText(string(R.string.cancel))
        assertTargetAtLeast64("narrow start", start)
        assertTargetAtLeast64("narrow cancel", cancel)
        assertInside("narrow start", start, card)
        assertInside("narrow cancel", cancel, card)
    }

    @Test fun longTurkishAndEnglishErrorsStayCompleteInsideTheNarrowChip() {
        val error = mutableStateOf(
            "Bağlantı kurulamadı; rota yeniden hesaplanamadı çünkü ağ bağlantısı yok ve çevrimdışı harita bu bölgeyi kapsamıyor.",
        )
        renderInActualMapParent(500.dp, 320.dp) {
            MapStatusChips(rerouting = false, degraded = false, error = error.value, navigating = true)
        }
        val panel = compose.onNodeWithTag("test-map-panel").getUnclippedBoundsInRoot()
        var text = error.value
        compose.onNodeWithText(text).assertTextEquals(text)
        compose.onNodeWithTag("map-status-chip").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        compose.onNodeWithText(text).assertIsDisplayed()
        var bounds = compose.onNodeWithTag("map-status-chip").getUnclippedBoundsInRoot()
        assertTrue("TR chip right ${bounds.right}", bounds.right <= panel.right + 0.5.dp)
        assertTrue("TR chip height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top <= 140.dp + 0.5.dp)

        text = "Could not reach the routing service; check your connection and try again after the offline map area has been downloaded."
        compose.runOnIdle { error.value = text }
        compose.waitForIdle()
        compose.onNodeWithText(text).assertTextEquals(text)
        compose.onNodeWithTag("map-status-chip").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        compose.onNodeWithText(text).assertIsDisplayed()
        bounds = compose.onNodeWithTag("map-status-chip").getUnclippedBoundsInRoot()
        assertTrue("EN chip right ${bounds.right}", bounds.right <= panel.right + 0.5.dp)
        assertTrue("EN chip height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top <= 140.dp + 0.5.dp)
    }

    @Test fun mapStatusChipsShowTheGuidanceErrorOnlyWhileNavigating() {
        val navigating = mutableStateOf(true)
        val rerouting = mutableStateOf(false)
        val error = mutableStateOf<String?>("Rota alınamadı")
        compose.setContent {
            MotoTheme {
                MapStatusChips(rerouting.value, degraded = false, error = error.value, navigating = navigating.value)
            }
        }
        compose.onNodeWithText("Rota alınamadı").assertIsDisplayed()

        compose.runOnIdle { navigating.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Rota alınamadı").assertDoesNotExist()

        compose.runOnIdle {
            navigating.value = true
            rerouting.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.rerouting_chip)).assertIsDisplayed()
        compose.onNodeWithText("Rota alınamadı").assertDoesNotExist()
    }

    @Test fun searchPanelKeepsQueryPinnedAndMakesTheLastResultReachableAtFont2Short() {
        val results = (1..40).map { place("Sonuç $it", 41.0 + it * 0.001) }
        renderInActualMapParent(600.dp, 320.dp) {
            SearchPanel(
                ui = NavUi(query = "kahve", results = results),
                locked = false,
                typing = false,
                onFocus = {},
                onSearch = {},
                places = savedPlaces(),
                onSelect = {},
                onSave = {},
                onRemovePlace = {},
                recents = listOf(place("Son yer")),
                onClearRecents = {},
                onSuggestion = {},
                here = GeoPoint(41.0, 29.0),
            )
        }
        val panel = compose.onNodeWithTag("map-search-panel").getUnclippedBoundsInRoot()
        assertTargetAtLeast64("search action", compose.onNodeWithText(string(R.string.search)))
        assertInside("search action", compose.onNodeWithText(string(R.string.search)), panel)

        compose.onNodeWithTag("map-search-results").performScrollToNode(hasText("Sonuç 40"))
        compose.onNodeWithText("Sonuç 40").assertIsDisplayed()
        assertInside("last result", compose.onNodeWithText("Sonuç 40"), panel)
        // The query stays outside the scrolling region, so it is still on screen after the list scrolls.
        compose.onNodeWithText("kahve").assertIsDisplayed()
    }

    @Test fun searchPanelLoadingAndSuggestionStayInsideThePanelAtFont2Short() {
        renderInActualMapParent(600.dp, 320.dp) {
            SearchPanel(
                ui = NavUi(query = "kah", loading = true, suggestion = "kahve"),
                locked = false,
                typing = false,
                onFocus = {},
                onSearch = {},
                places = emptyList(),
                onSelect = {},
                onSave = {},
                onRemovePlace = {},
                recents = emptyList(),
                onClearRecents = {},
                onSuggestion = {},
            )
        }
        val panel = compose.onNodeWithTag("map-search-panel").getUnclippedBoundsInRoot()
        compose.onNodeWithText(string(R.string.route_computing)).assertIsDisplayed()
        val suggestion = compose.onNodeWithText(string(R.string.did_you_mean_fmt, "kahve"))
        assertTargetAtLeast64("suggestion", suggestion)
        assertInside("suggestion", suggestion, panel)
    }

    @Test fun searchPanelErrorAndRetryHintStayInsideThePanelAtFont2Short() {
        renderInActualMapParent(600.dp, 320.dp) {
            SearchPanel(
                ui = NavUi(query = "yok", error = "Ağ hatası"),
                locked = false,
                typing = false,
                onFocus = {},
                onSearch = {},
                places = emptyList(),
                onSelect = {},
                onSave = {},
                onRemovePlace = {},
                recents = emptyList(),
                onClearRecents = {},
                onSuggestion = {},
            )
        }
        val panel = compose.onNodeWithTag("map-search-panel").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Ağ hatası").assertIsDisplayed()
        assertInside("error", compose.onNodeWithText("Ağ hatası"), panel)
        compose.onNodeWithText(string(R.string.hint_long_press)).assertIsDisplayed()
    }

    @Test fun routeCardKeepsStartAndCancelPinnedAtFont2Short() {
        val route = route(12_400.0, 900.0)
        renderInActualMapParent(600.dp, 320.dp) {
            RouteCard(
                ui = NavUi(
                    route = route,
                    destination = place("Uzun Bir Hedef Adı"),
                    choices = listOf(route, route(14_000.0, 1_100.0), route(16_000.0, 1_300.0)),
                    stops = listOf(place("Ara 1"), place("Ara 2")),
                    remainingM = 12_400,
                    remainingS = 900,
                ),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = false,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
            )
        }
        val card = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val start = compose.onNodeWithText(string(R.string.start))
        val cancel = compose.onNodeWithText(string(R.string.cancel))
        assertTargetAtLeast64("start", start)
        assertTargetAtLeast64("cancel", cancel)
        assertInside("start", start, card)
        assertInside("cancel", cancel, card)

        // The middle of the card scrolls; the primary actions above stay pinned.
        compose.onNodeWithText(string(R.string.opt_no_toll)).performScrollTo().assertIsDisplayed()
        start.assertIsDisplayed()
        cancel.assertIsDisplayed()
    }

    @Test fun routeCardUpdatingStateKeepsStartReachableAtFont2Short() {
        val route = route(8_000.0, 600.0)
        renderInActualMapParent(600.dp, 320.dp) {
            RouteCard(
                ui = NavUi(route = route, destination = place("Hedef"), loading = true),
                options = RouteOptions(),
                onOptions = {},
                canOpenExternal = false,
                saved = false,
                onSave = {},
                onStart = {},
                onCancel = {},
                onChoose = {},
                onAddStop = {},
                onRemoveStop = {},
            )
        }
        val card = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        compose.onNodeWithText(string(R.string.route_updating)).assertIsDisplayed()
        val start = compose.onNodeWithText(string(R.string.start))
        assertTargetAtLeast64("start while updating", start)
        assertInside("start while updating", start, card)
    }

    @Test fun arrivedCardContinueAndFinishStayReachableAtFont2Short() {
        var continues = 0
        var finishes = 0
        renderInActualMapParent(600.dp, 320.dp) {
            ArrivedCard(place("Sonraki Durak"), onContinue = { continues++ }, onDone = { finishes++ })
        }
        val card = compose.onNodeWithTag("map-arrived-card").getUnclippedBoundsInRoot()
        val continueTrip = compose.onNodeWithText(string(R.string.continue_trip))
        val finish = compose.onNodeWithText(string(R.string.finish))
        continueTrip.performScrollTo().assertIsDisplayed()
        finish.performScrollTo().assertIsDisplayed()
        assertTargetAtLeast64("continue", continueTrip)
        assertTargetAtLeast64("finish", finish)
        assertInside("continue", continueTrip, card)
        assertInside("finish", finish, card)

        continueTrip.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continues) }
        finish.performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, continues)
            assertEquals(1, finishes)
        }
    }

    @Test fun fuelPanelRowsKeepTheirActionsAt64AndInsideAtFont2Short() {
        val stations = (1..3).map { FuelStation(place("İstasyon $it", 41.0 + it * 0.001), it * 1_200) }
        renderInActualMapParent(600.dp, 320.dp) {
            FuelPanel(
                fuel = FuelUi(visible = true, stations = stations),
                guiding = false,
                onGo = {},
                onAddStop = {},
                onRetry = {},
                onClose = {},
            )
        }
        val panel = compose.onNodeWithTag("map-fuel-panel").getUnclippedBoundsInRoot()
        compose.onNodeWithText(string(R.string.fuel_title)).assertIsDisplayed()
        val firstGo = compose.onAllNodesWithText(string(R.string.fuel_go))[0]
        assertTargetAtLeast64("fuel action", firstGo)
        assertInside("fuel action", firstGo, panel)
        // The station list is the bounded, scrolling part; the header and close target stay pinned.
        compose.onNodeWithTag("map-fuel-list").performScrollToNode(hasText("İstasyon 3"))
        compose.onNodeWithText("İstasyon 3").assertIsDisplayed()
    }

    @Test fun fuelPanelErrorKeepsRetryAt64InsideThePanelAtFont2Short() {
        renderInActualMapParent(600.dp, 320.dp) {
            FuelPanel(
                fuel = FuelUi(visible = true, error = "Benzinlikler alınamadı"),
                guiding = true,
                onGo = {},
                onAddStop = {},
                onRetry = {},
                onClose = {},
            )
        }
        val panel = compose.onNodeWithTag("map-fuel-panel").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Benzinlikler alınamadı").assertIsDisplayed()
        val retry = compose.onNodeWithText(string(R.string.retry))
        assertTargetAtLeast64("retry", retry)
        assertInside("retry", retry, panel)
    }
}
