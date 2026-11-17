package com.ridedeck.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The ride-stop confirmation: cancel is safe, confirm runs once, and both targets stay 64 dp. */
class TripRideStopConfirmUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            MotoTheme(isDay = true) {
                RideStopConfirmDialog(onConfirm = onConfirm, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun cancellingEndsNothingAndDismissesTheDialog() {
        var confirmed = 0
        var dismissed = 0
        render(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })

        compose.onNodeWithText(context.getString(R.string.data_ride_stop_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.data_ride_stop_confirm_body)).assertIsDisplayed()
        compose.onNodeWithTag("ride-stop-cancel").performClick()

        compose.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun confirmingTwiceStopsTheRideExactlyOnce() {
        var confirmed = 0
        render(onConfirm = { confirmed++ })

        compose.onNodeWithTag("ride-stop-confirm").performClick()
        compose.onNodeWithTag("ride-stop-confirm").performClick()

        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun destructiveAndCancelActionsKeepAtLeast64DpTargets() {
        render()

        compose.onNodeWithTag("ride-stop-confirm").assertHeightIsAtLeast(64.dp).assertIsDisplayed()
        compose.onNodeWithTag("ride-stop-cancel").assertHeightIsAtLeast(64.dp).assertIsDisplayed()
    }
}
