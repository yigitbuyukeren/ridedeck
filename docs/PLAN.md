# RideDeck — Ürün, Tasarım, Teknik Kalite ve Yayın Ana Planı

> **Durum:** İlk iki dalganın belirtilen yerel kontrolleri geçti; ana plan devam ediyor
>
> **Hazırlanma:** 11 Kasım 2026
>
> **Kaynak tabanı:** [`yigitbuyukeren/ridedeck`](https://github.com/yigitbuyukeren/ridedeck), `main`, `0cbd322bdefab999616e0ba148654bc6b8be873f`
>
> **Görsel taban:** 11.11.2026 tarihli 43 emülatör ekranı; `qa-screenshots/ridedeck-tum-ekranlar.png` ve `qa-screenshots/ridedeck-ekran-listesi.md`
>
> **Sürüm:** Kodda `0.6.0 / versionCode 6`; bu yeni bir sürüm veya yayın kararı değildir.
> **Konumlandırma:** Android telefonunu motosiklete özel, hızlı, anlaşılır bir sürüş paneline dönüştüren uygulama.

## 0. Bu belgenin amacı ve uygulama kuralları

Bu belge, birlikte belirlediğimiz **(1) mevcut 43 ekranın tasarım/UX kalitesini yükseltme**, **(2) gerçekten yararlı yeni işlevler ekleme**, **(3) mevcut teknik/yayın engellerini giderme** ve **(4) ileride Play Store + Free/Pro modelini değerlendirme** işlerini tek uygulama sırasına koyar.

- Bu dosya **uygulama emri değildir**. Her sprint başlamadan önce kullanıcı açıkça geliştirme talimatı verir. Kod, repo ayarları, lisans, yayın ve fiyatlandırma kendiliğinden değiştirilmez.
- Projedeki `AGENTS.md`, mevcut `docs/PLAN.md`, `docs/STATUS.md` ve `docs/RELEASE_READINESS.md` korunur. Bu plan mevcut `docs/PLAN.md` üzerine körlemesine yazılmamalı; görevler onunla **birleştirilmeli**. Tarihsel kayıtlar silinmez.
- Kodun mevcut davranışı, eski dokümanlardan üstündür. Test edilmemiş özellikler veya sahada kanıtlanmamış sonuçlar **PASS** diye işaretlenmez.
- Yeni bir backend, API anahtarı, ücretli sağlayıcı, abonelik, tracking/analytics, uygulama mağazası kaydı veya sunucu **varsayılan olarak eklenmez**.
- Kullanıcı dosyaları, imzalama anahtarı, mevcut dal ve commit geçmişi korunur. Otomatik commit/push, release, sürüm artırma, repo private yapma veya lisans değiştirme yoktur.
- Her özellik için **Türkçe + İngilizce**, açık/kapalı izin, gündüz/gece, farklı ekran boyutları, erişilebilirlik ve regresyon değerlendirilir.
- Sürüşte yazı girişi, karmaşık menü veya dikkat dağıtan kontrol eklenmez. Gerekli dokunma alanlarında mevcut 64–84 dp kuralına uyulur. Kullanıcı ayarları dururken yapılır.
- Güvenlik işlevleri hiçbir zaman Pro ödeme duvarı arkasına konmaz; SOS bir güvenlik garantisi olarak pazarlanmaz.

**İşaretler:** `[ ]` planlandı, `[~]` kısmen uygulandı veya doğrulama bekliyor, `[x]` belirtilen yerel kabul kanıtıyla tamamlandı; `D` karar gerekli, `R` yayın engeli. Yerel kabul fiziksel cihaz veya yayın onayı değildir; güncel kanıt [STATUS.md](STATUS.md) içindedir.

---

## 1. Mevcut ürün ve 43 ekranın özeti

**Korunacak güçlü taraflar:** Hız göstergesi solda; navigasyon ve müzik sağda. Yatay sürüş düzeni, büyük işlemler, koyu/mavi tasarım dili, mini harita, sesli yönlendirme, çok duraklı rota, müzik/telefon entegrasyonu, yolculuk kayıtları, SOS test akışı ve TR/EN desteği var.

| Ekran kümesi | Görsel numaraları | Başlıca değerlendirme |
|---|---|---|
| Ana pano | 01–03 | Güçlü temel; gündüz mini harita tema senkronu bozuk; aktif rehberlik bilgi hiyerarşisi iyileştirilebilir |
| Harita/arama/rota | 04–15 | Rota eylemleri iyi; bilgi yoğunluğu, benzinlik listesi, durum geri bildirimi ve okunabilirlik gözden geçirilmeli |
| Müzik | 16–18 | Boş/izin durumları zayıf; gerçek albüm kapağı olmadığı emülatör koşulu ayrı tutulmalı |
| Telefon | 19–25 | Gelen/aktif arama kontrolleri okunaklı; liste, rehber ve tuş takımında boşluk/yerleşim iyileştirilebilir |
| Yolculuk geçmişi | 26–27 | `km/sa` kırpılması kesin sorun; bilgi hiyerarşisi ve kayıtlı rota görünümü eksik |
| SOS | 28–29 | Test modu ve gerçek alarm ayrımı korunmalı; görünür uyarı/erişilebilirlik gözden geçirilmeli |
| Ayarlar | 30–42 | Altı sekme yatayda kısmen gizleniyor; metin, boş alan, izin durumu ve kart sıklığı uyumu değerlendirilmeli |
| İlk açılış | 43 | İzin nedenleri ve doğru geri dönüş/onarım akışı netleşmeli |

**Kesin gözlem / kanıt sınırı:**

- 02: Gündüz temasında mini harita eski gece görünümünde kalıyor; tam harita açılınca güncelleniyor.
- 27: `41 km/` ve `92 km/` gibi birim kırpılmaları var.
- 32: Görünüm sayfasında yalnız tema var; bunun tek başına ayrı kategori olarak kalıp kalmayacağı **karar gerektirir**.
- 43 görüntü **Android 15 emülatöründen** alındı; gerçek fiziksel motosiklet sürüşü, gerçek SMS, interkom ve yüksek Hz donanım davranışını doğrulamaz.
- Yolculuk geçmişinin 27. ekranındaki üç kayıt **örnek veridir**, gerçek sürüş değildir; müzikte yerel test tonu, çağrıda GSM simülasyonu, SOS'ta yalnız SMS göndermeyen test kullanıldı.
- Sağlanan ana kolajdaki küçük görüntüler üzerinden ince hizalama/piksel kararları kesinleştirilmez; değişiklik öncesi ve sonrası **orijinal 2400×1080 PNG'ler** ile karşılaştırılır.

### Değiştirilmeyecek ürün kararları (aksine ayrıca karar verilmedikçe)

1. Solda sabit hız göstergesi ve motosiklete özel yatay panel.
2. Varsayılan sağ sütun **navigasyon + müzik**. Eski iki ayrı kart seçme ayarı ve gün batımı kartı geri gelmez.
3. Tema: otomatik / gündüz / gece; mevcut mavi vurgu ve yüksek kontrast korunur.
4. Dahili harita ve navigasyon, sesli yönlendirme, hava uyarıları, yolculuk geçmişi ve izin temelli telefon/medya çalışması korunur.
5. Çevrimdışı **harita** mevcut; çevrimdışı yeni rota **hesaplama ve arama yok**. Bunlar aynı özellik gibi sunulmaz.
6. GPX, sesli asistan, OBD, bakım takibi, canlı trafik, grup sürüşü, iOS/Android Auto geçmişte kapsam dışı bırakılmış. Yeni onay olmadan bu plana iş olarak dönmez.

---

## 2. Önceliklendirilmiş iş listesi — tasarım ve UX

**Durum sayımı (12 Kasım 2026):** UI/FEAT **16 satır: 3 [x], 12 [~], 1 [ ]**; TECH **11 satır: 2 kod/test tamam (TECH-006, TECH-011), 9 kısmi/bloklu**. Bu satırların hiçbiri fiziksel cihaz/saha kanıtıyla tümüyle kapatılmadı. Güncel MAIN'de Geometry ve Detour çekirdeği ile VM/UI/kaynak bağlantısı birleşik ve ürün kaynağı donduruldu; TR/EN **418/418 anahtar** (yinelenen/eksik yok), sürüm/manifest değişmedi. Hedefli düzeltme kabulü **88 JVM PASS** (MultiStop 13 + NavigationController 75) ve **27/27 UI PASS** (CachedRoute 7, FuelDetour 6, MapPanels 14). Final entegre yerel kapıda debug/release **709/709**, lint 0 hata (debug 109 / release 108 uyarı, 3'er öneri), release/test APK ve ELF/ZIP 16 KB PASS; kanıt `captures/final-integrated-2026-11-12/`. Önceki 187 JVM (186/1 FAIL) ve 58 UI (55/3 FAIL) koşuları başarısızdı; düzeltme yalnız test fixture/import ile yapıldı, ürün kaynağı değişmedi ve eski koşular tarihseldir; 605+605 checkpoint de tarihseldir. 43 ID'lik QA43 seti tamamlandı (`captures/plan-2026-11-12-wave4/qa43/index.json`): **24 gerçek current + 16 FAKE_UI + 3 fake native/probe = 43 ID**, `remainingCurrentGaps` boş; set **43 gerçek yolculuk değildir**. Test-only `Qa43FixtureCaptureTest` build/lint PASS (2 test, 16 FAKE_UI); izole son-3 probe **4/4 PASS** (gerçek MapLibre long-press, yerel source-free stil, ayrı probe DB temizliği, tam probe paketleri). Fixture'lar sistem/yolculuk veya güvenlik kanıtı değildir ve hiçbir UI/FEAT maddesini kapatmaz; kalan 12 [~] maddede eksik koşul yalnız fiziksel/yayın kapısı değildir, gerçek etkileşim/veri/ağ matrisi, EN/büyük yazı ve saha kapıları da açıktır. Tam 43 gerçek ekran, fiziksel cihaz/saha, uzak CI ve sağlayıcı toplu uyumluluğu açık; NO-GO sürer ve sayımlar şişirilmez.


