# Plan listesi

Bekleyen maddeler. Uygulama, kullanıcı "planı yap" diyene kadar başlamaz; yapılanlar buradan silinir (kayıt için `docs/STATUS.md` ve git geçmişi). Kaynak: [EKSIKLER.md](EKSIKLER.md).

Bekleyen madde: aşağıdaki çevrimdışı harita (öncelikli). Önceki üç madde (görsellerin yenilenmesi, yer aramasında yazım hatası toleransı, Ayarlar'ın sürerken kilitlenmesi) 2026-10-26'da tamamlandı. Son üç madde (görsellerin yenilenmesi, yer aramasında yazım hatası toleransı, Ayarlar'ın sürerken kilitlenmesi) 2026-10-26'da tamamlandı; ayrıntı `docs/STATUS.md` ve git geçmişinde.

## 1. Çevrimdışı harita (öncelikli, ayrı görev)

2026-10-28'de kullanıcı kararı: GPS, hız göstergesi ve önceden indirilmiş haritalar internetsiz çalışmalı. Şu an harita yalnızca çevrimiçi (OpenFreeMap), #5 "planlanmıyor" diye kapatılmıştı; bu karar geçersiz. Ses işi (çevrimiçi STT) bunu beklemez, ama bu madde ondan önce planlanır.

Başlangıç noktaları (araştırılacak, henüz karar yok): MapLibre offline region API (`OfflineManager`, mevcut sürüm 11.13.5'te var) ile vektör karo indirme; OpenFreeMap karolarının toplu indirme kullanım şartları; bölge seçimi (il/dikdörtgen), boyut ve disk tahmini; çevrimdışıyken rota (Valhalla çevrimiçi, yani rota hesabı ayrı bir karar) ve arama; kilit/uyarı davranışı. Kabul ölçütü: uçak modunda indirilmiş bölgede harita çizilir, konum ve hız çalışır; indirilmemiş bölgede açık uyarı.
