# Plan listesi

**Güncelleme: 2026-11-10.** Bekleyen işler ve ürün önerileri. Kullanıcı uygulamayı açıkça istemeden geliştirmeye başlanmaz. Tamamlanan işler buradan çıkarılıp [STATUS.md](STATUS.md) içine kaydedilir. İlgili kapsam kararları: [EKSIKLER.md](EKSIKLER.md).

## Mevcut durum ve kanıt sınırı

İnceleme tabanı: `main`, `7133dd7`. Maddeler kodda görülen tutarsızlıklar, doğrulanacak riskler ve ürün önerileri olarak ayrılmıştır. Bu incelemede yeni cihaz testi veya gerçek sürüş yapılmadı; bulgular fiziksel cihazda yeniden üretilmiş sayılmaz.

Mevcut özellikler: çok noktalı rota (en fazla üç ara durak), alternatif rotalar, dönüş vurgusu, debug rota simülasyonu, uygulama ömründe navigasyon/TTS sahipliği, süreç ölümü sonrası son hedefi önizleme olarak önerme ve çevrimdışı harita indirme. Bunlar yeniden yapılacak özellikler değildir. Çevrimdışı haritanın sağlamlaştırılması ve saha kabulü bekliyor; yeni rota hesabı ve yer araması hâlâ çevrimiçi.

