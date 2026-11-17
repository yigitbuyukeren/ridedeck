package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineMaps
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.ReconciledRegion
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import com.ridedeck.app.util.Units
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Semantics and destructive-action guards for the dashboard and map chrome. */
class AccessibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertTargetAtLeast64(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$tag width ${bounds.right - bounds.left}", bounds.right - bounds.left >= 64.dp)
        assertTrue("$tag height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
    }

    @Test fun navQuickActionsReadAsButtonsWithTheirDestination() {
        val places = listOf(
            SavedPlace(PlaceKind.HOME, Place("Ev", "", GeoPoint(41.0, 29.0))),
            SavedPlace(PlaceKind.WORK, Place("İş", "", GeoPoint(41.1, 29.1))),
        )
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(380.dp, 170.dp)) {
                    NavCard(
                        nav = NavUi(), places = places, weather = null, me = LocationState(),
                        isDay = false, lowPower = false,
                        onOpenMap = {}, onGoTo = {},
                        modifier = Modifier.size(380.dp, 170.dp),
                    )
                }
            }
        }
        val home = compose.onNodeWithTag("nav-quick-home")
        home.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        home.assertContentDescriptionEquals(
            context.getString(
                R.string.nav_quick_action_desc,
                context.getString(R.string.go_home),
                "Ev",
            )
        )
        compose.onNodeWithTag("nav-quick-work").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        )
    }

    @Test fun endingARideNeedsAnExplicitConfirmation() {
        var confirmed = 0
        var cancelled = 0
        val visible = mutableStateOf(true)
        compose.setContent {
            MotoTheme {
                StopConfirmDialog(
                    visible = visible.value,
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                )
            }
        }
        compose.onNodeWithTag("stop-confirm-dialog").assertExists()
        compose.onNodeWithTag("stop-confirm").assertIsDisplayed()
        assertTargetAtLeast64("stop-confirm")
        assertTargetAtLeast64("stop-cancel")

        compose.onNodeWithTag("stop-cancel").performClick()
        compose.runOnIdle {
            assertEquals("cancel must not stop navigation", 0, confirmed)
            assertEquals(1, cancelled)
        }

        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("stop-confirm-dialog").assertDoesNotExist()

        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("stop-confirm").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun offlineCardShowsAStatusInsteadOfDeadDownloadButtons() {
        val areas = MutableStateFlow<List<OfflineArea>>(emptyList())
        val progress = MutableStateFlow<OfflineMaps.Progress?>(null)
        val regions = MutableStateFlow<List<ReconciledRegion>>(emptyList())
        val reconcileError = MutableStateFlow<String?>(null)
        val actionError = MutableStateFlow<String?>(null)
        compose.setContent {
            MotoTheme {
                OfflineMapsCard(
                    here = GeoPoint(41.0, 29.0),
                    areasFlow = areas,
                    progressFlow = progress,
                    regionsFlow = regions,
                    reconcileErrorFlow = reconcileError,
                    actionErrorFlow = actionError,
                    actions = OfflineMapsCardActions(load = {}, download = { _, _, _, _ -> }),
                )
            }
        }
        compose.onNodeWithTag("offline-download-paused").assertExists()
        compose.onNodeWithText(context.getString(R.string.offline_download, 15)).assertDoesNotExist()
    }

    @Test fun mapStatusChipIsAPoliteLiveRegion() {
        compose.setContent {
            MotoTheme { MapStatusChips(rerouting = true, degraded = false, error = null, navigating = true) }
        }
        compose.onNodeWithTag("map-status-chip").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )
    }

    @Test fun speedReadoutHasOneSpokenLabelWithItsUnit() {
        compose.setContent { MotoTheme { SpeedChip(shown = 48, over = false) } }
        val expected = context.getString(
            R.string.speed_readout_desc,
            Units.speed(48).toString(),
            context.getString(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit),
        )
        compose.onNodeWithContentDescription(expected).assertExists()
    }

    @Test fun mapCreditOffersCopyrightAndFixTheMapWithoutALayoutJump() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                MotoTheme {
                    Box(Modifier.size(320.dp, 120.dp)) {
                        MapCredit(Modifier.testTag("credit"))
                    }
                }
            }
        }
        val credit = compose.onNodeWithContentDescription(context.getString(R.string.map_credit_a11y))
        credit.assertExists()
        val before = compose.onNodeWithTag("credit").getUnclippedBoundsInRoot()
        assertTrue("credit must stay compact", before.bottom - before.top <= 22.dp)

        credit.performClick()
        compose.onNodeWithTag("map-credit-copyright").assertIsDisplayed()
        assertTargetAtLeast64("map-credit-copyright")
        compose.onNodeWithTag("map-credit-copyright").performClick()
        compose.runOnIdle { assertEquals(listOf("https://www.openstreetmap.org/copyright"), opened) }

        credit.performClick()
        compose.onNodeWithTag("map-credit-fixthemap").assertIsDisplayed()
        assertTargetAtLeast64("map-credit-fixthemap")
        compose.onNodeWithTag("map-credit-fixthemap").performClick()
        compose.runOnIdle {
            assertEquals("https://www.openstreetmap.org/fixthemap", opened.last())
            assertEquals(2, opened.size)
        }
    }
}
