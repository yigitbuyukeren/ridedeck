package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripTrace
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native smoke coverage for the lazy map surface; no real database or provider tiles are used. */
class TripMapUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun trace(): TripTrace = TripTrace.from(
        listOf(
            TripPoint(1_700_000_000_000L, 41.0000, 29.0000, 30),
            TripPoint(1_700_000_001_000L, 41.0001, 29.0001, 32),
            TripPoint(1_700_000_041_000L, 41.0100, 29.0100, 28),
            TripPoint(1_700_000_042_000L, 41.0101, 29.0101, 26),
        )
    )

    private fun waitForMap(snapshot: AtomicReference<TripMapSnapshot?>) {
        compose.waitUntil(10_000L) {
            snapshot.get()?.let { it.hasMap && it.styleReady && it.lineLayer && it.startLayer && it.endLayer } == true
        }
    }

    @Test
    fun localStyleMapRendersLayersCameraAndSurvivesThemeChangeThenDisposes() {
        val trace = trace()
        val snapshot = AtomicReference<TripMapSnapshot?>()
        var show by mutableStateOf(true)
        var isDay by mutableStateOf(false)
        compose.setContent {
            MotoTheme {
                if (show) {
                    Box(Modifier.size(360.dp, 240.dp)) {
                        TripMapCanvas(
                            trace = trace,
                            isDay = isDay,
                            online = false,
                            modifier = Modifier.fillMaxSize().testTag(TAG),
                            onSnapshot = { snapshot.set(it) },
                        )
                    }
                }
            }
        }

        waitForMap(snapshot)
        compose.onNodeWithTag(TAG).assertExists()
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText(context.getString(R.string.trip_map_local_only)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.trip_map_local_only)).assertExists()

        val before = snapshot.get()!!
        assertTrue("local style must be explicit", before.localOnly)
        assertNotNull("camera target must be published", before.cameraTarget)
        assertTrue("camera must zoom in", before.cameraZoom > 0.0)

        // Local style reload on a theme change must keep the native layers and restore the camera.
        compose.runOnIdle { isDay = true }
        compose.waitUntil(10_000L) { snapshot.get()?.let { it.dayStyle && it.styleReady && it.frameRendered && it.cameraFitted } == true }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG).assertExists()
        val after = snapshot.get()!!
        assertTrue(after.lineLayer && after.startLayer && after.endLayer)
        assertNotNull(after.cameraTarget)
        assertEquals(before.cameraTarget!!.latitude, after.cameraTarget!!.latitude, 0.001)
        assertEquals(before.cameraTarget!!.longitude, after.cameraTarget!!.longitude, 0.001)

        // Closing the panel disposes the map; the surface must not be left behind.
        compose.runOnIdle { show = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag(TAG).assertCountEquals(0)
    }

    @Test
    fun duplicatePointsCenterZoomInsteadOfDegenerateBounds() {
        val duplicate = TripTrace.from(
            listOf(
                TripPoint(1_700_000_000_000L, 41.0, 29.0, 30),
                TripPoint(1_700_000_001_000L, 41.0, 29.0, 30),
            )
        )
        val snapshot = AtomicReference<TripMapSnapshot?>()
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(360.dp, 240.dp)) {
                    TripMapCanvas(
                        trace = duplicate,
                        isDay = false,
                        online = false,
                        modifier = Modifier.fillMaxSize().testTag(TAG),
                        onSnapshot = { snapshot.set(it) },
                    )
                }
            }
        }

        waitForMap(snapshot)
        val value = snapshot.get()!!
        assertNotNull(value.cameraTarget)
        assertEquals(41.0, value.cameraTarget!!.latitude, 0.0001)
        assertEquals(29.0, value.cameraTarget!!.longitude, 0.0001)
        assertEquals(15.0, value.cameraZoom, 0.01)
    }

    @Test
    fun localReadyPanelAtFont2NarrowKeepsHeaderCloseAndSavesNightAndDayScreenshots() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaledContext = context.createConfigurationContext(configuration)
        val scaledDensity = Density(scaledContext)
        val trace = trace()
        val snapshot = AtomicReference<TripMapSnapshot?>()
        var isDay by mutableStateOf(false)
        compose.setContent {
            MotoTheme(isDay = isDay) {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    Box(Modifier.size(300.dp, 240.dp).background(LocalMotoPalette.current.card)) {
                        Column(Modifier.fillMaxSize()) {
                            TripMapHeader(onClose = {})
                            TripMapReady(
                                trace = trace,
                                isDay = isDay,
                                online = false,
                                modifier = Modifier.weight(1f),
                                onSnapshot = { snapshot.set(it) },
                            )
                        }
                    }
                }
            }
        }

        waitForMap(snapshot)
        compose.waitUntil(10_000L) { snapshot.get()?.let { it.frameRendered && it.cameraFitted } == true }
        val close = compose.onNodeWithContentDescription(context.getString(R.string.trip_map_close))
        close.assertIsDisplayed()
        val closeBounds = close.getUnclippedBoundsInRoot()
        assertTrue("close target must stay at least 64 dp", closeBounds.bottom - closeBounds.top >= 64.dp)
        compose.onNodeWithText(context.getString(R.string.trip_map_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trip_map_local_only)).performScrollTo().assertIsDisplayed()
        val night = snapshot.get()!!
        assertTrue(night.localOnly)
        assertTrue("map must have positive width", night.mapWidth > 0)
        assertTrue("map must have positive height", night.mapHeight > 0)
        assertTrue("native map must stay at least 64 dp", with(scaledDensity) { night.mapHeight.toDp() } >= 64.dp)
        val notice = compose.onNodeWithTag("trip-map-notice").getUnclippedBoundsInRoot()
        val native = compose.onNodeWithTag("trip-map-native").getUnclippedBoundsInRoot()
        val credit = compose.onNodeWithTag("trip-map-credit").getUnclippedBoundsInRoot()
        assertTrue("notice must stay outside map interior", notice.bottom <= native.top + 1.dp)
        assertTrue("credit must stay outside map interior", credit.top >= native.bottom - 1.dp)
        assertTrue("credit must not overlap notice", notice.bottom <= credit.top + 1.dp)
        assertTrue("native map viewport must stay at least 64 dp", native.bottom - native.top >= 64.dp)
        assertNotNull(night.cameraTarget)
        assertTrue("camera must have a sane latitude", night.cameraTarget!!.latitude in 41.0..41.0101)
        assertTrue("camera must have a sane longitude", night.cameraTarget!!.longitude in 29.0..29.0101)
        assertTrue("camera must zoom in", night.cameraZoom > 0.0)
        saveScreenshot("trip-map-local-night-font2.png")

        compose.runOnIdle { isDay = true }
        compose.waitUntil(10_000L) { snapshot.get()?.let { it.dayStyle && it.styleReady && it.frameRendered && it.cameraFitted } == true }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.trip_map_local_only)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.trip_map_close)).assertIsDisplayed()
        val day = snapshot.get()!!
        assertTrue(day.localOnly)
        assertNotNull(day.cameraTarget)
        assertEquals(night.cameraTarget!!.latitude, day.cameraTarget!!.latitude, 0.001)
        assertEquals(night.cameraTarget!!.longitude, day.cameraTarget!!.longitude, 0.001)
        saveScreenshot("trip-map-local-day-font2.png")
    }

    @Test
    fun singlePointLocalCanvasUsesMarkerCamera() {
        val trace = TripTrace.from(listOf(TripPoint(1_700_000_000_000L, 41.0, 29.0, 0)))
        val snapshot = AtomicReference<TripMapSnapshot?>()
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(300.dp, 240.dp)) {
                    TripMapCanvas(
                        trace = trace,
                        isDay = false,
                        online = false,
                        modifier = Modifier.fillMaxSize().testTag(TAG),
                        onSnapshot = { snapshot.set(it) },
                    )
                }
            }
        }
        compose.waitUntil(10_000L) {
            snapshot.get()?.let {
                it.hasMap && it.styleReady && it.startLayer && it.endLayer &&
                    it.pointCount == 1 && it.lineFeatureCount == 0 &&
                    it.startPointCount == 1 && it.endPointCount == 1
            } == true
        }
        val value = snapshot.get()!!
        assertTrue("line layer remains present even when its source is empty", value.lineLayer)
        assertNotNull(value.cameraTarget)
        assertEquals(41.0, value.cameraTarget!!.latitude, 0.0001)
        assertEquals(29.0, value.cameraTarget!!.longitude, 0.0001)
        assertEquals(15.0, value.cameraZoom, 0.01)
    }

    @Test
    fun oneHourPreparedTraceWithGapRendersNatively() {
        val points = buildList {
            for (i in 0..3_600) {
                // A half-minute silence in the middle is a gap, not a bridged straight line.
                val t = if (i < 1_800) i * 1_000L else i * 1_000L + 40_000L
                add(TripPoint(1_700_000_000_000L + t, 41.0 + i * 0.00001, 29.0 + i * 0.00001, 30))
            }
        }
        val trace = TripTrace.from(points, maxPoints = 2_000)
        val snapshot = AtomicReference<TripMapSnapshot?>()
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(360.dp, 240.dp)) {
                    TripMapCanvas(
                        trace = trace,
                        isDay = false,
                        online = false,
                        modifier = Modifier.fillMaxSize().testTag(TAG),
                        onSnapshot = { snapshot.set(it) },
                    )
                }
            }
        }
        waitForMap(snapshot)
        compose.onNodeWithTag(TAG).assertExists()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = requireNotNull(context.getExternalFilesDir(null)).resolve("wave2").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TAG = "trip-map-canvas"
    }
}
