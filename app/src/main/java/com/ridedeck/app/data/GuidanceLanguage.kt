package com.ridedeck.app.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The one language spoken guidance uses: Valhalla instruction text and the TTS voice must never disagree. */
object GuidanceLanguage {
    /**
     * Turkish gets Turkish; every other app language falls back to English because Valhalla and the
     * bundled resources only ship those two. A full BCP 47 tag is accepted as well as a bare code.
     */
    fun tag(language: String): String =
        if (Locale.forLanguageTag(language).language.equals("tr", ignoreCase = true)) "tr-TR" else "en-US"

    fun tag(locale: Locale?): String = tag(locale?.language.orEmpty())

    /** Reads the app's own locale, so Android 13+ per-app language changes are honoured over the system locale. */
    fun tag(configuration: Configuration): String {
        val locales = configuration.locales
        return if (locales.isEmpty) tag(Locale.getDefault()) else tag(locales[0])
    }

    fun current(context: Context): String = tag(context.resources.configuration)

    fun currentLocale(context: Context): Locale = Locale.forLanguageTag(current(context))

    /** For plain JVM callers and tests; Android UI code should pass its context instead. */
    fun current(): String = tag(Locale.getDefault().language)

    fun currentLocale(): Locale = Locale.forLanguageTag(current())

    /** The exact TTS voice language code ("tr" / "en") for a resolved guidance tag. */
    fun languageCode(tag: String): String = Locale.forLanguageTag(tag).language.ifBlank { "en" }
}
