package com.ridedeck.app.data

/** Tidies the turn-by-turn texts the routing service sends. */
object InstructionText {
    private val roadTypes = "Caddesi|Sokağı|Sokak|Bulvarı|Yolu|Cd\\.|Sk\\."

    /** "Ahmet Yesevi Caddesi caddesine doğru sola dönün" names the road type twice: "Ahmet Yesevi Caddesine …". */
    private val doubledType = Regex("\\b($roadTypes)\\s+(?:$roadTypes)(\\p{L}*)", RegexOption.IGNORE_CASE)

    /** Road type word -> dative, locative, ablative forms of the name's own type word. */
    private val caseForms: Map<String, List<String>> = mapOf(
        "caddesi" to listOf("Caddesine", "Caddesinde", "Caddesinden"),
        "cadde" to listOf("Caddeye", "Caddede", "Caddeden"),
        "sokağı" to listOf("Sokağına", "Sokağında", "Sokağından"),
        "sokak" to listOf("Sokağa", "Sokakta", "Sokaktan"),
        "bulvarı" to listOf("Bulvarına", "Bulvarında", "Bulvarından"),
        "yolu" to listOf("Yoluna", "Yolunda", "Yolundan"),
        "meydanı" to listOf("Meydanına", "Meydanında", "Meydanından"),
        "köprüsü" to listOf("Köprüsüne", "Köprüsünde", "Köprüsünden"),
        "otoyolu" to listOf("Otoyoluna", "Otoyolunda", "Otoyolundan"),
        "çıkmazı" to listOf("Çıkmazına", "Çıkmazında", "Çıkmazından"),
    )

    /**
     * Valhalla's tr-TR template is "<name> caddesine doğru". When the name already ends in its own road type
     * ("Moda Bostanı Sokağı caddesine") the generic word is dropped and the case goes on the name's type word:
     * "Moda Bostanı Sokağına doğru". Abbreviations ("Cd.", "Sk.") and names without a road type are left alone.
     */
    private val genericRoad = Regex(
        "\\b(${caseForms.keys.sortedByDescending { it.length }.joinToString("|")})\\s+(caddesine|caddesinde|caddesinden)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun clean(text: String): String =
        genericRoad.replace(
            doubledType.replace(text) { m ->
                val first = m.groupValues[1]
                val second = m.value.substring(first.length).trim()
                // Same road type twice only; "Caddesi sokağına" is two different roads and stays as it is.
                if (second.take(first.length.coerceAtMost(4)).equals(first.take(4), ignoreCase = true)) {
                    val ending = second.drop(first.length.coerceAtMost(second.length)).ifEmpty { m.groupValues[2] }
                    first + ending
                } else m.value
            }
        ) { m ->
            val forms = caseForms[m.groupValues[1].lowercase()] ?: return@replace m.value
            val index = when (m.groupValues[2].lowercase()) {
                "caddesinde" -> 1
                "caddesinden" -> 2
                else -> 0
            }
            forms[index]
        }.replace(Regex("\\s{2,}"), " ").trim()
}
