# RideDeck sesli asistanı

Bu belge uygulamadaki mevcut davranışı anlatır; “planlandı” olanı çalışan özellik gibi sunmaz. Voice 2.0 geliştirmesi sürüyor.

## Mimari

- `VoiceAssistant` konuşma akışını, bekleyen soruları, seçimleri ve onayları yönetir. Eylemleri `VoiceActions` arayüzü üzerinden çağırır; Android tarafındaki uygulama `AndroidVoiceActions`, testlerde sahte eylemlerdir. Compose, diyalog içeriği ve ses makinesi durumunu tek `VoicePresentationState` anlık görüntüsünden gösterir.
- `VoiceCommand.kt` içindeki `VoiceParser`, Türkçe normalizasyon ve kural tablosuyla komutları çözer. `VoiceLoose` yalnızca sınırlandırılmış zararsız öneriler üretir. Belirsiz veya tanınmayan ifade rastgele eyleme dönüştürülmemelidir.
- `DialogContext` son yer/kişi/komut gibi bağlamı bellekte yaklaşık iki dakika tutar. Mesaj ve arama gibi işlemler kendi güvenlik akışlarına sahiptir.
- `VoiceInput` Android `SpeechRecognizer` için tek seferlik dinleme sağlar. `VoskVoiceInput` aynı `SpeechListener` sözleşmesini kullanır; `SelectableVoiceInput` ayardaki motora göre seçim yapar.
- `VoskModelStore` isteğe bağlı Türkçe modeli uygulamanın özel alanında tutar. Model kullanıcı isteğiyle indirilir, APK'ya eklenmez.
- `VoskWakeWord` ve Android `WakeWord` deneysel uyandırma yollarıdır. Sürekli dinleme varsayılan olarak kapalıdır. `Speaker` ve ViewModel'deki tek TTS örneği yanıtları ve anonsları koordine eder.

## Tanıma motorları ve çevrimdışı çalışma

- Android tanıyıcısı varsayılandır; `EXTRA_PREFER_OFFLINE` talep edilir, ancak gerçek çevrimdışı destek cihazın tanıyıcı hizmetine bağlıdır.
- Vosk küçük Türkçe modeli isteğe bağlı ve deneysel bir çevrimdışı seçenektir. Model yaklaşık 35 MB'dır. Gerçek tanıma kalitesi ve performans cihaz testine bağlıdır.
- İki motor da tek seferlik tanıma arayüzünü uygular. Mesaj diktesi, kullanıcı mesaj yazmayı seçtikten sonra açılır.

## Oturum, yaşam döngüsü ve Bluetooth

- `AudioSessionManager` süreç genelinde Android STT, Vosk STT, iki wake-word motoru ve TTS için tek geçerli sahiplik token'ı tutar. Yeni oturum öncekinin kaynaklarını iptal eder; her callback token'ını doğrular, eski callback yeni oturumu bırakamaz. Aktif motor `owner` akışıyla yayınlanır. `VoiceStateMachine`, UI ve ses sahibinin IDLE, wake, aktivasyon, komut, işlem, onay, konuşma, takip, hata ve askıda durumlarını tek akışta gözler.
- Android SpeechRecognizer çağrıları ana iş parçacığında yapılır. Dinleme başlangıcında geçici audio focus alınır; `BluetoothMicRouter` bağlı Bluetooth SCO/BLE kulaklık değişikliklerini izler, tercih etkinse Android 12+ iletişim mikrofonuna geçer ve kopma/ayar kapanmasında telefon mikrofonuna döner. Bitiş/iptalde focus bırakılır ve iletişim cihazı temizlenir.
- TTS de sahiplik ve audio focus alır; konuşma bittiğinde bırakır. Böylece wake listener TTS sırasında durur ve kaynak boşalınca ayarlar uygunsa yeniden açılır.
- Vosk tanıma işi tek iş parçacıklı executor'da yürür; dinleme ve sessizlik zaman aşımı vardır. Wake-word Vosk servisi de deneysel ve ayrı bir dinleyicidir; aynı mikrofon sahipliğini kullanır.
- Activity arka plana geçince aktif komut dinleme iptal edilir. Telefon görüşmesi başladığında asistan/TTS kesilir; çağrı durumu değişince wake uygunluğu yeniden hesaplanır.
- Android SpeechRecognizer ve Vosk tanıma hataları kullanıcıya eyleme dönük Türkçe/İngilizce geri bildirimle aktarılır; wake listener'ları sınırlı üstel beklemeyle yeniden dener. Android gerçek servis/izin davranışı cihazda doğrulanmalıdır.
- Kask mikrofonu Android 12+ iletişim cihazı API'siyle denenir. Donanım çeşitliliği, eski Android routing davranışı, fiziksel kask düğmeleri ve gerçek sürüşte geçişler **UNVERIFIED**.

