// v0.21 – 2026-10-15: uygulama içi kullanım kılavuzu (telefon: ⋮ menüsü, TV/tablet: Ayarlar)
package com.onur.tvgonder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Kılavuz metni: (başlık, satırlar). "• " ile başlayan satır madde, "1." ile başlayan adım. */
object GuideText {
    val sections: List<Pair<String, List<String>>> = listOf(
        "1. Kurulum ve ilk ayar" to listOf(
            "Aynı uygulama telefona, TV'ye ve tablete kurulur. Fare için TV'ye ayrıca küçük \"TV Gönder Fare\" eklentisi kurulur.",
            "1. Telefon: TvGonder APK'yı aç, Yükle. \"Bilinmeyen kaynak\" uyarısında izin ver.",
            "2. TV (ilk kez): APK'yı flaşa kopyala, TV'ye tak, dosya yöneticisinde aç, Yükle.",
            "3. TV'de uygulamayı aç; takılı USB kayıt yeri olarak otomatik seçilir. USB yoksa \"USB belleği seç\" → USB veya TV hafızası.",
            "4. Eşleştirme: TV'de Telefon ekle → QR çıkar. Telefonda \"QR kodu okut\" → TV'de Onayla.",
            "5. Telefonun üstünde yeşil \"Bağlı\" görünüyorsa hazırsın. \"Şimdilik geç\" ile eşleşmeden de gezebilirsin.",
        ),
        "2. Telefon ana ekranı" to listOf(
            "• Üstte seçili cihaz ve durumu: Bağlı / TV'ye ulaşılamıyor / TV ağda aranıyor (modem IP değiştirdiyse kendisi bulur).",
            "• \"Nereye gönderilsin?\": birden fazla cihaz eşleşmişse dokunduğun cihaz seçilir.",
            "• Kartlar: Fare / Kumanda, TV'deki dosyalar, Link gönder, Dosya gönder, Gönderimler, Son linkler.",
            "• ⋮ menüsü: cihaza geç, yeni cihaz ekle, güncelle, Fare eklentisini gönder, Gönderim geçmişi, Ayarlar, Kullanım kılavuzu, alıcı yap (tablet), çıkış.",
        ),
        "3. Link ve karekod" to listOf(
            "• Kopyaladığın link, uygulamayı açınca kutuya gelir; \"TV'de aç\" de. Yapıştır düğmesi de var.",
            "• YouTube linkleri TV'de YouTube'da, diğerleri TV Gönder'in tarayıcısında açılır.",
            "• Karekod okut (kamera): kitaptaki kodu okut.",
            "• Resimden / PDF'ten karekod oku: sayfadaki tüm kodlar bulunur; önizlemede kırmızı numaralar listeyle aynıdır.",
            "• Sonuç: Telefonda aç, TV'de aç veya Kopyala. Kodda link yoksa \"Link değil\" yazar.",
            "• Paylaş → \"TV Gönder ile karekod oku\": fotoğraftan doğrudan okur.",
        ),
        "4. Dosya gönderme" to listOf(
            "• Fotoğraf / Video, Müzik (MP3), Belge / Diğer: birden fazla seçilebilir.",
            "• Klasör (toplu): bir klasörün tamamı alt klasörleriyle gider.",
            "• Kaydedilecek yer → Değiştir: TV'de klasör seç veya \"+ Yeni klasör\" ile ad yazıp oluştur.",
            "• Dosya adına gönderim tarihi eklenir (ör. 2026-10-15 15.30 - foto.jpg); Ayarlar'dan kapatılır.",
            "• Başka uygulamada Paylaş → \"TV'ye gönder\": uygulamayı açmadan gider.",
        ),
        "5. Yazdırma" to listOf(
            "1. Yazdır sekmesi: Fotoğraf seç, Dosya / PDF veya Belge / kimlik tara (kamera, kenarları kırpar).",
            "2. Sıra: ↑ ↓, çıkar: ✕. Düzen: Tek, 2'li, 4'lü, 6'lı veya Kimlik (ön-arka gerçek kart boyutu).",
            "3. Kağıt A4/A5, Dikey/Yatay, Renkli/Siyah-beyaz; önizlemede ◀ ▶ ile kağıtları gez.",
            "4. Yazıcıya gönder: Android ekranında yazıcı, sayfa ve kopya seçilir; telefon son yazıcıyı hatırlar.",
            "• PDF olarak kaydet: seçilenleri tek PDF yapar. Paylaş → \"TV Gönder ile yazdır\" da çalışır.",
        ),
        "6. Fare / Kumanda" to listOf(
            "• Parmağı kaydır: imleç. Dokun: tıkla. Basılı tut: uzun bas. Sağdaki ↕ şerit: kaydır.",
            "• Geri, Ana ekran, ⏮ ⏪ ⏯ ⏩ ⏭, ses −/sessiz/+.",
            "İlk kullanım (bir kez):",
            "1. Telefonda ⋮ → Fare eklentisini gönder → TV'de Yükle, sonra tekrar Yükle.",
            "2. TV'de Ayarlar → Telefonla fare kontrolü → Aç.",
            "3. Erişilebilirlik ekranında \"TV Gönder Fare\" → anahtarı Açık yap → İzin ver.",
            "• Fare ekranında kırmızı uyarı yoksa hazırdır. Bazı TV uygulamaları imleçle tıklamaya tepki vermez; onlarda medya ve Geri düğmelerini kullan.",
        ),
        "7. TV'deki dosyaları telefona indirme" to listOf(
            "• \"TV'deki dosyalar\" kartı: klasörleri gez; dosyaya dokun, iner.",
            "• Çoklu: ☑ Seç → işaretle veya Tümü → \"Seçilenleri indir\".",
            "• İnenler telefonda İndirilenler/TvGonder klasörüne gider.",
        ),
        "8. TV / tablet ekranı" to listOf(
            "• Kumanda: oklar gezinir, OK seçer, Geri bir önceki ekrana döner.",
            "• Ana ekran: Dosyalarım, Slayt gösterisi, Telefon ekle, Ayarlar, resimli Son gelenler. Yeni dosya gelince \"Aç\" kartı çıkar.",
            "• Dosyalarım: küçük resimler, filtre (Video / Müzik / Resim / PDF), Ara, ☑ Seç / Sil.",
            "• Resim: ◀ ▶ önceki/sonraki, ▲ ▼ döndür (hatırlanır), OK tam ekran ↔ tamamı.",
            "• PDF: ◀ ▶ sayfa, OK yakınlaştır, ▲ ▼ kaydır.",
            "• Video: kaldığı yerden devam eder. Müzik: klasördeki şarkılar sırayla çalar (karıştır, tekrar).",
            "• Slayt gösterisi: TV 5 dk boşta kalınca başlar; herhangi bir tuş çıkarır.",
            "• Resim ve video tam ekran açılır; oran bozulmaz, kenar kırpılır. Ayarlar → Video/Resim görüntüsü → Sığdır.",
        ),
        "9. Tablet: alıcı ve verici" to listOf(
            "1. Tablete kur → Şimdilik geç → ⋮ → Bu cihazı alıcı yap → Alıcı yap.",
            "2. Tablette Telefon ekle → telefonda ⋮ → Yeni cihaz ekle → QR'ı okut → Onayla.",
            "3. Telefonda \"Nereye gönderilsin?\"den tableti seç.",
            "• Tabletten TV'ye göndermek: tablette \"Gönder\" kartı → ⋮ → Yeni cihaz ekle → TV'deki QR.",
            "• Geri dönmek: tablette Ayarlar → Gönderici moduna dön.",
        ),
        "10. Güncelleme" to listOf(
            "1. Telefona en yeni TvGonder APK'yı kur (GitHub Releases); ayarlar silinmez.",
            "2. TV/tablet: telefonda cihazı seç → ⋮ → uygulamasını güncelle → cihazda Yükle, sonra tekrar Yükle.",
            "• Fare eklentisi yalnız ilk kurulumda gönderilir.",
            "• Sürüm: telefonda ana ekranın en altı, TV'de Ayarlar'ın en altı.",
        ),
        "11. Sorun giderme" to listOf(
            "• \"TV'ye ulaşılamıyor\": TV açık ve aynı Wi‑Fi'de mi? ⋮ → Bağlantıyı yenile.",
            "• \"Kayıt yeri seçilmemiş\": USB'yi tak, TV'de USB belleği seç.",
            "• TV'de Yükle çalışmıyor: açılan ekranda TV Gönder'e bilinmeyen kaynak izni ver.",
            "• Fare ekranında kırmızı uyarı: bölüm 6'daki ilk kullanım adımları.",
            "• Resim ters: ▲ veya ▼ ile döndür. Kenar kesik: OK veya Ayarlar → Sığdır.",
            "• Yazıcı yok: ⋮ → Ayarlar → Telefonun yazıcı ayarlarını aç, hizmeti aç.",
        ),
    )
}

