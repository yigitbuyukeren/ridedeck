# Güvenlik politikası

RideDeck konum, telefon, SMS, kişi/rehber, bildirim ve medya bilgilerine erişebilen bir sürüş uygulamasıdır. Güvenlik ve gizlilik bildirimleri ciddiye alınır. Bir açık bulduysan lütfen **herkese açık issue açma**; bu belgedeki gizli kanalı kullan.

## Desteklenen sürümler

| Sürüm | Destek |
|---|---|
| En son GitHub sürümü (şu anda 0.6.x) | Güvenlik düzeltmeleri alır |
| `main` dalındaki en güncel kaynak | Geliştirme sürümü; düzeltmeler buradan yayına hazırlanır |
| Daha eski sürümler ve değiştirilmiş derlemeler | Desteklenmez; sorun eski sürümdeyse önce güncel sürümde tekrar dene |

Yayınlanan APK'nın sürümünü uygulama içinden veya APK dosya adından kontrol edebilirsin. Kaynaktan kendi derlediğin APK'lar için yalnızca güncel `main` kabul edilir.

## Güvenlik açığı nasıl bildirilir?

E-posta: **mail@yigitbuyukeren.com**

Konu satırına `RideDeck güvenlik` yaz. İlk mesajda şunları paylaş:

- Etkilenen sürüm ve/veya commit
- Android sürümü ve cihaz/emülatör bilgisi
- Açığın türü ve olası etkisi (ör. izin atlama, veri sızıntısı, kötü niyetli intent, gizli anahtar)
- En az adımla yeniden üretme yolu
- Varsa kanıt (log, ekran görüntüsü, küçük bir kavram kanıtı)

**Gerçek konum, telefon numarası, SMS içeriği, kişi listesi veya parola gönderme.** Gerekiyorsa örneği anonimleştir. Şifreli iletişim gerekiyorsa bunu ilk e-postada belirt; uygun bir kanal üzerinden devam edilir.

Güvenlik bildirimini issue, PR, tartışma veya sosyal medya üzerinden herkese açık biçimde paylaşma. Açığı doğrulamak için başka kullanıcıların verisine, cihazına veya hesaplarına erişme; hizmeti kesintiye uğratma.

## Neler güvenlik kapsamındadır?

Özellikle şu alanlardaki sorunlar ilgi alanındadır:

- Konum, SMS, arama, kişi/rehber, bildirim ve medya erişiminde izin veya kullanıcı onayı atlama
- Hassas verinin (konum, yolculuk geçmişi, kişi, SMS, arama) beklenmedik biçimde dışarı sızması veya log'a yazılması
- Kötü niyetli intent, deep link, bildirim veya dosya ile başka uygulamaların RideDeck'i yönlendirmesi
- Yerel depolama, dışa aktarma veya yedekleme yoluyla veri ifşası
- Bağımlılık, Gradle/CI zinciri veya release imzasında tedarik zinciri sorunu
- Depoda gizli anahtar, token veya imza parolası bulunması
- Güvenlik özelliklerini (SOS, kaza algılama, sürüş kilidi) devre dışı bırakan veya yanlış güven veren davranış

## Neler güvenlik açığı sayılmaz?

- Gerçek sürüşte beklenen ama sahada doğrulanmamış davranışlar; bunlar için normal hata bildirimi daha uygundur
- Yalnızca kötü amaçlı bir cihazda veya root'lu cihazda mümkün olan saldırılar (işletim sistemi güven sınırı dışında)
- Üçüncü taraf harita, arama, rota veya hava durumu servislerinin kendi kesintileri, hız sınırları veya kullanım koşulları
- Sosyal mühendislik, fiziksel cihaz erişimi veya kullanıcının kendi verisini kendi cihazından okuması
- Yalnızca eski/desteklenmeyen sürümlerde bulunan ve güncel sürümde giderilmiş sorunlar
- Kişisel veri içermeyen, sömürülemeyen teorik lint/uyarılar

Emin değilsen yine de gizli kanaldan yaz; uygun değilse normal issue'ya yönlendirilir.

## Yanıt ve açıklama süreci

RideDeck tek bakımcılı, gönüllü bir projedir; yanıt süresi garanti edilmez. İyi niyetli bildirimler makul sürede incelenir.

1. Bildirimi aldığımızı teyit ederiz.
2. Açığı yeniden üretmeye ve etkisini değerlendirmeye çalışırız.
3. Düzeltme hazırlanır; gerekirse geçici önlem veya sürüm planı paylaşılır.
4. Düzeltme yayınlandıktan sonra kamuya açık bir güvenlik notu yayımlanabilir.
5. İstersen adını/rumuzunu teşekkür listesinde belirtiriz; anonim kalmayı seçebilirsin.

Açığı senden önce kamuya açıklamamanı rica ederiz. Düzeltme yayınlanana kadar makul bir gizlilik süresi tanı; karşılıklı olarak daha erken veya daha geç açıklama üzerinde anlaşabiliriz.

## İyi niyetli araştırma

İyi niyetli güvenlik araştırmasını destekliyoruz. Araştırman şu sınırlar içinde kalmalı:

- Yalnızca kendi cihazında, kendi verinle veya izin verilen bir test ortamında çalış
- Başka kullanıcıların verisine, hesaplarına veya cihazlarına erişme
- Hizmeti kesintiye uğratma, veri silme veya fidye/istismar amaçlı kullanma
- Açığı yaymadan önce bize makul süre tanı
- Yasal sınırlar içinde kal; bu belge bir hukuki muafiyet garantisi değildir

## Gizli bilgi ve anahtarlar

- Depoya `local.properties`, `keystore.properties`, `*.jks`, `*.keystore`, `.env` veya parola/token eklenmemelidir.
- Bir anahtarın yanlışlıkla commit edildiğini düşünüyorsan açığı gizli kanaldan bildir; anahtarı kendin kullanma veya yayma.
- Release imza anahtarı yalnız bakımcıda bulunur; katkıcılardan imza anahtarı istenmez.

## Güvenlik özellikleri hakkında

SOS ve kaza algılama yardımcı özelliklerdir; acil durum garantisi veya profesyonel güvenlik hizmeti değildir. SMS teslimi, operatör, SIM, izinler ve cihaz davranışına bağlıdır. Bu sınırları güvenlik açığı gibi değil, ürün sınırlaması olarak ele alıyoruz; yine de yanlış güven veren veya güvenlik özelliğini sessizce devre dışı bırakan bir davranış görürsen bildir.

Teşekkürler: RideDeck'i ve kullanıcılarını daha güvenli hale getirdiğin için.
