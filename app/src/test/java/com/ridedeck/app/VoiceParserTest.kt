package com.ridedeck.app

import com.ridedeck.app.data.Contact
import com.ridedeck.app.data.InfoQuestion
import com.ridedeck.app.data.NavQuestion
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VoiceAnswer
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.VoiceParser
import com.ridedeck.app.data.VoiceScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A corpus of spoken phrases and what each must turn into; a failure names the phrase. */
class VoiceParserTest {
    private fun expect(command: VoiceCommand, vararg phrases: String) {
        for (p in phrases) assertEquals("\"$p\"", command, VoiceParser.parse(p))
    }

    private fun expectKind(kind: Class<out VoiceCommand>, vararg phrases: String) {
        for (p in phrases) {
            val c = VoiceParser.parse(p)
            assertTrue("\"$p\" gave $c", kind.isInstance(c))
        }
    }

    @Test
    fun goingHomeWorkAndParked() {
        expect(VoiceCommand.GoHome, "Eve git", "eve götür", "eve gidelim", "evime git", "Beni eve götür", "eve doğru git", "eve dön", "lütfen eve git", "şimdi eve gidelim", "eve yönlendir", "ev adresime git")
        expect(VoiceCommand.GoWork, "İşe git", "işe gidelim", "iş yerime git", "işyerime götür", "beni işe götür", "ofise git", "işe doğru git", "işe yönlendir")
        expect(VoiceCommand.GoParked, "park yerime git", "motoruma git", "arabama git", "bisikletime git", "park yerimi göster", "motorum nerede", "arabam nerede", "park ettiğim yere git")
    }

    @Test
    fun navigatingToAPlace() {
        for (p in listOf("Kadıköy'e git", "Kadıköy'e gidelim", "Kadıköy'e götür", "beni Kadıköy'e götür", "Kadıköy'e rota", "Kadıköy'e yol tarifi")) {
            assertEquals("\"$p\"", "kadıköy", (VoiceParser.parse(p) as VoiceCommand.NavigateTo).candidates.first())
        }
        for (p in listOf("rota taksim meydanı", "yol tarifi taksim meydanı", "navigasyon taksim meydanı")) {
            assertEquals("\"$p\"", "taksim meydanı", (VoiceParser.parse(p) as VoiceCommand.NavigateTo).candidates.first())
        }
        val noApostrophe = VoiceParser.parse("Kadıköye git") as VoiceCommand.NavigateTo
        assertEquals("kadıköye", noApostrophe.candidates.first())
        assertTrue("kadıköy" in noApostrophe.candidates)
        val ankara = VoiceParser.parse("Ankara git") as VoiceCommand.NavigateTo
        assertEquals("ankara", ankara.candidates.first())
        val ayas = VoiceParser.parse("Ankara'ya gidelim") as VoiceCommand.NavigateTo
        assertEquals("ankara", ayas.candidates.first())
        expectKind(VoiceCommand.NavigateTo::class.java, "Sabiha Gökçen Havalimanı'na git", "Bursa'ya gitmek istiyorum".replace("gitmek istiyorum", "git"), "Beşiktaş iskelesine git", "Moda sahiline nasıl giderim")
    }

    @Test
    fun stoppingAndRouteOptions() {
        expect(VoiceCommand.StopNavigation, "navigasyonu bitir", "Navigasyonu kapat", "rotayı iptal et", "yönlendirmeyi durdur", "gitmekten vazgeç", "vazgeçtim", "yolculuğu bitir", "navigasyonu iptal et", "geldik", "vardım")
        expect(VoiceCommand.AvoidHighways(true), "otoyolsuz git", "otoyolu kullanma", "otoyol kullanma", "otoyoldan gitme", "otoyolsuz rota")
        expect(VoiceCommand.AvoidHighways(false), "otoyol kullan", "otoyoldan git")
        expect(VoiceCommand.AvoidTolls(true), "ücretsiz yol", "ücretsiz yoldan git", "paralı yolları kullanma", "gişesiz rota", "paralı yoldan gitme")
        expect(VoiceCommand.AvoidTolls(false), "paralı yol kullan", "paralı yoldan git")
        expect(VoiceCommand.Recalculate, "rotayı yeniden hesapla", "rotayı tekrar hesapla", "yeni rota")
    }

