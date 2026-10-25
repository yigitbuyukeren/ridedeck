package com.ridedeck.app

import com.ridedeck.app.data.RideRoutine
import com.ridedeck.app.data.RideRoutines
import com.ridedeck.app.data.RoutineSnapshot
import com.ridedeck.app.data.RoutineExecutor
import com.ridedeck.app.data.RoutineStep
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.VoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRoutinesTest {
    @Test fun routineManagementLocksAtDashboardDrivingThreshold() {
        assertEquals(true, RideRoutines.canEditAtSpeed(4, speedFresh = true))
        assertEquals(false, RideRoutines.canEditAtSpeed(RideRoutines.EDIT_LOCK_SPEED_KMH, speedFresh = true))
        assertEquals(false, RideRoutines.canEditAtSpeed(80, speedFresh = true))
        assertEquals(false, RideRoutines.canEditAtSpeed(0, speedFresh = false))
    }

    @Test
    fun routineSetCreatesEditsAndDeletesWithoutNameCollisions() {
        val first = RideRoutine("Eve dönüş modu", listOf(RoutineStep.NavigateHome, RoutineStep.MediaVolume(45)))
        val created = RideRoutines.upsert(emptyList(), first)!!
        assertNull(RideRoutines.upsert(created, RideRoutine("EVE DÖNÜŞ MODU", listOf(RoutineStep.VoiceWarnings(false)))))

        val edited = RideRoutines.upsert(created, RideRoutine("Sessiz sürüş", listOf(RoutineStep.VoiceWarnings(false))), replacingName = first.name)!!
        assertEquals("Sessiz sürüş", RideRoutines.find(edited, "SESSİZ SÜRÜŞ")?.name)
        assertTrue(RideRoutines.delete(edited, "sessiz sürüş").isEmpty())
    }

    @Test
    fun jsonRoundTripKeepsSupportedStepsAndIgnoresMalformedRoutines() {
        val routines = listOf(RideRoutine("Akşam", listOf(
            RoutineStep.NavigateHome,
            RoutineStep.MediaVolume(35),
            RoutineStep.VoiceWarnings(true),
            RoutineStep.Theme(ThemeMode.NIGHT),
        )))
        assertEquals(routines, RideRoutines.fromJson(RideRoutines.toJson(routines)))
        assertTrue(RideRoutines.fromJson("not json").isEmpty())
        assertTrue(RideRoutines.fromJson("""[{"name":"Bozuk","steps":[{"type":"sms","number":"123"}]}]""").isEmpty())
    }

    @Test
    fun invalidNamesStepsVolumesAndDuplicatesAreRejected() {
        assertEquals("invalid_name", RideRoutines.validate(RideRoutine(" ", listOf(RoutineStep.NavigateHome))))
        assertEquals("invalid_steps", RideRoutines.validate(RideRoutine("Test", emptyList())))
        assertEquals("invalid_volume", RideRoutines.validate(RideRoutine("Test", listOf(RoutineStep.MediaVolume(101)))))
        assertEquals("duplicate_step", RideRoutines.validate(RideRoutine("Test", listOf(RoutineStep.NavigateHome, RoutineStep.NavigateHome))))
    }

    @Test
    fun routineVoicePhrasesHandleTurkishInflectionsAndOnlyMatchSavedNames() {
        val routines = listOf(RideRoutine("Sessiz sürüş", listOf(RoutineStep.MediaVolume(20))))
        assertEquals("Sessiz sürüş", RideRoutines.matchPhrase(routines, "sessiz sürüş modunu çalıştır"))
        assertEquals("Sessiz sürüş", RideRoutines.matchPhrase(routines, "hadi sessiz sürüş rutinini uygula"))
        assertNull(RideRoutines.matchPhrase(routines, "sessiz sürüşe git"))
    }

    @Test
    fun priorSettingsSnapshotRoundTripsAndRejectsIncompleteData() {
        val original = RoutineSnapshot(43, true, ThemeMode.NIGHT)
        assertEquals(original, RideRoutines.snapshotFromJson(RideRoutines.snapshotToJson(original)))
        assertNull(RideRoutines.snapshotFromJson("{\"volume\":44}"))
        assertNull(RideRoutines.snapshotFromJson("not json"))
    }

    @Test
    fun normalModePhraseUsesDedicatedRestoreCommand() {
        assertEquals(VoiceCommand.RestoreRoutineState, VoiceParser.parse("normal moda dön"))
    }

    @Test
    fun executorPersistsOriginalStateOnceAndReportsPartialFailure() {
        val initial = RoutineSnapshot(62, true, ThemeMode.DAY)
        val routine = RideRoutine("Sessiz", listOf(RoutineStep.MediaVolume(20), RoutineStep.VoiceWarnings(false), RoutineStep.Theme(ThemeMode.NIGHT)))
        var saved: RoutineSnapshot? = null
        var captures = 0
        val visited = mutableListOf<RoutineStep>()
        val failed = RoutineExecutor.execute(routine, null, { captures++; initial }, { saved = it }) { step ->
            visited += step
            step !is RoutineStep.VoiceWarnings
        }
        assertEquals(1, failed.appliedSteps)
        assertEquals(RoutineStep.VoiceWarnings(false), failed.failedStep)
        assertEquals(initial, saved)
        assertEquals(1, captures)
        assertEquals(routine.steps.take(2), visited)

        val retry = RoutineExecutor.execute(routine, saved, { captures++; error("snapshot must not be recaptured") }, { saved = it }) { true }
        assertTrue(retry.succeeded)
        assertEquals(1, captures)
    }

    @Test
    fun executorDropsSnapshotWhenFirstActionFailsAndRestoresAsOneOperation() {
        val initial = RoutineSnapshot(50, false, ThemeMode.NIGHT)
        var saved: RoutineSnapshot? = initial
        val failed = RoutineExecutor.execute(RideRoutine("Eve", listOf(RoutineStep.NavigateHome)), null, { initial }, { saved = it }) { false }
        assertEquals(0, failed.appliedSteps)
        assertNull(saved)

        val restored = mutableListOf<Any>()
        assertTrue(RoutineExecutor.restore(initial, { restored += it }, { restored += it }, { restored += it }))
        assertEquals(listOf(50, false, ThemeMode.NIGHT), restored)
    }
}
