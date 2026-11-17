# RideDeck mimarisi

Bu belge RideDeck'e kod katkısı yapacaklar için paketleri, çalışma zamanı akışını ve değişiklik noktalarını anlatır. Bağlayıcı kaynak koddur; burada anlatılan bir davranış kodda yoksa kod doğrudur. RideDeck **yalnız motosiklet** içindir; araç seçici, araba/bisiklet profili veya böyle bir dallanma yoktur.

<details>
<summary><b>English summary</b></summary>

RideDeck is a single-module Kotlin/Jetpack Compose Android app (`minSdk 26`, `compileSdk`/`targetSdk 36`). `MainActivity` hosts one Compose tree; `DashboardViewModel` is the screen wiring point, while `RideDeckApplication` creates the process-owned `RideTracking` (with `LocationArbiter`), `RideNavigation` and `RideSafety` objects. Domain logic and repositories live in `data/`, Compose screens in `ui/`, Android foreground services and notifications in `service/`, and small helpers in `util/`. GPS comes from the platform `LocationManager`; display speed is filtered separately from the raw speed used by safety decisions. Public HTTP calls go through `data/Http.kt`/`ProviderHttp`, while MapLibre tile requests stay in the map engine. Data stays on the phone, routing is online-only, and crash/SOS detection is an aid, not a guarantee. The Turkish sections below map packages, data flow, the network layer, persistence, UI rules, testing and change locations.

</details>

## 1. Kısa özet

| Konu | Durum |
|---|---|
| Uygulama türü | Tek modüllü Android uygulaması (`:app`) |
| Dil ve arayüz | Kotlin, Jetpack Compose |
| SDK | `minSdk 26`, `compileSdk 36`, `targetSdk 36`; JDK 17 |
| Hesap / anahtar / sunucu | Yok. RideDeck hesabı, API anahtarı ve kendi arka ucu yoktur |
| Ağ | Anahtarsız, ücretsiz genel servisler (Nominatim, Valhalla, Open-Meteo, Overpass); OpenFreeMap harita karoları MapLibre'nin kendi motorunda |
| Veri | Yalnız telefonda; bulut eşitleme ve Android yedeklemesi kapalı |
| Kullanım | Yatay gidon düzeni; hız, harita, müzik, telefon ve SOS tek panelde |
| Rota | Her zaman Valhalla `motorcycle` costing; motosiklet sabitleri `VehicleProfile.MOTORCYCLE.config` (`ProfileConfig`) içinde |

Kodda görülen `VehicleProfile` yalnız motosiklet sabitlerini taşır; ikinci bir profil veya araç seçici yoktur.

Uygulamanın amacı, telefonu eldivenle kullanılabilen bir motosiklet sürüş paneline çevirmektir. Bu yüzden mimari kararların bir kısmı doğrudan sürüş güvenliğidir: sürerken yazı girişi yoktur, dokunma hedefleri büyüktür ve hız bilinmiyorsa sahte `0` gösterilmez.

## 2. Çalışma zamanı giriş noktaları

### `MainActivity`

Tek `Activity`'dir (`singleTask`, `sensorLandscape`). Edge-to-edge çalışır, sistem çubuklarını gizler ve ekranı açık tutar. Termal koruma ekran parlaklığını düşürebilir; SOS geri sayımı sırasında parlaklık geri açılır. `tel:` bağlantıları tuş takımına taşınır.

`onStart`/`onStop` sırasında süreç ömürlü üç sahibe ekran görünürlüğünü bildirir:

- `RideTracking.setScreenVisible(...)`
- `RideNavigation.setScreenVisible(...)`
- `RideSafety.setScreenVisible(...)`

`setContent` tek Compose ağacını kurar:

```kotlin
MotoTheme(isDay) { DashboardScreen(viewModel) }
```

Navigasyon kütüphanesi, Fragment ve ayrı Activity yoktur. Ekran geçişleri `DashboardScreen` içindeki yerel durumla ve `AnimatedContent` ile yapılır. Bağımlılıklar elle bağlanır; DI framework'ü yoktur.

