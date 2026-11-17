## Özet
<!-- Ne değişti ve neden? Kullanıcıya etkisini kısaca anlat. -->

## İlgili issue
Closes #

<!-- Issue yoksa nedenini yaz. Yeni özellik, mimari veya sürüş güvenliği değişikliğinde önce issue açılmalı. -->

## Değişiklik türü
- [ ] Hata düzeltmesi
- [ ] Yeni özellik
- [ ] İyileştirme / refactor
- [ ] Belge veya çeviri
- [ ] Derleme, test veya CI
- [ ] Diğer

## Test kanıtı
<!-- Çalıştırdıklarını işaretle. Çalıştırmadıklarını ve nedenini aşağıda açıkla. -->
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew lintDebug`
- [ ] `./gradlew assembleDebug`
- [ ] Emülatörde denedim
- [ ] Fiziksel cihazda denedim
- [ ] Arayüz değiştiyse ekran görüntüsü ekledim (emülatör/cihaz bilgisiyle)
- [ ] Navigasyon, SOS veya GPS'i etkiliyorsa ilgili kritik testleri çalıştırdım

**Test ortamı ve notlar:**
<!-- Cihaz/emülatör modeli, Android sürümü, çalıştırılan kritik testler ve sonuçlar. -->

## Kontrol listesi
- [ ] Değişiklik tek konuya odaklı; ilgisiz düzeltme yok.
- [ ] Yeni davranış için birim testi ekledim veya test gerekmediğini açıkladım.
- [ ] Kullanıcıya görünen metinleri ilgili `app/src/main/res/values/strings*.xml` ve `values-en/strings*.xml` dosyalarında (ör. `strings.xml`, varsa `strings_data.xml`) birlikte güncelledim (veya metin değişikliği yok).
- [ ] Sürüş güvenliği ve motosiklet-only ilkesi korundu: sürerken yazı girişi yok, dokunma hedefleri 64–84 dp, yüksek kontrast.
- [ ] Yeni genel ağ çağrıları `app/src/main/java/com/ridedeck/app/data/Http.kt` üzerinden (MapLibre karo istekleri hariç); yeni dış servis varsa issue'da tartışıldı.
- [ ] `local.properties`, `keystore.properties`, `*.jks`, `.env` veya başka gizli dosya/anahtar commit edilmedi.
- [ ] Gerekli belgeler güncellendi (README, `docs/STATUS.md`, `CONTRIBUTING.md` vb.) veya belge değişikliği gerekmiyor.
- [ ] Log ve ekran görüntülerinde kişisel veri yok.
- [ ] CI (birim testleri, lint, APK/AAB ve instrumentation) geçti. (PR açtıktan sonra işaretle.)
