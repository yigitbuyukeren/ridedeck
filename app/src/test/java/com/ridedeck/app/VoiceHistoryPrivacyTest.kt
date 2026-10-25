package com.ridedeck.app

import com.ridedeck.app.data.VoiceHistory
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.containsSensitiveTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceHistoryPrivacyTest {
    @Test fun numberLikeTranscriptsAreMaskedBeforePersistence() {
        assertEquals("Ara •••• ••• •• ••", VoiceHistory.redactPhoneNumbers("Ara 0532 123 45 67"))
        assertEquals("ses yüzde 45 olsun", VoiceHistory.redactPhoneNumbers("ses yüzde 45 olsun"))
        assertEquals("•••••••", VoiceHistory.redactPhoneNumbers("1234567"))
    }

    @Test fun unrecognizedSensitiveActionsAreMaskedBeforeHistoryAndMissExport() {
        assertEquals("•••", VoiceHistory.safeTranscript("Ahmet'i ara lütfen"))
        assertEquals("•••", VoiceHistory.safeTranscript("anneme mesaj gönder geliyorum"))
        assertEquals("•••", VoiceHistory.safeTranscript("SOS gönder"))
        assertEquals("•••", VoiceHistory.safeTranscript("Ayşe'yi aramak istiyorum"))
        assertEquals("•••", VoiceHistory.safeTranscript("Babam'a mesajı gönderebilir misin"))
        assertEquals("yakındaki benzinliği bul", VoiceHistory.safeTranscript("yakındaki benzinliği bul"))
    }

    @Test fun previouslyPersistedTranscriptsAreSanitizedWhenLoaded() {
        val oldHistory = "[{\"h\":\"Ayşe'yi ara\",\"u\":false,\"t\":1},{\"h\":\"yakındaki benzinliği bul\",\"u\":true,\"t\":2}]"
        assertEquals(listOf("•••", "yakındaki benzinliği bul"), VoiceHistory.fromJson(oldHistory).map { it.heard })
        assertEquals(listOf("•••", "yakındaki benzinliği bul"), VoiceHistory.missesFromJson("[\"Ahmet'i ara\",\"yakındaki benzinliği bul\"]"))
        assertEquals("•••", VoiceHistory.fromJson(VoiceHistory.toJson(listOf(com.ridedeck.app.data.VoiceHistoryEntry("Ayşe'yi ara", true, 3)))).single().heard)
        assertEquals(listOf("•••"), VoiceHistory.missesFromJson(VoiceHistory.missesToJson(listOf("SMS ile Ayşe'ye yaz"))))
    }

    @Test fun disabledHistoryDoesNotHydrateLegacyTranscriptsIntoUiState() {
        val old = "[{\"h\":\"Ayşe'yi ara\",\"u\":true,\"t\":1}]"
        assertEquals(emptyList<com.ridedeck.app.data.VoiceHistoryEntry>(), VoiceHistory.fromPreference(old, enabled = false))
        assertEquals(listOf("•••"), VoiceHistory.fromPreference(old, enabled = true).map { it.heard })
    }

    @Test fun contactAndMessageCommandsAreMarkedSensitiveIncludingChains() {
        assertEquals(true, VoiceCommand.Call("Ayşe").containsSensitiveTranscript())
        assertEquals(true, VoiceCommand.CallDigits("05321234567").containsSensitiveTranscript())
        assertEquals(true, VoiceCommand.ReplyMessage("geliyorum").containsSensitiveTranscript())
        assertEquals(true, VoiceCommand.Chain(VoiceCommand.VolumeUp, VoiceCommand.SendSms("Ayşe")).containsSensitiveTranscript())
        assertEquals(false, VoiceCommand.MusicPause.containsSensitiveTranscript())
    }
}
