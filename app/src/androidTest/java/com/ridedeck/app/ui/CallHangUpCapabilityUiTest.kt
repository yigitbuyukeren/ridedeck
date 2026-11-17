package com.ridedeck.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.CallCapability
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import com.ridedeck.app.data.NavUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The hang-up control must not pretend to work on API 26/27 without the default-phone role. */
class CallHangUpCapabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(support: CallCapability.HangUpSupport, onHangUp: () -> Unit) {
        compose.setContent {
            MotoTheme {
                CallScreen(
                    call = CallState(CallPhase.ACTIVE, managed = false),
                    callerName = "Test",
                    speedShown = null,
                    nav = NavUi(),
                    onAnswer = {},
                    onHangUp = onHangUp,
                    hangUpSupport = support,
                )
            }
        }
    }

    @Test fun unsupportedHangUpIsDisabledAndExplainedWithoutClaimingSuccess() {
        var calls = 0
        render(CallCapability.HangUpSupport(false, CallCapability.HangUpMode.NEEDS_DEFAULT_PHONE_APP)) { calls++ }
        val button = compose.onNodeWithText(context.getString(R.string.call_hangup))
        button.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.call_hangup_needs_dialer)).assertIsDisplayed()
        button.performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun managedCallKeepsHangUpEnabled() {
        var calls = 0
        render(CallCapability.HangUpSupport(true, CallCapability.HangUpMode.MANAGED_CALL)) { calls++ }
        val button = compose.onNodeWithText(context.getString(R.string.call_hangup))
        button.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
