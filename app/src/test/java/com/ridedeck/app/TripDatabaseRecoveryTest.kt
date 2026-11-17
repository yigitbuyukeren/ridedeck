package com.ridedeck.app

import com.ridedeck.app.data.TripDatabaseRecovery
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting all trips must also remove the forensic copies made after a corrupt database. */
class TripDatabaseRecoveryTest {
    @Test
    fun deleteAllAlsoRemovesCorruptCopiesAndTheirSidecars() {
        val dir = Files.createTempDirectory("ridedeck-db").toFile()
        val db = dir.resolve("trips.db")
        val mainCopy = dir.resolve("trips.db.corrupt-123")
        val walCopy = dir.resolve("trips.db.corrupt-123-wal")
        val unrelated = dir.resolve("settings.json")
        mainCopy.writeText("trips")
        walCopy.writeText("wal")
        unrelated.writeText("keep")

        TripDatabaseRecovery.deleteCopies(db.path)

        assertFalse("main forensic copy", mainCopy.exists())
        assertFalse("sidecar forensic copy", walCopy.exists())
        assertTrue("unrelated files stay", unrelated.exists())
    }
}