### S0 — Kesin kusurlar (önce bunları düzelt)

#### [x] UI-001 — Mini haritanın tema senkronu (ekran 01–03)
- **Sorun:** Gündüz teması seçildiğinde ana panelde mini harita gece stilinde kalabiliyor.
- **Yap:** Ana tema ve mini haritanın stil state'ini tek kaynaktan yönet; temayı değiştirince harita yeniden açılmadan stil güncellensin. MapLibre yeniden yaratılırken harita kamerası/rota kaybolmasın.
- **Dosyalar:** `ui/MiniMap.kt`, `ui/NavCard.kt`, `ui/Theme.kt`, `data/ThemeController.kt`, `ui/DashboardScreen.kt` (gerekenler kod incelemesiyle kesinleşir).
- **Kabul:** 01↔02 arasında gündüz/gece/otomatik geçiş; navigasyon açık/kapalı; yeniden açılış, 180° dönüş; kart ve tam harita aynı stile sahip. Ekran yakalama ile kanıt.
- **11 Kasım sonucu:** Gözlenen kusur rehberlik dışındaki sabit `MapBackdrop` çizimindeydi; mevcut palete bağlandı. Canlı MiniMap'in mevcut tema akışı değiştirilmedi. Statik tema ve gündüz manevra kontrastı piksel testleri geçti; dururken emülatör rehberliğinde gündüz/gece/otomatik, tam harita eşliği ve gerçek ROTATION_90→270 geçişinde rota korunması gözlendi. Kanıt: `captures/plan-2026-11-11/`; fiziksel cihaz UNVERIFIED.

#### [x] UI-002 — Yolculuk istatistiklerinde kırpılan hız birimi (ekran 27)
- **Sorun:** `TripDetail` kartlarında `34.sp` tek satırlık hız metni sığmıyor.
- **Yap:** **Sayısal değer ve birimi ayrı** ölçülendir (ör. `41` ve daha küçük `km/sa`); esnek boşluk, ihtiyaçta kontrollü küçülme, uygun kısa biçim, uzun yerelleştirmede taşma önleme. Metni sadece keserek gizleme.
- **Dosyalar:** `ui/TripsScreen.kt`, `ui/GaugeFit.kt` (uygun ise), `res/values*/strings.xml`.
- **Kabul:** `km/sa`, `mph`, iki haneli/üç haneli hız, büyük yazı ve dar/geniş ekran; değer/birim tam görünür. İstatistik hesabı değişmez.
- **11 Kasım sonucu:** Değer/birim ayrıldı; gerçek yazı ölçümüyle sınırlı arama Android'in doğrusal olmayan font ölçeklemesini de kapsıyor. Açık satır yüksekliği ve kaydırılan istatistik alanı dikey kesilmeyi giderdi; silme düğmesi sabit kaldı. İlk denemede görülen 1,5×/2× FAIL düzeltildi; 7/7 Compose testi ve 2400×1080 / 1920×1080, TR/EN, km/sa–km/h–mph, 1×/1,5×/2× görüntü kontrolleri geçti. Sayısal hesap ve veritabanı değişmedi.

#### [x] UI-003 — Tutarlı interaktif durum ve dokunma animasyonu
- **Yap:** Ana kartların basılı durumu, ripple, haptik ve odak davranışını denetle. Navigasyon kartında mevcut **0,92 ölçek** etkisini görsel karşılaştır; daha hafif ölçek (ör. **0,98**, kesin karar görsel testle) veya ölçek yerine baskı opaklığı seç. Harita taşması/çift tıklama/yanlış tap olmamalı.
- **Dosyalar:** `ui/Press.kt`, `ui/Haptics.kt`, `ui/NavCard.kt`, `ui/MediaCard.kt`, `ui/PillButton.kt`.
- **Kabul:** Basılı tutunca tüm kart tek parça davranır; 0,92 ve alternatiflerin görüntüleri karşılaştırılır; aktif MiniMap durumu da doğrulanır; sürüşte gereksiz hareketlilik yok.
- **11 Kasım sonucu:** NAV için 0,98 seçildi; diğer çağrıların 0,92 varsayılanı korundu. Harita dokunma katmanı ortak basma state'i kullanıyor; tek erişilebilir eylem bırakıldı. 5/5 dokunma/semantik/haptik politika testi geçti. 0,92–0,98 görselleri karşılaştırıldı; canlı MiniMap basılıyken kart bütün kaldı, taşma görülmedi. Fiziksel titreşim hissi ve D-pad/TalkBack elle turu doğrulanmadı; odak görünümü UI-004'te açık.

### S1 — Var olan ekranların tutarlı tasarım sistemi

#### [~] UI-004 — Ortak tasarım ölçüleri ve bileşenler
- **Yap:** Renk rolleri, durum renkleri (mavi=eylem, yeşil=olumlu, kırmızı=kritik, turuncu=uyarı), tipografi ölçeği, 8/12/16/24/32 dp boşluk ritmi, kart köşeleri, focus/pressed/disabled durumları için denetim yap. Mevcut `MotoPalette`/Compose bileşenlerini **yeniden kullan**, ikinci bir tema motoru kurma.
- **Dosyalar:** `ui/Theme.kt`, `ui/PillButton.kt`, `ui/Press.kt`, `ui/StatusBar.kt`, `ui/SettingsScreen.kt`.
- **Kabul:** Temel ekranlarda rastgele renk/boşluk farkları azaltılmış; güneş altında okunabilirlik için gündüz kontrast testi; erişilebilir yazı ölçeklemesi ve taşma kontrolü yapılmış.
- **11 Kasım inceleme notu / açık:** `MapCredit` gündüz haritasında düşük kontrastlı kalabiliyor; harita atfının okunabilirliği ayrıca ele alınmalı. `Press.kt` içindeki mevcut `indication = null` nedeniyle klavye/D-pad odak göstergesi ve gerçek TalkBack turu bu dalgada doğrulanmış sayılmaz.

#### [~] UI-005 — Ana panel hiyerarşisi ve sağ sütun (01–03)
- **Yap:** Hız alanı büyük ve sabit; navigasyon kartı aktif/pasif hâlde anlaşılır; müzik başlığı ve temel kontroller açık; dock ölçüleri dengeli. Hız limiti, GPS kayıp, varış ve çağrı hâlleri tek tek incelensin. Sağ sütun küçük ekran/çentik/180° yön değiştirmede sığsın.
- **Karar:** Kapsam içinde **sabit NAV + MEDIA** kalıyor. Uygulama kartı/presetler yeni ürün kararı verilmeden yerleşime sokulmaz.
- **Kabul:** 01/02/03 ve dar ekran varyantları; öğeler çakışmaz, önem sırası belirgin; ekranı tek bakışta anlamak mümkün.
- **12 Kasım kısmi kabul (hedefli):** Dock hedefleri font-2'de 64 dp altına inmiyor; sığmayan ray kaydırılıyor ve durum bloğu sabit kalıyor. NavCard pasif hızlı eylemler ve rehberli manevra/varış satırları gerçek ölçümle test edildi. Panels matrisi **27/27 PASS** (landscape; `ridedeck-wave3-panels-device.log`). 320 dp'de dört dock hedefi aynı anda görünmez, kaydırarak erişilir. Tam 43 ekran, fiziksel cihaz ve çok küçük çoklu pencere matrisi açık.

