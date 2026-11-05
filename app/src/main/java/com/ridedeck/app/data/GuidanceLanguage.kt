package com.ridedeck.app.data

import java.util.Locale

/** The one language spoken guidance uses: Valhalla instruction text and the TTS voice must never disagree. */
object GuidanceLanguage {
    /** Turkish phones get Turkish; Valhalla ships a fixed set of languages, so everything else falls back to English. */
    fun tag(language: String): String = if (language.equals("tr", ignoreCase = true)) "tr-TR" else "en-US"

    fun current(): String = tag(Locale.getDefault().language)

    fun currentLocale(): Locale = Locale.forLanguageTag(current())
}
