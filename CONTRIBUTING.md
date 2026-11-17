# Katkı rehberi

RideDeck'e katkılarını bekliyoruz. Kod, hata bildirimi, fikir ve çeviri hepsi değerli.

## Başlamak
1. Depoyu fork'la ve klonla.
2. Android SDK ve JDK 17 kurulu olmalı. `local.properties` dosyasına `sdk.dir=/yol/Android/sdk` yaz (git'e girmez).
3. Derle ve test et:
   ```bash
   export JAVA_HOME=/yol/jdk-17
   ./gradlew test lintDebug assembleDebug
   ```
4. APK: `app/build/outputs/apk/debug/app-debug.apk`. Emülatörde veya telefonda dene. Sahte GPS için emülatörde `adb emu geo fix <boylam> <enlem> <irtifa> <uydu> <hız>` kullanabilirsin. Instrumentation için emülatör uygulama dondurucusunu kapat: `adb shell settings put global cached_apps_freezer disabled`, sonra `./gradlew :app:connectedDebugAndroidTest`.

## Pull request açmak
- Küçük ve tek konulu PR'lar tercih edilir. Büyük bir değişiklik için önce issue aç.
- Yeni mantık için birim testi ekle (`app/src/test`).
- Arayüz değişikliğinde ekran görüntüsü ekle.
- Kod stili: Kotlin resmi stili, çevredeki kodun diliyle uyumlu ol.
- PR'ın CI'dan (derleme ve test) geçmeli.

## Sürüş için tasarım ilkeleri
- Yalnız motosiklet için tasarlıyoruz; araç/bisiklet profili yoktur. Sürüş sırasında yazı girişi yok. Büyük dokunma hedefleri (64-84dp), eldivenle kullanılabilir.
- Motosiklete özel sabitler ve saf mantık Android'den ayrı tutulur; yeni davranışı koda `if` serpiştirmeden ilgili saf sınıfa/teste ekle.
- Arayüz yatay gidon düzenine sabittir; yeni ekranlar sistem yazı boyutu büyükken taşmamalı (sabit alanlarda `cappedSp`).
- Düşük dikkat gerektirir: kısa metin, yüksek kontrast, tek dokunuşla işlem.
- Ağ servisleri ücretsiz ve anahtarsız olmalı; merkezi `ProviderHttp` sınırını atlayan yeni bir çağrı ekleme. Yeni bir harici servis eklemeden önce issue'da tartış.

## Nereden başlamalı?
Güncel durum ve açık saha/yayın kapıları [docs/STATUS.md](docs/STATUS.md) ve [docs/RELEASE_READINESS.md](docs/RELEASE_READINESS.md) içindedir. Başlamadan önce bir issue aç.

## Davranış kuralları
Saygılı ol. Bkz. [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Lisans
Katkı yaparak kodunun [Apache 2.0](LICENSE) altında yayınlanmasını kabul edersin.
