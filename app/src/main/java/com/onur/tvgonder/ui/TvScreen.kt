// v0.18.1 – 2026-10-15: Ayarlar: video/resim görüntüsü tam ekran veya sığdır; fare kontrolü, tablet alıcı+verici
package com.onur.tvgonder.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.onur.tvgonder.Hub
import com.onur.tvgonder.Installer
import com.onur.tvgonder.Net
import com.onur.tvgonder.R
import com.onur.tvgonder.ReceiverServer
import com.onur.tvgonder.Received
import com.onur.tvgonder.Shared
import com.onur.tvgonder.Store
import com.onur.tvgonder.Volumes
import com.onur.tvgonder.appVersionCode
import com.onur.tvgonder.exitApp
import com.onur.tvgonder.isTv
import kotlinx.coroutines.delay

/** TV'deki ekranlar (geri tuşu bir öncekine döner). */
sealed interface TvNav {
    data object Home : TvNav
    data object Settings : TvNav
    data object Slides : TvNav
    data class Folder(val dir: Uri, val title: String) : TvNav
    data class Images(val list: List<Entry>, val start: Int) : TvNav
    data class Pdf(val uri: Uri, val name: String) : TvNav
    data class Video(val list: List<Uri>, val start: Int, val audio: Boolean) : TvNav
    data class Web(val url: String) : TvNav
    data object Send : TvNav
}

private val HomeBg = Brush.verticalGradient(listOf(Color(0xFF0B2633), Ink, Color(0xFF09131F)))

