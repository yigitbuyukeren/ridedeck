package com.ridedeck.app

import com.ridedeck.app.data.InstructionText
import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionTextTest {
    @Test fun doubledRoadTypeIsMergedKeepingTheSuffix() =
        assertEquals("Ahmet Yesevi Caddesine doğru sola dönün.", InstructionText.clean("Ahmet Yesevi Caddesi caddesine doğru sola dönün."))

    @Test fun sokakIsMergedToo() =
        assertEquals("Gül Sokağına dönün", InstructionText.clean("Gül Sokağı sokağına dönün"))

    @Test fun twoDifferentRoadsStayAsTheyAre() =
        assertEquals("Atatürk Caddesi Gül Sokağına dönün", InstructionText.clean("Atatürk Caddesi Gül Sokağına dönün"))

    @Test fun plainTextIsUntouched() =
        assertEquals("Sağa dönün.", InstructionText.clean("Sağa dönün."))

    @Test fun sokağıWithGenericTemplateTakesTheCaseOnItsOwnType() =
        assertEquals("Moda Bostanı Sokağına doğru sola dönün.", InstructionText.clean("Moda Bostanı Sokağı caddesine doğru sola dönün."))

    @Test fun sokakWithGenericTemplateTakesTheCaseOnItsOwnType() =
        assertEquals("Yusuf Kamil Paşa Sokağa doğru sola dönün.", InstructionText.clean("Yusuf Kamil Paşa Sokak caddesine doğru sola dönün."))

    @Test fun caddesiWithGenericTemplateIsMerged() =
        assertEquals("Ahmet Yesevi Caddesine doğru sola dönün.", InstructionText.clean("Ahmet Yesevi Caddesi caddesine doğru sola dönün."))

    @Test fun bulvarıWithGenericTemplateTakesTheCaseOnItsOwnType() =
        assertEquals("Atatürk Bulvarına doğru sağa dönün.", InstructionText.clean("Atatürk Bulvarı caddesine doğru sağa dönün."))

    @Test fun yoluWithGenericTemplateTakesTheCaseOnItsOwnType() =
        assertEquals("Eski Yoluna doğru sağa dönün.", InstructionText.clean("Eski Yolu caddesine doğru sağa dönün."))

    @Test fun locativeAndAblativeTemplatesGetTheMatchingCase() {
        assertEquals("Gül Sokağında devam edin.", InstructionText.clean("Gül Sokağı caddesinde devam edin."))
        assertEquals("Gül Sokağından çıkın.", InstructionText.clean("Gül Sokağı caddesinden çıkın."))
    }

    @Test fun plainNameKeepsTheTemplate() =
        assertEquals("Bağdat caddesine doğru sola dönün.", InstructionText.clean("Bağdat caddesine doğru sola dönün."))

    @Test fun abbreviationKeepsTheTextUnchanged() =
        assertEquals("Atatürk Cd. caddesine doğru sola dönün.", InstructionText.clean("Atatürk Cd. caddesine doğru sola dönün."))

    @Test fun nextStepTextGoesThroughTheSameCleaner() =
        assertEquals("Sonra Gül Sokağına doğru sağa dönün.", InstructionText.clean("Sonra Gül Sokağı caddesine doğru sağa dönün."))
}