/** Telefon: tam ekran kılavuz; başlığa dokununca bölüm açılır. */
@Composable
fun GuideDialog(onClose: () -> Unit) {
    var open by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖  Kullanım kılavuzu", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMain)
                TextButton(onClick = onClose) { Text("Kapat") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(GuideText.sections) { i, (title, lines) ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
                            .clickable { open = if (open == i) -1 else i }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (open == i) Accent else TextMain)
                            Text(if (open == i) "▲" else "▼", color = TextMuted)
                        }
                        if (open == i) lines.forEach { GuideLine(it, 15) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideLine(s: String, size: Int) {
    val head = !s.startsWith("•") && !s.first().isDigit()
    Text(
        s, style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = (size + 7).sp),
        color = if (head && s.endsWith(":")) Accent else TextMain,
    )
}

/** TV/tablet: kumandayla aşağı yukarı gezilen kılavuz (her bölüm odaklanabilir kart). */
@Composable
fun TvGuide() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 64.dp, vertical = 36.dp)) {
        Text("📖  Kullanım kılavuzu", style = MaterialTheme.typography.headlineMedium, color = TextMain)
        Text("▲ ▼ ile gez, Geri ile çık.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth(0.85f)) {
            itemsIndexed(GuideText.sections) { i, (title, lines) ->
                TvCard(Modifier.fillMaxWidth(), focusRequester = if (i == 0) first else null, padding = PaddingValues(22.dp), onClick = {}) { f ->
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp), color = if (f) Accent else TextMain)
                    Spacer(Modifier.height(8.dp))
                    lines.forEach { GuideLine(it, 18); Spacer(Modifier.width(0.dp).height(4.dp)) }
                }
            }
        }
    }
}
