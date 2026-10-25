# RideDeck sesli asistanı

Bu belge uygulamadaki mevcut davranışı anlatır; “planlandı” olanı çalışan özellik gibi sunmaz. Manuel ses tanıma Android'in `SpeechRecognizer` servisiyle yapılır; isteğe bağlı “Hey RideDeck” uyandırması ise sherpa-onnx KWS ve APK içindeki İngilizce Zipformer modelini cihazda çalıştırır. Uygulama ayrıca Vosk'un eski tercihini Android'e geçirip yalnızca eski modele ait dosyaları açılışta temizler.

## Mimari

- `VoiceAssistant` konuşma akışını, bekleyen soruları, seçimleri ve onayları yönetir. Eylemleri `VoiceActions` arayüzü üzerinden çağırır; Android tarafındaki uygulama `AndroidVoiceActions`, testlerde sahte eylemlerdir. Compose, diyalog içeriği ve ses makinesi durumunu tek `VoicePresentationState` anlık görüntüsünden gösterir.
- `VoiceCommand.kt` içindeki `VoiceParser`, Türkçe normalizasyon ve kural tablosuyla komutları çözer. `VoiceLoose` yalnızca sınırlandırılmış zararsız öneriler üretir. Belirsiz veya tanınmayan ifade rastgele eyleme dönüştürülmemelidir.
- `DialogContext` son yer/kişi/komut gibi bağlamı bellekte yaklaşık iki dakika tutar. Mesaj ve arama gibi işlemler kendi güvenlik akışlarına sahiptir.
- `VoiceInput` Android `SpeechRecognizer` ile kullanıcı başlatmalı komut, mesaj diktesi ve takip dinlemesini sağlar. `SherpaWakeWord`, streaming sherpa-onnx KeywordSpotter ile “Hey RideDeck” phrase'ini yerel algılar; eşleşme olunca KWS audio owner bırakılır ve mevcut Android STT tek seferlik komut oturumunu başlatır.
- `RideDeckApplication` eski tanıma motoru tercihini Android'e geçirir ve yalnızca eski Vosk model klasörünü, yarım kurulum dizinini ve indirme parçasını siler. Başka tercihlere veya uygulama verilerine dokunmaz.
- `Speaker` ve ViewModel'deki tek TTS örneği yanıtları, proaktif uyarıları ve navigasyon anonslarını koordine eder.

## Tanıma ve çevrimdışı çalışma

- Android tanıyıcısı `tr-TR` diliyle açılır ve `EXTRA_PREFER_OFFLINE` ister. Çevrimdışı tanıma, cihazda Türkçe konuşma paketi bulunmasına ve sistem hizmetinin desteğine bağlıdır; servis sağlayıcısının kendi veri politikası RideDeck'in denetimi dışındadır.
- Mesaj diktesi yalnızca kullanıcı mesaj yazmayı seçtikten sonra başlar. Android servisi erişilemiyorsa hata kullanıcıya bildirilir; asistan veya uygulama kapanmaz.
- Uyandırma modeli ve özel anahtar kelime APK'da bulunduğundan ilk kullanımda ağ indirmesi gerekmez. KWS modeli English GigaSpeech Zipformer tabanlıdır; Türkçe aksan, gürültü ve cihaz mikrofonuna göre algılama doğruluğu fiziksel cihazda **UNVERIFIED** durumundadır.
- Android `SpeechRecognizer` sürekli tanıma motoru değildir. A059 loglarında eski döngü sessizlikte `NO_SPEECH_DETECTED`, yeniden açılışta `MICROPHONE_UNAVAILABLE` (kod 2) üretmişti; bu yol kaldırıldı. Yerel KWS yalnızca uygulama etkin/önde, ekran açık, mikrofon izni verilmiş, çağrı yok ve asistan boşta iken Ayarlar > Sürüş'te açık tercih edilirse başlar. Varsayılanı kapalıdır.

## Oturum, yaşam döngüsü ve Bluetooth

- `AudioSessionManager` sherpa KWS, kullanıcı başlatmalı Android STT, takip dinlemesi ve TTS için süreç genelinde tek etkin sahiplik token'ı tutar. Yeni oturum öncekini iptal eder; KWS, STT veya TTS başladığında mikrofon kaydını bırakır. Her callback generation/oturum token'ını doğrular ve eski callback yeni oturumu bırakamaz. `VoiceStateMachine`, UI ve ses sahibi durumlarını tek akışta tutar.
- Android `SpeechRecognizer` ana iş parçacığında çalışır. Dinleme başlangıcında geçici audio focus alınır; `BluetoothMicRouter` bağlı SCO/BLE kulaklık değişikliklerini izler, tercih açıksa Android 12+ iletişim mikrofonuna geçer ve kopma/ayar kapanmasında telefon mikrofonuna döner. Bitiş ve iptalde focus bırakılır, iletişim cihazı temizlenir.
- TTS sahiplik ve audio focus alır. TTS başladığında wake KWS durur; ses sahipliği boşalınca koşullar uygunsa yeniden başlar. Çağrı başlayınca asistan ve TTS kesilir; çağrı bittiğinde koşullar yeniden değerlendirilir.
- Activity `onPause` olduğunda KWS ve aktif komut dinleme iptal edilir; `onResume` sonrasında izin/ayar/çağrı/owner şartları uygunsa KWS yeniden başlar. Eşleşme tek tetikleme guard'ı ve 2,5 saniye cooldown uygular; KWS akışını kapatıp ancak sonra Android STT'ye devreder. Motor açılış hatası sınırlı 30 saniyelik yeniden denemeyle tutulur.
- Kask mikrofonu, eski Android ses yönlendirmesi ve gerçek sürüşte Bluetooth geçişleri cihaz matrisinde ayrıca doğrulanmalıdır.