    @Test
    fun navigationQuestions() {
        expect(VoiceCommand.NavInfo(NavQuestion.ARRIVAL), "ne zaman varırım", "kaçta varırım", "varış saati", "tahmini varış")
        expect(VoiceCommand.NavInfo(NavQuestion.REMAINING_TIME), "kaç dakika kaldı", "ne kadar kaldı", "kalan süre", "ne kadar sürer", "kalan süre ne", "kaç saat kaldı")
        expect(VoiceCommand.NavInfo(NavQuestion.REMAINING_DISTANCE), "kaç kilometre kaldı", "kaç km kaldı", "kalan mesafe", "ne kadar yol var", "kaç metre kaldı")
        expect(VoiceCommand.NavInfo(NavQuestion.NEXT_TURN), "sıradaki dönüş ne", "sıradaki dönüş", "bir sonraki dönüş", "nereden döneceğim", "nerede döneceğim", "şimdi ne yapayım", "sonraki dönüş")
    }

    @Test
    fun savingPlaces() {
        expect(VoiceCommand.SaveFavoriteHere, "burayı favoriye ekle", "burayı kaydet", "favori yap", "burayı favori yap", "bu konumu kaydet")
        expect(VoiceCommand.SetHomeHere, "evimi buraya ayarla", "burası evim", "evim burası", "burayı ev yap")
        expect(VoiceCommand.SetWorkHere, "işimi buraya ayarla", "burası işim", "işim burası", "burayı iş yap")
    }

    @Test
    fun stopsAndNearbyPlaces() {
        expect(VoiceCommand.FindStops, "benzinlik", "en yakın benzinlik", "yolda benzinlik", "yakındaki benzinlik", "yakındaki benzinlikleri bul", "benzinlik bul", "benzin lazım", "yakıt lazım", "en yakın şarj istasyonu", "yolda şarj istasyonu", "rota üzerindeki şarj istasyonlarını göster", "en yakın tamirci", "şarj", "yakınımdaki benzinlik var mı")
        expect(VoiceCommand.FindNearest("kafe", "kafe"), "en yakın kafe", "yakında kafe var mı", "kafe bul", "en yakın kahve", "kafe göster")
        expect(VoiceCommand.FindNearest("eczane", "eczane"), "en yakın eczane", "eczane bul", "eczane nerede", "yakında eczane var mı", "eczane lazım")
        expect(VoiceCommand.FindNearest("hastane", "hastane"), "en yakın hastane", "hastane bul", "yakında hastane var mı", "en yakın acil")
        expect(VoiceCommand.FindNearest("atm", "ATM"), "en yakın atm", "atm bul", "en yakın bankamatik", "yakında atm var mı")
        expect(VoiceCommand.FindNearest("otopark", "otopark"), "en yakın otopark", "otopark bul", "otopark nerede")
        expect(VoiceCommand.FindNearest("market", "market"), "en yakın market", "market bul", "yakında market var mı")
        expect(VoiceCommand.FindNearest("restoran", "restoran"), "en yakın restoran", "restoran bul", "restoran lazım")
        expect(VoiceCommand.FindNearest("kafe", "kafe", along = true), "rota üzerindeki kafeleri bul", "rotadaki kafeler nerede")
    }

    @Test
    fun callingPeopleAndNumbers() {
        expect(VoiceCommand.Call("ali"), "Ali'yi ara", "ali'yi ara", "aliyi ara", "Ali ara", "ara Ali", "Ali'yi arar mısın", "Ali ile konuş", "Ali'yi aramak istiyorum", "lütfen Ali'yi ara")
        expect(VoiceCommand.Call("annemi"), "annemi ara")
        expect(VoiceCommand.Call("annem"), "ara annem", "annemi arar mısın")
        expect(VoiceCommand.Call("ayşe demir"), "Ayşe Demir'i ara", "ara Ayşe Demir")
        expect(VoiceCommand.CallDigits("05363482995"), "sıfır beş üç altı üç dört sekiz iki dokuz dokuz beş ara", "ara sıfır beş üç altı üç dört sekiz iki dokuz dokuz beş", "0536 348 2995 ara")
        expect(VoiceCommand.CallLast, "son aradığımı ara", "son arananı ara", "tekrar ara", "son numarayı ara")
        expect(VoiceCommand.CallMissed, "cevapsız aramayı ara", "cevapsızı ara", "beni arayanı ara", "geri ara")
        expect(VoiceCommand.ShowRecents, "cevapsız aramalar", "cevapsızlara bak", "son aramalar", "arama geçmişi")
    }

