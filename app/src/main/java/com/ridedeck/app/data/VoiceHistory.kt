package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One thing the rider said and whether it was understood. */
data class VoiceHistoryEntry(val heard: String, val understood: Boolean, val atMs: Long)

/** The last few spoken commands as text (never audio), newest first; pure list handling. */
object VoiceHistory {
    const val MAX = 10
    private val PHONE_NUMBER = Regex("(?<!\\d)\\+?\\d[\\d\\s().-]{5,}\\d(?!\\d)")
    private val PRIVATE_ACTION = Regex("(?iu)\\b(ara[a-zçğıöşü]*|telefon|mesaj[a-zçğıöşü]*|sms|whatsapp|ilet[a-zçğıöşü]*|gönder[a-zçğıöşü]*|yolla[a-zçğıöşü]*|at[a-zçğıöşü]*|yaz[a-zçğıöşü]*|yanıt[a-zçğıöşü]*|cevap[a-zçğıöşü]*|sos[a-zçğıöşü]*|acil|imdat|call[a-z]*|dial[a-z]*|text[a-z]*|message[a-z]*|send[a-z]*)\\b")

    /** Masks number-like text before it can enter either local history store. */
    fun redactPhoneNumbers(text: String): String = PHONE_NUMBER.replace(text) { match ->
        if (match.value.count(Char::isDigit) < 7) match.value else match.value.map { if (it.isDigit()) '•' else it }.joinToString("")
    }

    /** Unknown/STT-missed call, message or SOS utterances are masked before either history store. */
    fun safeTranscript(text: String): String = if (PRIVATE_ACTION.containsMatchIn(text)) "•••" else redactPhoneNumbers(text)

    fun add(list: List<VoiceHistoryEntry>, entry: VoiceHistoryEntry): List<VoiceHistoryEntry> =
        (listOf(entry.copy(heard = safeTranscript(entry.heard))) + list.map { it.copy(heard = safeTranscript(it.heard)) }).take(MAX)

    fun toJson(list: List<VoiceHistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("h", safeTranscript(it.heard)).put("u", it.understood).put("t", it.atMs)) }
        return arr.toString()
    }

    fun fromJson(json: String?): List<VoiceHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VoiceHistoryEntry(safeTranscript(o.getString("h")), o.optBoolean("u", true), o.optLong("t"))
            }.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Do not even hydrate old local transcripts into UI state when the user has disabled history. */
    fun fromPreference(json: String?, enabled: Boolean): List<VoiceHistoryEntry> =
        if (enabled) fromJson(json) else emptyList()

    const val MAX_MISSES = 200

    /** The sentences that were not understood, newest first, no repeats in a row, kept for the export in Settings. */
    fun addMiss(list: List<String>, heard: String): List<String> {
        val text = safeTranscript(heard).trim()
        if (text.isEmpty() || list.firstOrNull() == text) return list
        return (listOf(text) + list).take(MAX_MISSES)
    }

    fun missesToJson(list: List<String>): String = JSONArray(list.map(::safeTranscript)).toString()

    fun missesFromJson(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { safeTranscript(arr.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
