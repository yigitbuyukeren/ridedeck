package com.ridedeck.app.data

import org.json.JSONObject

/**
 * What the last few turns were about, kept in memory for about two minutes: the place, the person and the app that
 * were just used, so that "onu ara", "oraya git" and "hayır Ankara değil İzmir" mean something. Pure logic.
 */
class DialogContext(private val now: () -> Long = { System.currentTimeMillis() }) {
    private var touchedAt = 0L
    var place: String? = null
        private set
    var contact: Contact? = null
        private set
    var app: String? = null
        private set
    var lastCommand: VoiceCommand? = null
        private set
    /** The last sentence the assistant said as a result ("tekrar et" says it again). */
    var lastSpoken: String? = null
        private set
    private var previousResults = emptyList<String>()
    private var previousResultKind = VoiceResultKind.PLACE

    private fun fresh() = touchedAt != 0L && now() - touchedAt <= TTL_MS

    fun rememberPlace(name: String) { place = name; touchedAt = now() }
    fun rememberContact(c: Contact) { contact = c; touchedAt = now() }
    fun rememberApp(label: String) { app = label; touchedAt = now() }
    fun rememberSpoken(text: String) { lastSpoken = text }
    fun rememberResults(names: List<String>, kind: VoiceResultKind) {
        previousResults = names.take(4)
        previousResultKind = kind
        touchedAt = now()
    }

    /** Commands "geri al" and "hayır X değil Y" can refer back to; "tekrar et", "geri al" and the unknown ones are not. */
    fun rememberCommand(command: VoiceCommand) {
        if (command is VoiceCommand.Repeat || command is VoiceCommand.Undo || command is VoiceCommand.Unknown) return
        lastCommand = command
        touchedAt = now()
    }

    fun clear() { place = null; contact = null; app = null; lastCommand = null; previousResults = emptyList(); touchedAt = 0L }

    /** A command that only makes sense with the context ("onu ara", "oraya git", a correction), or null. */
    fun resolve(raw: String): VoiceCommand? {
        if (!fresh()) return null
        val t = VoiceParser.clean(raw)
        if (t.isEmpty()) return null
        if (previousResults.isNotEmpty() && RESULT_SELECTION.matches(t)) {
            val ordinal = VoiceParser.parseAnswer(t) as? VoiceAnswer.Ordinal
            if (ordinal != null && ordinal.index in previousResults.indices) {
                return VoiceCommand.SelectPreviousResult(ordinal.index, previousResultKind)
            }
        }
        contact?.let { c -> if (CALL_HIM.matches(t)) return VoiceCommand.Call(c.name) }
        place?.let { p ->
            if (GO_THERE.matches(t)) return VoiceCommand.NavigateTo(VoiceParser.navigateCandidates(p))
            if (STOP_THERE.matches(t)) return VoiceCommand.AddStop(VoiceParser.navigateCandidates(p))
        }
        return correction(t)
    }

    private fun correction(t: String): VoiceCommand? {
        val last = lastCommand ?: return null
        val replacement = NOT_X_BUT_Y.find(t)?.groupValues?.get(2)
            ?: NO_Y.find(t)?.groupValues?.get(1)?.takeIf { VoiceParser.parse(it) is VoiceCommand.Unknown }
            ?: return null
        val slot = replacement.trim().substringBefore('\'').trim()
        if (slot.isEmpty()) return null
        return when (last) {
            is VoiceCommand.NavigateTo -> VoiceCommand.NavigateTo(VoiceParser.navigateCandidates(replacement.trim()))
            is VoiceCommand.AddStop -> VoiceCommand.AddStop(VoiceParser.navigateCandidates(replacement.trim()))
            is VoiceCommand.Call -> VoiceCommand.Call(slot)
            is VoiceCommand.SendSms -> VoiceCommand.SendSms(slot)
            is VoiceCommand.PlaySearch -> VoiceCommand.PlaySearch(slot)
            is VoiceCommand.OpenApp -> VoiceCommand.OpenApp(slot)
            else -> null
        }
    }

