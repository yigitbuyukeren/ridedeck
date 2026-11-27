# 1.0 yayın hazırlığı — güncel durum

> **Ürün kapsamı (2026-11-27):** RideDeck, CarPlay/Android Auto'dan ilham alan bağımsız bir Android araç içi sürüş panelidir; CarPlay veya Android Auto ile entegre değildir. Tek genel araç profili `VehicleProfile.AUTO` kullanılır (Valhalla `auto`, 240 km/sa gösterge, 5,0 g kaza eşiği, 30 km/sa hareket eşiği, eğim koşulu yok, yalnız benzin durağı).

> **Güncel durum (2026-11-27):** Ürün sürümü `0.7.1` / `versionCode 9`; imzalı `v0.7.1` kararlı APK'sı `prerelease=false` olarak yayımlandı. 1.0 sürümü, etiketi veya mağaza yayını yoktur. Tarihli test ayrıntıları [STATUS.md](STATUS.md) içindedir.

## 27 Kasım 2026 — v0.7.1 sonrası doğrulama

- **Kod:** `v0.7.1` (`d22cf0a`) sonrasında `7aadca8` bağımsız araç içi sürüş paneli refactor'ü `main`'e alındı; takip eden lint/test düzeltmeleri ve yeni `DrivingLogicTest` (+14 test) `3af1c18` commit'indedir. Bu değişiklikler yeni sürüm/`versionCode`, etiket veya release artefaktı üretmedi.
- **Yerel doğrulama:** Debug JVM **865/865 PASS** (117 test dosyası, 0 hata, 0 atlama), `lintDebug` **0 hata**, `assembleDebug` + `assembleDebugAndroidTest` **PASS**, fastlane metadata **PASS**, hedefli x86_64 API 35 emülatör instrumentation **26/26 PASS**.
- **PENDING:** Release varyantı (`lintRelease`, `assembleRelease`, `bundleRelease`), 16 KB/ABI/imza paketi, tam emülatör instrumentation matrisi, fiziksel cihaz/gerçek sürüş ve diğer 1.0 saha kapıları bu refactor sonrası yeniden doğrulanmadı.

## v0.7.1 yayın snapshot'ı (26 Kasım 2026)

> Bu sonuçlar `v0.7.1` etiketi (`d22cf0a`) içindir; sonraki refactor sonrasında release varyantı ve tam instrumentation yeniden çalıştırılmadı.

- **Debug + release JVM birim testleri:** Her varyantta **848/848 PASS** (toplam 1.696), 0 hata, 0 atlama.
- **Lint:** **0 hata**; debug **105**, release **104** uyarı, 3'er öneri; uyarılar bastırılmadı.
- **Release APK ve AAB:** ARM-only, v2 imzalı; sertifika SHA-256 `9d9164ef8c679f2909e2a851d1327839c684cad238ca0932488a9f8d0f622427`; APK SHA-256 `6922b84fe938c28649d429eb2dbd8dc7ed60443c48d1ba4f64ab9afa1300c1fa`; `zipalign -c -P 16 4`, 16 KB ELF LOAD ve fastlane metadata (`changelogs/9.txt`) **PASS**.
- **Instrumentation:** Bu turda yerelde yeniden çalıştırılmadı; `v0.7.1` etiketi uzak Full CI x86_64 API 35 emülatör koşusunu tetikler. Son tam yerel koşu `v0.7.0` / 18 Kasım 2026: **209 testte 208 PASS + 1 atlandı**, atlanan izin testi ayrı **1/1 PASS** (toplam 209 doğrulandı).
- **Bilinen sınırlama:** Gerçek TTS ses çıkışı, fiziksel cihaz, gerçek sürüş/SMS/Bluetooth ses (eller serbest), Doze/OEM, fiziksel kilit ekranı ve 16 KB runtime **UNVERIFIED**.

## 18 Kasım 2026 — v0.7.0 doğrulama sonuçları

