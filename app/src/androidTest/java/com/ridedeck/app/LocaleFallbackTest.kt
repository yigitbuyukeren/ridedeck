package com.ridedeck.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.GuidanceLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verify all five translated resource groups without modifying global device settings. */
@RunWith(AndroidJUnit4::class)
class LocaleFallbackTest {
    private fun contextFor(languageTag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return base.createConfigurationContext(configuration)
    }

    private fun assertEnglish(languageTag: String) {
        val context = contextFor(languageTag)
        assertEquals("Settings", context.getString(R.string.settings_title))
        assertEquals("End the trip?", context.getString(R.string.data_ride_stop_confirm_title))
        assertEquals(
            "You are going the wrong way. Make a U-turn when it is safe.",
            context.getString(R.string.wrong_way_uturn_voice),
        )
        assertEquals("I'm OK", context.getString(R.string.sos_action_fine))
        assertEquals("End the trip?", context.getString(R.string.stop_confirm_title))
    }

    @Test
    fun englishLocaleUsesEnglishResources() = assertEnglish("en-US")

    @Test
    fun unsupportedFrenchLocaleFallsBackToEnglish() = assertEnglish("fr-FR")

    @Test
    fun unsupportedGermanLocaleFallsBackToEnglish() = assertEnglish("de-DE")

    @Test
    fun turkishLocaleUsesTurkishResources() {
        val context = contextFor("tr-TR")
        assertEquals("Ayarlar", context.getString(R.string.settings_title))
        assertEquals("Yolculuğu bitir?", context.getString(R.string.data_ride_stop_confirm_title))
        assertEquals(
            "Ters yöndesin. Güvenli olduğunda U dönüşü yap.",
            context.getString(R.string.wrong_way_uturn_voice),
        )
        assertEquals("İyiyim", context.getString(R.string.sos_action_fine))
        assertEquals("Sürüşü bitir?", context.getString(R.string.stop_confirm_title))
    }

    @Test
    fun guidanceLanguageReadsTheAppLocaleAndFallsBackToEnglish() {
        assertEquals("tr-TR", GuidanceLanguage.current(contextFor("tr-TR")))
        assertEquals("en-US", GuidanceLanguage.current(contextFor("en-US")))
        assertEquals("en-US", GuidanceLanguage.current(contextFor("de-DE")))
        assertEquals("en-US", GuidanceLanguage.current(contextFor("fr-FR")))
        assertEquals("tr", GuidanceLanguage.currentLocale(contextFor("tr-TR")).language)
        assertEquals("en", GuidanceLanguage.currentLocale(contextFor("de-DE")).language)
    }
}