#### [~] UI-006 — Navigasyon ve rota önizleme okunabilirliği (04–15)
- **Yap:** Manevra mesafesi, varış zamanı, toplam süre, rota seçenekleri, `Başlat/Bitir/Yola devam` ilişkisini hiyerarşik düzenle. Rota/benzinlik panelinde içerik çok daralmamalı. Ara durak, rota yükleniyor, rota yok, ağ hatası, varış durumları tutarlı ve erişilebilir olmalı.
- **Kabul:** Arama, yazım önerisi, alternatif rota, çoklu durak, gece/gündüz, yanlış GPS/online durumlarında doğru mesaj; **hareketliyken yazı girişi engeli korunur**.
- **12 Kasım kısmi kabul (hedefli):** Harita paneli gerçek ebeveyn genişliğine göre daralıyor; dar genişlikte hız/limit kontrolleri yığılıyor ve panel `0..parent` aralığında kalıyor. Arama sorgusu sabit, sonuçlar tek `LazyColumn`; rota yükleniyor/güncelleniyor, ağ hatası, varış, benzinlik hata/liste ve tam manevra metni kapsandı. Başlat/Vazgeç/Bitir/Yola devam/benzinlik eylemleri 64 dp altına inmiyor; çok dar panelde eylemler yatay kaydırılıyor. Panels matrisi **27/27 PASS**. Ağ sağlayıcı/stres, gerçek GPS/online matrisi ve tam 43 ekran görsel kabulü açık.

#### [~] UI-007 — Müzik ekranı ve boş/izin durumları (16–18)
- **Yap:** İzin yokken bir satırlık açıklama + doğru sistem ayarına götüren eylem; erişim olup medya yoksa yalın ama öğretici boş durum; çalarken kapak, başlık, zaman ve kontrol hiyerarşisi. Başka müzik kaynağı yükleme/yeni player yapma.
- **Kabul:** İzin reddi/geri alma, boş oturum, duraklat/ileri/geri, kapak geç yüklenmesi, koyu/gündüz tema; ekran kararması/ton sıçraması olmasın. Fiziksel medya oturumu gerektiren durumlar ayrı işaretlensin.
- **12 Kasım kısmi kabul:** İzin/boş durumda öğretici açıklama ve doğru eylem, dar tam ekranda tam genişlikte sabit kontrol çubuğu eklendi. 10/10 Compose testi; gerçek Android font-2, 600×320 dp içinde 64 dp hedefler ve kaydırılır zaman bilgisi doğrulandı. Gerçek oynatıcı/kask ve tüm görsel matris açık.

#### [~] UI-008 — Telefon, rehber ve çağrı ekranı (19–25)
- **Yap:** Rehber satırlarının ve sekmelerin okunabilirliği; seçili sekme kontrastı; tuş takımı büyük hedefler, eşleşen kişi ve silme erişilebilirliği; çağrıda kritik düğmelerin konumu; varsayılan dialer/izin yok hâllerinde yönlendirme.
- **Kabul:** Ekran 19–25; uzun kişi adı/numara, boş liste, büyük yazı; gerçek arama yapmadan emülatörle görsel QA. Gerçek Telecom/Bluetooth davranışı ayrıca test edilir.
- **12 Kasım kısmi kabul:** Paylaşılan ölçüye duyarlı tuş takımı, seçili sekme semantiği, görüşme tuşlarında sabit kapatma, uzun satır yerleşimi ve yeşil eylem kontrastı düzenlendi. Telefon 9/9 + çağrı 4/4 Compose testi; normal/font-2 tuş takımı gerçek uygulamada görüntülendi. Çağrılar sahte callback'lerle test edildi; gerçek Telecom/Bluetooth/kilit ekranı kapıları kapanmadı.

#### [~] UI-009 — Yolculuk geçmişi ekranını yeniden düzenle (26–27)
- **Yap:** Liste ve detay iki panelli yatay düzen korunabilir; liste seçimi, başlık, tarihler ve birincil eylem yeniden dengelenir. İstatistiklerde `Mesafe`, `Toplam süre`, `Hareket süresi`, `Ortalama hız`, `Maksimum hız` açık etiketlerle sunulur. Boş durum açıklaması; kayıt silme için yanlışlıkla silmeye karşı onay ve açık sonuç durumu.
- **Dikkat:** Ortalama hız şu anda `distance / movingMs`; bunu **toplam süre ortalaması** diye etiketleme. Duraklama yaklaşık `duration - movingMs` olarak hesaplanırsa GPS kaybı/ölçüm boşluklarının etkisini kullanıcıya yanlış yansıtmamaya dikkat et.
- **Dosyalar:** `ui/TripsScreen.kt`, `data/TripStats.kt`, `data/TripStore.kt`, `data/TripRecorder.kt`.
- **Kabul:** Boş, tek kayıt, çok kayıt, uzun tarih, mph/km, dar ekran, silme, kaydın devamı; mevcut veritabanı korunur ve şema geçişi gerekirse testlenir.
- **12 Kasım kısmi kabul:** Toplam/hareket süresi ayrımı ve GPS boşluğu açıklaması, silmede açık onay, isteğe bağlı harita eylemi eklendi. 7/7 geçmiş + mevcut sıkı 7/7 istatistik testi geçti; kayıt silme callback'leri sahte, mevcut DB kayıtları korunuyor. Tüm kayıt/devam/süreç matrisi ve fiziksel kullanım açık.

#### [~] UI-010 — Altı ayar sekmesi ve görsel düzen (30–42)
- **Yap:** Mevcut altı kategori (`Genel`, `Görünüm`, `Harita`, `Ses ve bildirimler`, `Güvenlik`, `İzinler ve sistem`) için okunur sekme, seçili durum, yatay kaydırma ipucu, otomatik seçili sekmeye kaydırma ve ayar içeriğinin doğru hizalanması. Açıklama metinleri, kartların dikey yoğunluğu, boşluk ve genişlik ortak kurallara bağlansın.
- **Karar kapısı D-01:** Görünüm'de yalnız tema var. **Altı kategori olarak mı kalsın**, yoksa **Görünüm Genel'e mi birleşsin**? İki alternatifin 2400×1080 görüntülerini karşılaştırmadan kategori silme. Varsayılan: mevcut altıyı koru.
- **Dosyalar:** `ui/SettingsScreen.kt`, `ui/DashboardScreen.kt`, `res/values*/strings.xml`.
- **Kabul:** 30–42 kapsamı, her sekmede üst/orta/alt kaydırma, içerik taşmaması, görünür seçili sekme, TR/EN ve büyük yazı; çıkış/geri dönüşte tutarlı durum.
- **11 Kasım kısmi sonuç:** Altı kategori korundu; seçili sekme ölçülen genişlik/font değişiminde görünür konuma kayıyor, yatay devam ipucu ve erişilebilir sekme grubu eklendi. 5/5 sekme testi geçti. Kart yoğunluğu/tipografi birleştirmesi ve 30–42 tam üst/orta/alt matrisi henüz tamamlanmadı; bu madde açık kalır.

#### [~] UI-011 — Güvenlik/SOS ve ilk açılış mesajları (28–29, 38–43)
- **Yap:** Gerçek SOS ile test modunu ayrı ve net göster; `SMS gitmez` ve başarı/başarısızlık metinleri yanıltıcı olmasın. Konum izni neden gerektiği kısa metinle açıklansın; reddedilirse kurtarma yolu görünsün. İzinler bölümünde gerçek sistem durumunu göster; genel 'her şey güvenli' garantisi verme.
- **Kabul:** İzin reddi/kısmi izin, bildirim kapalı, SOS kapalı, test geri sayımı, iptal, bitiş; ekran okuyucu etiketleri ve kontrast kontrolü; gerçek SMS yollamadan UI testi.

---

## 3. Yeni özellikler — yalnız yararı kanıtlanabilir olanlar

### S2 / İlk dalga

#### [~] FEAT-001 — Gelişmiş Yolculuk Detayı (öne çıkan yeni özellik)
- **Mevcut temel:** Yerel `trips.db` içinde yolculuk özeti **ve GPS noktaları** var (`TripStore.points(tripId)`), yalnız yeni analitik katman ve harita ekranı gerekiyor.
- **Kapsam:** Seçilen yolculuğun harita üzerinde çizimi, başlangıç/bitiş, mesafe, toplam süre, hareket süresi, tahmini durma süresi, ortalama/maksimum hız; sonrasında haftalık/aylık yerel özet **ayrı alt görev**.
- **Uygulama:** Harita yalnız detay açılınca yüklenir; GPS noktaları işlenirken sahte sapma/boşluklar düz çizgiyle yanlış birleştirilmez; çok uzun kayıtlarda örnekleme/çizim performansı ölçülür. Harita karosu internet isterse offline durumu doğru anlatılır. Ağ olmadığı hâlde saklanan GPS noktaları kaybolmaz.
- **Gizlilik:** Kullanıcı açık eylemde bulunmadıkça dışa aktarma veya konum paylaşımı yapılmaz; harita ağ sağlayıcısına istek gidebileceği gizlilik metninde doğru açıklanır.
- **Kabul:** 0/1/çok GPS noktası, boşluk/tünel, bozuk ve saat sırası şaşmış veri, 1 saatten uzun kayıt, uygulama yeniden açılışı, kayıt silme; çizgi/sayı tutarlılığı birim + emülatör testinde doğrulanır.
- **Dosyalar:** `data/TripStore.kt`, `data/TripStats.kt`, `ui/TripsScreen.kt`, uygun yeni `TripMap` bileşeni; testler.
- **12 Kasım kısmi kabul:** 15 iz hazırlığı + 3 GeoJSON testi ve 5 yerel stilli native harita testi geçti. 0/1/çok/aynı nokta, bir saatlik sentetik kayıt, GPS boşluğu, tema/kamera ve kapanma kapsandı. Font-2 dar panelde uyarı/atıf harita dışında; nokta okuması açık eylemden sonra yapılır. Haftalık/aylık yerel özet kodu kapsamlı hedefli testlerle tamamlandı; gerçek dolu DB izi, tüm süreç/uzun sürüş matrisi, fiziksel cihaz ve karo yükleme hatasının stil yüklendikten sonraki ayrımı açık; bu madde tamamlandı sayılmaz.
- **Not:** Şu anki sürüş geçmişi ücretsiz kalır. Bunun gelişmiş yerel analizleri gelecekte olası Pro adayıdır; bugün ödeme duvarı eklenmez.

