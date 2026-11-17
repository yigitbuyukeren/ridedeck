# Çeviri rehberi

RideDeck'in arayüzü şu anda **Türkçe** (varsayılan) ve **İngilizce**dir. Yeni dil eklemek veya mevcut çeviriyi düzeltmek için bu rehberi izle.

Çeviri, Kotlin kodu değişikliği gerektirmeyen ama dikkat isteyen bir katkıdır: metinler sürüş sırasında okunur, bu yüzden kısa, açık ve yüksek kontrastlı olmalıdır.

## Dosyalar

| Dil | Dosya |
|---|---|
| Türkçe (varsayılan) | `app/src/main/res/values/strings*.xml` (`strings.xml`, `strings_data.xml`, `strings_nav.xml`, `strings_sos.xml`, `strings_ui.xml`) |
| İngilizce | `app/src/main/res/values-en/strings*.xml` |
| Yeni dil (örnek Almanca) | `app/src/main/res/values-de/strings*.xml` |

Android, eksik bir anahtar için varsayılan (`values/`) metne düşer. Yine de yeni bir dilin mümkün olduğunca eksiksiz olması beklenir; yarım çeviri arayüzde karışık dil görünümüne yol açabilir.

## Mevcut çeviriyi düzeltme

1. İlgili anahtarı iki dildeki `strings*.xml` dosyalarında bul.
2. Yalnızca metni değiştir; `name` alanını, biçim yer tutucularını ve sırasını koru.
3. Anlamı değil, kullanıcının gördüğü bağlamı esas al. Örneğin "Yola devam" bir navigasyon eylemidir; "devam et" genel bir metin değildir.
4. Değişikliği küçük bir PR ile gönder. Birden çok ilgisiz metni tek PR'da toplama.

## Yeni dil ekleme

1. Önce bir [belge/çeviri issue'su](https://github.com/yigitbuyukeren/ridedeck/issues/new?template=documentation.yml) aç; dili ve kapsamı belirt.
2. `app/src/main/res/values-en/strings*.xml` dosyalarını yeni dil dizinine kopyala (ör. `values-de/strings.xml`, `values-de/strings_data.xml`, `values-de/strings_nav.xml`, `values-de/strings_sos.xml`, `values-de/strings_ui.xml`). İngilizceyi kaynak olarak kullanmak, anahtarların eksiksiz kalmasını kolaylaştırır.
3. Tüm kullanıcıya görünen metinleri çevir. Teknik adları, marka ve sağlayıcı adlarını (OpenStreetMap, Valhalla, Open-Meteo, MapLibre, Android, SMS, GPS gibi) olduğu gibi bırak.
4. Biçim yer tutucularını aynen koru: `%1$s`, `%2$d`, `%%`, `\n` gibi. Sıra değişebilirse bile numaralı yer tutucuları koru; numarasız `%s` kullanma.
5. Uzun metinleri kısalt. Almanca gibi uzun dillerde kart başlıkları ve düğmeler taşabilir; 2,0× yazı boyutunda da dene.
6. Sağdan sola yazılan bir dil ekliyorsan (Arapça, İbranice, Farsça) yönü ve simge hizalamasını ayrıca kontrol et.
7. PR'da dili, çevirinin durumunu (tam/kısmi) ve varsa ana dili konuşan gözden geçiren kişiyi belirt.

Yeni dil dizini eklemek Kotlin kaynak kodunu değiştirmez; yalnızca Android kaynak dosyası ekler. Dil seçimi sistem diline göre yapılır; uygulama içi ayrı bir dil seçici yoktur.

Sesli yönlendirme şu anda yalnız Türkçe ve İngilizce destekler: `GuidanceLanguage.tag()` Türkçe dışındaki diller için `en-US` döner. Yeni bir arayüz dili eklendiğinde sesli yönlendirme İngilizce kalır; yeni sesli dil desteği ayrı bir kod değişikliğidir.

## Kalite kuralları

- **Makine çevirisi tek başına yeterli değildir.** Özellikle navigasyon, güvenlik, izin ve SOS metinlerinde anlam hatası tehlikelidir. Mümkünse ana dili konuşan biri gözden geçirsin.
- **Kısa ve eyleme dönük yaz.** "Lütfen ... yapmanız gerekmektedir" yerine "... yap" gibi doğrudan bir ton kullan.
- **Sürüş bağlamını koru.** Metin, sürücünün bir bakışta anlayacağı kadar kısa olmalı; teknik ayrıntıyı açıklama alanına bırak.
- **Tutarlı ol.** Aynı kavram (rota, durak, yolculuk, izin, hız limiti) tüm dosyada aynı sözcükle geçsin.
- **Kişisel veri ekleme.** Örnek metinlerde gerçek konum, telefon numarası veya kişi adı kullanma.
- **Yeni anahtar uydurma.** Çeviri PR'ında yalnızca mevcut anahtarları çevir; yeni bir metin gerekiyorsa önce issue aç. Yeni anahtar eklenmesi gerekiyorsa Türkçe ve İngilizce dosyalara da eklenmeli.

## Doğrulama

Anahtar kümelerini karşılaştır:

```bash
diff <(cat app/src/main/res/values/strings*.xml | grep -o 'name="[^"]*"' | sort) \
     <(cat app/src/main/res/values-en/strings*.xml | grep -o 'name="[^"]*"' | sort)
```

Yeni dil için:

```bash
diff <(cat app/src/main/res/values/strings*.xml | grep -o 'name="[^"]*"' | sort) \
     <(cat app/src/main/res/values-de/strings*.xml | grep -o 'name="[^"]*"' | sort)
```

Derle ve emülatörde dene:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kontrol listesi:

- [ ] Anahtarlar eksiksiz; eksik varsa hangileri olduğunu PR'da yazdım.
- [ ] Yer tutucular (`%1$s`, `%2$d`, `%%`) korunmuş.
- [ ] Metinler yatay düzende ve 1,0×/1,5×/2,0× yazı boyutunda taşmıyor.
- [ ] Navigasyon, izin, SOS ve hata mesajları ana dili konuşan biri tarafından gözden geçirildi (veya bunu PR'da belirttim).
- [ ] README'deki dil listesi yeni dili yansıtıyor (yeni dil eklendiyse).
- [ ] Gerçek konum, telefon numarası veya kişisel veri yok.

## İlgili belgeler

- [Katkı rehberi](../CONTRIBUTING.md)
- [Geliştirme rehberi](DEVELOPMENT.md)
- [Destek](../SUPPORT.md)
