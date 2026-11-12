package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** First-launch presentation only: fake permission state, no runtime permission change. */
class PermissionIntroUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deniedPreciseLocationOffersSystemSettingsWithoutClaimingEverythingIsSafe() {
        var action = 0
        var closed = 0
        val row = PermissionRow(
            title = context.getString(R.string.perm_location),
            description = context.getString(R.string.perm_location_precise),
            granted = false,
            actionLabel = context.getString(R.string.action_open_settings),
            onAction = { action++ },
        )
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(640.dp, 360.dp)) { PermissionIntro(row, onClose = { closed++ }) }
            }
        }

        val subtitle = context.getString(R.string.perm_intro_sub)
        compose.onNodeWithText(subtitle).assertExists()
        compose.onNodeWithText(context.getString(R.string.perm_location_precise)).assertExists()
        assertFalse(subtitle.contains("her şey güvenli", ignoreCase = true))
        assertFalse(subtitle.contains("all safe", ignoreCase = true))

        compose.onNodeWithText(context.getString(R.string.action_open_settings)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, action)
            assertEquals(0, closed)
        }

        compose.onNodeWithText(context.getString(R.string.perm_intro_later)).performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun constrainedFont2LandscapeKeepsSettingsAndSkipActionsReachable() {
        var action = 0
        var closed = 0
        val row = PermissionRow(
            title = context.getString(R.string.perm_location),
            description = context.getString(R.string.perm_location_precise),
            granted = false,
            actionLabel = context.getString(R.string.action_open_settings),
            onAction = { action++ },
        )
        compose.setContent {
            MotoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.size(600.dp, 320.dp)) { PermissionIntro(row, onClose = { closed++ }) }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.perm_location_precise)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_open_settings)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, action)
            assertEquals(0, closed)
        }
        compose.onNodeWithText(context.getString(R.string.perm_intro_later)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun optionalReadinessEntryStaysReachableAtFont2AndDoesNotCloseIntro() {
        var readinessOpened = 0
        var closed = 0
        val row = PermissionRow(
            title = context.getString(R.string.perm_location),
            description = context.getString(R.string.perm_location_precise),
            granted = false,
            actionLabel = context.getString(R.string.action_open_settings),
            onAction = {},
        )
        compose.setContent {
            MotoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.size(600.dp, 320.dp)) {
                        PermissionIntro(
                            row,
                            onClose = { closed++ },
                            onOpenReadiness = { readinessOpened++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.readiness_title))
            .assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, readinessOpened)
            assertEquals("opening readiness must not dismiss the first-launch page", 0, closed)
        }
        compose.onNodeWithText(context.getString(R.string.perm_intro_later)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun grantedLocationShowsStatusAndHasNoAllowAction() {
        val row = PermissionRow(
            title = context.getString(R.string.perm_location),
            description = context.getString(R.string.perm_location_desc),
            granted = true,
            actionLabel = context.getString(R.string.action_allow),
            onAction = {},
        )
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(640.dp, 360.dp)) { PermissionIntro(row, onClose = {}) }
            }
        }

        compose.onNodeWithText(context.getString(R.string.perm_granted)).assertExists()
        compose.onNodeWithText(context.getString(R.string.perm_intro_done)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_allow)).assertDoesNotExist()
    }
}
