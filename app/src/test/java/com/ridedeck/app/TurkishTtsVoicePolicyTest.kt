package com.ridedeck.app

import com.ridedeck.app.data.TurkishTtsVoice
import com.ridedeck.app.data.TurkishTtsVoicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurkishTtsVoicePolicyTest {
    private val offline = TurkishTtsVoice("tr-local", "tr", requiresNetwork = false)
    private val online = TurkishTtsVoice("tr-cloud", "tr", requiresNetwork = true)
    private val english = TurkishTtsVoice("en-local", "en", requiresNetwork = false)

    @Test fun restoresSavedTurkishVoiceWhenItIsInstalled() {
        assertEquals(online, TurkishTtsVoicePolicy.choose("tr-cloud", listOf(offline, online)))
    }

    @Test fun missingSavedVoiceFallsBackToAnOfflineTurkishVoice() {
        assertEquals(offline, TurkishTtsVoicePolicy.choose("removed-voice", listOf(online, english, offline)))
    }

    @Test fun onlineTurkishVoiceIsFallbackOnlyWhenNoOfflineVoiceExists() {
        assertEquals(online, TurkishTtsVoicePolicy.choose(null, listOf(english, online)))
    }

    @Test fun noTurkishVoiceLeavesLocaleFallbackToTheTtsEngine() {
        assertNull(TurkishTtsVoicePolicy.choose("en-local", listOf(english)))
    }

    @Test fun successfulInitializationPublishesVoicesAndRestoresPreferenceInItsStartupPlan() {
        val plan = TurkishTtsVoicePolicy.startupPlan(true, "tr-cloud", listOf(english, offline, online))
        assertEquals(true, plan.ready)
        assertEquals(listOf(online, offline), plan.voices)
        assertEquals(online, plan.selected)
    }

    @Test fun failedInitializationNeverPublishesUnusableVoiceChoices() {
        val plan = TurkishTtsVoicePolicy.startupPlan(false, "tr-cloud", listOf(offline, online))
        assertEquals(false, plan.ready)
        assertEquals(emptyList<TurkishTtsVoice>(), plan.voices)
        assertNull(plan.selected)
    }
}