Debug + release JVM **848/848 PASS** (toplam 1.696); lint **0 hata**; **209 instrumentation testi** (208 PASS + 1 atlandı, atlanan izin testi ayrı **1/1 PASS**); `en-US` → `tr-TR` geçişinde PID değişmedi. Release APK SHA-256 `08e264b2c6a1235418aa89ed4646074dca71677a4ca01e5ea031811d084b845d`; ayrıntı ve diğer tarihli sonuçlar [STATUS.md](STATUS.md) içindedir.

## CI kapıları (`.github/workflows/ci.yml`)

- **Doküman/metadata-only PR:** Gradle/emülatör başlatılmaz; metadata doğrulamaları ve `git diff --check` çalışır.
- **Kod PR (hızlı kapı):** `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- **Full kapı (`full-ci`, `v*`, haftalık, `workflow_dispatch` `full=true`):** `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, 16 KB ELF/ZIP hizalama, debug x86_64/ARM ve release ARM-only ABI, AAB, koşullu imza ve x86_64 API 35 emülatör instrumentation (`build` ve `instrumentation` iş adları korunur).

## 1.0 kapı tablosu

| 1.0 kapısı | Durum | Not |
|---|---|---|
| Fiziksel cihaz, gerçek GPS/sürüş, ekran kilidi/Doze/OEM | **OPEN/UNVERIFIED** | Emülatör ve sentetik GPS yerine geçmez |
| Gerçek SIM/SMS/operatör, gerçek arama, Bluetooth ses/eller serbest | **OPEN/UNVERIFIED** | Sahte gateway/emülatör teslim kanıtı değildir |
| Kaza algılama eşikleri ve yanlış alarm oranı | **OPEN/UNVERIFIED** | Saha verisi gerekir |
| 16 KB runtime | **OPEN/UNVERIFIED** | Yalnız statik ELF/ZIP kontrolü var |
| Pil/ısı/jank/ANR ölçümü (araç içi montaj) | **OPEN/UNVERIFIED** | Fiziksel cihaz ölçümü gerekir |
| Sağlayıcı toplam (global) kota uyumu | **BLOCKED/UNVERIFIED** | Uygulama içi throttle global kanıt değildir; yeni indirme/resume kapalı |
| Bağımlılık/supply-chain doğrulaması | **KISMİ/OPEN** | Sürümler sabit; dependency verification yok |
| Tam ekran/TalkBack/64dp matrisi | **KISMİ** | Hedefli testler var; tüm ekranlar, gerçek TalkBack/D-pad ve tam 64dp taraması yok |
| Mağaza/Data safety, lisans/ticari karar | **OPEN** | Ayrı ürün/mağaza kararı |
| Uzak CI ve 1.0 yayın izlenebilirliği | **OPEN** | Push/merge/release açık talimat gerektirir |

**Karar: kamuya açık 1.0 için NO-GO.** Koşullu beta ancak yukarıdaki saha kapıları gerçek cihaz/gerçek SMS/gerçek sürüş kanıtıyla kapandıktan sonra değerlendirilebilir.

## Tarihsel denetimler

29 Ekim 2026 (`0.4.0`), 11–12 Kasım 2026 (`0.6.0` dalgaları), 17 Kasım 2026 (`PR #13`, 831/831 JVM) ve eski 1.0 denetim takip tablosunun ayrıntıları Git geçmişinde; özet [STATUS.md](STATUS.md) içindedir. Bu belge güncel kapıları tekilleştirir; eski tarihli kayıtlar güncel sonuç gibi okunmamalıdır.

## Başvurulan kurallar

- Konum servisi görünür kullanıcı eyleminden başlatılır; arka plandan while-in-use servis oluşturma kısıtları [Android foreground servis kurallarına](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) göre değerlendirildi.
- API 34+ mute/endpoint callback ayrımı [InCallService API belgesinden](https://developer.android.com/reference/android/telecom/InCallService) doğrulandı.
- ELF ve ZIP kontrolleri [Android 16 KB sayfa boyutu rehberini](https://developer.android.com/guide/practices/page-sizes) izler; statik hizalama runtime testi yerine geçmez.
- [Full-screen intent erişim sınırları](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications) ve [SMS/arama kaydı istisnaları](https://support.google.com/googleplay/android-developer/answer/10208820) incelendi; mağaza onayı iddia edilmedi.
