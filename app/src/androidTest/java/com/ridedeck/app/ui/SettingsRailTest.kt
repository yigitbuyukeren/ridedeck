package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The settings category rail: six tabs, one selected state, and a selected tab that stays wholly
 * visible at narrow widths and large fonts. No activity, services or device state are involved.
 */
class SettingsRailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun railKeepsSixCategoriesAndMarksOnlyTheSelectedOne() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.width(280.dp)) {
                    SettingsCategoryRail(SettingsCategory.GENERAL, {})
                }
            }
        }
        compose.waitForIdle()

        assertEquals("UI-010 keeps the six existing categories", 6, SettingsCategory.entries.size)
        compose.onNodeWithTag("settings-tab-general").assertIsSelected()
        compose.onAllNodes(isSelected(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun narrowRailScrollsTheLastTabFullyIntoViewAndThenHidesTheForwardHint() {
        var selected by mutableStateOf(SettingsCategory.GENERAL)
        compose.setContent {
            MotoTheme {
                Box(Modifier.width(280.dp)) {
                    SettingsCategoryRail(selected, { selected = it })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-tabs-forward-hint").assertIsDisplayed()

        compose.runOnIdle { selected = SettingsCategory.SYSTEM }
        compose.waitForIdle()

        val rail = compose.onNodeWithTag("settings-tabs").getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithTag("settings-tab-system").getUnclippedBoundsInRoot()
        assertTrue("selected tab starts inside the rail", tab.left >= rail.left)
        assertTrue("selected tab ends inside the rail", tab.right <= rail.right)
        compose.onNodeWithTag("settings-tab-system").assertIsSelected()
        compose.onNodeWithTag("settings-tabs-forward-hint").assertDoesNotExist()
    }

    @Test fun largeFontKeepsTheSelectedTabFullyVisible() {
        var selected by mutableStateOf(SettingsCategory.GENERAL)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MotoTheme {
                    Box(Modifier.width(280.dp)) {
                        SettingsCategoryRail(selected, { selected = it })
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { selected = SettingsCategory.SYSTEM }
        compose.waitForIdle()

        val rail = compose.onNodeWithTag("settings-tabs").getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithTag("settings-tab-system").getUnclippedBoundsInRoot()
        assertTrue("large-font tab starts inside the rail", tab.left >= rail.left)
        assertTrue("large-font tab ends inside the rail", tab.right <= rail.right)
        compose.onNodeWithTag("settings-tab-system").assertIsSelected()
    }

    @Test fun clickingTheLastTabAfterScrollingSelectsItAndKeepsItVisible() {
        var selected by mutableStateOf(SettingsCategory.GENERAL)
        compose.setContent {
            MotoTheme {
                Box(Modifier.width(280.dp)) {
                    SettingsCategoryRail(selected, { selected = it })
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("settings-tabs").performScrollToIndex(SettingsCategory.SYSTEM.ordinal)
        compose.onNodeWithTag("settings-tab-system").performClick()
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(SettingsCategory.SYSTEM, selected) }
        compose.onNodeWithTag("settings-tab-system").assertIsSelected()
        val rail = compose.onNodeWithTag("settings-tabs").getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithTag("settings-tab-system").getUnclippedBoundsInRoot()
        assertTrue("clicked tab starts inside the rail", tab.left >= rail.left)
        assertTrue("clicked tab ends inside the rail", tab.right <= rail.right)
    }

    @Test fun changingCategoryResetsContentScrollToTheTop() {
        compose.setContent {
            MotoTheme {
                SettingsScreen(pages = testPages(), onClose = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("settings-list", useUnmergedTree = true).performScrollToIndex(5)
        compose.onNodeWithText("general-row-5", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("settings-tabs", useUnmergedTree = true).performScrollToIndex(SettingsCategory.MAP.ordinal)
        compose.onNodeWithTag("settings-tab-map", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("map-row-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("settings-tab-map", useUnmergedTree = true).assertIsSelected()
    }

    private fun testPages(): List<SettingsPage> = SettingsCategory.entries.map { category ->
        val rows: List<@Composable () -> Unit> = List(20) { index ->
            { Text("${category.name.lowercase()}-row-$index", modifier = Modifier.height(96.dp)) }
        }
        SettingsPage(category, rows)
    }
}
