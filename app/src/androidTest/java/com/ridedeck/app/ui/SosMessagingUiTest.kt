package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReason
import com.ridedeck.app.data.SosUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** SOS presentation with fake result state; no sender, Telecom, radio or controller is constructed. */
class SosMessagingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun testResultExplicitlySaysNoSmsWasSentAndNeverLooksLikeARealSend() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 320.dp)) {
                    SosScreen(SosUi(SosPhase.SENT, SosReason.TEST), emptyList(), {}, {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_test_done)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_test_note)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_sent)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sos_delivery_note), substring = true).assertDoesNotExist()
    }

    @Test fun testCountdownDoesNotShowRecipientsOrPromiseSending() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 320.dp)) {
                    SosScreen(
                        SosUi(SosPhase.COUNTDOWN, SosReason.TEST, secondsLeft = 8),
                        listOf("Contact"), {}, {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_count_test)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_to_fmt, "Contact"), substring = true).assertDoesNotExist()
    }

    @Test fun realSendResultClaimsOnlyRequestConfirmationAndKeepsTheDeliveryCaveat() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 320.dp)) {
                    SosScreen(
                        SosUi(SosPhase.SENT, SosReason.MANUAL, sentTo = listOf("Contact")),
                        emptyList(), {}, {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_sent)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_sent_to_fmt, "Contact"), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_delivery_note), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_test_done)).assertDoesNotExist()
    }

    @Test fun longRealResultKeepsFullMessageAndDismissReachableAtFont2Narrow() {
        var dismissed = false
        compose.setContent {
            MotoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        SosScreen(
                            SosUi(SosPhase.SENT, SosReason.MANUAL, sentTo = listOf("A", "B", "C")),
                            emptyList(), {}, { dismissed = true },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_sent_to_fmt, "A, B, C"), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_delivery_note), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.ok)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun sosButtonExposesItsHoldInstructionToAccessibility() {
        compose.setContent { MotoTheme { SosButton(onTrigger = {}) } }
        compose.onNodeWithContentDescription(context.getString(R.string.sos_button_a11y), useUnmergedTree = true).assertExists()
    }
}
