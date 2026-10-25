package com.ridedeck.app

import com.ridedeck.app.util.LegacyVoskDataMigration
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySpeechDataMigrationTest {
    @Test fun oldEngineChoiceMigratesToAndroidAndCurrentChoiceStays() {
        assertEquals("system", LegacyVoskDataMigration.androidEngine("vosk"))
        assertEquals("system", LegacyVoskDataMigration.androidEngine("system"))
        assertEquals("system", LegacyVoskDataMigration.androidEngine(null))
    }

    @Test fun cleanupRemovesOnlyRetiredModelArtifacts() {
        val root = Files.createTempDirectory("ridedeck-legacy-speech").toFile()
        try {
            val files = File(root, "files")
            val cache = File(root, "cache")
            val installedModel = File(files, "speech/vosk-tr-0.3/model.bin")
            val incompleteInstall = File(files, "speech/vosk-tr-0.3.installing/temp.bin")
            val partialArchive = File(cache, "vosk-tr-0.3.zip.part")
            val unrelatedFile = File(files, "speech/other-feature/data.bin")
            listOf(installedModel, incompleteInstall, partialArchive, unrelatedFile).forEach { file ->
                requireNotNull(file.parentFile).mkdirs()
                file.writeText("test")
            }

            assertTrue(LegacyVoskDataMigration.removeArtifacts(files, cache))
            assertFalse(installedModel.exists())
            assertFalse(incompleteInstall.exists())
            assertFalse(partialArchive.exists())
            assertTrue(unrelatedFile.isFile)
            assertTrue(LegacyVoskDataMigration.removeArtifacts(files, cache))
        } finally {
            root.deleteRecursively()
        }
    }
}
