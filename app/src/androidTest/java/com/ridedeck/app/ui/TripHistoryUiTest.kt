package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.Trip
import com.ridedeck.app.data.TripStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI009: clear history labels and a real delete confirmation, independent of the store. */
class TripHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(fontScale: Float, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            MotoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width).height(height)) { content() }
                }
            }
        }
    }

    private fun trip(
        distanceM: Double = 12_340.0,
        durationMs: Long = 1_800_000,
        movingMs: Long = 1_200_000,
        maxKmh: Int = 88,
    ): Trip {
        val started = 1_700_000_000_000
        return Trip(
            id = 7,
            startedAt = started,
            endedAt = started + durationMs,
            stats = TripStats(distanceM = distanceM, movingMs = movingMs, maxKmh = maxKmh),
        )
    }

    @Test
    fun cancellingDeleteKeepsTheTripAndCallsNothing() {
        val trip = trip()
        var deletes = 0
        render(fontScale = 1f, width = 700.dp, height = 420.dp) {
            TripDetail(trip, onDelete = { deletes++ }, modifier = Modifier.fillMaxSize())
        }
        val confirmTitle = context.getString(R.string.trip_delete_confirm_title)
        val displayedDate = compose.onNodeWithTag("trip-detail-date", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val confirmBody = context.getString(
            R.string.trip_delete_confirm_body,
            displayedDate,
        )

        compose.onNodeWithText(context.getString(R.string.trip_delete)).performClick()
        compose.onNodeWithText(confirmTitle).assertIsDisplayed()
        compose.onNodeWithText(confirmBody).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_delete_cancel)).performClick()

        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText(context.getString(R.string.stat_distance)).assertExists()
    }

    @Test
    fun confirmingDeleteCallsTheCallbackExactlyOnceAndClosesTheDialog() {
        val trip = trip()
        var deletes = 0
        render(fontScale = 1f, width = 700.dp, height = 420.dp) {
            TripDetail(trip, onDelete = { deletes++ }, modifier = Modifier.fillMaxSize())
        }
        val confirmTitle = context.getString(R.string.trip_delete_confirm_title)

        compose.onNodeWithText(context.getString(R.string.trip_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.trip_delete_confirm_action)).performClick()

        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText(confirmTitle).assertDoesNotExist()
    }

    @Test
    fun deleteFeedbackNamesTheActionAndItsDateForBothOutcomes() {
        val confirmedDate = "12 Kasım 2026 14:30"
        render(fontScale = 1f, width = 700.dp, height = 240.dp) {
            Column(Modifier.fillMaxSize()) {
                TripDeleteFeedbackText(TripDeleteFeedback(succeeded = true, tripDate = confirmedDate))
                TripDeleteFeedbackText(TripDeleteFeedback(succeeded = false, tripDate = confirmedDate))
            }
        }

        compose.onNodeWithText(context.getString(R.string.trip_delete_success_fmt, confirmedDate))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_delete_failed_fmt, confirmedDate))
            .assertIsDisplayed()
    }

    @Test
    fun deleteFeedbackStaysReadableAtLargeFontAndNarrowWidth() {
        val confirmedDate = "12 Kasım 2026 14:30"
        render(fontScale = 2f, width = 300.dp, height = 400.dp) {
            Column(Modifier.fillMaxSize()) {
                TripDeleteFeedbackText(TripDeleteFeedback(succeeded = true, tripDate = confirmedDate))
                TripDeleteFeedbackText(TripDeleteFeedback(succeeded = false, tripDate = confirmedDate))
            }
        }

        compose.onNodeWithText(context.getString(R.string.trip_delete_success_fmt, confirmedDate))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_delete_failed_fmt, confirmedDate))
            .assertIsDisplayed()
    }

    @Test
    fun largeFontLongDurationKeepsTotalsMovingAndConfirmationReachable() {
        val longTrip = trip(
            distanceM = 987_654.0,
            durationMs = 90L * 24 * 60 * 60 * 1000,
            movingMs = 70L * 24 * 60 * 60 * 1000,
            maxKmh = 123,
        )
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaledContext = context.createConfigurationContext(configuration)
        val total = formatDuration(context, (longTrip.durationMs / 1000).toInt())
        val moving = formatDuration(context, (longTrip.stats.movingMs / 1000).toInt())
        var deletes = 0

        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(scaledContext)) {
                    Box(Modifier.width(300.dp).height(240.dp)) {
                        TripDetail(longTrip, onDelete = { deletes++ }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.stat_total_duration)).assertExists()
        compose.onNodeWithText(context.getString(R.string.stat_moving)).assertExists()
        compose.onNodeWithText(total).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(moving).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_moving_disclaimer)).assertExists()

        // The danger action and the confirmation remain reachable with the large font.
        compose.onNodeWithText(context.getString(R.string.trip_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.trip_delete_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_delete_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    @Test
    fun emptyMapPanelAtFont2NarrowKeepsHeaderAndCloseTarget() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaledContext = context.createConfigurationContext(configuration)
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(scaledContext)) {
                    Box(Modifier.width(300.dp).height(240.dp)) {
                        TripMapPanel(
                            tripId = 1,
                            loadPoints = { emptyList() },
                            isDay = false,
                            onClose = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.trip_map_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_map_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_map_privacy)).assertExists()
        val close = compose.onNodeWithContentDescription(context.getString(R.string.trip_map_close))
        close.assertIsDisplayed()
        val closeBounds = close.getUnclippedBoundsInRoot()
        assertTrue("close target must stay at least 64 dp", closeBounds.bottom - closeBounds.top >= 64.dp)
    }

    @Test
    fun mapLoadsOnlyAfterExplicitActionAndEmptyRecordDoesNotCreateAMap() {
        val trip = trip()
        var loads = 0
        render(fontScale = 1f, width = 700.dp, height = 420.dp) {
            TripDetail(
                trip,
                onDelete = {},
                modifier = Modifier.fillMaxSize(),
                onLoadPoints = {
                    loads++
                    emptyList()
                },
            )
        }

        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithText(context.getString(R.string.trip_map_open)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.trip_map_empty)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loads) }

        compose.onNodeWithContentDescription(context.getString(R.string.trip_map_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.trip_map_empty)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun mapLoadErrorStaysTruthfulAndDoesNotShowAMap() {
        val trip = trip()
        render(fontScale = 1f, width = 700.dp, height = 420.dp) {
            TripDetail(
                trip,
                onDelete = {},
                modifier = Modifier.fillMaxSize(),
                onLoadPoints = { throw IllegalStateException("test point read failure") },
            )
        }

        compose.onNodeWithText(context.getString(R.string.trip_map_open)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.trip_map_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_map_retry)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.trip_map_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.trip_map_error)).assertDoesNotExist()
    }

    @Test
    fun totalAndMovingTimeUseDistinctDurations() {
        val trip = trip(durationMs = 1_800_000, movingMs = 1_200_000)
        render(fontScale = 1f, width = 700.dp, height = 420.dp) {
            TripDetail(trip, onDelete = {}, modifier = Modifier.fillMaxSize())
        }
        compose.onNodeWithText(context.getString(R.string.stat_total_duration)).assertExists()
        compose.onNodeWithText(context.getString(R.string.stat_moving)).assertExists()
        compose.onNodeWithText(formatDuration(context, 1_800)).assertExists()
        compose.onNodeWithText(formatDuration(context, 1_200)).assertExists()
    }
}
