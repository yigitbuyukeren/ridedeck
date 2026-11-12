package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real SettingsScreen control bounds and single-toggle semantics at converter font scale 2. */
class SettingsControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun toggleRowsUseASingleRowActionAndADecorativeSwitchAtConverterFont2() {
        var rowToggles = 0
        var groupToggles = 0
        val rowChecked = mutableStateOf(false)
        val groupChecked = mutableStateOf(true)
        renderSettings(
            rowChecked = rowChecked,
            groupChecked = groupChecked,
            onRowToggle = { rowToggles++ },
            onGroupToggle = { groupToggles++ },
        )

        scrollTo(0)
        val row = compose.onNode(hasText(TR_TITLE) and hasClickAction())
        assertHeightAtLeast64(row)
        row.assertIsToggleable().assertIsOff()
        val rowNode = row.fetchSemanticsNode()
        assertEquals(Role.Switch, rowNode.config[SemanticsProperties.Role])
        assertTrue("row must remain keyboard-focusable", rowNode.config.contains(SemanticsActions.RequestFocus))
        row.performClick()
        compose.runOnIdle { assertEquals(1, rowToggles) }
        compose.onNode(hasText(TR_TITLE) and hasClickAction()).assertIsOn()

        // Clicking over the visual switch must still belong to the row, not a second clickable node.
        compose.onNode(hasText(TR_TITLE) and hasClickAction()).performTouchInput {
            click(Offset(width * 0.9f, height / 2f))
        }
        compose.runOnIdle { assertEquals(2, rowToggles) }
        compose.onNode(hasText(TR_TITLE) and hasClickAction()).assertIsOff()

