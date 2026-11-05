package com.ridedeck.app

import com.ridedeck.app.data.GuidanceLanguage
import com.ridedeck.app.data.TtsVoice
import com.ridedeck.app.data.TtsVoicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoicePolicyTest {
    private val offline = TtsVoice("tr-local", "tr", requiresNetwork = false)
    private val online = TtsVoice("tr-cloud", "tr", requiresNetwork = true)
    private val english = TtsVoice("en-local", "en", requiresNetwork = false)
    private val englishOnline = TtsVoice("en-cloud", "en", requiresNetwork = true)

    @Test fun restoresSavedVoiceWhenItIsInstalled() {
        assertEquals(online, TtsVoicePolicy.choose("tr-cloud", listOf(offline, online), "tr"))
    }

    @Test fun missingSavedVoiceFallsBackToAnOfflineVoice() {
        assertEquals(offline, TtsVoicePolicy.choose("removed-voice", listOf(online, english, offline), "tr"))
    }

    @Test fun onlineVoiceIsFallbackOnlyWhenNoOfflineVoiceExists() {
        assertEquals(online, TtsVoicePolicy.choose(null, listOf(english, online), "tr"))
    }

    @Test fun noVoiceOfTheLanguageLeavesLocaleFallbackToTheTtsEngine() {
        assertNull(TtsVoicePolicy.choose("en-local", listOf(english), "tr"))
    }

    @Test fun savedVoiceOfAnotherLanguageIsIgnored() {
        assertEquals(english, TtsVoicePolicy.choose("tr-local", listOf(offline, englishOnline, english), "en"))
        assertEquals(offline, TtsVoicePolicy.choose("en-local", listOf(offline, english), "tr"))
    }

    @Test fun successfulInitializationPublishesVoicesAndRestoresPreferenceInItsStartupPlan() {
        val plan = TtsVoicePolicy.startupPlan(true, "tr-cloud", listOf(english, offline, online), "tr")
        assertEquals(true, plan.ready)
        assertEquals(listOf(online, offline), plan.voices)
        assertEquals(online, plan.selected)
    }

    @Test fun englishPhoneOnlyOffersEnglishVoices() {
        val plan = TtsVoicePolicy.startupPlan(true, "tr-cloud", listOf(online, offline, englishOnline, english), "en")
        assertEquals(listOf(englishOnline, english), plan.voices)
        assertEquals(english, plan.selected)
    }

    @Test fun failedInitializationNeverPublishesUnusableVoiceChoices() {
        val plan = TtsVoicePolicy.startupPlan(false, "tr-cloud", listOf(offline, online), "tr")
        assertEquals(false, plan.ready)
        assertEquals(emptyList<TtsVoice>(), plan.voices)
        assertNull(plan.selected)
    }

    @Test fun guidanceLanguageIsTurkishOnlyForTurkishPhones() {
        assertEquals("tr-TR", GuidanceLanguage.tag("tr"))
        assertEquals("en-US", GuidanceLanguage.tag("en"))
        assertEquals("en-US", GuidanceLanguage.tag("de"))
        assertEquals("en-US", GuidanceLanguage.tag(""))
    }
}
