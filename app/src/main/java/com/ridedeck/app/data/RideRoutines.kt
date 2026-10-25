package com.ridedeck.app.data

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The only effects a personal routine may contain. Calls, messages, SOS and arbitrary commands are unrepresentable. */
sealed class RoutineStep {
    object NavigateHome : RoutineStep()
    data class MediaVolume(val percent: Int) : RoutineStep()
    data class VoiceWarnings(val enabled: Boolean) : RoutineStep()
    data class Theme(val mode: ThemeMode) : RoutineStep()
}

data class RideRoutine(val name: String, val steps: List<RoutineStep>)
data class RoutineSnapshot(val volumePercent: Int, val warningsEnabled: Boolean, val theme: ThemeMode)
data class RoutineExecutionResult(val appliedSteps: Int, val totalSteps: Int, val failedStep: RoutineStep?) {
    val succeeded: Boolean get() = failedStep == null
}

object RoutineExecutor {
    fun execute(
        routine: RideRoutine,
        existingSnapshot: RoutineSnapshot?,
        captureSnapshot: () -> RoutineSnapshot,
        persistSnapshot: (RoutineSnapshot?) -> Unit,
        applyStep: (RoutineStep) -> Boolean,
    ): RoutineExecutionResult {
        val capturedNow = existingSnapshot == null
        if (capturedNow) persistSnapshot(captureSnapshot())
        var applied = 0
        for (step in routine.steps) {
            if (!runCatching { applyStep(step) }.getOrDefault(false)) {
                if (capturedNow && applied == 0) persistSnapshot(null)
                return RoutineExecutionResult(applied, routine.steps.size, step)
            }
            applied++
        }
        return RoutineExecutionResult(applied, routine.steps.size, null)
    }

    fun restore(
        snapshot: RoutineSnapshot,
        setVolume: (Int) -> Unit,
        setWarnings: (Boolean) -> Unit,
        setTheme: (ThemeMode) -> Unit,
    ): Boolean = runCatching {
        setVolume(snapshot.volumePercent)
        setWarnings(snapshot.warningsEnabled)
        setTheme(snapshot.theme)
        true
    }.getOrDefault(false)
}

/** Pure validation and local JSON codec so broken or obsolete entries are ignored safely. */
object RideRoutines {
    const val MAX_ROUTINES = 12
    const val MAX_STEPS = 4
    const val MAX_NAME_LENGTH = 32
    const val EDIT_LOCK_SPEED_KMH = 5

    fun canEditAtSpeed(speedKmh: Int, speedFresh: Boolean): Boolean = speedFresh && speedKmh < EDIT_LOCK_SPEED_KMH

    fun validate(routine: RideRoutine): String? = when {
        routine.name.trim().length !in 2..MAX_NAME_LENGTH -> "invalid_name"
        routine.steps.isEmpty() || routine.steps.size > MAX_STEPS -> "invalid_steps"
        routine.steps.any { it is RoutineStep.MediaVolume && it.percent !in 0..100 } -> "invalid_volume"
        routine.steps.distinct().size != routine.steps.size -> "duplicate_step"
        else -> null
    }

    /** Replaces a routine by its old name or creates a new one; names compare case-insensitively. */
    fun upsert(current: List<RideRoutine>, routine: RideRoutine, replacingName: String? = null): List<RideRoutine>? {
        if (validate(routine) != null) return null
        val normalized = key(routine.name)
        if (current.any { key(it.name) == normalized && key(it.name) != key(replacingName.orEmpty()) }) return null
        val retained = current.filterNot { replacingName != null && key(it.name) == key(replacingName) }
        if (retained.size >= MAX_ROUTINES) return null
        return retained + routine.copy(name = routine.name.trim().replace(Regex("\\s+"), " "))
    }

    fun delete(current: List<RideRoutine>, name: String): List<RideRoutine> = current.filterNot { key(it.name) == key(name) }

