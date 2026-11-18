package com.ridedeck.app

import com.ridedeck.app.data.GuidanceLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceLanguageTest {
    @Test
    fun turkishGetsTurkishInstructionsAndVoice() {
        assertEquals("tr-TR", GuidanceLanguage.tag("tr"))
        assertEquals("tr-TR", GuidanceLanguage.tag("tr-TR"))
        assertEquals("tr-TR", GuidanceLanguage.tag(Locale.forLanguageTag("tr-TR")))
    }

    @Test
    fun englishGetsEnglishInstructionsAndVoice() {
        assertEquals("en-US", GuidanceLanguage.tag("en"))
        assertEquals("en-US", GuidanceLanguage.tag("en-GB"))
    }

    @Test
    fun unsupportedLanguagesFallBackToEnglish() {
        assertEquals("en-US", GuidanceLanguage.tag("de"))
        assertEquals("en-US", GuidanceLanguage.tag("fr-FR"))
        assertEquals("en-US", GuidanceLanguage.tag("es"))
        assertEquals("en-US", GuidanceLanguage.tag(""))
    }

    @Test
    fun ttsLanguageCodeIsDerivedFromTheResolvedTag() {
        assertEquals("tr", GuidanceLanguage.languageCode("tr-TR"))
        assertEquals("en", GuidanceLanguage.languageCode("en-US"))
        assertEquals("en", GuidanceLanguage.languageCode(""))
    }
}
