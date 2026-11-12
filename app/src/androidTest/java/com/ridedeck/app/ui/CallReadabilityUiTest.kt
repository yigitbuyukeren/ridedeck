package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import com.ridedeck.app.data.NavUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Call presentation with fake callbacks only; no Telecom registration and no real call. */
class CallReadabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ringingControlsStayVisibleAtLargeFont() {
        showCall(CallState(CallPhase.RINGING), largeFont = true)

        val answer = compose.onNodeWithText(context.getString(R.string.call_answer)).assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val decline = compose.onNodeWithText(context.getString(R.string.call_decline)).assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("answer sits above decline", answer.top < decline.top)
        assertTrue("answer is inside the screen", answer.top >= root.top && answer.bottom <= root.bottom)
        assertTrue("decline is inside the screen", decline.top >= root.top && decline.bottom <= root.bottom)
    }

    @Test fun activeCallKeepsHangUpAndLargeKeypadTargetsAtLargeFont() {
        var digits by mutableStateOf("")
        var hangs by mutableStateOf(0)
        showCall(
            CallState(CallPhase.ACTIVE, startedAtMs = System.currentTimeMillis(), managed = true),
            largeFont = true,
            onDigit = { digits += it; true },
            onHangUp = { hangs++ },
        )

        compose.onNodeWithText(context.getString(R.string.call_hangup)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_keys)).performClick()

        val key = compose.onNodeWithTag("call-key-5").getUnclippedBoundsInRoot()
        assertTrue("in-call digit target is at least 64 dp tall", key.bottom - key.top >= 64.dp)
        compose.onNodeWithTag("call-hangup-keys").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.call_keys_hide)).assertIsDisplayed()
        compose.onNodeWithTag("call-key-5").performClick()
        // The screen root is a disabled clickable, so it merges plain descendants; the typed
        // digits are a plain Text and are only addressable through the unmerged tree.
        compose.onNodeWithTag("call-typed-digits", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("5")
        compose.onNodeWithTag("call-hangup-keys").performClick()
        compose.runOnIdle {
            assertEquals("5", digits)
            assertEquals(1, hangs)
        }
    }

    @Test fun fullContentLandscapeKeysFitAtLargeFontAndKeepHangUp() {
        compose.setContent {
            // Converter-backed density from the real context; only font scale and the surface config are raised.
            val configuration = Configuration(context.resources.configuration).apply {
                screenWidthDp = 914
                screenHeightDp = 411
                orientation = Configuration.ORIENTATION_LANDSCAPE
                fontScale = 2f
            }
            val androidDensity = Density(context.createConfigurationContext(configuration))
            CompositionLocalProvider(
                LocalDensity provides androidDensity,
                LocalConfiguration provides configuration,
            ) {
                MotoTheme {
                    Box(Modifier.requiredSize(914.dp, 320.dp).testTag("test-surface")) {
                        InCallKeysPane(
                            callerName = "Ayşe Nur Çok Uzun Bir Arayan Adı",
                            typed = "123",
                            onDigit = {},
                            onHide = {},
                            onHangUp = {},
                        )
                    }
                }
            }
        }
        val surface = compose.onNodeWithTag("test-surface").getUnclippedBoundsInRoot()
        "123456789*0#".forEach { digit ->
            val bounds = compose.onNodeWithTag("call-key-$digit").getUnclippedBoundsInRoot()
            assertTrue("call key $digit is at least 64 dp wide", bounds.right - bounds.left >= 64.dp)
            assertTrue("call key $digit is at least 64 dp tall", bounds.bottom - bounds.top >= 64.dp)
            assertTrue(
                "call key $digit is inside the measured surface",
                bounds.left >= surface.left && bounds.right <= surface.right &&
                    bounds.top >= surface.top && bounds.bottom <= surface.bottom,
            )
        }
        val hangUp = compose.onNodeWithTag("call-hangup-keys").getUnclippedBoundsInRoot()
        assertTrue("hang up is inside the measured surface", hangUp.left >= surface.left && hangUp.right <= surface.right && hangUp.bottom <= surface.bottom)
    }

    @Test fun activeCallHangUpStaysInsideTheScreenAtLargeFont() {
        showCall(
            CallState(CallPhase.ACTIVE, startedAtMs = System.currentTimeMillis()),
            largeFont = true,
        )
        val hangUp = compose.onNodeWithText(context.getString(R.string.call_hangup))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("hang up is inside the screen", hangUp.top >= root.top && hangUp.bottom <= root.bottom)
    }

    private fun showCall(
        state: CallState,
        largeFont: Boolean = false,
        onDigit: (Char) -> Boolean = { false },
        onHangUp: () -> Unit = {},
    ) {
        compose.setContent {
            // Converter-backed density from the real context; only the font scale is raised.
            val configuration = Configuration(context.resources.configuration).apply {
                if (largeFont) fontScale = 2f
            }
            val androidDensity = Density(context.createConfigurationContext(configuration))
            CompositionLocalProvider(
                LocalDensity provides androidDensity,
                LocalConfiguration provides configuration,
            ) {
                MotoTheme {
                    Box(Modifier.fillMaxSize()) {
                        CallScreen(
                            state, "Ayşe Nur Çok Uzun Bir Arayan Adı", 92, NavUi(), {}, onHangUp,
                            onDigit = onDigit,
                        )
                    }
                }
            }
        }
    }
}
