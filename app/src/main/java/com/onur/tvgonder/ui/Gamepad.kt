// v0.29 – 2026-10-20: Bluetooth: telefon TV'yi arar/eşleşir/bağlanır; Kumanda, oyun kolu, fare, medya tuşları
package com.onur.tvgonder.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.onur.tvgonder.BtPad
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun Context.act(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun GamepadDialog(full: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val status by BtPad.status.collectAsState()
    val ready by BtPad.ready.collectAsState()
    val connected by BtPad.connected.collectAsState()
    var tab by remember { mutableIntStateOf(3) }
    var devices by remember { mutableStateOf(BtPad.bonded()) }

    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun granted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) { BtPad.start(ctx); devices = BtPad.bonded() } else BtPad.status.value = "Bluetooth izni verilmedi"
    }
    val discover = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { devices = BtPad.bonded() }

    if (!full) DisposableEffect(Unit) {
        val a = ctx.act()
        val old = a?.requestedOrientation
        a?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (old != null) a?.requestedOrientation = old }
    }
    LaunchedEffect(Unit) { if (granted()) { BtPad.start(ctx); devices = BtPad.bonded() } else ask.launch(perms) }
    LaunchedEffect(ready) { if (ready) devices = BtPad.bonded() }

    MaybeDialog(full, onClose) {
        Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Üst çubuk
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎮", fontSize = 22.sp)
                Text(status, Modifier.weight(1f), color = if (connected != null) Accent else TextMuted, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                PadTab("Kumanda", tab == 3) { tab = 3 }
                PadTab("Kol", tab == 0) { tab = 0 }
                PadTab("Fare", tab == 1) { tab = 1 }
                PadTab("Bağlantı", tab == 2) { tab = 2 }
                TextButton(onClick = { BtPad.stop(); onClose() }) { Text("Kapat") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    3 -> PadRemote()
                    0 -> PadControls()
                    1 -> PadMouse()
                    else -> PadConnect(ready, devices,
                        onConnect = { BtPad.pairAndConnect(it) },
                        onScan = { if (granted()) BtPad.scan(ctx) else ask.launch(perms) },
                        onPair = { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                        onVisible = {
                            runCatching {
                                discover.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
                            }
                        },
                        onRetry = { if (granted()) { BtPad.stop(); BtPad.start(ctx); devices = BtPad.bonded() } else ask.launch(perms) },
                    )
                }
                if (connected == null && tab != 2) {
                    Text(
                        "Henüz TV'ye bağlı değil → \"Bağlantı\" sekmesi",
                        Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(10.dp)).background(PanelHi)
                            .clickable { tab = 2 }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Danger, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Tam ekran sayfada doğrudan, değilse pencere içinde gösterir. */
@Composable
fun MaybeDialog(full: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    if (full) {
        androidx.activity.compose.BackHandler { onClose() }
        Box(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.displayCutout)) { content() }
    } else Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
}

@Composable
private fun PadTab(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else PanelHi).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (on) Ink else TextMain, style = MaterialTheme.typography.labelLarge,
    )
}

// ---------------------------------------------------------------- bağlantı

@Composable
private fun PadConnect(
    ready: Boolean, devices: List<android.bluetooth.BluetoothDevice>,
    onConnect: (android.bluetooth.BluetoothDevice) -> Unit, onScan: () -> Unit, onPair: () -> Unit, onVisible: () -> Unit, onRetry: () -> Unit,
) {
    val found by BtPad.found.collectAsState()
    val scanning by BtPad.scanning.collectAsState()
    Row(Modifier.fillMaxSize().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ready) TextButton(onClick = onRetry) { Text("↻ Yeniden başlat") }
            androidx.compose.material3.Button(onClick = onScan, enabled = !scanning) {
                Text(if (scanning) "Aranıyor…" else "🔍 Yakındaki cihazları ara (TV'yi bul)")
            }
            val fresh = found.filter { f -> devices.none { it.address == f.address } }
            if (fresh.isNotEmpty()) {
                Text("Bulunanlar – TV'ne dokun (eşleşip bağlanır):", color = TextMain)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(fresh, key = { "f" + it.address }) { d ->
                        Column(
                            Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.3f)).clickable { onConnect(d) }.padding(12.dp),
                        ) {
                            Text("🆕", fontSize = 22.sp)
                            Text(runCatching { d.name }.getOrNull() ?: d.address, color = TextMain, maxLines = 2)
                        }
                    }
                }
            }
            Text("Eşleşmiş cihazlar – TV'ne dokun:", color = TextMain)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.address }) { d ->
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(PanelHi).clickable { onConnect(d) }.padding(12.dp),
                    ) {
                        Text("📺", fontSize = 22.sp)
                        Text(runCatching { d.name }.getOrNull() ?: d.address, color = TextMain, maxLines = 2)
                    }
                }
            }
            if (devices.isEmpty()) Text("Henüz eşleşmiş cihaz yok. Yukarıdan ara.", color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPair) { Text("📶 Bluetooth ayarları") }
                TextButton(onClick = onVisible) { Text("👁 Telefonu görünür yap") }
            }
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("İlk kez (bir kere):", color = TextMain, style = MaterialTheme.typography.titleSmall)
            listOf(
                "1. TV: Ayarlar → Kumandalar ve aksesuarlar → Aksesuar ekle. Bu ekranda kalsın (TV görünür olur).",
                "2. Telefon: \"🔍 Yakındaki cihazları ara\" → çıkan listede TV'nin adına dokun.",
                "3. TV'de ve telefonda eşleşme kodu çıkarsa ikisinde de Eşle / Tamam de.",
                "4. Üstte \"Bağlı\" yazınca Kumanda sekmesine geç.",
                "TV listede çıkmazsa: TV'de Ayarlar → Bluetooth (ya da Kumandalar ve aksesuarlar) ekranını açık tut, tekrar ara.",
                "Sonra her seferinde sadece 3. adım. TV gerçek oyun kolu + fare görür; fare eklentisi gerekmez.",
            ).forEach { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ---------------------------------------------------------------- kumanda (TV'nin kendi kumandası gibi, Bluetooth ile anında)

@Composable
private fun PadRemote() {
    @Composable
    fun Arrow(label: String, h: Int, m: Modifier) {
        var down by remember { mutableStateOf(false) }
        Box(
            m.clip(RoundedCornerShape(18.dp)).background(if (down) Accent else PanelHi)
                .pointerInput(h) {
                    detectTapGestures(onPress = {
                        down = true; BtPad.pressHat(h)
                        tryAwaitRelease()   // basılı tutunca TV kendisi tekrarlar
                        down = false; if (BtPad.hat == h) BtPad.pressHat(8)
                    })
                },
            contentAlignment = Alignment.Center,
        ) { Text(label, fontSize = 30.sp, color = if (down) Ink else TextMain) }
    }
    @Composable
    fun Key(label: String, usage: Int, m: Modifier) {
        Box(
            m.clip(RoundedCornerShape(16.dp)).background(PanelHi).clickable { BtPad.key(usage) },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = TextMain, maxLines = 1, fontSize = 18.sp) }
    }
    Row(Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        // Yön tuşları + OK
        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = Modifier.size(84.dp)
            Arrow("▲", 0, b)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Arrow("◀", 6, b)
                var ok by remember { mutableStateOf(false) }
                Box(
                    b.clip(CircleShape).background(if (ok) Accent else Color(0xFF2E6F8E))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                ok = true; BtPad.press(BtPad.A, true)
                                tryAwaitRelease()
                                ok = false; BtPad.press(BtPad.A, false)
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("OK", fontSize = 24.sp, color = Color.White) }
                Arrow("▶", 2, b)
            }
            Arrow("▼", 4, b)
        }
        // Diğer tuşlar
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val k = Modifier.weight(1f).height(60.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Key("⟵ Geri", BtPad.C_BACK, k); Key("⌂ Ana ekran", BtPad.C_HOME, k) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Key("☰ Menü", BtPad.C_MENU, k); Key("⏯", BtPad.C_PLAY, k) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Key("🔉", BtPad.C_VOL_DOWN, k); Key("🔇", BtPad.C_MUTE, k); Key("🔊", BtPad.C_VOL_UP, k) }
        }
    }
}

