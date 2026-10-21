// v0.32 – 2026-10-21: TV kumandası kartı her zaman görünür (TV Gönder eşleşmesi gerekmez); YouTube linki kumanda hizmetiyle; yansıtma, kızılötesi
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.documentfile.provider.DocumentFile
import com.onur.tvgonder.Target
import com.onur.tvgonder.Sent
import com.onur.tvgonder.PrintItem
import com.onur.tvgonder.Printing
import com.onur.tvgonder.TvClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.onur.tvgonder.appVersionCode
import com.onur.tvgonder.exitApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// ---------------------------------------------------------------- yardımcılar

private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

private val urlInText = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
private val bareDomain = Regex("""^[\w-]+(\.[\w-]+)*\.[a-zA-Z]{2,}(/\S*)?$""")

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
    is SecurityException -> "Bu dosyaya artık erişilemiyor; dosyayı yeniden seç."
    null -> "Bilinmeyen hata"
    else -> e.message ?: "Bilinmeyen hata"
}

private class Msg(val text: String, val error: Boolean)

/** Bir resimdeki karekodlar (yalnız QR; çizgili barkodlar okunmaz). */
private fun qrResults(bmp: android.graphics.Bitmap): List<com.google.zxing.Result> {
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val src = RGBLuminanceSource(w, h, px)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    val out = LinkedHashMap<String, com.google.zxing.Result>()
    for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
        runCatching { QRCodeMultiReader().decodeMultiple(BinaryBitmap(bin), hints).forEach { out.putIfAbsent(it.text, it) } }
    }
    if (out.isEmpty()) runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).let { out[it.text] = it } }
    return out.values.toList()
}

/** Önizleme: sayfa küçültülür, her karekodun etrafına çerçeve ve sıra numarası çizilir. */
private fun markQrs(bmp: android.graphics.Bitmap, hits: List<Pair<Int, com.google.zxing.Result>>): android.graphics.Bitmap {
    val s = minOf(1f, 1200f / bmp.width)
    val out = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val c = android.graphics.Canvas(out)
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 7f; color = 0xFFE53935.toInt()
    }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = 40f; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER
    }
    for ((n, r) in hits) {
        val pts = r.resultPoints?.filterNotNull().orEmpty()
        if (pts.isEmpty()) continue
        var l = pts.minOf { it.x } * s; var t = pts.minOf { it.y } * s
        var rr = pts.maxOf { it.x } * s; var b = pts.maxOf { it.y } * s
        val pad = maxOf(rr - l, b - t) * 0.25f + 12f    // bulucu noktalar köşe merkezleri; çerçeveyi büyüt
        l -= pad; t -= pad; rr += pad; b += pad
        c.drawRect(l, t, rr, b, stroke)
        c.drawCircle(l, t, 34f, dot)
        c.drawText("$n", l, t + 14f, txt)
    }
    return out
}

private class QrFound(val texts: List<String>, val preview: android.graphics.Bitmap?)

/** Seçilen resim veya PDF'teki (ilk 10 sayfa) karekodlar + ilk bulunan sayfanın önizlemesi. */
private fun qrsInFile(ctx: Context, uri: Uri): QrFound {
    val items = Printing.load(ctx, listOf(uri))
    val texts = mutableListOf<String>()
    var preview: android.graphics.Bitmap? = null
    for (item in items) {
        for (page in 0 until minOf(item.count, 10)) {
            for (size in listOf(2400, 3600)) {
                val bmp = Printing.tileBitmap(ctx, item, page, size)
                val found = qrResults(bmp)
                if (found.isNotEmpty()) {
                    val numbered = found.map { r ->
                        if (r.text !in texts) texts += r.text
                        (texts.indexOf(r.text) + 1) to r
                    }
                    if (preview == null) preview = runCatching { markQrs(bmp, numbered) }.getOrNull()
                }
                bmp.recycle()
                if (found.isNotEmpty()) break
            }
        }
    }
    return QrFound(texts, preview)
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
    var adding by remember { mutableStateOf(false) }    // eşliyken yeni cihaz ekleme
    var current by remember { mutableStateOf(store.tvToken) }

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
            r.onSuccess { paired = true; adding = false; current = store.tvToken; pairMsg = null }
                .onFailure { pairMsg = friendly(it) }
        }
    }

    if (!paired) {
        LaunchedEffect(shared) {
            if (shared != null) {
                if (shared.qr) skipped = true   // karekod okumak için TV gerekmez
                else {
                    skipped = false
                    pairMsg = "Paylaşmak için önce TV'yi eşleştir."
                    onSharedHandled()
                }
            }
        }
    }

    if ((!paired && !skipped) || adding) {
        androidx.activity.compose.BackHandler(enabled = adding) { adding = false; pairMsg = null }
        PairUi(
            pairing, pairMsg,
            onScan = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false).setOrientationLocked(false)
                        .setPrompt("TV'deki QR kodu okut")
                )
            },
            onSkip = { if (adding) adding = false else skipped = true; pairMsg = null },
            skipText = if (adding) "Vazgeç" else "Şimdilik geç, uygulamayı gez",
        )
    } else {
        androidx.compose.runtime.key(current) {
            SendUi(
                store, client, paired,
                if (paired || shared?.qr == true) shared else null, onSharedHandled,
                onPair = { if (paired) adding = true else skipped = false; pairMsg = null },
                onUnpair = {
                    store.removeCurrentTarget()
                    current = store.tvToken
                    paired = store.tvToken != null
                    if (!paired) skipped = false
                    pairMsg = null
                },
                onSwitch = { t -> store.selectTarget(t); current = t.token },
            )
        }
    }
}

