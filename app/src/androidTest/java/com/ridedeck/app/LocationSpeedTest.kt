package com.ridedeck.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.LocationRepository
import com.ridedeck.app.data.LocationState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocationSpeedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var location: LocationRepository
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun fix(speed: Float = 15f, hasSpeed: Boolean = true) =
        LocationState(lat = 41.0, lon = 29.0, speedMps = speed, hasSpeed = hasSpeed, speedAccuracyMps = 0.3f)

    @Before fun setup() = main { location = LocationRepository(instrumentation.targetContext) }
    @After fun cleanup() = main { location.stop() }

    @Test fun simulationFeedsTheSameSpeedReadoutAsGps() = main {
        location.simulate(listOf(fix()), 1)
        assertEquals(54.0, location.displayKmh()!!, 0.01)
        assertEquals(54, location.state.value.speedKmh)
    }

    @Test fun stoppingACompletedSimulationClearsItsSpeed() = main {
        location.simulate(listOf(fix()), 1)
        location.stopSimulation()
        assertNull(location.displayKmh())
        assertFalse(location.state.value.hasSpeed)
    }

    @Test fun aPositionWithoutSpeedDoesNotBecomeAReportedStop() = main {
        location.simulate(listOf(fix(hasSpeed = false)), 1)
        assertNull(location.displayKmh())
        assertFalse(location.state.value.hasSpeed)
    }

    @Test fun stoppingTheRepositoryCancelsPendingSimulatedFixes() {
        main {
            location.simulate(listOf(fix(), fix(20f)), 20)
            location.stop()
        }
        SystemClock.sleep(150)
        main {
            assertNull(location.displayKmh())
            assertEquals(0L, location.state.value.fixAtMs)
        }
    }
}
