package com.ridedeck.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure call presentation with fake callbacks; never constructs a Call or contacts Telecom. */
class CallControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectedDtmfIsNotAddedToTheDisplayedEnteredDigits() {
        var attempts = 0
        compose.setContent {
            MotoTheme {
                CallScreen(CallState(CallPhase.ACTIVE, managed = true), "Contact", null, NavUi(), {}, {},
                    onDigit = { attempts++; false })
            }
        }
        compose.onNodeWithText(context.getString(R.string.call_keys)).performClick()
        compose.onNodeWithText("1").performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
        assertEquals(1, compose.onAllNodesWithText("1").fetchSemanticsNodes().size)
    }

    @Test fun heldCallDoesNotAdvertiseActiveMuteKeypadControls() {
        compose.setContent { MotoTheme { CallScreen(CallState(CallPhase.HELD, managed = true), "Contact", null, NavUi(), {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.call_held)).assertExists()
        compose.onNodeWithText(context.getString(R.string.call_keys)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.call_mute)).assertDoesNotExist()
    }
}
