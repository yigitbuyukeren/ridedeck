package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation only: no controller, radio, call or real contact is constructed. */
class SosResultUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun partialResultNamesConfirmedFailedAndUnknownRecipientsAndCanCloseOnCompactScreen() {
        var dismissed = false
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 280.dp)) {
                    SosScreen(SosUi(SosPhase.PARTIAL, sentTo = listOf("Confirmed contact"),
                        failedTo = listOf("Failed contact"), unknownTo = listOf("Unknown contact")),
                        emptyList(), onCancel = {}, onDismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_partial)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_sent)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sos_sent_to_fmt, "Confirmed contact"), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_failed_to_fmt, "Failed contact"), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_unknown_to_fmt, "Unknown contact"), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.ok)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun unknownResultDoesNotClaimDeliveryOrOfferAnAutomaticRetry() {
        compose.setContent { MotoTheme { SosScreen(SosUi(SosPhase.UNKNOWN, unknownTo = listOf("Contact")), emptyList(), {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.sos_unknown)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_delivery_note), substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_sent)).assertDoesNotExist()
    }

    @Test fun pendingSendCannotBeDismissedAsIfNothingWereSent() {
        compose.setContent { MotoTheme { SosScreen(SosUi(SosPhase.SENDING), emptyList(), {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.sos_sending)).assertExists()
        compose.onNodeWithText(context.getString(R.string.ok)).assertDoesNotExist()
    }
}
