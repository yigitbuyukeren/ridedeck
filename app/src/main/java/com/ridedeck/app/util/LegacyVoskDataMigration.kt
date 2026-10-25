package com.ridedeck.app.util

import java.io.File

/** Narrow one-time cleanup for files owned by the retired offline speech engine. */
internal object LegacyVoskDataMigration {
    fun androidEngine(previous: String?): String = if (previous == "vosk" || previous.isNullOrBlank()) "system" else previous

    fun removeArtifacts(filesDir: File, cacheDir: File): Boolean {
        val retiredPaths = listOf(
            File(filesDir, "speech/vosk-tr-0.3"),
            File(filesDir, "speech/vosk-tr-0.3.installing"),
            File(cacheDir, "vosk-tr-0.3.zip.part"),
        )
        var success = true
        retiredPaths.forEach { path ->
            val removed = runCatching { !path.exists() || path.deleteRecursively() }.getOrDefault(false)
            if (!removed) success = false
        }
        return success
    }
}