#### [~] FEAT-002 — “Sürüşe hazır mı?” kontrolü
- **Kapsam:** Dururken açılan hafif bir hazırlık paneli: GPS/konum izni, telefonun konum erişimi, navigasyon için ağ, mevcut çevrimdışı harita kapsamı, sistem TTS durumu, bildirim/medya erişimi, tercihe bağlı SOS yapılandırması.
- **Davranış:** Her satır `Hazır / Eksik / İsteğe bağlı / Bilinmiyor` şeklinde ve **neden/çözüm** ile gösterilir. İzin verildiğinde geri dönüşte anlık güncellenir. Kritik olmayan izin eksikliği temel sürüş ekranını **engellemez**. SOS 'hazır' etiketi dahi mesaj teslimi/kaza algılama garantisi taşımaz.
- **Sunum kararı D-02:** İlk açılışta kısa kontrol mü, ana ekranda dururken erişilen kısa düğme mi? Varsayılan: ilk açılışta isteğe bağlı, daha sonra ayarlardan erişilir; otomatik tam ekran uyarı yok.
- **Kabul:** GPS kapalı, hassas konum yok, internetsiz Wi-Fi, konum hazır, bildirim izinleri yok; her satır doğru ayara gider; tamamlanmış izin ekranı tekrar açılmaz.
- **Dosyalar:** `ui/PermissionIntro.kt`, `ui/SettingsScreen.kt`, `data/LocationRepository.kt`, `data/OfflineMaps.kt`, `data/Voice.kt`.
- **12 Kasım ara durum:** Saf hazırlık modeli ve salt okunur TTS gerçekleri entegre edildi; 14 model + 2 ayar eşleme testi geçti. Bilinmeyen ses ağ gereksinimi/yerel karo deposu hazır sayılmaz, SOS yalnız isteğe bağlı yapılandırmadır. İsteğe bağlı ilk açılış + Genel ayar erişimi ve hareket kilitli panel entegre edildi; UI/geri dönüş kabulü sürüyor.

### S3 / İkinci dalga — bağımlılıklarına göre

#### [~] FEAT-003 — Benzinlik seçiminde gerçek sapma maliyeti
- **Kapsam:** Mevcut 'yakında/rota üzerinde' sonuçlarına **rotaya ek süre ve mesafe** (ör. '+4 dk / +1,8 km') hesaplanabiliyorsa eklenir. Kuş uçuşu mesafeden açıkça ayrılır.
- **Bağımlılık:** Rota/ara durak doğruluğu; API servis limiti, istek sayısı ve önbellek stratejisi.
- **Kabul:** Yolun ters yönündeki benzinliğin farkı gösterilir; hesaplanamıyorsa 'hesaplanamadı' gösterilir, sahte değer üretilmez; çok sayıda sorguyla sağlayıcı zorlanmaz.
- **Dosyalar:** `data/Fuel.kt`, `data/RouteRepository.kt`, `ui/MapScreen.kt`.
- **12 Kasım durumu:** Detour ana kaynağa alındı (6 yeni kaynak + 6 birim test dosyası, **39 test**; önceki izole **49** HTTP/parser dahil tarihseldir). UI bağlantısı FuelDetour **6/6** hedefli emülatör testiyle kabul edildi. Sonuç yalnız **≈** tahminidir; seçilen istasyon için yönlendirilmiş taban çizgisi kullanılır, sabit bağlamda **25 m** köken toleransı vardır ve rehberlik kendiliğinden başlamaz/değişmez. Gerçek sağlayıcı gecikmesi, fiziksel cihaz ve tüm rota matrisi açık; madde tamamlandı sayılmaz.

#### [~] FEAT-004 — Önceden hesaplanmış yolculuğu bağlantı kesilmesine karşı koruma
- **Kapsam:** Seçilmiş yolculuğun mevcut rota geometrisi, kalan durakları, hedefi ve tercihlerini yerel sürümlü saklama; yeniden açıldığında **onayla devam et** önizlemesi; indirilmiş harita bölgesinin yeterli olup olmadığını bildirme.
- **Kritik ayrım:** Bu **tam offline rota hesaplama** değildir; yeni rota/sapma için servis gerekebilir. Çevrimdışı harita içeriğini sağlayıcı kurallarını ihlal ederek önceden topluca indirme.
- **Bağımlılık:** `docs/PLAN.md` #3–4, #7–9.
- **Kabul:** Bağlantı kesik, yeniden hesaplama hatalı, süreç ölümü, eski/bozuk kayıt, durak kaybı, iptal; kendiliğinden takip başlatmaz.
- **12 Kasım durumu:** Geometry çekirdeği ana kaynağa alındı: `NavigationController`, `RideNavigation`, uygulama VM'i ve yeni codec/store/slice/queue/startup/motion dosyaları ile testleri. İzole ağaçta **148 PASS**; Atomic **4** proof ana kapı değildir. Kayıtlı geometri artık yalnız önizlemedir: Başlat, taze/hassas GPS rotanın ilk **50 m** dilimindeyse açılır; STOP kaydı temizler; aksi hâlde yalnız açık **Rotayı güncelle** taze rota ister ve çevrimdışı rota hesaplama yapılmaz. CachedRoute **7/7** hedefli UI testi geçti; gerçek dolu yolculuk, süreç ölümü/fiziksel cihaz matrisi ve çevrimdışı karo yeterliliği açık, madde tamamlanmadı.

#### [ ] FEAT-005 — Hazır panel düzenleri (yalnız kanıt varsa)
- **Karar kapısı D-03:** Mevcut sabit NAV + MEDIA kullanıcı ihtiyacını karşılamazsa **Standart** ve **Navigasyon odaklı** gibi en fazla 2–3 hazır düzen prototiplenir. Önceden kaldırılan iki ayrı üst/alt kart seçimi geri getirilmez.
- **Kabul:** Hız göstergesi ve sürüş güvenliği aynı; kaydedilen tercih restore edilir, sürüşte değiştirilemez; düşük/dar ekran ve tema testleri geçer.
- **Varsayılan:** Bu özellik **ertelenmiş**; tasarım sadeleştirmesi tamamlanmadan yapılmaz.

### Kapsam dışı / yeniden onay gerektiren fikirler

- **GPX alma/verme, bakım hatırlatma, OBD/ECU, sesli AI asistan, gidon kumandası, grup sürüşü, canlı trafik, iOS/Android Auto, park/video:** Eski kararlar nedeniyle **bu sürümde yok**. Fayda/maliyet ve açık kullanıcı kararı olmadan geri eklenmez.
- **Geniş kapsamlı offline rota motoru:** Harita indirme kararlılığı ve sağlayıcı stratejisinden sonra ayrı fizibilite. Hemen Pro vaadi yapılmaz.
- **Reklam:** Sürüş panelinde dikkat dağıtıcı reklam **önerilmez**.

---

## 4. Mevcut teknik borç ve yayın engelleri (orijinal `docs/PLAN.md` ile eşleştir)

Aşağıdaki maddeler **yeniden keşfedilmiş veya otomatik kapanmış işler değildir**; mevcut projenin planındaki durumları korunarak yeni sprintlere bağlanır. Orijinal numaraları karşılık olarak belirtilir.