### `RideDeckApplication`

Süreç ömürlü sahipleri tembel olarak oluşturur:

| Sahip | Görevi |
|---|---|
| `rideTracking` | `LocationArbiter` ile paylaşılan tek `LocationRepository`, `TripRecorder`, kayıtlı yerler, yolculuk oturumu |
| `rideNavigation` | `NavigationController`, ses/TTS, medya, çağrı durumu, rota ve geometri kalıcılığı |
| `rideSafety` | Kaza algılama, `SosController`, kişiler ve geri sayım bildirimi |

`onCreate` içinde `rideSafety` erken başlatılır; böylece süreç ölürken yükseltilmiş alarm sesi bir sonraki açılışta geri alınır.

### `DashboardViewModel`

`AndroidViewModel`'dir ve ekranın bağlama noktasıdır; repository sahibi değildir. Uygulama ömürlü `rideTracking`, `rideNavigation` ve `rideSafety` örneklerini kullanır; kendi GPS aboneliğini açmaz. Ekran ömürlü yardımcıları (pil, tema, hava durumu, hız limiti, ısınma koruması, sapma teklifi) burada kurar.

ViewModel'in ürettiği `gps` akışı üç ayrı bilgi taşır:

- `speedKmh`: `GpsUi` içinde ham GPS hızı; taze fix yoksa `0`. Karar modülleri doğrudan `LocationState` içindeki ham hızı kullanır.
- `displayKmh`: gösterim için süzülmüş hız; taze fix yoksa `null`.
- `motion` / `inputLocked`: `MotionTracker` ile üretilen hareket ve yazı/ayar kilidi.

## 3. Paket haritası

| Paket | Sorumluluk | Öne çıkan dosyalar |
|---|---|---|
| `com.ridedeck.app` | Giriş noktaları ve bağlama | `MainActivity.kt`, `RideDeckApplication.kt`, `DashboardViewModel.kt` |
| `data/` | Saf mantık, repository'ler, sensör/SOS, kalıcılık, ağ | `LocationRepository.kt`, `NavigationController.kt`, `RideSafety.kt`, `Http.kt` |
| `ui/` | Compose ekranları, kartlar, tema, harita | `DashboardScreen.kt`, `MapScreen.kt`, `SettingsScreen.kt`, `TripsScreen.kt` |
| `service/` | Ön plan servisleri, bildirimler, çağrı köprüleri | `RideTrackingService.kt`, `OverlayService.kt`, `CarNotificationListener.kt`, `MotoInCallService.kt` |
| `util/` | Tercihler, birim dönüşümü, dış uygulama açma, metin yardımcıları | `Prefs.kt`, `Units.kt`, `Launch.kt`, `MessageText.kt` |

### `data/` altındaki ana gruplar

| Grup | Dosyalar | Not |
|---|---|---|
| Konum ve hız | `RideTracking.kt` (`LocationArbiter`), `LocationRepository.kt`, `SpeedFilter.kt`, `PositionSmoother.kt`, `Motion.kt` | Ham `LocationManager`; ekran/servis/overlay aynı GPS akışını paylaşır |
| Navigasyon | `NavigationController.kt`, `NavigationRideLifecycle.kt`, `RouteSnap.kt`, `Geo.kt`, `GuidanceMath.kt` | Arama, rota, eşleme, anons ve yeniden rota |
| Ağ servisleri | `Http.kt`, `ProviderThrottle.kt`, `RouteRepository.kt`, `Fuel.kt`, `Weather.kt`, `SpeedLimit.kt` | `ProviderHttp` sınırı; iptal ve sağlayıcı bazlı eşzamanlılık/aralık |
| Güvenlik | `RideSafety.kt`, `Sos.kt`, `SosAndroid.kt`, `RideReadiness.kt`, `MessagePrivacy.kt` | Kaza algılama, SMS ve bildirim gizliliği |
| Yolculuklar | `RideTracking.kt`, `TripRecorder.kt`, `TripStore.kt`, `TripStats.kt` | Otomatik kayıt, SQLite geçmişi |
| Kalıcılık | `SavedPlaces.kt`, `ActiveTrip.kt`, `RouteGeometryStore.kt`, `RouteGeometryPersistence.kt` | Yerel JSON/SQLite/AtomicFile |
| Harita ve çevrimdışı | `OfflineMaps.kt`, `OfflinePlan.kt`, `OfflineRegions.kt` | Mevcut bölgeler korunur; yeni indirme kapalı |
| Telefon ve medya | `CallRepository.kt`, `MediaRepository.kt`, `Phone.kt`, `Voice.kt`, `CallCapability.kt`, `CallerNamePolicy.kt` | Bildirim erişimi, çağrı yeteneği ve TTS |
| Yardımcı mantık | `GaugeScale.kt`, `DashLayout.kt`, `PlaceQuery.kt`, `Sos.kt` (`SosMath`) | Android'siz birim testine uygun |

