<!-- v0.8 – 2026-10-12 -->
# TV Gönder (v0.8) — M-ONUR

Tek uygulama: TV'de alıcı, telefonda gönderici (LocalSend benzeri, sadece eşleşmiş telefonlar). Aynı APK hem telefona hem TV'ye kurulur.

## APK
Her değişiklikte GitHub otomatik derler: **Releases** sayfasında `TvGonder-N.apk`.
TV'ye ilk kurulum flaş bellekle; sonraki sürümler telefondan: ⋮ → *TV'deki uygulamayı güncelle*.

## Telefon
- **Link gönder**: kopyalanan link otomatik gelir, *Yapıştır*, *TV'de aç*, son linkler.
- **Karekod okut**: kitapçık vb. karekodu → telefonda aç / TV'de aç / kopyala.
- **Dosya gönder**: fotoğraf/video/belge, kayıt klasörü seçimi, ilerleme çubuğu.
- **Paylaş menüsü**: *TV'ye gönder* ve *TV Gönder ile yazdır* (çoklu seçim).
- **Yazdır**: çoklu fotoğraf/PDF, **Belge tara** (kamera, otomatik kırpma), düzen *Tek / 2'li / 4'lü / 6'lı / Kimlik* (85,6×54 mm), önizleme, A4/A5, yön, renk, *Yazıcıya gönder* veya *PDF olarak kaydet*.
- **Ayarlar** (⋮): otomatik pano linki, varsayılan kağıt/renk, telefonun yazıcı ayarları.
- Eşleştirme *Şimdilik geç* ile atlanabilir.

## TV
- Modern ana ekran: saat, durum, *Dosyalarım*, *Slayt gösterisi*, *Telefon ekle*, *Ayarlar*, **Son gelenler**.
- **Dosyalarım**: USB bellekte kumandayla gezinme; video/müzik oynatıcı, PDF (◀▶ sayfa, OK yakınlaştır), resim (◀▶).
- Linkler: YouTube uygulamasında, diğerleri uygulamanın **kendi tarayıcısında**.
- Yeni dosya gelince "Yeni dosya geldi – Aç" kartı.
- Boşta 5 dk → slayt gösterisi (Ayarlar'dan kapatılabilir).
- Telefondan gelen güncellemeyi "Yükle" ile kurar.

## İpucu
TV'de başka uygulama açıkken gelen linkin ekrana gelmesi için (bir kez, bilgisayardan ADB ile):
`adb shell appops set com.onur.tvgonder SYSTEM_ALERT_WINDOW allow`
