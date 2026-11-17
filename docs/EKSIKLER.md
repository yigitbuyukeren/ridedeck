# Android Auto / Apple CarPlay karşısında eksikler

Tarih: 2026-11-17 (ilk karşılaştırma 2026-10-20). RideDeck tarafı `docs/STATUS.md` ve koddan, Android Auto/CarPlay tarafı web araştırmasından (çoğu teknoloji basını, resmi sayfalar sınırlı) alındı. Rakip ayrıntıları yaklaşık kabul edilmeli. Aşağıdaki RideDeck durumu 17 Kasım 2026 denetim dalgasına göre güncellendi; kapatılmamış saha/mağaza kapıları ayrıca belirtilir.

Not: RideDeck bir yansıtma sistemi değil, telefonun kendisini gösterge paneline çeviren launcher. Aşağıdaki maddeler bu farkın doğurduğu eksiklerdir.

Durum sütunu: **Yapılabilir** = anahtarsız/ücretsiz ilkeye uyuyor. **Kısıtlı** = kısmen yapılabilir. **Yapılmaz** = sunucu, donanım, ortaklık veya ilke engeli.

| # | Eksik özellik | Android Auto / CarPlay'de | RideDeck'te | Durum |
|---|---|---|---|---|
| 1 | Sesle komut ve dikte | Gemini / Siri: sesle hedef, arama, mesaj, müzik | Kaldırıldı (2026-10-28) ve geri getirilmedi: güvenilir uyandırma/tanıma sağlanamadı; yalnız Android TTS ile anons var | Yapılmaz (bilerek kaldırıldı) |
| 2 | Mesajlara sesle cevap | Okuma ve sesle cevap, WhatsApp/Signal dahil | Mesajı sesli okur (kilit ekranında gizli/özel içerik politikasıyla); sesle cevap asistanla birlikte kaldırıldı | Yalnız okuma |
| 3 | Üçüncü taraf uygulama ekosistemi | Onaylı navigasyon, müzik, mesaj, şarj, park, hava uygulamaları | Yalnızca kendi modülleri; başka uygulama gömülemez | Yapılmaz |
| 4 | Canlı trafik ve olay bildirimi | Google Maps / Waze: trafik, kaza, polis, yol çalışması | Yok | Yapılmaz (sunucu/veri) |
| 5 | Çevrimdışı harita ve rota | Google Maps çevrimdışı haritalar | Önceden indirilmiş alanlar korunur; **yeni indirme ve mevcut bölge devamı sağlayıcı uyumluluğu doğrulanana kadar kapalı**. Çevrimdışı rota ve arama yok | Kısıtlı; indirme **BLOCKED**, büyük iş |
| 6 | 3D bina ve zengin harita (Immersive Navigation) | Google Maps'te 3D binalar, yükseltili yollar, arazi | 2D vektör harita, 55° eğim | Yapılmaz |
| 7 | Şerit bilgisi ve kavşak görünümü | Şerit yönlendirme, kavşak görseli | Dönüş ikonu ve metin | Kısıtlı (Valhalla verisine bağlı) |
| 8 | Viraj ağırlıklı / manzaralı rota | Calimoto, Kurviger gibi uygulamalarda var; AA/CarPlay'de yok | Yok | Yapılmaz (anahtarsız karşılığı yok) |
| 9 | Widget ve Live Activity | CarPlay (iOS 26): yığınlanabilir widget, canlı etkinlik | Sistem widget'ı denendi ve bilerek kaldırıldı; ana ekran kart seçimi de kaldırıldı, düzen sabit | Yapılmadı (widget yapılmayacak) |
| 10 | Araç donanımı entegrasyonu | Direksiyon/gidon tuşları, araç mikrofonu, ekranı, klima | Yok | Yapılmaz (gidon kumandası kararlaştırıldı: yapılmayacak) |
| 11 | Araç verisi (OBD, TPMS, yakıt menzili) | Araç üreticisine bağlı | Çıkarıldı / yapılmayacak | Yapılmaz |
| 12 | Gösterge paneli (cluster) kontrolü | CarPlay Ultra | Yok | Yapılmaz |
| 13 | Park halinde video | Android Auto: YouTube, hareketle ses moduna geçer | Yok | Yapılmaz |
| 14 | Müzik uygulaması tarama ve kütüphane | Uygulamanın kütüphanesi araç ekranından gezilir | Yalnızca çalan oturum ve oynat/duraklat/geç | Kısıtlı (`MediaBrowser`) |
| 15 | Bağımsız sürücü dikkati doğrulaması | Google/Apple şablon ve içerik sınırları, onay süreci | Kendi kuralları (5 km/sa kilidi, büyük hedefler), doğrulama yok | Yapılmaz |
| 16 | Saha doğrulaması | Milyonlarca araçta denendi | Gerçek telefonda hâlâ denenmemiş maddeler: arama ses yolu/interkom, gerçek SMS/SIM, kaza eşikleri, kilit ekranı/Doze/OEM, ısınma, mini harita pili, 16 KB runtime | Açık / **BLOCKED** (saha doğrulaması) |
| 17 | iOS desteği | iPhone (CarPlay) | Yalnızca Android | Yapılmaz |
| 18 | Birden çok ekran / çok kullanıcılı | Araç ekranı + telefon aynı anda | Tek ekran | Yapılmaz |
| 19 | Grup sürüşü, canlı konum paylaşımı | Google Maps / Find My ile | Yok | Yapılmaz (sunucu gerekir) |
| 20 | mph / mil birimi | Bölgeye göre | Ayarlardan mil/mph (hız, mesafe, limit, rota sesleri) | Yapıldı |

## Öncelik listesi

1. Sesle komut (#1): **yapılmadı, kapsam dışı** — asistan 2026-10-28'de kaldırıldı.
2. Sesle mesaj cevabı (#2): **yapılmadı, kapsam dışı** — yalnız sesli okuma var.
3. Düzenlenebilir ana ekran kartları (#9): **yapılmadı** — widget denemesi ve kart seçimi kaldırıldı.
4. mph / mil birimi (#20): yapıldı.
5. Müzik kütüphanesi gezinme (#14): bilerek yapılmadı (üçüncü taraf bağlantısı büyük olasılıkla reddedilir, doğrulanamaz).
6. Gerçek telefonda saha testi (#16): **açık/BLOCKED**, `docs/STATUS.md` ve `docs/RELEASE_READINESS.md` "denenmedi" listeleri.

- Şerit okları: Valhalla `format=osrm` şerit verisi (`intersections[].lanes`) 2026-11-02 ölçümünde 3 rotada 51 adımın 6'sında var; bu kapsamla bant yapılmadı. Kapsam artarsa yeniden bakılabilir.