// ---------------------------------------------------------------- eşleştirme

@Composable
private fun PairUi(pairing: Boolean, msg: String?, onScan: () -> Unit, onSkip: () -> Unit, skipText: String) {
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
        Text("v${appVersion(LocalContext.current)}  ·  M-ONUR", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Text(
            "Dosyaları ve linkleri tek dokunuşla TV'ye gönder.",
            style = MaterialTheme.typography.bodyLarge, color = TextMuted,
        )
        Section("Nasıl eşleştirilir?") {
            StepRow(1, "TV'de (veya alıcı yaptığın tablette) TV Gönder'i aç.")
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
        ) { Text(skipText) }
        val c = LocalContext.current
        TextButton(onClick = { exitApp(c) }, modifier = Modifier.fillMaxWidth()) { Text("Uygulamadan çık", color = TextMuted) }
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
    onSwitch: (Target) -> Unit,
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
    var scanPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showReceiver by remember { mutableStateOf(false) }
    var tvVer by remember { mutableStateOf<Long?>(null) }
    var tvStorage by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var showTvFiles by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }
    var showTvScreen by remember { mutableStateOf(false) }
    val mirroring by com.onur.tvgonder.Hub.mirrorOut.collectAsState()
    val mirrorStart = com.onur.tvgonder.mirror.rememberMirrorStarter(store)
    var showGuide by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<Float?>(null) }
    val myVer = remember { appVersionCode(ctx) }

    val notPaired = Msg("Göndermek için önce TV'yi eşleştir.", true)

    fun sendLink(raw: String) {
        if (!paired) { msg = notPaired; return }
        val url = extractUrl(raw)
        if (url == null) { msg = Msg("Bu bir link değil.", true); return }
        linkBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // YouTube: TV'nin kendi kumanda hizmetiyle aç (YouTube açıkken bile yeni video açılır)
                    val h = Uri.parse(url).host?.lowercase() ?: ""
                    val yt = h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
                    val tvHost = store.tvHost
                    if (!(yt && tvHost != null && com.onur.tvgonder.AtvRemote.launchBlocking(ctx, tvHost, url))) client.link(url)
                }
            }
            linkBusy = false
            if (r.isSuccess) {
                online = true
                store.addRecentLink(url); store.lastClip = url
                store.addHistory(Sent("link", url, url, store.tvName, System.currentTimeMillis()))
                recent = store.recentLinks()
                link = ""; fromClip = false
                msg = Msg("Link TV'de açıldı.", false)
            } else {
                msg = Msg(friendly(r.exceptionOrNull()), true)
            }
        }
    }

    /** Sırayla gönderir. items: (satır, (TV'deki klasör, dosya)). */
    fun uploadAll(items: List<Pair<Transfer, Pair<String, Uri>>>, withDate: Boolean) {
        transfers.addAll(0, items.map { it.first })
        scope.launch {
            for ((t, dest) in items) {
                val (dir, u) = dest
                t.state = TState.Sending
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        client.upload(ctx, u, dir, withDate) { sent, total ->
                            if (total > 0) {
                                val p = sent.toFloat() / total
                                if (p - t.progress >= 0.01f || p >= 1f) t.progress = p
                            }
                        }
                    }
                }
                if (r.isSuccess) {
                    t.progress = 1f; t.state = TState.Done; online = true
                    store.addHistory(Sent("file", t.name, u.toString(), store.tvName, System.currentTimeMillis()))
                } else {
                    t.state = TState.Failed; t.error = friendly(r.exceptionOrNull())
                }
            }
            val done = items.count { it.first.state == TState.Done }
            if (items.size > 1) msg = Msg("$done / ${items.size} dosya gönderildi.", done < items.size)
        }
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!paired) { msg = notPaired; return }
        val target = folder
        store.lastFolder = target
        uploadAll(uris.map { Transfer(displayName(ctx, it)) to (target to it) }, withDate = true)
    }

    /** Telefondaki bir klasörü alt klasörleriyle birlikte TV'ye gönderir (adlar korunur). */
    fun sendFolder(tree: Uri) {
        if (!paired) { msg = notPaired; return }
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return
        val target = folder
        msg = Msg("Klasör hazırlanıyor…", false)
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<String, DocumentFile>>()
                fun walk(d: DocumentFile, rel: String) {
                    d.listFiles().forEach { f ->
                        if (f.isDirectory) walk(f, join(rel, f.name ?: "klasor")) else if (f.isFile) out += rel to f
                    }
                }
                walk(root, root.name ?: "Klasör")
                out
            }
            if (files.isEmpty()) { msg = Msg("Klasör boş.", true); return@launch }
            val dirs = files.map { it.first }.distinct()
                .flatMap { rel -> rel.split('/').runningReduce { a, b -> "$a/$b" } }
                .distinct().sortedBy { d -> d.count { it == '/' } }
            val mk = withContext(Dispatchers.IO) {
                runCatching { dirs.forEach { d -> client.mkdir(join(target, d.substringBeforeLast('/', "")), d.substringAfterLast('/')) } }
            }
            if (mk.isFailure) { msg = Msg(friendly(mk.exceptionOrNull()), true); return@launch }
            msg = Msg("${files.size} dosya gönderiliyor…", false)
            uploadAll(files.map { (rel, f) -> Transfer("$rel/${f.name}") to (join(target, rel) to f.uri) }, withDate = false)
        }
    }

    /** Resim/PDF'teki karekodları bulur; tek ise sonucu, çok ise listeyi açar. */
    fun findQr(uri: Uri) {
        msg = Msg("Karekod aranıyor…", false)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { qrsInFile(ctx, uri) } }
            val found = r.getOrNull()
            when {
                r.isFailure -> msg = Msg(r.exceptionOrNull()?.message ?: "Dosya açılamadı.", true)
                found == null || found.texts.isEmpty() -> msg = Msg("Bu dosyada karekod bulunamadı. Karekodu daha yakından, net çek.", true)
                else -> { msg = null; scanPreview = found.preview; scannedMany = found.texts }
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
            var info = withContext(Dispatchers.IO) { runCatching { client.tvInfo() }.getOrNull() }
            if (info == null) {
                // Modem IP'yi değiştirmiş olabilir: ağda ara
                online = null; searching = true
                val ok = withContext(Dispatchers.IO) { runCatching { client.rediscover() }.getOrDefault(false) }
                searching = false
                if (ok) {
                    info = withContext(Dispatchers.IO) { runCatching { client.tvInfo() }.getOrNull() }
                    if (info != null) msg = Msg("${store.tvName} yeni adreste bulundu (${store.tvHost}).", false)
                }
            }
            // Eski eşleşmelerde kalıcı kimliği öğren
            info?.optString("id")?.takeIf { it.isNotBlank() && store.tvId.isBlank() }?.let { id ->
                store.tvId = id
                store.tvToken?.let { tok -> store.addTarget(Target(store.tvName, store.tvHost ?: "", store.tvPort, tok, id)) }
            }
            online = info != null
            tvVer = info?.optLong("version", 0L)
            tvStorage = info?.optBoolean("storage", true) ?: true
        }
    }

    // Paylaş menüsünden gelen içerik
    LaunchedEffect(shared) {
        val s = shared ?: return@LaunchedEffect
        if (s.qr) {
            s.files.firstOrNull()?.let { findQr(it) }
        } else if (s.files.isNotEmpty()) {
            sendFiles(s.files)
        } else {
            val u = s.text?.let { extractUrl(it) }
            if (u != null) { link = u; sendLink(u) } else msg = Msg("Paylaşılan metinde link bulunamadı.", true)
        }
        onSharedHandled()
    }

    LaunchedEffect(msg) { if (msg != null) { delay(5000); msg = null } }

    fun updateTv(fare: Boolean = false) {
        if (!paired) { msg = notPaired; return }
        if (updating != null) return
        updating = 0f
        if (fare) msg = Msg("Fare eklentisi indiriliyor…", false)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val file = if (fare) client.fetchFareApk(ctx) else null
                    client.sendUpdate(ctx, file) { sent, total ->
                        if (total > 0) {
                            val p = sent.toFloat() / total
                            if (p - (updating ?: 0f) >= 0.01f || p >= 1f) updating = p
                        }
                    }
                }
            }
            updating = null
            msg = if (r.isSuccess) Msg(
                if (fare) "Fare eklentisi gönderildi. TV'de \"Yükle\"ye bas, sonra TV Gönder → Ayarlar → Telefonla fare kontrolü → Aç."
                else "Yeni sürüm TV'ye gönderildi. TV'de \"Yükle\"ye bas.", false,
            )
            else Msg(friendly(r.exceptionOrNull()), true)
        }
    }

    val qrFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) findQr(uri)
    }

    val qr = rememberLauncherForActivityResult(ScanContract()) { res ->
        val text = res.contents?.trim()
        if (!text.isNullOrEmpty()) scanned = text
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { sendFiles(it) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendFiles(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) sendFolder(it) }

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
                if (paired && searching) Text("TV ağda aranıyor…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                else if (paired) StatusPill(online)
                else Text("TV eşleşmemiş", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü", tint = TextMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (paired) {
                        DropdownMenuItem(text = { Text("Bağlantıyı yenile") }, onClick = { menu = false; online = null; recheck++ })
                        store.targets().filter { it.token != store.tvToken }.forEach { t ->
                            DropdownMenuItem(text = { Text("📺  ${t.name} cihazına geç") }, onClick = { menu = false; onSwitch(t) })
                        }
                        DropdownMenuItem(text = { Text("Yeni cihaz ekle (TV / tablet)") }, onClick = { menu = false; onPair() })
                        DropdownMenuItem(text = { Text("${store.tvName} uygulamasını güncelle") }, onClick = { menu = false; updateTv() })
                        if (paired) DropdownMenuItem(text = { Text("Fare eklentisini ${store.tvName}'ye gönder") }, onClick = { menu = false; updateTv(fare = true) })
                        DropdownMenuItem(text = { Text("Gönderim geçmişi") }, onClick = { menu = false; showHistory = true })
                        DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { menu = false; showSettings = true })
                        DropdownMenuItem(text = { Text("📖 Kullanım kılavuzu") }, onClick = { menu = false; showGuide = true })
                        DropdownMenuItem(text = { Text("Eşleşmeyi kaldır", color = Danger) }, onClick = { menu = false; onUnpair() })
                        if (!store.receiverMode) DropdownMenuItem(text = { Text("Bu cihazı alıcı yap (tablet)") }, onClick = { menu = false; showReceiver = true })
                        DropdownMenuItem(text = { Text("Uygulamadan çık") }, onClick = { menu = false; exitApp(ctx) })
                    } else {
                        DropdownMenuItem(text = { Text("TV'yi eşleştir") }, onClick = { menu = false; onPair() })
                        if (paired) DropdownMenuItem(text = { Text("Fare eklentisini ${store.tvName}'ye gönder") }, onClick = { menu = false; updateTv(fare = true) })
                        DropdownMenuItem(text = { Text("Gönderim geçmişi") }, onClick = { menu = false; showHistory = true })
                        DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { menu = false; showSettings = true })
                        DropdownMenuItem(text = { Text("📖 Kullanım kılavuzu") }, onClick = { menu = false; showGuide = true })
                        if (!store.receiverMode) DropdownMenuItem(text = { Text("Bu cihazı alıcı yap (tablet)") }, onClick = { menu = false; showReceiver = true })
                        DropdownMenuItem(text = { Text("Uygulamadan çık") }, onClick = { menu = false; exitApp(ctx) })
                    }
                }
            }
        }
        val targets = remember(paired) { store.targets() }
        if (paired && targets.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nereye gönderilsin?", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    targets.forEach { t ->
                        FilterChip(
                            selected = t.token == store.tvToken,
                            onClick = { if (t.token != store.tvToken) onSwitch(t) },
                            label = { Text("📺  ${t.name}") },
                        )
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
                                "${store.tvName} cihazındaki uygulama eski. Telefondaki sürümü gönder.", Modifier.weight(1f),
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
        if (paired && online == true && !tvStorage) {
            Surface(shape = RoundedCornerShape(20.dp), color = PanelHi, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "TV bağlı ama dosyaların kaydedileceği yer seçilmemiş. TV'de \"USB belleği seç\"e bas, sonra burada ⋮ → Bağlantıyı yenile.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = Danger,
                )
            }
        }
        if (paired && online == false) {
            Text(
                "TV açık mı ve telefonla aynı Wi‑Fi'de mi? TV'de uygulama en az bir kez açılmış olmalı.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            )
        }

        if (paired) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("🖱️", "Fare / Kumanda", Modifier.weight(1f)) { showRemote = true }
                Tile("📥", "TV'deki dosyalar", Modifier.weight(1f)) { showTvFiles = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(if (mirroring) "⏹️" else "📲", if (mirroring) "Yansıtmayı durdur" else "Ekranı TV'de göster", Modifier.weight(1f)) {
                    if (mirroring) com.onur.tvgonder.mirror.MirrorService.stop(ctx) else mirrorStart()
                }
                Tile("📺", "TV ekranını gör", Modifier.weight(1f)) { com.onur.tvgonder.FullActivity.open(ctx, "tv") }
            }
            // TV Gönder kurulu olmayan TV'ler (Chromecast/Miracast): telefonun kendi yansıtma ekranı
            Tile("📡", "Başka TV'ye yansıt (Chromecast / Miracast)", Modifier.fillMaxWidth()) {
                val ok = listOf("android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS")
                    .any { a -> runCatching { ctx.startActivity(android.content.Intent(a).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
                if (!ok) msg = Msg("Bu telefonda yansıtma ekranı bulunamadı. Bildirim panelinde \"Yayınla / Ekran yansıtma\" düğmesini kullan.", true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("🧩", "TV'deki uygulamalar", Modifier.weight(1f)) { showApps = true }
                Tile("🎮", "Oyun kolu (Bluetooth)", Modifier.weight(1f)) { com.onur.tvgonder.FullActivity.open(ctx, "pad") }
            }
        }
        // TV kumandası: Google TV'nin kendi hizmeti; TV Gönder eşleşmesi gerekmez
        Tile("📺", "TV kumandası (hızlı, Google TV)", Modifier.fillMaxWidth()) {
            ctx.startActivity(android.content.Intent(ctx, com.onur.tvgonder.AtvActivity::class.java))
        }
        // Kızılötesi: TV Gönder'den bağımsız (Panasonic TV, uydu alıcısı vb.), yalnız vericisi olan telefonlarda
        if (remember { Ir.has(ctx) }) {
            Tile("📡", "Kızılötesi kumanda (TV, uydu alıcısı…)", Modifier.fillMaxWidth()) {
                ctx.startActivity(android.content.Intent(ctx, com.onur.tvgonder.IrActivity::class.java))
            }
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
                Tile("🎵", "Müzik (MP3)", Modifier.weight(1f)) { pickDocs.launch(arrayOf("audio/*")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("📄", "Belge / Diğer", Modifier.weight(1f)) { pickDocs.launch(arrayOf("*/*")) }
                Tile("📁", "Klasör (toplu)", Modifier.weight(1f)) { runCatching { pickFolder.launch(null) } }
            }
            Text(
                "Birden fazla dosya seçebilirsin; Klasör ile bir klasörün tamamı alt klasörleriyle gider." +
                    if (store.datePrefix) " Dosya adının başına gönderim tarihi eklenir." else "",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            )
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
                Text(
                    "Tümünü temizle", Modifier.clip(RoundedCornerShape(8.dp)).clickable { store.clearRecentLinks(); recent = emptyList() }.padding(4.dp),
                    style = MaterialTheme.typography.labelLarge, color = Danger,
                )
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
                        Text(
                            "✕", Modifier.clip(RoundedCornerShape(8.dp)).clickable { store.removeRecentLink(u); recent = store.recentLinks() }
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        Text(
            "TV Gönder v${appVersion(ctx)}  ·  M-ONUR",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            textAlign = TextAlign.Center,
        )

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
            onDismissRequest = { scannedMany = emptyList(); scanPreview = null },
            containerColor = PanelHi,
            title = { Text(if (scannedMany.size == 1) "1 karekod bulundu" else "${scannedMany.size} karekod bulundu", color = TextMain) },
            text = {
                Column(
                    Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scanPreview?.let { pv ->
                        Image(
                            pv.asImageBitmap(), "Önizleme",
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                        Text("Kırmızı numaralar aşağıdaki listeyle aynı.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                    scannedMany.forEachIndexed { i, t ->
                        OutlinedButton(
                            onClick = { scanned = t },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                "${i + 1}.  " + (extractUrl(t)?.let { prettyUrl(it) } ?: "Link değil: $t"),
                                Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scannedMany = emptyList(); scanPreview = null }) { Text("Kapat") } },
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

    if (showRemote) RemoteDialog(store) { showRemote = false }
    if (showGuide) GuideDialog { showGuide = false }
    if (showTvFiles) TvFilesDialog(client) { showTvFiles = false }
    if (showApps) RemoteAppsDialog(client, store.tvName) { showApps = false }
    if (showPad) GamepadDialog { showPad = false }
    if (showTvScreen) com.onur.tvgonder.mirror.TvScreenViewer(client, store) { showTvScreen = false }

    if (showHistory) {
        var items by remember { mutableStateOf(store.history()) }
        AlertDialog(
            onDismissRequest = { showHistory = false },
            containerColor = PanelHi,
            title = { Text("Gönderim geçmişi", color = TextMain) },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (items.isEmpty()) Text("Henüz bir şey gönderilmedi.", color = TextMuted)
                    items.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showHistory = false
                                    if (h.kind == "link") sendLink(h.uri) else sendFiles(listOf(Uri.parse(h.uri)))
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (h.kind == "link") "🔗" else "📄", fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (h.kind == "link") prettyUrl(h.name) else h.name,
                                    style = MaterialTheme.typography.bodyMedium, color = TextMain,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "→ ${h.target}  ·  " + android.text.format.DateFormat.format("d MMM HH:mm", h.at),
                                    style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1,
                                )
                            }
                            Text("Tekrar", style = MaterialTheme.typography.labelLarge, color = Accent)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showHistory = false }) { Text("Kapat") } },
            dismissButton = {
                if (items.isNotEmpty() || recent.isNotEmpty()) TextButton(onClick = { store.clearHistory(); items = emptyList(); recent = emptyList() }) { Text("Geçmişi temizle", color = Danger) }
            },
        )
    }

    if (showReceiver) {
        AlertDialog(
            onDismissRequest = { showReceiver = false },
            containerColor = PanelHi,
            title = { Text("Bu cihazı alıcı yap", color = TextMain) },
            text = {
                Text(
                    "Bu cihaz (ör. tablet) TV gibi çalışır: dosya ve link alır, Dosyalarım'dan açar, telefondan güncellenebilir. " +
                        "Sonra telefonda ⋮ → Yeni cihaz ekle ile eşleştir. Geri dönmek için alıcıda Ayarlar → Gönderici moduna dön.",
                    style = MaterialTheme.typography.bodyLarge, color = TextMain,
                )
            },
            confirmButton = {
                Button(onClick = {
                    store.receiverMode = true
                    showReceiver = false
                    (ctx as? android.app.Activity ?: findAct(ctx))?.recreate()
                }) { Text("Alıcı yap") }
            },
            dismissButton = { TextButton(onClick = { showReceiver = false }) { Text("Vazgeç") } },
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
    var datePrefix by remember { mutableStateOf(store.datePrefix) }
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
                Text("Dosya gönderme", style = MaterialTheme.typography.labelLarge, color = Accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Dosya adına gönderim tarihini ekle\n(ör. 2026-10-14 15.30 - foto.jpg)", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = TextMain,
                    )
                    Switch(checked = datePrefix, onCheckedChange = { datePrefix = it; store.datePrefix = it })
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

private fun findAct(c: Context): android.app.Activity? {
    var x: Context? = c
    while (x is android.content.ContextWrapper) {
        if (x is android.app.Activity) return x
        x = x.baseContext
    }
    return null
}
