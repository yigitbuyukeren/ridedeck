package com.ridedeck.app

import com.ridedeck.app.data.VoiceResponseFormatter
import com.ridedeck.app.data.VoiceResponseMode
import com.ridedeck.app.data.VoiceResponseKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResponseFormatterTest {
    @Test fun responseModesControlSpokenDetail() {
        val message = "Eve rota oluşturuldu. Tahmini varış 24 dakika."
        assertEquals("Tamam.", VoiceResponseFormatter.format(message, VoiceResponseMode.MINIMAL))
        assertEquals("Eve rota oluşturuldu.", VoiceResponseFormatter.format(message, VoiceResponseMode.NORMAL))
        assertEquals(message, VoiceResponseFormatter.format(message, VoiceResponseMode.DETAILED))
    }

    @Test fun minimalModeKeepsFactsAndAnswersInsteadOfAcknowledgingThemAway() {
        val answer = "Pil seviyesi yüzde 42. Telefon şu anda şarj oluyor."
        assertEquals("Pil seviyesi yüzde 42.", VoiceResponseFormatter.format(answer, VoiceResponseMode.MINIMAL, VoiceResponseKind.INFORMATION))
        assertEquals("Tamam.", VoiceResponseFormatter.format("Tema gece moduna alındı.", VoiceResponseMode.MINIMAL, VoiceResponseKind.ACTION))
    }
}
