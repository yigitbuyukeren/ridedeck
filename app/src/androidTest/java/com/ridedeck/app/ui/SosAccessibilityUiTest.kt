package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.SosAlertPermissionFacts
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReason
import com.ridedeck.app.data.SosUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** TalkBack reaches the SOS button, the countdown and the result; no controller, radio or real contact is built. */
class SosAccessibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertTargetAtLeast64(description: String, node: SemanticsNodeInteraction) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$description width ${bounds.right - bounds.left}", bounds.right - bounds.left >= 64.dp)
        assertTrue("$description height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
    }

    @Test
    fun sosButtonIsAButtonTalkBackCanActivateWithA68DpTarget() {
        var triggered = 0
        compose.setContent { MotoTheme { SosButton(onTrigger = { triggered++ }) } }
        val node = compose.onNodeWithContentDescription(context.getString(R.string.sos_button_a11y), useUnmergedTree = true)
        node.assertExists()
        val config = node.fetchSemanticsNode().config
        assertNotNull("SOS button must expose a click action", config.getOrNull(SemanticsActions.OnClick))
        assertEquals("SOS button must have the Button role", Role.Button, config[SemanticsProperties.Role])
        assertTargetAtLeast64("SOS button", node)
        // A single touch is a hold, not a tap; TalkBack activates the click action directly.
        node.performTouchInput { down(center); up() }
        compose.runOnIdle { assertEquals("a tap must not fire the SOS", 0, triggered) }
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, triggered) }
    }

    @Test
    fun countdownAnnouncesTheSecondsAndKeepsTheCancelTargetAt64() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 320.dp)) {
                    SosScreen(SosUi(SosPhase.COUNTDOWN, SosReason.AUTO, secondsLeft = 12), emptyList(), {}, {})
                }
            }
        }
        val seconds = compose.onNodeWithText("12")
        assertEquals(
            LiveRegionMode.Assertive,
            seconds.fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertEquals(
            LiveRegionMode.Assertive,
            compose.onNodeWithText(context.getString(R.string.sos_count_auto)).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertTargetAtLeast64("countdown cancel", compose.onNodeWithText(context.getString(R.string.sos_fine)))
    }

    @Test
    fun resultTitleIsAHeadingAndCloseStaysAt64() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 320.dp)) {
                    SosScreen(SosUi(SosPhase.SENT, SosReason.MANUAL, sentTo = listOf("Contact")), listOf("Contact"), {}, {})
                }
            }
        }
        val title = compose.onNodeWithText(context.getString(R.string.sos_sent))
        assertTrue("result title must be a heading", title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertTargetAtLeast64("result close", compose.onNodeWithText(context.getString(R.string.ok)))
    }

    /** The Settings card must render (and stay honest about the emulator's missing SIM) without a crash. */
    @Test
    fun settingsCardRendersReadinessWithoutCrashing() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 900.dp)) {
                    SosSettingsCard(
                        contacts = listOf(SosContact("One", "15550000001")),
                        onAdd = {}, onRemove = {}, crashEnabled = true, onCrashEnabled = {},
                        buttonEnabled = true, onButtonEnabled = {}, onTest = {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_test)).assertExists()
    }

    @Test
    fun notificationAndFullScreenWarningsAreShownWithoutDisablingSos() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 1400.dp)) {
                    SosSettingsCard(
                        contacts = listOf(SosContact("One", "15550000001")),
                        onAdd = {}, onRemove = {}, crashEnabled = true, onCrashEnabled = {},
                        buttonEnabled = true, onButtonEnabled = {}, onTest = {},
                        // A denied POST_NOTIFICATIONS and a refused full-screen intent, as on API 34+.
                        alertFacts = SosAlertPermissionFacts(
                            notificationsEnabled = true,
                            postNotificationsGranted = false,
                            fullScreenIntentAllowed = false,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_notif_perm_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_fsi_title)).assertExists()
        // Warnings only: crash detection and the SOS button remain configurable.
        compose.onNodeWithText(context.getString(R.string.sos_crash_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_button_title)).assertExists()
    }

    @Test
    fun notificationsSwitchedOffStillNamesTheRefusedFullScreenIntent() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(480.dp, 1400.dp)) {
                    SosSettingsCard(
                        contacts = listOf(SosContact("One", "15550000001")),
                        onAdd = {}, onRemove = {}, crashEnabled = true, onCrashEnabled = {},
                        buttonEnabled = true, onButtonEnabled = {}, onTest = {},
                        alertFacts = SosAlertPermissionFacts(
                            notificationsEnabled = false,
                            postNotificationsGranted = false,
                            fullScreenIntentAllowed = false,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.sos_notif_off_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_notif_settings)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_fsi_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sos_fsi_action)).assertExists()
    }
}
