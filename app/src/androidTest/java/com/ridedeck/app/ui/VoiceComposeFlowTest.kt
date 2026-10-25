package com.ridedeck.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.mutableStateListOf
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.RideRoutine
import com.ridedeck.app.data.RoutineStep
import com.ridedeck.app.data.VoicePhase
import com.ridedeck.app.data.VoiceRuntimeState
import com.ridedeck.app.data.VoicePresentationState
import com.ridedeck.app.data.VoiceUi
import com.ridedeck.app.data.SelectableVoiceInput
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class VoiceComposeFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedLockHidesRoutineMutationControlsButKeepsRunAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MotoTheme {
                RoutineManager(
                    routines = listOf(RideRoutine("Sessiz sürüş", listOf(RoutineStep.MediaVolume(40)))),
                    onSave = { _, _ -> true }, onDelete = {}, onRun = {}, canEdit = false,
                )
            }
        }

        assertText(context.getString(R.string.routine_edit_parked_only), 1)
        assertText(context.getString(R.string.routine_run), 1)
        assertText(context.getString(R.string.routine_add), 0)
        assertText(context.getString(R.string.routine_edit), 0)
        assertText(context.getString(R.string.routine_delete), 0)
    }

    @Test fun routineManagerCreatesEditsRunsAndDeletesThroughItsCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val routines = mutableStateListOf<RideRoutine>()
        val ran = mutableListOf<String>()
        compose.setContent {
            MotoTheme {
                RoutineManager(
                    routines = routines,
                    onSave = { routine, original ->
                        if (original != null) routines.removeAll { it.name == original }
                        routines.removeAll { it.name == routine.name }
                        routines.add(routine)
                        true
                    },
                    onDelete = { name -> routines.removeAll { it.name == name } },
                    onRun = ran::add,
                    canEdit = true,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.routine_add)).performClick()
        compose.onNodeWithText(context.getString(R.string.routine_name)).performTextInput("Eve modu")
        compose.onNodeWithText(context.getString(R.string.save_place_label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Eve modu").assertExists()

        compose.onNodeWithText(context.getString(R.string.routine_run)).performClick()
        compose.runOnIdle { assertEquals(listOf("Eve modu"), ran) }

        compose.onNodeWithText(context.getString(R.string.routine_edit)).performClick()
        compose.onNodeWithText(context.getString(R.string.routine_name)).performTextClearance()
        compose.onNodeWithText(context.getString(R.string.routine_name)).performTextInput("Sessiz sürüş")
        compose.onNodeWithText(context.getString(R.string.save_place_label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sessiz sürüş").assertExists()
        compose.onNodeWithText("Eve modu").assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.routine_delete)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.routine_delete)).get(1).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sessiz sürüş").assertDoesNotExist()
    }

    @Test fun voiceOverlayDisplaysRecognizedCommandWhileProcessing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MotoTheme {
                VoiceOverlay(
                    presentation = VoicePresentationState(
                        assistant = VoiceUi(phase = VoicePhase.PROCESSING, recognizedText = "Sesi artır"),
                        runtime = VoiceRuntimeState.PROCESSING,
                    ),
                    onCancel = {}, onChoose = {}, onHelp = {},
                )
            }
        }

        assertText(context.getString(R.string.voice_processing), 1)
        assertText(context.getString(R.string.voice_heard_fmt, "Sesi artır"), 1)
    }

    @Test fun ttsMessageProvidesAStopControl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var stopped = false
        compose.setContent {
            MotoTheme {
                VoiceOverlay(
                    presentation = VoicePresentationState(
                        assistant = VoiceUi(phase = VoicePhase.MESSAGE, text = "Ses açıldı."),
                        runtime = VoiceRuntimeState.SPEAKING,
                    ),
                    onCancel = { stopped = true }, onChoose = {}, onHelp = {},
                )
            }
        }

        assertText(context.getString(R.string.voice_stop_speaking), 1)
        compose.onNodeWithText(context.getString(R.string.voice_stop_speaking)).performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(stopped) }
    }

    @Test fun speechEngineChoiceReportsMissingVoskModelAndKeepsSystemSelectionLive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected = ""
        var unavailable = 0
        compose.setContent {
            MotoTheme {
                VoiceEngineSelector(
                    engine = SelectableVoiceInput.ENGINE_SYSTEM,
                    voskReady = false,
                    onSelect = { selected = it },
                    onVoskUnavailable = { unavailable++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.voice_engine_vosk_title)).performClick()
        compose.runOnIdle { assertEquals(1, unavailable); assertEquals("", selected) }
        compose.onNodeWithText(context.getString(R.string.voice_engine_system_title)).performClick()
        compose.runOnIdle { assertEquals(SelectableVoiceInput.ENGINE_SYSTEM, selected) }
    }

    @Test fun modelDownloadProgressIsStatusTextRatherThanADeadButton() {
        compose.setContent {
            MotoTheme {
                SettingsScreen(
                    items = listOf(
                        SettingItem(
                            title = "Vosk model", description = "Downloading", granted = false, action = "İndiriliyor",
                            required = true, progress = 0.4f, actionEnabled = false, onClick = {},
                        ),
                    ),
                    toggles = emptyList(), onOpenTrips = {}, themeMode = com.ridedeck.app.data.ThemeMode.AUTO,
                    onThemeMode = {}, onClose = {},
                )
            }
        }

        compose.onNodeWithTag("settings-list", useUnmergedTree = true).performTouchInput { swipeUp() }
        compose.onNodeWithText("İndiriliyor", useUnmergedTree = true).assertHasNoClickAction()
    }

    private fun assertText(text: String, count: Int) {
        assertEquals(count, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }
}