    companion object {
        const val TTL_MS = 2 * 60_000L
        private val CALL_HIM = Regex("^(?:onu|onu da|yine onu|tekrar onu) (?:ara|arar mısın|arayalım|arasana)$")
        private val GO_THERE = Regex("^(?:beni )?(?:oraya|orayı|oraya doğru) (?:git|gidelim|götür|yönlendir|rota|gidiyoruz)$")
        private val STOP_THERE = Regex("^(?:bir de |de |ayrıca )?oraya (?:de )?(?:uğra|uğrayalım)$")
        private val NOT_X_BUT_Y = Regex("^(?:hayır |yok |yanlış )?(.+?) (?:değil|olmasın|demedim) (.+)$")
        private val NO_Y = Regex("^(?:hayır|yok|yanlış) (.+)$")
        private val RESULT_SELECTION = Regex("^(?:(?:yok|hayır|yanlış) )?.+ (?:git|gidelim|götür|yönlendir|rota|dön|geri dön)$")
    }
}

/**
 * Words for the people a rider calls without a name ("annemi ara"): each one maps to a contact the rider picked once.
 * [canonical] turns every spoken form ("annemi", "anneme") into the key.
 */
object Nicknames {
    private val FORMS: Map<String, List<String>> = mapOf(
        "annem" to listOf("annem", "annemi", "anneme", "annemle", "annemden", "anne", "anneyi", "anneye"),
        "babam" to listOf("babam", "babamı", "babama", "babamla", "babamdan", "baba", "babayı", "babaya"),
        "eşim" to listOf("eşim", "eşimi", "eşime", "eşimle", "eşimden"),
        "karım" to listOf("karım", "karımı", "karıma", "karımla"),
        "kocam" to listOf("kocam", "kocamı", "kocama", "kocamla"),
        "kardeşim" to listOf("kardeşim", "kardeşimi", "kardeşime", "kardeşimle"),
        "abim" to listOf("abim", "abimi", "abime", "abimle"),
        "ablam" to listOf("ablam", "ablamı", "ablama", "ablamla"),
        "oğlum" to listOf("oğlum", "oğlumu", "oğluma", "oğlumla"),
        "kızım" to listOf("kızım", "kızımı", "kızıma", "kızımla"),
        "patron" to listOf("patron", "patronu", "patrona", "patronum", "patronumu", "patronuma", "patronla"),
    )
    private val ASKING = mapOf(
        "annem" to "Annen", "babam" to "Baban", "eşim" to "Eşin", "karım" to "Karın", "kocam" to "Kocan",
        "kardeşim" to "Kardeşin", "abim" to "Abin", "ablam" to "Ablan", "oğlum" to "Oğlun", "kızım" to "Kızın", "patron" to "Patronun",
    )
    private val BY_FORM: Map<String, String> = FORMS.flatMap { (key, forms) -> forms.map { it to key } }.toMap()

    /** The key for a heard word ("annemi" -> "annem"), or null when it is not one of the nickname words. */
    fun canonical(heard: String): String? = BY_FORM[Turkish.normalize(heard).substringBefore('\'')]

    /** "Annen" for the question "Annen kim?". */
    fun asking(key: String): String = ASKING[key] ?: key.replaceFirstChar { it.uppercase() }

    fun toJson(map: Map<String, Contact>): String {
        val o = JSONObject()
        map.forEach { (key, c) -> o.put(key, JSONObject().put("n", c.name).put("p", c.number)) }
        return o.toString()
    }

    fun fromJson(json: String?): Map<String, Contact> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().mapNotNull { key ->
                val c = o.optJSONObject(key) ?: return@mapNotNull null
                val number = c.optString("p")
                if (number.isBlank()) null else key to Contact(c.optString("n", number), number)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
