package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.TripSummary
import com.ridedeck.app.util.Units
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI-level check for the optional weekly/monthly totals block, with no store or GPS points. */
class TripSummaryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertNotNull("missing text layout action", action)
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("layout result was not supplied", action!!.invoke(results))
        return results.first()
    }

    @Test
    fun font2NarrowSummaryBlockKeepsBothPeriodsAndAtLeast64dp() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaled = context.createConfigurationContext(configuration)
        val week = TripSummary(
            periodStart = LocalDate.of(2026, 11, 8),
            periodEndInclusive = LocalDate.of(2026, 11, 14),
            tripCount = 2,
            distanceM = 15_000.0,
            totalMs = 7_200_000,
            movingMs = 3_600_000,
        )
        val month = TripSummary(
            periodStart = LocalDate.of(2026, 11, 1),
            periodEndInclusive = LocalDate.of(2026, 11, 30),
            tripCount = 3,
            distanceM = 30_000.0,
            totalMs = 10_800_000,
            movingMs = 5_400_000,
        )
        val unit = context.getString(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)
        val weekLine = context.getString(
            R.string.trip_summary_line_fmt,
            2,
            formatDistance(15_000),
            formatDuration(context, 7_200),
            formatDuration(context, 3_600),
            "${Units.speed(week.averageMovingKmh)} $unit",
        )
        val monthLine = context.getString(
            R.string.trip_summary_line_fmt,
            3,
            formatDistance(30_000),
            formatDuration(context, 10_800),
            formatDuration(context, 5_400),
            "${Units.speed(month.averageMovingKmh)} $unit",
        )

        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                    LazyColumn(Modifier.width(300.dp).height(300.dp)) {
                        item {
                            TripSummaryHeader(week, month, Modifier.testTag("trip-summary"))
                        }
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.trip_summary_week_label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.trip_summary_month_label)).assertExists()
        compose.onNodeWithText(weekLine).assertExists()
        compose.onNodeWithText(monthLine).assertExists()
        val header = compose.onNodeWithTag("trip-summary")
        val headerBounds = header.getUnclippedBoundsInRoot()
        assertTrue("summary block must stay at least 64 dp", headerBounds.bottom - headerBounds.top >= 64.dp)
        assertTrue(
            "summary header must not become a trip selection target",
            !header.fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
        )
        for (line in listOf(weekLine, monthLine)) {
            assertFalse("summary text must not overflow its layout", layoutOf(line).hasVisualOverflow)
            val bounds = compose.onNodeWithText(line).getUnclippedBoundsInRoot()
            assertTrue("summary text must stay inside its card", bounds.left >= headerBounds.left - 1.dp)
            assertTrue("summary text must stay inside its card", bounds.right <= headerBounds.right + 1.dp)
            assertTrue("summary text must stay inside its card", bounds.top >= headerBounds.top - 1.dp)
            assertTrue("summary text must stay inside its card", bounds.bottom <= headerBounds.bottom + 1.dp)
        }
    }

    @Test
    fun emptyAndNegativeCountsShowTruthfulNoneWithoutSelectionAffordance() {
        val empty = TripSummary(
            periodStart = LocalDate.of(2026, 11, 8),
            periodEndInclusive = LocalDate.of(2026, 11, 14),
            tripCount = 0,
            distanceM = 0.0,
            totalMs = 0,
            movingMs = 0,
        )
        val negative = empty.copy(tripCount = -1)
        compose.setContent {
            MotoTheme {
                Box(Modifier.width(300.dp).height(300.dp)) {
                    TripSummaryHeader(empty, negative, Modifier.testTag("trip-summary-empty"))
                }
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.trip_summary_none)).assertCountEquals(2)
        compose.onNodeWithTag("trip-summary-empty").assertExists()
    }

    @Test
    fun omittedAndCappedCountsAreVisibleAtFont2() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaled = context.createConfigurationContext(configuration)
        val week = TripSummary(
            periodStart = LocalDate.of(2026, 11, 8),
            periodEndInclusive = LocalDate.of(2026, 11, 14),
            tripCount = 1,
            distanceM = 1_000.0,
            totalMs = 60_000,
            movingMs = 60_000,
            omittedCount = 2,
        )
        val month = week.copy(tripCount = 1, omittedCount = 0, capped = true)
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                    Box(Modifier.width(300.dp).height(600.dp)) {
                        TripSummaryHeader(
                            week,
                            month,
                            Modifier.testTag("trip-summary-edge"),
                            unassignedDateCount = 1,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.trip_summary_omitted_fmt, 2)).assertExists()
        compose.onNodeWithText(context.getString(R.string.trip_summary_capped)).assertExists()
        compose.onNodeWithText(context.getString(R.string.trip_summary_unassigned_fmt, 1)).assertExists()
        compose.onNodeWithTag("trip-summary-edge").assertExists()
    }
}