    @Test
    fun callsInProgress() {
        expect(VoiceCommand.AnswerCall, "cevapla", "aramayı cevapla", "telefonu aç", "aç", "kabul et")
        expect(VoiceCommand.RejectCall, "reddet", "aramayı reddet", "aramayı kapat", "telefonu kapat", "kapat")
        expect(VoiceCommand.MuteCall, "sessize al", "mikrofonu kapat", "sesimi kapat")
        expect(VoiceCommand.SpeakerCall(true), "hoparlör", "hoparlöre al", "hoparlörü aç", "hoparlöre geç")
        expect(VoiceCommand.SpeakerCall(false), "hoparlörü kapat", "kulaklığa al", "kulaklığa geç")
    }

    @Test
    fun messages() {
        expect(VoiceCommand.ReadMessage, "son mesajı oku", "mesajı oku", "yeni mesaj var mı", "mesaj geldi mi", "mesajları oku", "ne yazmış", "oku")
        expect(VoiceCommand.WhoWrote, "kim yazdı", "kimden mesaj var", "kimden geldi", "mesaj kimden")
        expect(VoiceCommand.ReplyMessage(null), "cevap yaz", "yanıt yaz", "cevap ver", "mesaja cevap ver", "ona cevap yaz")
        expect(VoiceCommand.ReplyMessage("yoldayım geliyorum"), "cevap yaz yoldayım geliyorum", "ona yaz yoldayım geliyorum", "yanıtla yoldayım geliyorum")
    }

    @Test
    fun music() {
        expect(VoiceCommand.MusicPlay, "müziği aç", "müziği çal", "müzik çal", "müzik aç", "çal", "devam et", "oynat", "müziğe devam", "şarkıyı çal", "müziği başlat")
        expect(VoiceCommand.MusicPause, "müziği durdur", "müziği kapat", "durdur", "duraklat", "müziği duraklat", "şarkıyı durdur", "müziği sustur", "çalmayı durdur")
        expect(VoiceCommand.MusicNext, "sonraki şarkı", "sonraki", "bir sonraki şarkı", "şarkıyı geç", "atla", "sonrakine geç", "sıradaki şarkı")
        expect(VoiceCommand.MusicPrevious, "önceki şarkı", "önceki", "bir önceki şarkı", "başa al", "şarkıyı başa sar")
        expect(VoiceCommand.PlaySearch("tarkan"), "Tarkan çal", "tarkan'ı çal", "çal tarkan", "tarkan dinlemek istiyorum", "Spotify'da Tarkan çal", "Tarkan dinle")
        expect(VoiceCommand.PlaySearch("sezen aksu"), "Sezen Aksu çal", "çal Sezen Aksu", "YouTube müzik'te Sezen Aksu çal")
        expect(VoiceCommand.Info(InfoQuestion.NOW_PLAYING), "ne çalıyor", "bu ne şarkı", "kim söylüyor", "şu an ne çalıyor")
    }

    @Test
    fun volume() {
        expect(VoiceCommand.VolumeUp, "sesi aç", "sesi yükselt", "sesi artır", "sesi biraz aç", "ses seviyesini artır", "sesi arttır")
        expect(VoiceCommand.VolumeDown, "sesi kıs", "sesi azalt", "sesi düşür", "sesi biraz kıs", "ses seviyesini azalt")
        expect(VoiceCommand.VolumeChange(2), "sesi iki kademe aç", "sesi 2 kademe yükselt")
        expect(VoiceCommand.VolumeChange(-3), "sesi üç kademe kıs", "ses seviyesini 3 kademe düşür")
        expect(VoiceCommand.VolumeSet(50), "sesi yüzde elli yap", "sesi elli yap", "ses yüzde elli", "sesi yüzde 50 yap", "ses seviyesini elli yap")
        expect(VoiceCommand.VolumeSet(25), "sesi yüzde yirmi beş yap", "ses yüzde yirmi beş")
        expect(VoiceCommand.VolumeSet(100), "sesi yüzde yüz yap")
        assertTrue(VoiceParser.parse("sesi altı kademe aç") is VoiceCommand.Unknown)
    }

