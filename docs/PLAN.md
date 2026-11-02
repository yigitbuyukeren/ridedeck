# Plan listesi

Bekleyen maddeler. Uygulama, kullanıcı "planı yap" diyene kadar başlamaz; yapılanlar buradan silinir (kayıt için `docs/STATUS.md` ve git geçmişi). Kaynak: [EKSIKLER.md](EKSIKLER.md).

Bekleyen maddeler: çevrimdışı harita (#1, öncelikli) ve navigasyonun üst düzeye taşınması (#2, 2026-11-02). Önceki üç madde (görsellerin yenilenmesi, yer aramasında yazım hatası toleransı, Ayarlar'ın sürerken kilitlenmesi) 2026-10-26'da tamamlandı; ayrıntı `docs/STATUS.md` ve git geçmişinde.

## 1. Çevrimdışı harita (öncelikli, ayrı görev)

2026-10-28'de kullanıcı kararı: GPS, hız göstergesi ve önceden indirilmiş haritalar internetsiz çalışmalı. Şu an harita yalnızca çevrimiçi (OpenFreeMap), #5 "planlanmıyor" diye kapatılmıştı; bu karar geçersiz. Sesli asistan kaldırılmıştır; bu plan onu geri getirmez.

Başlangıç noktaları (araştırılacak, henüz karar yok): MapLibre offline region API (`OfflineManager`, mevcut sürüm 11.13.5'te var) ile vektör karo indirme; OpenFreeMap karolarının toplu indirme kullanım şartları; bölge seçimi (il/dikdörtgen), boyut ve disk tahmini; çevrimdışıyken rota (Valhalla çevrimiçi, yani rota hesabı ayrı bir karar) ve arama; kilit/uyarı davranışı. Kabul ölçütü: uçak modunda indirilmiş bölgede harita çizilir, konum ve hız çalışır; indirilmemiş bölgede açık uyarı.

## 2. Navigasyonu üst düzeye taşımak (2026-11-02)

Amaç: rehberlik, rota seçimi ve harita görünümü her açıdan ticari navigasyon uygulamalarıyla yarışacak seviyeye gelsin. Değişmeyen sınırlar: yalnızca anahtarsız public servisler (bu yüzden canlı trafik yok), sürerken yazı girişi yok, eldivenle kullanım (64-84dp dokunma alanları), yatay telefonda ~360-415dp yükseklik.

### Bugünkü durum (2026-11-02 okuması)

Güçlü taraflar: `NavigationController` Android'den bağımsız ve testli; arama → rota → dönüş dönüş rehberlik → yeniden rota → yakıt molası → hız limiti akışı çalışıyor; `PositionSmoother` ile 1 Hz GPS'e rağmen akıcı işaret; geçilen rota griye dönüyor; rota harici navigasyon uygulamasına devredilebiliyor.

Tespit edilen eksikler:

**Rehberlik çekirdeği**
1. Sesli uyarı mesafeleri sabit (`ALERT_M` 600 m, `PRE_M` 150 m), hıza bağlı değil. 120 km/sa'te 150 m ≈ 4,5 sn; otoyol çıkışında geç kalır.
2. Rota eşleme (`onFix`) yalnızca en yakın segmente bakıyor, gidiş yönünü kullanmıyor. Paralel yol, köprü/alt geçit, yan yolda yanlış eşleşebilir. Pencere (`MATCH_WINDOW` 80 nokta) yalnızca ileri bakıyor.
3. Rotadan çıkma eşiği sabit (60 m × 4 konum); otoyolda geç, şehirde bazen erken.
4. Art arda yakın manevralarda "ardından …" talimatı yok; ikinci dönüş ne söyleniyor ne gösteriliyor.
5. Kalan süre kalan mesafe oranıyla doğrusal hesaplanıyor; Valhalla'nın manevra başına `time` değeri kullanılmıyor.
6. Varışta hedefin hangi tarafta olduğu söylenmiyor.

**Valhalla'nın verip kullanmadığımız verisi**
7. `RouteRepository.alternatives()` yazılmış ama hiçbir yerden çağrılmıyor.
8. Manevra alanları atılıyor: `street_names` / `begin_street_names`, `roundabout_exit_count`, `verbal_post_transition_instruction`, `time`, `toll`, `highway`.
9. Şerit bilgisi yok. Valhalla'nın OSRM biçimli çıktısı (`format=osrm`, `banner_instructions`, kavşak `lanes`) bunu verebilir; public sunucuda ve Türkiye'deki OSM `turn:lanes` kapsamında doğrulanmalı.
10. Tek hedef var, yalnızca `legs[0]` okunuyor. Yakıt molası bu yüzden `finalDestination` ile ayrı yolculuk olarak yamalı; Valhalla çok noktalı rotayı (birden çok `locations`, birden çok `legs`) doğrudan destekliyor.

**Harita ve görsel**
11. Kamera sabit: zoom 17, eğim 55 (`SmoothFollow`). Hızla uzaklaşmıyor, dönüşe yaklaşınca yakınlaşmıyor; rehberlikte "tüm rotayı gör" yok.
12. Haritada bir sonraki manevrayı gösteren dönüş oku yok.
13. `ManeuverBanner` / `NavCard`: sıradaki-sonraki manevra, yol adı, dönüşe kalan mesafe çubuğu yok; döner kavşak çıkış numarası ikonda yok.

**Dayanıklılık ve ölçüm**
14. Süreç ölümü / force-stop sonrası rota geri yüklenmiyor (STATUS 2026-10-29 kaydı).
15. Çevrimdışı harita yok (#1).
16. Gerçekçi sürüş simülasyonu yok; yukarıdaki iyileştirmelerin kalitesi ölçülemiyor.

### Aşamalar

Her aşama ayrı onayla başlar; her biri kendi birim testleriyle ve `testDebugUnitTest lintDebug assembleDebug` yeşil biter. Gerçek telefonda deneme yapılmadıysa STATUS'ta UNVERIFIED olarak yazılır.

**Aşama 0: Ölçüm altyapısı (önce bu)**
- Kayıtlı rotaları (Valhalla shape + gürültülü GPS, tünel boşluğu, paralel yol, yanlış sapma senaryoları) `NavigationController`'a besleyen test düzeneği; konuşulan cümleleri ve zamanlarını, eşleme sapmasını, yeniden rota sayısını ölçer.
- Debug derlemede "rotayı simüle et" modu (emülatörde `adb emu geo fix` betiğinin uygulama içi karşılığı), hız çarpanıyla.
- Kabul: en az 4 senaryo testte koşar; mevcut davranışın taban değerleri kaydedilir.

**Aşama 1: Rehberlik çekirdeği** (madde 1-6, 8'in ses kısmı)
- Zamana dayalı konuşma: hıza göre uzak uyarı (otoyol ~2 km / 1 km / 500 m, şehir ~300 m), yakın talimat ve "şimdi"; saniye eşikleri saf bir `PromptSchedule` nesnesinde.
- Yön kontrollü eşleme: segment yönü ile GPS yönü farkı cezası; pencerenin kısa bir geri payı; düşük hızda yön yok sayılır.
- Hıza ve GPS doğruluğuna göre rotadan çıkma eşiği.
- Yakın manevraları birleştirme: "200 metre sonra sağa, ardından sola" (Valhalla `verbal_post_transition_instruction` ve manevra mesafesi).
- Kalan süre manevra sürelerinden; varışta hedef tarafı ("hedef sağınızda").
- Döner kavşakta "3. çıkış" sesli ve ikonla.
- Kabul: Aşama 0 senaryolarında geç/yanlış konuşma ve yanlış eşleşme taban değerden düşük; yeni durumlar için birim testleri.

**Aşama 2: Rota seçimi ve duraklar** (madde 7, 10, 11'in genel bakış kısmı)
- Rota önizlemesinde 2-3 alternatif haritada farklı renkte; dokununca seçilir; süre/mesafe farkı kartta.
- Çok noktalı rota: ara durak ekle/çıkar (yakıt molası dahil); `Route` birden çok `leg` taşır; yakıt molası `finalDestination` yaması yerine ara durak olur. Eski davranışın regresyon testleri korunur.
- Rehberlik sırasında "genel bakış" düğmesi (tüm rota kadrajı, birkaç saniye sonra takip kamerasına döner).
- Kabul: alternatif seçimi, durak ekleme ve duraktan sonra devam testli; sürerken yazı girişi gerekmez.

**Aşama 3: Görsel cila** (madde 9, 11-13)
- Dinamik kamera: hıza göre zoom/eğim, manevraya yaklaşınca yakınlaşma, dönüş sonrası yumuşak dönüş; saf bir `CameraPlan` hesabı.
- Haritada dönüş oku katmanı (manevra noktası çevresindeki rota parçası).
- Zengin manevra bandı: büyük mesafe + ikon, yol adı, sıradaki-sonraki manevra küçük satırı, kalan mesafe çubuğu; `NavCard` aynı bilgiyi kısaltılmış gösterir.
- Şerit bilgisi: önce public Valhalla'da `format=osrm` denemesi (spike); veri yeterliyse bandın altında şerit okları, değilse madde kapatılır ve EKSIKLER'e yazılır.
- Kabul: emülatörde gece/gündüz ekran görüntüleri; MiniMap 30/15 fps sınırı ve ThermalGuard davranışı bozulmaz.

**Aşama 4: Çevrimdışı ve süreklilik** (madde 14-15)
- #1 çevrimdışı harita.
- Aktif rotanın (`Route`, hedef, duraklar, ilerleme) `Prefs`'e yazılması; süreç ölümünden sonra açılışta "rehberliğe devam et" önerisi.
- Kabul: force-stop sonrası rota geri gelir; uçak modunda #1'in kabul ölçütü.

### Kapsam dışı (bilinçli)
- Canlı trafik ve trafiğe göre süre: anahtarsız public kaynağı yok.
- 3D binalar / Immersive görünüm (EKSIKLER #6).
- Viraj ağırlıklı rota (EKSIKLER #8); Valhalla motorcycle `use_trails` gibi seçeneklerle yarım bir yaklaşım Aşama 2 sonrası ayrıca değerlendirilebilir.
