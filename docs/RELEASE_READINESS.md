# 1.0 yayın hazırlığı — 2026-10-29

> **11 Kasım 2026 güncellik notu:** Aşağıdaki 0.4.0/344 test ve cihaz sonuçları tarihsel denetimdir; güncel 0.6.0 (`versionCode 6`) çalışma ağacının yeniden doğrulaması değildir. Güncel uygulama/test kanıtı [STATUS.md](STATUS.md), bekleyen kabul kapıları [PLAN.md](PLAN.md) içindedir. 11 Kasım başlangıç koşusunda debug birim testleri 436/436 geçti; bu tek başına tam CI, emülatör regresyonu veya yayın onayı değildir. Arka plan SOS ve süreç sonrası hedef önizlemesi gibi davranışlar sonradan değişti; aşağıdaki tarihsel mimari sınırlamaları güncel kodun yerine kullanma. Fiziksel cihaz, gerçek SMS/arama, interkom ve yayın kapıları kapanmadı.

**11 Kasım ilk dalga son kontrolü:** Güncel 0.6.0/6 çalışma ağacında debug/release **470'er birim testi**, iki lint (0 hata, 95'er uyarı), debug/release/test APK, ELF/ZIP 16 KB statik kapıları ve **21 hedefli emülatör testi** geçti. Kanıt `captures/plan-2026-11-11/` ve STATUS'tadır; aşağıdaki tarihsel sonuçların yerine bütün cihaz matrisinin yeniden koşulduğu anlamına gelmez. Yeni çevrimdışı indirmeler sağlayıcı koşulları doğrulanamadığı için kapalıdır. Tam 43 ekran, gerçek cihaz ve çağrı/SOS saha kapıları açık; genel yayın kararı **NO-GO**.

**12 Kasım ikinci dalga ara notu:** Sonraki kaynak değişiklikleri yukarıdaki 470/21 kontrolüne dahil değildir. Yeni iz/bağlantı/hazırlık modeli için hedefli **41/41 JVM** ve debug/test APK derlemesi geçti; birleşik emülatör koşusu **38/42**, dört hata düzeltme/yeniden kabul bekliyor. Native harita testleri yerel stilli sentetik GPS verisi kullanır; gerçek çevrimdışı karo deposunu veya fiziksel GPS'i doğrulamaz. Güncel ayrıntı ve sonraki sonuçlar [STATUS.md](STATUS.md) içindedir; yayın kapıları gevşetilmedi.

**12 Kasım ikinci dalga son yerel kontrolü:** Yukarıdaki ara hatalar düzeltildikten ve küçük haritanın uyarı/kamera yerleşimi yeniden kabul edildikten sonra debug/release **526'şar JVM testi**, debug/release lint (**0 hata, 102 uyarı, 3 öneri**), debug/release/test APK ve ELF/ZIP 16 KB kontrolleri geçti. Birleşik hedefli emülatör koşusu **55/55 PASS** (63,295 sn). Kanıt `captures/plan-2026-11-12/`; kapsam/sınırlamalar STATUS'tadır. Bu tam 43 ekran, bütün instrumentation paketi, fiziksel cihaz, gerçek SMS/çağrı, kask veya yeni yayın onayı değildir. Yeni indirmeler hâlâ kapalı; **NO-GO** korunur.

