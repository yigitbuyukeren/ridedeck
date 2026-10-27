# Gerçek cihaz demo kaydı: senaryo ve komutlar

Bu dosya, **gerçek bir Android telefonla** 60–90 saniyelik bir ekran kaydı almak için hazırlık listesidir. Depodaki `README`'deki görüntüler ve `demo.gif` emülatörden alınmıştır; bu senaryo uygulanıp kayıt alınana kadar gerçek cihaz videosu yoktur ve öyle sunulmamalıdır.

## Hazırlık (kişisel veri sızmasın)

- Telefonu USB hata ayıklamayla bağla: `adb devices -l` cihazı `device` olarak göstermeli.
- Bildirimleri kapat (Rahatsız Etme), ana ekran ve rehber boş/sade olsun. RideDeck'in telefon ekranı **rehberi** ve **son aramaları** gösterir: kaydın o ekrana girmeyecek şekilde kurgulandığından emin ol ya da test rehberi kullan.
- Konum: sahte konum kullanıyorsan videoda "simüle konum" yaz; gerçek sürüş kaydı alıyorsan evin/işin görünmemesi için kayıtlı yerleri sil veya kırp.
- SOS'u **gerçek SMS göndermeyecek** şekilde göster: ayarlarda kişi eklemeden geri sayım ekranını aç, "İptal" de.
- Kayıt öncesi: `adb shell settings put system show_touches 1` (dokunma noktaları görünsün). Sonra `... show_touches 0`.

## Kayıt komutları

```bash
# Seçenek 1: cihaz içi kayıt (en fazla 180 sn, 1080p)
adb shell screenrecord --time-limit 90 --size 2400x1080 --bit-rate 8000000 /sdcard/ridedeck-demo.mp4
adb pull /sdcard/ridedeck-demo.mp4 ./ridedeck-demo.mp4 && adb shell rm /sdcard/ridedeck-demo.mp4

# Seçenek 2: scrcpy ile bilgisayardan kayıt (brew install scrcpy)
scrcpy --record=ridedeck-demo.mp4 --max-fps=30 --time-limit=90

# Hafifletme: README için GIF yerine 720p MP4 (ffmpeg varsa)
ffmpeg -i ridedeck-demo.mp4 -vf scale=-2:720 -c:v libx264 -crf 28 -an ridedeck-demo-720p.mp4
```

Uygulama yatay kilitlidir (`sensorLandscape`); telefon yatay tutulmalı. Sesli komut gösterilecekse ses de gerekir: `scrcpy --record=... --audio-source=playback` (Android 11+, sürüme göre değişir) ya da dışarıdan kaydet.

## 75 saniyelik çekim planı

| Süre | Ne gösterilir | Not |
|---|---|---|
| 0–8 sn | Araç seçimi, ana panel (hız, saat, müzik kartı) | Gerçek GPS ile hız göstergesi hareket etsin; park halindeysen "hız 0" gerçek durumdur |
| 8–25 sn | Hedef arama ("Ara" ile), rota önizleme, "Başla" | Arama sürerken kilitlenir; bunu da göster |
| 25–45 sn | Sesli navigasyon kartı, bir dönüş anonsu, mini harita | Gerçek sürüşte değil de park halinde sahte konumla çekiliyorsa altyazıda belirt |
| 45–55 sn | ⛽ benzinlik listesi, ara durak ekleme | |
| 55–65 sn | Sesli komut ("En yakın benzinlik", "Kalan süre") | Tanıma cihaza ve gürültüye bağlıdır; başarısız denemeyi de kesme |
| 65–75 sn | SOS geri sayımı, **İptal** | Gerçek SMS gönderme |

## Kayıttan sonra

1. Videoyu kontrol et: bildirim, rehber ismi, adres, plaka, kişisel konum var mı? Varsa kırp/bulanıklaştır.
2. `docs/screenshots/` yerine `docs/demo/` altına koy (büyük dosyayı git'e değil GitHub Release varlığı olarak eklemek daha uygun).
3. README ve `CASE-STUDY.md` içindeki "emülatör" notunu yalnızca gerçekten değiştirdiğin kısım için güncelle; kaydın hangi telefonda, hangi sürümde ve gerçek mi simüle konumla mı alındığını açıkça yaz.