### `ui/` ve `service/`

`DashboardScreen` pano, harita, müzik ve telefon ekranlarını; ayrıca Ayarlar, yolculuk geçmişi, izin sayfası, "Sürüşe hazır mı?" paneli, arama ekranı ve SOS geri sayımını yönetir. `MapScreen` MapLibre haritasını, arama/rota panellerini, kayıtlı yerleri ve benzinlik duraklarını içerir. `SettingsScreen`, `TripsScreen`, `PhoneScreen`, `CallScreen`, `NowPlayingScreen` ve `SosUi` diğer büyük ekranlardır.

`service/` katmanı Android kabuğudur:

- `RideTrackingService`: yalnız kullanıcının açıkça başlattığı yolculukta ön plan konum servisi; bildirimden bitirilebilir; önyükleme alıcısı veya kendiliğinden yeniden başlama yoktur.
- `OverlayService`: başka uygulama öndeyken hız/saat şeridi; RideDeck görünürken gizlenir.
- `CarNotificationListener`: bildirim erişimiyle müzik, mesaj ve arayan adı bilgisi; gizli bildirimlerde içerik okunmaz, kilitli özel içerik yalnız uygulama adıyla gösterilir; son mesaj yalnız bellekte tutulur.
- `SosActionReceiver`: kilit ekranı bildirimindeki **İyiyim/Kapat** eylemlerini işler.
- `MotoInCallService` / `IncomingCallNotification`: RideDeck varsayılan telefon uygulamasıysa görüşme ekranı ve gelen arama bildirimi.

`util/` içinde `Prefs` tüm `SharedPreferences` anahtarlarını, `Units`/`UnitMath` yalnız gösterim dönüşümünü (içte km/sa ve metre), `Launch` dış uygulama ve izin yardımcılarını barındırır.

## 4. Veri akışı

```text
LocationManager (GPS_PROVIDER, ~1 Hz)
  └─► LocationRepository.state  (ham LocationState)
        └─► LocationArbiter (ekran / açık yolculuk servisi / overlay referans sayımı)
              ├─► SpeedFilter ──► displayKmh() ──► SpeedCard / OverlayService
              ├─► PositionSmoother ──► MapScreen imleci ve kamera (kare başına)
              ├─► NavigationController ──► rota eşleme, anons, yeniden rota
              │        └─► RideNavigation (süreç) ──► Voice / Media / Call
              │                 └─► ActiveTrip + RouteGeometry kalıcılığı
              ├─► RideSafety ──► CrashDetector ──► SosController ──► SMS
              └─► TripRecorder ──► TripStore (SQLite)
DashboardViewModel: ekran akışlarını birleştirir, GpsUi ve inputLocked üretir
Compose: DashboardScreen ──► hız kartı, harita, seyahat, ayarlar, SOS
ProviderHttp: genel HTTP çağrılarında iptal + sağlayıcı bazlı eşzamanlılık/aralık
```

**Gösterim hızı ile karar hızı ayrıdır.** `SpeedFilter` yalnız kadran, hız şeridi ve "kaçla gidiyorum" içindir; kaza algılama, yolculuk kaydı ve hız limiti uyarısı ham `speedKmh` değerini kullanır. Filtre güvenlik kararını geciktirmez.

