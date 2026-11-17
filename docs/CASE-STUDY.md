# RideDeck teknik vaka analizi

🇬🇧 [English version](CASE-STUDY.en.md)

Bu belge RideDeck'in hangi problemi çözdüğünü, nasıl kurulduğunu ve hangi teknik kararların neden verildiğini anlatır. Rakamlar ve davranışlar koddan ve CI'dan alınmıştır; **sahada doğrulanmayan her şey ayrıca işaretlidir** (bkz. [Doğrulama durumu](#doğrulama-durumu)).

## 1. Problem

Motosiklet sürerken telefon üç sorunla gelir: ekran küçük ve eldivenle kullanılmaz, hız ve navigasyon ayrı uygulamalardadır, yazı girişi sürüş güvenliğini bozar. Hazır çözümler (Android Auto, CarPlay) araç ekranı ister ve motosiklette yoktur.

RideDeck telefonun kendisini yatay, büyük hedefli bir sürüş paneline çevirir: hız, harita, müzik ve telefon tek ekranda. Hedefler:

- Hesap, API anahtarı ve kendi sunucusu olmayacak; veri telefonda kalacak.
- Sürerken yazı girişi olmayacak; her şey büyük hedeflerle veya sesle yapılacak.
- Mantık Android'den ayrılabilir olacak ki birim testiyle sınanabilsin.

## 2. Mimari

Tek modüllü Android uygulaması (Kotlin, Jetpack Compose, minSdk 26). Tek `MainActivity` tek bir Compose ağacı barındırır; navigasyon kütüphanesi yoktur, `DashboardScreen` yerel durumla pano, harita, ayarlar ve arama ekranı arasında geçer.

```
RideDeckApplication (uygulama ömrü)
 ├─ RideTracking: tek süreç LocationRepository + SpeedFilter + TripRecorder
 │    └─ LocationArbiter: ekran / açık yolculuk servisi / overlay aynı GPS akışını
 │       referans sayımıyla paylaşır; ilk sahip başlatır, son sahip durdurur
 ├─ RideNavigation: tek NavigationController + Routing (Valhalla) + Speaker + FuelFinder
 │    └─ NavigationRideLifecycle: açık yolculukta rehberlik ekran/Activity'den bağımsız
 ├─ RideSafety: CrashDetector + SosController ─► tek SMS + kilit ekranı bildirimi
 └─ DashboardViewModel: ekran durumunun bağlama noktası (repository sahibi değil)
ProviderHttp : tüm genel servis çağrılarında iptal + sağlayıcı bazlı eşzamanlılık/aralık
```

Prensipler:

- **Saf mantık + ince Android kabuğu.** Güvenlik ve konfor modüllerinin (`ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, `PhoneMath`, `PinnedApps`, motosiklet profili vb.) çekirdeği Android bağımlılığı olmayan sınıflardır; zaman ve sensör verisi dışarıdan verilir. Bu yüzden telefon olmadan testlenir.
- **Bağımlılık enjeksiyonu elle.** `NavigationController` arama, rota, konuşma ve metin arama bağımlılıklarını kurucudan alır; `NavigationControllerTest` gerçek servis olmadan çalışır.
- **Tek ağ geçidi.** Genel servislere (Nominatim, Valhalla, Open-Meteo, Overpass, OpenFreeMap) giden çağrılar `data/Http.kt`/`ProviderHttp` üzerinden geçer: sürüm bilgili tek `User-Agent`, iptal edilebilir çağrı ve sağlayıcı başına eşzamanlılık/aralık sınırı. Bu sınır uygulamanın kendi trafiğini şekillendirir; sağlayıcının tüm kullanıcılar toplamındaki kotasını kanıtlamaz.
- **Ömür.** `RideNavigation` uygulama ömründedir; Activity veya ViewModel yeniden oluşması rehberliği, TTS'yi veya açık yolculuğu kapatmaz. Navigasyon aynı süreç içinde ekran kapalıyken de sürer; süreç ölümü/force-stop sonrasında rota kendiliğinden devam etmez, yalnız önizleme olarak geri gelir.

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
- **Rota:** Valhalla, her zaman `motorcycle` costing ile; talimat telefon diline göre Türkçe veya İngilizce. Otoyolsuz ve ücretsiz yol seçenekleri `costing_options`'a çevrilir. Bisiklet/araba profilleri ve bisiklet rota seçenekleri 2026-11-08'de kaldırıldı.
- **Konum eşleme:** her fix, son ilerleme noktasından başlayıp GPS boşluğuna ve hıza göre genişleyen (en az **80 segment**) pencerede en yakın segmente izdüşürülür (`Geo.project`). Rotadan uzaklık hız/doğruluğa göre 45–150 m arasında ölçülür; art arda 3–4 fix sürerse yeniden hesaplama başlar (10 sn yeniden deneme aralığı, "rota yeniden hesaplanıyor" yalnızca bir kez söylenir). 3 taze fix'te yön uyuşmazlığı **yanlış yön/U dönüşü** uyarısını tetikler; geriye sıçrayan eşleşmeler kabul edilmez.
- **Anons zamanlaması:** hıza göre ölçeklenir: uzak uyarı ~45 sn (400 m–2 km), tam talimat ~11 sn (100–450 m), kısa "şimdi" ~3 sn (20–60 m); her anons bir kez söylenir. Varış rota sonu ≤25 m, hedefte durma veya kapalı otoparkta GPS kaybıyla; erken varışa karşı kalan mesafe koşulu vardır.
- **İptal ve eski cevaplar:** her rota/arama isteği bir `generation` taşır; kullanıcı vazgeçtiyse veya yeni istek açıldıysa geç gelen cevap yok sayılır. Bekleyen rota/alternatif/devam işleri sahiplik ve generation ile iptal edilir; konum izni kaybı ve GPS kapanması ayrı nedenlerle (`NavigationTermination`) sonlandırır.
- **Ara durak:** benzinlik kendi başına bir rota olarak hesaplanır, asıl hedef `finalDestination`'da tutulur; varınca "Yola devam" eski hedefe döner. Şarj/tamir/su durağı türleri kaldırıldı.
- **Yakıt arama dayanıklılığı:** genel Overpass sunucuları kararsızdır (500, 30 sn cevap). Sorgu en çok **iki farklı operatöre** (FOSSGIS ve kumi/private.coffee) paralel atılır; aynı operatöre iki bağlantı açılmaz, ilk iyi cevap alınır, olmazsa Nominatim koridoruna düşülür.
- **Tazelik ve sağlayıcı bütçesi:** önizleme 10 dakikadan eskiyse veya GPS rota başlangıcının ilk 50 m koridorunda değilse **Başlat** kilitlidir. Hava durumu ilk veri gelene kadar üstel geri çekilir; hız limiti hata sonrası bekler ve 60 sn'den eski ölçümü göstermez.

Testler: `NavigationControllerTest`, `GeoTest`, `PlaceQueryTest`, `FuelTest`, `RouteOptionsTest`, `RouteStartGuard`/`GuidanceMath`, geometri ve detour testleri.

## 5. Sesli asistan neden yok

Uygulamada bir dönem Türkçe sesli asistan vardı (komut ayrıştırıcı, çok turlu diyalog, kask mikrofonu, "Hey RideDeck" uyandırma denemeleri). 2026-10-28'de tamamen kaldırıldı: Android'in `SpeechRecognizer`'ı sürekli dinleme için tasarlanmadığı için uyandırma güvenilir çalışmadı, yerel uyandırma modeli de gerçek sesle yeterince tutarlı sonuç vermedi. Çalışmayan özelliği göstermek yerine kaldırmak tercih edildi. Sesli yönlendirme (sıradaki dönüş anonsu, hava ve hız uyarıları, mesaj okuma) Android TTS ile devam ediyor.

## 6. Güvenlik özellikleri (SOS) ve sınırı

Kaza algılama (`CrashDetector`) şu mantıkla çalışır: yakın zamanda sürüş hızı **ve** eşiği aşan darbe, ardından telefonun sakin kalması ve aracın durması. Çukur (darbe var, araç yuvarlanıyor), sert fren (darbe yok) ve park yerinde devrilen motosiklet (hiç sürülmedi) sessiz kalır. İki tekerlekte ek olarak telefonun yönünün değişmiş (≥ 25°) ya da darbenin eşiğin 2 katı olması istenir; böylece "çukur + kırmızı ışık" yanlış alarmı azalır. Sonrasında geri sayım, geri sayım bitince kişi başına **tek** SMS (konum bağlantılı); arama yapılmaz.

**Bu bir güvenlik garantisi değildir.** Motosiklet eşiği (4 g) tasarım varsayımıdır ve **sahada ayarlanmamıştır**; gerçek bir kazada veya düşüşte test edilmedi. Özellik varsayılan kapalıdır. Sensör, uygulama ekrandayken veya başlatılmış bir yolculuğun ön plan servisi sürerken dinler. Ekran kapalıyken başlayan geri sayım kilit ekranı üstünde bildirim, tam ekran bildirim ve uyandırma kilidiyle öne alınmaya çalışılır; bildirimde **İyiyim/İptal** eylemleri vardır. Bildirim yoksa uygulama açılmaya çalışılır; FSI reddedilirse yüksek öncelikli bildirim kalır, ses/titreşim ve SMS yolu sürer. Gerçek kilitli cihaz, OEM FSI reddi, SIM/operatör teslimi **doğrulanmadı**; operatör SMS ücreti uygulanabilir. Yanlış alarm ve kaçırılmış kaza ikisi de mümkündür. README'de ve uygulamada bu şekilde sunulur.

## 7. Testler ve CI

- **Son yerel tam JVM koşusu: debug + release 828/828, 0 hata, 0 atlama** (`./gradlew test`, JUnit). Bu son koşudur; sürekli yeşil garanti değildir ve önceki tarihli test sayıları tarihsel kayıttır.
- **Lint:** iki varyantta **0 hata** (debug 118 / release 117 uyarı; KTX, çoğul metin, log, bağımlılık ve UI kaynakları ağırlıklı; bastırılmadı).
- Test edilenler: GPS filtresi, konum eşleme ve rota mantığı, yazım toleransı, SOS/kaza algılama ve SMS sonuç modeli, ısınma koruması, telefon/klavye matematiği, birimler, yolculuk kaydı, SQLite geçişi/bozulma yedeği, sağlayıcı sınırlama/geri çekilme ve paylaşılan GPS sahipliği.
- **GitHub Actions** (`.github/workflows/ci.yml`): `test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, release APK hizalama, AAB varlığı, debug x86_64/ARM ABI, release ARM-only ve koşullu imza kontrolü. Ayrı iş, x86_64 API 35 emülatöründe instrumentation çalıştırır; cached-app freezer kapatılır.
- **Instrumentation:** toplam **203 test** hedefli batch'lerle koşuluyor; `RideDeckTestRunner` sonuçtan sonra test sürecini kapatır. Tek koşuda tam yeşil sonuç iddia edilmez.
- Test edilmeyen: gerçek sensör/GPS/ağ davranışı, fiziksel kilit ekranı/Doze/OEM, gerçek SMS/arama/interkom ve 16 KB runtime. Bunlar emülatör/sentetik testlerle eşdeğer sayılmaz.

## 8. Teknik kararlar ve gerekçeleri

| Karar | Gerekçe |
|---|---|
| Ham `LocationManager`, Play Services yok | Bağımlılık ve hesap/anahtar yok; açık kaynak ve F-Droid benzeri kurulumla uyumlu. Bedeli: kendi filtre ve eşleme kodunu yazmak. |
| Tek süreç GPS akışı + referans sayımlı `LocationArbiter` | Ekran, açık yolculuk servisi ve overlay aynı fix'leri paylaşır; ikinci abonelik açılmaz ve bir sahibin ayrılması diğerlerinin GPS'ini kesmez. |
| Tüm servisler anahtarsız genel servis + merkezi `ProviderHttp` | "Hesap, anahtar, sunucu yok" sözünü korur. Bedeli: servis kararsızlığı için yedek/geri çekilme; uygulama içi sınır sağlayıcı global kotasını kanıtlamaz. |
| Gösterim hızı ≠ karar hızı | Titremeyen kadran ile gecikmeyen güvenlik kararı birbirine feda edilmedi. |
| Sahte 0 yok, `--` var | Sinyal kaybında yanlış güven vermemek. |
| Sürerken yazı girişi/ayar kilidi (5 km/sa üstü) | Güvenlik; kendi Türkçe klavyesi park halinde arama içindir. |
| Sesli asistan kaldırıldı | Güvenilir uyandırma ve tanıma sağlanamadı; çalışmayan özelliği göstermek yerine kaldırıldı. |
| Çevrimdışı rota yok; harita indirmeleri sağlayıcı kapısında | Rota ve arama canlı hizmete bağlı kalır. Yeni indirme/resume, OpenFreeMap koşulları ve global uyum kanıtlanana kadar **BLOCKED**; mevcut alanlar korunur. |
| OBD, bakım takibi, eğim, GPX çıkarıldı | Sürerken kullanılamayan veya doğrulanamayan özelliklerden kaçınmak; odak sürüş panelinde. |

## Gerçek cihaz doğrulamaları

`docs/STATUS.md` kayıtlarına göre debug ve R8'li release APK'ları geçmişte bir fiziksel Android telefona kurulup denenmişti. Cihaz günlükleri, sürekli "Hey RideDeck" dinlemesinin neden bırakıldığını gösterdi (§5); bu kayıt sesli asistan dönemine aittir ve güncel sürümde asistan yoktur. Bu çalışmada telefon bağlı olmadığı için **yeni bir cihaz testi yapılmadı**; aşağıdaki "denenmedi" listesi güncel tutuluyor. Gerçek cihazla 60–90 sn'lik kayıt için hazır senaryo ve komutlar: [DEMO-SCENARIO.md](DEMO-SCENARIO.md). Henüz gerçek cihaz videosu yok.

## Doğrulama durumu

**Doğrulandı (otomatik):** son yerel koşuda 828/828 JVM testi (debug + release), iki lint (0 hata), debug ve R8 release derlemesi, AAB, 16 KB statik hizalama ve CI yapılandırması. **Instrumentation:** 203 test hedefli batch'lerle sürüyor; tek tam yeşil koşu yok. **Emülatörde görüldü:** ana ekranlar, navigasyon, benzinlik, SOS geri sayımı, farklı yazı boyutları. Araç seçimi ve dik bisiklet düzeni artık yoktur. README'deki ekran görüntüleri ve demo GIF'i emülatörden alınmıştır (sahte GPS ile); demo GIF bir ekran görüntüsü geçişidir, ekran kaydı değildir.

**Sahada denenmedi:** kaza algılama eşikleri, gerçek SMS gönderimi ve SIM/operatör teslimi, fiziksel kilit ekranı/Doze/OEM, varsayılan telefon uygulamasıyken arama ses yolu ve interkom, gidonda ısınma ve mini haritanın pil etkisi, Android 8–10 davranışı, 16 KB runtime, mağaza/Data safety ve sağlayıcı global kota uyumu. Güncel liste: [STATUS.md](STATUS.md) ve [RELEASE_READINESS.md](RELEASE_READINESS.md). Genel 1.0 kararı **NO-GO**.
