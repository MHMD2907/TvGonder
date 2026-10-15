// v0.19 – 2026-10-15: fare durumu eklentiden (ping) sorulur; fare/kumanda ekranı, TV'den indirme
package com.onur.tvgonder.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onur.tvgonder.Remote
import com.onur.tvgonder.RemoteFile
import com.onur.tvgonder.Store
import com.onur.tvgonder.TvClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

// ---------------------------------------------------------------- fare / kumanda

/** Telefonu TV için fare + kumanda yapar. mouseOn: TV'de "TV Gönder Fare" açık mı. */
@Composable
fun RemoteDialog(store: Store, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val remote = remember { Remote(store.tvHost ?: "", store.tvToken ?: "") }
        var mouseOn by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { mouseOn = withContext(Dispatchers.IO) { remote.probeMouse() } }
        DisposableEffect(Unit) { onDispose { remote.close() } }
        val speed = 1.8f
        var rx by remember { mutableStateOf(0f) }
        var ry by remember { mutableStateOf(0f) }
        var sAcc by remember { mutableStateOf(0f) }

        Column(
            Modifier.fillMaxSize().background(Ink).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🖱️  Fare / Kumanda", style = MaterialTheme.typography.titleMedium, color = TextMain)
                    Text(store.tvName, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                TextButton(onClick = onClose) { Text("Kapat") }
            }
            if (!mouseOn) {
                Text(
                    "İmleç için: telefonda ⋮ → \"Fare eklentisini TV'ye gönder\", TV'de Yükle; sonra TV'de TV Gönder → Ayarlar → " +
                        "Telefonla fare kontrolü → TV Gönder Fare'yi aç. Oynat/ses düğmeleri şimdiden çalışır.",
                    Modifier.clip(RoundedCornerShape(14.dp)).background(PanelHi).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Danger,
                )
            }
            // Dokunmatik yüzey + kaydırma şeridi
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(PanelHi)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { remote.send("c") }, onLongPress = { remote.send("lc") })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                rx += drag.x * speed; ry += drag.y * speed
                                val ix = rx.toInt(); val iy = ry.toInt()
                                if (ix != 0 || iy != 0) { remote.send("m $ix $iy"); rx -= ix; ry -= iy }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Parmağını kaydır: imleç\nDokun: tıkla   ·   Basılı tut: uzun bas",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center,
                    )
                }
                Box(
                    Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Panel)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dy ->
                                change.consume()
                                sAcc += dy
                                if (kotlin.math.abs(sAcc) > 30) { remote.send("s ${(-sAcc * 3).toInt()}"); sAcc = 0f }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("↕", fontSize = 26.sp, color = TextMuted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("⟵ Geri") { remote.send("back") }
                Key("⌂ Ana ekran") { remote.send("home") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("⏮") { remote.send("prev") }
                Key("⏪") { remote.send("rw") }
                Key("⏯") { remote.send("play") }
                Key("⏩") { remote.send("ff") }
                Key("⏭") { remote.send("next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("🔉 Ses −") { remote.send("vd") }
                Key("🔇") { remote.send("mute") }
                Key("🔊 Ses +") { remote.send("vu") }
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(16.dp)).background(PanelHi).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = TextMain, maxLines = 1) }
}

// ---------------------------------------------------------------- TV'den telefona indirme

/** İndirilen dosyayı İndirilenler/TvGonder klasörüne yazar; açılabilir adresini döner. */
private fun saveDownload(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
    if (Build.VERSION.SDK_INT >= 29) {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TvGonder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Kaydedilemedi")
        try {
            (cr.openOutputStream(uri) ?: error("Kaydedilemedi")).use(write)
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null); throw e
        }
        return uri
    }
    val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TvGonder").apply { mkdirs() }
    val f = File(dir, name)
    f.outputStream().use(write)
    return androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
}

private fun emojiFor(f: RemoteFile) = when {
    f.dir -> "📁"
    f.mime.startsWith("video/") -> "🎬"
    f.mime.startsWith("audio/") -> "🎵"
    f.mime.startsWith("image/") -> "🖼️"
    f.mime == "application/pdf" -> "📄"
    else -> "📎"
}

@Composable
fun TvFilesDialog(client: TvClient, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastSaved by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        items = null; err = null
        val r = withContext(Dispatchers.IO) { runCatching { client.files(path) } }
        items = r.getOrNull()
        err = r.exceptionOrNull()?.message?.let { if (r.exceptionOrNull() is java.io.IOException) "TV'ye ulaşılamadı." else it }
    }

    fun download(f: RemoteFile) {
        if (busy) return
        busy = true; lastSaved = null
        status = "İndiriliyor: ${f.name}"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    saveDownload(ctx, f.name, f.mime) { out ->
                        client.download(path, f.name, out) { got, total ->
                            if (total > 0) status = "İndiriliyor: ${f.name}  %${got * 100 / total}"
                        }
                    }
                }
            }
            busy = false
            r.onSuccess { uri -> status = "✓ İndirildi: İndirilenler/TvGonder/${f.name}"; lastSaved = uri to f.mime }
                .onFailure { status = "İndirilemedi: ${it.message}" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelHi,
        title = { Text(if (path.isEmpty()) "📥 TV'deki dosyalar" else "📁 " + path.substringAfterLast('/'), color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Dosyaya dokun: telefona indir (İndirilenler/TvGonder).", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                if (path.isNotEmpty()) {
                    Text(
                        "‹  Üst klasör", Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { path = path.substringBeforeLast('/', "") }.padding(vertical = 12.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyLarge, color = TextMuted,
                    )
                }
                val l = items
                when {
                    err != null -> Text(err!!, color = Danger)
                    l == null -> Text("Yükleniyor…", color = TextMuted, modifier = Modifier.padding(8.dp))
                    l.isEmpty() -> Text("Bu klasör boş.", color = TextMuted, modifier = Modifier.padding(8.dp))
                    else -> l.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { if (f.dir) path = if (path.isEmpty()) f.name else "$path/${f.name}" else download(f) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emojiFor(f), fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!f.dir) Text(
                                    Formatter.formatShortFileSize(ctx, f.size) + "  ·  " + DateFormat.format("d MMM yyyy HH:mm", f.mod),
                                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                                )
                            }
                            if (!f.dir) Text("İndir", style = MaterialTheme.typography.labelLarge, color = Accent)
                        }
                    }
                }
                status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Accent) }
                lastSaved?.let { (uri, mime) ->
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Aç") }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Kapat") } },
    )
}
