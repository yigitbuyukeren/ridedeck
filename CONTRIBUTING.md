# RideDeck'e katkı

RideDeck, CarPlay/Android Auto'dan ilham alan bağımsız bir Android araç içi sürüş panelidir; CarPlay veya Android Auto ile entegre değildir. Kod, hata bildirimi, belge, çeviri, tasarım ve saha deneyimi katkıları değerlidir. Bu rehber, değişikliğini güvenli ve hızlı biçimde inceleyebilmemiz için gereken en az süreci anlatır.

> Kısa yol: küçük bir düzeltme için doğrudan PR açabilirsin. Yeni özellik, mimari değişiklik veya sürüş güvenliğini etkileyen bir iş için önce issue açıp yaklaşımı netleştir.

## Hızlı bağlantılar

| Belge | Ne için? |
|---|---|
| [Özellik referansı](docs/FEATURES-REFERENCE.md) | Kullanıcıya dönük özellikler, izinler ve bilinen sınırlamalar |
| [Geliştirme rehberi](docs/DEVELOPMENT.md) | Ortam kurulumu, derleme, test, emülatör ve sık kullanılan iş akışları |
| [Mimari](docs/ARCHITECTURE.md) | Paketler, veri akışı, ağ katmanı ve "nerede ne değiştirilir?" |
| [Çeviri rehberi](docs/TRANSLATING.md) | Yeni dil ekleme ve metin güncelleme kuralları |
| [Yayın hazırlığı](docs/RELEASE_READINESS.md) | 1.0/saha kapıları ve açık engeller |
| [Destek](SUPPORT.md) | Soru, geri bildirim ve hata bildirimi nereye? |
| [Güvenlik](SECURITY.md) | Güvenlik açıklarını gizli biçimde bildirme |
| [Davranış kuralları](CODE_OF_CONDUCT.md) | Topluluk beklentileri |
| [Teknik vaka analizi](docs/CASE-STUDY.md) | Tasarım kararları ve test yaklaşımı |

## Nasıl katkı sağlayabilirsin?