**Sinyal kaybında sahte sıfır yoktur.** Hareket halindeyken fix gelmezse `displayKmh` `null` olur; arayüz `--` ve "GPS sinyali kayıp" gösterir. Duruşta kısa bir tutma süresi vardır; bu, gerçek duruş ile veri kaybını karıştırmamak içindir.

**Navigasyon.** Arama yalnız "Ara"ya basınca çalışır. `NavigationController` Nominatim sonucunu seçilen yere çevirir, Valhalla'dan rota ister, her fix'i rota üzerinde eşler ve anonsları planlar. Her istek bir `generation` taşır; eski veya iptal edilmiş cevap yok sayılır. `RideNavigation` bu oturumu süreçte tutar; ekran kapansa da başlatılmış rehberlik ve ses devam eder.

**Güvenlik.** `RideSafety`, ivmeölçer ve GPS hızını `CrashDetector`'a verir; darbe, yakın zamanda sürüş ve hareketsizlik koşulları (motosiklette ayrıca eğim veya çift eşik) oluşursa `SosController` geri sayım başlatır, ardından kişi başına tek SMS denemesi yapar; arama açmaz. SOS varsayılan kapalıdır; kişi listesi boşsa tetikleyiciler kapanır. Kaza algılama yalnız pano görünürken veya açıkça başlatılmış yolculuk sürerken dinler. Ekran kapalıyken başlayan geri sayım kilit ekranı bildirimi, tam ekran bildirim ve uyandırma kilidiyle öne alınmaya çalışılır; FSI reddedilirse yüksek öncelikli bildirim kalır. **Bu bir güvenlik garantisi değildir:** eşikler sahada ayarlanmamıştır, gerçek SMS teslimi ve kilit ekranı/OEM davranışı garanti edilmez, yanlış alarm ve kaçırılmış olay mümkündür.

**Yolculuk kaydı.** `TripRecorder` art arda üç fix en az `8 km/sa` ise kaydı başlatır, üç dakika hareket yoksa bitirir; `300 m` veya `60 sn` altındaki kayıtları siler. Noktalar geldikçe SQLite'a yazılır; süreç ölürse yarım kayıt sonraki açılışta kapatılır. Kayıt tercihi kapalıysa yalnız GPS takibi yapılır.

## 5. Ağ katmanı

Genel servis çağrılarının merkezi sınırı `data/Http.kt` ve `ProviderHttp`'dir. Tek `User-Agent` şu biçimdedir:

```text
RideDeck/<sürüm> (+https://github.com/yigitbuyukeren/ridedeck)
```

`Http` hem bloklayan (`get`, `postJson`, `postForm`) hem de iptal edilebilir (`getCancellable`, `postJsonCancellable`, `postFormCancellable`) yollar sunar. `ProviderHttp`, servis başına eşzamanlılık ve istek aralığı sınırını uygular; iptal, OkHttp `Call.cancel()` ile açık soketi kapatır. OkHttp yalnız bu katmanda, sınırlı bir iş parçacığı havuzuyla kullanılır. Testler `ServiceHttp` arayüzüne sahte taşıma enjekte eder. Uygulama içi sınır, sağlayıcının tüm kullanıcılar toplamındaki kotasını kanıtlamaz.

| Servis | Kullanım | Not |
|---|---|---|
| Nominatim | Yer arama ve ters geocode | Yalnız açık aramada; yazarken arama yok; merkezi sınır en çok 1 istek/sn |
| Valhalla | `motorcycle` rota ve `trace_attributes` hız limiti | Anahtarsız genel sunucu; `X-Client-Id` başlığı |
| Open-Meteo | Sıcaklık, yağmur ve buzlanma tahmini | Yaklaşık konum; anahtarsız |
| Overpass | Benzinlik sorgusu | En çok iki farklı operatöre paralel; yanıt yoksa Nominatim koridoruna düşer |
| OpenFreeMap | Harita stilleri, karoları ve çevrimdışı indirme | MapLibre'nin kendi HTTP motoru; `Http`/`ProviderHttp` dışında |

