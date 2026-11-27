# RideDeck teknik vaka analizi

Bu belge RideDeck'in hangi problemi çözdüğünü, nasıl kurulduğunu ve hangi teknik kararların neden verildiğini anlatır. Rakamlar ve davranışlar koddan ve CI'dan alınmıştır; **sahada doğrulanmayan her şey ayrıca işaretlidir** (bkz. [Doğrulama durumu](#doğrulama-durumu)). Paket haritası ve veri akışı için [ARCHITECTURE.md](ARCHITECTURE.md), tarihli kanıt için [STATUS.md](STATUS.md).

## 1. Problem

Otomobil, minibüs veya başka bir araçta telefon ya da tabletle sürüş üç sorunla gelir: ekran montajda hızlı bakışta kullanılmaz, hız ve navigasyon ayrı uygulamalardadır, yazı girişi sürüş güvenliğini bozar. Hazır çözümler (Android Auto, CarPlay) uyumlu bir araç ünitesi ister; eski araçlarda, motosikletlerde veya bağımsız tablet kurulumlarında bu her zaman yoktur.

RideDeck, uyumlu bir Android telefonu, tableti veya bağımsız head unit'i yatay, büyük hedefli bir araç içi sürüş paneline çevirir: hız, harita, müzik ve telefon tek ekranda. CarPlay ve Android Auto'dan ilham alır; ancak onlarla entegre değildir ve projeksiyon yapmaz. Hedefler:

- Hesap, API anahtarı ve kendi sunucusu olmayacak; veri telefonda kalacak.
- Sürerken yazı girişi olmayacak; her şey büyük, yüksek kontrastlı hedeflerle veya sesle yapılacak.
- Mantık Android'den ayrılabilir olacak ki birim testiyle sınanabilsin.
- Tek genel araç profili (`VehicleProfile.AUTO`) yeterli olacak; araç seçici veya araca göre dallanan davranış olmayacak.

## 2. Mimari

Tek modüllü Android uygulaması (Kotlin, Jetpack Compose, minSdk 26). Tek `MainActivity` tek bir Compose ağacı barındırır; navigasyon kütüphanesi yoktur, `DashboardScreen` yerel durumla pano, harita, ayarlar ve arama ekranı arasında geçer. `RideDeckApplication` süreç ömründeki `RideTracking` (paylaşılan GPS), `RideNavigation` (rota/TTS) ve `RideSafety` (kaza/SOS) sahiplerini kurar; `DashboardViewModel` yalnız ekran durumunu bağlar.

- **Saf mantık + ince Android kabuğu.** `ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, `PhoneMath` gibi çekirdekler Android bağımlılığı taşımaz; zaman ve sensör verisi dışarıdan verilir.
- **Elle bağımlılık enjeksiyonu ve tek ağ geçidi.** `NavigationController` bağımlılıklarını kurucudan alır; Nominatim, Valhalla, Open-Meteo ve Overpass çağrıları `data/Http.kt`/`ProviderHttp` üzerinden geçer. MapLibre karo istekleri harita motorundadır. Bu sınır yalnız uygulamanın trafiğini şekillendirir; sağlayıcının toplam kotasını kanıtlamaz.
- **Ömür.** `RideNavigation` uygulama ömründedir; Activity/ViewModel yeniden oluşması rehberliği, TTS'yi veya açık yolculuğu kapatmaz. Süreç ölümü/force-stop sonrasında rota kendiliğinden devam etmez, yalnız önizleme olarak geri gelir.

Ayrıntılı paket haritası ve veri akışı: [ARCHITECTURE.md](ARCHITECTURE.md).

## 3. GPS filtreleme

Telefon GPS'i saniyede bir hız verir ve bu veri gürültülüdür; ham değeri göstermek kadranı titretir, düz bir filtre ise gerçek sert freni geç gösterir. `SpeedFilter` bu iki ucu dengeler:

- **Düşük kazançlı alpha-beta filtresi.** Durum hız ve yavaş öğrenilen ivmedir. Ölçüm gürültü bandı (`3σ` veya en az 1,5 m/s) içindeyse tahmini yalnızca hafifçe iter; bandın dışındaysa gerçek değişim sayar ve ona uyar.
- **Aykırı değer kapısı.** Aracın fiziksel olarak yapamayacağı sıçrama (> 15 m/s²) ya da çip kötü doğruluk bildirirken > 3 m/s'lik sıçrama **bir kez** tutulur; arka arkaya ikincisi gerçek kabul edilir. Fizikle açıklanabilir bir düşüş (≤ 9 m/s², sert fren) tutulurken tahmini %70 çeker, yani gerçek fren bir fix geç kalmaz.
- **Durma histerezisi.** Durmuş saymak için iki yavaş fix, tekrar hareket için belirgin biçimde yüksek hız gerekir; kırmızı ışıkta 0/1/0 titremesi olmaz.
- **Sahte sıfır yok.** Hareket halindeyken 3 sn fix gelmezse hız "bilinmiyor" olur (`--`, "GPS sinyali kayıp"). Tünelde 0 göstermek yanıltıcıdır; bu bilinçli bir karardır.
- **Gösterim ve güvenlik ayrı.** Süzülmüş hız yalnızca kadran için kullanılır. Kaza algılama, yolculuk kaydı ve hız limiti uyarısı **ham** hızı kullanır; filtre bu kararları geciktirmesin diye.
- `PositionSmoother` haritadaki imleci fix'ler arasında (ölü hesap) kare kare yumuşatır; kamera ve imleç 1 Hz'e bağlı kalmaz.

Testler: `SpeedFilterTest`, `PositionSmootherTest`, `GaugeScaleTest`.

## 4. Navigasyon

- **Arama:** Nominatim, yalnızca açıkça "Ara"ya basınca (kullanım politikası yazarken-ara'yı yasaklar); merkezi sınır en çok 1 istek/sn tutar. Sonuç çıkmazsa `PlaceQuery` düzeltilmiş yazımları (Türkçe harf varyantları, eksik/yer değiştirmiş harf, il/ilçe adlarına bulanık eşleşme) saniyede en çok bir istekle dener; yine yoksa "Bunu mu demek istedin?" der ve **onaysız rotaya girmez**.
- **Rota:** Valhalla, her zaman `auto` costing ile; talimat uygulama diline göre Türkçe veya İngilizce. Otoyolsuz ve ücretsiz yol seçenekleri `costing_options`'a çevrilir. Araç seçici yoktur; tek genel `VehicleProfile.AUTO` kullanılır.
- **Konum eşleme:** her fix, son ilerleme noktasından başlayıp GPS boşluğuna ve hıza göre genişleyen (en az **80 segment**) pencerede en yakın segmente izdüşürülür (`Geo.project`). Rotadan uzaklık hız/doğruluğa göre 45–150 m arasında ölçülür; art arda 3–4 fix sürerse yeniden hesaplama başlar (10 sn yeniden deneme aralığı, "rota yeniden hesaplanıyor" yalnızca bir kez söylenir). 3 taze fix'te yön uyuşmazlığı **yanlış yön/U dönüşü** uyarısını tetikler; geriye sıçrayan eşleşmeler kabul edilmez.
- **Anons zamanlaması:** hıza göre ölçeklenir: uzak uyarı ~45 sn (400 m–2 km), tam talimat ~11 sn (100–450 m), kısa "şimdi" ~3 sn (20–60 m); her anons bir kez söylenir. Varış rota sonu ≤25 m, hedefte durma veya kapalı otoparkta GPS kaybıyla; erken varışa karşı kalan mesafe koşulu vardır.
- **İptal ve eski cevaplar:** her rota/arama isteği bir `generation` taşır; kullanıcı vazgeçtiyse veya yeni istek açıldıysa geç gelen cevap yok sayılır. Bekleyen rota/alternatif/devam işleri sahiplik ve generation ile iptal edilir; konum izni kaybı ve GPS kapanması ayrı nedenlerle (`NavigationTermination`) sonlandırır.
- **Ara durak:** benzinlik kendi başına bir rota olarak hesaplanır, asıl hedef `finalDestination`'da tutulur; varınca "Yola devam" eski hedefe döner. Şarj/tamir/su durağı türleri kaldırıldı.
- **Yakıt arama dayanıklılığı:** genel Overpass sunucuları kararsızdır (500, 30 sn cevap). Sorgu en çok **iki farklı operatöre** (FOSSGIS ve kumi/private.coffee) paralel atılır; aynı operatöre iki bağlantı açılmaz, ilk iyi cevap alınır, olmazsa Nominatim koridoruna düşülür.
- **Tazelik ve sağlayıcı bütçesi:** taze rota 10 dakikadan eskiyse veya GPS rota başlangıcının ilk 500 m koridorunda değilse **Başlat** kilitlidir; geri yüklenen önbellek önizlemesinde sınır ilk 50 m dilimidir. Hava durumu ilk veri gelene kadar üstel geri çekilir; hız limiti hata sonrası bekler ve 60 sn'den eski ölçümü göstermez.

Testler: `NavigationControllerTest`, `GeoTest`, `PlaceQueryTest`, `FuelTest`, `RouteOptionsTest`, `RouteStartGuard`/`GuidanceMath`, geometri ve detour testleri.

## 5. Sesli asistan neden yok

Uygulamada bir dönem Türkçe sesli asistan vardı (komut ayrıştırıcı, çok turlu diyalog, araç içi mikrofon, "Hey RideDeck" uyandırma denemeleri). 2026-10-28'de tamamen kaldırıldı: Android'in `SpeechRecognizer`'ı sürekli dinleme için tasarlanmadığı için uyandırma güvenilir çalışmadı, yerel uyandırma modeli de gerçek sesle yeterince tutarlı sonuç vermedi. Çalışmayan özelliği göstermek yerine kaldırmak tercih edildi. Sesli yönlendirme (sıradaki dönüş anonsu, hava ve hız uyarıları, mesaj okuma) Android TTS ile devam ediyor.

## 6. Güvenlik özellikleri (SOS) ve sınırı

Kaza algılama (`CrashDetector`) şu mantıkla çalışır: yakın zamanda sürüş hızı (≥ 30 km/sa) **ve** 5,0 g eşiğini aşan darbe, ardından telefonun sakin kalması ve aracın durması. Genel araç profilinde `crashNeedsTilt = false` olduğu için telefonun yön değiştirmesi aranmaz; çukur (darbe var, araç hareket ediyor), sert fren (darbe yok) ve park halindeki araç sessiz kalır. Sonrasında geri sayım, geri sayım bitince en fazla 3 kişiye birer SMS (konum bağlantılı); arama yapılmaz.

**Bu bir güvenlik garantisi değildir.** Genel araç eşiği (5,0 g) ve 30 km/sa hareket eşiği tasarım varsayımıdır ve **sahada ayarlanmamıştır**; gerçek bir kazada test edilmedi. Özellik varsayılan kapalıdır. Sensör, uygulama ekrandayken veya başlatılmış bir yolculuğun ön plan servisi sürerken dinler. Ekran kapalıyken başlayan geri sayım kilit ekranı üstünde bildirim, tam ekran bildirim ve uyandırma kilidiyle öne alınmaya çalışılır; bildirimde **İyiyim** (geri sayım) / **Kapat** (sonuç) eylemleri vardır. FSI reddedilirse yüksek öncelikli bildirim kalır, ses/titreşim ve SMS yolu sürer. Gerçek kilitli cihaz, OEM FSI reddi, SIM/operatör teslimi **doğrulanmadı**; operatör SMS ücreti uygulanabilir. Yanlış alarm ve kaçırılmış kaza ikisi de mümkündür. Bu sınırlar uygulamada ve mağaza metninde bu şekilde sunulur.

## 7. Testler ve CI

- **Son yerel JVM koşusu: debug 865/865 PASS** (117 test dosyası, 0 hata, 0 atlama); `3af1c18` ile `DrivingLogicTest` (+14 test) eklendi. **v0.7.1 yayın snapshot'ında** debug 848/848 + release 848/848 (toplam 1.696) doğrulanmıştı; release bu son yerel koşuda yeniden çalıştırılmadı. Önceki tarihli sayılar tarihsel kayıttır.
- **Lint:** iki varyantta **0 hata** (debug 105 / release 104 uyarı; bastırılmadı).
- Test edilenler: GPS filtresi, konum eşleme ve rota mantığı, yazım toleransı, SOS/kaza algılama ve SMS sonuç modeli, ısınma koruması, telefon/klavye matematiği, birimler, yolculuk kaydı, SQLite geçişi/bozulma yedeği, sağlayıcı sınırlama/geri çekilme, paylaşılan GPS sahipliği ve sürüş mantığı.
- **GitHub Actions** (`.github/workflows/ci.yml`): `test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, release APK hizalama, AAB varlığı, debug x86_64/ARM ABI, release ARM-only ve koşullu imza kontrolü. Ayrı iş, x86_64 API 35 emülatöründe instrumentation çalıştırır; cached-app freezer kapatılır.
- **Instrumentation:** toplam **209 test**; son tam yerel koşuda **208 PASS + 1 atlandı**, atlanan izin testi ayrı **1/1 PASS** (toplam 209). `RideDeckTestRunner` sonuçtan sonra test sürecini kapatır.
- Test edilmeyen: gerçek sensör/GPS/ağ davranışı, fiziksel kilit ekranı/Doze/OEM, gerçek SMS/arama/Bluetooth ses (eller serbest) ve 16 KB runtime. Bunlar emülatör/sentetik testlerle eşdeğer sayılmaz.

## 8. Teknik kararlar ve gerekçeleri

| Karar | Gerekçe |
|---|---|
| Ham `LocationManager`, Play Services yok | Bağımlılık ve hesap/anahtar yok; açık kaynak ve F-Droid benzeri kurulumla uyumlu. Bedeli: kendi filtre ve eşleme kodunu yazmak. |
| Tek süreç GPS akışı + referans sayımlı `LocationArbiter` | Ekran, açık yolculuk servisi ve overlay aynı fix'leri paylaşır; ikinci abonelik açılmaz ve bir sahibin ayrılması diğerlerinin GPS'ini kesmez. |
| Tüm servisler anahtarsız genel servis + merkezi `ProviderHttp` | "Hesap, anahtar, sunucu yok" sözünü korur. Bedeli: servis kararsızlığı için yedek/geri çekilme; uygulama içi sınır sağlayıcı global kotasını kanıtlamaz. |
| Gösterim hızı ≠ karar hızı | Titremeyen kadran ile gecikmeyen güvenlik kararı birbirine feda edilmedi. |
| Sahte 0 yok, `--` var | Sinyal kaybında yanlış güven vermemek. |
| Sürerken yazı girişi/ayar kilidi (5 km/sa ve üzeri) | Güvenlik; kendi Türkçe klavyesi park halinde arama içindir. |
| Sesli asistan kaldırıldı | Güvenilir uyandırma ve tanıma sağlanamadı; çalışmayan özelliği göstermek yerine kaldırıldı. |
| Çevrimdışı rota yok; harita indirmeleri sağlayıcı kapısında | Rota ve arama canlı hizmete bağlı kalır. Yeni indirme/resume, OpenFreeMap koşulları ve global uyum kanıtlanana kadar **BLOCKED**; mevcut alanlar korunur. |
| OBD, bakım takibi, eğim, GPX çıkarıldı | Sürerken kullanılamayan veya doğrulanamayan özelliklerden kaçınmak; odak sürüş panelinde. |

## Gerçek cihaz doğrulamaları

`docs/STATUS.md` kayıtlarına göre debug ve R8'li release APK'ları geçmişte bir fiziksel Android telefona kurulup denenmişti. Bu çalışmada telefon bağlı olmadığı için **yeni bir cihaz testi yapılmadı**. Depoda tanıtım ekran görüntüsü, demo GIF, mağaza görseli veya video tutulmaz; gerçek cihaz kaydı için senaryo ve komutlar [DEVELOPMENT.md](DEVELOPMENT.md#gerçek-cihaz-demo-kaydı) içindedir.

## Doğrulama durumu

**Doğrulandı (otomatik):** son yerel debug JVM koşusunda 865/865 test (117 dosya), `v0.7.1` snapshot'ında debug + release 848/848 (toplam 1.696), iki lint (0 hata), debug ve R8 release derlemesi, AAB, 16 KB statik hizalama ve CI yapılandırması. **Instrumentation:** 209 test; son tam koşuda 208 PASS + 1 atlandı, atlanan izin testi ayrı 1/1 PASS (toplam 209). **Emülatörde görüldü:** ana ekranlar, navigasyon, benzinlik, SOS geri sayımı, farklı yazı boyutları. Tek genel araç profili vardır; araç seçici yoktur.

**Sahada denenmedi:** kaza algılama eşikleri, gerçek SMS gönderimi ve SIM/operatör teslimi, fiziksel kilit ekranı/Doze/OEM, varsayılan telefon uygulamasıyken arama ses yolu ve Bluetooth ses/eller serbest, araç içi montajda ısınma ve mini haritanın pil etkisi, Android 8–10 davranışı, 16 KB runtime, mağaza/Data safety ve sağlayıcı global kota uyumu. Güncel liste: [STATUS.md](STATUS.md) ve [RELEASE_READINESS.md](RELEASE_READINESS.md). Genel 1.0 kararı **NO-GO**.

## English summary

RideDeck is a standalone Android in-vehicle dashboard (Kotlin/Compose, minSdk 26) inspired by CarPlay and Android Auto but not integrated with either. Process-scoped `RideTracking`, `RideNavigation` and `RideSafety` own shared GPS, guidance and crash detection; `DashboardViewModel` only binds screen state.

- **Filtering:** `SpeedFilter` smooths the 1 Hz GPS speed for the gauge only; crash detection, trip recording and limit warnings use the raw speed, and a lost signal shows `--`, never a fake 0.
- **Navigation:** explicit Nominatim search, Valhalla `auto` routing, 80+ segment map matching, speed-scaled announcements, generation-based cancellation, fuel stops as their own route, and online-only routing/search.
- **SOS:** 5.0 g impact plus a 30 km/h moving threshold, no tilt requirement, up to three SMS messages, no call. Thresholds are design assumptions and **not tuned in the field**; this is an aid, not a safety guarantee.
- **Network:** public calls go through `data/Http.kt`/`ProviderHttp`; MapLibre tiles stay in the map engine, and app-local throttling does not prove aggregate provider compliance.
- **Tests:** last local debug JVM run **865/865 pass** (117 files); the v0.7.1 snapshot recorded 848/848 debug and 848/848 release (1,696 total). Instrumentation has **209 tests** (208 pass + 1 skipped in one full run; the skipped permission test passed separately 1/1).
- **Not verified:** real device/driving/GPS, real SMS/SIM/operator delivery, physical keyguard/Doze/OEM, Bluetooth hands-free audio, heat/battery in a mount, Android 8–10, 16 KB runtime, store/Data safety and global provider quotas. The 1.0 decision remains **NO-GO**; see [STATUS.md](STATUS.md) and [RELEASE_READINESS.md](RELEASE_READINESS.md).
