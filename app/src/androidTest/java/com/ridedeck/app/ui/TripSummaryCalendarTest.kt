package com.ridedeck.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic seam test for the summary clock; no protected broadcast spoofing is used. */
class TripSummaryCalendarTest {
    @get:Rule val compose = createComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun injectedClockResumeAndSignalRefreshRestartTheMidnightWait() {
        val owner = TestOwner()
        val zone = ZoneId.of("UTC")
        var now = Instant.parse("2026-11-12T12:00:00Z")
        val delays = CopyOnWriteArrayList<Long>()
        val signals = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
        val state = AtomicReference<TripSummaryCalendarState?>()

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val calendar = rememberTripSummaryCalendar(
                    locale = Locale.forLanguageTag("en-US"),
                    clock = { now },
                    zone = { zone },
                    externalSignals = signals,
                    waitUntil = { waitMs ->
                        delays += waitMs
                        awaitCancellation()
                    },
                )
                SideEffect { state.set(calendar) }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000L) { delays.isNotEmpty() && state.get() != null }

        // 12:00 UTC to the next local midnight is deterministic and not a poll.
        assertEquals(Duration.ofHours(12).toMillis(), delays.first())
        val firstGeneration = state.get()!!.generation

        // Resume must refresh even when the wall clock has not yet reached midnight.
        now = now.plusSeconds(3_600)
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitUntil(10_000L) { state.get()!!.generation > firstGeneration }
        assertEquals(now, state.get()!!.now)

        // A time-change style signal restarts the wait and re-samples the injected clock.
        val afterResume = state.get()!!.generation
        now = now.plusSeconds(3_600)
        compose.runOnIdle { signals.tryEmit(Unit) }
        compose.waitUntil(10_000L) { state.get()!!.generation > afterResume && delays.size >= 2 }
        assertEquals(now, state.get()!!.now)
        assertTrue(delays.size >= 2)
        assertNotNull(state.get())
    }
}
