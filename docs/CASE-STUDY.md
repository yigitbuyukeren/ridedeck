# RideDeck teknik vaka analizi

🇬🇧 [English version](CASE-STUDY.en.md)

> **Güncellik notu (2026-11-17):** Bu vaka analizi belirli bir geliştirme dönemindeki tasarımı anlatır. Uygulama artık yalnız motosiklet içindir; araç seçici, bisiklet/araba profilleri ve HOME launcher desteği kaldırılmıştır. Güncel davranış ve mimari için [README](../README.md) ve [ARCHITECTURE.md](ARCHITECTURE.md); tarihsel test sayıları ve doğrulama kayıtları için [STATUS.md](STATUS.md) dosyalarına bak.

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
LocationRepository (ham LocationManager, Play Services yok)
   ├─► SpeedFilter ─► hız göstergesi (yalnızca gösterim)
   ├─► NavigationController ─► Routing (Valhalla) / Speaker / FuelFinder
   ├─► CrashDetector + SosController ─► kişi başına bir SMS (en fazla 3 kişi)
   └─► TripRecorder ─► yolculuk geçmişi
DashboardViewModel = ekranın bağlama noktası (süreç sahipleri: RideTracking, RideNavigation, RideSafety)
data/Http.kt : genel HTTP servislerinin tek çıkışı (MapLibre karo istekleri hariç)
```

Prensipler:

- **Saf mantık + ince Android kabuğu.** Güvenlik ve konfor modüllerinin (`ThermalGuard`, `CrashDetector`, `SpeedFilter`, `PositionSmoother`, `PhoneMath`, `PinnedApps`, `VehicleProfile` vb.) çekirdeği Android bağımlılığı olmayan sınıflardır; zaman ve sensör verisi dışarıdan verilir. Bu yüzden telefon olmadan testlenir.
- **Bağımlılık enjeksiyonu elle.** `NavigationController` arama, rota, konuşma ve metin arama bağımlılıklarını kurucudan alır; `NavigationControllerTest` gerçek servis olmadan çalışır.
- **Tek ağ çıkışı.** Genel HTTP servislerine (Nominatim, Valhalla, Open-Meteo, Overpass) giden çağrılar `data/Http.kt` içinden geçer: sürüm bilgili tek `User-Agent`, tek `safely()` yardımcısı. MapLibre'nin OpenFreeMap karo istekleri motorun kendi ağ katmanındadır.
- **Ömür.** `NavigationController` süreç ömürlü `RideNavigation` içinde yaşar; `DashboardViewModel` yalnız bu örneği kullanır. Harita ekranı kapansa da yönlendirme ve ses sürer.

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

- **Arama:** Nominatim, yalnızca açıkça "Ara"ya basınca (kullanım politikası yazarken-ara'yı yasaklar). Sonuç çıkmazsa `PlaceQuery` düzeltilmiş yazımları (Türkçe harf varyantları, eksik/yer değiştirmiş harf, il/ilçe adlarına bulanık eşleşme) saniyede en çok bir istekle dener; yine yoksa "Bunu mu demek istedin?" der ve **onaysız rotaya girmez**.
- **Rota:** Valhalla, her zaman `motorcycle` costing ile; Türkçe talimat. Otoyolsuz ve ücretsiz yol seçenekleri `costing_options`'a çevrilir.
- **Konum eşleme:** her fix, son ilerleme noktasından başlayıp öne doğru **80 segmentlik pencerede** en yakın segmente izdüşürülür (`Geo.project`). Geriye ve çok ileriye atlamak mümkün olmadığından kıvrımlı yolda yanlış segment seçilmez. Rotadan uzaklık eşiği hız ve GPS doğruluğuna göre 45–150 m arasında hesaplanır; 20 m/s ve üzerinde 3, altında 4 ardışık fix ile yeniden hesaplama başlar (10 sn yeniden deneme aralığı, "rota yeniden hesaplanıyor" yalnızca bir kez söylenir).
- **Anons zamanlaması:** mesafeli ön uyarı, tam talimat ve dönüşte kısa "şimdi" olmak üzere üç kademe; her anons bir kez söylenir (`spoken` kümesi).
- **İptal ve eski cevaplar:** her rota/arama isteği bir `generation` taşır; kullanıcı vazgeçtiyse veya yeni istek açıldıysa geç gelen cevap yok sayılır. Konum izni kaybı ve GPS kapanması ayrı nedenlerle (`NavigationTermination`) sonlandırır.
- **Ara durak:** benzinlik kendi başına bir rota olarak hesaplanır, asıl hedef `finalDestination`'da tutulur; varınca "Yola devam" eski hedefe döner.
- **Yakıt arama dayanıklılığı:** genel Overpass sunucuları kararsızdır (500, 30 sn cevap). Sorgu birkaç aynaya paralel atılır, ilk cevap alınır, olmazsa Nominatim koridoruna düşülür.

Testler: `NavigationControllerTest`, `GeoTest`, `PlaceQueryTest`, `FuelTest`, `RouteOptionsTest`.

## 5. Sesli asistan neden yok

Uygulamada bir dönem Türkçe sesli asistan vardı (komut ayrıştırıcı, çok turlu diyalog, kask mikrofonu, "Hey RideDeck" uyandırma denemeleri). 2026-10-28'de tamamen kaldırıldı: Android'in `SpeechRecognizer`'ı sürekli dinleme için tasarlanmadığı için uyandırma güvenilir çalışmadı, yerel uyandırma modeli de gerçek sesle yeterince tutarlı sonuç vermedi. Çalışmayan özelliği göstermek yerine kaldırmak tercih edildi. Sesli yönlendirme (sıradaki dönüş anonsu, hava ve hız uyarıları, mesaj okuma) Android TTS ile devam ediyor.

## 6. Güvenlik özellikleri (SOS) ve sınırı

Kaza algılama (`CrashDetector`) şu mantıkla çalışır: yakın zamanda sürüş hızı **ve** eşiği aşan darbe, ardından telefonun sakin kalması ve aracın durması. Çukur (darbe var, araç yuvarlanıyor), sert fren (darbe yok) ve park yerinde devrilen motosiklet (hiç sürülmedi) sessiz kalır. İki tekerlekte ek olarak telefonun yönünün değişmiş (≥ 25°) ya da darbenin eşiğin 2 katı olması istenir; böylece "çukur + kırmızı ışık" yanlış alarmı azalır. Sonrasında geri sayım, geri sayım bitince kişi başına **bir** SMS (konum bağlantılı, en fazla 3 kişi); arama yapılmaz.

**Bu bir güvenlik garantisi değildir.** Eşik (motosiklet 4 g) tasarım varsayımıdır ve **sahada ayarlanmamıştır**; gerçek bir kazada veya düşüşte test edilmedi. Özellik varsayılan kapalıdır; uygulama ekrandayken veya açıkça başlatılmış bir yolculuk (ön plan servisi) sürerken çalışır. Operatör SMS ücreti uygulanabilir. Yanlış alarm ve kaçırılmış kaza ikisi de mümkündür. README'de ve uygulamada bu şekilde sunulur.

## 7. Testler ve CI

- Bu belge ilk yazıldığında **396 birim testi, 48 test sınıfı/dosyası** vardı; güncel test sayısı ve son sonuçlar [STATUS.md](STATUS.md) içindedir. Testler JUnit ile `testDebugUnitTest` üzerinden çalışır.
- Test edilenler: GPS filtresi, konum eşleme ve rota mantığı, yazım toleransı, SOS/kaza algılama, ısınma koruması, motosiklet profili, telefon/klavye matematiği, birimler, yolculuk kaydı.
- **GitHub Actions** (`.github/workflows/ci.yml`): her `main` push'unda ve PR'da Temurin JDK 17 ile `test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest` çalışır. Instrumentation testleri derlenir, cihazda çalıştırılmaz. `assembleRelease` R8 küçültmesini dener, böylece küçültme hataları PR'da çıkar.
- `lintDebug`/`lintRelease` hatasız tutulur; minSdk 26 ile API 28/30 çağrıları gibi hataları yakalar.
- Instrumentation testleri Compose ekranlarını ve bazı servis/izin akışlarını kapsar; cihazda çalıştırmak için `connectedDebugAndroidTest` gerekir. Gerçek sensör/ağ davranışı, fiziksel cihaz ve saha koşulları otomatik test edilmez.

## 8. Teknik kararlar ve gerekçeleri

| Karar | Gerekçe |
|---|---|
| Ham `LocationManager`, Play Services yok | Bağımlılık ve hesap/anahtar yok; açık kaynak ve F-Droid benzeri kurulumla uyumlu. Bedeli: kendi filtre ve eşleme kodumu yazmak. |
| Tüm servisler anahtarsız genel servis | "Hesap, anahtar, sunucu yok" sözünü korur. Bedeli: servis kararsızlığı (Overpass) için yedek ve yeniden deneme yazmak; yoğun kullanımda kendi sunucu gerekir. |
| Gösterim hızı ≠ karar hızı | Titremeyen kadran ile gecikmeyen güvenlik kararı birbirine feda edilmedi. |
| Sahte 0 yok, `--` var | Sinyal kaybında yanlış güven vermemek. |
| Sürerken yazı girişi/ayar kilidi (5 km/sa ve üzeri) | Güvenlik; kendi Türkçe klavyesi park halinde arama içindir. |
| Sesli asistan kaldırıldı | Güvenilir uyandırma ve tanıma sağlanamadı; çalışmayan özelliği göstermek yerine kaldırıldı. |
| Çevrimdışı rota yok; mevcut harita bölgeleri korunur | Rota ve arama canlı hizmete bağlı kalır; yeni çevrimdışı indirmeler sağlayıcı uyumluluğu doğrulanana kadar kapalıdır. |
| OBD, bakım takibi, eğim, GPX çıkarıldı | Sürerken kullanılamayan veya doğrulanamayan özelliklerden kaçınmak; odak sürüş panelinde. |

## Gerçek cihaz doğrulamaları

`docs/STATUS.md` kayıtlarına göre debug ve R8'li release APK'ları bir fiziksel Android telefona kurulup denendi. Cihaz günlükleri, sürekli "Hey RideDeck" dinlemesinin neden bırakıldığını gösterdi (§5). Bu çalışmada telefon bağlı olmadığı için **yeni bir cihaz testi yapılmadı**; aşağıdaki "denenmedi" listesi güncel tutuluyor. Gerçek cihazla 60–90 sn'lik kayıt için hazır senaryo ve komutlar: [DEMO-SCENARIO.md](DEMO-SCENARIO.md). Henüz gerçek cihaz videosu yok.

## Doğrulama durumu

**Doğrulandı (otomatik):** Bu belgenin ilk sürümünde 396 birim test, `lintDebug`, debug ve R8 release derlemesi ve CI; güncel sayılar [STATUS.md](STATUS.md) içindedir. **Emülatörde görüldü:** ana ekranlar, navigasyon, benzinlik, SOS geri sayımı, farklı yazı boyutları. README'deki ekran görüntüleri ve demo GIF'i emülatörden alınmıştır (sahte GPS ile); demo GIF bir ekran görüntüsü geçişidir, ekran kaydı değildir.

**Sahada denenmedi:** kaza algılama eşikleri, gerçek SMS gönderimi, varsayılan telefon uygulamasıyken arama ses yolu, gidonda ısınma ve mini haritanın pil etkisi, Android 8–10 davranışı. Güncel liste: [STATUS.md](STATUS.md).
