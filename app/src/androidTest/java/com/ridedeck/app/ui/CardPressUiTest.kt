package com.ridedeck.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Card press regression; no calls, SMS, media or remote maps. */
class CardPressUiTest {
    @get:Rule val compose = createComposeRule()

    private fun showNavCard(onOpenMap: () -> Unit) {
        compose.setContent {
            MotoTheme {
                NavCard(
                    nav = NavUi(), places = emptyList(), weather = null,
                    me = LocationState(), isDay = false, lowPower = false,
                    onOpenMap = onOpenMap, onGoTo = {},
                    modifier = Modifier.size(420.dp, 260.dp).testTag(NAV_CARD),
                )
            }
        }
    }

    @Test fun oneTapOnTheNavCardOpensTheMapOnce() {
        var opened = 0
        showNavCard { opened++ }
        compose.onNodeWithTag(NAV_CARD).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun holdingTheNavCardStillOpensTheMapOnceOnRelease() {
        var opened = 0
        showNavCard { opened++ }
        compose.onNodeWithTag(NAV_CARD).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun disabledPressDoesNotClick() {
        var clicked = 0
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(120.dp).testTag(DISABLED).pressClickable(enabled = false) { clicked++ })
            }
        }
        compose.onNodeWithTag(DISABLED).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicked) }
    }

    @Test fun sharedTwoLayerPressHasOneClickTargetAndFiresOnce() {
        var clicked = 0
        compose.setContent {
            MotoTheme {
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(200.dp).testTag(SHARED_CARD)
                        .pressClickable(interactionSource = press, onClick = { clicked++ })
                ) {
                    // Mirrors the navigation card's map overlay: pointer input stays, semantics do not.
                    Box(
                        Modifier.fillMaxSize()
                            .clearAndSetSemantics { }
                            .pressClickable(interactionSource = press, onClick = { clicked++ })
                    )
                }
            }
        }

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag(SHARED_CARD).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun appHapticSwitchOffKeepsTheClickButSilencesThePulse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wasEnabled = Prefs.hapticsEnabled(context)
        val pulses = AtomicInteger()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses.incrementAndGet() }
        }
        var clicked = 0
        try {
            Prefs.setHapticsEnabled(context, false)
            compose.setContent {
                MotoTheme {
                    CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                        Box(Modifier.size(120.dp).testTag(HAPTIC).pressClickable { clicked++ })
                    }
                }
            }
            compose.onNodeWithTag(HAPTIC).performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, clicked); assertEquals(0, pulses.get()) }
        } finally {
            Prefs.setHapticsEnabled(context, wasEnabled)
        }
    }

    private companion object {
        const val NAV_CARD = "navCard"
        const val DISABLED = "disabledPress"
        const val HAPTIC = "hapticPress"
        const val SHARED_CARD = "sharedPressCard"
    }
}
