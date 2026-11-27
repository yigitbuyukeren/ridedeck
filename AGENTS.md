# RideDeck

Android Kotlin/Compose araç içi sürüş paneli (CarPlay/Android Auto'dan ilham alan bağımsız pano; entegrasyon değil). Tek genel araç profili `VehicleProfile.AUTO` kullanılır; kaynak kod ve `docs/STATUS.md` güncel durum/kanıttır.

## Bağlam ve CI kapıları

- Görevle ilgili belgeler: ürün özeti `docs/FEATURES-REFERENCE.md`, açık işler GitHub issue'ları, güncel durum/kanıt `docs/STATUS.md`, saha/yayın kapıları `docs/RELEASE_READINESS.md`. Özellik/mimari değişince ilgili `docs/` belgesini güncelle.
- Gradle wrapper ve JDK 17 kullan. Kademeli CI: doküman/metadata değişikliğinde Gradle/emülatör başlatılmaz (`git diff --check` + metadata); kod değişikliğinde hızlı PR kapısı `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Full CI (`full-ci` etiketi, `v*` etiketi, haftalık koşu veya `workflow_dispatch full=true`): `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, release APK 16 KB hizalama, debug x86_64/ARM ve release ARM-only ABI, AAB, koşullu imza ve x86_64 API 35 emülatör instrumentation. `build`/`instrumentation` iş adları ve required-check davranışı korunur.

## Ürün, güvenlik ve doğrulama

- Sürüş güvenliği, SOS, GPS ve navigasyon davranışlarını değiştirmeden önce ilgili testleri doğrula; gerçek cihazda denenmeyen özellikleri doğrulanmış gösterme. Navigasyon servislerinde anahtarsız yapıyı ve merkezi HTTP katmanını koru.
- RideDeck'i CarPlay/Android Auto entegrasyonu veya projeksiyonu olarak tanıtma. Sürüşte yazı girişi ekleme; yeni dokunma hedefleri 64–84dp (mevcut 56dp istisnaları ayrı iyileştirme), yüksek kontrast korunur.
- Yeni arayüz metinlerini `values/strings*.xml` (İngilizce varsayılan) ve `values-tr/strings*.xml` (Türkçe) dosyalarında birlikte güncelle. Arayüz değişince emülatör doğrulaması yap; fiziksel cihaz/gerçek sürüş doğrulamasını ayrı bildir (debug APK x86_64 + ARM, release yalnız ARM; `$ANDROID_HOME/emulator/emulator -avd <avd-adı> -no-snapshot`).

## Git ve yayın

- Genel doğrulama, commit/push, gizli dosya ve mağaza kuralları çalışma alanı kökündeki `AGENTS.md` (`../AGENTS.md`) içindedir; doğrulanmış değişiklikleri `python3 teslim.py --deliver ridedeck --paths <dosya...> --message "<conventional>"` ile teslim et.
- Mevcut dalı ve kullanıcı değişikliklerini koru; test başarısızken commit/push yapma. Otomatik `switch`, `pull`, sürüm artırma, etiket veya release oluşturma; mağaza yayını açık talimat gerektirir.