## Sürekli uyandırma sınırı

Android'in [`SpeechRecognizer`](https://developer.android.com/reference/android/speech/SpeechRecognizer) API'si sürekli tanıma için tasarlanmamıştır; kısa tanıma oturumlarını döngüyle yeniden başlatmak bu sınırlamayı çözmez. A059'da eski yöntem boş dinlemede `NO_SPEECH_DETECTED`, yeniden açılışta `MICROPHONE_UNAVAILABLE` üretmişti. Mevcut “Hey RideDeck” yolu sherpa-onnx KWS'dir ve sistem STT'yi uyandırmadan sonra tek seferlik kullanır.

Sistem `VoiceInteractionService` DSP yolundan bağımsız olarak KWS modeli uygulama APK'sındaki `assets/wake/` altında paketlenir. Tercih kapalıyken, ekran/Activity arka plandayken, çağrıda ya da başka ses sahibi aktifken microphone capture yapılmaz. Gerçek tetikleme, yanlış tetiklenme, pil/ısı etkisi ve kask yönlendirmesi kullanıcı tarafından A059 üzerinde sesli denenmelidir.

## Türkçe komutlar ve diyalog

- `Turkish` Türkçe harflerle küçük harfe çevirme, noktalama/boşluk temizliği, yaygın ekleri ve sayı sözcüklerini destekler. `VoiceParser` doğal komut varyantlarını ve alternatif tanıma sonuçlarını kurallarla değerlendirir.
- En çok dört ayrı komut `ve`, `sonra`, `ardından`, `bir de`, `ayrıca` gibi bağlaçlarla zincirlenebilir. Sıra korunur; asenkron eylem tamamlanmadan sonraki çalıştırılmaz.
- Birden fazla kritik eylem (arama, mesaj/yanıt, SOS) içeren zincir reddedilir. Tek kritik eylem kendi onay akışını korur.
- Asistan belirsiz kişi/sonuç için soru sorabilir; evet/hayır, iptal, tekrar ve seçenek numaraları desteklenir. Takip dinlemesi sınırlıdır ve sonsuz döngüye dönüştürülmemelidir.
- Hedef ve yakıt durağı aramaları iki dakika boyunca dört adede kadar sonucu tutar. Asistan rota için hedef seçtirir; eski sonuç seçilemez, rota hatası başarı diye bildirilmez ve yakıt durağı önceki hedefi korur.
- Yer araması rota üzerindeki yerleri ve çekimli adları destekler. Hatırlatmalar “bir buçuk saat” ifadesini anlar. Desteklenmeyen ses kademesi sayıları zararsız öneri eşlemesine düşürülmeden reddedilir.

## RideContext

- `RideContextProvider` güncel navigasyon, kalan mesafe/süre/varış, taze GPS hızı, ölçülmüş pil, Bluetooth mikrofon erişimi, aktif medya, hava ve araç profilini sunar.
- Eski GPS, olmayan medya oturumu, ölçülmemiş pil ve bilinmeyen Bluetooth durumu yok/boş kalır. “Durum ne?” / “sürüş durumum” yalnızca mevcut verileri bildirir.

## Eylemler, ayarlar ve gizlilik

- Navigasyon, medya, telefon, mesaj, sistem bilgisi ve ayarlar `VoiceActions` üzerinden yürütülür. Desteklenmeyen eylem başarılıymış gibi bildirilmez.
- Mesaj gönderimi sesli onay ister; çağrı geri sayımı vardır; SOS kendi onay akışını kullanır. Belirsiz kişi seçimi kullanıcıya sorulur.
- Ayarlar > Sürüş yanıt uzunluğu ve konuşma hızını, Türkçe TTS sesini, kask mikrofonunu, komut geçmişini, çift basış kask tuşunu, isteğe bağlı offline “Hey RideDeck” anahtarını, proaktif uyarıları ve rutinleri gösterir.
- RideDeck ham ses saklamaz. Komut geçmişi metindir, kullanıcı tarafından kapatılabilir ve temizlenebilir. Eski “Vosk” motor tercihi uygulama güncellenip açıldığında Android tanıyıcısına çevrilir.

## Doğrulama

- Regresyon testleri sherpa KWS audio owner preemption/cooldown, Android STT oturum sahipliği, komut/eylem akışları, hassas ifade gizliliği, TTS ve rutinleri kapsar.
- Rutin, medya, rota, SMS ve telefon akışları birim/Compose testleriyle doğrulanır; cihazın konuşma tanıyıcısı, kask Bluetooth yönlendirmesi, audio focus ve sürüş davranışı için fiziksel cihaz testi gerekir.
- Gerçek arama, SMS veya SOS işlemi test kapsamına dahil değildir.
