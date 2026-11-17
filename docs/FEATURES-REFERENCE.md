# RideDeck — Ayrıntılı özellikler ve teknik notlar

> [!NOTE]
> Bu belge, 17 Kasım 2026 tarihli kapsamlı README açıklamalarını **kaybetmeden korumak** için oluşturulmuştur. Geliştirme ağacıyla yayımlanan beta arasındaki farklar ve test sonuçları tarihsel bağlamında okunmalıdır. Güncel indirme için [ana README](../README.md) ve [v0.6.1-beta.1 sürüm notları](https://github.com/yigitbuyukeren/ridedeck/releases/tag/v0.6.1-beta.1); yayın kapıları için [RELEASE_READINESS.md](RELEASE_READINESS.md) esas alınmalıdır.

Bu sayfa; özellik davranışlarını, bilinen sınırlamaları, SOS/gizlilik açıklamalarını ve harita sağlayıcısı notlarını ayrıntılı olarak saklar. Uygulama davranışının kesin kaynağı güncel koddur.

---

### 🧭 Özellikler

Ayrıntılar için başlıklara dokun.

<details>
<summary><b>Motosiklet profili</b></summary>

- Uygulama yalnız motosiklet içindir; araç seçim ekranı ve Ayarlar'daki araç seçimi kaldırıldı
- Rota her zaman Valhalla `motorcycle` costing'i ile hesaplanır; otoyolsuz ve ücretsiz yol seçenekleri hatırlanır
- ⛽ düğmesi benzinlik arar; benzinlik, ara durak ve rota üzerindeki duraklar motosiklet akışında çalışır
- Kaza algılama eşikleri ve 200 km/s hız göstergesi motosiklete sabittir; arayüz yatay gidon düzenindedir

</details>

<details>
<summary><b>Gösterge ve sürüş</b></summary>

- GPS hız göstergesi, saat, pil ve telefon sıcaklığı; gündüz/gece teması (ortam ışığına göre otomatik). Gösterilen hız GPS gürültüsünü yumuşatır ve imkânsız sıçramaları bir kez tutar; sinyal kesilince son değer kısa süre kalır, sonra uydurma 0 yerine `--` ve "GPS sinyali kayıp" görünür. Kaza algılama, yolculuk kaydı ve limit uyarısı ham GPS hızını kullanır
- Yolculuk kaydı: otomatik, geçmiş ekranı; hız kartından açılan geçmişte **Yolculuğu başlat / bitir**. Açıkça başlatılan takip overlay kapalıyken ve ekran kilitliyken de sürer; bildirimden bitirilebilir. Kayıt ayarı ve kısa sürüş eleme kuralları korunur.
- Geliştirme ağacındaki yolculuk detayı: toplam süre ve GPS'ten türetilen hareket süresi ayrı gösterilir; silme onay ister; onaydan sonra tarihli başarı/başarısızlık sonucu gösterilir, başarısız silmede kayıt korunur. **Haritada göster** kayıtlı noktaları ancak dokunulduğunda yükler. Çizim en çok 2.000 noktaya örneklenebilir; GPS boşlukları çizgiyle birleştirilmez. Aynı zaman damgası ve konuma sahip yinelenen kayıtlar çizgiye eklenmez; sahte GPS boşluğu oluşturmaz. Çevrimiçi altlık stili yüklense de karolar eksik kalırsa uyarı gösterilir ve kayıtlı GPS izi görünür kalır. Çevrimiçi altlık yüklenmezse iz yerel bir arka planda gösterilir; bu çevrimdışı harita/rota hesabı değildir. Önbellek önizlemesinde kayıtlı bölgelerin sınır birleşimi parça parça denetlenir ve uyarı gösterilir; bu yalnızca metadata sınırıdır, karo varlığını kanıtlamaz. Eksik veya şüpheli veri güvenli biçimde bilinmiyor kalır; çevrimdışı rota hesabı yapılmaz. Bu değişiklikler henüz yayımlanan APK'ya dahil değildir; kabul durumu [STATUS.md](STATUS.md) içindedir.
- Geliştirme ağacında rota, benzinlik, hız limiti ve hava durumu servis depoları merkezi iptal edilebilir HTTP katmanını kullanır; istek iptali ağ çağrısını da kapatır ve yavaş yanıt diğer kullanıcı eylemlerini bekletmez. Harita motorunun karo istekleri bu katmanın dışındadır. Yerel gecikme testleri gerçek sağlayıcı/Android ağ davranışının saha kanıtı değildir.
- Geliştirme ağacındaki **Sürüşe hazır mı?** paneli ilk izin ekranından isteğe bağlı olarak veya Ayarlar → Genel üzerinden açılır. Konum/GPS, ağ, harita, ses ve izin durumlarını nedenleriyle gösterir; sürüşü başlatmayı engellemez ve hareket hâlinde ayar erişimi mevcut kilide tabidir. İzin bilgileri ekrana her dönüşte (RESUMED) tazelenir; eksik SMS izni satırı yalnız uygulamanın izin ayarlarını açar, gerçek SMS göndermez. Bilinmeyen yerel karo deposu hazır sayılmaz; SOS yapılandırması mesaj teslimi veya güvenlik garantisi değildir.
- Geliştirme ağacında Ayarlar kartları ortak yarıçap, iç boşluk ve başlık/açıklama ölçülerini kullanır; altı sayfa aynı kart ritmini korur.
- Hız kartında GPS durumu; büyük, eldivenle basılan tuşlar; ekranlar arasında kısa geçiş animasyonları (ısı koruması açıkken kapanır)
- Yatay düzen iki yönde de aynıdır: telefon 180° çevrilince çentik bir yandan öbür yana geçse de arayüz kaymaz; güvenli boşluk iki yana eşit verilir
- Türkçe ve İngilizce arayüz; sesli yönlendirme telefon diline göre konuşur (Türkçe telefonda Türkçe talimat ve ses, diğerlerinde İngilizce)

</details>

<details>
<summary><b>Navigasyon</b></summary>

- Gömülü harita ve sesli yönlendirme: MapLibre + OpenStreetMap (OpenFreeMap), Nominatim arama, Valhalla motosiklet rotası, rota dışına çıkınca yeniden hesaplama. Tam ekran harita telefonun ekran yenileme hızında (90/120 Hz) çizilir; yalnızca ısınma koruması açıkken kısılır
- Anonslar üç kademe: mesafeli uzak uyarı ("800 metre sonra sağa dön"), tam talimat, dönüşte kısa "şimdi"; uzun yolda "2 km devam". Mesaj ve hava uyarıları dönüş talimatını kesmez, sonra okunur. Rota isteği sürüş yönünü taşır (U dönüşüyle başlamaz). İnternet yokken yeniden hesaplama başarısız olursa bir kez söylenir, eski rota izlenir. Varış: rota sonu, hedefte durma veya kapalı otoparkta GPS kaybıyla; varış saati gerçek hıza uyum sağlar. Görüşmede talimat yerine titreşim, görüşme bitince talimat okunur.
- Rota seçenekleri: otoyolsuz ve ücretsiz yol, seçim hatırlanır
- Kayıtlı yerler: Ev, İş ve favoriler; büyük karolarla tek dokunuşla rota, dashboard'da "Eve git"
- Benzinlikler: yakınında veya rotanın üzerinde; navigasyon sırasında tek dokunuşla **ara durak** ekle, varınca "Yola devam"
- Benzinlik için **Sapmayı hesapla** yalnız dururken, seçilen istasyon için yönlendirilmiş taban çizgisine göre yaklaşık (`≈`) ek süre/mesafe üretir; sabit bağlamda 25 m'ye kadar köken kayması yeniden kullanılır. Rehberliği kendiliğinden başlatmaz veya rotayı değiştirmez; hesaplanamazsa sahte değer göstermez
- Yolun hız limiti ve aşınca sesli uyarı; 60 saniyeden eski ölçüm veya eski oturumdan geç gelen yanıt güncel limit gibi gösterilmez
- Sürerken arama kutusu ve Ayarlar kilitlenir (sürüş kuralı: hareket halindeyken yazı girişi ve ayar değişikliği yok); hedefi kayıtlı yerlerden veya haritaya uzun basarak seç
- Yazım hatası toleransı: sonuç çıkmazsa düzeltilmiş yazımlar (Türkçe harfler, eksik veya yer değiştirmiş harf) en çok üç kez, saniyede en fazla bir istekle denenir; yine bulunamazsa "Bunu mu demek istedin?" önerisi gelir. Önerilen yer sormadan rotaya girmez. Seçtiğin son hedefler yalnızca telefonda tutulur (Ayarlar > Genel'den kapatılır, "Geçmişi sil" ile silinir)
- Navigasyon sürerken ana ekranda canlı mini harita, sıradaki dönüş, kalan mesafe ve varış saati
- Süreç ölümü sonrası kalan rota geometrisi yalnız önizleme olarak geri gelir; **Başlat** taze ve hassas GPS rotanın ilk 50 m dilimindeyse açılır. Aksi hâlde **Rotayı güncelle** ile açıkça taze rota istenir; çevrimdışı rota hesaplama/yeniden hesaplama yapılmaz
- Arama kutusu için uygulamanın kendi Türkçe klavyesi

</details>

<details>
<summary><b>Uygulamalar, kartlar ve ses</b></summary>

- Dock'taki dişli düğmesi Ayarlar'ı açar; sürüş sırasında ayarlar kilitlenir.
- Ayarlar altı bölüme ayrıldı: Genel, Görünüm, Harita, Ses ve bildirimler, Güvenlik, İzinler ve sistem. Üstteki bölüm şeridi yatay kayar; içerik tek sütunda, geniş ekranda ortalanmış ve en fazla 820 dp genişliğinde gösterilir. Genel'de birim seçimi iki düğmeyle yapılır, Harita'da konum işareti renkleri tek satırdadır, Ses'te yalnız konuşma hızı ayarlanır; yolculuk geçmişi ana ekran hız kartından açılır.
- Ana ekran düzeni hız kartının yanında harita ve müzik kartıyla kurulur; kart seçimi Ayarlar'dan kaldırıldı, Görünüm'de yalnız gündüz/gece teması seçilir. Gün batımı kartı kaldırıldı. Yeni uygulama ekleme de kaldırılmıştır.
- Rota kartında "Navigasyon uygulamasında aç" (Google Haritalar, Waze, Yandex Navi derin bağlantıları, yalnızca dururken); rota hâlâ RideDeck'te hesaplanır, canlı trafik isteyen bu uygulamalara geçer
- Kilometre/mil seçimi (Ayarlar > Genel): hız, mesafe, hız limiti ve rota sesleri; kilometre veya mil düğmesine doğrudan dokunulur
- Düğmelere basınca hafif dokunma titremesi (Ayarlar > Genel'den kapatılır); telefonun "dokunma geri bildirimi" ayarı kapalıysa yine de titremez
- Mesaj uygulamalarını tek tek sessize alma (Ayarlar > Ses ve bildirimler'deki uygulama listesi)

</details>

<details>
<summary><b>Güvenlik</b></summary>

- Hava durumu: sıcaklık, 1 saat içinde yağmur ve buzlanma uyarısı; başarısız yenilemede eski tahmin süresiz tutulmaz, 30 dakikalık tazelik sınırı sonraki kontrolde uygulanır
- Isınma koruması: telefon 42°C'yi aşınca ekran kısılır ve harita yavaşlar, motosiklet durunca ekran kararır
- SOS (varsayılan kapalı): otomatik kaza algılama (sert darbe + hareketsizlik; iki tekerlekte yön değişimi veya çok yüksek darbe aranır) veya uzun basmalı düğme. Çukur/kırmızı ışık kaynaklı yanlış alarmları azaltan filtreler var; bilinmeyen GPS hızı duruş sayılmaz. Geri sayımdan sonra en fazla 3 kişiye konum linkli bir mesaj gönderimi denenir; arama ve otomatik tekrar gönderim yok. Onaylanan, başarısız ve belirsiz sonuçlar kişi bazında ayrılır; alıcıya teslim garantisi verilmez.
- Geri sayım ekran kapalıyken kilit ekranı üstünde bildirim, tam ekran bildirim ve uyandırma kilidiyle öne alınmaya çalışılır; bildirim yoksa uygulama açılmaya çalışılır (Android 10+ yalnız üstte gösterme izni varsa), FSI reddedilirse yüksek öncelikli bildirim kalır; ses/titreşim ve SMS yolu sürer. Bildirimde geri sayım için **İyiyim**, sonuç için **Kapat** eylemi vardır. SMS izni/SIM yoksa hazırlık paneli bunu açıkça söyler. Gerçek kilit ekranı, SIM ve OEM davranışı doğrulanmadı.

</details>

<details>
<summary><b>Telefon ve medya</b></summary>

- Uygulama içi telefon: son aramalar, tüm rehber ve tuş takımı (eşleşen kişi önerisi, büyük silme tuşu, `0` uzun basınca `+`; her tuşta telefonlardaki gibi kısa DTMF "dit" sesi; yatay telefonda listede 4 satır görünür); sürerken tuş takımı kilitli
- RideDeck'i varsayılan telefon uygulaması yaparsan arama ekranı da uygulamanın içinde: sessiz, ses yolu (telefon, hoparlör, Bluetooth), ses seviyesi, görüşme tuş takımı
- Ses yolu seçimi sistemin bildirdiği kullanılabilir yollarla sınırlıdır ve onaylandığında güncellenir. Görüşme sırasında TTS anonsları kesilir; kask/kulaklık kopması algılanırsa otomatik müzik devamı durdurulur.
- Müzik: yalnızca müzik uygulamalarının oturumu, ilerleme çubuğu, kapağı kaydırarak şarkı geçme, ses tuşları; hiçbir şey çalmıyorken Oynat son müziği başlatır (bildirim erişimi gerekir)
- Mesajları sesli okuma (yalnızca RideDeck ekrandayken)
- Diğer uygulamaların üstünde hız/saat şeridi

</details>

### 🚧 Bilinen sınırlamalar

- **Sahada doğrulanmayanlar:** kaza algılama eşikleri (gerçek düşüşte denenmedi), gerçek SMS gönderimi ve SIM/operatör davranışı, kilit ekranı/Doze/OEM, varsayılan telefon uygulamasıyken arama ses yolu ve interkom, gidonda ısınma ve mini harita pil etkisi, Android 8–10 davranışı, 16 KB runtime, mağaza/Data safety ve sağlayıcı toplam kotası. Güncel liste: [docs/STATUS.md](STATUS.md) ve [docs/RELEASE_READINESS.md](RELEASE_READINESS.md).
- **Güncel QA görüntü seti:** 43 kimliğin tamamı toplanmış olsa da bu 43 gerçek sürüş değildir: 24'ü gerçek emülatör, 16'sı bileşen fixture'ı, 3'ü izole native/probe fixture'ıdır. 03 boş source-free altlık olduğu için harita/rota/işaret görünürlüğünü, 10 ise kırpılan ikincil etiket nedeniyle tüm metinlerin taşmasız olduğunu kanıtlamaz; fixture'lar gerçek navigasyon, çağrı, SMS veya medya yolculuğu değildir.
- Navigasyon, yer arama ve hava durumu **internet gerektirir**. Önceden indirilmiş harita alanları korunur; **yeni çevrimdışı indirmeler ve mevcut bölge devamı kapalıdır**. [OpenFreeMap koşulları](https://openfreemap.org/tos/) 17 Kasım 2026'da doğrulandı: indirme başına 3.000 karo veya 50 MB, en çok 4 paralel istek ve uygulamayı tanımlayan User-Agent. Uygulama içi sınırlama sağlayıcının tüm kullanıcılar toplamını kanıtlamadığı için mevcut SDK yolunda indirme başlatılmaz; yeni indirme ve devam **BLOCKED** kalır. Devralınan etkin bir indirme listede **Vazgeç** ile kapatılabilir; bu yeni indirme veya devam başlatmaz. SDK'nın `NATIVE_COMPLETE` durumu karo kullanılabilirliği veya sağlayıcı uyumu kanıtı değildir. Rota hesabı ve arama çevrimdışı çalışmaz. Genel servisler garantisizdir.
- Arka plan takibi için yolculuğu uygulama görünürken başlatmak gerekir (navigasyondaki **Başlat / Yola devam** da başlatır). Kesin konum izni ve açık GPS gerekir; overlay izni gerekmez. Bildirimler reddedilmişse uygulamadan veya Android Etkin uygulamalar bölümünden durdurulur. Navigasyon yokken 3 dk hareketsizlikte, 3 dk GPS alınamadığında veya izin/depolama hatasında kapanır. Android zorla durdurduğunda kendiliğinden yeniden başlamaz; yarım kalan kayıt sonraki açılışta toparlanır.
- Açık yolculukta navigasyon ve TTS aynı uygulama süreci içinde ekran/Activity yeniden açılışından bağımsız sürer; ara durakta Yola devam beklenebilir. Bildirimden Bitir rehberliği ve bekleyen rotayı da iptal eder. Süreç ölümü veya zorla durdurma sonrasında geçerli yerel rota geometrisi yalnız önizleme olarak geri gelir; rota kendiliğinden devam etmez. Başlat yalnız taze/hassas GPS ilk 50 m içindeyse açılır; aksi hâlde **Rotayı güncelle** açık eylemi gerekir ve bu çevrimdışı rota hesabı değildir. Çevrimdışı harita indirmesinin gerçek boyutu, süresi ve uçak modunda çizimi ile fiziksel kilit/Doze/OEM ve interkom doğrulaması henüz tamamlanmadı.
- Canlı trafik, radar uyarısı, iOS ve Android Auto desteği yok.
- SOS/kaza algılama yardımcı bir özelliktir, güvenlik garantisi değildir; uygulama ekrandayken veya başlatılmış bir yolculuk (ön plan servisi) sürerken çalışır. Ekran kapalıyken geri sayım kilit ekranı üstünde bildirim, tam ekran bildirim ve uyandırma kilidiyle öne alınmaya çalışılır; bildirim yoksa uygulama açılmaya çalışılır (Android 10+ yalnız üstte gösterme izni varsa), FSI reddedilirse bildirim kalır ve ses/titreşim/SMS yolu sürer. Bu yollar gerçek kilitli cihazda ve OEM yazılımında doğrulanmadı.

📖 Tasarım kararları ve testler: [Teknik vaka analizi](CASE-STUDY.md) · [English](CASE-STUDY.en.md)

---

### 🔒 Gizlilik ve izinler

RideDeck hesabı veya uygulamaya ait sunucu yok; yolculuk kayıtları ve ayarlar telefonda tutulur, Android yedeklemesi kapalıdır (`dataExtractionRules` ve `allowBackup=false`). Bu, konumun cihazdan hiç çıkmadığı anlamına gelmez. Çevrimiçi özellikler şu verileri dış servislere gönderir: harita karoları ve stilleri için görünen bölge (OpenFreeMap), arama metni (Nominatim, yalnızca Ara'ya basınca), rota uç noktaları ve hız limiti için son konum noktaları (Valhalla; `X-Client-Id` başlığıyla), yaklaşık konum (hava durumu için 1 km hassasiyetle, Open-Meteo), çevre veya rota koridoru boyunca benzinlik sorgusu (Nominatim / Overpass). Bu servisler istekle birlikte IP adresini de görebilir. Kayıtlı yolculuğun yeni harita görünümü açılırsa da altlık istekleri görüntülenen bölgeyi sağlayıcıya gösterebilir; kayıtlı iz dosyası dışa aktarılmaz. Sistem TTS motorunda ağ gerektiren bir ses seçilirse okunacak metin motorun sağlayıcısında işlenebilir; çevrimdışı Türkçe ses varsa başlangıçta tercih edilir. Mesajları sesli okuma isteğe bağlıdır; kilit ekranında gizli bildirim okunmaz, özel içerik yalnız uygulama içinde gösterilir.

SOS yalnızca sen açarsan çalışır ve `SEND_SMS` izni ister; kişiler telefonun kişi seçicisinden eklenir (rehber izni gerekmez). Kaza algılama uygulama ekrandayken veya başlatılmış bir yolculuk (ön plan servisi) sürerken, güncel GPS/sensör verisiyle çalışır; yolculuk başlatılmamışsa ekran kapanınca durur. Ekran kapalıyken başlayan geri sayım kilit ekranı üstünde öne alınmaya çalışılır; bildirim yoksa uygulama açılmaya çalışılır (Android 10+ yalnız üstte gösterme izni varsa), FSI reddedilirse bildirim kalır ve ses/titreşim/SMS sürer. Varsayılan SMS SIM'i seçili olmalıdır; SMS izni, SIM veya kişi yoksa hazırlık görünümü bunu söyler. Uzun metin birden çok SMS parçasına bölünebilir ve operatörün SMS ücreti uygulanabilir. Gönderim onayı alıcıya teslim onayı değildir; belirsiz sonucu yeniden göndermek aynı mesajı çoğaltabilir. Bu özellik bir güvenlik garantisi değildir; gerçek SIM/kilit ekranı doğrulanmadı.

### ⚠️ Güvenlik notu

> [!WARNING]
> Bu uygulama sürüş sırasında dikkat dağıtabilir. Telefonu sabitle, ayarları park halindeyken yap ve trafik kurallarına uy. Yazılım garantisiz sunulur (Apache 2.0).

### 🗺️ Kullanılan servisler ve atıflar

- Harita verisi © [OpenStreetMap](https://www.openstreetmap.org/copyright) katkıda bulunanlar (ODbL)
- Harita karoları: [OpenFreeMap](https://openfreemap.org); harita motoru: [MapLibre Native](https://maplibre.org)
- Adres arama: [Nominatim](https://nominatim.org) (genel sunucu; kullanım politikasına uygun olarak yalnızca Ara'ya basınca sorgular)
- Rota: [Valhalla](https://github.com/valhalla/valhalla) genel sunucusu (`valhalla1.openstreetmap.de`)
- Hava durumu: [Open-Meteo](https://open-meteo.com) (ücretsiz, anahtarsız; ticari olmayan kullanım)
- Benzinlikler: [Overpass API](https://overpass-api.de) genel sunucuları (aynı anda en çok iki farklı operatöre sorulur; ilk iyi yanıt kullanılır) ve Nominatim

Bunlar ücretsiz, garantisiz genel servislerdir. Yoğun veya ticari kullanım için kendi sunucunu kur. Uygulama içi hız sınırlama ve önbellek sağlayıcıların tüm kullanıcılar toplamındaki kotasını kanıtlamaz; ayrıntılı üçüncü taraf bildirimleri [NOTICE](../NOTICE) içindedir.