| ID | Orijinal | İş | Öncelik / çıkış kriteri |
|---|---|---|---|
| TECH-001 | #3 | OpenFreeMap indirmede güncel servis kullanım koşulları; karo/bayt ve eşzamanlı istek sınırları; User-Agent kontrolü | **Bloklu**; bağlayıcı sağlayıcı politikası ve gerçek indirme kanıtı açık |
| TECH-002 | #4 | Çevrimdışı indirme iptal/devam/tekrar deneme/silme; uygulama yeniden açılışında MapLibre alanı eşleştirmesi | **Kısmi**; hedefli kimlik/uzlaştırma/iptal-silme var, gerçek indirme/final CI açık |
| TECH-003 | #6 | Nominatim/Overpass ve yönlendirme servislerinde uygulama geneli hız sınırı, önbellek ve alternatif sağlayıcı stratejisi | **Bloklu**; uygulama-geneli kamu kotası istemciyle kanıtlanamaz, sağlayıcı kararı bekliyor |
| TECH-004 | #7 | Süreç ölümü sonrası kalan durakları ve rota seçeneklerini koruma | **Kısmi**; hedefli main entegrasyon, tam CI/saha açık |
| TECH-005 | #8 | Doğrulanmış internet bağlantısı ile servis erişim hatasını ayırma | **Kısmi**; hedefli JVM/UI var, tüm ağ matrisi açık |
| TECH-006 | #9 | Hız limiti/hava verisi eskime süresi ve geç cevap reddi | **Kod/test tamam** |
| TECH-007 | #10 | Overpass/HTTP iptali ve gerçek gecikme ölçümü | **Kısmi**; Overpass iptali var, diğer HTTP/DNS/gecikme matrisi açık |
| TECH-008 | #11 | Tam ekran çağrı yetkisi kapalıyken normal bildirimden çağrı ekranı erişimi | **Kısmi**; normal bildirim/FSI kodu var, gerçek dialer/kilit matrisi açık |
| TECH-009 | #12 | README/STATUS/RELEASE_READINESS ve sürüm/test kanıtlarını güncel kaynakla eşitleme | **Kısmi**; belge eşitleme sürüyor, final kapılar açık |
| TECH-010 | N12 | Gerçek cihazda navigasyon, çağrı/interkom ve SOS güvenilirliğini doğrulama | **Bloklu/UNVERIFIED**; fiziksel cihaz ve saha matrisi yok |
| TECH-011 | #1,#2,#5 | Ara durak, GPS kilitleri, anons önceliği | **Kod/test tamam**; saha regresyonu ayrı |

