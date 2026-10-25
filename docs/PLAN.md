# Plan listesi

Bekleyen maddeler. Uygulama, kullanıcı "planı yap" diyene kadar başlamaz; yapılanlar buradan silinir (kayıt için `docs/STATUS.md` ve git geçmişi). Kaynak: [EKSIKLER.md](EKSIKLER.md).

## Görselleri ve belgeleri yenile (2026-10-24)

Dock logosu kalktı ve kadran, çipler, nav kartı, Ayarlar değişti; eski görseller güncel değil. Tek turda:
- `docs/screenshots/*.png` ve `demo.gif`, README kapağı (`banner-light.jpg`, `banner-dark.jpg`; `yigitbuyukeren/tools/banners.html?p=ride`, maketteki dock logosu da kalkmalı).
- Sitedeki (`yigitbuyukeren.github.io`), profil README'sindeki kartlardaki, `sosyal-onizleme/` ve `paylasim-taslaklari/` içindeki RideDeck görselleri.

## Yer aramasında yazım hatası ve eksik harf (2026-10-24)

### Sorun

"Taksim'e gidiyorum" yerine "taksm" yazılınca "sonuç bulunamadı" çıkıyor. Arama (`RouteRepository.search`) metni olduğu gibi Nominatim'e yolluyor; Nominatim yazım hatalarına az tolerans gösteriyor. Ses tanıyıcının yanlış duyduğu yer adları da aynı soruna yol açabilir.

### Yapılacaklar

1. **Düzeltme adayları (`PlaceQuery`, saf mantık):** Türkçe karakter varyantları, tek harf eksik/fazla/yer değiştirme ve çift harf sadeleştirme. Kısa sorgularda en çok 6 aday; uzun sorgularda yalnızca Türkçe karakter ve ek ayıklama varyantları.
2. **Bilinen yerlerle eşleştirme:** Ağa çıkmadan kayıtlı ev/iş/favoriler ve son aranan/gidilen yerlerle bulanık eşleşme. Önerilen eşik yaklaşık 0,75; çok güçlü yerel eşleşme (≥0,9) doğrudan sonuç listesine eklenebilir.
3. **Ağ araması sırası:** Önce özgün sorgu, sonuç yoksa en iyi 2-3 aday. İstekler Nominatim politikasına göre seri ve en az 1 saniye arayla yapılır; yalnızca kullanıcı aramayı gönderdiğinde çalışır. Sonuç yine yoksa "Bunu mu demek istedin?" önerisi gösterilir. Onay olmadan rota başlatılmaz.
4. **Sesli arama:** `PlaceQuery` adaylarını `VoiceCommand.NavigateTo` arama akışına ekle. Eşleşme bulunamazsa kullanıcıya öneri sor; tanıyıcının alternatiflerini de değerlendir.
5. **Boş sonuç ekranı:** Varsa yazım önerisi, son aramalar ve harita üzerinde seçme kısayolu göster.
6. **Bölge ağırlığı:** Önce yakın çevreyi sınırlayan `bounded=1` araması yap; sonuç bulunmazsa sınırı kaldır.

### Otomatik doğrulama

- `PlaceQueryTest`: `taksm`, `takism`, `kadikoy`, `beşiktaş` varyantları; uzun sorgular ve aday sınırı.
- Eşleştirme testleri: kayıtlı/son aranan yerlerle bulanık eşleşme ve eşik altında öneri verilmemesi.
- `NavigationControllerTest`: aday sorgulardan sonuç bulunması, öneri üretilmesi ve onaysız rota başlamaması.
- `VoiceAssistantTest`: öneri sorusu ile evet/hayır yanıtları.

### Riskler

- Nominatim'e arama başına gereksiz istek göndermemek için alternatifler seri çalışır ve yalnızca gönderimden sonra aranır.
- Yanlış yere rota riskini önlemek için öneri kullanıcı onayı olmadan uygulanmaz.
- Son aramalar cihazda tutulur; Ayarlar'da geçmişi silme ve saklamayı kapatma seçeneği gerekir.

## Ayarlara erişim: kalan (2026-10-24)

İsteğe bağlı: Hız 5 km/sa üstündeyken Ayarlar'ı kilitle ve kısa açıklama göster. Konum izni yokken mevcut açılma davranışı korunsun. Ayrı ürün kararı.