## Türkçe komutlar ve diyalog

- `Turkish` Türkçe harflerle küçük harfe çevirme, noktalama/boşluk temizliği, yaygın ekleri ve sayı sözcüklerini destekler. `VoiceParser` doğal komut varyantlarını ve alternatif STT sonuçlarını kurallarla değerlendirir.
- En çok dört ayrı komut `ve`, `sonra`, `ardından`, `bir de`, `ayrıca` gibi bağlaçlarla zincirlenebilir. Sıra korunur; asenkron eylem tamamlanmadan sonraki çalıştırılmaz.
- Birden fazla kritik eylem (arama, mesaj/yanıt, SOS) içeren zincir reddedilir. Tek kritik eylem kendi onay akışını korur.
- Asistan belirsiz kişi/sonuç için soru sorabilir; evet/hayır, iptal, tekrar ve seçenek numaraları desteklenir. Takip dinlemesi sınırlıdır ve sonsuz döngüye dönüştürülmemelidir.
- Voice destination and fuel-stop searches retain up to four results for two minutes. The assistant asks which destination to route to; fuel searches can be followed by a separate “ikincisine git” turn. Ordinal replies accept Turkish case endings and corrections such as “yok, ilkine dön”. A stale result cannot be selected, route failures are reported as failures, and a fuel detour keeps the prior destination.
- Natural place searches accept route-relative forms and inflected place names (for example “rota üzerindeki kafeleri bul”). Reminders understand “bir buçuk saat”. Unsupported volume step counts are rejected instead of falling through to the loose intent matcher.

## RideContext

- `RideContextProvider` projects current navigation, remaining distance/time/arrival, fresh GPS speed, a confirmed battery reading, Bluetooth microphone availability, active media, weather and selected vehicle profile.
- Stale GPS, absent media session, unreported battery and unknown Bluetooth state remain unavailable. “Durum ne?” / “sürüş durumum” reports the available facts; no route, track or reading is invented.

## Eylemler ve güvenlik

- Navigasyon, medya, telefon, mesaj, sistem bilgisi ve ayarlar mevcut `VoiceActions` uygulamasına yönlendirilir. Uygulamanın/servisin desteklemediği sonuç başarılıymış gibi bildirilmemelidir.
- Mesaj gönderimi sesli onay ister; çağrı geri sayımı vardır; SOS kendi onay akışını kullanır. Belirsiz kişi seçimi kullanıcıya sorulur.
- Sesli komut sistemi Android mikrofon iznini atlamaz. Sürekli dinleme kullanıcının açık tercihini gerektirir ve varsayılanı kapalıdır.

## Ayarlar ve geçmiş

