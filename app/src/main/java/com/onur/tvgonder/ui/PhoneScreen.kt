// v0.9 – 2026-10-12: resimden / PDF'ten karekod okuma (sayfadaki tüm karekodlar); TV güncelleme, Ayarlar
package com.onur.tvgonder.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.onur.tvgonder.R
import com.onur.tvgonder.Shared
import com.onur.tvgonder.Store
import com.onur.tvgonder.PrintItem
import com.onur.tvgonder.Printing
import com.onur.tvgonder.TvClient
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.onur.tvgonder.appVersionCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// ---------------------------------------------------------------- yardımcılar

private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

private val urlInText = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
private val bareDomain = Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""")

/** Metnin içindeki ilk linki bulur. "youtube.com/..." gibi şemasız yazılana https ekler. */
internal fun extractUrl(text: String): String? {
    val t = text.trim()
    urlInText.find(t)?.let { return it.value.trimEnd('.', ',', '!', '?', ';', ':') }
    if (t.isNotEmpty() && bareDomain.matches(t)) return "https://$t"
    return null
}

private fun prettyUrl(u: String) =
    u.removePrefix("https://").removePrefix("http://").removePrefix("www.")

private fun isYoutube(u: String): Boolean {
    val host = runCatching { Uri.parse(u).host?.lowercase() }.getOrNull() ?: return false
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
}

/** Android 10+ panoyu yalnızca uygulama öndeyken okutur; o yüzden pencere odaklanınca çağrılır. */
private fun readClipboard(ctx: Context): String? = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip
    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(ctx)?.toString()
}.getOrNull()

private fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: (uri.lastPathSegment ?: "dosya")

private fun friendly(e: Throwable?): String = when (e) {
    is IOException -> "TV'ye ulaşılamadı. TV açık ve aynı Wi‑Fi'de mi?"
    null -> "Bilinmeyen hata"
    else -> e.message ?: "Bilinmeyen hata"
}

private class Msg(val text: String, val error: Boolean)

/** Bir resimdeki tüm karekodları okur (test kitabı sayfasında birden fazla olabilir). */
private fun qrsInBitmap(bmp: android.graphics.Bitmap): List<String> {
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val src = RGBLuminanceSource(w, h, px)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    val out = LinkedHashSet<String>()
    for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
        runCatching { QRCodeMultiReader().decodeMultiple(BinaryBitmap(bin), hints).forEach { out += it.text } }
    }
    if (out.isEmpty()) runCatching { out += MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).text }
    return out.toList()
}

/** Seçilen resim veya PDF'teki (ilk 10 sayfa) karekodlar. */
private fun qrsInFile(ctx: Context, uri: Uri): List<String> {
    val items = Printing.load(ctx, listOf(uri))
    val out = LinkedHashSet<String>()
    for (item in items) {
        for (page in 0 until minOf(item.count, 10)) {
            for (size in listOf(2400, 3600)) {
                val bmp = Printing.tileBitmap(ctx, item, page, size)
                val found = qrsInBitmap(bmp)
                bmp.recycle()
                out += found
                if (found.isNotEmpty()) break
            }
        }
    }
    return out.toList()
}

private fun openOnPhone(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TV Gönder", text))
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

private enum class TState { Waiting, Sending, Done, Failed }

private class Transfer(val name: String) {
    var progress by mutableFloatStateOf(0f)
    var state by mutableStateOf(TState.Waiting)
    var error by mutableStateOf<String?>(null)
}

// ---------------------------------------------------------------- ana ekran

@Composable
fun PhoneScreen(store: Store, shared: Shared? = null, onSharedHandled: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val client = remember { TvClient(store) }
    var paired by remember { mutableStateOf(store.tvToken != null) }
    var pairMsg by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }   // "Şimdilik geç" — her açılışta sıfırlanır

    val scanner = rememberLauncherForActivityResult(ScanContract()) { res ->
        val text = res.contents ?: return@rememberLauncherForActivityResult
        val u = Uri.parse(text)
        val host = u.getQueryParameter("host")
        val port = u.getQueryParameter("port")?.toIntOrNull()
        val code = u.getQueryParameter("code")
        if (u.scheme != "tvgonder" || host == null || port == null || code == null) {
            pairMsg = "Bu bir TV eşleşme kodu değil."
            return@rememberLauncherForActivityResult
        }
        pairing = true
        pairMsg = "TV'de Onayla'ya basmanı bekliyorum…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { client.pair(host, port, code, Build.MODEL) } }
            pairing = false
            r.onSuccess { paired = true; pairMsg = null }
                .onFailure { pairMsg = friendly(it) }
        }
    }

    if (!paired) {
        LaunchedEffect(shared) {
            if (shared != null) {
                skipped = false
                pairMsg = "Paylaşmak için önce TV'yi eşleştir."
                onSharedHandled()
            }
        }
    }

    if (!paired && !skipped) {
        PairUi(
            pairing, pairMsg,
            onScan = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false).setOrientationLocked(false)
                        .setPrompt("TV'deki QR kodu okut")
                )
            },
            onSkip = { skipped = true; pairMsg = null },
        )
    } else {
        SendUi(
            store, client, paired,
            if (paired) shared else null, onSharedHandled,
            onPair = { skipped = false; pairMsg = null },
            onUnpair = { store.clearTv(); paired = false; skipped = false; pairMsg = null },
        )
    }
}

// ---------------------------------------------------------------- eşleştirme

@Composable
private fun PairUi(pairing: Boolean, msg: String?, onScan: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Image(
            painterResource(R.drawable.logo_round), "M-ONUR",
            Modifier.size(132.dp).align(Alignment.CenterHorizontally),
        )
        Text("TV Gönder", style = MaterialTheme.typography.headlineMedium, color = TextMain)
        Text(
            "Dosyaları ve linkleri tek dokunuşla TV'ye gönder.",
            style = MaterialTheme.typography.bodyLarge, color = TextMuted,
        )
        Section("Nasıl eşleştirilir?") {
            StepRow(1, "TV'de TV Gönder uygulamasını aç.")
            StepRow(2, "TV'de \"Telefon ekle\"yi seç. Ekranda QR kod çıkar.")
            StepRow(3, "Aşağıdaki düğmeyle QR kodu okut, sonra TV'de \"Onayla\"ya bas.")
        }
        Button(
            enabled = !pairing, onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        ) { Text(if (pairing) "TV'de onay bekleniyor…" else "QR kodu okut") }
        TextButton(
            enabled = !pairing, onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Şimdilik geç, uygulamayı gez") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = if (pairing) Accent else Danger) }
    }
}

@Composable
private fun StepRow(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(PanelHi), contentAlignment = Alignment.Center) {
            Text("$n", color = Accent, fontWeight = FontWeight.SemiBold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextMain)
    }
}

// ---------------------------------------------------------------- gönderme

@Composable
private fun SendUi(
    store: Store,
    client: TvClient,
    paired: Boolean,
    shared: Shared?,
    onSharedHandled: () -> Unit,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focused = LocalWindowInfo.current.isWindowFocused

    var online by remember { mutableStateOf<Boolean?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf("") }
    var fromClip by remember { mutableStateOf(false) }
    var linkBusy by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(store.recentLinks()) }
    var folder by remember { mutableStateOf(store.lastFolder) }
    var showFolders by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Msg?>(null) }
    val transfers = remember { mutableStateListOf<Transfer>() }
    var scanned by remember { mutableStateOf<String?>(null) }
    var scannedMany by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var tvVer by remember { mutableStateOf<Long?>(null) }
    var updating by remember { mutableStateOf<Float?>(null) }
    val myVer = remember { appVersionCode(ctx) }

    val notPaired = Msg("Göndermek için önce TV'yi eşleştir.", true)

    fun sendLink(raw: String) {
        if (!paired) { msg = notPaired; return }
        val url = extractUrl(raw)
        if (url == null) { msg = Msg("Bu bir link değil.", true); return }
        linkBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { client.link(url) } }
            linkBusy = false
            if (r.isSuccess) {
                online = true
                store.addRecentLink(url); store.lastClip = url
                recent = store.recentLinks()
                link = ""; fromClip = false
                msg = Msg("Link TV'de açıldı.", false)
            } else {
                msg = Msg(friendly(r.exceptionOrNull()), true)
            }
        }
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!paired) { msg = notPaired; return }
        val target = folder
        store.lastFolder = target
        val items = uris.map { Transfer(displayName(ctx, it)) to it }
        transfers.addAll(0, items.map { it.first })
        scope.launch {
            for ((t, u) in items) {
                t.state = TState.Sending
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        client.upload(ctx, u, target) { sent, total ->
                            if (total > 0) {
                                val p = sent.toFloat() / total
                                if (p - t.progress >= 0.01f || p >= 1f) t.progress = p
                            }
                        }
                    }
                }
                if (r.isSuccess) {
                    t.progress = 1f; t.state = TState.Done; online = true
                } else {
                    t.state = TState.Failed; t.error = friendly(r.exceptionOrNull())
                }
            }
        }
    }

    // Uygulama öne gelince: panoda yeni link varsa kutuya koy, TV bağlantısını yokla.
    LaunchedEffect(focused, recheck) {
        if (!focused) return@LaunchedEffect
        val clip = readClipboard(ctx)?.let { extractUrl(it) }
        if (store.autoClip && clip != null && clip != store.lastClip && link.isBlank()) {
            link = clip; fromClip = true
        }
        if (paired) {
            online = withContext(Dispatchers.IO) { runCatching { client.folders("") }.isSuccess }
            if (online == true) tvVer = withContext(Dispatchers.IO) { runCatching { client.tvVersion() }.getOrNull() }
        }
    }

    // Paylaş menüsünden gelen içerik
    LaunchedEffect(shared) {
        val s = shared ?: return@LaunchedEffect
        if (s.files.isNotEmpty()) {
            sendFiles(s.files)
        } else {
            val u = s.text?.let { extractUrl(it) }
            if (u != null) { link = u; sendLink(u) } else msg = Msg("Paylaşılan metinde link bulunamadı.", true)
        }
        onSharedHandled()
    }

    LaunchedEffect(msg) { if (msg != null) { delay(5000); msg = null } }

    fun updateTv() {
        if (!paired) { msg = notPaired; return }
        if (updating != null) return
        updating = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    client.sendUpdate(ctx) { sent, total ->
                        if (total > 0) {
                            val p = sent.toFloat() / total
                            if (p - (updating ?: 0f) >= 0.01f || p >= 1f) updating = p
                        }
                    }
                }
            }
            updating = null
            msg = if (r.isSuccess) Msg("Yeni sürüm TV'ye gönderildi. TV'de \"Yükle\"ye bas.", false)
            else Msg(friendly(r.exceptionOrNull()), true)
        }
    }

    val qrFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = Msg("Karekod aranıyor…", false)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { qrsInFile(ctx, uri) } }
            val list = r.getOrDefault(emptyList())
            when {
                r.isFailure -> msg = Msg(r.exceptionOrNull()?.message ?: "Dosya açılamadı.", true)
                list.isEmpty() -> msg = Msg("Bu dosyada karekod bulunamadı. Daha net bir fotoğraf dene.", true)
                list.size == 1 -> { msg = null; scanned = list[0] }
                else -> { msg = null; scannedMany = list }
            }
        }
    }

    val qr = rememberLauncherForActivityResult(ScanContract()) { res ->
        val text = res.contents?.trim()
        if (!text.isNullOrEmpty()) scanned = text
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { sendFiles(it) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendFiles(it) }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Başlık
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_round), "M-ONUR", Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (paired) store.tvName else "TV Gönder",
                    style = MaterialTheme.typography.headlineMedium, color = TextMain,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (paired) StatusPill(online)
                else Text("TV eşleşmemiş", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü", tint = TextMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (paired) {
                        DropdownMenuItem(text = { Text("Bağlantıyı yenile") }, onClick = { menu = false; online = null; recheck++ })
                        DropdownMenuItem(text = { Text("TV'deki uygulamayı güncelle") }, onClick = { menu = false; updateTv() })
                        DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { menu = false; showSettings = true })
                        DropdownMenuItem(text = { Text("Eşleşmeyi kaldır", color = Danger) }, onClick = { menu = false; onUnpair() })
                    } else {
                        DropdownMenuItem(text = { Text("TV'yi eşleştir") }, onClick = { menu = false; onPair() })
                        DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { menu = false; showSettings = true })
                    }
                }
            }
        }
        if (!paired) {
            Surface(shape = RoundedCornerShape(20.dp), color = PanelHi, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Göndermek için TV'yi eşleştir.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge, color = TextMain,
                    )
                    Button(onClick = onPair, shape = RoundedCornerShape(14.dp)) { Text("Eşleştir") }
                }
            }
        }
        val u = updating
        val tv = tvVer
        if (paired && (u != null || (tv != null && tv < myVer))) {
            Surface(shape = RoundedCornerShape(20.dp), color = PanelHi, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (u == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "TV'deki uygulama eski. Telefondaki sürümü TV'ye gönder.", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge, color = TextMain,
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { updateTv() }, shape = RoundedCornerShape(14.dp)) { Text("Güncelle") }
                        }
                    } else {
                        Text("TV'ye gönderiliyor… %${(u * 100).toInt()}", style = MaterialTheme.typography.bodyLarge, color = Accent)
                        LinearProgressIndicator(
                            progress = { u },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Accent, trackColor = Panel,
                        )
                    }
                }
            }
        }
        if (paired && online == false) {
            Text(
                "TV açık mı ve telefonla aynı Wi‑Fi'de mi? TV'de uygulama en az bir kez açılmış olmalı.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            )
        }

        // Link
        Section("Link gönder") {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; fromClip = false },
                placeholder = { Text("Linki buraya yapıştır") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (link.isNotBlank()) sendLink(link) }),
                trailingIcon = {
                    if (link.isNotEmpty()) {
                        IconButton(onClick = {
                            if (fromClip) store.lastClip = link
                            link = ""; fromClip = false
                        }) { Icon(Icons.Filled.Close, "Temizle", tint = TextMuted) }
                    }
                },
            )
            if (fromClip) Text("Kopyaladığın link eklendi.", style = MaterialTheme.typography.bodyMedium, color = Accent)
            if (link.isNotBlank() && isYoutube(link)) {
                Text("YouTube uygulamasında açılacak.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val u = readClipboard(ctx)?.let { extractUrl(it) }
                        if (u != null) { link = u; fromClip = false } else msg = Msg("Panoda link yok.", true)
                    },
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                ) { Text("Yapıştır") }
                Button(
                    enabled = !linkBusy && link.isNotBlank(),
                    onClick = { sendLink(link) },
                    modifier = Modifier.weight(1.5f).height(52.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (linkBusy) "Gönderiliyor…" else "TV'de aç")
                }
            }
            OutlinedButton(
                onClick = {
                    qr.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setBeepEnabled(false).setOrientationLocked(false)
                            .setPrompt("Karekodu çerçeveye getir")
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            ) { Text("📷  Karekod okut (kamera)") }
            OutlinedButton(
                onClick = { qrFile.launch(arrayOf("image/*", "application/pdf")) },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            ) { Text("🖼️  Resimden / PDF'ten karekod oku") }
        }

        // Dosya
        Section("Dosya gönder") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("🖼️", "Fotoğraf / Video", Modifier.weight(1f)) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                Tile("📄", "Belge / Diğer", Modifier.weight(1f)) { pickDocs.launch(arrayOf("*/*")) }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelHi)
                    .clickable { if (paired) showFolders = true else msg = notPaired }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Kaydedilecek yer", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Text(
                        if (folder.isEmpty()) "USB bellek (ana klasör)" else "📁 $folder",
                        style = MaterialTheme.typography.titleMedium, color = TextMain,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("Değiştir", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
        }

        // Gönderimler
        if (transfers.isNotEmpty()) {
            Section("Gönderimler") {
                transfers.forEach { TransferRow(it) }
                if (transfers.none { it.state == TState.Waiting || it.state == TState.Sending }) {
                    TextButton(onClick = { transfers.clear() }) { Text("Listeyi temizle") }
                }
            }
        }

        // Son linkler
        if (recent.isNotEmpty()) {
            Section("Son linkler") {
                recent.forEach { u ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !linkBusy) { sendLink(u) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            prettyUrl(u), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Tekrar aç", style = MaterialTheme.typography.labelLarge, color = Accent)
                    }
                }
            }
        }

        msg?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = PanelHi, modifier = Modifier.fillMaxWidth()) {
                Text(
                    it.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                    color = if (it.error) Danger else Accent,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (scannedMany.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { scannedMany = emptyList() },
            containerColor = PanelHi,
            title = { Text("${scannedMany.size} karekod bulundu", color = TextMain) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scannedMany.forEachIndexed { i, t ->
                        OutlinedButton(
                            onClick = { scanned = t },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                "${i + 1}.  " + (extractUrl(t)?.let { prettyUrl(it) } ?: t),
                                Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scannedMany = emptyList() }) { Text("Kapat") } },
        )
    }

    scanned?.let { text ->
        ScanResultDialog(
            text = text,
            paired = paired,
            onTv = { url -> scanned = null; sendLink(url) },
            onPhone = { url -> scanned = null; openOnPhone(ctx, url) },
            onCopy = { scanned = null; copyText(ctx, text); msg = Msg("Kopyalandı.", false) },
            onDismiss = { scanned = null },
        )
    }

    if (showSettings) {
        SettingsDialog(store, paired, onDismiss = { showSettings = false })
    }

    if (showFolders) {
        FolderDialog(
            client, folder,
            onPick = { folder = it; store.lastFolder = it; showFolders = false },
            onDismiss = { showFolders = false },
        )
    }
}

// ---------------------------------------------------------------- parçalar

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain)
            content()
        }
    }
}

@Composable
private fun StatusPill(online: Boolean?) {
    val (text, color) = when (online) {
        true -> "Bağlı" to Accent
        false -> "TV'ye ulaşılamıyor" to Danger
        null -> "Kontrol ediliyor…" to TextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Tile(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(104.dp).clip(RoundedCornerShape(18.dp)).background(PanelHi)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, fontSize = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = TextMain, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val (label, color) = when (t.state) {
        TState.Waiting -> "Sırada" to TextMuted
        TState.Sending -> "%${(t.progress * 100).toInt()}" to Accent
        TState.Done -> "Gönderildi ✓" to Accent
        TState.Failed -> "Gönderilemedi" to Danger
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = TextMain,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        LinearProgressIndicator(
            progress = { t.progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (t.state == TState.Failed) Danger else Accent,
            trackColor = PanelHi,
        )
        t.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Danger) }
    }
}

@Composable
private fun FolderDialog(client: TvClient, start: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(start) }
    var list by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(path) {
        list = null; err = null
        val r = withContext(Dispatchers.IO) { runCatching { client.folders(path) } }
        if (r.isSuccess) list = r.getOrNull()
        else if (path.isNotEmpty()) path = ""          // klasör silinmişse köke dön
        else { list = emptyList(); err = friendly(r.exceptionOrNull()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelHi,
        title = {
            Text(
                if (path.isEmpty()) "USB bellek" else path.substringAfterLast('/'),
                color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (path.isNotEmpty()) FolderRow("‹  Üst klasör", TextMuted) { path = path.substringBeforeLast('/', "") }
                val l = list
                val e = err
                when {
                    e != null -> Text(e, color = Danger)
                    l == null -> Text("Yükleniyor…", color = TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                    l.isEmpty() -> Text("Burada alt klasör yok.", color = TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                    else -> l.forEach { n -> FolderRow("📁  $n", TextMain) { path = join(path, n) } }
                }
                Spacer(Modifier.height(8.dp))
                if (adding) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text("Yeni klasör adı") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { adding = false; newName = "" }) { Text("Vazgeç") }
                        TextButton(enabled = newName.isNotBlank(), onClick = {
                            val n = newName.trim()
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { client.mkdir(path, n) } }
                                if (r.isSuccess) { adding = false; newName = ""; path = join(path, n) }
                                else err = friendly(r.exceptionOrNull())
                            }
                        }) { Text("Oluştur") }
                    }
                } else {
                    TextButton(onClick = { adding = true }) { Text("+ Yeni klasör") }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(path) }) { Text("Buraya kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun FolderRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.bodyLarge, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun ScanResultDialog(
    text: String,
    paired: Boolean,
    onTv: (String) -> Unit,
    onPhone: (String) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val url = extractUrl(text)
    val pairCode = text.startsWith("tvgonder://")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelHi,
        title = { Text(if (url != null) "Karekodda link var" else "Karekod okundu", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (url != null) prettyUrl(url) else text,
                    style = MaterialTheme.typography.bodyLarge, color = TextMain, maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pairCode) {
                    Text(
                        "Bu bir TV eşleşme kodu. Eşleştirmek için menüden \"TV'yi eşleştir\"i kullan.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    )
                }
                if (url != null) {
                    Button(
                        onClick = { onPhone(url) },
                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text("Telefonda aç") }
                    OutlinedButton(
                        enabled = paired,
                        onClick = { onTv(url) },
                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text(if (paired) "TV'de aç" else "TV'de aç (önce eşleştir)") }
                }
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                ) { Text("Kopyala") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun SettingsDialog(store: Store, paired: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var autoClip by remember { mutableStateOf(store.autoClip) }
    var a5 by remember { mutableStateOf(store.defA5) }
    var color by remember { mutableStateOf(store.defColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelHi,
        title = { Text("Ayarlar", color = TextMain) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Link", style = MaterialTheme.typography.labelLarge, color = Accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Kopyaladığım linki otomatik ekle", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = TextMain,
                    )
                    Switch(checked = autoClip, onCheckedChange = { autoClip = it; store.autoClip = it })
                }

                HorizontalDivider(color = Panel)
                Text("Yazdırma", style = MaterialTheme.typography.labelLarge, color = Accent)
                Text("Varsayılan kağıt", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !a5, onClick = { a5 = false; store.defA5 = false }, label = { Text("A4") })
                    FilterChip(selected = a5, onClick = { a5 = true; store.defA5 = true }, label = { Text("A5") })
                }
                Text("Varsayılan renk", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = color, onClick = { color = true; store.defColor = true }, label = { Text("Renkli") })
                    FilterChip(selected = !color, onClick = { color = false; store.defColor = false }, label = { Text("Siyah-beyaz") })
                }
                Text(
                    "Yazıcıyı yazdırma ekranında seçersin; telefon son kullandığını hatırlar.",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                ) { Text("Telefonun yazıcı ayarlarını aç") }

                HorizontalDivider(color = Panel)
                Text("TV", style = MaterialTheme.typography.labelLarge, color = Accent)
                Text(
                    if (paired) "${store.tvName}  ·  ${store.tvHost ?: ""}" else "Eşleşmiş TV yok",
                    style = MaterialTheme.typography.bodyMedium, color = TextMain,
                )
                Text(
                    "Sürüm ${appVersion(ctx)}  ·  M-ONUR",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Tamam") } },
    )
}
