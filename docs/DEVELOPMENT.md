# Geliştirme rehberi

Bu belge RideDeck'i yerelde derlemek, test etmek ve değiştirmek için gereken pratik adımları anlatır. Katkı süreci, dal/commit kuralları ve PR beklentileri için [CONTRIBUTING.md](../CONTRIBUTING.md); kod haritası için [ARCHITECTURE.md](ARCHITECTURE.md) dosyasına bak.

RideDeck tek modüllü bir Android uygulamasıdır: Kotlin, Jetpack Compose, `minSdk 26`, `compileSdk`/`targetSdk 36`. Hesap, API anahtarı, analitik veya RideDeck sunucusu yoktur.

> **English quick start:** use JDK 17 and Android SDK 36, set `sdk.dir` in `local.properties`, then run `./gradlew testDebugUnitTest assembleDebug`. See the English quick start in [CONTRIBUTING.md](../CONTRIBUTING.md) for the PR workflow.

## 1. Gereksinimler

| Araç | Sürüm / not |
|---|---|
| JDK | **17** (Temurin veya Android Studio'nun JDK'sı) |
| Android Studio | Son kararlı sürüm (isteğe bağlı; komut satırı da yeterli) |
| Android SDK Platform | **36** |
| Android Build-Tools | **36.0.0** (CI en son yüklü build-tools'u kullanır) |
| Android Platform-Tools | `adb` için |
| Emülatör | API 35/36; x86_64 veya ARM64 system image |
| Git | Güncel bir sürüm |
| Python 3 | Yalnız APK hizalama betiği ve bazı yardımcı kontroller için isteğe bağlı |

`JAVA_HOME` JDK 17'yi göstermelidir. Kontrol:

```bash
java -version
./gradlew -version
```

Gradle wrapper depoya dahildir; ayrıca Gradle kurmana gerek yoktur.

## 2. Klonlama ve yapılandırma

Fork'unu klonla, upstream'i ekle ve SDK yolunu tanımla:

```bash
git clone https://github.com/<kullanici-adin>/ridedeck.git
cd ridedeck
git remote add upstream https://github.com/yigitbuyukeren/ridedeck.git

export JAVA_HOME=/yol/jdk-17
echo "sdk.dir=$ANDROID_HOME" > local.properties
# ANDROID_HOME boşsa doğrudan yolu yaz: echo "sdk.dir=/yol/Android/sdk" > local.properties
```

`local.properties` ve `keystore.properties` Git'e girmez. `sdk.dir` yoksa Gradle "SDK location not found" hatası verir. Android Studio projeyi açarken SDK yolunu kendisi de yazabilir.

İlk doğrulama:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Derleme ve test komutları

| Komut | Ne yapar? |
|---|---|
| `./gradlew testDebugUnitTest` | Debug JVM birim testleri |
| `./gradlew test` | Debug + release JVM birim testleri |
| `./gradlew lintDebug` | Debug lint |
| `./gradlew lintRelease` | Release lint |
| `./gradlew assembleDebug` | Debug APK |
| `./gradlew assembleRelease` | R8 ile küçültülmüş release APK; imza anahtarı yoksa imzasız çıkar |
| `./gradlew bundleRelease` | Release AAB (`app/build/outputs/bundle/release/app-release.aab`) |
| `./gradlew assembleDebugAndroidTest` | Instrumentation test APK'sını derler (cihazda çalıştırmaz) |
| `./gradlew connectedDebugAndroidTest` | Bağlı cihaz/emülatörde instrumentation testlerini çalıştırır |
| `./gradlew testDebugUnitTest lintDebug assembleDebug` | Hızlı PR kapısı: debug birim testleri, debug lint, debug APK |
| `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease` | Full CI build işinin ana Gradle komutu (debug + release) |

Debug APK `x86_64`, `arm64-v8a` ve `armeabi-v7a` native kütüphanelerini taşır; release APK/AAB yalnız ARM'dır. x86_64 AVD debug ve instrumentation için kullanılabilir.

Tek test sınıfı veya metodu:

```bash
./gradlew testDebugUnitTest --tests "com.ridedeck.app.SpeedFilterTest"
./gradlew testDebugUnitTest --tests "com.ridedeck.app.SpeedFilterTest.steadySpeedStaysSteady"
```

Tek instrumentation sınıfı:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ridedeck.app.ui.FocusIndicatorUiTest
```

Raporlar:

- Birim testleri: `app/build/reports/tests/testDebugUnitTest/index.html`
- Instrumentation: `app/build/reports/androidTests/connected/debug/index.html`
- Lint: `app/build/reports/lint-results-debug.html`, `lint-results-release.html`

CI üç kademe çalışır:

1. **Doküman/metadata-only** (`docs/`, `fastlane/`, `captures/`, `*.md`, lisans/topluluk dosyaları): Gradle ve emülatör başlatılmaz; ilgili metadata doğrulamaları ve `git diff --check` çalışır.
2. **Uygulama/kod değişikliği:** hızlı PR kapısı `testDebugUnitTest lintDebug assembleDebug` çalıştırır.
3. **Full CI:** debug + release test/lint, APK/AAB, hizalama/ABI/imza kontrolleri ve x86_64 API 35 emülatöründe instrumentation. Full CI yalnızca `full-ci` PR etiketi, `v*` etiket push'u, haftalık zamanlanmış regresyon veya `workflow_dispatch` (`full=true`) ile başlatılır.

`build` ve `instrumentation` iş adları korunur; atlanan kademe PR'ı Pending bırakmaz. Değişiklik tespiti başarısız ya da belirsizse güvenli tarafta kalınır (Gradle yolu çalışır). Emülatörde `adb shell settings put global cached_apps_freezer disabled` gerekir. `RideDeckTestRunner` test sürecini sonuçtan sonra kapatır. Toplam 209 instrumentation testi vardır; tek koşuda 208 PASS + 1 atlandı, atlanan izin testi ayrı PASS. Elle fiziksel cihaz denemesi ayrı bir doğrulamadır. Bir testi geçirmek için üretim kodunu veya test beklentisini gevşetme.

## 4. Proje düzeni

```
app/
  build.gradle.kts              Uygulama modülü, SDK sürümleri, bağımlılıklar
  src/main/java/com/ridedeck/app/
    MainActivity.kt             Tek Activity, Compose girişi
    DashboardViewModel.kt       Repository'lerin bağlandığı ViewModel
    RideDeckApplication.kt      Süreç ömürlü navigasyon/güvenlik nesneleri
    data/                       Saf mantık, repository'ler, HTTP, kalıcılık
    ui/                         Compose ekranları ve bileşenleri
    service/                    Ön plan servisi, bildirimler, çağrı ekranı
    util/                       Birimler, tercihler, paylaşım, metin yardımcıları
  src/main/res/values/          İngilizce metinler (varsayılan) ve temel kaynaklar
  src/main/res/values-tr/       Türkçe metinler
  src/test/                     JVM birim testleri
  src/androidTest/              Compose/Android instrumentation testleri
docs/                           Mimari, durum, sürüm kapıları, ekran görüntüleri
scripts/check-apk-alignment.py  Release APK 16 KB ELF hizalama kontrolü
```

Tek modül ve tek Activity vardır. Navigasyon kütüphanesi yoktur; `DashboardScreen` yerel durumla pano, harita, ayarlar, arama ve diğer ekranlar arasında geçiş yapar. Bağımlılıklar elle bağlanır; DI framework'ü yoktur.

## 5. Mimari özeti

```
LocationRepository (ham LocationManager, Play Services yok)
  └─► LocationArbiter (ekran / açık yolculuk servisi / overlay referans sayımı)
        ├─► SpeedFilter / PositionSmoother ─► hız göstergesi
        ├─► NavigationController / RideNavigation ─► rota, eşleme, sesli yönlendirme
        ├─► RideSafety (CrashDetector + SosController) ─► SOS
        └─► TripRecorder / TripStore ─► yolculuk geçmişi
DashboardViewModel = ekranın bağlama noktası (repository sahibi değil)
data/Http.kt + ProviderHttp = genel HTTP geçidi (iptal + sağlayıcı sınırı)
```

Temel ilkeler:

- **Saf mantık + ince Android kabuğu.** Hız filtresi, rota eşleme, SOS matematiği, ısınma koruması gibi çekirdekler Android API'sine bağımlı değildir; saat ve sensör girdisi dışarıdan verilir. Bu yüzden telefon olmadan test edilebilirler.
- **Tek ağ geçidi.** Nominatim, Valhalla, Open-Meteo ve Overpass çağrıları `data/Http.kt`/`ProviderHttp` üzerinden gider; tek `User-Agent`, ortak hata/iptal ve sağlayıcı bazlı eşzamanlılık/aralık vardır. MapLibre'nin harita karo istekleri motorun kendi ağ katmanındadır.
- **Ömür.** `RideSafety` ve `RideNavigation` uygulama sürecinde yaşar; ekran kapansa da başlatılmış yolculuk ve SOS devam eder. Ekran durumu `DashboardViewModel` ve Compose'dadır.
- **Yerel veri.** Yolculuklar, kayıtlı yerler ve tercihler telefonda tutulur. Rota geometrisi süreç ölümünden sonra yalnız önizleme olarak geri gelir; çevrimdışı rota hesabı yoktur.
- **Motosiklet-only.** Araç seçici veya araba/bisiklet profili yoktur. Motosiklet sabitleri (gösterge üst sınırı, kaza eşiği, durak türü) `data/VehicleProfile.kt` içindeki `ProfileConfig` üzerinden gelir; yeni bir araç profili ekleme.

Ayrıntılı paket haritası ve "nerede ne değiştirilir?" tablosu: [ARCHITECTURE.md](ARCHITECTURE.md).

## 6. Emülatörde çalıştırma

Android Studio'da bir AVD oluştur (API 35/36, **x86_64 veya ARM64 system image**). Debug APK x86_64 ve ARM yerellerini taşır; release APK/AAB yalnız ARM'dır. x86_64 AVD debug ve instrumentation için kullanılabilir. Komut satırıyla:

```bash
# Bağlı cihazları gör
adb devices

# Debug APK'yı kur
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Uygulamayı aç
adb shell am start -n com.ridedeck.app/.MainActivity
```

Uygulama yatay (sensor landscape) çalışır. İlk açılışta yalnız konum izni istenir; telefon, müzik, bildirim ve SMS izinleri ilgili özelliği açarken sorulur. Instrumentation koşusundan önce emülatör uygulama dondurucusunu kapat: `adb shell settings put global cached_apps_freezer disabled`.

### Sahte GPS

Emülatörde konum göndermek için (sıra: **boylam enlem**):

```bash
adb emu geo fix 29.0230 41.0500 30 12 15
```

Bu yalnız emülatör içindir; gerçek cihaz testinin yerine geçmez. Rota ve arama için internet gerekir. Emülatörde gerçek SMS gönderilmez; SOS akışını test ederken bunu "gerçek SMS doğrulandı" diye raporlama.

### Ekran görüntüsü ve log

```bash
adb exec-out screencap -p > /tmp/ridedeck-screen.png
adb logcat -d > /tmp/ridedeck-logcat.txt
```

Logcat ve ekran görüntülerini paylaşmadan önce konum, telefon numarası, kişi adı ve SMS içeriğini anonimleştir.

### Dil ve yazı boyutu

- **Dil:** Emülatör Ayarlar > Sistem > Diller ve giriş > Diller yolundan Türkçe/İngilizce seç.
- **Yazı boyutu:** Ayarlar > Ekran > Yazı boyutu ile 1,0×, 1,5× ve 2,0× dene.
- **Tema:** Gündüz/gece ve otomatik tema; harita, kartlar ve kontrast birlikte kontrol edilmeli.

## 7. Sık kullanılan iş akışları

### Metin ekleme veya düzeltme

1. Anahtarı ilgili `app/src/main/res/values/strings*.xml` (İngilizce, varsayılan) ve `values-tr/strings*.xml` (Türkçe) dosyasına ekle. Gruplar: `strings.xml`, `strings_data.xml`, `strings_nav.xml`, `strings_sos.xml`, `strings_ui.xml`.
2. `stringResource(R.string.yeni_anahtar)` ile kullan; kullanıcıya görünen metni koda gömme.
3. Biçim yer tutucularını (`%1$s`, `%2$d`) iki dilde aynı sırayla koru.
4. Uzun metni dar ekranda ve 2,0× yazı boyutunda dene.

### Arayüz değişikliği

1. İlgili Compose dosyasını `ui/` altında bul; ekranlar `DashboardScreen`, `MapScreen`, `SettingsScreen`, `PhoneScreen`, `TripsScreen` gibi dosyalarda.
2. Dar ekran için `isNarrowScreen()`, sabit alanlarda taşmayı önlemek için `cappedSp()` kullan.
3. Yeni dokunma hedeflerini 64–84 dp'de tut; mevcut 56 dp istisnaları ayrı iyileştirme olarak ele alınmalı. Sürerken yazı girişi ve ayar değişikliği ekleme.
4. Farklı ekran genişlikleri ve 1,0×/2,0× yazı boyutunda emülatörde kontrol et; ekran görüntüsünü PR'a ekle.
5. Davranış değiştiyse `app/src/androidTest` altına test ekle.

### Yeni ağ çağrısı

1. Önce issue'da servisi ve kullanım koşullarını tartış. Ücretsiz, anahtarsız ve uygulama kimliği gönderen bir yapı olmalı.
2. Çağrıyı `data/Http.kt` üzerinden yap; doğrudan `HttpURLConnection`/OkHttp kullanma.
3. İptal edilebilir yol gerekiyorsa `getCancellable`/`postJsonCancellable`/`postFormCancellable` kullan.
4. Zaman aşımı, HTTP hatası, boş yanıt ve eski/geç yanıt davranışını test et. Testlerde gerçek servise bağlanma; loopback veya sahte yanıt kullan.
5. Kullanıcıya gösterilen hata metnini TR/EN ekle; sessizce yutma.

### Navigasyon, GPS veya SOS değişikliği

Bu alanlar sürüş güvenliğini doğrudan etkiler.

1. Önce ilgili testleri oku ve çalıştır (`NavigationControllerTest`, `SpeedFilterTest`, `SosTest`, `SosSafetyRegressionTest`, `LocationFreshnessTest` gibi).
2. Davranışı değiştirmeden önce hatayı yakalayan testi yaz.
3. Gerçek cihazda denenmeyen davranışı doğrulanmış gibi anlatma; emülatör, sentetik veri ve gerçek sürüş ayrı kanıtlardır.
4. `docs/STATUS.md` ve `docs/RELEASE_READINESS.md` içindeki açık saha kapılarını kontrol et; kapanmış sayma.

### Yeni dil veya çeviri

[docs/TRANSLATING.md](TRANSLATING.md) rehberini izle. Yeni dil için `values-<dil>` dizini ve tüm anahtarlar gerekir; eksik anahtar varsayılan İngilizce metne düşer. Çeviriyi ana dili konuşan biriyle gözden geçir.

### Belge güncelleme

- Kullanıcıya görünen özellik/sınır değiştiyse `README.md`.
- Mimari veya geliştirme akışı değiştiyse `docs/ARCHITECTURE.md` / bu dosya.
- Tamamlanan işin kanıtı ve tarihsel durum için `docs/STATUS.md`; yayın kapıları için `docs/RELEASE_READINESS.md`.
- `docs/STATUS.md` güncel durum/kanıttır; tarihli eski bölümlerdeki test sayılarını güncel sonuç gibi sunma.

## 8. Kod stili ve kalite

- Kotlin resmi stili, 4 boşluk girinti, dosyanın mevcut dili.
- Compose'da durumu yukarı taşı (state hoisting), `collectAsStateWithLifecycle` kullan, ana iş parçacığında ağ/disk işi yapma.
- Yeni coroutine'lerde uygun scope kullan; `GlobalScope` ile süreç ömrüne bağlı iş başlatma.
- Kullanıcıya görünen metinlerde sabit string kullanma; kaynak dosyalara ekle.
- Hata durumlarını sessizce yutma; kullanıcıya kısa ve eyleme dönük mesaj ver.
- Log'a konum, telefon numarası, SMS içeriği veya kişi bilgisi yazma.
- Yorumlar "ne" değil "neden" anlatsın; çevredeki dosyanın diline uy.
- Yeni bağımlılık eklemeden önce issue'da gerekçesini ve lisansını tartış. Mevcut bağımlılıkları gereksiz yere yükseltme.
- Lint uyarılarını topluca bastırma; gerçek hatayı düzelt. Mevcut uyarı sayısını artıran yeni bir desen ekleme.

## 9. Sorun giderme

| Belirti | Çözüm |
|---|---|
| `SDK location not found` | `local.properties` içine `sdk.dir=...` yaz veya `ANDROID_HOME` ayarla. |
| `Unsupported class file major version` / derleme JDK hatası | `JAVA_HOME` ve `./gradlew -version` çıktısının JDK 17 olduğunu doğrula. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | İmza anahtarı değişmiş olabilir; eski uygulamayı kaldırıp yeniden kur. 0.5.0 → 0.6.0 geçişi de bu nedenle kaldırma gerektirir. |
| Emülatörde konum yok | `adb emu geo fix <boylam> <enlem>` gönder; uygulamanın konum izni açık mı bak. |
| Harita boş / karo yüklenmiyor | İnternet bağlantısını ve OpenFreeMap erişimini kontrol et; ilk karo yüklemesi biraz sürebilir. |
| `connectedDebugAndroidTest` cihaz bulamıyor | `adb devices` ile cihazı doğrula; emülatör açık ve yetkilendirilmiş olmalı. |
| Gradle daemon bellek hatası | `./gradlew --stop`; gerekiyorsa `gradle.properties` içindeki `-Xmx3g` değerini artır, ancak bunu PR'da gerekçelendir. |
| Tuhaf önbellek davranışı | Önce `./gradlew --stop`, sonra gerekirse `./gradlew clean`; clean'i alışkanlık haline getirme. |
| `keystore.properties` hatası | Release imzası için isteğe bağlıdır; dosyayı düzelt veya kaldır. Anahtar/parola commit etme. |
| Lint uyarıları | Hataları düzelt; uyarıları topluca `@Suppress` ile kapatma. CI lint hatalarında kırılır. |

## 10. Release ve imzalama (bakımcı)

Katkıcıların release anahtarına ihtiyacı yoktur; debug APK ve testler yeterlidir. Release süreci bakımcı sorumluluğundadır ve açık onay gerektirir.

- `keystore.properties` kökte durur: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Dosya ve `*.jks` Git'e girmez.
- Dosya yoksa `./gradlew assembleRelease` imzasız APK üretir; bu normaldir.
- Release R8 ile küçültülür. Sürüm numarası (`versionCode`/`versionName`) yalnız bakımcı tarafından, yayın öncesi artırılır.
- AAB: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- İmza doğrulaması (yalnız imzalı release APK varsa):

```bash
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
"$SDK/build-tools/36.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

- 16 KB sayfa boyutu kontrolü:

```bash
python3 scripts/check-apk-alignment.py app/build/outputs/apk/release/*.apk
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
"$SDK/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/*.apk
```

- Etiket, GitHub release, APK yayını ve mağaza dağıtımı açık talimat olmadan yapılmaz.
- CI artifact'ları: Full CI'da debug APK, androidTest APK, imzalı/imzasız release APK, release AAB, test/lint ve instrumentation raporları; hızlı PR akışında yalnızca küçük test/lint raporları.

## 11. Yardım

- Sorular ve katkı yolları: [SUPPORT.md](../SUPPORT.md)
- Katkı süreci: [CONTRIBUTING.md](../CONTRIBUTING.md)
- Güvenlik: [SECURITY.md](../SECURITY.md)
- Güncel durum ve açık saha kapıları: [STATUS.md](STATUS.md), [RELEASE_READINESS.md](RELEASE_READINESS.md)

RideDeck gönüllü bir açık kaynak projesidir; yanıt süresi garanti edilmez. Sorunu net, tekrarlanabilir ve kişisel veriden arındırılmış biçimde paylaşmak en hızlı yardım yoludur.
