package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.CachedRouteMapCoverage
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FEAT-004 MapScreen cached-route UI, isolated against fake [NavUi]/callbacks. No MapLibre,
 * network, DB or real route service is touched. Requires the routeworker NavUi flags/method API.
 */
class CachedRouteUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int) = context.getString(id)

    private fun renderAt(width: Dp, height: Dp, content: @Composable () -> Unit) {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
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
        assertTrue("$description width ${bounds.right - bounds.left}", bounds.right - bounds.left >= 64.dp)
        assertTrue("$description height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
    }

    private fun assertInside(description: String, node: SemanticsNodeInteraction, container: DpRect) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description right", bounds.right <= container.right + 0.5.dp)
        assertTrue("$description bottom", bounds.bottom <= container.bottom + 0.5.dp)
    }

    /** Scrolled-to text must fit the card hard bounds and keep real (non-zero) layout width. */
    private fun scrollToAndAssertVisible(description: String, node: SemanticsNodeInteraction, card: DpRect) {
        node.performScrollTo()
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description empty", bounds.right - bounds.left > 0.dp && bounds.bottom - bounds.top > 0.dp)
        assertTrue("$description left", bounds.left >= card.left - 0.5.dp)
        assertTrue("$description right", bounds.right <= card.right + 0.5.dp)
        assertTrue("$description top", bounds.top >= card.top - 0.5.dp)
        assertTrue("$description bottom", bounds.bottom <= card.bottom + 0.5.dp)
    }

    /** Long metadata/stop text must wrap rather than be silently truncated by maxLines. */
    private fun assertNotEllipsized(description: String, node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        assertTrue("$description has no lines", layout.lineCount > 0)
        for (line in 0 until layout.lineCount) {
            assertTrue("$description ellipsized at line $line", !layout.isLineEllipsized(line))
        }
    }

    /** Start/Cancel live outside the scroll region and stay on-screen while content scrolls. */
    private fun assertFooterPinned(description: String, node: SemanticsNodeInteraction, card: DpRect) {
        node.assertIsDisplayed()
        assertInside(description, node, card)
    }

    private val start = Place("Başlangıç", "", GeoPoint(41.0, 29.0))
    private val dest = Place("Hedef", "", GeoPoint(41.02, 29.02))
    private val route = Route(
        shape = listOf(start.point, dest.point),
        maneuvers = listOf(Maneuver(1, "Düz git", "", "Düz git", 2000.0, 0)),
        distanceM = 2_000.0,
        durationS = 300.0,
    )

    private fun cachedUi(
        blocked: Boolean = false,
        loading: Boolean = false,
        failed: Boolean = false,
        needsNetwork: Boolean = false,
        choices: List<Route> = emptyList(),
        stops: List<Place> = emptyList(),
    ) = NavUi(
        destination = dest,
        route = route,
        choices = choices,
        stops = stops,
        remainingM = 2_000,
        remainingS = 300,
        cachedRoute = true,
        cachedPreview = true,
        cachedPreviewStartBlocked = blocked,
        cachedPreviewRefreshFailed = failed,
        cachedRouteNeedsNetwork = needsNetwork,
        cachedPreviewMapCoverage = CachedRouteMapCoverage.UNKNOWN,
        loading = loading,
    )

    private @Composable
    fun card(
        ui: NavUi,
        onRefresh: () -> Unit,
        onStart: () -> Unit,
    ) {
        RouteCard(
            ui = ui,
            options = RouteOptions(),
            onOptions = {},
            canOpenExternal = true,
            saved = false,
            onSave = {},
            onStart = onStart,
            onCancel = {},
            onChoose = {},
            onAddStop = {},
            onRemoveStop = {},
            onRefreshCached = onRefresh,
        )
    }

    @Test fun cachedPreviewAt600x320ShowsNoticeRefreshAndDisabledStart() {
        var refreshes = 0
        var starts = 0
        renderAt(600.dp, 320.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                card(cachedUi(blocked = true, choices = listOf(route, route.copy(distanceM = 2_100.0))), { refreshes++ }, { starts++ })
            }
        }
        val cardBounds = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithText(string(R.string.cached_route_notice))
        scrollToAndAssertVisible("cached notice", notice, cardBounds)
        assertNotEllipsized("cached notice", notice)
        val coverage = compose.onNodeWithText(string(R.string.cached_route_coverage_unknown))
        scrollToAndAssertVisible("cached coverage", coverage, cardBounds)
        assertNotEllipsized("cached coverage", coverage)
        val needsRefresh = compose.onNodeWithText(string(R.string.cached_route_needs_refresh))
        scrollToAndAssertVisible("cached needs refresh", needsRefresh, cardBounds)
        assertNotEllipsized("cached needs refresh", needsRefresh)

        val refresh = compose.onNodeWithTag("cached-route-refresh")
        refresh.performScrollTo()
        assertTargetAtLeast64("cached refresh", refresh)
        assertInside("cached refresh", refresh, cardBounds)
        refresh.performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }

        val disabled = compose.onNodeWithTag("cached-start-disabled")
        assertTargetAtLeast64("disabled cached start", disabled)
        disabled.assertIsNotEnabled()
        disabled.assertHasNoClickAction()
        // Footer stays pinned and inside the card after the metadata region scrolled.
        assertFooterPinned("pinned disabled cached start", disabled, cardBounds)
        val cancel = compose.onNodeWithText(string(R.string.cancel))
        assertFooterPinned("pinned cached cancel", cancel, cardBounds)
        // The disabled surface and its label are one merged node, not a separate clickable Text child.
        val startLabel = compose.onNodeWithText(string(R.string.start))
        startLabel.assertIsDisplayed()
        assertInside("pinned start label", startLabel, cardBounds)
        compose.runOnIdle { assertEquals(0, starts) }

        // Cached previews are read-only for edits and never show the debug simulation shortcut here.
        compose.onNodeWithText(string(R.string.opt_no_toll)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.add_stop)).assertDoesNotExist()
        compose.onNodeWithText("Simüle et (x4)").assertDoesNotExist()
    }

    @Test fun cachedPreviewLoadingHidesRefreshAndKeepsRouteVisible() {
        var refreshes = 0
        renderAt(600.dp, 320.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                card(cachedUi(loading = true), { refreshes++ }, {})
            }
        }
        compose.onNodeWithTag("cached-route-refresh").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.cached_route_notice)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_updating)).assertIsDisplayed()
    }

    @Test fun cachedRefreshFailureKeepsRouteAndShowsRetry() {
        var refreshes = 0
        renderAt(600.dp, 320.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                card(cachedUi(failed = true), { refreshes++ }, {})
            }
        }
        val cardBounds = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val failure = compose.onNodeWithText(string(R.string.cached_route_refresh_failed))
        scrollToAndAssertVisible("cached refresh failure", failure, cardBounds)
        assertNotEllipsized("cached refresh failure", failure)
        // The failed refresh keeps the cached route on screen instead of clearing it.
        compose.onNodeWithTag("map-route-card").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.cached_route_notice)).assertExists()

        val refresh = compose.onNodeWithTag("cached-route-refresh")
        refresh.performScrollTo()
        assertTargetAtLeast64("retry cached refresh", refresh)
        assertInside("retry cached refresh", refresh, cardBounds)
        refresh.performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("map-route-card").assertIsDisplayed()
    }

    @Test fun unblockedCachedPreviewKeepsEnabledStartAt64() {
        var starts = 0
        renderAt(600.dp, 320.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                card(cachedUi(blocked = false), {}, { starts++ })
            }
        }
        val startButton = compose.onNodeWithText(string(R.string.start))
        assertTargetAtLeast64("cached enabled start", startButton)
        startButton.performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithTag("cached-start-disabled").assertDoesNotExist()
    }

    @Test fun cachedPreviewAt190KeepsPrimaryTargetsAt64() {
        renderAt(190.dp, 296.dp) {
            card(cachedUi(blocked = true), {}, {})
        }
        val refresh = compose.onNodeWithTag("cached-route-refresh")
        refresh.performScrollTo()
        assertTargetAtLeast64("narrow cached refresh", refresh)
        val disabled = compose.onNodeWithTag("cached-start-disabled")
        disabled.performScrollTo()
        assertTargetAtLeast64("narrow disabled start", disabled)
        disabled.assertIsNotEnabled()
        disabled.assertHasNoClickAction()
        compose.onNodeWithText("Simüle et (x4)").assertDoesNotExist()
    }

    @Test fun cachedPreviewKeepsStopsReadableButNotEditable() {
        renderAt(600.dp, 320.dp) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                card(cachedUi(stops = listOf(Place("Ara Durak", "", GeoPoint(41.01, 29.01)))), {}, {})
            }
        }
        val cardBounds = compose.onNodeWithTag("map-route-card").getUnclippedBoundsInRoot()
        val stopLabel = compose.onNodeWithText("1. Ara Durak")
        scrollToAndAssertVisible("cached stop", stopLabel, cardBounds)
        assertNotEllipsized("cached stop", stopLabel)
        compose.onNodeWithContentDescription(string(R.string.apps_remove)).assertDoesNotExist()
        // Read-only stop region still leaves the primary Start target pinned in view.
        val start = compose.onNodeWithText(string(R.string.start))
        assertFooterPinned("pinned enabled cached start", start, cardBounds)
    }

    @Test fun missingRouteNeedsNetworkNoticeIsHonestAndHasNoStartOrReadyClaim() {
        var retries = 0
        val onRetry = mutableStateOf<(() -> Unit)?>(null)
        renderAt(356.dp, 296.dp) {
            CachedRouteNeedsNetworkNotice(onRetry.value)
        }
        compose.onNodeWithText(string(R.string.cached_route_needs_refresh)).assertIsDisplayed()
        compose.onNodeWithTag("cached-route-retry").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.start)).assertDoesNotExist()
        compose.onNodeWithText("Hazır").assertDoesNotExist()
        compose.onNodeWithText("Offline ready").assertDoesNotExist()

        compose.runOnIdle { onRetry.value = { retries++ } }
        compose.waitForIdle()
        val retry = compose.onNodeWithTag("cached-route-retry")
        assertTargetAtLeast64("needs-network retry", retry)
        retry.performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(string(R.string.start)).assertDoesNotExist()
    }
}