**Önemli istisna:** MapLibre'nin canlı stil/karo ve çevrimdışı indirme istekleri render motorunun içindedir ve `Http`/`ProviderHttp` sınırından geçmez. Bu nedenle harita çağrılarını bu katmandan geçiyor gibi anlatmayın. Yeni çevrimdışı indirme/resume, sağlayıcı koşulları ve toplam uyum kanıtlanana kadar kapalıdır; canlı harita görüntülenen bölgenin karolarını normal biçimde ister.

### Yeni servis ekleme kuralı

1. Önce issue açın; yeni dış servis veri modeli, gizlilik ve lisans etkisi taşır.
2. UI veya controller içinde doğrudan `URL.openConnection()`, `OkHttpClient` ya da servis URL'si kullanmayın.
3. `data/` altında servise özel bir repository ekleyin; HTTP bağımlılığını `ServiceHttp` olarak enjekte edin.
4. Çağrıyı `Http`'nin iptal edilebilir yolundan yapın; coroutine iptalinde soketin kapandığından emin olun.
5. `User-Agent` ve zaman aşımı davranışını `Http.kt`'de bırakın; servis politikasına uyun (ör. Nominatim'de yazarken arama yok, istek hızı sınırlı).
6. Sahte `ServiceHttp` ile JVM testi ekleyin; ağ yokken sahte veri üretmeyin, hatayı kullanıcıya güvenli biçimde gösterin.
7. Dışarıya giden veriyi, atfı ve varsa yeni izni README/gizlilik metnine ekleyin. Anahtar veya hesap gerektiren servis bu mimariye uymaz; önce tartışılmalıdır.

## 6. Kalıcılık ve veri

| Veri | Yer | Davranış |
|---|---|---|
| Tercihler | `SharedPreferences` dosyası `ridedeck` | Birim, tema, ses, rota seçenekleri, kayıtlı/son yerler, SOS kişileri, çevrimdışı bölge metadata'sı |
| Yolculuklar | SQLite `trips.db` | `trips` ve `points` tabloları; bitmemiş kayıt sonraki açılışta kapatılır |
| Aktif rota özeti | `SharedPreferences` içinde JSON | Hedef, kalan duraklar, seçenekler; en fazla 6 saat tazelik |
| Rota geometrisi | `noBackupFilesDir/route_geometry_v1.json` | `AtomicFile`, boyut/şema/tazelik/kimlik denetimi; yalnız önizleme |
| Çevrimdışı bölgeler | Prefs metadata + MapLibre yerel bölgeleri | Mevcut bölgeler korunur; yeni indirme başlatılmaz |

Tüm veri telefonda kalır. `android:allowBackup="false"` olduğu için Android bulut yedeklemesi yoktur. RideDeck hesabı, senkronizasyonu veya dışa aktarma sunucusu yoktur.

**Süreç ölümü sonrası rota.** Kaydedilmiş rota geometrisi yalnız önizleme olarak geri gelir; rehberlik kendiliğinden başlamaz. **Başlat** yalnız taze ve hassas GPS fix'i rotanın ilk 50 m'lik ilerleme diliminde ve rota koridorundaysa açılır. Aksi hâlde **Rotayı güncelle** ile açıkça yeni rota istenir. Bu, çevrimdışı rota hesabı değildir.

**Çevrimdışı sınırı.** Çevrimdışı rota hesaplama ve çevrimdışı arama yoktur; rota, arama ve hava durumu internet ister. Önceden indirilmiş harita alanları korunur, ancak **yeni çevrimdışı indirmeler sağlayıcı uyumluluğu doğrulanana kadar kapalıdır**. Devralınan etkin bir indirme listede "Vazgeç" ile kapatılabilir; bu yeni indirme veya devam başlatmaz. Kayıtlı bölge metadata'sı karo bulunduğunu kanıtlamaz.

## 7. UI ilkeleri