Ayarlar > Sürüş bölümünde tanıma motoru, Vosk modeli, kask mikrofonu, sesli yanıt, yanıt uzunluğu ve konuşma hızı, komut geçmişi, kask tuşu tetikleyicisi, deneysel “Hey RideDeck”, proaktif uyarı anahtarı ve her uyarı kategorisi bulunur. Rutinler yerel saklanır; oluşturulup düzenlenebilir, silinebilir ve sesle veya ekrandan çalıştırılabilir. Desteklenen adımlar kayıtlı eve gitme, medya ses seviyesi, sesli hava uyarıları ve tema seçimidir. Hassas telefon/SMS/SOS eylemleri rutin modelinde temsil edilemez. “Normal moda dön” rutinle değişen ses, uyarı ve tema değerlerini ilk rutin çalıştırılmadan önceki hâline döndürür; navigasyon hedefi geri alınabilir ayar kabul edilmez. Rutin uygulaması eksik ev adresinde kısmi sonucu açıkça bildirir.

RideDeck ham ses saklamaz. Komut geçmişi metindir, kullanıcı tarafından kapatılabilir ve temizlenebilir. Android'in sistem konuşma tanıyıcısının kendi veri politikası uygulama denetiminin dışındadır.

## Doğrulama durumu (2026-10-25)

- **COMPLETED:** Android/Vosk ortak tek seferlik dinleyici sözleşmesi, `AudioSessionManager` ile tek sahiplik/oturum token'ı, stale callback koruması, TTS sahipliği ve activity/call temizliği; `VoiceStateMachine` ve takip dinlemesi geçişleri testli.
- **COMPLETED:** Bluetooth cihaz olaylarına dinamik routing/fallback, wake retry backoff ve Android/Vosk hata sınıflandırması; kademeli Türkçe medya ses komutları.
- **COMPLETED:** Yerel düzenlenebilir/silinebilir sürüş rutinleri; Türkçe rutin adını eşleme; önceki ses/uyarı/tema ayarını kalıcı snapshot ile geri alma; minimal/normal/ayrıntılı yanıt kipi; kalıcı konuşma hızı; cihazda yüklü Türkçe TTS seslerini listeleyip seçme; kategori başına kapatılabilen yağmur, düşük pil, yüksek pil sıcaklığı, varış, mola ve gözlemlenmiş Bluetooth mikrofon kopması uyarıları. Pil uyarısı yalnızca gerçek pil okuması varsa çalışır; düşük pil uyarısı ekran parlaklığını değiştirmez. Bluetooth kopma uyarısı yalnızca önceki ölçümde mikrofon erişilebilirken sonraki ölçümde erişilemez olduğunu görürse üretilir.
- **COMPLETED:** Compose ses katmanı tanıma/işleme/onay/hata ve ses sahipliği durumunu tek `VoicePresentationState` akışından, son algılanan komut ve eylem sonucuyla gösterir. Rutin yönetimi sürüş hızı kilidini uygular. Medya kontrolleri aktif `MediaSession` eylem yeteneklerini denetler; desteklenmeyen play/pause/next/previous/search eylemleri başarı gibi bildirilmez. Minimal yanıt eylemi kısaltır, fakat pil/rota/hava gibi bilgi yanıtlarının temel bilgisini korur. Yanıt modu ve konuşma hızı İngilizce/Türkçe kaynaklardan gösterilir.
- **COMPLETED:** Telefon/SMS/SOS içeren tanınan komutlar maskelenir; ayrıştırılamayan fakat arama/mesaj/SOS belirteci içeren ifadeler de hem geçmişe hem anlaşılmayan ifadeler listesine kaydedilmeden önce maskelenir. Telefon benzeri sayılar ayrıca maskelenir. Ham ses saklanmaz.
- **COMPLETED:** Eski yerel komut/miss JSON'u yüklenirken yeniden maskelenip güvenli biçimde tercihlere geri yazılır; writer ve list helper'ları da kaydetmeden önce sanitize eder. Vosk exception loglarında throwable içeriği/stacktrace yoktur. Sesli yanıt ayarını kapatmak mevcut TTS'i ve kuyruğu durdurur.
- **COMPLETED:** Arama intent'i izin/Android eylem talebinin kabulünü, yalnız sistem çeviricisinin açılmasını ve gerçek başlatma hatasını ayırır; başarısız cevapla/bitir/mute/ses yönü talepleri başarı diye sunulmaz. SMS dispatch logu hedef/exception metni taşımaz; sesli başarı yanıtı telefonun isteği aldığını bildirir, teslim iddiasında bulunmaz.
- **COMPLETED:** Navigasyon `ARRIVED`, `USER_CANCELLED`, `LOCATION_DISABLED`, `LOCATION_PERMISSION_REVOKED`, `TRACKING_ERROR` nedenlerini ayrı tutar. `LocationRepository` GPS provider callback'lerini ve uygulama `onResume` sırasında izin kontrolünü yayınlar; provider kapanınca veya izin kaldırılmışsa takip doğru nedenle durur. Proaktif navigasyon uyarısı yalnız `TRACKING_ERROR` ile tetiklenir; kullanıcı iptali, varış, GPS kapatma veya izin kaybı tetiklemez.
- **COMPLETED:** Son doğrulamada `testDebugUnitTest` (338 test), `lintDebug`, `assembleDebug`, `assembleRelease` ve `compileDebugAndroidTestKotlin` başarılı. `VoiceComposeFlowTest` içindeki altı Compose testi ayrılmış Android 15 test AVD'sinde hem varsayılan hem 640×360 yatay kompakt düzende 6/6 geçti. Kapsam; rutin düzenleme kilidi/CRUD, işlenen komut, TTS durdurma, tanıma motoru seçimi ve Vosk indirme durum satırını içerir. Son `git diff --check` temiz.
- **PARTIAL/BLOCKED:** Sessiz/stale GPS güncellemeleri için güvenilir neden sinyali yok; stale fix beklenmedik hata sayılmıyor. Collector exception yolu kod/test olarak kapsanıyor.
- **IMPLEMENTED_UNVERIFIED:** Gerçek Android/Vosk ses doğruluğu ve gecikmesi, motor seçimi, kask mikrofonu, Bluetooth kopup bağlanması, audio focus/ducking, çağrı kesişimi, gerçek Türkçe TTS sesi ve gerçek sürüş/proaktif zamanlama.
- **UNVERIFIED:** Gerçek Android cihazında mikrofon/STT/Vosk doğruluğu, kask Bluetooth yönlendirmesi, audio focus/ducking ve gerçek sürüş davranışı. Fiziksel A059 testleri tamamlanmadı. Gerçek telefon araması, SMS veya SOS ile test yapılmadı.

