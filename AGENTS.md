# RideDeck

Android Kotlin/Compose motosiklet sürüş paneli. Kaynak kod, `README.md` ve `docs/STATUS.md` güncel durum/kanıttır; eski tarihli kayıtlar tarihseldir.

## Bağlam ve belgeler

- Görevle ilgili belgeleri gerektiğinde oku: ürün özeti `README.md`, açık işler GitHub issue'ları, güncel durum/kanıt `docs/STATUS.md`, saha/yayın kapıları `docs/RELEASE_READINESS.md`, denetim takibi `docs/AUDIT-TRACKING.md`.
- Özellik veya mimari değişince ilgili `README.md` bölümünü, doğrulama/durum değişince `docs/STATUS.md` dosyasını güncelle.

## Derleme ve CI kapıları

- Gradle wrapper ve JDK 17 kullan. Hedefli geliştirmede ilgili birim testini seç; bu, tam CI veya yayın kapısının yerine geçmez.
- Kademeli CI: yalnız doküman/metadata değişikliğinde Gradle/emülatör başlatılmaz (`git diff --check` ve metadata kontrolleri çalışır). Kod değişikliğinde hızlı PR kapısı: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- Full CI (`full-ci` etiketi, `v*` etiketi, haftalık zamanlanmış koşu veya `workflow_dispatch` `full=true`): `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease`, release APK 16 KB hizalama, debug x86_64/ARM ve release ARM-only ABI, AAB, koşullu imza kontrolleri ve x86_64 API 35 emülatör instrumentation. `build`/`instrumentation` iş adları ve required-check davranışı korunur.

## Ürün, güvenlik ve doğrulama kuralları

- Sürüş güvenliği, SOS, GPS ve navigasyon davranışlarını değiştirmeden önce ilgili testleri doğrula. Gerçek cihazda denenmeyen özellikleri doğrulanmış gösterme.
- Navigasyon servislerinde mevcut anahtarsız yapıyı ve merkezi HTTP katmanını koru.
- Sürüşte yazı girişi ekleme; dokunma hedefi 64–84dp olsun (yeni hedefler altına inmesin; mevcut 56dp istisnaları ayrı iyileştirme) ve yüksek kontrast korunsun.
- Yeni arayüz metinlerini `values/strings*.xml` (İngilizce varsayılan) ve `values-tr/strings*.xml` (Türkçe) dosyalarında birlikte güncelle.
- Arayüz değişince emülatör doğrulaması yap; fiziksel cihaz ve gerçek sürüş doğrulamasını ayrı bildir. Debug APK x86_64 + ARM, release yalnız ARM'dır (`$ANDROID_HOME/emulator/emulator -avd <avd-adı> -no-snapshot`).

## Git ve yayın

- Mevcut dalı ve kullanıcı değişikliklerini koru; otomatik `switch`, `pull`, sürüm artırma, etiket veya release oluşturma.
- Test başarısızken commit/push yapma; yalnız ilgili dosyaları stage et. Canlı dağıtım veya sürüm yayınlama açık talimat gerektirir.