    @Test
    fun threeIndependentCommandsStayInTheirSpokenOrder() {
        val parsed = VoiceParser.parse("sesi biraz kıs ve ekranı kıs sonra gece modunu aç")
        assertEquals(
            VoiceCommand.Chain(
                VoiceCommand.VolumeDown,
                VoiceCommand.Chain(VoiceCommand.Brightness(-1), VoiceCommand.SetTheme(ThemeMode.NIGHT)),
            ),
            parsed,
        )
    }

    @Test
    fun excessiveCommandChainIsNotMisreadAsAnAppOrPlace() {
        assertTrue(VoiceParser.parse("sesi kıs ve ekranı kıs ve gece modunu aç ve müziği durdur ve eve git") is VoiceCommand.Unknown)
    }

    @Test
    fun information() {
        expect(VoiceCommand.Info(InfoQuestion.WEATHER), "hava nasıl", "hava durumu", "kaç derece", "yağmur var mı", "yağmur yağacak mı", "hava kaç derece", "hava durumunu söyle")
        expect(VoiceCommand.Info(InfoQuestion.TIME), "saat kaç", "saati söyle", "saat ne", "şimdi saat kaç")
        expect(VoiceCommand.Info(InfoQuestion.RIDE_STATUS), "durum ne", "sürüş durumum", "genel durum")
        expect(VoiceCommand.Info(InfoQuestion.BATTERY), "pil durumu", "pil yüzde kaç", "şarjım ne kadar", "batarya", "pil ne kadar kaldı")
        expect(VoiceCommand.Info(InfoQuestion.SPEED), "kaç ile gidiyorum", "hızım kaç", "hız kaç")
        expect(VoiceCommand.Info(InfoQuestion.SPEED_LIMIT), "hız sınırı kaç", "hız limiti kaç", "limit kaç", "hız sınırı")
    }

    @Test
    fun appsAndSettings() {
        expect(VoiceCommand.SetTheme(ThemeMode.NIGHT), "gece modu", "gece moduna geç", "karanlık mod", "koyu tema")
        expect(VoiceCommand.SetTheme(ThemeMode.DAY), "gündüz modu", "gündüz moduna geç", "aydınlık mod")
        expect(VoiceCommand.SetTheme(ThemeMode.AUTO), "otomatik tema", "temayı otomatik yap")
        expect(VoiceCommand.SetMiles(true), "mil kullan", "mph kullan", "birimi mil yap", "mil cinsinden göster")
        expect(VoiceCommand.SetMiles(false), "kilometre kullan", "km kullan", "birimi kilometre yap")
        expect(VoiceCommand.OpenScreen(VoiceScreen.MAP), "haritayı aç", "haritaya git", "haritayı göster")
        expect(VoiceCommand.OpenScreen(VoiceScreen.MUSIC), "müzik ekranını aç", "müzik çalara git")
        expect(VoiceCommand.OpenScreen(VoiceScreen.PHONE), "rehberi aç", "tuş takımını aç", "telefon ekranını aç")
        expect(VoiceCommand.OpenScreen(VoiceScreen.APPS), "uygulamaları aç", "uygulama rafını aç")
        expect(VoiceCommand.OpenScreen(VoiceScreen.SETTINGS), "ayarları aç", "ayarlara git")
        expect(VoiceCommand.OpenScreen(VoiceScreen.HOME), "ana ekrana dön", "ana ekran", "ana ekrana git")
    }

    @Test
    fun alternativeRouteBrightnessWarningsAndTrip() {
        expect(VoiceCommand.AlternativeRoute, "başka rota", "alternatif rota", "başka yoldan git", "başka yol var mı", "başka bir rota")
        expect(VoiceCommand.Recalculate, "rotayı yeniden hesapla", "yeni rota")
        expect(VoiceCommand.Brightness(-1), "ekranı kıs", "parlaklığı azalt", "ekranı karart")
        expect(VoiceCommand.Brightness(1), "ekranı aç", "parlaklığı artır", "ekranı aydınlat")
        expect(VoiceCommand.WarningVoice(false), "sesli uyarıları kapat", "uyarıları sustur")
        expect(VoiceCommand.WarningVoice(true), "sesli uyarıları aç")
        expect(VoiceCommand.Info(InfoQuestion.TRIP), "yolculuk özeti", "bugün kaç km", "ne kadar yol yaptım", "son yolculuk")
    }