// ---------------------------------------------------------------- oyun kolu

@Composable
private fun PadControls() {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadBtn("L2", BtPad.L2, 74.dp, 40.dp); PadBtn("L1", BtPad.L1, 74.dp, 40.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyBtn("⟵", BtPad.C_BACK); KeyBtn("⌂", BtPad.C_HOME)
                PadBtn("Select", BtPad.SELECT, 70.dp, 40.dp); PadBtn("Start", BtPad.START, 70.dp, 40.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadBtn("R1", BtPad.R1, 74.dp, 40.dp); PadBtn("R2", BtPad.R2, 74.dp, 40.dp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DPad()
            Stick(left = true)
            Stick(left = false)
            Abxy()
        }
    }
}

@Composable
private fun PadBtn(label: String, btn: Int, w: Dp, h: Dp = w, round: Boolean = false, color: Color = PanelHi) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.size(w, h).clip(if (round) CircleShape else RoundedCornerShape(12.dp))
            .background(if (down) Accent else color)
            .pointerInput(btn) {
                detectTapGestures(onPress = {
                    down = true; BtPad.press(btn, true)
                    tryAwaitRelease()
                    down = false; BtPad.press(btn, false)
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (down) Ink else TextMain, fontSize = 16.sp) }
}

@Composable
private fun KeyBtn(label: String, usage: Int) {
    Box(
        Modifier.size(48.dp, 40.dp).clip(RoundedCornerShape(12.dp)).background(Panel).clickable { BtPad.key(usage) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = TextMain, fontSize = 18.sp) }
}

@Composable
private fun DPad() {
    @Composable
    fun D(label: String, h: Int) {
        var down by remember { mutableStateOf(false) }
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(if (down) Accent else PanelHi)
                .pointerInput(h) {
                    detectTapGestures(onPress = {
                        down = true; BtPad.pressHat(h)
                        tryAwaitRelease()
                        down = false; if (BtPad.hat == h) BtPad.pressHat(8)
                    })
                },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (down) Ink else TextMain, fontSize = 20.sp) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        D("▲", 0)
        Row { D("◀", 6); Spacer(Modifier.size(56.dp)); D("▶", 2) }
        D("▼", 4)
    }
}

