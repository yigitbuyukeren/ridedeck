# Plan listesi

Bekleyen maddeler. Uygulama, kullanıcı "planı yap" diyene kadar başlamaz; yapılanlar buradan silinir (kayıt için `docs/STATUS.md` ve git geçmişi). Kaynak: [EKSIKLER.md](EKSIKLER.md).

Bekleyen maddeler: çevrimdışı harita (#1, öncelikli) ve navigasyonun üst düzeye taşınması (#2, 2026-11-02). Önceki üç madde (görsellerin yenilenmesi, yer aramasında yazım hatası toleransı, Ayarlar'ın sürerken kilitlenmesi) 2026-10-26'da tamamlandı; ayrıntı `docs/STATUS.md` ve git geçmişinde.

## 1. Çevrimdışı harita (öncelikli, ayrı görev)

2026-10-28'de kullanıcı kararı: GPS, hız göstergesi ve önceden indirilmiş haritalar internetsiz çalışmalı. Şu an harita yalnızca çevrimiçi (OpenFreeMap), #5 "planlanmıyor" diye kapatılmıştı; bu karar geçersiz. Sesli asistan kaldırılmıştır; bu plan onu geri getirmez.

Başlangıç noktaları (araştırılacak, henüz karar yok): MapLibre offline region API (`OfflineManager`, mevcut sürüm 11.13.5'te var) ile vektör karo indirme; OpenFreeMap karolarının toplu indirme kullanım şartları; bölge seçimi (il/dikdörtgen), boyut ve disk tahmini; çevrimdışıyken rota (Valhalla çevrimiçi, yani rota hesabı ayrı bir karar) ve arama; kilit/uyarı davranışı. Kabul ölçütü: uçak modunda indirilmiş bölgede harita çizilir, konum ve hız çalışır; indirilmemiş bölgede açık uyarı.

## 2. Navigasyonu üst düzeye taşımak: kalan işler (2026-11-02)

Aşama 0, 1, 2 (alternatif rota, genel bakış), 3 (dinamik kamera, zengin manevra bandı) ve aşama 4'ün rota geri önerme kısmı yapıldı; ayrıntı `docs/STATUS.md`. Değişmeyen sınırlar: anahtarsız public servisler, sürerken yazı girişi yok, eldivenle kullanım.

Kalanlar:
- **Haritada dönüş oku katmanı** (aşama 3).
- **Şerit bilgisi** (aşama 3): önce public Valhalla'da `format=osrm` spike'ı.
- **Debug "rotayı simüle et" modu** (aşama 0): `RouteSimulator` hazır ve testlerde kullanılıyor; uygulama içi tetikleyici yok.
- **Çevrimdışı harita** (#1): OpenFreeMap karolarının toplu indirme şartları belirsiz; karar gerekiyor.

## Kapsam dışı (bilinçli)
- Canlı trafik: anahtarsız public kaynağı yok.
- 3D binalar / Immersive görünüm (EKSIKLER #6); viraj ağırlıklı rota (EKSIKLER #8).