**11 Kasım 2026 sağlayıcı doğrulaması (TECH-001/003 açık):** [OpenFreeMap koşulları](https://openfreemap.org/tos/) (14 Ekim 2026 güncellemesi) çevrimdışı indirme başına 3.000 karo veya 50 MB, en çok 4 paralel istek ve uygulamaya özel User-Agent şartı koyuyor. Mevcut 200.000 karo sınırı uygunluk kanıtı değildir; koşullar sağlanmadan gerçek indirme testi yapılmaz. Canlı harita gösterimi ile toplu indirme farklıdır. [Nominatim politikası](https://operations.osmfoundation.org/policies/nominatim/) 1 istek/sn sınırını **tüm uygulamanın toplam kullanıcı trafiğine** uygular; istemci başına gecikme yeterli değildir, servis değişimi uygulama güncellemesi gerektirmeden mümkün olmalıdır.

**Bağlayıcı operatör metinleri ve rehberler (12 Kasım 2026, kaynak: `captures/wave3offline/provider-policy-evidence.md`):** Bağlayıcı [FOSSGIS koşulları](https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/) `overpass-api.de` ve `valhalla1.openstreetmap.de` için en çok iki indirme bağlantısı, uygulama UA’sı ve Valhalla’da **1 istek/sn** ister; ticari kullanım hizmetin çevrimiçi ürünün önemli parçası olmamasıyla sınırlıdır. FOSSGIS sayfası 10 dakika/gün veya “100’e böl” metnini içermez; [OSM Wiki](https://wiki.openstreetmap.org/wiki/Overpass_API) “10.000 sorgu/1 GB/10 dk günlük” ve “100’e böl” ifadelerini **topluluk rehberi** olarak verir. `X-Client-Id` [Valhalla docs](https://valhalla.github.io/valhalla/start/introduction/) önerisidir, FOSSGIS bağlayıcı şartı değildir. [Private.coffee koşulları](https://overpass.private.coffee/) rate limit olmadığını, ticari kullanımın serbest olduğunu ve 10 istek/sn veya 10 eşzamanlı istek üstünde önceden haber verilmesini söyler. `Fuel` hâlâ `kumi.systems` ve `private.coffee` adreslerini paralel yarıştırır; güncel site ikisini aynı operatör olarak gösterir ve sağlayıcı stratejisi onayı olmadan bu kod değiştirilmez. Uygulama içi 1 sn aralığı/önbellek, Nominatim’in tüm kullanıcılar toplamı 1 istek/sn sınırını **global olarak kanıtlamaz**. Yeni backend/ücretli servis/kimlik doğrulama kullanıcı kararı verilmeden eklenmez; “uyumsuzluğu kabul et” yayın seçeneği değildir. Yeni indirme ve resume kapalı kalır; mevcut alanlar korunur ve 5 Compose + 1 native probe testiyle sınırlı doğrulama vardır.

**Karar gereken sağlayıcı seçenekleri:** (1) etkilenen özellikleri kapalı tutmak, (2) kişisel/araştırma kullanımını yayınlanan koşullar içinde sınırlamak, (3) açık onaylı uyumlu sağlayıcı/self-host/ücretli hizmet kararı almak. Yeni sağlayıcı, backend veya kimlik doğrulama kullanıcı kararı olmadan eklenmez; uyumsuzluğu kabul etmek yayın seçeneği değildir.

**11 Kasım uygulama sonuçları:**
- **TECH-001 kısmi:** 3.000 karo ve tahmini 50.000.000 bayt koruması eklendi. SDK indirme yolunda eşzamanlılık/User-Agent birlikte doğrulanamadığından **yeni indirmeler istek gönderilmeden durduruluyor**; mevcut alanlar korunuyor. 9/9 plan testi geçti. Gerçek bayt ölçümü, uyumlu indirme ve TECH-002 yaşam döngüsü hâlâ açık; bu iş tamamlandı sayılmaz.
- **TECH-006 kod/test tamam:** Hız limiti eski oturum/geç yanıtları reddediyor, yeni GPS gelmese de istek kökenli 60 saniyelik tazelik denetleniyor; uyku benzeri elapsed-clock sıçraması ve negatif yaş regresyonları var. Hava verisi 30 dakikadan sonra sonraki kontrolde kaldırılıyor; kapatılan özelliğin geç yanıtı/anonsu uygulanmıyor. 11 hız limiti denetleyici + 9 hava denetleyici testi geçti. Gerçek cihaz uyku/OEM ve gerçek veri doğrulaması ayrı kalır.
- **TECH-008 kısmi:** Normal bildirim dokunma intent'i her zaman var; API 34+ FSI yetkisi kontrol ediliyor. 2 JVM + 2 sahte verili Android bildirim testi geçti. Gerçek/default dialer rolü, kilit ekranı ve bildirim/FSI reddi matrisi henüz doğrulanmadı; yayın engeli açık.
- **TECH-009 kısmi:** README/STATUS ve tarihsel RELEASE_READINESS ayrımı güncellendi. Yerel tüm CI derleme/test/lint ve statik hizalama kapıları geçti; uzak CI, tam 43 ekran ve saha kanıtı yok. Yayın kararı NO-GO olarak kalır.

**12 Kasım ikinci dalga ara durumu:** TECH-005 doğrulanmış varsayılan ağ durumunu ve geç ağ callback'lerini ayırıyor (9 JVM testi). TECH-007 için yalnız Overpass ayna yarışı iptal edilebilir merkezi HTTP yoluna taşındı; 9 loopback testi iptal/kuyruk/soket kapanışı ve HTTP eşliğini doğruladı. Diğer senkron HTTP yolları aynı; gerçek Android ağ gecikmesi/DNS ve bütün sağlayıcıların erişim hatası matrisi tamamlanmadı. Bunlar henüz tüm teknik maddelerin kapanışı değildir.

**12 Kasım üçüncü dalga ara durumu (hedefli):** TECH-004 yedi dosyayla ana kaynağa alındı; kalan duraklar ve rota tercihleri sürümlü kayıttan yalnız önizleme olarak geri geliyor. Katı tip/koordinat/zaman denetimi, GPS bekledikten sonra yaş kontrolü, kullanıcı rota eyleminden sonra dirilmeme ve iptal/geçersiz kayıt temizliği kapsandı; kendiliğinden rehberlik/servis başlatılmıyor. Hedefli JDK 17 **92/92 PASS** (`ActiveTripTest` 11, `ActiveTripPersistenceTest` 9, `MultiStopTest` 13, `NavigationControllerTest` 59); log `/tmp/opencode/ridedeck-wave3-route-integrated.log`. Tam debug/release test/lint/APK kapısı değildir. FEAT-004 geometrisi bu dalgada ayrı ağaçtaydı (o anki test **136**, `/tmp/opencode/ridedeck-wave4-geometry-races.log`); dördüncü dalgada ana kaynağa alındı ve izole **148 PASS** kanıtına ulaştı. Önceki **92+4** tarihsel kanıttır, güncel değildir. Detour o anda adaydı (**45**); dördüncü dalgada ana kaynağa alındı (**39 test**), UI doğrulaması açık. Hazırlık **9 JVM PASS**; hedefli emülatörde Focus dışı wiring 2, panel 2, izin 3, kart 5, tema 1, kontrast 1 **PASS (14/14)**. Önceki Focus koşusu **2 PASS / 7 FAIL** (cihaz toplamı **16 PASS / 23**) tarihseldir; düzeltme sonrası Focus **9/9 PASS** ve Card **5/5 PASS**, güncel log `/tmp/opencode/ridedeck-wave3-focus-fixed-device.log`, önceki başarısız log `/tmp/opencode/ridedeck-wave3-device.log`. UI-004 hedefli Focus main **9 PASS** + Settings **26 PASS**; tam kapsam açık, tamamlanmış değildir. UI-005/006 panel ana kaynağa alındı ve **27 PASS**; tam 43 ekran/kapsam açık, tamamlanmış değildir. Çevrimdışı TECH-002 hedefli ana kaynağa alındı; tam yaşam döngüsü ve gerçek indirme açık. Ayarlar iki yolla ana kaynağa alındı (`SettingsScreen.kt`, `SettingsControlsUiTest.kt`) ve **26 PASS**. Bu paragraf üçüncü dalga anlık görüntüsüdür; Geometry/Detour daha sonra ana kaynağa alındı. Önceki **526/526 debug + release ve 55/55 emülatör** sonucu güncel nihai kanıt değildir; sayılar yeniden kullanılmaz. Bu dalga tam CI ve saha kapılarını bekliyor; release/commit/push yok, **NO-GO** sürer.

**12 Kasım TECH-002 hedefli sonuç:** Kanonik metadata kimliği (şema v2; gerçek tip/aralık denetimi), disk yazma hatasında metadata koruma, nesil ile eski callback reddi, native durum uzlaştırması, iptal/silme ve bayat onay temizliği ana kaynağa alındı. Doğrulama **43 JVM + 5 UI + 1 native PASS**; kanıt `captures/wave3offline/` altındaki loglar ve XML'ler. `NATIVE_COMPLETE` yalnız SDK durum kanıtıdır; karo kullanılabilirliği, gerçek sağlayıcı baytı veya indirme yaşam döngüsü tamamlanmış sayılmaz. Yeni indirme ve mevcut bölge devamı sağlayıcı kapısıyla kapalıdır. TECH-001/003 açık: istemci önbelleği/gecikmesi genel kamu toplu kullanım politikasını tek başına karşılayamaz; yetkisiz sağlayıcı/backend değişikliği yapılmadı.

**12 Kasım dördüncü dalga (main entegrasyonu):** Geometry çekirdeği ve Detour ana kaynağa alındı (izole Geometry **148 PASS**, Atomic 4 proof ana kapı değil; Detour **39 test**). VM/UI/metin bağlantısı tek taze turda ve kabul edilmedi. Son tam checkpoint debug **605** + release **605**, 0 hata, 108 uyarı, 3 öneri (`captures/wave4checkpoint/`); yeni özellik UI kabulü ve tam CI/saha kapıları açık, **NO-GO** sürer.

**12 Kasım beşinci dalga (final entegre yerel kapı):** Ürün kaynağı donduruldu; hedefli düzeltme kabulü **88 JVM + 27 UI PASS**. Final entegre kapıda debug/release **709/709**, lint 0 hata (debug 109 / release 108 uyarı, 3'er öneri), release/test APK, iki arm64 ELF LOAD 16384 ve `zipalign -c -P 16 4` PASS (build-tools 36); kanıt `captures/final-integrated-2026-11-12/`. QA43 seti **24 gerçek + 16 FAKE_UI + 3 fake native/probe = 43 ID** olarak tamamlandı; test-only `Qa43FixtureCaptureTest` build/lint PASS (2 test) ve izole son-3 probe **4/4 PASS**. Fixture sınıfları gerçek yolculuk/sistem kanıtı değildir; 03 source-free boş altlık, 10'da ikincil etiket kırpılmıştır. Önceki 187/186 + 58/55 FAIL koşuları başarısızdı; düzeltme yalnız test fixture/import ile yapıldı, ürün kaynağı değişmedi ve tarihseldir. Cached preview ilk **50 m** ile sınırlı explicit yenileme kullanır, çevrimdışı rota hesabı yoktur. Detour **≈** ve **25 m** köken yeniden kullanımıyla yönlendirilmiş taban çizgisi sunar, kendiliğinden rehberlik başlatmaz. `NATIVE_COMPLETE` yalnız SDK durumudur; sağlayıcı toplu uyumluluğu **OPEN**, yeni indirme/resume **BLOCKED**. Tam 43 gerçek ekran, fiziksel cihaz/saha, uzak CI ve 16 KB runtime açık; **NO-GO** sürer.

**Kritik teknik karar:** OpenFreeMap/Nominatim/Valhalla gibi ücretsiz genel sunucuların çok kullanıcılı ticari uygulamada kullanım koşulları ve sürdürülebilirliği bağımsız olarak doğrulanmadan geniş ölçekli dağıtım vaat edilmez. Bu plan sağlayıcı limitlerini satın alma izni veya yeni backend onayı saymaz.

---

## 5. 43 ekran için kabul ve karşılaştırma matrisi

Yeni görüntüler, mevcut `qa-screenshots/` referans setiyle **aynı durum, tema ve çözünürlükte** alınır.

| Küme | Test durumu / görüntü | Beklenen |
|---|---|---|
| 01–03 | Gece/gündüz/aktif navigasyon | Temalar ve mini harita senkron; hız/rota/müzik dengeli, hiçbir kart taşmaz |
| 04–07 | Harita, klavye, sonuç, Türkçe karakter | Yazı arama dururken kullanılabilir; sonuç ve yükleme/boş/hata durumları anlaşılır |
| 08–10 | Rota önizleme, gündüz/gece, haritadan hedef | Başlat, süre, varış, rota tercihi ve harita noktaları tutarlı |
| 11–15 | Aktif rehberlik, benzinlik, varış | Manevra bilgisi öncelikli; doğru durak; rota/varış state'i çakışmaz |
| 16–18 | Medya izin yok/boş/çalıyor | Hatalı izin kurtarılır; boş ekran anlaşılır; kapak/tint sabit |
| 19–22 | Son arama, rehber, tuş takımı | Uzun isim/sayı, kaydırma, seçili durum, hedef boyutu doğru |
| 23–25 | Gelen/aktif arama ve tuş takımı | Kritik çağrı eylemleri belirgin; güvenli oturum geçişi |
| 26–27 | Boş/kayıtlı sürüş | Birim kırpılmaz; geçmiş ve yeni rota detayı doğru; örnek veri gerçek veri sayılmaz |
| 28–29 | SOS test geri sayım/sonuç | Gerçek SMS gönderilmez; test ayrımı belirgin, iptal edilir |
| 30–42 | Tüm ayarlar, üst/orta/alt kaydırma | Sekmeler, açıklamalar, izin durumu ve tüm kartlar kullanılabilir |
| 43 | İlk izin | İzin gerekçesi, reddetme/yeniden deneme akışı ve sistem sayfasına dönüş doğru |

**Ek kapsam (eski kolajda yok):** Alternatif rota; çoklu ara durak; kayıtlı Ev/İş; yazım düzeltme; uyarı bantları; hız şeridi overlay; çevrimdışı indirme ilerlemesi/iptali; harita alan dışı; gerçek Bluetooth ses yolu; farklı en-boy oranları. Görüntülemeyen testler `UNVERIFIED` olarak kalır.

**Görsel QA cihazları:** 2400×1080 yatay ana referans; en az bir daha dar yatay emülatör; iki tema; 90°/270° yön ve çentikli ekran; yazı büyütme/yerelleştirme. Ölçü önerileri gerçek ekranda denenmeden nihai değildir.

**12 Kasım QA43 kanıt sınıfları:** 43 ID'nin tamamı `captures/plan-2026-11-12-wave4/qa43/` altında toplandı: **24 gerçek current, 16 FAKE_UI bileşen fixture'ı, 3 fake native/probe fixture** (03, 10, 26); `remainingCurrentGaps` boştur. Bu, **43 gerçek yolculuk veya tam system QA değildir**; fixture'lar gerçek navigasyon, çağrı, SMS, medya, DB veya sağlayıcı isteği içermez. Orchestrator 03/10/26 PNG'lerini inceledi: FAKE_NATIVE_FIXTURE rozetleri görünür; 26'da boş haftalık/aylık özet okunur. 03 bilinçli **source-free boş altlık**tır; kartografi, rota karosu veya sürücü işaretinin görünürlüğünü kanıtlamaz. 10'da seçili konum, rota, eylemler ve alt bilgi görünür; ancak ikincil harici uygulama etiketi `Başka uygu…` olarak kırpılmış ve rozet üstte durur. Bu görüntüler yalnız sınıflandırılmış fixture kanıtıdır; toplu görsel PASS, tüm metinler taşmasız veya tam native yolculuk sayılmaz.

---

## 6. UX ve tasarım kararı listesi (uygulamadan önce netleştir)

| Karar | Önerilen varsayılan | Alternatif / tetikleyici |
|---|---|---|
| D-01: Ayar kategorileri | Altı kategori kalsın; sekmeler düzelsin | `Görünüm` Genel'e katılır, ancak önce iki prototip karşılaştırılır |
| D-02: Hazırlık kontrolü | İlk kullanımda isteğe bağlı ve ayarlardan erişilebilir | Sürekli ana ekran düğmesi, ancak alanı sıkıştırıyorsa hayır |
| D-03: Panel düzenleri | Sabit NAV + MEDIA | Kullanıcı geri bildirimiyle en çok üç hazır profil |
| D-04: Kart basma animasyonu | Düşük ölçek/kısa süre ve haptik | Mevcut 0,92 ile A/B görsel kıyas; navigasyon sırasında dikkat dağıtmamalı |
| D-05: Yolculuk istatistiği | Temel geçmiş herkese açık, detay yerel | İleride gelişmiş istatistiklerin Pro olup olmayacağı ücretlendirme aşamasında seçilir |
| D-06: Rota verisi paylaşımı | Tamamen yerelde saklanır | Dışa aktarma için ayrı açık onay ve gizlilik analizi gerekir |

---

## 7. Play Store ve Free/Pro — **daha sonraki ticari aşama**

### [ ] STORE-001 — Ticari kod ve lisans stratejisi

- Uygulamanın tamamının kullanıcı tarafından yazıldığı beyanı başlangıç verisidir; üçüncü taraf bağımlılık/varlık/katkı hakları ayrıca taranır.
- Apache 2.0 altında **önceden yayımlanmış** kodun hakları geriye dönük iptal edilmez. Gelecekteki ticari sürümü kapalı kaynak yapma ve mevcut GitHub reposunun görünürlüğü **ayrı onay gerektirir**.
- Depo private yapmadan önce geçmiş release'ler, NOTICE, atıflar, dağıtılan kodun lisans metinleri, imza anahtarları ve geçmişe sızmış sırlar değerlendirilir. Lisans değişikliği güvenlik sorunu veya geçmiş anahtar sızıntısını kendiliğinden çözmez.
- Repo gizliliği, abonelik ve uygulama mağazası hesabı henüz bu planla değiştirilmez.

### [ ] STORE-002 — Play Store yayın hazırlığı

- Resmî Google Play gereklilikleri **yayın tarihinde yeniden kontrol edilir**: Play Console, geliştirici hesabı, varsa kişisel hesap kapalı test şartı, güncel hedef API, AAB, Play App Signing, Data Safety, gizlilik politikası, içerik/yaş sınıflandırması ve destek adresleri.
- Manifestteki **`READ_CALL_LOG`, `SEND_SMS`, `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`, foreground location service** gibi izinlerin gerçekten gerekli olup olmadığı, rol/istisna koşulları ve mağaza politikasına uygunluğu tek tek kanıtlanır. Kısıtlı izin gerekmiyorsa özellik/fallback yeniden tasarlanır; kısıtı aşma yöntemi üretilmez.
- Uygulama içinde kullandığımız çevrimiçi harita, rota, yer, hava ve sistem TTS sağlayıcılarının veri akışları Data Safety/gizlilik metnine doğru yansıtılır.
- Sürüm güncelleme, aynı `applicationId`, key continuity, Play App Signing / teslim anahtarı, imza arşivi, olası önceki APK kullanıcıları ve geri dönüş stratejisi açıklanır.
- **Yayın kapısı:** Gerçek cihaz navigasyon/SOS/telefon/performans testlerinde çözülmemiş kritik kusur yok; sınırlamalar mağaza metninde dürüstçe belirtilir.

### [ ] STORE-003 — Ücretsiz ve Pro ürün ayrımı (önce analiz, sonra kod)

**Kesin karar değil; başlangıç ilkesi:** Tek RideDeck uygulaması + ücretsiz işe yarar çekirdek + isteğe bağlı Pro. İlk sürümde Pro satışı zorunlu değildir.

| Ücretsiz kalmalı | Gelecekte olası Pro değeri (geliştirilmişse) |
|---|---|
| Hız göstergesi, temel rota/sesli navigasyon | Gelişmiş yerel sürüş analitiği ve uzun dönem karşılaştırmalar |
| Sürüşte güvenlik kilitleri, kritik uyarılar, mevcut temel SOS | Ek tasarım profilleri / ek kişiselleştirme, **ancak kullanışlıysa** |
| Mevcut temel yolculuk kaydı/geçmişi | Gelişmiş kayıt/rota kütüphanesi, **ayrı onay ve kapsam değişikliğiyle** |
| Mevcut medya, temel panel ve gerekli ayarlar | Gerçek ve doğrulanabilir ek fayda sağlayan yeni fonksiyonlar |

- Temel haritayı/temel navigasyonu yalnızca para almak için işlevsiz bırakma; güvenlik veya acil durum işlevlerini ücretlendirme.
- Önce yerel teknik maliyet, kullanıcı değeri ve bakım yükü doğrulansın. Fiyatlar **hipotezdir**, garanti değil: 149 / 199 / 249 TL tek seferlik alternatifleri; abonelik ancak sürekli değer ve gider oluşursa tartışılır.
- Satın alma gerekli olursa Google Play Billing'in güncel sürümü/politikası; satın alımın cihazlar arası geri yüklenmesi, kalıcı hak, çevrimdışı durum, iptal/iade senaryosu ve güvenli yetki kontrolü tasarlanır. Lisans kontrolünü yalnız cihazdaki kolay değiştirilebilen bir bayrağa bağlama.
- Reklam eklememe tercihinin, iş modelinin ve gelir tahminlerinin veriye dayalı değerlendirileceği ayrı bir ticari karar kapısı oluştur.

---

## 8. Sprint planı ve bağımlılıklar

| Sprint | Öncelik | İçerik | Çıkış ölçütü |
|---|---|---|---|
| **0 — Baseline** | Şimdi | Mevcut kod/CI/screenshot referanslarını sabitle, değişiklikleri doğrula | Kullanıcı değişiklikleri korunur; HEAD, test kapsamı, 43 ekran listesi kayda alınır |
| **1 — Kritik görsel düzeltmeler** | P0 | UI-001, UI-002, UI-003 | Gündüz mini harita doğru; `km/sa` taşmıyor; baskı animasyonunda taşma yok |
| **2 — Mevcut UX iyileştirmeleri** | P1 | UI-004…UI-011, D-01 | Ana ekran, rota, müzik, telefon, ayarlar, SOS/izin akışları görsel regresyondan geçer |
| **3 — Yolculuk detay ürünü** | P1/P2 | FEAT-001 + TripsScreen tasarımı | Kaydedilmiş GPS noktaları ile yerel harita/istatistik; eski kayıt bozulmaz |
| **4 — Sürüşe hazırlık özeti** | P2 | FEAT-002, D-02 | Gerçek durum/izinlere göre değişen, abartılı garanti vermeyen hazırlık paneli |
| **5 — Altyapı kararlılığı** | Yayın engeli | TECH-001…TECH-009 | Çevrimdışı harita, sağlayıcı, ağ, çağrı, veri eskime işlerinin kabul testleri geçer |
| **6 — Koşullu ileri işlevler** | Daha sonra | FEAT-003, FEAT-004; FEAT-005 yalnız onayla | Kullanım değeri/maliyet ve bağımlılıklar tamamlandıysa eklenir |
| **7 — Cihaz doğrulaması ve beta** | Yayın engeli | TECH-010 + 43 ekran QA + kapsam dışı senaryolar | Doğrulanmış/başarısız/denenmemiş gerçek cihaz sonuçları ayrıdır |
| **8 — Play Store ve ticari karar** | Ayrı onay | STORE-001…STORE-003 | Politika, hukuki haklar, maliyet, Free/Pro kararı; açık yayın onayı gerekir |

**Paralellik:** Sprint 5 içindeki sağlayıcı/harita sorunları yayın engeli olduğu için 3–4 sürerken ayrı kolda analiz edilebilir; fakat onları çözmeden genel Play Store sürümü 'hazır' sayılmaz.

### 11 Kasım uygulama oturumu — bağımlılık ve doğrulama sırası

- **Taban:** `main`, `0cbd322bdefab999616e0ba148654bc6b8be873f`; başlangıçta yalnız bu plan kullanıcı tarafından değiştirilmişti. Kullanıcı içeriği korunur.
- **İlk hazır işler:** UI-001, UI-002 ve UI-003 ayrı worktree/oturumlarda paralel uygulanır. UI-001 ve UI-003 aynı `NavCard.kt` dosyasına dokunabileceği için sonuçları incelemeyle birleştirilir.
- **Sonraki bağımlılıklar:** UI-004 ortak ölçüler → UI-005…UI-011 ekran düzeni; UI-002/UI-009 → FEAT-001; UI-011 ve gerçek izin/durum kaynakları → FEAT-002. TECH-001…009 analizi bu zincirlerden bağımsız yürüyebilir; FEAT-004 için TECH-001/002/004/005/006/007 ve rota kurtarma kanıtı gerekir.
- **Darboğaz:** Gradle ve emülatör tek doğrulama kuyruğunda; uygulama oturumları kendi başlarına derleme/emülatör başlatmaz. Paralellik, hazır bağımsız iş ve entegrasyon kapasitesine göre ayarlanır.
- **Birleştirilen işler:** UI-010'un sekme alt işi, TECH-001 güvenli indirme engeli, TECH-006 tazelik ve TECH-008 bildirim erişimi alt işleri hedefli testlerle birleştirildi. Yukarıdaki kısmi maddelerin kalan kabul/yayın kapıları açık tutuldu.
- **Ayrı kapılar:** FEAT-005 ertelenmiş kalır; STORE işleri, dış servis değişikliği, fiziksel cihaz/saha, gerçek SMS/arama ve yayın bu oturumda kendiliğinden onaylanmış sayılmaz. Push/release/yayın yapılmaz.
- **Baseline kanıtı:** JDK 17, `testDebugUnitTest`: 436 test, 0 hata/atlama; derleme girdilerinin çoğu `UP-TO-DATE`, test görevi çalıştı. `emulator-5580` üzerinde 2400×1080 önce görüntülerinde gündüz kartının gece çizimi ve `km/sa` kırpılması yeniden görüldü; 0,92 basılı kart görüntüsü alındı. Geçici kanıt dizini: `/tmp/opencode/ridedeck-sprint1/`.
- **Son kabul:** Debug/release 470'er birim testi, iki lint (0 hata, 95'er uyarı, 3'er öneri), debug/release/test APK ve ELF/ZIP 16 KB statik kontrolleri PASS. Altı etkilenen Android test sınıfı 21/21 PASS. Yerel kanıtlar Git dışında `captures/plan-2026-11-11/` içinde korundu. FEAT-001/002 dahil planın kalan işleri ve tam 43 ekran/saha matrisi tamamlanmadı.
- **İkinci dalga başladı:** Doğrulanmış 470/21 kaynak tabanı ayrı çalışma ağaçlarına taşındı. UI-007, UI-008, UI-009, UI-011, FEAT-001'in yerel iz hazırlığı ve TECH-005'in doğrulanmış bağlantı kaynağı bağımsız uygulanıyor. Harita arayüzü iz hazırlama API'sinden sonra, FEAT-002 izin/bağlantı kaynakları netleştikten sonra bağlanacak. Bu devam eden işler ilk dalganın PASS sonuçlarıyla tamamlanmış sayılmaz.

---

## 9. Her geliştirme için zorunlu kalite ölçütleri

### Kod/test

- [ ] Sprint başlangıcında `git status --short`, HEAD SHA ve mevcut kullanıcı değişiklikleri kaydedildi.
- [ ] Değişiklik yalnız ilgili bileşenleri etkiliyor; **navigasyon/GPS/SOS/çağrı servisi bozulmadı**.
- [ ] İlgili Kotlin birim testleri ve regresyon testleri eklendi/güncellendi.
- [ ] TR/EN string kaynakları uyumlu, metinler kesilmiyor; ekran okuyucu ve kontrast incelendi.
- [ ] JDK 17 + proje Gradle wrapper ile `./gradlew test lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest` başarılı. Hata/uyarı sayıları gerçekte neyse rapora yazıldı.
- [ ] Gerekli hâllerde `connectedDebugAndroidTest` **gerçekten koşturuldu**; yalnız test APK'sının derlenmesi PASS cihaz testi sayılmadı.
- [ ] İlgili ekranlar güncel emülatörden yeniden alındı; 43 ekran baseline ile **önce/sonra** yan yana karşılaştırıldı.
- [ ] Mevcut tasarım eylemleri (dokunma/uzun basma/kaydırma) ekran okuyucu, büyük font, haptik kapalı ve sürüş kilidi ile test edildi.
- [ ] GPS kapalı, internetsiz Wi-Fi, uçak modu, bildirim izni reddi, ekran kilidi ve uygulama yeniden açılışı gibi geçişler hedeflenen özellik için test edildi.
- [ ] Harita performansı, pil ve sıcaklık için ölçüm varsa cihaz/süre/koşullar belirtiliyor; ölçülmediyse performans iddiası yok.
- [ ] `docs/STATUS.md` ve ilgili README/PLAN bölümleri **gerçek kanıta göre** güncellendi; PASS/FAIL/UNVERIFIED açık.
- [ ] Yan etki/commit/push/sürüm/release ancak açıkça istenirse yapıldı.

### Saha testi ve güvenlik

- **Emülatör** üzerinden GPS rota simülasyonu, çağrı simülasyonu, SOS'un SMS göndermeyen test modu güvenli doğrulama içindir.
- Gerçek motosiklet sürüşünde telefon ekranı/ayarlarıyla uğraşma; sürüşün gerektirdiği testlerde ayrı gözlemci, izinli ve güvenli koşullar veya kapalı kontrollü ortam kullan. Kasıtlı düşme/kaza, tehlikeli hızlanma veya trafik içinde dikkat dağıtıcı test yok.
- Gerçek SOS/SMS ve varsayılan arama işlevleri yalnız izin/roller/yetkili numaralar ve uzman gözetimli güvenli test prosedürüyle değerlendirilir; test modu gerçek teslim kanıtı değildir.
- Saha raporuna model, Android sürümü, build SHA, tarih, çevrimiçi/çevrimdışı, test türü ve sonuç yazılır.

---

## 10. Açık kararlar ve ilerleme takibi

| Karar / bağımlılık | Şimdiki durum | Kapanma koşulu |
|---|---|---|
| D-01 Ayarlarda 6 mı 5 kategori mi? | **Açık** — 6 varsayılan | Yan yana görsel + okunabilirlik kararı |
| D-02 Hazırlık kontrolüne erişim | **Açık** — isteğe bağlı | İlk açılış ve ayar akışının prototipi |
| D-03 Panel düzenleri | **Ertelenmiş** | Kullanıcı geri bildirimiyle somut ihtiyaç |
| D-04 Navigasyon kartı basma animasyonu | **NAV için 0,98 seçildi** | 0,92 karşılaştırması, 5 dokunma/semantik testi ve canlı MiniMap basılı görüntüsü; diğer kontrollerin varsayılanı korunur |
| D-05 Gelecek Pro özellikleri | **Açık** | Ürün değeri ve maliyet kanıtı, kullanıcı onayı |
| D-06 Rota paylaşımı/export | **Kapsam dışında** | Yeni açık onay ve gizlilik/format analizi |
| Play Store yayın tarihi | **Belirsiz** | Tüm yayın engelleri + gerçek cihaz QA + açık izin |

### Her sprint sonunda rapor şablonu

```md
## RideDeck Sprint N — [ad]
- Baz commit / sonuç commit: ...
- Değişen dosyalar: ...
- Tamamlanan görev ID'leri: ...
- Birim test: ... PASS / ... FAIL / ... SKIP
- Lint: ... hata / ... uyarı
- Debug/Release/AAB: ...
- Emülatör: ... cihaz, ... senaryo, ... PASS/FAIL
- Fiziksel cihaz: ... PASS/FAIL/UNVERIFIED
- Görseller: önce/sonra yolları
- Bilinen riskler: ...
- Kullanıcıya açık kararlar: ...
- Commit/push/release: yapılmadı (ayrıca istenmediyse)
```

## 11. Bir yapay zekâ ajanına verirken uygulama talimatı

> Önce `AGENTS.md`, `docs/STATUS.md`, mevcut `docs/PLAN.md`, `docs/RELEASE_READINESS.md` ve **bu belgeyi** oku. Mevcut işaretleri doğrulayıp yalnız kullanıcı tarafından seçilen sprinti uygula. Başlamadan önce git çalışma ağacını denetle, güncel kodla bu planın farklı olduğu noktaları raporla. Sadece belirtilen özellik/düzeltme kapsamına gir; yeniden tasarım bahanesiyle tüm mimariyi değiştirme. Eski planı silme veya ezme. İlgili testleri, TR/EN metinlerini, emülatör görüntülerini ve sonuç raporunu tamamla. Gerçek cihazda test edilmeyen işlevleri 'çalışıyor' diye pazarlama. Commit, push, lisans/visibility değişimi, Play Store yayını veya ödeme entegrasyonuna açık talimat olmadan girişme.

---

### Kaynaklar ve kanıtlar

- [RideDeck GitHub deposu](https://github.com/yigitbuyukeren/ridedeck)
- Proje içi `AGENTS.md`, `README.md`, `docs/STATUS.md`, `docs/PLAN.md`, `docs/RELEASE_READINESS.md`, `docs/EKSIKLER.md`
- 43 ekranın `qa-screenshots/ridedeck-ekran-listesi.md` açıklaması ve gerçek emülatör çıktıları
- `ui/TripsScreen.kt`, `data/TripStore.kt`, `data/TripStats.kt`, `ui/DashboardScreen.kt`, `ui/MapScreen.kt`, `ui/SettingsScreen.kt`, `ui/NavCard.kt`, `data/DashLayout.kt`

**Son karar:** Önce mevcut ürün deneyiminin kalitesi, ardından yerel ve değerli yeni işlevler, sonra altyapı/saha güvenilirliği, en son Play Store ve Free/Pro gelir modeli. Plan tek başına kod değiştirme yetkisi vermez.