    fun find(current: List<RideRoutine>, name: String): RideRoutine? = current.firstOrNull { key(it.name) == key(name) }

    fun toJson(routines: List<RideRoutine>): String = JSONArray().apply {
        routines.take(MAX_ROUTINES).forEach { routine ->
            put(JSONObject().put("name", routine.name).put("steps", JSONArray().apply {
                routine.steps.forEach { step ->
                    put(when (step) {
                        RoutineStep.NavigateHome -> JSONObject().put("type", "home")
                        is RoutineStep.MediaVolume -> JSONObject().put("type", "volume").put("percent", step.percent)
                        is RoutineStep.VoiceWarnings -> JSONObject().put("type", "warnings").put("enabled", step.enabled)
                        is RoutineStep.Theme -> JSONObject().put("type", "theme").put("mode", step.mode.name)
                    })
                }
            }))
        }
    }.toString()

    fun fromJson(json: String?): List<RideRoutine> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val result = mutableListOf<RideRoutine>()
            for (i in 0 until minOf(array.length(), MAX_ROUTINES)) {
                val obj = array.optJSONObject(i) ?: continue
                val stepsJson = obj.optJSONArray("steps") ?: continue
                var malformed = false
                val steps = buildList {
                    for (j in 0 until minOf(stepsJson.length(), MAX_STEPS + 1)) {
                        val step = stepsJson.optJSONObject(j)
                        if (step == null) {
                            malformed = true
                            continue
                        }
                        when (step.optString("type")) {
                            "home" -> add(RoutineStep.NavigateHome)
                            "volume" -> step.optInt("percent", -1).takeIf { it in 0..100 }?.let { add(RoutineStep.MediaVolume(it)) } ?: run { malformed = true }
                            "warnings" -> if (step.has("enabled") && step.opt("enabled") is Boolean) add(RoutineStep.VoiceWarnings(step.optBoolean("enabled"))) else malformed = true
                            "theme" -> ThemeMode.entries.firstOrNull { it.name == step.optString("mode") }?.let { add(RoutineStep.Theme(it)) } ?: run { malformed = true }
                            else -> malformed = true
                        }
                    }
                }
                val routine = RideRoutine(obj.optString("name"), steps)
                if (!malformed && validate(routine) == null && result.none { key(it.name) == key(routine.name) }) result.add(routine)
            }
            result
        }.getOrDefault(emptyList())
    }

    fun snapshotToJson(snapshot: RoutineSnapshot): String = JSONObject()
        .put("volume", snapshot.volumePercent.coerceIn(0, 100))
        .put("warnings", snapshot.warningsEnabled)
        .put("theme", snapshot.theme.name)
        .toString()

    fun snapshotFromJson(json: String?): RoutineSnapshot? = runCatching {
        val obj = JSONObject(json ?: return null)
        val volume = obj.optInt("volume", -1)
        val theme = ThemeMode.entries.firstOrNull { it.name == obj.optString("theme") }
        if (volume !in 0..100 || !obj.has("warnings") || obj.opt("warnings") !is Boolean || theme == null) null
        else RoutineSnapshot(volume, obj.optBoolean("warnings"), theme)
    }.getOrNull()

    fun matchPhrase(routines: List<RideRoutine>, phrase: String): String? {
        val cleaned = phrase.trim().lowercase(Locale.forLanguageTag("tr-TR"))
            .replace(Regex("^(?:lütfen |hadi |şimdi )+"), "")
            .replace(Regex("\\b(?:rutin(?:i|in|ini|ler|leri)?|modu|modunu|moduna|çalıştır|başlat|uygula|aç)\\b"), " ")
            .trim().replace(Regex("\\s+"), " ")
        return routines.firstOrNull { key(cleaned) == key(it.name) }?.name
    }

    fun key(name: String): String = name.trim().lowercase(Locale.forLanguageTag("tr-TR")).replace(Regex("\\s+"), " ")
}
