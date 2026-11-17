# RideDeck'e katkı

RideDeck, motosiklet kullanıcıları için bir Android sürüş panelidir. Kod, hata bildirimi, belge, çeviri, tasarım ve saha deneyimi katkıları değerlidir. Bu rehber, değişikliğini güvenli ve hızlı biçimde inceleyebilmemiz için gereken en az süreci anlatır.

> Kısa yol: küçük bir düzeltme için doğrudan PR açabilirsin. Yeni özellik, mimari değişiklik veya sürüş güvenliğini etkileyen bir iş için önce issue açıp yaklaşımı netleştir.

## Hızlı bağlantılar

| Belge | Ne için? |
|---|---|
| [Geliştirme rehberi](docs/DEVELOPMENT.md) | Ortam kurulumu, derleme, test, emülatör ve sık kullanılan iş akışları |
| [Mimari](docs/ARCHITECTURE.md) | Paketler, veri akışı, ağ katmanı ve "nerede ne değiştirilir?" |
| [Çeviri rehberi](docs/TRANSLATING.md) | Yeni dil ekleme ve metin güncelleme kuralları |
| [Destek](SUPPORT.md) | Soru, geri bildirim ve hata bildirimi nereye? |
| [Güvenlik](SECURITY.md) | Güvenlik açıklarını gizli biçimde bildirme |
| [Davranış kuralları](CODE_OF_CONDUCT.md) | Topluluk beklentileri |
| [Teknik vaka analizi](docs/CASE-STUDY.md) | Tasarım kararları ve test yaklaşımı |

## Nasıl katkı sağlayabilirsin?

