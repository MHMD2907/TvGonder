// v0.51 – 2026-11-13: kılavuz: TV'ye canlı yazı; kurulum kontrolü; göreve göre bölümler
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

/** Kılavuz metni: (başlık, satırlar). "• " madde, "1." adım, ":" ile biten satır ara başlık. */
object GuideText {
    val sections: List<Pair<String, List<String>>> = listOf(
        "⭐ İlk kurulum (bir kez, 5 dakika)" to listOf(
            "Telefona ve TV'ye aynı TV Gönder uygulaması kurulur. TV'ye ayrıca küçük \"TV Gönder Fare\" eklentisi kurulur (TV Gönder'in içinde gelir).",
            "1. Telefona TvGonder APK'yı kur.",
            "2. TV'ye ilk kez: APK'yı flaşa kopyala → TV'ye tak → TV'nin dosya yöneticisinde aç → Yükle. Sonraki güncellemeler telefondan gider.",
            "3. Eşleştir: TV'de TV Gönder → Telefon ekle → QR çıkar. Telefonda \"QR kodu okut\" → TV'de Onayla. Telefonun üstünde yeşil \"Bağlı\" yazmalı.",
            "4. TV kumandası: telefonda \"TV kumandası (hızlı)\" → Eşleşmeyi başlat → TV'de çıkan 6 haneli kodu yaz → Eşleştir.",
            "5. Fare: TV'de TV Gönder ana ekranındaki sarı uyarıya bas (ya da Ayarlar → Fare → Kur) → Yükle → açılan listede \"TV Gönder Fare\" → Aç → İzin ver.",
            "6. Bu kılavuzun en üstündeki \"Kurulum kontrolü\"nde hepsi ✓ olmalı. ✗ olanın yanındaki düğme onu düzeltir.",
        ),
        "📤 Dosya ve link gönder" to listOf(
            "Link:",
            "1. Linki kopyala → TV Gönder'i aç (link kutuya kendiliğinden gelir) → \"TV'de aç\".",
            "• YouTube linki TV'de YouTube'da açılır (YouTube açıkken bile). Diğer linkler TV Gönder'in tarayıcısında açılır.",
            "• Başka uygulamada Paylaş → \"TV'ye gönder\" de olur.",
            "Dosya:",
            "1. Fotoğraf / Video, Müzik, Belge / Diğer ya da Klasör (toplu) → seç → gider.",
            "2. Kaydedilecek yer → Değiştir ile TV'de klasör seç ya da yeni klasör aç.",
            "• Büyük dosya gönderirken telefon uyumaz; gönderim yarıda kalmaz.",
            "• TV'de yeni dosya gelince \"Aç\" kartı çıkar. Sonra Dosyalarım'dan da açılır.",
        ),
        "🖱️ Telefonla fare ve kumanda" to listOf(
            "Fare (her uygulamada, TV Gönder kapalı olsa bile):",
            "1. Telefonda Fare / Kumanda → üstte \"✓ Fare her uygulamada çalışıyor\" yazmalı. Yazmıyorsa çıkan \"TV'de Fare eklentisini aç\" düğmesine bas.",
            "2. Parmağını kaydır: imleç gezer. Dokun: tıklar. Basılı tut: uzun basar. Sağdaki ↕ şerit: sayfayı kaydırır.",
            "• Tıklamanın gidip gitmediği altta yazar: \"uygulandı ✓\" / \"düğmeye basıldı ✓\". \"TV kabul etmedi\" yazarsa orada düğme yoktur (oyun/video alanı); yön tuşları + OK'i dene.",
            "Kumanda:",
            "• ◀ ▲ OK ▼ ▶, Geri, Ana ekran, oynat/sar, ses tuşları aynı ekranda. YouTube ve TV uygulamaları imleçten çok yön tuşlarıyla rahat kullanılır.",
            "• \"TV kumandası (hızlı)\" kartı: TV'nin kendi kumandasının tamamı (güç, kaynak, rakamlar, renkli tuşlar, kanal).",
            "• Kumanda bağlantısı koparsa bir tuşa basınca kendisi yeniden bağlanır.",
        ),
        "⌨️ TV'ye yazı yaz" to listOf(
            "1. Telefonda Fare / Kumanda (ya da TV kumandası hızlı) açık olsun.",
            "2. TV'de yazı kutusunu seç (YouTube'da 🔍, Play Store'da arama, tarayıcıda adres çubuğu).",
            "3. TV \"Mobil cihazınızdaki klavyeyi kullanın\" der; telefonda klavye kendiliğinden açılır. Yaz: harfler anında TV'de görünür (Türkçe harfler dahil). Bitince Ara ↵.",
            "• TV kutuyu bildirmezse yazı \"Gönder\" ile harf harf gider (Türkçe harfler en yakın harfe çevrilir).",
            "• Telefonda TV Gönder kapatılınca 30 sn sonra bağlantı bırakılır; TV'nin kendi ekran klavyesi geri gelir, kumandayla yazabilirsin.",
        ),
        "📺 Ekran yansıtma" to listOf(
            "Telefonun ekranını TV'de göster:",
            "1. \"Ekranı TV'de göster\" → izinleri ver → Şimdi başlat. Telefonda ne açarsan TV'de görünür, ses TV'den gelir.",
            "2. Durdurmak için aynı karta ya da bildirimdeki Durdur'a bas.",
            "• İlk seferde \"Diğer uygulamaların üzerinde göster\" izni ver: yansıtma sürerken telefon ekranı kapanmaz.",
            "• YouTube/Spotify sesini başka uygulamaya vermez (görüntü gelir, ses gelmez); YouTube için link gönder. Netflix gibi korumalı uygulamalar siyah görünür.",
            "TV'nin ekranını telefonda gör ve yönet:",
            "1. \"TV ekranını gör\" → TV'de çıkan izin penceresinde kumandayla Başla.",
            "2. Telefonda TV görüntüsüne dokununca TV'de oraya tıklanır. Kapat / Geri / Ana ekran sağ sütunda.",
            "• TV'de başka uygulama açıkken de çalışır.",
        ),
        "📦 Uygulama kur ve aç" to listOf(
            "Telefondaki uygulamayı TV'ye kurmak:",
            "1. \"📦 Telefondaki uygulamayı TV'ye kur\" → uygulamayı seç → TV'ye gider.",
            "2. TV'de gelen dosyaya bas → Yükle.",
            "• Telefon önce uygulamanın TV'de çalışıp çalışmayacağına bakar. \"TV'ye uygun değil\" derse çıkan penceredeki APKPure düğmesiyle uygulamanın \"armeabi-v7a\" sürümünü indir → \"Belge / Diğer\" ile gönder → TV'de Yükle.",
            "• O sürüm yoksa uygulama bu TV'de çalışamaz (TV 32 bit). Telefonda açıp \"Ekranı TV'de göster\" ile yansıt.",
            "• Flaştan: APK / XAPK / APKS dosyasını TV'de Dosyalarım'dan aç → Yükle.",
            "Açmak ve kaldırmak:",
            "• Telefonda \"TV'deki uygulamalar\" → ▶ Aç (TV'de hangi uygulama açık olursa olsun açılır) · 🗑 Kaldır (TV'de onay ister).",
            "• TV'de TV Gönder → Uygulamalar → uygulamayı seç → Aç / Kaldır / Bilgi. Telefon uygulamaları TV'nin ana ekranında görünmeyebilir; buradan açılır.",
        ),
        "🎮 Oyun" to listOf(
            "• En iyisi gerçek oyun kolu: PS4/PS5/Xbox kolunu TV'de Ayarlar → Kumandalar ve aksesuarlar → Aksesuar eşle ile bağla (PS kolunda PS + Share'e ışık yanıp sönene kadar bas).",
            "• Telefondaki \"Oyun kolu\" Wi‑Fi ile yön tuşları ve A/B gönderir; menüler ve basit oyunlar için uygundur, çoğu oyun bunu gerçek kol olarak görmez.",
            "• Tabletteki oyunu TV'de oynamak: tablette \"Ekranı TV'de göster\", oyunu tablette oyna (biraz gecikme olur).",
        ),
        "🗂️ TV'de Dosyalarım ve gösterme" to listOf(
            "• Kumanda: oklar gezinir, OK açar, Geri geri gider. Ana ekranda Geri'ye iki kez basınca çıkılır.",
            "• Dosyalarım: filtre (Video / Müzik / Resim / PDF), Ara, ➕ Yeni klasör, ☑ Seç → 📂 Taşı / 📋 Kopyala / 🗑 Sil.",
            "• Resim: ◀ ▶ önceki/sonraki, ▲ ▼ döndür, OK büyüt. PDF: ◀ ▶ sayfa, OK okunaklı büyüt, ▲ ▼ kaydır.",
            "• Video: kaldığı yerden devam eder; ▲ / Menü ile Sığdır ↔ Doldur. Müzik: klasördeki şarkılar sırayla çalar.",
            "• Resim, PDF, slayt ve tarayıcı açıkken TV ekran koruyucuya geçmez.",
            "• Tarayıcıda videoyu büyütmek için sağ alttaki \"🎬 Videoyu tam ekran aç\" (ya da ⏯ / OK'e uzun bas).",
            "• Telefona indirmek: telefonda \"TV'deki dosyalar\" → dosyaya dokun (İndirilenler/TvGonder'e iner).",
        ),
        "🖨️ Yazdırma" to listOf(
            "1. Yazdır sekmesi → Fotoğraf, Dosya / PDF ya da Belge / kimlik tara.",
            "2. Düzen (Tek, 2'li, 4'lü, 6'lı, Kimlik), kağıt (A4/A5, Dikey/Yatay), renk seç; önizlemeye bak.",
            "3. Yazıcıya gönder → Android ekranında yazıcıyı seç. Ya da PDF olarak kaydet.",
        ),
        "🔴 Kızılötesi kumanda (telefonda verici varsa)" to listOf(
            "1. Kızılötesi kumanda → + Cihaz ekle → tür → marka → model.",
            "2. Telefonu cihaza doğrult, ⏻ / Ses + ile dene → \"Çalıştı, kaydet\" ya da \"Sonraki\".",
            "• Marka yoksa \"Tümünü sırayla dene\". Google TV'ler için \"TV kumandası (hızlı)\" daha iyidir.",
        ),
        "📱 Tablet" to listOf(
            "• Alıcı yapmak (TV gibi): tablette ⋮ → Bu cihazı alıcı yap → telefonda ⋮ → Yeni cihaz ekle → tabletin QR'ını okut.",
            "• Telefonda \"Nereye gönderilsin?\"den TV ya da tableti seç.",
            "• Geri dönmek: tablette Ayarlar → Gönderici moduna dön.",
        ),
        "🔄 Güncelleme" to listOf(
            "1. Yeni APK'yı telefona kur (eskisinin üzerine; ayarlar silinmez).",
            "2. Telefonda ⋮ → \"… uygulamasını güncelle\" → TV'de Yükle.",
            "3. TV'de \"Fare eklentisi güncellensin mi?\" çıkarsa Güncelle → Yükle. Sonra Kurulum kontrolü ✓ mi bak.",
        ),
        "🛠️ Sorun giderme" to listOf(
            "• Önce bu kılavuzun en üstündeki Kurulum kontrolü'ne bak: ✗ olan satırın düğmesi çoğu sorunu çözer.",
            "• \"TV'ye ulaşılamıyor\": TV açık ve aynı Wi‑Fi'de mi? ⋮ → Bağlantıyı yenile.",
            "• Fare hareket ediyor ama tıklamıyor: Fare ekranının altındaki yazıya bak. TV Gönder ana ekranında sarı uyarı varsa ona bas.",
            "• Fare hiç yok: TV Gönder → Ayarlar → Fare eklentisini kaldır → Kur → Yükle → Aç.",
            "• TV'de Yükle çalışmıyor: açılan ekranda TV Gönder'e \"bilinmeyen kaynak\" izni ver.",
            "• Uygulama kurulmuyor: çıkan pencere nedenini yazar (çoğunlukla 64 bit / parçalı uygulama). Bölüm \"📦 Uygulama kur ve aç\".",
            "• Kayıt yeri seçilmemiş: TV'ye USB tak, TV Gönder'de USB belleği seç.",
        ),
    )
}

