# RideDeck 1.0 denetim takip tablosu — 2026-11-17

Bu tablo, iki kapsamlı denetimin ve 17 Kasım 2026 kullanıcı talebinin tekilleştirilmiş
bulgu listesidir. Durumlar kod, test veya kanıtla güncellenir. "Kapalı" yalnız ilgili
doğrulama gerçekten çalıştığında kullanılır; gerçek cihaz/gerçek SMS/gerçek sürüş
gerektiren maddeler ayrıca **ENGEL** olarak işaretlenir.

| ID | Öncelik | Alan | Bulgu | Durum | Kanıt / not |
|---|---|---|---|---|---|
| P0-1 | P0 | Saha | Fiziksel cihaz, gerçek GPS/Doze/OEM, gerçek çağrı/SMS/interkom ve 16 KB runtime doğrulanmadı | ENGEL | Emülatör/sentetik test bunun yerine geçmez |
| P0-2 | P0 | Test | 173 instrumentation testinin tamamı tek koşuda yeşil değil; süreç çökmesi/askıda kalma var | KAPALI | 204/204 PASS tek connectedDebugAndroidTest (0 hata, 1 atlandı: izin reddi ayrı PASS); batch 204/204 |
| P0-3 | P0 | Sağlayıcı | Nominatim/Valhalla/Overpass toplu (global) kota uyumu istemciden kanıtlanamaz | ENGEL | Yerel merkezi throttle uygulanacak; global kanıt değildir |
| P0-4 | P0 | SOS | Gerçek SIM üzerinden SMS teslimi ve gerçek arama doğrulanmadı | ENGEL | Sahte gateway/emülatör kullanılacak |
| P0-5 | P0 | Yayın | 1.0 yayın kanıtı ve Go/No-Go kararı | ENGEL | Push/PR/release yapılmayacak |
| P1-1 | P1 | SOS | Kullanıcı "İyiyim" dedikten sonra 5 dk cooldown yeni gerçek kazayı bastırıyor | KAPALI | acknowledgeCancel + regresyon testi |
| P1-2 | P1 | SOS | Ekran kapalı/kilitliyken geri sayım görünmüyor; bildirim/FSI reddi sessizce yutuluyor | KAPALI | public bildirim, FSI, wake lock, activity fallback; ekran kapanınca yeniden post |
| P1-3 | P1 | SOS | Bildirimden "İyiyim" (geri sayım) / "Kapat" (sonuç) eylemi yok | KAPALI | SosActionReceiver + İyiyim/Kapat |
| P1-4 | P1 | Erişilebilirlik | SOS düğmesi pointerInput kullanıyor; TalkBack/D-pad tıklama semantiği yok | KAPALI | Role.Button + onClick; cihaz testi |
| P1-5 | P1 | SOS | SMS izni/SIM yokken "hazır" görünümü yanıltıcı; kalıcı redde ayar yolu yok | KAPALI | SosReadiness + SIM/telefon + kalıcı red ayarı |
| P1-6 | P1 | Servis | FGS başlatma/durdurma yarışı; erken stop foreground yükseltmesi yapmıyor | KAPALI | promote-first + startId; RideTrackingServiceTest |
| P1-7 | P1 | UI | NavCard hızlı eylemleri font 2'de kart dışına taşıyor | KAPALI | NavCard ağırlıklı satır; DashboardChrome 6/6 |
| P1-8 | P1 | Test | MapPanels/OfflineMaps instrumentation çökmesi/askıda kalması | KAPALI | RideDeckTestRunner + freezer kapatma; batch suite |
| P1-9 | P1 | Navigasyon | Yanlış yön/U dönüşü algılanmıyor; ilerleme geriye sıçrayabiliyor | KAPALI | bearing uyuşmazlığı + monoton ilerleme |
| P1-10 | P1 | Navigasyon | Rota hedefe yakın geçerken erken varış | KAPALI | kalan mesafe koşullu varış |
| P1-11 | P1 | Navigasyon | Eski rota ile Başlat; rota başlangıcı doğrulanmıyor | KAPALI | RouteStartGuard + 10 dk tazelik |
| P1-12 | P1 | Navigasyon | Bekleyen rota/alternatif/reverse işleri iptal edilmiyor | KAPALI | choices/reverse/weather/speed job iptali |
| P1-13 | P1 | Ağ | Sağlayıcı istek bütçesi hiçbir yere bağlı değil | KAPALI | ProviderHttp throttle; global kanıt değil |
| P1-14 | P1 | Ağ | Hava ilk snapshot'a kadar 5 sn'de bir yeniden deniyor | KAPALI | 5/15/60 sn backoff |
| P1-15 | P1 | Ağ | Hız limiti hatasında geri çekilme yok | KAPALI | 30/60 sn backoff |
| P1-16 | P1 | Pil | OverlayService uygulama öndeyken ikinci GPS aboneliği açıyor | KAPALI | LocationArbiter + overlay paylaşımı |
| P1-17 | P1 | Gizlilik | Arka planda hava/termal TTS | KAPALI | VoiceGate onScreen |
| P1-18 | P1 | Performans | Palette ana thread'de hesaplanıyor | KAPALI | Dispatchers.Default + cache |
| P1-19 | P1 | Gizlilik | Kilitli ekranda/secret bildirimde mesaj içeriği okunabiliyor | KAPALI | MessagePrivacy + test |
| P1-20 | P1 | Gizlilik | dataExtractionRules yok | KAPALI | dataExtractionRules + manifest |
| P1-21 | P1 | Veri | Yolculuk geçmişini topluca silme yok | KAPALI | deleteAllTrips + onay + test |
| P1-22 | P1 | Veri | SQLite onUpgrade boş; bozulma kurtarma yok | KAPALI | v2 migration + corrupt backup + test |
| P1-23 | P1 | Telefon | API 26/27'de arama kapatma sessizce başarısız | KAPALI | CallCapability + CallScreen disabled/açıklama |
| P1-24 | P1 | Telefon | MotoInCallService API <30 kişi araması ana thread'de | KAPALI | executor + generation guard |
| P1-25 | P1 | CI | CI instrumentation çalıştırmıyor; x86_64 yok | KAPALI | CI emulator job + rapor |
| P1-26 | P1 | Build | ARM-only APK CI emülatöründe çalışmıyor | KAPALI | debug x86_64; batch suite |
| P2-1 | P2 | UI | Küçük ekran/taşma ve kırpılmalar | KAPALI | hedefli UI testleri |
| P2-2 | P2 | UI | Büyük yazı ölçeğinde diğer kartlar | KISMİ | NavCard ve birçok kart; tam 43 ekran matrisi yok |
| P2-3 | P2 | UI | Gündüz teması düşük kontrast (turuncu/yeşil) | KAPALI | readableOn + gündüz kontrast testleri |
| P2-4 | P2 | Erişilebilirlik | TalkBack/D-pad semantiği ve odak | KISMİ | semantics/focus testleri; gerçek TalkBack/D-pad yok |
| P2-5 | P2 | Erişilebilirlik | 64 dp altı dokunma hedefleri | KISMİ | hedefli 64 dp testleri; tüm ekranlar taranmadı |
| P2-6 | P2 | UI | Mesaj banner'ı navigasyonu kapatıyor | KAPALI | haritada rehberlik sırasında banner gizli |
| P2-7 | P2 | İzin | Kalıcı reddedilen izinlerde ayara yönlendirme | KAPALI | PermissionRequestPolicy + ayar yönlendirmesi |
| P2-8 | P2 | Erişilebilirlik | Eksik açıklama/durum metinleri | KAPALI | SOS/UI semantics; cihaz doğrulaması açık |
| P2-9 | P2 | Harita | Her fix'te tüm rota GeoJSON'unun yeniden üretilmesi | KAPALI | progressIndex bazlı rota çizimi |
| P2-10 | P2 | Performans | Ana thread'de ağır işlemler | KAPALI | Palette/package sorgusu arka plana |
| P2-11 | P2 | Performans | Pil/ısı/jank/ANR ölçümü yok | ENGEL | Fiziksel cihaz ölçümü |
| P2-12 | P2 | Veri | Zaman/saklama/hata sonrası toparlanma | KAPALI | veri testleri; gerçek bozulma yok |
| P2-13 | P2 | Çevrimdışı | Harita arayüzünde yanıltıcı işlemler | KAPALI | ölü indirme düğmeleri kaldırıldı |
| P2-14 | P2 | Build | Gradle/Kotlin/Actions uyarıları | KISMİ | 0 hata; 121/120 uyarı bilinçli olarak kaldı |
| P2-15 | P2 | Yayın | AAB/R8 runtime doğrulaması | KAPALI | AAB + imza + R8 smoke |
| P2-16 | P2 | Yayın | 16 KB runtime | ENGEL | Statik kontrol var, runtime yok |
| P2-17 | P2 | Tedarik | Bağımlılık/supply-chain doğrulaması | KISMİ | sürümler sabit; dependency verification yok |
| P2-18 | P2 | Lisans | NOTICE/lisans eksikleri | KAPALI | NOTICE lisansları eklendi |
| P2-19 | P2 | Asset | Gereksiz üretim asset'leri | KAPALI | probe asset debug’a taşındı |
| P2-20 | P2 | Doküman | README/STATUS/CASE-STUDY/EKSIKLER/RELEASE_READINESS çelişkileri | KAPALI | README/STATUS/CASE-STUDY/EKSIKLER/RELEASE_READINESS güncellendi |
| P3-1 | P3 | UI | Küçük görsel/odak/klavye iyileştirmeleri | KISMİ | fırsat bulunan küçük düzeltmeler |
| P3-2 | P3 | Test | Kapsam yüzdesi ve raporlama | KAPALI | test-report-summary.py + CI raporu |
| P3-3 | P3 | Doküman | Denetim kanıtlarının arşivlenmesi | KAPALI | mevcut captures/ kanıtları |

## Çalışma paketleri

- **SOS**: P1-1..P1-5, P2-8 (SOS kısmı)
- **NAV**: P1-9..P1-15, P2-9, P2-13
- **SVC**: P1-6, P1-16, P1-18, P2-10 (performans)
- **UI**: P1-7, P2-1..P2-8, P2-20 (UI metinleri)
- **DATA**: P1-19..P1-24, P2-12
- **CI**: P1-25, P1-26, P2-14, P2-15, P2-17, P3-2
- **DOCS**: P2-18..P2-20, P3-3

> Not: Bu tablodaki "kapalı" sayısı yalnız kod + test kanıtı olan maddeleri sayar.
> Gerçek cihaz, gerçek SMS/arama, sağlayıcı global kotası ve mağaza kararları
> kapanmadan genel 1.0 kararı GO olamaz.

> **17 Kasım 2026 doküman uzlaştırma notu:** README, STATUS, CASE-STUDY (TR/EN),
> EKSIKLER, RELEASE_READINESS ve NOTICE güncel davranış ve kanıtlarla uzlaştırıldı.
> Bu tablodaki durumların nihai güncellenmesi orkestratöre aittir; bu not yeni bir
> kapanış iddiası değildir.
