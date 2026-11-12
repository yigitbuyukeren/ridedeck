package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.Trip
import com.ridedeck.app.data.TripStats
import com.ridedeck.app.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Compose acceptance for the trip speed value/unit line, without constructing a recorder. */
class TripStatFitUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val displayedUnit
        get() = context.getString(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)

    private fun render(fontScale: Float, width: Dp, height: Dp? = null, content: @Composable () -> Unit) {
        compose.setContent {
            MotoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width).then(if (height == null) Modifier else Modifier.height(height))) {
                        content()
                    }
                }
            }
        }
    }

    private fun renderWithDensity(density: Density, width: Dp, height: Dp? = null, content: @Composable () -> Unit) {
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.width(width).then(if (height == null) Modifier else Modifier.height(height))) {
                        content()
                    }
                }
            }
        }
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertNotNull("text node has no text layout action", action)
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("text could not report its layout", action!!.invoke(results))
        return results.first()
    }

    private fun describe(layout: TextLayoutResult): String {
        val input = layout.layoutInput
        return "text='${input.text.text}' size=${layout.size} " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "constraints=${input.constraints} fontSize=${input.style.fontSize} lineHeight=${input.style.lineHeight}"
    }

    private fun assertUnclippedLine(node: SemanticsNode) {
        val layout = layoutOf(node)
        assertFalse("${describe(layout)} has visual overflow", layout.hasVisualOverflow)
        assertEquals("${describe(layout)} must stay on one line", 1, layout.lineCount)
    }

    private fun assertSingleUnclippedLine(text: String) {
        compose.onNodeWithText(text).assertIsDisplayed()
        assertUnclippedLine(compose.onNodeWithText(text).fetchSemanticsNode())
    }

    private fun assertAllUnclippedLines(text: String) {
        val nodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        assertTrue("$text was not rendered", nodes.isNotEmpty())
        nodes.forEach { assertUnclippedLine(it) }
    }

    @Test
    fun threeDigitValueAndLocalizedUnitFitANarrowCardAtLargeFont() {
        render(fontScale = 2f, width = 140.dp) {
            SpeedStat(R.string.stat_max, 180)
        }
        assertSingleUnclippedLine("180")
        assertSingleUnclippedLine(displayedUnit)
    }

    @Test
    fun threeDigitValueAndTurkishUnitFitAVeryNarrowCard() {
        render(fontScale = 1.5f, width = 104.dp) {
            SpeedValue(180, "km/sa")
        }
        assertSingleUnclippedLine("180")
        assertSingleUnclippedLine("km/sa")
    }

    @Test
    fun threeDigitValueAndMphFitAVeryNarrowCard() {
        render(fontScale = 1.5f, width = 104.dp) {
            SpeedValue(999, "mph")
        }
        assertSingleUnclippedLine("999")
        assertSingleUnclippedLine("mph")
    }

    @Test
    fun twoDigitValueKeepsItsUnitSmallerAndOnTheSameLine() {
        render(fontScale = 1f, width = 170.dp) {
            SpeedValue(34, "km/sa")
        }
        assertSingleUnclippedLine("34")
        assertSingleUnclippedLine("km/sa")
        val valueSp = layoutOf(compose.onNodeWithText("34").fetchSemanticsNode()).layoutInput.style.fontSize.value
        val unitSp = layoutOf(compose.onNodeWithText("km/sa").fetchSemanticsNode()).layoutInput.style.fontSize.value
        assertTrue("unit $unitSp should be smaller than value $valueSp", unitSp < valueSp)
    }

    @Test
    fun actualAndroidNonLinearFontScaleKeepsThreeDigitSpeedOnOneLine() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaledContext = context.createConfigurationContext(configuration)
        // Android Density(Context) carries the platform's non-linear font-scale converter.
        val androidDensity = Density(scaledContext)
        renderWithDensity(androidDensity, 140.dp) {
            SpeedValue(180, "km/sa")
        }
        assertSingleUnclippedLine("180")
        assertSingleUnclippedLine("km/sa")
    }

    @Test
    fun bothBaselineSpeedStatsStayOnOneLineAtLargeFont() {
        render(fontScale = 2f, width = 320.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedStat(R.string.stat_avg, 41, Modifier.weight(1f))
                SpeedStat(R.string.stat_max, 92, Modifier.weight(1f))
            }
        }
        assertSingleUnclippedLine("41")
        assertSingleUnclippedLine("92")
        assertAllUnclippedLines(displayedUnit)
    }

    @Test
    fun fullTripDetailAtLargeFontKeepsAllStatsReachableAndDeleteFixed() {
        val stats = TripStats(distanceM = 28_700.0, movingMs = 2_400_000, maxKmh = 92)
        val trip = Trip(id = 1, startedAt = 0, endedAt = 2_520_000, stats = stats)
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaledContext = context.createConfigurationContext(configuration)
        renderWithDensity(Density(scaledContext), width = 300.dp, height = 240.dp) {
            TripDetail(trip, onDelete = {}, modifier = Modifier.fillMaxSize())
        }
        val distance = formatDistance(stats.distanceM.toInt())
        val duration = formatDuration(context, (trip.durationMs / 1000).toInt())
        val avg = Units.speed(stats.avgKmh).toString()
        val max = Units.speed(stats.maxKmh).toString()
        val delete = context.getString(R.string.trip_delete)

        // The danger action stays visible outside the scrolling stats, with its normal touch height.
        val deleteNode = compose.onNodeWithText(delete)
        deleteNode.assertIsDisplayed()
        val deleteBounds = deleteNode.getUnclippedBoundsInRoot()

        // Every stat card is reachable by scrolling; none may be clipped by the bounded detail pane.
        for ((text, singleLine) in listOf(distance to false, duration to false, avg to true, max to true)) {
            val node = compose.onNodeWithText(text).performScrollTo()
            node.assertIsDisplayed()
            val layout = layoutOf(node.fetchSemanticsNode())
            assertFalse("${describe(layout)} has visual overflow", layout.hasVisualOverflow)
            if (singleLine) assertEquals("${describe(layout)} must stay on one line", 1, layout.lineCount)
        }

        // Both speed units remain one-line and unclipped after scrolling to the speed row.
        val units = compose.onAllNodesWithText(displayedUnit).fetchSemanticsNodes()
        assertTrue("speed units not rendered", units.size >= 2)
        units.forEach { assertUnclippedLine(it) }

        // Scrolling the stats must not move or hide the delete action.
        compose.onNodeWithText(delete).assertIsDisplayed()
        assertEquals(deleteBounds, compose.onNodeWithText(delete).getUnclippedBoundsInRoot())
    }
}