- **Hata bildir:** Uygulama sürümü, telefon modeli, Android sürümü ve tekrarlama adımlarıyla [hata formunu](https://github.com/yigitbuyukeren/ridedeck/issues/new?template=bug_report.yml) doldur.
- **Fikir öner:** Önce problemin kimin için ne zaman ortaya çıktığını anlat. Sürüş sırasında kullanım etkisini de ekle.
- **Kod yaz:** Küçük ve tek konulu değişiklikler en hızlı birleşenlerdir. Yeni davranış için test ekle.
- **Belge ve çeviri:** Yazım hatası, eksik adım, İngilizce/Türkçe tutarsızlık veya yeni dil desteği değerlidir.
- **Saha deneyimi paylaş:** Hangi telefonda/tablette, hangi araç ve montaj kurulumunda ve hangi koşulda denediğini yaz. Gerçek sürüş, emülatör ve masaüstü testi aynı kanıt değildir.

## Başlamadan önce

1. Aynı konuda açık bir [issue](https://github.com/yigitbuyukeren/ridedeck/issues) var mı bak. Varsa oraya ek bilgi yazmak, yeni issue açmaktan daha iyidir.
2. Yeni özellik, yeni bir dış servis, veri modeli değişikliği veya navigasyon/SOS/GPS davranışı değişikliği için önce issue aç. Böylece yanlış yönde büyük bir değişiklik yapılmaz.
3. Güvenlik açığını **herkese açık issue olarak açma**; [SECURITY.md](SECURITY.md) adımlarını izle.
4. Gerçek konum, telefon numarası, SMS içeriği, kişi listesi veya parola paylaşma. Hata ayıklama için gerekiyorsa anonimleştir.

## Geliştirme ortamı

Gereksinimler: **JDK 17**, Android SDK Platform 36 + Build-Tools 36.0.0, `adb` ve isteğe bağlı API 35/36 emülatör (x86_64 veya ARM64). Fork'unu klonlayıp `local.properties` içine `sdk.dir=...` yaz; ayrıntılı kurulum, komutlar, emülatör ve sorun giderme [geliştirme rehberinde](docs/DEVELOPMENT.md)dir.

İlk doğrulama:

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug APK x86_64 ve ARM native kütüphaneleri taşır; release APK/AAB yalnız ARM'dır.

## Proje yapısı

Tek modüllü bir Android uygulamasıdır (`:app`). Kotlin, Jetpack Compose, `minSdk 26`, `compileSdk`/`targetSdk 36`.

```
app/src/main/java/com/ridedeck/app/
  MainActivity.kt          Tek Activity, Compose girişi
  DashboardViewModel.kt    Ekran durumunun bağlama noktası
  data/                    Saf mantık, repository'ler, servisler, kalıcılık
  ui/                      Compose ekranları ve bileşenleri
  service/                 Ön plan servisi, bildirimler, çağrı ekranı
  util/                    Birimler, tercihler, paylaşım, metin yardımcıları
app/src/test/              JVM birim testleri
app/src/androidTest/       Compose/Android instrumentation testleri
```

Mimari ve veri akışı için [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) dosyasına bak.

## Kod ve tasarım kuralları

- **Tek genel araç profilini koru.** `VehicleProfile.AUTO` dışında araç seçici veya araca göre dallanan yeni davranış ekleme. Rota Valhalla `auto` costing kullanır; gösterge üst sınırı, kaza eşiği ve durak türü gibi sabitler `data/VehicleProfile.kt` içindeki `ProfileConfig` üzerinden gelir; yeni sabiti oraya ekle, koda dağınık `if` serpiştirme.
- **Sürüş güvenliği pazarlık konusu değildir.** Sürerken yazı girişi, küçük dokunma hedefi veya dikkat dağıtan animasyon ekleme. Dokunma hedefi kuralı 64–84 dp'dir; yeni hedefler bu aralığın altına inmemeli, mevcut 56 dp istisnaları ayrı iyileştirme olarak ele alınmalı. Kontrast yüksek, metinler kısa olmalı.
- **Yatay ve büyük yazıyı düşün.** Arayüz yatay araç içi düzende çalışır; dar ekran için `isNarrowScreen()`, sabit alanlarda taşmayı önlemek için `cappedSp()` kullanılır. Sistem yazı boyutu 2,0× iken de dene.
- **Kullanıcıya görünen her metin kaynak dosyada olur.** Metinler `app/src/main/res/values/strings*.xml` (İngilizce, varsayılan) ve `app/src/main/res/values-tr/strings*.xml` (Türkçe) altındadır; `strings.xml`, `strings_data.xml`, `strings_nav.xml`, `strings_sos.xml`, `strings_ui.xml` grupları vardır. Aynı anahtarı iki dilde birlikte güncelle. Çeviri için [docs/TRANSLATING.md](docs/TRANSLATING.md).
- **Saf mantığı Android kabuğundan ayır.** Hız filtresi, rota eşleme, SOS matematiği gibi çekirdekler Android API'sine bağlı olmamalı; saat/sensör girdisi dışarıdan gelmeli ve JVM testi yazılabilmeli.
- **Tek ağ geçidi.** Genel servis çağrıları `data/Http.kt`/`ProviderHttp` üzerinden yapılır (MapLibre'nin harita karo istekleri motorun kendi ağ katmanındadır). Yeni bir dış servis eklemeden önce issue'da tartış; servis ücretsiz, anahtarsız ve kullanım koşullarına uygun olmalı.
- **Veri telefonda kalır.** Hesap, analitik, reklam veya RideDeck sunucusu yoktur. Yeni bir veri toplama/aktarma yolu ekleme.
- **Gizli bilgi commit etme.** `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`, `.env` ve imza parolaları depoya girmez.
- **Yalnız kaynağı commit et.** `build/`, `.gradle/`, `captures/`, `.idea/` ve yerel yapılandırma Git'e girmez; zorla ekleme.
- **Çevredeki koda uy.** Kotlin resmi stili, 4 boşluk girinti ve dosyanın mevcut dili. İlgisiz biçimlendirme veya toplu yeniden adlandırma PR'ı zorlaştırır.

## Test ve kanıt

Değişikliğin büyüklüğüne göre en azından ilgili testleri çalıştır:

```bash
# JVM birim testleri
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# Debug APK
./gradlew assembleDebug

# Hızlı PR kapısı (debug birim testleri, debug lint, debug APK)
./gradlew testDebugUnitTest lintDebug assembleDebug

# Full CI ana Gradle komutu (debug + release test/lint, APK'lar, AAB, instrumentation derlemesi)
./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease
```

Tek bir test sınıfı:

```bash
./gradlew testDebugUnitTest --tests "com.ridedeck.app.SpeedFilterTest"
```

- Yeni mantık için `app/src/test` altında birim testi ekle. Hata düzeltiyorsan hatayı önce yakalayan bir regresyon testi yaz.
- Compose/Android davranışı için `app/src/androidTest` altında instrumentation testi ekleyebilirsin. Normal PR hattı instrumentation çalıştırmaz; Full CI (`full-ci` etiketi, `v*` etiketi, haftalık zamanlanmış regresyon veya `workflow_dispatch`) bunları x86_64 API 35 emülatöründe çalıştırır. Elle fiziksel cihaz denemesi ayrı bir kanıttır. Yerelde `./gradlew connectedDebugAndroidTest` ile çalıştır.
- Arayüz değişikliğinde ekran görüntüsü ekle. Emülatörde alınan görüntüyü "gerçek cihazda doğrulandı" diye sunma; hangi ortamda alındığını yaz.
- Testleri gevşetme, başarısız testi silme veya yalnızca testi geçirmek için üretim davranışını değiştirme.
- Navigasyon, GPS, SOS, arama ve müzik gibi kritik yolları değiştirdiysen ilgili testleri ve varsa emülatör/cihaz denemesini PR'da ayrıca belirt.

## Dal, commit ve PR

- Dalını güncel `main` üzerinden aç; PR öncesi `upstream/main` ile çakışmaları çöz.
- Dal adı örneği: `fix/route-recovery`, `feat/fuel-detour`, `docs/translation-guide`.
- Commit mesajı kısa ve emir kipinde olsun; depo geçmişi ağırlıklı olarak İngilizce `feat:`, `fix:`, `docs:`, `test:`, `refactor:` öneklerini kullanır. Örnek: `fix: keep route preview after process death`.
- Bir PR tek konuya odaklansın. İlgisiz düzeltmeleri ayrı PR'lara böl.
- PR açıklamasında **ne**, **neden** ve **nasıl test edildi** bilgisi olsun; ilgili issue'yu `Closes #123` ile bağla.
- Büyük veya tartışmalı işlerde taslak PR açıp erken geri bildirim alabilirsin.
- İnceleme sonrası geri bildirimleri yanıtla; yeni commit'ler ekle. Bakımcı istemeden force-push veya geçmişi yeniden yazma.
- `main` dalına doğrudan push, sürüm etiketi ve release bakımcıya aittir. Katkılar PR ile gelir.

## İyi bir ilk iş nasıl seçilir?

- [`good first issue`](https://github.com/yigitbuyukeren/ridedeck/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) etiketli issue'lara bak; yoksa belge düzeltmesi, çeviri, test kapsamı veya küçük bir arayüz düzeltmesi öner.
- `help wanted` dışarıdan yardım bekleyen, `accessibility` erişilebilirlik, `documentation` belge/çeviri, `question` ise soru niteliğindeki konular içindir.
- Navigasyon çekirdeği, SOS/kaza algılama, GPS ve izin akışı yüksek riskli alanlardır. Bu alanlarda çalışmadan önce issue'da yaklaşımı netleştir.
- `docs/STATUS.md` güncel durum/kanıt kaydıdır; açık işler GitHub issue'larında izlenir.
- Emin değilsen issue'ya "bunu almak istiyorum, yaklaşım şu olur mu?" diye yaz. Bu, boşa emek harcamayı önler.

## Çeviri

Mevcut arayüz dilleri Türkçe ve İngilizce'dir. Yeni dil eklemek veya mevcut metni düzeltmek için [docs/TRANSLATING.md](docs/TRANSLATING.md) rehberini izle. Çeviri PR'ında mümkünse ana dili konuşan birinin gözden geçirmesini belirt; makine çevirisini tek başına yeterli sayma.

## Davranış ve iletişim

Saygılı, yapıcı ve sabırlı ol. Bkz. [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Sorular için [SUPPORT.md](SUPPORT.md), güvenlik için [SECURITY.md](SECURITY.md).

## Lisans

Katkı yaparak kodunun ve belgenin [Apache 2.0](LICENSE) lisansı altında yayınlanmasını kabul edersin. Ayrı bir CLA veya imzalı taahhüt istenmez.

<details>
<summary><b>English quick start</b></summary>

RideDeck is a standalone Android in-vehicle driving dashboard inspired by CarPlay and Android Auto (Kotlin, Jetpack Compose, minSdk 26); it does not integrate with or project to either. Contributions are welcome.

1. Fork, clone, add `upstream`, set `sdk.dir` in `local.properties`, and use JDK 17 with Android SDK 36. Details: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
2. Keep changes focused. Add a JVM test for new logic and a Compose/instrumentation test for UI behaviour. The fast PR gate is `./gradlew testDebugUnitTest lintDebug assembleDebug`; docs-only PRs skip Gradle. The full gate (`full-ci` label, `v*` tag, weekly schedule, or `workflow_dispatch`) adds release builds and the x86_64 API 35 instrumentation suite.
3. Put user-facing text in `app/src/main/res/values/strings*.xml` (English, default) and `values-tr/strings*.xml` (Turkish). Preserve driving safety: no text input while moving, 64–84 dp targets (existing 56 dp exceptions need separate fixes), high contrast, landscape, large fonts.
4. Keep the single general `VehicleProfile.AUTO` (Valhalla `auto` costing); do not add a vehicle chooser or describe RideDeck as CarPlay/Android Auto integration. Route network calls through `data/Http.kt`/`ProviderHttp` (MapLibre tiles stay in the map engine), and do not add accounts, keys, analytics or services without an issue.
5. Never commit `local.properties`, `keystore.properties`, `*.jks`, secrets, or real locations/phone numbers/contact names/SMS content. Report vulnerabilities privately via [SECURITY.md](SECURITY.md).
6. Open a PR with the problem, the change, and test evidence; link the issue with `Closes #123`.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the code map and [SUPPORT.md](SUPPORT.md) for questions. Follow the [code of conduct](CODE_OF_CONDUCT.md). By contributing, you agree that your work is licensed under Apache 2.0.

</details>
