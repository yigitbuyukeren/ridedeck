package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.DetourQuote
import com.ridedeck.app.data.DetourQuoteUi
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.FuelUi
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fake fuel/detour UI only; no map, provider, route or navigation mutation. */
class FuelDetourUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stationIdentityUsesPointAndNormalizedName() {
        val blankA = Place("", "", GeoPoint(41.0, 29.0))
        val blankB = Place(" ", "", GeoPoint(41.0, 29.0))
        val otherPoint = Place("", "", GeoPoint(41.1, 29.0))
        assertEquals(detourStationKey(blankA), detourStationKey(blankB))
        assertNotEquals(detourStationKey(blankA), detourStationKey(otherPoint))
    }

    @Test fun quoteOnlyMatchesTheExplicitSelectedStation() {
        val a = place("A")
        val b = place("B")
        val bQuote = DetourQuoteUi(station = b, quote = DetourQuote.available(1_200.0, 120.0))

        assertNull(detourQuoteForSelection(a, bQuote))
        assertEquals(bQuote, detourQuoteForSelection(b, bQuote))
    }

    @Test fun calculateDetourIsExplicitAndDoesNotAddOrStartTheStation() {
        var calculated = 0
        var added = 0
        var started = 0
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(600.dp, 500.dp)) {
                    FuelPanel(
                        fuel = FuelUi(visible = true, stations = listOf(FuelStation(place("A"), 900))),
                        guiding = true,
                        onGo = { started++ },
                        onAddStop = { added++ },
                        onRetry = {},
                        onClose = {},
                        detourStation = place("A"),
                        onCalculateDetour = { calculated++ },
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.detour_calculate)).performClick()
        compose.runOnIdle {
            assertEquals(1, calculated)
            assertEquals(0, added)
            assertEquals(0, started)
        }
    }

    @Test fun onlyTheSelectedStationRowShowsCalculate() {
        val a = place("A", 41.000)
        val b = place("B", 41.010)
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(600.dp, 500.dp)) {
                    FuelPanel(
                        fuel = FuelUi(
                            visible = true,
                            stations = listOf(FuelStation(a, 900), FuelStation(b, 1_200)),
                        ),
                        guiding = true,
                        onGo = {},
                        onAddStop = {},
                        onRetry = {},
                        onClose = {},
                        detourStation = a,
                        onCalculateDetour = {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.detour_calculate)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.detour_calculate)).assertCountEquals(1)
    }

    @Test fun zeroDetourKeepsZeroDistanceAndTime() {
        val station = place("A")
        compose.setContent {
            MotoTheme { Box(Modifier.size(600.dp, 320.dp)) {
                FuelDetourQuote(
                    station = station,
                    ui = DetourQuoteUi(station = station, quote = DetourQuote.available(0.0, 0.0)),
                    onClose = {},
                )
            } }
        }
        compose.onNodeWithText("0 m", substring = true).assertIsDisplayed()
        compose.onNodeWithText("0 ${context.getString(R.string.unit_minute)}", substring = true).assertIsDisplayed()
    }

    @Test fun narrowFont2RoutedQuoteShowsToleranceAndHas64Cancel() {
        val station = place("A")
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val density = Density(context.createConfigurationContext(configuration))
        var closed = 0
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.size(600.dp, 320.dp)) {
                        FuelDetourQuote(
                            station = station,
                            ui = DetourQuoteUi(
                                station = station,
                                quote = DetourQuote.available(1_200.0, 120.0),
                            ),
                            onClose = { closed++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("≈", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.detour_snapshot_tolerance, 25),
        ).assertIsDisplayed()
        val cancel = compose.onNodeWithText(context.getString(R.string.cancel))
        val bounds = cancel.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 64.dp)
        assertTrue(bounds.bottom - bounds.top >= 64.dp)
        cancel.performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    private fun place(name: String, lat: Double = 41.0) =
        Place(name, "", GeoPoint(lat, 29.0))
}
