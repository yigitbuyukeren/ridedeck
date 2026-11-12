package com.ridedeck.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ridedeck.app.R
import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.RideReadiness
import com.ridedeck.app.data.RideReadinessAssessment
import com.ridedeck.app.data.RideReadinessInput
import com.ridedeck.app.data.TtsFacts
import com.ridedeck.app.data.hasFreshFix
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose wiring evidence for the same gate and fact refresh helpers used by the Dashboard. */
class ReadinessWiringUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fakeReadiness(): RideReadiness = RideReadinessAssessment.assess(
        RideReadinessInput(
            fineLocationGranted = false,
            coarseLocationGranted = true,
            gpsEnabled = false,
            hasFreshFix = false,
            internet = ConnectivityStatus.OFFLINE,
            offlineAreas = emptyList(),
            tts = TtsFacts(engineInitialized = false),
            notificationsGranted = false,
            mediaAccess = false,
            sosContactsConfigured = false,
        )
    )

    @Test fun gateOpensClosesOnMotionBlocksDelayedActionAndReopensStationary() {
        var inputLocked by mutableStateOf(false)
        var gate by mutableStateOf(ReadinessUiState())
        var settingsClicks = 0

        compose.setContent {
            MotoTheme {
                if (gate.open) {
                    ReadinessContent(
                        fakeReadiness(),
                        onClose = { gate = gate.dismiss() },
                        onAction = { if (gate.allowsSettingsAction(inputLocked)) settingsClicks++ },
                    )
                }
            }
        }

        compose.runOnIdle { gate = gate.requestOpen(inputLocked = true) }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        compose.runOnIdle {
            inputLocked = false
            gate = gate.requestOpen(inputLocked)
        }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertIsDisplayed()

        compose.runOnIdle {
            inputLocked = true
            gate = gate.afterMotion(inputLocked)
        }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        compose.runOnIdle { gate = gate.requestOpen(inputLocked) }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        compose.runOnIdle {
            inputLocked = false
            gate = gate.requestOpen(inputLocked)
        }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertIsDisplayed()

        // Lock between panel paint and the click: the click must be denied by the same gate.
        compose.runOnIdle { inputLocked = true }
        compose.onNodeWithTag("readiness-action-PRECISE_LOCATION", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, settingsClicks) }

        compose.runOnIdle { gate = gate.afterMotion(true) }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        compose.runOnIdle {
            inputLocked = false
            gate = gate.requestOpen(inputLocked)
        }
        compose.onNodeWithTag("readiness-action-PRECISE_LOCATION", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test fun resumeKeyReReadsFactsAndTickerAgesFreshFix() {
        var refreshKey by mutableStateOf(0)
        var fact = false
        var fakeNow = 1_000L
        val fix = LocationState(
            fixAtMs = 1_000L, elapsedRealtimeMs = 0,
            gpsProviderEnabled = true, locationPermissionGranted = true,
        )

        compose.setContent {
            MotoTheme {
                val currentFact = rememberReadinessFact(refreshKey) { fact }
                val now = rememberReadinessNowMs(clock = { fakeNow }, intervalMs = 20L)
                Text(
                    "fact=$currentFact fresh=${fix.hasFreshFix(now, 0)} now=$now",
                    modifier = Modifier.testTag("readiness-probe"),
                )
            }
        }
        compose.onNodeWithTag("readiness-probe").assertTextContains("fact=false fresh=true now=1000")

        compose.runOnIdle { fakeNow = 7_000L }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("fact=false fresh=false now=7000", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            fact = true
            refreshKey++
        }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("fact=true", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun motionCloseStaysClosedWhenStationaryUntilExplicitReopen() {
        var inputLocked by mutableStateOf(false)
        var gate by mutableStateOf(ReadinessUiState())

        compose.setContent {
            MotoTheme {
                if (gate.open) {
                    ReadinessContent(fakeReadiness(), onClose = { gate = gate.dismiss() }, onAction = {})
                }
            }
        }

        compose.runOnIdle { gate = gate.requestOpen(inputLocked) }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertIsDisplayed()

        compose.runOnIdle {
            inputLocked = true
            gate = gate.afterMotion(inputLocked)
        }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        // Becoming stationary again must not silently reopen an informational panel that motion closed.
        compose.runOnIdle {
            inputLocked = false
            gate = gate.afterMotion(inputLocked)
        }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertDoesNotExist()

        compose.runOnIdle { gate = gate.requestOpen(inputLocked) }
        compose.onNodeWithText(composeContext().getString(R.string.readiness_title)).assertIsDisplayed()
    }

    @Test fun resumeWithoutRefreshKeyChangeReReadsPermissionFact() {
        var fact = false
        val owner = TestLifecycleOwner()

        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    Text(
                        "fact=${rememberReadinessFact(refreshKey = "fixed") { fact }}",
                        modifier = Modifier.testTag("readiness-fact"),
                    )
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("readiness-fact").assertTextContains("fact=false")

        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.runOnIdle { fact = true }
        compose.onNodeWithTag("readiness-fact").assertTextContains("fact=false")

        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithText("fact=true", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun composeContext() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
