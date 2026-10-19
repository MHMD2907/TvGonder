// v0.23 – 2026-10-19: Uygulamalar: TV/tablette liste (aç, kaldır, bilgi); telefonda TV'deki uygulamaları aç/kaldır
package com.onur.tvgonder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.onur.tvgonder.Apps
import com.onur.tvgonder.TvClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- TV / tablet ekranı

/** Bu cihazdaki uygulamalar. OK/dokun: Aç · Kaldır · Bilgi. Kaldırınca liste yenilenir. */
@Composable
fun TvAppsScreen() {
    val ctx = LocalContext.current
    var system by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<Apps.App>?>(null) }
    var chosen by remember { mutableStateOf<Apps.App?>(null) }
    val first = remember { FocusRequester() }

    // Kaldırma penceresinden dönünce listeyi yenile
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        life.lifecycle.addObserver(o)
        onDispose { life.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(system, tick) { apps = withContext(Dispatchers.IO) { Apps.list(ctx, system) } }
    LaunchedEffect(apps != null) { if (apps != null) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🧩  Uygulamalar", style = MaterialTheme.typography.headlineMedium, color = TextMain)
                Text(
                    apps?.let { "${it.size} uygulama  ·  Seç: Aç / Kaldır / Bilgi" } ?: "Yükleniyor…",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
            }
            TvCard(padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), onClick = { system = !system }) { f ->
                Text(if (system) "☑ Sistem uygulamaları" else "☐ Sistem uygulamaları", color = if (f) Accent else TextMain)
            }
        }
        Spacer(Modifier.height(18.dp))
        val list = apps
        if (list != null) {
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(list, key = { it.pkg }) { a ->
                    TvCard(
                        Modifier.height(150.dp), focusRequester = if (a == list.first()) first else null,
                        padding = PaddingValues(10.dp), onClick = { chosen = a },
                    ) { f ->
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val icon = remember(a.pkg) { Apps.icon(ctx, a.pkg, 128) }
                            if (icon != null) Image(icon.asImageBitmap(), a.label, Modifier.size(64.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                a.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium, color = if (f) Accent else TextMain,
                            )
                        }
                    }
                }
            }
        }
    }

    chosen?.let { a ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            containerColor = Panel,
            title = { Text(a.label, color = TextMain) },
            text = {
                Text(
                    listOfNotNull(
                        Apps.sizeText(a.size).ifBlank { null },
                        if (a.system) "Sistem uygulaması: kaldırılamaz, Bilgi'den devre dışı bırakılabilir" else null,
                    ).joinToString("\n"),
                    color = TextMuted,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { chosen = null; Apps.open(ctx, a.pkg) }) { Text("▶ Aç") }
                    if (!a.system) OutlinedButton(onClick = { chosen = null; Apps.remove(ctx, a.pkg) }) { Text("🗑 Kaldır") }
                    OutlinedButton(onClick = { chosen = null; Apps.info(ctx, a.pkg) }) { Text("ℹ Bilgi") }
                }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Vazgeç") } },
        )
    }
}

// ---------------------------------------------------------------- telefon: TV'deki uygulamalar

@Composable
fun RemoteAppsDialog(client: TvClient, tvName: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var system by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<TvClient.RemoteApp>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<TvClient.RemoteApp?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val icons = remember { mutableStateMapOf<String, Bitmap?>() }

    LaunchedEffect(system, reload) {
        error = null
        apps = null
        runCatching { withContext(Dispatchers.IO) { client.apps(system) } }
            .onSuccess { apps = it }
            .onFailure { error = "Liste alınamadı: ${it.message}. TV'deki TV Gönder güncel mi?" }
    }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(4000); note = null } }

    fun act(block: () -> Unit, ok: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { note = ok }
                .onFailure { note = "Olmadı: ${it.message}" }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🧩  $tvName uygulamaları", style = MaterialTheme.typography.titleMedium, color = TextMain)
                    Text("Aç: TV'de açılır  ·  Kaldır: onayı TV ekranında ver", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                TextButton(onClick = onClose) { Text("Kapat") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Ara") }, singleLine = true)
                FilterChip(system, { system = !system }, label = { Text("Sistem") })
            }
            note?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodyMedium) }
            error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium) }
            val list = apps?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            if (list == null && error == null) Text("Yükleniyor…", color = TextMuted)
            if (list != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.pkg }) { a ->
                    LaunchedEffect(a.pkg) {
                        if (!icons.containsKey(a.pkg)) {
                            icons[a.pkg] = null
                            icons[a.pkg] = withContext(Dispatchers.IO) { client.appIcon(a.pkg)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(PanelHi), contentAlignment = Alignment.Center) {
                            icons[a.pkg]?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp)) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(Apps.sizeText(a.size).ifBlank { null }, if (a.system) "sistem" else null).joinToString(" · "),
                                color = TextMuted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { act({ client.appOpen(a.pkg) }, "${a.label} TV'de açılıyor") }) { Text("▶ Aç") }
                        if (!a.system) TextButton(onClick = { confirm = a }) { Text("🗑", fontSize = 18.sp) }
                    }
                }
            }
        }
    }

    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${a.label} kaldırılsın mı?") },
            text = { Text("TV'de \"Kaldırılsın mı?\" penceresi açılır; kumandayla Tamam'a bas.") },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    act({ client.appRemove(a.pkg) }, "TV'de onay penceresi açıldı")
                    scope.launch { kotlinx.coroutines.delay(8000); reload++ }
                }) { Text("Kaldır") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}