- **Sürerken yazı girişi yok.** Harita araması, tuş takımı ve Ayarlar, hareket halindeyken kilitlenir. Uygulamanın kendi Türkçe klavyesi yalnız park halinde açılır. Dış navigasyon uygulamasına geçiş de yalnız dururken sunulur.
- **5 km/sa ve üzeri kilit.** `MotionTracker` süzülmüş gösterim hızını iki fix ile doğrular; eşik `5 km/sa`. Kilit devreye girince Ayarlar kapanır ve kısa bir uyarı gösterilir. GPS yoksa durum `UNKNOWN` olur ve önceki kilit korunur; tünelde kilit kendiliğinden açılmaz.
- **Büyük hedefler.** Dokunma hedefleri için tasarım kuralı 64–84 dp aralığıdır; birincil eylemler 72–84 dp'ye çıkar. Yeni hedefler bu aralığın altına inmemelidir. Mevcut birkaç 56 dp istisnası (ör. konum izni düğmesi, bazı arama sonuçları) ayrı bir iyileştirme konusudur.
- **Yüksek kontrast.** Gündüz ve gece paletleri ayrıdır; metin, uyarı ve harita işaretleri arka plandan net ayrılır.
- **Sığdırma.** `isNarrowScreen()` ekran genişliği 560 dp altındaysa kompakt düzeni seçer. `cappedSp()` sistem yazı ölçeğini varsayılan olarak 1.15 ile sınırlar; kadran ve dock gibi sabit alanlarda metin taşmaz.
- **Yatay gidon düzeni.** Manifest `sensorLandscape` kullanır; telefon 180° çevrildiğinde çentik tarafı değişse de içerik kaymasın diye simetrik güvenli boşluk uygulanır.
- **TR/EN.** Kullanıcıya görünen her metin ilgili `values/strings*.xml` (Türkçe) ve `values-en/strings*.xml` (İngilizce) dosyalarında birlikte güncellenir; `strings.xml`, `strings_data.xml`, `strings_nav.xml`, `strings_sos.xml` ve `strings_ui.xml` gibi gruplar vardır. Sesli yönlendirme telefon diline göre Türkçe veya İngilizce konuşur (`GuidanceLanguage`).

## 8. Test edilebilirlik

Mimari **saf mantık + ince Android kabuğu** üzerine kuruludur:

- `SpeedFilter`, `PositionSmoother`, `CrashDetector`, `SosMath`, `RouteMatcher`, `Geo`, `PlaceQuery`, `TripMath`, `MotionTracker`, `GaugeScale`, `ProviderThrottle`, `LocationArbiter` gibi sınıflar Android API'sine bağımlı değildir.
- Zaman ve sensör girdisi dışarıdan verilir: `SpeedFilter` ölçüm zamanını, `PositionSmoother` monotonic nanosaniyeyi, `NavigationController` saat ve rota/ses bağımlılıklarını, `CrashDetector` darbe/eğim sağlayıcılarını alır.
- `TripStore` `TripStorage` arayüzüyle, ağ repository'leri `ServiceHttp` ile test edilir. Gerçek servis, GPS veya SMS gerekmez.
- `LocationRepository`, `RideTrackingService`, `OverlayService`, `SosAndroid`, `TripStore` ve `Prefs` Android'e bağlı adaptörlerdir; test edilebilir mantık ayrı sınıflara taşınmıştır.

Test katmanları:

| Katman | Yer | Ne kapsar |
|---|---|---|
| JVM birim testleri | `app/src/test/java/com/ridedeck/app/` | Filtre, rota, SOS matematiği, depolama sözleşmeleri, tercihler, UI matematiği |
| Instrumentation | `app/src/androidTest/java/com/ridedeck/app/` | Compose ekranları, SQLite, servis/izin akışları, harita yaşam döngüsü |

CI şu komutu çalıştırır:

```bash
./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease
```

Burada `test` debug ve release JVM testlerini çalıştırır. Ayrı CI işi x86_64 API 35 emülatöründe `:app:connectedDebugAndroidTest` çalıştırır (cached-app freezer kapatılır); toplam 204 instrumentation testi vardır. Davranış değiştiren katkılarda ilgili JVM testini, arayüz değişikliklerinde ilgili Compose testini güncelleyin; doğrulanmamış cihaz davranışını doğrulanmış gibi anlatmayın.

