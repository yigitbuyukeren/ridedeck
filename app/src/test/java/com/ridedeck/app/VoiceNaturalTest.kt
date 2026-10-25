package com.ridedeck.app

import com.ridedeck.app.data.InfoQuestion
import com.ridedeck.app.data.NavQuestion
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.VoiceParser
import com.ridedeck.app.data.VoiceScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measuring stick for the loose pass: how people really talk (extra words, another order, "şey", "bi"), plus
 * sentences that must never become a command. The canonical corpus stays in [VoiceParserTest]; the real phrases that
 * were missed on the road (Settings > Setup > export) are added here.
 */
class VoiceNaturalTest {
    private fun expect(command: VoiceCommand, vararg phrases: String) {
        for (p in phrases) assertEquals("\"$p\"", command, VoiceParser.parse(p))
    }

    @Test
    fun informationInOtherWords() {
        expect(VoiceCommand.Info(InfoQuestion.WEATHER), "bugün hava nasıl olacak", "hava nasıl bugün", "şey hava nasıl", "hava durumu nedir")
        expect(VoiceCommand.Info(InfoQuestion.WEATHER_TOMORROW), "yarın hava nasıl", "yarın yağmur yağacak mı", "yarın hava nasıl olacak")
        expect(VoiceCommand.Info(InfoQuestion.WEATHER_EVENING), "akşam hava nasıl olacak", "akşam yağmur var mı")
        expect(VoiceCommand.Info(InfoQuestion.TIME), "şu an saat kaç oldu", "saati söyle bakalım")
        expect(VoiceCommand.Info(InfoQuestion.BATTERY), "telefonun pili kaç kalmış", "pilim kaç oldu")
        expect(VoiceCommand.Info(InfoQuestion.SPEED), "şu an hızım kaç", "kaç ile gidiyorum şu an")
        expect(VoiceCommand.Info(InfoQuestion.SPEED_LIMIT), "bu yolda hız sınırı kaç")
    }

    @Test
    fun musicAndVolumeInOtherWords() {
        expect(VoiceCommand.VolumeDown, "müziği biraz kıs", "sesi çok kıs lütfen")
        expect(VoiceCommand.VolumeUp, "sesi biraz daha aç")
        expect(VoiceCommand.MusicPause, "müziği biraz durdur", "müziği bi durdur")
        expect(VoiceCommand.MusicNext, "bu şarkıyı geç")
    }

    @Test
    fun routeAndScreensInOtherWords() {
        expect(VoiceCommand.StopNavigation, "navigasyonu artık bitir")
        expect(VoiceCommand.NavInfo(NavQuestion.REMAINING_TIME), "kaç dakika sonra varırım")
        expect(VoiceCommand.NavInfo(NavQuestion.NEXT_TURN), "bir sonraki sapak nerede")
        expect(VoiceCommand.OpenScreen(VoiceScreen.MAP), "haritayı bir aç")
        expect(VoiceCommand.SetTheme(ThemeMode.NIGHT), "gece moduna geçelim")
        expect(VoiceCommand.ReadMessage, "gelen mesajı bir oku")
    }

    @Test
    fun twoCommandsInOneSentence() {
        expect(VoiceCommand.Chain(VoiceCommand.VolumeDown, VoiceCommand.GoHome), "müziği kıs ve eve git")
        expect(VoiceCommand.Chain(VoiceCommand.GoHome, VoiceCommand.MusicPlay), "eve git sonra müziği aç")
    }

    @Test
    fun remindersAndStops() {
        expect(VoiceCommand.Reminder(20, null), "yirmi dakika sonra hatırlat")
        expect(VoiceCommand.Reminder(30, null), "yarım saat sonra hatırlat")
        expect(VoiceCommand.Reminder(90, null), "bir buçuk saat sonra hatırlat")
        expect(VoiceCommand.Reminder(60, null), "bir saat sonra hatırlat")
        expect(VoiceCommand.AddStop(emptyList(), PlaceKind.HOME), "bir de eve uğra")
        assertTrue(VoiceParser.parse("Migros'a uğra") is VoiceCommand.AddStop)
        expect(VoiceCommand.SendSms("ali"), "Ali'ye mesaj at", "Ali'ye sms yaz")
        expect(VoiceCommand.Repeat, "tekrar et", "ne dedin")
        expect(VoiceCommand.Undo, "geri al")
        expect(VoiceCommand.StopProactive, "bir daha sorma")
    }

    @Test
    fun ordinaryTalkIsNotACommand() {
        val negatives = listOf(
            "bugün hava çok güzel dostum", "dün akşam müzik dinledik çok güzeldi", "annem beni aradı", "saat üçte buluşacağız",
            "ben eve gittim", "müzik zevkin çok güzel", "kafede arkadaşlarla buluştuk", "yarın toplantı var", "pil sorunu yok",
            "sesi duydun mu", "iki bin yirmi altı",
        )
        for (p in negatives) assertTrue("\"$p\" gave ${VoiceParser.parse(p)}", VoiceParser.parse(p) is VoiceCommand.Unknown)
    }

    @Test
    fun aNameWithVeInItStaysOnePlace() {
        val c = VoiceParser.parse("Sezen Aksu ve Tarkan çal")
        assertTrue("gave $c", c !is VoiceCommand.Chain)
    }
}