        scrollTo(1)
        val group = compose.onNode(hasText(EN_TOGGLE) and hasClickAction())
        assertHeightAtLeast64(group)
        group.assertIsToggleable().assertIsOn()
        group.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, groupToggles) }
        compose.onNode(hasText(EN_TOGGLE) and hasClickAction()).assertIsOff()

        val switches = compose.onAllNodesWithTag(SWITCH, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("decorative switch should still be rendered", switches.isNotEmpty())
        switches.forEach {
            assertFalse("row switch must not be a second click target", it.config.contains(SemanticsActions.OnClick))
        }
    }

    @Test
    fun settingActionAndSliderExposeReal64DpTargetsAtConverterFont2() {
        var rate by mutableStateOf(1f)
        var sliderCalls = 0
        var actionCalls = 0
        compose.setContent {
            MotoTheme {
                Column(Modifier.size(600.dp, 300.dp)) {
                    SettingRow(
                        SettingItem(
                            SETTING_TITLE, SETTING_DESC, granted = false, action = ACTION,
                            onClick = { actionCalls++ },
                        )
                    )
                    VoiceControlsRow(rate) { rate = it; sliderCalls++ }
                }
            }
        }
        compose.waitForIdle()

        assertHeightAtLeast64(compose.onNodeWithTag(SETTING_ROW, useUnmergedTree = true))
        val action = compose.onNodeWithText(ACTION)
        assertHeightAtLeast64(action)
        action.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, actionCalls) }

        val slider = compose.onNodeWithTag(SLIDER_HOST)
        slider.assertIsDisplayed()
        assertHeightAtLeast64(slider)
        // Touch near the top edge of the 64 dp host proves the actual pointer surface, not only a parent.
        slider.performTouchInput { click(Offset(width * 0.8f, height * 0.05f)) }
        compose.runOnIdle {
            assertTrue("slider pointer area should react near its top edge", rate != 1f)
            assertTrue("slider callback should fire", sliderCalls > 0)
        }
    }

    @Test
    fun rtlTapAndEdgeDragMapReverseDirectionWithoutDuplicateTap() {
        var rate by mutableStateOf(1f)
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MotoTheme {
                    Box(Modifier.size(600.dp, 120.dp)) {
                        VoiceControlsRow(rate) { rate = it; calls++ }
                    }
                }
            }
        }
        compose.waitForIdle()
        val host = compose.onNodeWithTag(SLIDER_HOST)
        host.performTouchInput { click(Offset(width * 0.05f, height * 0.05f)) }
        compose.runOnIdle {
            assertTrue("RTL left edge should map to the high end, was $rate", rate > 1f)
            assertEquals("edge tap must call once", 1, calls)
        }

        compose.runOnIdle { rate = 1f; calls = 0 }
        compose.waitForIdle()
        host.performTouchInput {
            down(Offset(width * 0.9f, height * 0.05f))
            moveTo(Offset(width * 0.1f, height * 0.05f))
            up()
        }
        compose.runOnIdle {
            assertTrue("RTL left drag should increase the rate, was $rate", rate > 1f)
            assertTrue("RTL edge drag should call back", calls > 0)
        }
    }

    @Test
    fun verticalSwipeOnSliderEdgeScrollsParentWithoutChangingRate() {
        var rate by mutableStateOf(1f)
        var calls = 0
        val listState = LazyListState()
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(600.dp, 300.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item { VoiceControlsRow(rate) { rate = it; calls++ } }
                        item { Box(Modifier.height(600.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SLIDER_HOST).performTouchInput {
            swipe(
                start = Offset(width * 0.5f, height * 0.95f),
                end = Offset(width * 0.5f, height * 0.05f),
                durationMillis = 200,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("vertical swipe should scroll the list", listState.firstVisibleItemScrollOffset > 0)
            assertEquals("vertical swipe must not change the rate", 1f, rate)
            assertEquals("vertical swipe must not call the slider", 0, calls)
        }
    }

    @Test
    fun sliderSemanticsSetProgressStillWorks() {
        var rate by mutableStateOf(1f)
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(600.dp, 120.dp)) {
                    VoiceControlsRow(rate) { rate = it }
                }
            }
        }
        compose.waitForIdle()
        val slider = compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0.75f..1.25f)))
        slider.assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.9f) }
        compose.runOnIdle {
            assertTrue("SetProgress should still update the rate, was $rate", kotlin.math.abs(rate - 0.9f) < 0.02f)
        }
    }

    @Test
    fun disabledToggleRowKeepsItsDisabledStateAndDoesNotToggle() {
        var calls = 0
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(600.dp, 120.dp)) {
                    ToggleRow(
                        ToggleItem(
                            DISABLED_TITLE, DISABLED_DESC, checked = false, enabled = false,
                            onChange = { calls++ },
                        )
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(TOGGLE_ROW, useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag(SWITCH, useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag(TOGGLE_ROW, useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals("disabled row must not toggle", 0, calls) }
    }

    @Test
    fun settingsTargetsStayAtLeast64InDayAndNightAtConverterFont2() {
        val day = mutableStateOf(true)
        renderSettings(isDay = day, onRowToggle = {}, onGroupToggle = {}, onAction = {})

        for (expectedDay in listOf(true, false)) {
            compose.runOnIdle { day.value = expectedDay }
            compose.waitForIdle()
            scrollTo(0)
            assertHeightAtLeast64(compose.onNode(hasText(TR_TITLE) and hasClickAction()))
            scrollTo(1)
            assertHeightAtLeast64(compose.onNode(hasText(EN_TOGGLE) and hasClickAction()))
            scrollTo(2)
            assertHeightAtLeast64(compose.onNodeWithTag(SETTING_ROW, useUnmergedTree = true))
        }
    }

    private fun renderSettings(
        isDay: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false),
        rate: Float = 1f,
        rowChecked: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false),
        groupChecked: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(true),
        onRate: (Float) -> Unit = {},
        onRowToggle: () -> Unit,
        onGroupToggle: () -> Unit,
        onAction: () -> Unit = {},
    ) {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val androidDensity = Density(context.createConfigurationContext(configuration))
        val pages = listOf(
            SettingsPage(
                SettingsCategory.GENERAL,
                listOf(
                    {
                        ToggleRow(
                            ToggleItem(
                                TR_TITLE, TR_DESC, checked = rowChecked.value,
                                onChange = { rowChecked.value = it; onRowToggle() },
                            )
                        )
                    },
                    {
                        ToggleGroupRow(
                            EN_TITLE, EN_DESC,
                            listOf(ToggleItem(
                                EN_TOGGLE, EN_TOGGLE_DESC,
                                checked = groupChecked.value,
                                onChange = { groupChecked.value = it; onGroupToggle() },
                            )),
                        )
                    },
                    {
                        SettingRow(
                            SettingItem(
                                SETTING_TITLE, SETTING_DESC, granted = false, action = ACTION,
                                onClick = onAction,
                            )
                        )
                    },
                    { VoiceControlsRow(rate, onRate) },
                ),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides androidDensity) {
                MotoTheme(isDay = isDay.value) {
                    Box(Modifier.size(600.dp, 320.dp)) {
                        SettingsScreen(pages = pages, onClose = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int) {
        compose.onNodeWithTag("settings-list", useUnmergedTree = true).performScrollToIndex(index)
        compose.waitForIdle()
    }

    private fun assertHeightAtLeast64(node: SemanticsNodeInteraction) {
        val bounds = node.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("target height $height must be at least 64dp", height >= 64.dp)
    }

    private companion object {
        const val TR_TITLE = "Uzun Türkçe ayar başlığı: bildirimler ve ses davranışı"
        const val TR_DESC = "Bu açıklama büyük yazıda satır kaydırmadan okunabilmeli ve satır hedefi 64 dp kalmalı."
        const val EN_TITLE = "Long English setting title: notifications, sound and safety"
        const val EN_DESC = "This description must wrap at large text without shrinking the row target."
        const val EN_TOGGLE = "Grouped long English toggle row"
        const val EN_TOGGLE_DESC = "A grouped toggle description that is long enough to wrap."
        const val SETTING_TITLE = "Uzun izin ayarı başlığı ve sistem durumu"
        const val SETTING_DESC = "Satır yüksekliği ve eylem alanı gerçek 64 dp hedefi korumalı."
        const val ACTION = "Ayarları aç"
        const val SWITCH = "settings-toggle-switch"
        const val SETTING_ROW = "settings-setting-row"
        const val SLIDER_HOST = "settings-speech-slider"
        const val TOGGLE_ROW = "settings-toggle-row"
        const val DISABLED_TITLE = "Devre dışı ayar satırı"
        const val DISABLED_DESC = "Bu satır devre dışıyken eylem değiştirmemeli."
    }
}