@Composable
fun TvScreen(
    store: Store, tree: String?, onPickSaf: () -> Boolean, onSetStorage: (String) -> Unit,
    shared: Shared? = null, onSharedHandled: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<TvNav>(TvNav.Home) }
    val top = stack.last()
    val treeUri = remember(tree) { tree?.let { Uri.parse(it) } }
    val storageName = remember(tree) {
        tree?.let { t -> Volumes.labelFor(ctx, t) ?: runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.name }.getOrNull() }
    }
    var showStorage by remember { mutableStateOf(false) }
    val onPickStorage: () -> Unit = { showStorage = true }
    val ip = remember { Net.localIp() }

    val devices by Hub.devices.collectAsState()
    val code by Hub.pairingCode.collectAsState()
    val pending by Hub.pending.collectAsState()
    val running by Hub.running.collectAsState()
    val update by Hub.updateReady.collectAsState()
    val openUrl by Hub.openUrl.collectAsState()
    val rec by Hub.received.collectAsState()

    var note by remember { mutableStateOf<String?>(null) }

    // İlk açılış: takılı USB varsa kayıt yeri olarak kendiliğinden seç
    LaunchedEffect(tree) {
        if (tree == null) {
            val vols = Volumes.list(ctx)
            (vols.firstOrNull { it.removable } ?: if (!isTv(ctx)) vols.firstOrNull() else null)?.let {
                onSetStorage(Uri.fromFile(it.dir).toString())
                note = "Kayıt yeri: ${it.label} (otomatik seçildi)"
            }
        }
    }
    var toast by remember { mutableStateOf<Received?>(null) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun push(n: TvNav) { stack.add(n) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    fun open(e: Entry, siblings: List<Entry>) {
        when (e.kind) {
            Kind.Image -> {
                val imgs = siblings.filter { it.kind == Kind.Image }.ifEmpty { listOf(e) }
                push(TvNav.Images(imgs, imgs.indexOf(e).coerceAtLeast(0)))
            }
            Kind.Pdf -> push(TvNav.Pdf(e.uri, e.name))
            Kind.Video -> push(TvNav.Video(listOf(e.uri), 0, audio = false))
            Kind.Audio -> {
                val songs = siblings.filter { it.kind == Kind.Audio }.ifEmpty { listOf(e) }
                push(TvNav.Video(songs.map { it.uri }, songs.indexOf(e).coerceAtLeast(0), audio = true))
            }
            else -> {
                val ok = runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, e.mime.ifBlank { "*/*" })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!ok) note = "Bu dosya türü TV'de açılamıyor: ${e.name}"
            }
        }
    }

    fun openReceived(r: Received) {
        val e = Entry(r.name, Uri.parse(r.uri), r.mime, 0, r.at)
        open(e, listOf(e))
    }

    // Tablet: başka uygulamadan paylaşılan içerik gönderme ekranına gider
    LaunchedEffect(shared) { if (shared != null && top != TvNav.Send) push(TvNav.Send) }

    // Telefondan gelen link: uygulamanın tarayıcısında aç
    LaunchedEffect(openUrl) {
        val u = openUrl ?: return@LaunchedEffect
        if (top is TvNav.Web) stack[stack.lastIndex] = TvNav.Web(u) else push(TvNav.Web(u))
        Hub.openUrl.value = null
    }
    // Yeni dosya geldi: kart göster (slayt gösterisi açıksa kapat)
    LaunchedEffect(rec) {
        val r = rec ?: return@LaunchedEffect
        if (top == TvNav.Slides) pop()
        toast = r
        delay(15_000)
        if (toast == r) toast = null
    }
    // Boşta 5 dakika: slayt gösterisi
    LaunchedEffect(top, lastKey) {
        if (top == TvNav.Home && treeUri != null && store.idleSlides) {
            delay(5 * 60_000L)
            push(TvNav.Slides)
        }
    }
    LaunchedEffect(note) { if (note != null) { delay(6000); note = null } }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { lastKey = System.currentTimeMillis(); false }) {
        key(stack.size, top) {
            when (val n = top) {
                TvNav.Home -> TvHome(
                    store = store, hasTree = treeUri != null, storageName = storageName, ip = ip, running = running,
                    deviceCount = devices.size, rec = rec,
                    onFiles = { if (treeUri != null) push(TvNav.Folder(Usb.rootUri(treeUri), storageName ?: "USB bellek")) else onPickStorage() },
                    onSlides = { if (treeUri != null) push(TvNav.Slides) else onPickStorage() },
                    onAddPhone = { if (ip != null) Hub.pairingCode.value = (100000..999999).random().toString() else note = "TV Wi‑Fi'ye bağlı değil." },
                    onSettings = { push(TvNav.Settings) },
                    onSend = if (isTv(ctx)) null else ({ push(TvNav.Send) }),
                    onPickStorage = onPickStorage,
                    onOpenRecent = { openReceived(it) },
                )
                TvNav.Settings -> TvSettings(
                    store = store, storageName = storageName, ip = ip, devices = devices,
                    onPickStorage = onPickStorage,
                    onAddPhone = { if (ip != null) Hub.pairingCode.value = (100000..999999).random().toString() },
                )
                TvNav.Slides -> if (treeUri != null) TvSlideshow(treeUri) { pop() }
                is TvNav.Folder -> if (treeUri != null) TvFilesScreen(
                    treeUri, n.dir, n.title,
                    onOpenFolder = { push(TvNav.Folder(it.uri, it.name)) },
                    onOpen = { e, list -> open(e, list) },
                )
                is TvNav.Images -> TvImageViewer(n.list, n.start)
                is TvNav.Pdf -> TvPdfViewer(n.uri, n.name)
                is TvNav.Video -> TvVideoPlayer(n.list, n.start, n.audio)
                is TvNav.Web -> TvBrowser(n.url) { pop() }
                TvNav.Send -> PhoneRoot(store, shared, onSharedHandled)
            }
        }

        toast?.let { r ->
            ReceivedCard(
                r, autoFocus = top == TvNav.Home,
                modifier = Modifier.align(Alignment.BottomEnd).padding(36.dp),
                onOpen = { toast = null; openReceived(r) },
            )
        }
        note?.let {
            Text(
                it, Modifier.align(Alignment.BottomCenter).padding(32.dp).clip(RoundedCornerShape(14.dp))
                    .background(PanelHi).padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge, color = TextMain,
            )
        }
    }

    // ---- Kayıt yeri seçimi
    if (showStorage) {
        val vols = remember { Volumes.list(ctx) }
        val firstBtn = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstBtn.requestFocus() } }
        AlertDialog(
            onDismissRequest = { showStorage = false },
            containerColor = PanelHi,
            title = { Text("Dosyalar nereye kaydedilsin?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    vols.forEachIndexed { i, v ->
                        Button(
                            onClick = {
                                onSetStorage(Uri.fromFile(v.dir).toString())
                                showStorage = false
                                note = "Kayıt yeri: ${v.label}"
                            },
                            modifier = (if (i == 0) Modifier.focusRequester(firstBtn) else Modifier).fillMaxWidth(),
                        ) { Text((if (v.removable) "💾  " else "📺  ") + v.label + if (v.removable) "  (önerilen)" else "") }
                    }
                    if (vols.none { it.removable }) {
                        Text("Takılı USB bellek bulunamadı. Takıp tekrar dene ya da TV hafızasını seç.", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = {
                            showStorage = false
                            if (!onPickSaf()) note = "Bu TV'de klasör seçici yok. Listeden birini seç."
                        },
                        modifier = (if (vols.isEmpty()) Modifier.focusRequester(firstBtn) else Modifier).fillMaxWidth(),
                    ) { Text("🗂️  Başka klasör seç (Android seçicisi)") }
                    Text(
                        "Dosyalar seçtiğin birimde TvGonder klasörüne kaydedilir ve Dosyalarım'dan açılır.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { OutlinedButton(onClick = { showStorage = false }) { Text("Kapat") } },
        )
    }

    // ---- Eşleştirme: QR kod
    if (code != null && ip != null) {
        val bmp = remember(code) { Net.qr("tvgonder://pair?host=$ip&port=${ReceiverServer.PORT}&code=$code") }
        AlertDialog(
            onDismissRequest = { Hub.pairingCode.value = null },
            containerColor = PanelHi,
            title = { Text("Telefon ekle") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp.asImageBitmap(), null, Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Telefonda TV Gönder'i aç.", style = MaterialTheme.typography.bodyLarge)
                        Text("2. \"QR kodu okut\"a dokun, bu kodu okut.", style = MaterialTheme.typography.bodyLarge)
                        Text("3. Burada Onayla'ya bas.", style = MaterialTheme.typography.bodyLarge)
                        Text(code!!, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Accent, letterSpacing = 6.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { OutlinedButton(onClick = { Hub.pairingCode.value = null }) { Text("Vazgeç") } },
        )
    }

    // ---- Eşleştirme: onay
    pending?.let { req ->
        AlertDialog(
            onDismissRequest = { req.decision.complete(false) },
            containerColor = PanelHi,
            title = { Text("Yeni telefon") },
            text = { Text("${req.name} bu TV'ye bağlanmak istiyor.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = { req.decision.complete(true) }) { Text("Onayla") } },
            dismissButton = { OutlinedButton(onClick = { req.decision.complete(false) }) { Text("Reddet") } },
        )
    }

    // ---- Telefondan gelen yeni sürüm
    update?.let { apk ->
        var info by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { Hub.updateReady.value = null },
            containerColor = PanelHi,
            title = { Text("Yeni sürüm hazır") },
            text = {
                Text(
                    info ?: "Telefondan TV Gönder'in yeni sürümü geldi. Yükle'ye bas, açılan ekranda tekrar Yükle'yi seç.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (!Installer.canInstall(ctx)) {
                        val opened = Installer.openPermission(ctx)
                        info = if (opened) "Açılan ekranda TV Gönder için izni aç, geri dön ve tekrar Yükle'ye bas."
                        else "Ayarlar > Cihaz Tercihleri > Güvenlik ve kısıtlamalar > Bilinmeyen kaynaklar bölümünden TV Gönder'e izin ver, sonra tekrar Yükle'ye bas."
                    } else {
                        runCatching { Installer.install(ctx, apk) }
                            .onSuccess { Hub.updateReady.value = null }
                            .onFailure { info = "Kurulum ekranı açılamadı: ${it.message}" }
                    }
                }) { Text("Yükle") }
            },
            dismissButton = { OutlinedButton(onClick = { Hub.updateReady.value = null }) { Text("Sonra") } },
        )
    }
}

// ---------------------------------------------------------------- ana ekran

@Composable
private fun TvHome(
    store: Store,
    hasTree: Boolean,
    storageName: String?,
    ip: String?,
    running: Boolean,
    deviceCount: Int,
    rec: Received?,
    onFiles: () -> Unit,
    onSlides: () -> Unit,
    onAddPhone: () -> Unit,
    onSettings: () -> Unit,
    onSend: (() -> Unit)?,
    onPickStorage: () -> Unit,
    onOpenRecent: (Received) -> Unit,
) {
    val first = remember { FocusRequester() }
    val recent = remember(rec) { store.received() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        while (true) { delay(20_000); now = System.currentTimeMillis() }
    }

    Column(Modifier.fillMaxSize().background(HomeBg).padding(horizontal = 48.dp, vertical = 40.dp)) {
        // Başlık + saat
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_round), "M-ONUR", Modifier.size(104.dp))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("TV Gönder", style = MaterialTheme.typography.headlineLarge, color = TextMain)
                Text("Telefondan gelen dosya ve linkler burada açılır", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(DateFormat.format("HH:mm", now).toString(), fontSize = 52.sp, fontWeight = FontWeight.Light, color = TextMain)
                Text(DateFormat.format("d MMMM EEEE", now).toString(), style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvChip(
                when { !running -> "Alıcı kapalı"; ip == null -> "Wi‑Fi yok"; else -> "Hazır  ·  $ip" },
                if (running && ip != null) Accent else Danger,
            )
            TvChip(if (hasTree) "USB: ${storageName ?: "seçili"}" else "USB bellek seçilmedi", if (hasTree) Accent else Danger)
            TvChip("$deviceCount telefon eşleşmiş", if (deviceCount > 0) Accent else TextMuted)
        }
        Spacer(Modifier.height(32.dp))

        // Büyük kartlar
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val m = Modifier.weight(1f).height(190.dp)
            if (!hasTree) HomeTile(m, "💾", "USB belleği seç", "Gelen dosyalar buraya kaydedilir", first, onPickStorage)
            HomeTile(m, "📁", "Dosyalarım", "Video, PDF ve resimleri aç", if (hasTree) first else null, onFiles)
            HomeTile(m, "🖼️", "Slayt gösterisi", "Bellekteki fotoğraflar", null, onSlides)
            HomeTile(m, "📱", "Telefon ekle", "QR kod ile eşleştir", null, onAddPhone)
            if (onSend != null) HomeTile(m, "📤", "Gönder", "Buradan TV'ye link, dosya, yazdır", null, onSend)
            HomeTile(m, "⚙️", "Ayarlar", "USB, telefonlar, slayt", null, onSettings)
        }
        Spacer(Modifier.height(32.dp))

        // Son gelenler
        Text("Son gelenler", style = MaterialTheme.typography.headlineMedium, color = TextMain)
        Spacer(Modifier.height(14.dp))
        if (recent.isEmpty()) {
            Text(
                "Henüz dosya gelmedi. Telefonda TV Gönder'i açıp dosya ya da link gönder.",
                style = MaterialTheme.typography.bodyLarge, color = TextMuted,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                items(recent, key = { it.uri }) { r ->
                    TvCard(Modifier.width(320.dp).height(120.dp), padding = PaddingValues(12.dp), onClick = { onOpenRecent(r) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EntryThumb(Entry(r.name, Uri.parse(r.uri), r.mime, 0, r.at), Modifier.size(width = 130.dp, height = 96.dp))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(r.name, style = MaterialTheme.typography.titleMedium, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    DateUtils.getRelativeTimeSpanString(r.at, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTile(modifier: Modifier, emoji: String, title: String, sub: String, fr: FocusRequester?, onClick: () -> Unit) {
    TvCard(modifier, focusRequester = fr, padding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), onClick = onClick) { focused ->
        Text(emoji, fontSize = 42.sp)
        Spacer(Modifier.weight(1f))
        Text(
            title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            color = if (focused) Accent else TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp), color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReceivedCard(r: Received, autoFocus: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(r) { if (autoFocus) runCatching { fr.requestFocus() } }
    val kind = Entry(r.name, Uri.EMPTY, r.mime, 0, 0).kind
    TvCard(modifier.width(440.dp), focusRequester = fr, padding = PaddingValues(22.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📥", fontSize = 40.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Yeni dosya geldi", style = MaterialTheme.typography.bodyMedium, color = Accent)
                Text("${kind.emoji} ${r.name}", style = MaterialTheme.typography.titleMedium, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Açmak için OK", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
        }
    }
}

// ---------------------------------------------------------------- ayarlar

@Composable
private fun TvSettings(
    store: Store,
    storageName: String?,
    ip: String?,
    devices: Map<String, String>,
    onPickStorage: () -> Unit,
    onAddPhone: () -> Unit,
) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var idle by remember { mutableStateOf(store.idleSlides) }
    var vfill by remember { mutableStateOf(store.videoFill) }
    var ifill by remember { mutableStateOf(store.imageFill) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(HomeBg).padding(horizontal = 64.dp, vertical = 44.dp)) {
        Text("⚙️  Ayarlar", style = MaterialTheme.typography.headlineLarge, color = TextMain)
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
            item {
                SettingRow("💾", "USB bellek", storageName ?: "Seçilmedi", "Değiştir", first, onPickStorage)
            }
            item {
                SettingRow("🖼️", "Boşta slayt gösterisi", if (idle) "Açık – 5 dakika işlem olmazsa başlar" else "Kapalı", if (idle) "Kapat" else "Aç", null) {
                    idle = !idle; store.idleSlides = idle
                }
            }
            item {
                SettingRow("🎬", "Video görüntüsü", if (vfill) "Tam ekran – oran korunur, kenarlar kırpılabilir" else "Sığdır – görüntünün tamamı, kenarlarda siyah boşluk", if (vfill) "Sığdır" else "Tam ekran", null) {
                    vfill = !vfill; store.videoFill = vfill
                }
            }
            item {
                SettingRow("🖼️", "Resim görüntüsü", if (ifill) "Tam ekran – oran korunur (resimde OK ile değişir)" else "Sığdır – resmin tamamı (resimde OK ile değişir)", if (ifill) "Sığdır" else "Tam ekran", null) {
                    ifill = !ifill; store.imageFill = ifill
                }
            }
            item {
                SettingRow("📱", "Telefon ekle", "QR kod ile yeni telefon eşleştir", "Ekle", null, onAddPhone)
            }
            item {
                val on = com.onur.tvgonder.MouseService.instance != null
                SettingRow(
                    "🖱️", "Telefonla fare kontrolü",
                    if (on) "Açık – telefonda ⋮ → Fare / Kumanda" else "Kapalı – açılan ekranda \"TV Gönder Fare\"yi seç ve aç",
                    if (on) "Ayarlar" else "Aç", null,
                ) {
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            items(devices.entries.toList(), key = { it.key }) { (token, name) ->
                SettingRow("📲", name, "Eşleşmiş telefon", "Kaldır", null) {
                    store.removeDevice(token); Hub.devices.value = store.devices()
                }
            }
            if (!isTv(ctx)) item {
                SettingRow("📱", "Gönderici moduna dön", "Bu cihaz yeniden telefondaki gibi gönderici olur", "Dön", null) {
                    store.receiverMode = false
                    ctx.stopService(android.content.Intent(ctx, com.onur.tvgonder.ReceiverService::class.java))
                    var x: android.content.Context? = ctx
                    while (x is android.content.ContextWrapper) { if (x is android.app.Activity) { x.recreate(); break }; x = x.baseContext }
                }
            }
            item {
                SettingRow("🚪", "Uygulamadan çık", "Telefondan gelenler yine de alınır", "Çık", null) { exitApp(ctx) }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adres: ${ip ?: "Wi‑Fi yok"}:${ReceiverServer.PORT}   ·   Sürüm v${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: appVersionCode(ctx)}   ·   M-ONUR",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(emoji: String, title: String, sub: String, action: String, fr: FocusRequester?, onClick: () -> Unit) {
    TvCard(Modifier.fillMaxWidth(), focusRequester = fr, padding = PaddingValues(horizontal = 24.dp, vertical = 18.dp), onClick = onClick) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 32.sp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Text(action, style = MaterialTheme.typography.labelLarge, color = if (focused) Accent else TextMuted)
        }
    }
}