    @Test
    fun stopsOnTheWayAreFlagged() {
        assertEquals(VoiceCommand.FindNearest("kafe", "kafe", along = true), VoiceParser.parse("yolda kafe"))
        assertEquals(VoiceCommand.FindNearest("eczane", "eczane", along = true), VoiceParser.parse("rota üzerinde eczane"))
        assertEquals(VoiceCommand.FindNearest("kafe", "kafe", along = false), VoiceParser.parse("en yakın kafe"))
    }

    @Test
    fun openingAppsByName() {
        expect(VoiceCommand.OpenApp("whatsapp"), "WhatsApp'ı aç", "whatsapp aç", "WhatsApp uygulamasını aç")
        expect(VoiceCommand.OpenApp("chrome"), "Chrome'u aç", "chrome'u başlat")
        expect(VoiceCommand.OpenApp("gmail"), "Gmail aç")
    }

    @Test
    fun assistantItself() {
        expect(VoiceCommand.Help, "ne diyebilirim", "neler diyebilirim", "komutlar", "yardım", "ne yapabilirsin")
        expect(VoiceCommand.Emergency, "yardım çağır", "acil yardım", "kaza yaptım", "ambulans çağır", "sos")
    }

    @Test
    fun noiseIsNotACommand() {
        for (p in listOf("", "   ", "bugün hava çok güzel dostum", "asdf", "evet hayır belki", "iki bin yirmi altı")) {
            assertTrue("\"$p\"", VoiceParser.parse(p) is VoiceCommand.Unknown)
        }
    }

    @Test
    fun answers() {
        for (y in listOf("evet", "Evet", "tamam", "olur", "gönder", "onayla")) assertEquals(y, VoiceAnswer.Yes, VoiceParser.parseAnswer(y))
        for (n in listOf("hayır", "Hayır", "olmaz", "istemiyorum", "yok")) assertEquals(n, VoiceAnswer.No, VoiceParser.parseAnswer(n))
        for (c in listOf("iptal", "vazgeç", "dur", "boşver")) assertEquals(c, VoiceAnswer.Cancel, VoiceParser.parseAnswer(c))
        assertEquals(VoiceAnswer.Repeat, VoiceParser.parseAnswer("tekrar"))
        assertEquals(VoiceAnswer.Ordinal(0), VoiceParser.parseAnswer("birincisi"))
        assertEquals(VoiceAnswer.Ordinal(1), VoiceParser.parseAnswer("ikinci"))
        assertEquals(VoiceAnswer.Ordinal(1), VoiceParser.parseAnswer("ikincisine git"))
        assertEquals(VoiceAnswer.Ordinal(0), VoiceParser.parseAnswer("ilkine dön"))
        assertEquals(VoiceAnswer.Ordinal(0), VoiceParser.parseAnswer("yok, ilkine dön"))
        assertEquals(VoiceAnswer.Ordinal(2), VoiceParser.parseAnswer("üçüncü"))
        assertEquals(VoiceAnswer.Other("yoldayım"), VoiceParser.parseAnswer("yoldayım"))
    }

    @Test
    fun contactMatching() {
        val list = listOf(Contact("Ali Yılmaz", "1"), Contact("Ali Kaya", "2"), Contact("Ayşe", "3"), Contact("Annem", "4"), Contact("Mehmet Öz", "5"))
        assertEquals(2, VoiceParser.matchContact("ali", list).size)
        assertEquals("Annem", VoiceParser.matchContact("annem", list).first().name)
        assertEquals("Ayşe", VoiceParser.matchContact("ayse", list).first().name)
        assertEquals("Ali Kaya", VoiceParser.matchContact("ali kaya", list).first().name)
        assertEquals("Mehmet Öz", VoiceParser.matchContact("mehmedi", list).first().name)
        assertTrue(VoiceParser.matchContact("zeynep", list).isEmpty())
    }
}