@Composable
private fun Abxy() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadBtn("Y", BtPad.Y, 58.dp, round = true, color = Color(0xFF8A7A20))
        Row {
            PadBtn("X", BtPad.X, 58.dp, round = true, color = Color(0xFF24508A))
            Spacer(Modifier.size(54.dp))
            PadBtn("B", BtPad.B, 58.dp, round = true, color = Color(0xFF8A2830))
        }
        PadBtn("A", BtPad.A, 58.dp, round = true, color = Color(0xFF247A3A))
    }
}

/** Analog çubuk: parmağı merkezden uzaklaştırdıkça -127..127. */
@Composable
private fun Stick(left: Boolean) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val sizeDp = 150.dp
    Box(
        Modifier.size(sizeDp).clip(CircleShape).background(Panel).border(2.dp, PanelHi, CircleShape)
            .pointerInput(left) {
                val r = size.width / 2f
                val center = Offset(r, r)
                fun apply(p: Offset) {
                    var d = p - center
                    val len = sqrt(d.x * d.x + d.y * d.y)
                    if (len > r) d = d * (r / len)
                    knob = d
                    val x = (d.x / r * 127).roundToInt(); val y = (d.y / r * 127).roundToInt()
                    if (left) { BtPad.lx = x; BtPad.ly = y } else { BtPad.rx = x; BtPad.ry = y }
                    BtPad.sendPad()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    apply(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        apply(c.position)
                    }
                    knob = Offset.Zero
                    if (left) { BtPad.lx = 0; BtPad.ly = 0 } else { BtPad.rx = 0; BtPad.ry = 0 }
                    BtPad.sendPad()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(60.dp).clip(CircleShape).background(PanelHi),
            contentAlignment = Alignment.Center,
        ) { Text(if (left) "L" else "R", color = TextMuted) }
    }
}

// ---------------------------------------------------------------- fare

@Composable
private fun PadMouse() {
    var rx by remember { mutableStateOf(0f) }
    var ry by remember { mutableStateOf(0f) }
    var acc by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(PanelHi)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { BtPad.click() },
                            onLongPress = { BtPad.mouseButton(1, true); BtPad.mouseButton(1, false) },
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            rx += d.x * 1.6f; ry += d.y * 1.6f
                            val ix = rx.toInt(); val iy = ry.toInt()
                            if (ix != 0 || iy != 0) { BtPad.mouse(ix, iy); rx -= ix; ry -= iy }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Kaydır: imleç · Dokun: tıkla · Basılı tut: sağ tık", color = TextMuted, textAlign = TextAlign.Center)
            }
            Box(
                Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Panel)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { ch, dy ->
                            ch.consume()
                            acc += dy
                            if (kotlin.math.abs(acc) > 24) { BtPad.mouse(0, 0, if (acc > 0) -1 else 1); acc = 0f }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("↕", fontSize = 24.sp, color = TextMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "⟵ Geri" to BtPad.C_BACK, "⌂ Ana ekran" to BtPad.C_HOME, "☰ Menü" to BtPad.C_MENU,
                "⏯" to BtPad.C_PLAY, "🔉" to BtPad.C_VOL_DOWN, "🔇" to BtPad.C_MUTE, "🔊" to BtPad.C_VOL_UP,
            ).forEach { (l, u) ->
                Box(
                    Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(PanelHi).clickable { BtPad.key(u) },
                    contentAlignment = Alignment.Center,
                ) { Text(l, color = TextMain, maxLines = 1) }
            }
        }
    }
}