/**
 * Kurulum kontrolü (telefon): her şeyin hazır olup olmadığını tek bakışta gösterir; ✗ olanın düzeltme düğmesi var.
 */
@Composable
fun SetupCheck(store: com.onur.tvgonder.Store) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val client = remember { com.onur.tvgonder.TvClient(store) }
    var info by remember { androidx.compose.runtime.mutableStateOf<org.json.JSONObject?>(null) }
    var reach by remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        reach = null
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { client.tvInfo() }.getOrNull() }
        info = r; reach = r != null
    }
    val myVer = remember { com.onur.tvgonder.appVersionCode(ctx) }
    val paired = store.tvToken != null
    val atv = store.tvHost?.let { com.onur.tvgonder.AtvRemote.isPaired(ctx, it) } == true
    val overlay = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx)

    @Composable
    fun Row2(ok: Boolean?, title: String, fix: (@Composable () -> Unit)? = null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (ok) { true -> "✓"; false -> "✗"; null -> "…" }, color = when (ok) { true -> Accent; false -> Danger; null -> TextMuted },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
                Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium)
            }
            if (ok == false) fix?.invoke()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row2(paired, if (paired) "Telefon ${store.tvName} ile eşleşmiş" else "Telefon henüz bir TV ile eşleşmemiş") {
            Text("TV'de TV Gönder → Telefon ekle → QR'ı telefonda \"QR kodu okut\" ile okut.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (paired) {
            Row2(reach, if (reach == false) "TV'ye ulaşılamıyor" else "TV'ye ulaşılıyor") {
                Text("TV açık ve aynı Wi‑Fi'de mi? TV'de TV Gönder'i bir kez aç.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Button(onClick = { tick++ }) { Text("Tekrar dene") }
            }
            val tvVer = info?.optLong("version", -1L) ?: -1L
            if (reach == true) {
                Row2(tvVer >= myVer, if (tvVer >= myVer) "TV'deki TV Gönder güncel" else "TV'deki TV Gönder eski (telefondaki daha yeni)") {
                    Text("Telefonda ⋮ → \"${store.tvName} uygulamasını güncelle\" → TV'de Yükle.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Row2(info?.optBoolean("storage") == true, "TV'de kayıt yeri seçili") {
                    Text("TV'ye USB tak, TV Gönder'de \"USB belleği seç\".", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                val fare = info?.optBoolean("fare") == true
                Row2(fare, if (fare) "Fare eklentisi TV'de çalışıyor" else "Fare eklentisi TV'de kapalı / kurulu değil") {
                    FareFixButton(client)
                }
            }
        }
        Row2(atv, if (atv) "TV kumandası (hızlı) eşleşmiş" else "TV kumandası (hızlı) eşleşmemiş (yazı, uygulama açma ve YouTube için gerekli)") {
            androidx.compose.material3.Button(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(ctx, com.onur.tvgonder.AtvActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("TV kumandasını eşleştir") }
        }
        Row2(overlay, if (overlay) "Yansıtmada telefon ekranı açık kalır" else "Yansıtmada telefon ekranı kapanabilir (izin yok)") {
            androidx.compose.material3.Button(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("İzni ver") }
        }
        TextButton(onClick = { tick++ }) { Text("↻ Yeniden kontrol et") }
    }
}

/** Telefon: tam ekran kılavuz; başlığa dokununca bölüm açılır. */
@Composable
fun GuideDialog(store: com.onur.tvgonder.Store? = null, onClose: () -> Unit) {
    var open by remember { mutableIntStateOf(if (store != null) -2 else 0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖  Kullanım kılavuzu", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMain)
                TextButton(onClick = onClose) { Text("Kapat") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (store != null) item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
                            .clickable { open = if (open == -2) -1 else -2 }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✅ Kurulum kontrolü", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (open == -2) Accent else TextMain)
                            Text(if (open == -2) "▲" else "▼", color = TextMuted)
                        }
                        if (open == -2) SetupCheck(store)
                    }
                }
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
