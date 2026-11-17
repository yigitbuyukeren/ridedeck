package com.ridedeck.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke test: proves the dashboard activity reaches RESUMED without a crash.
 *
 * It intentionally needs no network, GPS, notification or phone permission so it stays
 * fast and deterministic on a fresh emulator.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun mainActivityReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            var state = Lifecycle.State.DESTROYED
            scenario.onActivity { activity -> state = activity.lifecycle.currentState }
            assertEquals(Lifecycle.State.RESUMED, state)
        }
    }
}