## 9. Nerede ne değiştirilir?

| Görev | Önce bakılacak yer | Yanına eklenenler |
|---|---|---|
| Hız filtresi / kadran | `data/SpeedFilter.kt`, `data/LocationRepository.kt` | `SpeedFilterTest`, `SpeedRegressionTest`, `ui/SpeedCard.kt` |
| Rota, eşleme, anons | `data/NavigationController.kt`, `data/RouteRepository.kt`, `data/RouteSnap.kt` | `Geo.kt`, `GuidanceMath.kt`, `ui/MapScreen.kt`, `ui/NavCard.kt`; `NavigationControllerTest` |
| SOS / kaza algılama | `data/RideSafety.kt`, `data/Sos.kt`, `data/SosAndroid.kt` | `ui/SosUi.kt`, `ui/SettingsScreen.kt`; `SosTest`, `SosSafetyRegressionTest` |
| Yolculuk kaydı / geçmiş | `data/TripRecorder.kt`, `data/TripStore.kt`, `data/TripStats.kt` | `ui/TripsScreen.kt`, `ui/TripMap.kt`; `TripRecorderTest` (JVM), `TripStoreTest` (instrumentation) |
| Yeni ekran | `ui/DashboardScreen.kt` (yerel ekran durumu) | Yeni `ui/*.kt` bileşeni; gerekirse `DashboardViewModel.kt`; iki dilde string |
| Yeni ağ çağrısı | `data/Http.kt`/`ProviderHttp` | `data/` altında repository + `ServiceHttp` test seam'i; sağlayıcı sınırı/politikası ve gizlilik/atıf metni |
| String / çeviri | `app/src/main/res/values/strings*.xml`, `app/src/main/res/values-en/strings*.xml` | Konuşma metniyse `data/GuidanceLanguage.kt` ve TTS akışı |
| İzin / servis | `AndroidManifest.xml` | `MainActivity.kt`, `ui/DashboardScreen.kt` izin akışları, `service/`, gerekirse `RideTrackingService` |
| Harita / çevrimdışı bölge | `ui/MapScreen.kt`, `data/OfflineMaps.kt`, `data/OfflinePlan.kt` | `OfflineRegions.kt`, `ui/OfflineMapsCard.kt`; sağlayıcı politikası olmadan indirmeyi açmayın |

## 10. İlgili belgeler

| Belge | Ne için? | Durum |
|---|---|---|
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Katkı süreci, issue/PR beklentileri, dal ve commit kuralları | Güncel süreç |
| [docs/DEVELOPMENT.md](DEVELOPMENT.md) | Ortam kurulumu, derleme, test, emülatör ve sorun giderme | Güncel pratik rehber |
| [docs/TRANSLATING.md](TRANSLATING.md) | Yeni dil ekleme ve mevcut metinleri güncelleme | Güncel çeviri rehberi |
| [docs/CASE-STUDY.md](CASE-STUDY.md) | Tasarım gerekçeleri ve teknik anlatım | Güncel teknik anlatım; saha doğrulaması STATUS/RELEASE'te |
| [docs/CASE-STUDY.en.md](CASE-STUDY.en.md) | Vaka analizinin İngilizce özeti | Güncel özet |
| [docs/STATUS.md](STATUS.md) | Tarihli doğrulama ve iş günlüğü | Güncel durum/kanıt; eski bölümler tarihsel |
| [docs/RELEASE_READINESS.md](RELEASE_READINESS.md) | Yayın/saha kapıları | Güncel kapılar; tarihli alt bölümler tarihsel |
| [README.md](../README.md) | Kullanıcıya dönük özellikler ve sınırlamalar | Güncel ürün özeti |

Güncel işler, açık hatalar ve öneriler GitHub issue'larında izlenir. Eski bir belgedeki davranış ile kod çelişirse **kod ve güncel testler** esas alınır; belgeyi de düzeltin.
