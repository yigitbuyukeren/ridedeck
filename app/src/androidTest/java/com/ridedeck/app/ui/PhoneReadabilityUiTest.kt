package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.CallKind
import com.ridedeck.app.data.Contact
import com.ridedeck.app.data.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phone list, tab and keypad UI only: fake data and callbacks, no Telecom or real calls. */
class PhoneReadabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val longName = "Ayşe Nur Çok Uzun Bir Kişi Adı Soyadı İkinci Satır"
    private val longNumber = "+90 532 123 45 67 89 00 11 22"

    @Test fun tabsExposeSelectedStateAndOnlyOneIsSelected() {
        showPhone()
        compose.onNodeWithTag("phone-tab-recents").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onAllNodes(isSelected()).assertCountEquals(1)

        compose.onNodeWithTag("phone-tab-contacts").performClick()
        compose.onNodeWithTag("phone-tab-contacts").assertIsSelected()
        compose.onNodeWithTag("phone-tab-recents").assertIsNotSelected()
    }

    @Test fun emptyRecentsAndContactsOfferTheKeypadInsteadOfADeadEnd() {
        showPhone()
        val openKeypad = context.getString(R.string.phone_open_keypad)
        compose.onNodeWithText(context.getString(R.string.phone_empty_recents)).assertIsDisplayed()
        compose.onNodeWithText(openKeypad).performClick()
        compose.onNodeWithText(context.getString(R.string.phone_enter_number)).assertIsDisplayed()

        compose.onNodeWithTag("phone-tab-contacts").performClick()
        compose.onNodeWithText(context.getString(R.string.phone_empty_contacts)).assertIsDisplayed()
        compose.onNodeWithText(openKeypad).performClick()
        compose.onNodeWithTag("phone-key-5").assertIsDisplayed()
    }

    @Test fun truthfulPermissionNoticeStillLeavesTheKeypadUsable() {
        var grants by mutableIntStateOf(0)
        showPhone(hasAccess = false, onGrant = { grants++ })

        compose.onNodeWithText(context.getString(R.string.phone_need_perm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.phone_allow)).performClick()
        compose.runOnIdle { assertEquals(1, grants) }

        compose.onNodeWithTag("phone-tab-keypad").performClick()
        compose.onNodeWithTag("phone-key-5").assertIsDisplayed()
    }

    @Test fun dialerHintActionIsJustARequestAndDoesNotDial() {
        var setDialer by mutableIntStateOf(0)
        var dials by mutableIntStateOf(0)
        showPhone(dialerHint = true, onSetDialer = { setDialer++ }, onDial = { _, _ -> dials++ })

        compose.onNodeWithTag("phone-dialer-hint").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_set)).performClick()
        compose.runOnIdle {
            assertEquals(1, setDialer)
            assertEquals(0, dials)
        }
    }

    @Test fun longContactNameAndNumberStayInsideTheirRowAtLargeFontInDayAndNight() {
        var day by mutableStateOf(false)
        compose.setContent {
            // Converter-backed density from the real context; only the font scale is raised.
            val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
            val androidDensity = Density(context.createConfigurationContext(configuration))
            CompositionLocalProvider(
                LocalDensity provides androidDensity,
                LocalConfiguration provides configuration,
            ) {
                MotoTheme(isDay = day) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        PhoneScreen(
                            recents = emptyList(),
                            contacts = listOf(Contact(longName, longNumber, starred = true)),
                            hasAccess = true,
                            keypadLocked = false,
                            initialNumber = null,
                            dialerHint = false,
                            onSetDialer = {},
                            onGrantAccess = {},
                            onDial = { _, _ -> },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("phone-tab-contacts").performClick()
        assertRowContainsBothTexts()

        compose.runOnIdle { day = true }
        compose.waitForIdle()
        assertRowContainsBothTexts()
    }

    @Test fun longRecentNameAndNumberStayInsideTheirRowAtLargeFont() {
        showPhone(
            recents = listOf(RecentCall(longNumber, longName, CallKind.MISSED, System.currentTimeMillis())),
            largeFont = true,
        )
        val row = compose.onAllNodesWithTag("phone-row")[0].getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText(longName, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // In the upright layout the row deliberately joins the number and the time in one line,
        // so match the number as part of the subtitle instead of assuming a separate node.
        val number = compose.onNodeWithText(longNumber, substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("recent name stays inside its row", name.left >= row.left && name.right <= row.right && name.bottom <= row.bottom)
        assertTrue("recent number stays inside its row", number.left >= row.left && number.right <= row.right && number.bottom <= row.bottom)
    }

    @Test fun keypadTargetsAreLargeAndDeleteExposesAndPerformsClearAll() {
        var dials by mutableIntStateOf(0)
        showPhone(initialNumber = "123", largeFont = true, onDial = { _, _ -> dials++ })

        val key = compose.onNodeWithTag("phone-key-5").getUnclippedBoundsInRoot()
        assertTrue("digit key is at least 64 dp tall", key.bottom - key.top >= 64.dp)
        val delete = compose.onNodeWithTag("phone-delete").getUnclippedBoundsInRoot()
        assertTrue(
            "delete target is at least 64 dp",
            delete.right - delete.left >= 64.dp && delete.bottom - delete.top >= 64.dp,
        )
        val clearAllLabel = context.getString(R.string.phone_delete_all)
        compose.onNodeWithTag("phone-delete").assert(
            SemanticsMatcher("long click action is labelled \"$clearAllLabel\"") { node ->
                node.config.getOrNull(SemanticsActions.OnLongClick)?.label == clearAllLabel
            },
        )

        compose.onNodeWithTag("phone-key-5").performClick()
        compose.onNodeWithText("1235").assertIsDisplayed()
        compose.onNodeWithTag("phone-delete").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText(context.getString(R.string.phone_enter_number)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dials) }
    }

    @Test fun shortLandscapeKeypadWithDialerHintKeepsEveryTargetAt64AndInside() {
        showPhone(
            initialNumber = "1", dialerHint = true, largeFont = true,
            landscapeSurface = true,
        )
        val surface = compose.onNodeWithTag("test-surface").getUnclippedBoundsInRoot()
        "123456789*0#".forEach { key ->
            val bounds = compose.onNodeWithTag("phone-key-$key").getUnclippedBoundsInRoot()
            assertTrue("phone key $key is at least 64 dp wide", bounds.right - bounds.left >= 64.dp)
            assertTrue("phone key $key is at least 64 dp tall", bounds.bottom - bounds.top >= 64.dp)
            assertTrue(
                "phone key $key is inside the measured surface",
                bounds.left >= surface.left && bounds.right <= surface.right &&
                    bounds.top >= surface.top && bounds.bottom <= surface.bottom,
            )
        }
        compose.onNodeWithTag("phone-dialer-hint").assertIsDisplayed()
    }

    @Test fun deleteIsReportedDisabledWhenThereIsNothingToRemove() {
        showPhone()
        compose.onNodeWithTag("phone-tab-keypad").performClick()
        compose.onNodeWithTag("phone-delete").assertIsNotEnabled()
    }

    private fun assertRowContainsBothTexts() {
        val row = compose.onAllNodesWithTag("phone-row")[0].getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText(longName, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val number = compose.onNodeWithText(longNumber, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("name stays inside its row", name.left >= row.left && name.right <= row.right && name.bottom <= row.bottom)
        assertTrue("number stays inside its row", number.left >= row.left && number.right <= row.right && number.bottom <= row.bottom)
        assertTrue("title and subtitle line boxes do not overlap", name.bottom <= number.top + 0.5.dp)
        assertTrue("both lines fit the measured row height", number.bottom - name.top <= row.bottom - row.top)
    }

    private fun showPhone(
        recents: List<RecentCall> = emptyList(),
        contacts: List<Contact> = emptyList(),
        hasAccess: Boolean = true,
        initialNumber: String? = null,
        dialerHint: Boolean = false,
        largeFont: Boolean = false,
        landscapeSurface: Boolean = false,
        onGrant: () -> Unit = {},
        onSetDialer: () -> Unit = {},
        onDial: (String, String?) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            // Converter-backed density from the real context; only font scale and landscape config are raised.
            val configuration = Configuration(context.resources.configuration).apply {
                if (largeFont) fontScale = 2f
                if (landscapeSurface) {
                    screenWidthDp = 914
                    screenHeightDp = 411
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
            }
            val androidDensity = Density(context.createConfigurationContext(configuration))
            CompositionLocalProvider(
                LocalDensity provides androidDensity,
                LocalConfiguration provides configuration,
            ) {
                MotoTheme {
                    val surface = if (landscapeSurface) {
                        Modifier.requiredSize(914.dp, 411.dp)
                    } else {
                        Modifier.size(360.dp, 640.dp)
                    }
                    Box(surface.testTag("test-surface")) {
                        PhoneScreen(
                            recents = recents,
                            contacts = contacts,
                            hasAccess = hasAccess,
                            keypadLocked = false,
                            initialNumber = initialNumber,
                            dialerHint = dialerHint,
                            onSetDialer = onSetDialer,
                            onGrantAccess = onGrant,
                            onDial = onDial,
                        )
                    }
                }
            }
        }
    }
}