- **Hata bildir:** Uygulama sürümü, telefon modeli, Android sürümü ve tekrarlama adımlarıyla [hata formunu](https://github.com/yigitbuyukeren/ridedeck/issues/new?template=bug_report.yml) doldur.
- **Fikir öner:** Önce problemin kimin için ne zaman ortaya çıktığını anlat. Sürüş sırasında kullanım etkisini de ekle.
- **Kod yaz:** Küçük ve tek konulu değişiklikler en hızlı birleşenlerdir. Yeni davranış için test ekle.
- **Belge ve çeviri:** Yazım hatası, eksik adım, İngilizce/Türkçe tutarsızlık veya yeni dil desteği değerlidir.
- **Saha deneyimi paylaş:** Hangi telefonda, hangi motosiklet/ekipman kurulumunda ve hangi koşulda denediğini yaz. Gerçek sürüş, emülatör ve masaüstü testi aynı kanıt değildir.

## Başlamadan önce

1. Aynı konuda açık bir [issue](https://github.com/yigitbuyukeren/ridedeck/issues) var mı bak. Varsa oraya ek bilgi yazmak, yeni issue açmaktan daha iyidir.
2. Yeni özellik, yeni bir dış servis, veri modeli değişikliği veya navigasyon/SOS/GPS davranışı değişikliği için önce issue aç. Böylece yanlış yönde büyük bir değişiklik yapılmaz.
3. Güvenlik açığını **herkese açık issue olarak açma**; [SECURITY.md](SECURITY.md) adımlarını izle.
4. Gerçek konum, telefon numarası, SMS içeriği, kişi listesi veya parola paylaşma. Hata ayıklama için gerekiyorsa anonimleştir.

## Geliştirme ortamı

Gereksinimler:

- **JDK 17** (Temurin veya Android Studio'nun getirdiği JDK 17)
- **Android Studio** (son kararlı sürüm) veya komut satırı araçları
- **Android SDK Platform 36** ve **Build-Tools 36.0.0**
- **Android SDK Platform-Tools** (`adb`) ve isteğe bağlı bir emülatör (API 35/36)
- Git

Fork'unu klonla ve upstream'i ekle:

```bash
git clone https://github.com/<kullanici-adin>/ridedeck.git
cd ridedeck
git remote add upstream https://github.com/yigitbuyukeren/ridedeck.git
```

`local.properties` dosyasına SDK yolunu yaz (bu dosya Git'e girmez):

```properties
sdk.dir=/yol/Android/sdk
```

İlk derleme ve test:

```bash
export JAVA_HOME=/yol/jdk-17
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Ayrıntılı kurulum, emülatör, sahte GPS, tek test çalıştırma ve sorun giderme için [geliştirme rehberine](docs/DEVELOPMENT.md) bak.

## Proje yapısı

Tek modüllü bir Android uygulamasıdır (`:app`). Kotlin, Jetpack Compose, `minSdk 26`, `compileSdk`/`targetSdk 36`.

```
app/src/main/java/com/ridedeck/app/
  MainActivity.kt          Tek Activity, Compose girişi
  DashboardViewModel.kt    Repository'lerin bağlandığı yer
  data/                    Saf mantık, repository'ler, servisler, kalıcılık
  ui/                      Compose ekranları ve bileşenleri
  service/                 Ön plan servisi, bildirimler, çağrı ekranı
  util/                    Birimler, tercihler, paylaşım, metin yardımcıları
app/src/test/              JVM birim testleri
app/src/androidTest/       Compose/Android instrumentation testleri
```

Mimari ve veri akışı için [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) dosyasına bak.

## Kod ve tasarım kuralları

- **Motosiklet odaklı kal.** Araç seçici, araba/bisiklet profili veya araca göre dallanan yeni davranış ekleme. Uygulama tek bir motosiklet deneyimi sunar. Gösterge üst sınırı, kaza eşiği ve durak türü gibi motosiklet sabitleri `data/VehicleProfile.kt` içindeki `ProfileConfig` üzerinden gelir; yeni sabiti oraya ekle, koda dağınık `if` serpiştirme.
- **Sürüş güvenliği pazarlık konusu değildir.** Sürerken yazı girişi, küçük dokunma hedefi veya dikkat dağıtan animasyon ekleme. Dokunma hedefleri 64–84 dp, kontrast yüksek, metinler kısa olmalı.
- **Yatay ve büyük yazıyı düşün.** Arayüz yatay gidon düzeninde çalışır; dar ekran için `isNarrowScreen()`, sabit alanlarda taşmayı önlemek için `cappedSp()` kullanılır. Sistem yazı boyutu 2,0× iken de dene.
- **Kullanıcıya görünen her metin kaynak dosyada olur.** Metinler `app/src/main/res/values/strings*.xml` (Türkçe) ve `app/src/main/res/values-en/strings*.xml` (İngilizce) altındadır; çoğu metin `strings.xml`, veri ekranları gibi ayrı gruplar `strings_data.xml` içindedir. Aynı anahtarı iki dilde birlikte güncelle. Çeviri için [docs/TRANSLATING.md](docs/TRANSLATING.md).
- **Saf mantığı Android kabuğundan ayır.** Hız filtresi, rota eşleme, SOS matematiği gibi çekirdekler Android API'sine bağlı olmamalı; saat/sensör girdisi dışarıdan gelmeli ve JVM testi yazılabilmeli.
- **Tek ağ çıkışı.** Genel servis çağrıları `data/Http.kt` üzerinden yapılır. Yeni bir dış servis eklemeden önce issue'da tartış; servis ücretsiz, anahtarsız ve kullanım koşullarına uygun olmalı.
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

# CI build işindeki ana Gradle komutu (debug + release test/lint, APK'lar, AAB, instrumentation derlemesi)
./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease
```

Tek bir test sınıfı:

```bash
./gradlew testDebugUnitTest --tests "com.ridedeck.app.SpeedFilterTest"
```

- Yeni mantık için `app/src/test` altında birim testi ekle. Hata düzeltiyorsan hatayı önce yakalayan bir regresyon testi yaz.
- Compose/Android davranışı için `app/src/androidTest` altında instrumentation testi ekleyebilirsin. CI bu testleri API 35 x86_64 emülatörde çalıştırır; elle emülatör/cihaz denemesi yine ayrı bir kanıttır. Yerelde `./gradlew connectedDebugAndroidTest` ile çalıştır.
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
- `docs/STATUS.md` tarihsel durum ve kanıt kaydıdır; güncel görev listesi değildir. Güncel işler açık issue'larda izlenir.
- Emin değilsen issue'ya "bunu almak istiyorum, yaklaşım şu olur mu?" diye yaz. Bu, boşa emek harcamayı önler.

## Çeviri

Mevcut arayüz dilleri Türkçe ve İngilizce'dir. Yeni dil eklemek veya mevcut metni düzeltmek için [docs/TRANSLATING.md](docs/TRANSLATING.md) rehberini izle. Çeviri PR'ında mümkünse ana dili konuşan birinin gözden geçirmesini belirt; makine çevirisini tek başına yeterli sayma.

## Davranış ve iletişim

Saygılı, yapıcı ve sabırlı ol. Bkz. [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Sorular için [SUPPORT.md](SUPPORT.md), güvenlik için [SECURITY.md](SECURITY.md).

## Lisans

Katkı yaparak kodunun ve belgenin [Apache 2.0](LICENSE) lisansı altında yayınlanmasını kabul edersin. Ayrı bir CLA veya imzalı taahhüt istenmez.

<details>
<summary><b>English quick start</b></summary>

RideDeck is a motorcycle-only Android dashboard (Kotlin, Jetpack Compose, minSdk 26). Contributions are welcome.

1. Fork, clone, add `upstream`, and set `sdk.dir` in `local.properties`.
2. Use JDK 17 and Android SDK 36. Build with `./gradlew testDebugUnitTest assembleDebug`.
3. Keep changes focused. Add a JVM test for new logic; add a Compose/instrumentation test when UI behavior changes. CI runs the instrumentation suite on an API 35 x86_64 emulator; a physical-device check is still separate evidence.
4. Update the relevant `app/src/main/res/values/strings*.xml` and `values-en/strings*.xml` files (for example `strings.xml` and `strings_data.xml`) for user-facing text.
5. Preserve the driving-safety rules: no text input while riding, 64–84 dp touch targets, high contrast, landscape layout, and large-font support.
6. Route public network calls through `app/src/main/java/com/ridedeck/app/data/Http.kt`. Do not add accounts, API keys, analytics, or a new external service without an issue.
7. Never commit `local.properties`, `keystore.properties`, `*.jks`, or secrets. Report vulnerabilities privately via [SECURITY.md](SECURITY.md).
8. Open a PR with the problem, the change, and test evidence. Link the issue with `Closes #123`.

Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for setup, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the code map, and [SUPPORT.md](SUPPORT.md) for where to ask questions. Follow the [code of conduct](CODE_OF_CONDUCT.md). By contributing, you agree that your work is licensed under Apache 2.0.

</details>