### Park hâli fiziksel cihaz doğrulama matrisi (bu oturumda çalıştırılmadı)

- [ ] Ekran portre/yatay ve küçük çözünürlükte ayar kartları, rutin formu ve sesli overlay kaydırması.
- [ ] Mikrofon izni reddi/sonradan verilmesi; asistanın doğru durum/eylem mesajı ve iptal.
- [ ] Android STT ↔ hazır Vosk motoru geçişi; model silme/indirmenin iptali ve tanıma hatası.
- [ ] Kask Bluetooth bağlanma/kopma/yeniden bağlanma; telefon mikrofonuna fallback ve kullanıcı tercihi kapalı durumu.
- [ ] TTS kısa/normal/ayrıntılı yanıt, hız/ses seçimi, TTS durdurma; navigasyon anonsu ile çakışma ve medya ducking.
- [ ] Komut geçmişini kapatınca kayıtların silinmesi; hassas eski geçmişin migration sonrası paylaşım ekranında maskeli kalması.
- [ ] Rutin düzenlemenin taze GPS'te 5 km/sa ve üstünde, ayrıca GPS verisi yok/stale iken reddi; parked durumda CRUD ve gerçek sonuç geri bildirimi.
- [ ] GPS provider kapatma/açma, uygulama arka plandayken konum iznini kaldırıp geri dönme, stale fix ve gerçek route collector hatası; doğru durdurma nedeni, yalnız gerçek tracking error uyarısı.
- [ ] Gerçek arama/SMS/SOS işlemi olmadan onay ve iptal ekranı akışları.
- **IMPLEMENTED_UNVERIFIED:** Android/Vosk gerçek ses doğruluğu, gecikme, kask mikrofonu, Bluetooth kopup bağlanma, sürekli dinleme pil/ısı etkisi ve yanlış uyandırma.
- Önceki tamamlanmış navigasyon/medya/diyalog işleri bu belgenin yukarısında açıklanmıştır. Bu bölümdeki eski backlog kaydı güncel durum değildir; kalan iş yalnızca yukarıdaki TODO ve UNVERIFIED maddeleridir.