**12 Kasım sağlayıcı politikası bloğu:** Yeni çevrimdışı indirme ve mevcut bölge resume **kapalı**; mevcut alanlar korunuyor. Bağlayıcı operatör metinleri [OpenFreeMap ToS](https://openfreemap.org/tos/) (indirme başına 3.000 karo/50 MB, en çok 4 paralel, uygulama UA’sı), [Nominatim OSMF politikası](https://operations.osmfoundation.org/policies/nominatim/) (tüm kullanıcılar toplamı için 1 istek/sn) ve [FOSSGIS koşulları](https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/) (`overpass-api.de`/`valhalla1.openstreetmap.de`: iki bağlantı, Valhalla 1 istek/sn, uygulama UA’sı, ticari sınır) olarak ayrıştırıldı. Overpass 10 dk/gün ve “100’e böl” metni [OSM Wiki](https://wiki.openstreetmap.org/wiki/Overpass_API) topluluk rehberidir; `X-Client-Id` [Valhalla docs](https://valhalla.github.io/valhalla/start/introduction/) önerisidir. Uygulama içi gecikme/önbellek Nominatim’in global kullanıcı toplamını kanıtlamaz; `Fuel` içindeki `kumi.systems`/`private.coffee` çifti güncel site kanıtında aynı operatördür ve sağlayıcı stratejisi onayı olmadan değiştirilmez. Yeni sağlayıcı/backend/kimlik doğrulama kullanıcı kararı olmadan eklenmez; “uyumsuzluğu kabul et” yayın seçeneği değildir. Kanıt: `captures/wave3offline/provider-policy-evidence.md`; TECH-002 hedefli **43 JVM + 5 UI + 1 native PASS** olsa da gerçek indirme/yaşam döngüsü ve tam CI kapısı açıktır.

**12 Kasım beşinci dalga final entegre yerel kapı:** Ürün kaynağı dondurulmuş Geometry + Detour VM/UI birleşiminde hedefli düzeltme kabulü **88 JVM + 27 UI PASS**; final entegre kapıda debug/release **709/709**, lint 0 hata (debug 109 / release 108 uyarı, 3'er öneri), release/test APK, iki arm64 ELF LOAD 16384 ve `zipalign -c -P 16 4` geçti (build-tools 36). Kanıt `captures/final-integrated-2026-11-12/`; önceki 187/186 ve 58/55 FAIL koşuları başarısızdı; düzeltme yalnız test fixture/import ile yapıldı ve ürün kaynağı değişmedi. Cached preview ilk 50 m ile sınırlı açık yenileme kullanır, çevrimdışı rota hesabı yoktur; Detour yalnız `≈` ve 25 m köken yeniden kullanımıyla yönlendirilmiş taban çizgisi sunar, kendiliğinden rehberlik başlatmaz. **Bu yerel kapı yayın onayı değildir:** tam 43 gerçek ekran, fiziksel cihaz/gerçek sürüş, çağrı/SMS/interkom, 16 KB runtime, uzak CI ve sağlayıcı toplu uyumluluğu **UNVERIFIED/OPEN**; yeni indirme ve resume **BLOCKED**. NO-GO ayrımı korunur.

**12 Kasım QA43 kapsamı:** `captures/plan-2026-11-12-wave4/qa43/index.json` **24 gerçek current + 16 FAKE_UI + 3 fake native/probe = 43 ID** kaydeder ve `remainingCurrentGaps` boştur; bu **43 gerçek yolculuk değildir**. Test-only `Qa43FixtureCaptureTest` build/lint **PASS** (2 test, 16 FAKE_UI); izole son-3 probe **4/4 PASS** (gerçek MapLibre long-press, yerel source-free stil, ayrı probe DB temizliği). Orchestrator 03/10/26 PNG'lerini inceledi: rozetler görünür, 26'da boş haftalık/aylık özet okunur; ancak 03 source-free boş altlık olduğu için kartografi/rota karosu/sürücü işareti kanıtlanmaz, 10'da ikincil harici uygulama etiketi `Başka uygu…` kırpılmıştır. Fixture'lar gerçek navigasyon/çağrı/SMS/medya/DB/sağlayıcı kanıtı değildir; toplu görsel PASS, tüm metinler taşmasız veya tam native yolculuk sayılmaz ve hiçbir UI/FEAT maddesi bu setle kapatılmaz.

**Karar: NO-GO (genel 1.0 yayını).** Yerel otomatik kontroller ve test APK'ları hazır. Fiziksel cihaz, interkom ve operatör doğrulamaları tamamlanmadı. Bu rapor saha güvenilirliği onayı değildir.

Denetlenen sürüm `0.4.0` / `versionCode 4`; dal `main`, HEAD `d4c1bb319495e6cfe5b427420850846f671bd68b`. Sonuçlar bu HEAD üzerindeki **commit'lenmemiş çalışma ağacına**, yeni dosyalar dahil, aittir. Önceki değişiklikler korundu; commit, push, etiket veya dağıtım yapılmadı. Sesli asistan eklenmedi.

## Navigasyon yaşam döngüsü — tamamlanan takip

`RideDeckApplication.rideNavigation` artık tek `NavigationController`, TTS, medya/telefon repository'leri, çağrı kesintisi bağlantısı ve son hedefler akışının sahibidir. `DashboardViewModel` bunları paylaşır; temizlenmesi rota işini veya TTS'yi kapatmaz. Ekran görünmüyorken rehberlik açık yolculuk servisi ve aynı GPS üzerinden ilerler. Çağrı/müzik dinleyicileri görünür ekran veya rehberlik/ara durak varken tutulur; son sahip bırakınca kaldırılır. Harita çizimi ve SOS sensörünün ekran yaşam döngüsü değişmedi.

`NavigationRideLifecycle` servis–navigasyon bağlantısını ekran collector'larından ayırır. Son varış ve kullanıcı Bitir eylemi servisi kapatır; planlı ara durakta ve başarısız devam isteğinde açık yolculuk korunur. Bildirimden Bitir, servis yok edilmesi veya takip hatası bekleyen rota/devam işini de iptal eder; eski cevap rehberliği yeniden başlatamaz. GPS/izin hatasının navigasyondaki türü korunur. Eski bitiş durumu, yeni manuel yolculuğu sorgu değişince kapatmaz.

**Önce hata üreten üç regresyon:** tekrar Başlat ilerlemeyi sıfırlayıp ayrılış anonsunu tekrarlıyordu; Bitir bekleyen aramayı iptal etmiyordu; başarısız Yola devam varış durumunu kaldırıp tekrar düğmesini kaybettiriyordu. Üçü de eski kodda FAIL görüldükten sonra düzeltildi. Toplam **11 yeni birim regresyonu** bu davranışlarla birlikte servis kapanışı, son/ara varış, devam isteğinin iptali ve izin kaybını kapsar.

İki yeni Android testi ayrı kanıtlar sağlar: gerçek Activity recreation ve Activity/ViewModel yok edilip yeniden açılmasında aynı navigasyon/medya/telefon/TTS durumu korunur; ayrıca gerçek foreground servis + sentetik GPS + sahte routing/speaker ile Activity kapalı ve ekran uyurken rehberlik ilerler, bildirimden Bitir bekleyen yeniden rotayı iptal eder. Bu ikinci test gerçek TTS sesi veya dış rota sunucusu testi değildir. **Süreç ölümü/force-stop sonrasında rota geri yükleme yoktur**; telefonun fiziksel kilit/Doze/OEM ve interkom davranışları **UNVERIFIED** kalır.

## Önceki aynı gün denetiminde düzeltilen sorunlar

API 34+ `MotoInCallService.onMuteStateChanged` yeni mikrofon durumunu saklıyor, ancak `publish()` eski `CallAudioState.isMuted` değerini okuyordu. Ekran ile sonraki `toggleMute()` kararı sistemin yeni callback'iyle uyuşmayabiliyordu. `CallMuteState` artık API 34+ için yeni callback'i, API 26–33 için eski callback'i esas alır. Gecikmiş eski callback yeni mikrofon durumunu ezemez. Üç regresyon testi sessize alma, geri açma, karışık callback sırası ve eski Android yolunu kapsar. Fiziksel mikrofonun gerçekten kapanması **UNVERIFIED**.

Gizlilik açıklamasındaki eksik OpenFreeMap harita karosu/IP ve ağ gerektiren sistem TTS veri akışları README'ye eklendi. Çevrimdışı Türkçe ses tercihi korundu; motorun ağ sesi seçilirse metni dışarıda işlemesi mümkündür. Yeni ses tanıma veya mikrofon izni yok.

## Birlikte çalışma denetimi

| Alan | İncelenen kanıt ve sonuç | Kapsam sınırı |
|---|---|---|
| Git diff ve mimari | Staged/unstaged/yeni dosyalar incelendi; staged alan boş, `git diff --check` geçti. Uygulama ömründeki `RideTracking`, ekran ve açık yolculuk için aynı GPS/filter/recorder'ı kullanıyor. | Büyük değişiklik grubu hâlâ yerelde; yalnız tracked diff taşımak yeni sınıfları/testleri kaybettirir. |
| Manifest ve izinler | Kaynak ve **paketlenmiş release manifesti** incelendi: min 26 / target 36, `allowBackup=false`; takip/overlay servisleri exported değil, location türünde. Telecom ve bildirim servisleri sistem bind izinleriyle korunuyor; FileProvider yalnız `cache/exports/` paylaşır. | Yaklaşık konum açık yolculuk için yeterli sayılmıyor. İzin kaldırılması/varsayılan dialer rolü OEM matrisi tamamlanmadı. |
| Foreground servis | Görünür ekrandaki açık Başlat/rota Başlat eylemi, token, foreground'a yükseltme, izin yarışı, timeout, flush ve kapanış yolları incelendi/test edildi. `START_NOT_STICKY`, boot receiver yok. Overlay kapalıyken de kayıt sürüyor. | Force-stop sonrası otomatik devam yok. Fiziksel Doze/OEM süreç öldürme denenmedi. Overlay kendi açık kullanıcı tercihiyle ayrı abonelik sürdürür; yolculuğu bitirmek bu tercihi kapatmaz. |
| GPS ve hız filtresi | Ölçüm zamanı/tazelik, sırasız/tekrar/NaN fix, izin/GPS kaybı, hızsız fix ve uzun kesinti testleri geçiyor; mevcut filtre fiziği korunuyor. | Test GPS'i sentetik. Gerçek GPS doğruluğu, tünel, soğuk başlangıç ve kilitli uzun sürüş **UNVERIFIED**. |
| Yolculuk/SQLite | Mevcut v1 şema korunuyor; tek IO akışı + mutex, hata kilidi, transaction, unfinished recovery, finish/delete ve hizmet bitişi ile flush testleri geçiyor. | Süreç öldürülürken son bekleyen yazının dayanıklılığı ve saat değişimi/saha büyüklüğündeki kayıtlar için tam cihaz matrisi yok. |
| Harita/navigasyon | MapView yaşam döngüsü/FPS sınırı korundu. Uygulama ömründe tek navigasyon/TTS; ViewModel yok edilmesi, servis Bitir, ara/son varış, bekleyen rota iptali ve tekrar Başlat regresyonları geçti. | Süreç ölümü/force-stop sonrası rota geri yüklenmez. Sentetik GPS/sahte routing ve speaker, fiziksel sürüş veya gerçek TTS kanıtı değildir. |
| Bluetooth/telefon/TTS | API 34+ sistem endpoint listesi, eski route yolu, kopma/timeout/eski callback, DTMF, arayan adı sahipliği, çağrı sırasında TTS ve gecikmiş müzik devamı testleri geçiyor. Bu tur mute hatası giderildi. | Gerçek HFP/A2DP/LE Audio, kask mikrofonu ve çoklu interkom geçişi yok. Yeni Bluetooth tarama/bağlanma veya SCO oturumu açılmıyor; yönlendirmeyi Telecom yapıyor. |
| SOS/SMS | Geri sayım nesli, izin/kişi tekrar kontrolü, sensör reset/gap/NaN, SENDING kilidi, kişi/parça callback, PARTIAL/UNKNOWN ve receiver/PendingIntent temizliği testleri geçiyor. Otomatik tekrar yok. | Sensör yalnız dashboard resumed iken çalışır; FGS bunu arka plan kaza algılamaya dönüştürmez. Gerçek SIM callback'i/alıcı teslimi ve kaza eşikleri **UNVERIFIED**. |
| Gizlilik | Uygulamaya ait backend/analitik yok; SQLite ve tercihler yerel. Ağ URL'leri HTTPS; loglarda numara/mesaj/koordinat basılmıyor (isteğe bağlı hız logu yalnız hız/zaman). Harita, arama, rota, hava, durak ve TTS dış servis akışları değerlendirildi. | Sistem TTS motoru ve genel servisler bağımsız sağlayıcılardır. Android/OEM cihazdan cihaza aktarım ve mağaza Data safety beyanları ayrıca doğrulanmalı. |
| Performans | DB ve ağ işleri IO'da; harita kamera/GeoJSON işi FPS sınırında ve resumed dışında duruyor; dinleyici/timer/receiver kapanışları kontrol edildi. | Pil, ısı, jank/ANR, çok saatlik kayıt ve düşük bellek ölçümü yapılmadı. Sayısal iyileşme veya pil garantisi iddia edilmiyor. |
| Native/R8 | Signed ve unsigned release derlemeleri, imza kontrolü ve 64 bit `.so` LOAD hizalaması geçti; MapLibre ve AndroidX path ARM64 LOAD segmentleri 16384. ZIP kontrolü geçti. | Emülatör sayfa boyutu 4096; gerçek 16 KB runtime **UNVERIFIED**. 32 bit MapLibre LOAD 4096; 64 bit kontrol sonucu 32 bit kütüphaneye genellenmedi. |

Release manifestine bağımlılıkların eklediği `ACCESS_WIFI_STATE` ve AndroidX dışa kapalı receiver izin tanımı da dahil edildi. `RECORD_AUDIO`, `ACCESS_BACKGROUND_LOCATION` ve Bluetooth tarama/bağlanma izni bulunmuyor.

## Gerçek doğrulama sonuçları

JDK 17 + Gradle wrapper 8.14.3 ile:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew \
  test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew connectedDebugAndroidTest
python3 scripts/check-apk-alignment.py app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
```

| Kontrol | Sonuç |
|---|---|
| Debug birim testleri | **344/344 PASS**, 0 hata/atlama |
| Release birim testleri | **344/344 PASS**, 0 hata/atlama |
| Debug / release lint | **PASS**, 0 error/fatal; 80 / 81 warning |
| Debug / R8 release / Android test APK | **PASS** |
| API 35 ARM emülatör, tam `connectedDebugAndroidTest` (`moto` AVD, Android 15) | **20/20 PASS**, 0 hata/atlama (2026-10-29 14:02; ham çıktı `/tmp/ridedeck-navigation-device-tests.log`). Yeni 2 navigasyon testi dahil. **Emülatördür, fiziksel cihaz değildir.** |
| Kapanış yeniden doğrulaması (kaynak değişmeden) | `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest` **BUILD SUCCESSFUL**; XML raporlarından debug/release **344/344**, 0 hata/atlama; lint 0 hata, 80/81 uyarı; release v2 imza, `zipalign -P 16` ve ELF64 LOAD 16384 **PASS**; `git diff --check` **PASS**. Adb'de yalnız emülatör (`emulator-5554`) bağlı; fiziksel cihaz testi yapılmadı. |
| POST_NOTIFICATIONS gerçek runtime izni reddedilmiş ayrı soğuk test (önceki denetim) | **1/1 PASS**, görünür ekrandan location FGS ve sentetik GPS; navigasyon takibinde ayrı soğuk test tekrarlanmadı |
| Release APK imzası | `apksigner verify --verbose`: **PASS**, v2 |
| Signed ve unsigned release 16 KB statik kontroller | ELF64 + `zipalign -c -P 16 4`: **PASS** |
| Hizalama kontrol aracının olumsuz örnekleri | 4 KB / 8 KB / geçersiz 24 KB hizalama ve bozuk ELF reddedildi |
| CI workflow | `actionlint 1.7.12`: **PASS**; YAML ayrıştırma geçti |
| İmzasız CI kaynak kopyası (önceki denetim) | Önceki kaynak kopyasında `keystore.properties` ve `local.properties` olmadan, SDK env ile `assembleRelease`: **PASS**. Navigasyon değişikliğini kapsayan unsigned kopya ayrıca denenmedi. |
| R8 APK emülatör başlangıcı (önceki denetim) | Önceki APK'da kurulum + cold start **PASS**. Güncel R8 APK derlendi/imzası doğrulandı; güncel APK'da runtime smoke **UNVERIFIED**. |
| Çalışma ağacı | `git diff --check`: **PASS**; dal/HEAD değişmedi |

Tam Android paketinin beklenen kapsamı 20 testtir: önceki 18 test (4 servis/GPS, 2 SQLite, 1 MapView, 5 **FakeGateway** SMS callback, 1 sahte rehber provider, 3 SOS sonuç UI, 2 sahte çağrı kontrolü UI) ve 2 yeni navigasyon yaşam döngüsü testi. Gerçek SMS gönderilmedi, arama başlatılmadı. Emülatör testi fiziksel GPS, radyo veya gerçek görüşme kanıtı sayılmadı. Mute testleri birim testidir; gerçek Telecom mikrofon testi değildir. Kod kapsamı yüzdesi ölçülmedi.

Lint uyarıları ağırlıkla KTX, log biçimi, çoğul metin adayları, bağımlılık güncellemeleri ve UI kaynaklarıdır; ayrıca ARM-only/ChromeOS ve backup/data-extraction uyarıları vardır. Uyarılar bastırılmadı veya sıfırmış gibi raporlanmadı; bu tur kanıtsız bağımlılık yükseltmesi yapılmadı.

Yerel raporlar:

- `app/build/reports/tests/testDebugUnitTest/`, `testReleaseUnitTest/`
- `app/build/reports/androidTests/connected/debug/`
- `app/build/reports/lint-results-debug.html`, `lint-results-release.html`
- `app/build/outputs/mapping/release/mapping.txt`
- Ham oturum çıktıları: `/tmp/ridedeck-readiness-validation.log`, `/tmp/ridedeck-readiness-device-tests.log`, `/tmp/ridedeck-readiness-notification-denied.log`, `/tmp/ridedeck-readiness-unsigned-release.log` (geçici dosyalar).
- Navigasyon takibinin güncel çıktıları: `/tmp/ridedeck-navigation-validation.log`, `/tmp/ridedeck-navigation-focused.log`, `/tmp/ridedeck-navigation-device-tests.log` (geçici dosyalar). Güncel birim test/lint raporları yukarıdaki `app/build/reports/` yollarındadır.

## APK'lar ve CI

**Güncel navigasyon derlemeleri:** `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, `app/build/outputs/apk/release/app-release.apk`. Release imzası, ELF64 LOAD ve ZIP 16 KB statik hizalaması bu dosyada yeniden doğrulandı.

Önceki denetimin yerel test paketi (Git dışında, ignored): `dist/readiness-2026-10-29/`. Bu anlık görüntü ve kaynak checksum listesi **yeni navigasyon değişikliklerini kapsamaz**; güncel APK yerine kullanılmamalıdır.

- `RideDeck-0.4.0-readiness-debug.apk` — cihaza kurulacak uygulama, 33.48 MiB.
- `RideDeck-0.4.0-readiness-androidTest.apk` — instrumentation eşlikçisi, 1.07 MiB; tek başına uygulama değildir.
- `RideDeck-0.4.0-readiness-release.apk` — yerel mevcut imzayla R8 APK, 9.73 MiB.
- `SHA256SUMS`, `SOURCE_SHA256SUMS`, `TESTING.txt` — dosya bütünlüğü, kaynak girdileri ve güvenli yeniden çalıştırma kapsamı.

Yeni CI `test` ile iki birim test varyantını, iki lint'i, debug/release ve Android test APK derlemesini kapsar; ELF64/ZIP hizalamasını denetler. Debug, instrumentation ve **unsigned** release APK'larını; iki test varyantının raporlarını, lint ve R8 mapping çıktısını saklar. Yetki `contents: read` ile sınırlı, elle çalıştırma girişi hazırdır. Signing anahtarı CI'a eklenmedi.

**Uzak CI: UNVERIFIED (mevcut çalışma ağacı).** Okunan son üç uzak CI koşusu başarılı; en yenisi eski `d4c1bb3` HEAD'ine aittir ve bu değişiklikleri kapsamaz. Push/dispatch yapılmadı. Yerel unsigned kaynak kopyası kontrolü Linux/GitHub koşusunun yerine geçmez. Workflow Android testlerini **derler**, emülatörde çalıştırmaz; ürünün ARM ABI'ları nedeniyle uygun ARM cihaz/emülatör doğrulaması ayrıca korunmalıdır. Bütün yeni kaynak/test/script dosyalarının ilerideki checkout'a dahil edilmesi gerekir.

## 1.0 için kapanması gereken kapılar

1. **UNVERIFIED — fiziksel sürüş:** overlay kapalı, ekran kilitli uzun GPS/kayıt; Doze ve OEM pil kısıtı; tünel/fix kaybı, GPS/izin kapatma, bildirim reddi; normal Bitir, görevden kaldırma, süreç öldürme ve yeniden açılma. DB ve servis/konum aboneliği sonuçları birlikte kontrol edilmeli.
2. **UNVERIFIED — görüşme/interkom:** gerçek Bluetooth kaskında A2DP/HFP/LE, mikrofon mute/unmute, DTMF, çağrı bekletme/bitirme, iki BT cihazı, kopma/yeniden bağlanma ve Türkçe TTS/müzik odağı. API 26–33 eski ses yolu ve API 34–36 yeni endpoint yolu ayrıca denenmeli. Full-screen intent erişimi/bildirim kapalıyken gerçek gelen çağrı sunumu da buna dahil.
3. **UNVERIFIED — SOS/operatör:** izinli kontrollü test numarası ve SIM ile tek/çoklu SIM, seçilmemiş varsayılan SIM, radyo/servis yokluğu, multipart, geciken/kayıp callback ve alıcı teslimi. Gerçek kaza/yanlış alarm oranı için saha değerlendirmesi yok. Arka plan kaza algılama desteklenmiş gibi sunulmamalı.
4. **Navigasyon saha doğrulaması:** ViewModel ömrü sınırı giderildi; açık yolculukta aynı süreç içinde rota/TTS ve servis bağlantısı ekrandan bağımsız. Fiziksel ekran kilidi/Doze/OEM, gerçek TTS/interkom ve uzun rehberlik **UNVERIFIED**. Süreç ölümü/force-stop sonrası rota geri yüklenmez; otomatik yeniden başlatma yok.
5. **UNVERIFIED — cihaz/performans:** desteklenen eski Android, Android 14/16, 16 KB runtime ve fiziksel düşük bellek/uzun sürüş pil-ısı ölçümleri. Otomatik suite API 35 / 4 KB emülatöründedir.
6. **Yayın kanıtı:** bütün çalışma ağacını içeren uzak CI koşusu ve nihai sürüm/APK izlenebilirliği. Sürüm hâlâ 0.4.0; 1.0 numarası/etiketi oluşturulmadı. Google Play hedeflenecekse SMS/arama kaydı istisnası veya uygun varsayılan rol, FGS/full-screen beyanları ve Data safety/mahremiyet beyanı ayrıca **UNVERIFIED**; APK derlenmesi mağaza uygunluğu onayı değildir.

Genel 1.0 için GO kararı bu kapılarda gerçek kanıt oluştuğunda yeniden verilmeli. Mevcut APK'lar kontrollü test için hazırdır.

## Başvurulan güncel kurallar

- Konum servisi görünür kullanıcı eyleminden başlatılır; arka plandan while-in-use servis oluşturma kısıtları [Android foreground servis kurallarına](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) göre değerlendirildi.
- API 34+ mute/endpoint callback ayrımı [InCallService API belgesinden](https://developer.android.com/reference/android/telecom/InCallService) doğrulandı.
- ELF ve ZIP kontrolleri [Android 16 KB sayfa boyutu rehberini](https://developer.android.com/guide/practices/page-sizes) izler; statik hizalama runtime testi yerine geçmez.
- [Full-screen intent erişim sınırları](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications) ve [SMS/arama kaydı istisnaları](https://support.google.com/googleplay/android-developer/answer/10208820) incelendi; mağaza onayı iddia edilmedi.
