# TV Gönder (v0.1 – ilk adım)

Tek uygulama: TV'de alıcı, telefonda gönderici (LocalSend benzeri, sadece eşleşmiş telefonlar).

## Derleme
1. Android Studio'da `TvGonder` klasörünü aç (Gradle wrapper'ı kendisi oluşturur).
2. Build > Build APK(s).
3. APK'yı hem telefona hem TV'ye kur (TV için: USB bellek veya "Send Files to TV", ya da ADB).

## İlk kullanım
1. TV: uygulamayı aç > "USB belleği seç" > flash belleğin klasörünü seç.
2. TV: "Telefon ekle" > QR çıkar.
3. Telefon: uygulamayı aç > "TV'yi eşleştir" > QR'ı okut.
4. TV'de "Onayla".
5. Telefon: klasör seç, dosya seç, gönder. Link yapıştırıp "Linki TV'de aç".

## Yazdırma
Telefonda alttaki "Yazdır" sekmesi: PDF veya resim seç, kağıt (A4/A5), yön (dikey/yatay) ve rengi seç, "Yazıcıya gönder". Açılan Android yazdırma ekranında ağdaki Epson'u, sayfa aralığını ve kopya sayısını seç. Yazıcı ve telefon aynı Wi-Fi'de olmalı. Yazıcı görünmezse Ayarlar > Bağlı cihazlar > Yazdırma bölümünde varsayılan yazdırma hizmetini (Mopria) aç.

## Bu sürümde YOK (sıradaki adımlar)
- Telefondan klasör gönderme
- TV'yi otomatik uyandırma / uygulamayı öne getirme
- HTTPS şifreleme (şu an düz HTTP, sadece yerel ağ)
- Otomatik TV bulma (TV'nin IP'si değişirse yeniden eşleştirmek gerekir)
