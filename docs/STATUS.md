# Proje durumu

Güncel kurallar kökteki `AGENTS.md`, saha/yayın kapıları [RELEASE_READINESS.md](RELEASE_READINESS.md) ve denetim maddeleri [AUDIT-TRACKING.md](AUDIT-TRACKING.md) içindedir. Aşağıdaki tarihli kayıtlar yeniden çalıştırılmış test sonucu değildir.

> **Kanıt yolları:** Metindeki `/tmp/...` yolları yerel kanıt dosyalarıdır; repoda yer almaz.

**Son güncelleme:** 2026-11-18

## Güncel özet

- **Sürüm ve yayın:** `versionName 0.7.0`, `versionCode 8` (`app/build.gradle.kts`); imzalı `v0.7.0` kararlı (prerelease değil) GitHub sürümü 2026-11-18'de yayımlandı. Release imzası 2026-11-08'de yenilendi (`0.5.0`'dan geçiş için kaldırıp kurma gerekir).
- **Son doğrulama (18 Kasım 2026):** Debug + release JVM **848/848** (0 hata, 0 atlama), lint **0 hata** (debug 105 / release 104 uyarı, 3'er öneri), x86_64 API 35 instrumentation **209 testte 208 PASS + 1 atlandı** (atlanan izin testi ayrı **1/1 PASS**), 16 KB statik ELF/ZIP kontrolü **PASS**.
- **Ürün kapsamı:** Yalnız motosiklet içindir (araç seçimi yok; eski araç/asistan/widget verisi göç kodu kaldırıldı). Ayarlar 6 bölüme ayrılmıştır (Genel, Görünüm, Harita, Ses ve bildirimler, Güvenlik, İzinler ve sistem).
- **Çevrimdışı harita:** Yeni çevrimdışı indirmeler sağlayıcı uyumluluğu doğrulanana kadar durduruldu; önceden indirilmiş alanlar korunur.
- **Açık kapılar:** Fiziksel cihaz, gerçek sürüş/GPS, gerçek SMS/arama/interkom, Doze/OEM, fiziksel kilit ekranı, 16 KB runtime, mağaza/Data safety ve sağlayıcı toplam kotası **UNVERIFIED/OPEN**; kamuya açık 1.0 kararı **NO-GO**.

## 2026-11-18 — v0.7.0 çalışma zamanı dil geçişi

**Kapsam:** Android uygulama dili (sistem veya Android 13+ uygulama dili menüsü) süreç yaşarken değiştiğinde TTS ve navigasyonun yeni dili kullanması sağlandı. Sürüm `0.7.0` / `versionCode 8`; `v0.6.0`, `v0.6.1-beta.1` ve önceki release'ler değiştirilmedi.

**Düzeltme:**

- `GuidanceLanguage` artık uygulamanın `Resources.configuration` yerelini okuyor; Android 13+ uygulama dili, sistem dilinden farklı olsa da doğru çözülüyor (`tr` → `tr-TR`, diğer tüm diller → `en-US`).
- `Voice.applyLanguage` canlı `TextToSpeech` motorunu yeniden oluşturmadan yeni locale ve sesle hedefliyor; mevcut anonsu durduruyor. Aktif `SpeechPriority.SOS` anonsu kesilmiyor; dil değişikliği anons bitince uygulanıyor.
- `RideNavigation`, `Application` yapılandırma callback'ini dinleyip TTS'i hedefliyor ve navigasyonu tetikliyor.
- `NavigationController.onGuidanceLanguageChanged` rota metinlerini yeni dilde yeniden istiyor; başarısız yenileme eski rotayı kullanılabilir bırakıyor, duraklar `stopsPassed` ile yeniden hizalanıyor.
- `RouteRepository` rota, Nominatim arama ve ters geocode isteklerinde uygulama dilini kullanıyor; TTS'te konuşulan ondalık ayırıcı uygulama diline bağlandı.

**Testler:**

| Kontrol | Sonuç |
|---|---|
| Debug + release JVM | Her varyantta **848/848 PASS**, 0 hata, 0 atlama |
| Lint | **0 hata**; debug **105**, release **104** uyarı, 3'er öneri |
| Instrumentation | **209 testte 208 PASS + 1 atlandı, 0 hata**; atlanan izin testi ayrı `am instrument` koşusunda **1/1 PASS** |
| Dil fallback | TR/EN ve DE/FR → İngilizce; `GuidanceLanguage`, `TtsVoicePolicy`, rota isteği ve konuşma mesafesi testleri PASS |
| Emülatör dil geçişi | `en-US` → `tr-TR` sırasında PID değişmedi; arayüz `Konum izni gerekli` / `km/sa` metinlerine geçti; çökme yok |

**Yayın:** `RideDeck-0.7.0.apk` aynı v2 imza sertifikasıyla imzalandı; sertifika SHA-256 `9d9164ef8c679f2909e2a851d1327839c684cad238ca0932488a9f8d0f622427`, APK SHA-256 `08e264b2c6a1235418aa89ed4646074dca71677a4ca01e5ea031811d084b845d`.

**UNVERIFIED:** Gerçek TTS sesinin kulakla TR/EN doğrulanması, fiziksel cihaz/gerçek sürüş/GPS, gerçek SMS/arama/interkom, Doze/OEM ve kilit ekranı, 16 KB runtime, mağaza/Data safety ve sağlayıcı toplam kotası.

## 2026-11-18 — İngilizce varsayılan dil ve Android 13+ dil seçimi (PR #14)

- Varsayılan kaynaklar İngilizceye taşındı (`values/`), Türkçe `values-tr/` altına alındı, `values-en/` kaldırıldı. Desteklenmeyen diller (DE/FR) İngilizceye düşer.
- `res/xml/locales_config.xml` ve `android:localeConfig` eklendi (Android 13+ uygulama dili menüsünde `en`/`tr`).
- 5 grupta toplam **470** string/plurals anahtarı iki dilde birebir eşleşir; yer tutucu farkı yoktur.
- Sonuçlar: Debug+release JVM **832/832**, lint **0 hata**, x86_64 API 35 instrumentation **207 PASS + 1 atlandı** (atlanan izin testi ayrı **1/1 PASS**), uzak CI `build` + `instrumentation` **PASS**.

## 2026-11-17 — 1.0 denetim düzeltme dalgası (PR #13)

- **SOS:** Kilit ekranı üstünde bildirim, tam ekran bildirim (FSI), uyandırma kilidi, bildirimden **İyiyim**/**Kapat** eylemleri, SMS izni/SIM hazırlığı. Gerçek SIM/SMS/OEM FSI **doğrulanmadı**.
- **Navigasyon:** Yanlış yön/U dönüşü algılama, erken varış koruması, rota tazeliği, merkezi `ProviderHttp` hız sınırlama, hava/hız limiti hata geri çekilmesi.
- **Servis:** Ön plan servisi yarışı düzeltmesi, referans sayımlı tek GPS akışı (`LocationArbiter`), `Palette` hesabının ana iş parçacığından çıkarılması.
- **Gizlilik/veri:** `MessagePrivacy` kilit ekranı politikası, `dataExtractionRules`, toplu yolculuk silme, SQLite v2 indeks geçişi ve bozulma yedeği.
- **Sağlayıcı koşulları:** Nominatim (global 1 istek/sn, UA), FOSSGIS/Valhalla (2 bağlantı, 1 istek/sn, UA, `X-Client-Id`), OpenFreeMap (3.000 karo / 50 MB / 4 paralel; indirmeler kapalı). Uygulama içi throttle global uyumu kanıtlamaz (**BLOCKED/UNVERIFIED**).
- Sonuçlar: Debug+release JVM **831/831**, lint **0 hata**, instrumentation **203 PASS + 1 atlandı** (izin testi ayrı **1/1 PASS**), 16 KB statik ELF/ZIP **PASS**.

## Tarihsel kayıtlar

12 Kasım 2026 ve öncesi geliştirme dalgaları (`0.4.0–0.6.0`, 29 Ekim denetimi, 11–12 Kasım yerel kabul dalgaları ve Ekim 2026 günlükleri) Git geçmişinde korunmaktadır. Güncel karar ve açık kapılar için [RELEASE_READINESS.md](RELEASE_READINESS.md) ve [AUDIT-TRACKING.md](AUDIT-TRACKING.md) dosyalarına bakın.
