// v0.8 – 2026-10-12: Belge tara (kamera, otomatik kırpma); çoklu seçim, düzen, önizleme, PDF olarak kaydet
package com.onur.tvgonder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onur.tvgonder.Layout
import com.onur.tvgonder.PrintItem
import com.onur.tvgonder.PrintJob
import com.onur.tvgonder.Printing
import com.onur.tvgonder.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrintScreen(incoming: List<Uri> = emptyList(), onIncomingHandled: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }

    val items = remember { mutableStateListOf<PrintItem>() }
    val thumbs = remember { mutableStateMapOf<PrintItem, Bitmap?>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var layout by remember { mutableStateOf(Layout.One) }
    var a5 by remember { mutableStateOf(store.defA5) }
    var landscape by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(store.defColor) }
    var sheet by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    val job = PrintJob(items.toList())
    val sheets = job.sheets(layout)

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true; error = null; info = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Printing.load(ctx, uris) } }
            busy = false
            r.onSuccess { loaded ->
                val wasEmpty = items.isEmpty()
                items.addAll(loaded)
                if (loaded.size < uris.size) error = "${uris.size - loaded.size} dosya açılamadı (sadece PDF ve resim)."
                // Tek resim seçildiyse yönü resme göre ayarla
                val t = withContext(Dispatchers.IO) { loaded.associateWith { Printing.thumb(ctx, it) } }
                thumbs.putAll(t)
                if (wasEmpty && items.size == 1) thumbs[items[0]]?.let { landscape = it.width > it.height }
            }.onFailure { error = it.message ?: "Dosya açılamadı." }
        }
    }

    fun clear() {
        items.clear(); thumbs.clear(); preview = null; error = null; info = null
        layout = Layout.One; a5 = store.defA5; color = store.defColor; landscape = false; sheet = 0
    }

    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val res = GmsDocumentScanningResult.fromActivityResultIntent(r.data)
        val pages = res?.pages?.map { it.imageUri }.orEmpty()
        if (pages.isNotEmpty()) add(pages)
    }

    fun startScan() {
        val act = ctx.findActivity() ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { scan.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { error = "Belge tarayıcı açılamadı (Google Play hizmetleri gerekli): ${it.message}" }
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { add(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        val snapshot = PrintJob(items.toList())
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        Printing.writePdf(ctx, snapshot, layout, a5, landscape, color, out)
                    } ?: error("Dosya kaydedilemedi.")
                }
            }
            busy = false
            if (r.isSuccess) info = "PDF kaydedildi." else error = r.exceptionOrNull()?.message ?: "PDF oluşturulamadı."
        }
    }

    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) { add(incoming); onIncomingHandled() }
    }

    // Kağıt önizlemesi: ayar değiştikçe yeniden çizilir
    val key = items.toList()
    LaunchedEffect(key, layout, a5, landscape, color, sheet) {
        if (key.isEmpty()) { preview = null; return@LaunchedEffect }
        val s = sheet.coerceIn(0, PrintJob(key).sheets(layout) - 1)
        if (s != sheet) { sheet = s; return@LaunchedEffect }
        preview = withContext(Dispatchers.IO) {
            runCatching { Printing.preview(ctx, PrintJob(key), layout, s, a5, landscape, color) }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Yazdır", style = MaterialTheme.typography.headlineMedium, color = TextMain)

        if (items.isEmpty()) {
            Text(
                "Bir ya da birden fazla fotoğraf, resim veya PDF seç. Hepsi tek seferde yazdırılır ya da tek PDF yapılır.",
                style = MaterialTheme.typography.bodyLarge, color = TextMuted,
            )
            PickRow(busy,
                onPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFiles = { pickFiles.launch(arrayOf("application/pdf", "image/*")) },
                onScan = { startScan() },
            )
            if (busy) Text("Açılıyor…", color = Accent)
        } else {
            // Seçilenler
            PrintCard("Seçilenler (${items.size})") {
                items.forEachIndexed { i, it ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val t = thumbs[it]
                        Box(Modifier.size(48.dp, 60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                            if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.name, style = MaterialTheme.typography.bodyMedium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (it is PrintItem.Pdf) "PDF · ${it.pages} sayfa" else "Resim", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                        IconButton(enabled = i > 0, onClick = { items.add(i - 1, items.removeAt(i)) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Yukarı", tint = if (i > 0) TextMuted else Panel)
                        }
                        IconButton(enabled = i < items.size - 1, onClick = { items.add(i + 1, items.removeAt(i)) }) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Aşağı", tint = if (i < items.size - 1) TextMuted else Panel)
                        }
                        IconButton(onClick = { thumbs.remove(it); items.removeAt(i) }) {
                            Icon(Icons.Filled.Close, "Çıkar", tint = Danger)
                        }
                    }
                }
                PickRow(busy,
                    onPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onFiles = { pickFiles.launch(arrayOf("application/pdf", "image/*")) },
                    onScan = { startScan() },
                    add = true,
                )
            }

            // Düzen
            PrintCard("Sayfa düzeni") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Layout.entries.forEach { l ->
                        FilterChip(selected = layout == l, onClick = { layout = l; sheet = 0; if (l == Layout.Id) landscape = false }, label = { Text(l.label) })
                    }
                }
                Text(
                    when (layout) {
                        Layout.One -> "Her resim / PDF sayfası ayrı kağıda, sayfaya sığdırılarak."
                        Layout.Two, Layout.Four, Layout.Six -> "Bir kağıda ${layout.perSheet} resim/sayfa. Kağıt tasarrufu için."
                        Layout.Id -> "Ön ve arka yüzü sırayla seç ya da \"Belge tara\" ile çek (kenarlar otomatik kırpılır). Gerçek kart boyutunda (85,6 × 54 mm) alt alta basılır."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
                Text("Kağıt", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !a5, onClick = { a5 = false }, label = { Text("A4") })
                    FilterChip(selected = a5, onClick = { a5 = true }, label = { Text("A5") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !landscape, onClick = { landscape = false }, label = { Text("Dikey") })
                    FilterChip(selected = landscape, onClick = { landscape = true }, label = { Text("Yatay") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = color, onClick = { color = true }, label = { Text("Renkli") })
                    FilterChip(selected = !color, onClick = { color = false }, label = { Text("Siyah-beyaz") })
                }
            }

            // Önizleme
            PrintCard("Önizleme") {
                val ratio = if (a5) 420f / 595f else 595f / 842f
                Box(
                    Modifier.fillMaxWidth().aspectRatio(if (landscape) 1f / ratio else ratio)
                        .clip(RoundedCornerShape(6.dp)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val p = preview
                    if (p != null) Image(p.asImageBitmap(), "Önizleme", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("Hazırlanıyor…", color = Color.Gray)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = sheet > 0, onClick = { sheet-- }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, "Önceki", tint = if (sheet > 0) TextMain else Panel)
                    }
                    Text(
                        "Kağıt ${sheet + 1} / $sheets   ·   ${job.tiles.size} sayfa/resim",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    )
                    IconButton(enabled = sheet < sheets - 1, onClick = { sheet++ }) {
                        Icon(Icons.Filled.KeyboardArrowRight, "Sonraki", tint = if (sheet < sheets - 1) TextMain else Panel)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clear() },
                    modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(14.dp),
                ) { Text("Vazgeç") }
                Button(
                    enabled = !busy,
                    onClick = {
                        error = runCatching { Printing.print(ctx, PrintJob(items.toList()), layout, a5, landscape, color) }
                            .exceptionOrNull()?.message
                    },
                    modifier = Modifier.weight(1.6f).height(54.dp), shape = RoundedCornerShape(14.dp),
                ) { Text("Yazıcıya gönder") }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val base = if (items.size == 1) items[0].name.substringBeforeLast('.') else "TVGonder_${items.size}_dosya"
                    savePdf.launch("$base.pdf")
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            ) { Text(if (busy) "Hazırlanıyor…" else "PDF olarak kaydet") }
            Text(
                "Yazıcıyı, sayfa aralığını ve kopya sayısını sonraki ekranda seçersin.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            )
        }
        info?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Accent) }
        error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Danger) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PickRow(busy: Boolean, onPhotos: () -> Unit, onFiles: () -> Unit, onScan: () -> Unit, add: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val m = Modifier.height(if (add) 48.dp else 96.dp)
        if (add) {
            OutlinedButton(enabled = !busy, onClick = onPhotos, modifier = m.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("+ Fotoğraf") }
            OutlinedButton(enabled = !busy, onClick = onFiles, modifier = m.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("+ Dosya / PDF") }
        } else {
            Button(enabled = !busy, onClick = onPhotos, modifier = m.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("🖼️  Fotoğraf seç") }
            Button(enabled = !busy, onClick = onFiles, modifier = m.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("📄  Dosya / PDF") }
        }
    }
    if (add) {
        OutlinedButton(enabled = !busy, onClick = onScan, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("+ Belge tara") }
    } else {
        Button(enabled = !busy, onClick = onScan, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp)) {
            Text("📷  Belge / kimlik tara (kamera)")
        }
    }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun PrintCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain)
            content()
        }
    }
}