[İnceleme tabanının CI raporları](https://github.com/yigitbuyukeren/ridedeck/actions/runs/42001172783):

| Kontrol | Sonuç ve sınır |
|---|---|
| Debug / release birim testleri | Her varyantta aynı 390 test; 0 hata, 0 atlama. 780 farklı test değildir. |
| Debug / release lint | Her varyantta 0 hata, 87 uyarı, 2 öneri. |
| APK ve hizalama | Debug, R8 release ve Android test APK derlemesi; release ELF/ZIP hizalama kontrolleri başarılı. |
| Android testleri | Kaynakta 24 test; bu CI çalışması yalnız derliyor, cihazda çalıştırmıyor. Eski emülatör sonuçları güncel paketin saha kanıtı değildir. |
| Dil kaynakları | TR/EN 319'ar anahtar; karşılıksız anahtar yok. Görsel veya çeviri kalitesi doğrulanmış sayılmaz. |

## Uygulama sırası

| Aşama | Kapsam | Çıkış ölçütü |
|---|---|---|
| 1 — sürüş ve navigasyon doğruluğu | #1 ara durak, #2 GPS belirsizliği, #5 anons önceliği | Seçenek değişince duraklar korunur; GPS kaybı yazı girişini açmaz; mesaj yakın dönüşü kesmez. |
| 2 — bağlantı ve kalıcı durum | #3–4 çevrimdışı indirme, #6 sağlayıcı erişimi, #7–10 kurtarma/ağ/veri tazeliği | İptal, yeniden açılış, silme ve geç cevaplar doğru yönetilir; sağlayıcı koşulları uygulanır. |
| 3 — cihaz ve yayın hazırlığı | #11 çağrı erişimi, #12 belge/sürüm tutarlılığı, saha matrisi | Sürümlü beta için cihaz sonuçları kaydedilir; belgeler mevcut davranışı açıklar. Yayın ayrıca açık istek gerektirir. |
| Sonraki ürün değerlendirmesi | #13–15 | İlk üç aşamanın sonuçlarına göre kapsam seçilir. |
| N — kusursuz navigasyon | #N12 saha matrisi (aşağıda); N1–N11 2026-11-10'da kodda tamamlandı (STATUS) | Aşama 1 ile birlikte yürür. Her madde birim testi ve en az bir gerçek sürüşle kapanır; N1–N4'ün saha doğrulaması N12'de. |

#1, #2 ve #5 N1/N2/N5 ile birlikte kodda kapandı (saha doğrulaması bekler); kodda kalan navigasyon maddesi yok; sıradaki N12 saha matrisi (gerçek cihaz). Bu sıralama uygulamaya başlama onayı değildir.

## 1. Ara durakları yeniden hesaplamada koru — kodda tamamlandı 2026-11-10 (saha doğrulaması N12)

**Kodda görülen tutarsızlık:** Ara duraklı önizlemede otoyol/ücret seçeneği değişince `NavigationController.setOptions()` yalnız başlangıç ve son hedefle `routeResult()` çağırıyor. Duraklar hesaplamaya gitmiyor, UI durak listesi korunuyor. Önizlemede araç profili değişimi de aynı yolu kullanıyor.

- Kalan durakları ve sıralarını seçenek/profil değişiminde koru; harita, UI ve routing isteği aynı yolculuğu temsil etsin. Hata halinde rota ve duraklar birlikte tutarlı kalsın.
- **Kabul:** Bir/üç durak + otoyol/ücret/bisiklet seçeneği ve profil değişimi; hata, iptal ve geç cevap senaryoları. Geçilen duraklar tekrar eklenmesin.
- **Kaynak:** [NavigationController.kt](../app/src/main/java/com/ridedeck/app/data/NavigationController.kt), [MultiStopTest.kt](../app/src/test/java/com/ridedeck/app/MultiStopTest.kt). Mevcut testler seçenek değişimi birleşimini kapsamıyor.

## 2. GPS belirsizliğini sürüş kilitleri ve ısı korumasında işle — kodda tamamlandı 2026-11-10 (`MotionTracker`; saha doğrulaması N12)

**Kodda görülen tutarsızlık:** `DashboardViewModel.gps`, eski GPS verisinde sayısal hızı 0 yapıyor. Klavye, tuş takımı ve uygulama düzenleme kilitleri bu sayıya; Ayarlar kilidi ayrıca güncel fix'e bağlı. Tünel/sinyal kaybında kilitler açılabiliyor. Konum güncelken hız bilgisinin bulunmaması da ele alınmalı. Aynı 0 değeri sıcak telefonda duruş karartmasını tetikleyebilir.

- Ortak hareket durumu belirle: hareketli, duruyor, bilinmiyor. Son güvenilir hız, veri yaşı ve kayıp süresine göre tüketicilerde tutarlı davranış tanımla.
- GPS kaybını duruş kanıtı sayma; ilk açılış/izin reddi için kullanılabilir kurtarma akışı bırak.
- **Kabul:** Hareket sırasında 5 sn/1 dk kesinti, hızsız fix, GPS geri gelişi, ilk açılış ve izin kaybı. Belirsizlik yazı girişini açmasın; sıcak telefon GPS kaybı yüzünden duruş karartmasına geçmesin.
- **Kaynak:** [DashboardViewModel.kt](../app/src/main/java/com/ridedeck/app/DashboardViewModel.kt), [DashboardScreen.kt](../app/src/main/java/com/ridedeck/app/ui/DashboardScreen.kt), [MapScreen.kt](../app/src/main/java/com/ridedeck/app/ui/MapScreen.kt), [ThermalGuard.kt](../app/src/main/java/com/ridedeck/app/data/ThermalGuard.kt).

## 3. Çevrimdışı indirmede sağlayıcı sınırlarını uygula — yüksek öncelik

**Kod ve sağlayıcı koşulu:** OpenFreeMap'in 2026-11-05'te kontrol edilen [koşulları](https://openfreemap.org/tos/) indirme başına 3.000 karo veya 50 MB, en fazla dört paralel istek ve uygulamayı tanımlayan User-Agent belirtiyor. Daha büyük indirmeler için iletişim isteniyor. Uygulama sırasında koşulları yeniden kontrol et; büyük indirme izni alınmış sayılmaz.

Mevcut z5–14 algoritmasının `41.0, 29.0` çevresindeki hesabı:

| Yarıçap | Karo | Mevcut boyut tahmini |
|---|---:|---:|
| 15 km | 420 | 22 MB |
| 30 km | 1.603 | 86 MB |
| 60 km | 5.918 | 317 MB |

Boyutlar gerçek indirme ölçümü değildir. 60 km hesaplanan karo sayısıyla 3.000'i aşıyor. `setOfflineMapboxTileCountLimit(200_000)` OpenFreeMap koşullarının uygulanması anlamına gelmiyor; mevcut 200.000 sınırı testi sağlayıcı uygunluğunu kanıtlamıyor.

- Bölge/zoom seçimini koşullara göre sınırla; gerçek baytları, disk alanını, gündüz/gece kaynak paylaşımını ve paralelliği doğrula.
- MapLibre'nin gerçek ağ yolunda User-Agent'i doğrula; `Http.USER_AGENT` kullanıldığını varsayma.
- **Kabul:** Büyük bölge başlamadan engellenir veya uygun kapsam önerilir; tahmin ölçülen boyuttan ayrı sunulur; disk dolması/sınır aşımı anlaşılır sonuç verir.
- **Kaynak:** [OfflineMaps.kt](../app/src/main/java/com/ridedeck/app/data/OfflineMaps.kt), [OfflinePlan.kt](../app/src/main/java/com/ridedeck/app/data/OfflinePlan.kt), [OfflinePlanTest.kt](../app/src/test/java/com/ridedeck/app/OfflinePlanTest.kt).

## 4. Çevrimdışı indirme yaşam döngüsünü tamamla — yüksek öncelik

**Kodda görülen eksikler:** Aktif indirmede iptal yok; hata eski indirmeyi açıkça durdurmuyor; hata kapatma yalnız UI durumunu temizliyor. Yeni denemeyi eski callback'lerden ayıran işlem kimliği yok. Açılışta kayıtlı alanlar gerçek MapLibre bölgeleriyle uzlaştırılmıyor. Silme hatasında bile alan listeden kaldırılıyor.

[MapLibre belgesi](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region/-offline-region-observer/on-error.html) hataların geçici olabileceğini ve tekrar denenebileceğini açıklıyor. Sabitlenen 11.13.5 sürümündeki davranışı ayrıca doğrula. Eski callback'lerin yeni işlem durumuna karışması cihazda yeniden üretilmemiş risktir.

- Kalıcı bölge/işlem kimliği, tek seferlik tamamlanma, iptal/devam/tekrar deneme ve eski callback reddi ekle.
- Açılışta tamamlanmış/yarım/bozuk bölgeleri uzlaştır; silme hatasını göster, kalan kaynakları temizle. Gerçek disk kullanımı ve düşük depolama davranışını doğrula.
- **Kabul:** İnternet kesilmesi, gündüz tamam/gece yarım, süreç ölümü, tekrar denemede eski callback, silme hatası ve yeniden açılış. Uçak modunda indirilmiş bölgede iki stil çizilir, GPS/hız çalışır; alan dışında uyarı görünür.
- **Kaynak:** [OfflineMaps.kt](../app/src/main/java/com/ridedeck/app/data/OfflineMaps.kt), [OfflineMapsCard.kt](../app/src/main/java/com/ridedeck/app/ui/OfflineMapsCard.kt).

## 5. Sesli anonslara öncelik ve erteleme ekle — kodda tamamlandı 2026-11-10 (`SpeechPriority`, `SpeechSession` kuyruğu; saha doğrulaması N12)

**Kodda görülen davranış:** Navigasyon, mesaj, hava ve ısı aynı `Speaker` üzerinden gider. `SpeechSession.announce()` önceki konuşmayı durdurur; TTS `QUEUE_FLUSH` kullanır. Mesaj dönüş talimatını kesebilir; anonsun söylenmiş olarak işaretlenmesi yarıda kalan talimatın tekrarını garanti etmez.

- SOS/yakın dönüş önceliğini tanımla; mesajları uygun aralığa ertele, eski/geçersiz anonsları düşür. Eşzamanlı hava uyarılarını birleştir.
- **Kabul:** Dönüş sırasında mesaj, eşzamanlı yağmur/buz/rüzgâr, çağrı, audio-focus kaybı, kask kopması, SOS ve eski TTS callback'i. Kritik talimat düşük öncelikle kesilmesin; gecikmiş dönüş sonradan okunmasın.
- **Kaynak:** [SpeechSession.kt](../app/src/main/java/com/ridedeck/app/data/SpeechSession.kt), [Voice.kt](../app/src/main/java/com/ridedeck/app/data/Voice.kt), [NavigationController.kt](../app/src/main/java/com/ridedeck/app/data/NavigationController.kt).

## 6. Nominatim erişimini merkezileştir, sağlayıcı stratejisini belirle — yüksek öncelik

**Kodda görülen eksik:** Bazı arama tekrarlarında 1 sn bekleme var; durak türü sorguları, reverse ve yer araması ortak sınırlayıcı kullanmıyor. Endpoint'ler sabit.

[Nominatim politikası](https://operations.osmfoundation.org/policies/nominatim/) tüm kullanıcıların toplamı için saniyede en fazla bir istek, tanımlayıcı User-Agent/atıf ve yazılım güncellemesi gerektirmeden servis değiştirmeyi istiyor. Arama-tamamlama yapılmaması korunmalı. Cihaz başına kuyruk uygulama genelindeki sınırı tek başına çözmez.

- Ortak kuyruk, önbellek ve eşdeğer istek birleştirme; iptal/429/hata davranışı ekle.
- Kullanıcı sayısı büyürken anahtarsız/ücretsiz ilkeyle uyumlu sağlayıcı ve çalışma anında değiştirme stratejisini kararlaştır. Yeni sunucu/ücret onaylanmış sayılmaz.
- **Kabul:** Arama, reverse ve çok türde durak sorguları eşzamanlıyken sınır korunur; tekrarlar önbellekten yararlanır; sağlayıcı değişimi sınanır.
- **Kaynak:** [Fuel.kt](../app/src/main/java/com/ridedeck/app/data/Fuel.kt), [RouteRepository.kt](../app/src/main/java/com/ridedeck/app/data/RouteRepository.kt), [Http.kt](../app/src/main/java/com/ridedeck/app/data/Http.kt).

## 7. Süreç ölümü sonrası kalan yolculuğu koru — orta öncelik

**Kodda görülen eksik:** `ActiveTrip` hedef/final hedef/zaman saklıyor; ara duraklar ve geçilen durak sayısı yok. Geri önerilen rota kalan durakları kaybediyor.

- Kalan durakları, sıralarını ve gerekli tercihleri sürümlü kalıcı kayda taşı; eski kayıtlarla uyumluluğu koru.
- **Kabul:** Birkaç duraktan sonra süreç ölümü; yalnız kalanların doğru sırayla önizlenmesi; bozuk/eski kayıt, süre aşımı ve iptalde temizleme. Kendiliğinden rehberlik/servis başlatma yok.
- **Kaynak:** [ActiveTrip.kt](../app/src/main/java/com/ridedeck/app/data/ActiveTrip.kt), [RideNavigation.kt](../app/src/main/java/com/ridedeck/app/data/RideNavigation.kt), [ActiveTripTest.kt](../app/src/test/java/com/ridedeck/app/ActiveTripTest.kt).

## 8. Gerçek internet erişimini ve servis hatalarını göster — orta öncelik

**Kodda görülen tutarsızlık:** `rememberOnline()` ağ bulunduğunda true atıyor; `NET_CAPABILITY_VALIDATED` ve capability değişimlerini izlemiyor. İnternetsiz Wi-Fi'de çevrimdışı alan uyarısı gizlenebilir. [Android rehberi](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) `INTERNET` ile doğrulanmış erişimi ayırıyor.

- Doğrulanmış ağı izle; harita/routing servis hatalarını cihazın bağlantı durumundan ayır.
- **Kabul:** İnternetsiz Wi-Fi, giriş sayfalı ağ, Wi-Fi/mobil geçişi, uçak modu ve yalnız bir sağlayıcının erişilememesi. Uyarılar kaybolmasın veya yanlış güven vermesin.
- **Kaynak:** [OfflineMapsCard.kt](../app/src/main/java/com/ridedeck/app/ui/OfflineMapsCard.kt), [MapScreen.kt](../app/src/main/java/com/ridedeck/app/ui/MapScreen.kt).

## 9. Hız limiti ve hava verisinin tazeliğini yönet — orta öncelik

**Kodda görülen eksikler:** Limit yalnız yeni fix sırasında eskir; GPS tamamen kesilirse eski tabela kalabilir. Bekleyen sorgu navigasyon kapandıktan sonra limiti doldurabilir. Hava hatasında eski tahmin korunur; modelde kullanıcıya gösterilecek güncellik zamanı yok.

- Saatle eskime, veri zamanı, istek nesli ve geç cevap reddi ekle; eski bilgiyi gizle veya işaretle.
- **Kabul:** Yeni fix gelmeden süre aşımı, navigasyon kapalıyken geç cevap, yol/profil değişimi ve art arda hava hataları. Eski bilgi güncel gibi sunulmasın.
- **Kaynak:** [SpeedLimit.kt](../app/src/main/java/com/ridedeck/app/data/SpeedLimit.kt), [Weather.kt](../app/src/main/java/com/ridedeck/app/data/Weather.kt).

## 10. Overpass yarışında iptal ve gecikmeyi ölç — orta öncelik

**Doğrulanacak performans riski:** `firstAnswerOf()` ilk başarıda diğer coroutine'leri iptal ediyor; bloklayan `HttpURLConnection` iptalle doğrudan kesilmiyor. Yapılandırılmış kapsam yavaş çocuk işleri bekleyebilir. Gerçek gecikme ölçülmedi.

- Kontrollü hızlı/yavaş/hatalı uçlarla ölç; bağlantıyı gerçekten iptal eden çözüm seç, gereksiz işleri sonlandır.
- **Kabul:** Bir hızlı/iki takılan cevapta sonuç yavaşların timeout'unu beklemeden alınır; arama kapatılınca işleri sonlanır; tüm kaynaklar başarısızsa anlaşılır hata gelir.
- **Kaynak:** [Fuel.kt](../app/src/main/java/com/ridedeck/app/data/Fuel.kt), [Http.kt](../app/src/main/java/com/ridedeck/app/data/Http.kt).

## 11. Tam ekran erişimi kapalı gelen çağrıyı sağlamlaştır — orta öncelik

**Kodda görülen eksik / cihaz doğrulaması gerekli:** Bildirimde `setFullScreenIntent` var; normal dokunuş için `contentIntent` yok, tam ekran erişimi kontrol edilmiyor. Android 14+ kullanıcı erişimi kapatabilir ([resmî açıklama](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications)).

- Normal bildirimden çağrı ekranına erişim ve cevapla/reddet yollarını tamamla; izinleri ve varsayılan dialer rolünü dikkate al.
- **Kabul:** Tam ekran açık/kapalı, ekran kilitli/açık, bildirim izni açık/kapalı, dialer rolü var/yok; bildirim temizliği ve gerçek ses yolu doğrulanır.
- **Kaynak:** [MotoInCallService.kt](../app/src/main/java/com/ridedeck/app/service/MotoInCallService.kt), [CallRepository.kt](../app/src/main/java/com/ridedeck/app/data/CallRepository.kt).

## 12. Belgeleri, sürümü ve dağıtım kanıtını uzlaştır — orta öncelik

**İnceleme tabanındaki çelişkiler:** README çevrimdışı harita olmadığını, süreç ölümünden sonra hedefin geri yüklenmediğini ve Türkçe özet tabloda konuşma tanıma yapıldığını söylüyor. Güncel kodla uyuşmuyor. Eski plan hem çevrimdışı haritayı yapılmamış hem bekleyen iş olmadığını gösteriyordu; bu dosya o plan çelişkisini giderir.

Son yayın v0.4.0 (2026-10-22); inceleme tabanına kadar 51 yeni commit var, sürüm o tarihte `0.4.0 / 4` idi.

**2026-11-05:** README çelişkileri giderildi (çevrimdışı harita, süreç ölümü sonrası hedef önerisi, konuşma tanıma) ve sürüm `0.5.0 / 5` yapıldı. Beta 0.5.0 GitHub'da yayınlandı (v0.5.0, `RideDeck-0.5.0.apk`, 2026-11-05); saha kanıtı hâlâ açık.

- README TR/EN, STATUS, EKSIKLER ve yayın hazırlığı belgelerinde mevcut/bekleyen/sahada doğrulanmayan ayrımını koru; tarihsel kayıtları güncel durum gibi sunma.
- Sonraki beta için farklı `versionName/versionCode`, kaynak commit'i ve test kapsamı belirle. APK yayını/etiket oluşturma ayrıca açık istek gerektirir.
- **Kabul:** İndirilen APK, belge ve hata bildirimindeki sürüm ayırt edilir; yeni özellikler doğrulanmış gibi sunulmaz.
- **Kaynak:** [README](../README.md), [STATUS](STATUS.md), [EKSIKLER](EKSIKLER.md), [RELEASE_READINESS](RELEASE_READINESS.md), [app/build.gradle.kts](../app/build.gradle.kts).

## Saha ve yayın kabul matrisi — aşama 3

Sonuçlarda uygulama sürümü/commit'i, cihaz, Android sürümü, koşullar ve PASS/FAIL/UNVERIFIED kaydedilsin. Sentetik GPS, sahte SMS gateway'i, emülatör ve fiziksel cihaz kanıtları ayrı tutulsun.

| Alan | Senaryolar |
|---|---|
| Navigasyon/GNSS | Soğuk başlangıç, gerçek hızlanma/fren, tünel/sinyal geri gelişi, paralel yollar, sapma, ara durak, yüksek hızda anons zamanı. |
| Yaşam döngüsü | Ekran kilidi, Activity yeniden açılışı, süreç ölümü, Doze/OEM, bildirimden Bitir, izin kaybı; geç cevap yolculuğu diriltmesin. |
| Çevrimdışı harita | Gerçek boyut/süre/disk, iki stil, yarım indirme/kurtarma, uçak modunda çizim, alan dışı uyarı. |
| Ses/interkom | Müzik/navigasyon/çağrı, mikrofon mute, ses yolu değişimi, kask kopması/yeniden bağlanması; istenmeyen müzik devamı olmasın. |
| SOS/SMS | Kontrollü sensör senaryoları/yanlış alarm; kendi test numarasına açıkça yetkilendirilmiş SMS, varsayılan/çoklu SIM, kişi/parça sonuçları. Gerçek kaza oluşturulmaz; gönderim teslim garantisi değildir. |
| Pil/ısı/performans | Uzun kullanımda pil/sıcaklık, okunurluk, mini/tam harita, düşük bellek, UI takılması ve çok saatlik kayıt. Sayısal iddialar ölçüme dayansın. |
| Platform/release | Desteklenen Android sürümleri (özellikle 8–10 ve 14+), R8 fonksiyon testi, izinler, uygun cihazda 16 KB runtime. Statik hizalama runtime kanıtı değildir. |

## 13. Sürüşe hazır mı? özeti — ürün önerisi

GPS, çevrimdışı alan, cihazdaki TTS sesi, bildirim erişimi ve isteğe bağlı SOS yapılandırmasını tek yerde göster; eksikler sürüş başlamadan anlaşılsın.

- **Kabul:** Gerçek izin/servis durumları değişince yenilenir ve düzeltme yoluna götürür. İsteğe bağlı özellik kapalıyken sürüş engellenmez. Hazır göstergesi SMS teslimi/güvenlik garantisi vermez.
- **Bağımlılık:** #2–4, #8–9 ve ilgili cihaz doğrulamaları.

## 14. Benzinlikte gerçek sapma maliyeti — ürün önerisi

Mevcut kuş uçuşu mesafeye ek olarak rotaya eklenecek süre/mesafeyi (ör. +4 dk / +1,8 km) göster; karşı şerit veya zor erişilen durakların seçimi kolaylaşsın.

- **Kabul:** Kalan duraklı rota esas alınır; kuş uçuşu/gerçek rota ayrılır; hesaplanamayan maliyet uydurulmaz. Her satıra sınırsız sorgu gönderilmez; sağlayıcı yükü/önbellek tasarlanır.
- **Bağımlılık:** #1, #6, #10; [Fuel.kt](../app/src/main/java/com/ridedeck/app/data/Fuel.kt), [MapScreen.kt](../app/src/main/java/com/ridedeck/app/ui/MapScreen.kt).

## 15. Seçili yolculuğu çevrimdışı koru — ürün önerisi

Rota geometrisi, kalan duraklar, tercihler ve gerekli harita alanını birlikte sakla; bağlantı kesilince mevcut rehberliği koru. Yeni çevrimdışı rota hesabı/arama ayrı ürün kararıdır.

- **Kabul:** Paket kapsamı/boyutu ve eksik alanlar anlaşılır; süreç ölümü sonrası kalan rota kurtarılır; çevrimdışı sapmada yeniden hesaplama sınırı gösterilir. Cihazdaki TTS sesinin uygunluğu doğrulanır. Saklama/temizleme/gizlilik davranışı tanımlanır.
- **Bağımlılık:** #1, #3–4, #7–9. Bölge/koridor indirmesi sağlayıcı koşullarına uysun.

## 16. Ana ekran düzeni ve Görünüm sayfası — ürün önerisi (2026-11-11)

Gün batımı kartı ve Ayarlar > Görünüm'deki kart seçimi kaldırıldı. Sıradaki adım ana ekranın sağ sütununu ve Görünüm sayfasını yeniden kurmak; ayrıntılar kullanıcıyla netleşmeden kod değişikliği yapılmaz.

- **Sabit iskelet:** Hız kartı solda sabit kalır; sağda tek sütun. Sütunun ne göstereceği (harita + müzik, seçili uygulamalar veya tek büyük kart) ürün kararıdır; iki ayrı "üst kart / alt kart" ayarı geri getirilmez.
- **Görünüm sayfası:** Yalnız tema (gündüz/gece/otomatik) kalır. Kart yoğunluğu, köşe yuvarlaklığı veya yazı boyutu gibi yeni seçenekler yalnız gerçek ihtiyaç doğarsa ve tek ekranda toplanır; sürüş sırasında ayarlar yine kilitlidir.
- **Uygulama kartı:** Sabitlenmiş uygulamaların yeri yeniden tanımlanır (dock kısayolu veya tek kart). Yeni uygulama ekleme bu kapsamda değildir.
- **Tema:** Gündüz/gece teması ortam ışığıyla otomatik çalışmaya devam eder; bu, kaldırılan gün batımı kartından bağımsızdır.
- **Kabul:** Sağ sütun dar/geniş ekranda ve 180° döndürmede taşmaz; kart yoksa hız kartı tüm alanı kullanır; TR/EN metinleri ve `DashLayout` testleri yeni düzene göre güncellenir; emülatörde iki temada görülür.
- **Bağımlılık:** [DashboardScreen.kt](../app/src/main/java/com/ridedeck/app/ui/DashboardScreen.kt), [SettingsScreen.kt](../app/src/main/java/com/ridedeck/app/ui/SettingsScreen.kt), [DashLayout.kt](../app/src/main/java/com/ridedeck/app/data/DashLayout.kt).

## N. Kusursuz navigasyon — saha kapanışı (N1–N11 kodda tamamlandı, bkz. STATUS 2026-11-10)

Kaynak: `NavigationController.kt`, `GuidanceMath.kt`, `RouteSnap.kt`, `RouteRepository.kt`, `MapScreen.kt`, `Voice.kt`/`SpeechSession.kt`. İnceleme kod okumasıdır; cihazda yeniden üretilmedi. "Kusursuz" ölçütü: rotayı hiç bozmayan, hiçbir anonsu yutmayan, yanlış yerde yeniden hesaplamayan ve sürücüye asla "ne kadar sonra" sorusunu bıraktırmayan rehberlik.












### N12. Saha doğrulama matrisi (navigasyon) — kapanış ölçütü
Hiçbir N maddesi cihazda denenmeden kapanmaz. En az: şehir içi 30 dk (dönüş yoğun), çevre yolu 20 dk (çıkış anonsları, 90+ km/sa), 1 tünel, 1 kavşak dönüş (roundabout), 1 bilerek rotadan çıkma, 1 uçak modu kesintisi, 1 iki duraklı yolculuk + benzinlik sapması, 1 görüşme sırasında dönüş. Her sürüşte GPS logu (`RideDeckSpeed` etiketi) ve anons zaman damgaları kaydedilir; `docs/STATUS.md`'ye tarih ve cihazla işlenir.

## Korunan kararlar ve kapsam sınırları

- Anahtarsız/ücretsiz servisler, sürüşte yazı girişi yasağı ve eldivenle kullanım korunur. Yeni dokunma hedefleri ve TR/EN metinler proje kurallarını izler.
- Sesli asistan/STT/uyandırma, GPX, bakım takibi, OBD ve otomatik park kaydı geçmişte kaldırıldı; bu plan geri getirmez.
- Canlı trafik, 3D bina, viraj ağırlıklı rota, gidon kumandası, TPMS/yakıt menzili ve grup/canlı konum paylaşımı mevcut kapsam dışı kararlarıdır.
- Şerit okları: 2026-11-02 kaydında üç rotadaki 51 adımın altısında veri vardı; kapsam artmadan bant planlanmıyor.
- Kaza sensörü ve SOS 2026-11-09'dan beri uygulama ömürlü `RideSafety` içindedir: ekran görünürken veya başlatılmış yolculuk sürerken çalışır; başlatılmamış yolculukta ekran kapanınca durur. Bunun ötesinde (yolculuk başlatılmadan arka plan algılama) ayrı karardır.
- Yeni sağlayıcı, sunucu, ücret, cihazda SMS/arama, kurulum, etiket ve yayın bu plan kaydıyla yetkilendirilmiş sayılmaz.