LLM entegrasyonu bu kapsamda kullanılmaz. Eski PLAN.md içindeki cihaz içi dil modeli önerisi, güncel görev kapsamıyla geçersizdir ve uygulanmamalıdır.

## Kritik regresyon düzeltmeleri (2026-10-25)

- Vosk wake-word retry işleri merkezi iptal/generation denetimiyle yönetilir. `stop()` bekleyen retry'ı iptal eder; retry çalışmadan hemen önce ekran, çağrı, tercih, pil, mikrofon izni, seçili motor ve model uygunluğu tekrar kontrol edilir. Android wake dinleyicisi de callback ve yeniden dinleme öncesi aynı canlı uygunluğu denetler. ViewModel kapanınca Vosk worker durdurulur.
- Sesli hedef seçimi rota hesabı tamamlanana kadar bekler ve ancak hazır rotada başarı verir. Ev/iş/park/favori, arama sonucu, yakıt durağı, rota üstü durak ve rutin içindeki eve rota akışları gerçek route sonucunu kullanır. Timeout/hata/iptal başarısızdır; generation kontrolü kullanıcı iptali, hedef değişikliği ve gecikmiş arama/rota callback'lerinin yeni rotayı ezmesini engeller.
- Normal ve takip dinlemesi partial transcript'i aynı gizlilik kuralıyla gizler. Tam komut sınıflandırılınca mevcut hassas transcript maskelemesi UI, geçmiş ve missed-phrase kayıtlarında sürer.
- TTS yanıtları `SUCCESS`, `INFORMATION`, `PARTIAL` ve `ERROR` olarak ayrılır. Minimal kip yalnızca başarı eylemini “Tamam.”a kısaltır; kısmi/başarısız rutin ve rota sonuçlarını korur. Başlangıçta TTS hazır olduktan sonra Türkçe ses listesi çözülür, kayıtlı ses yüklenir, ses yoksa Türkçe locale fallback uygulanır; init hatası sessiz ve güvenli durumda kalır. Navigasyon anonsu kesilen yanıtın bekleyen follow-up'ını yeni anons bitene kadar taşır; TTS submit hatası token ve devam callback'ini temizler.
- Regresyon kapsamı: iptal/yeniden planlama ve retry uygunluk politikası; rota başarısı, hata, timeout, iptal, eski sonuç, arama iptalinde rota başlatmama ve yakıt rotası hatası; takip partial gizliliği; kayıtlı/kurulu Türkçe ses, offline fallback ve init başarısızlığı politikası; minimal/normal/ayrıntılı partial/error yanıtları.
- Güncel doğrulama: 359 unit test; `lintDebug`, `assembleDebug`, `assembleRelease`, `compileDebugAndroidTestKotlin`, `git diff --check` başarılı. Compose akışı bu çalışmada 6/6 geçti; son kaynak değişikliklerinden sonraki yeniden çalıştırma, 2.8 GB boş diskle AVD'nin host disk denetiminde reddedilmesi nedeniyle **UNVERIFIED** kaldı. Fiziksel Android ses, Bluetooth/STT/TTS, izin geri alma ve sürüş davranışı **UNVERIFIED**; gerçek arama/SMS/SOS testi yapılmadı.
