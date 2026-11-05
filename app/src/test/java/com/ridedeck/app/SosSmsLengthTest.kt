package com.ridedeck.app

import com.ridedeck.app.data.SosMath
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SOS text must stay one GSM-7 SMS (160 characters); one non-GSM character would switch it to UCS-2 and 3+ parts. */
class SosSmsLengthTest {
    private val gsm7 = ("@£\$¥èéùìòÇ\nØø\rÅå" +
        "Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿" +
        "abcdefghijklmnopqrstuvwxyzäöñüà").toSet()

    private class Texts(
        val auto: String, val manual: String, val loc: String, val stale: String, val none: String, val nouns: List<String>,
    )

    /** Reads the real resource file, unescaping like aapt (\\' and \\"). */
    private fun load(dir: String): Texts {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val map = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            map[n.attributes.getNamedItem("name").nodeValue] =
                n.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
        fun get(k: String) = map[k] ?: error("$dir missing $k")
        return Texts(
            get("sos_sms_auto"), get("sos_sms_manual"), get("sos_loc"), get("sos_loc_stale"), get("sos_loc_none"),
            listOf(get("sos_noun_moto"), get("sos_noun_car"), get("sos_noun_bike")),
        )
    }

    private val tr = load("values")
    private val en = load("values-en")

    private fun check(t: Texts, label: String) {
        val link = SosMath.locationLink(-41.012344, -128.976543)
        val messages = t.nouns.flatMap { noun ->
            listOf(
                SosMath.compose(String.format(t.auto, noun), link, true, t.none, t.stale, t.loc),
                SosMath.compose(String.format(t.auto, noun), link, false, t.none, t.stale, t.loc),
                SosMath.compose(String.format(t.auto, noun), null, false, t.none, t.stale, t.loc),
            )
        } + listOf(true, false).map { SosMath.compose(t.manual, link, it, t.none, t.stale, t.loc) }
        for (m in messages) {
            assertTrue("$label too long (${m.length}): $m", m.length <= 160)
            val bad = m.filter { it !in gsm7 }
            assertEquals("$label has non-GSM-7 characters '$bad': $m", "", bad)
        }
    }

    @Test fun turkishWorstCaseIsOneGsmSms() = check(tr, "tr")

    @Test fun englishWorstCaseIsOneGsmSms() = check(en, "en")

    @Test
    fun linkHasNoMapFragment() {
        assertEquals("https://www.openstreetmap.org/?mlat=-41.01234&mlon=-128.97654", SosMath.locationLink(-41.012344, -128.976543))
    }
}
