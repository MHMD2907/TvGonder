// v0.28 – 2026-10-19: kızılötesi kumanda ekranı: kayıtlı cihazlar, kumanda düzeni (açma, ses, kanal, yön, rakam, renk), cihaz ekleme ve kod deneme
package com.onur.tvgonder.ui

import android.content.Context
import android.hardware.ConsumerIrManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onur.tvgonder.ir.IrDb
import com.onur.tvgonder.ir.IrSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

object Ir {
    private val exec = Executors.newSingleThreadExecutor()

    fun has(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
    }.getOrDefault(false)

    fun send(c: Context, s: IrSignal) {
        val m = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return
        exec.execute { runCatching { m.transmit(s.freq, s.pattern) } }
    }

    private fun norm(s: String) = s.trim().lowercase().replace(' ', '_')

    /** Kumanda düğmesi → dosyalardaki farklı adları. */
    private val names: Map<String, List<String>> = mapOf(
        "power" to listOf("power", "on_off", "power_toggle", "standby", "pwr", "power_on", "on/off"),
        "vol_up" to listOf("vol_up", "volume_up", "vol+", "volup", "vol_+", "volume+"),
        "vol_dn" to listOf("vol_dn", "vol_down", "volume_down", "vol-", "voldown", "vol_-", "volume-"),
        "mute" to listOf("mute"),
        "ch_up" to listOf("ch_next", "ch_up", "channel_up", "ch+", "p+", "chan_up", "prog_up", "page_up"),
        "ch_dn" to listOf("ch_prev", "ch_dn", "ch_down", "channel_down", "ch-", "p-", "chan_down", "prog_down", "page_down"),
        "up" to listOf("up", "arrow_up", "cursor_up"),
        "down" to listOf("down", "arrow_down", "cursor_down"),
        "left" to listOf("left", "arrow_left", "cursor_left"),
        "right" to listOf("right", "arrow_right", "cursor_right"),
        "ok" to listOf("ok", "enter", "select", "center", "confirm"),
        "back" to listOf("back", "return", "ret"),
        "exit" to listOf("exit"),
        "home" to listOf("home", "smart", "smart_hub", "apps"),
        "menu" to listOf("menu", "setup", "settings"),
        "source" to listOf("source", "input", "av", "tv_av", "tv/av", "tv_video", "tv/radio"),
        "info" to listOf("info", "display"),
        "guide" to listOf("guide", "epg"),
        "red" to listOf("red"), "green" to listOf("green"), "yellow" to listOf("yellow"), "blue" to listOf("blue"),
        "play" to listOf("play", "play_pause", "play/pause"),
        "pause" to listOf("pause", "play_pause", "play/pause"),
        "stop" to listOf("stop"),
        "rew" to listOf("rewind", "rew", "fast_back"),
        "ff" to listOf("fast_forward", "ff", "forward", "fast_fwd", "fwd"),
    ) + (0..9).associate { d -> "$d" to listOf("$d", "num_$d", "key_$d", "digit_$d", "btn_$d") }

    fun find(sigs: List<IrSignal>, key: String): IrSignal? {
        val wanted = names[key] ?: listOf(key)
        for (w in wanted) sigs.firstOrNull { norm(it.name) == w }?.let { return it }
        return null
    }

    /** Kumanda düzeninde yer almayan diğer tuşlar. */
    fun extras(sigs: List<IrSignal>): List<IrSignal> {
        val used = names.keys.mapNotNull { find(sigs, it) }.toSet()
        return sigs.filter { it !in used }.distinctBy { norm(it.name) }
    }
}

// ---------------------------------------------------------------- ana ekran

private sealed interface IrNav {
    data object Remote : IrNav
    data object Categories : IrNav
    data class Brands(val cat: String) : IrNav
    data class Models(val cat: String, val brand: String) : IrNav
    data class Test(val list: List<IrDb.Model>, val start: Int, val title: String) : IrNav
}

@Composable
fun IrRemoteScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var nav by remember { mutableStateOf<IrNav>(IrNav.Remote) }
    var devices by remember { mutableStateOf(IrDb.saved(ctx)) }
    var current by remember { mutableStateOf(IrDb.current(ctx) ?: devices.firstOrNull()?.name) }
    LaunchedEffect(Unit) { if (devices.isEmpty()) nav = IrNav.Categories }

    BackHandler {
        nav = when (val n = nav) {
            IrNav.Remote -> { onClose(); IrNav.Remote }
            IrNav.Categories -> IrNav.Remote
            is IrNav.Brands -> IrNav.Categories
            is IrNav.Models -> IrNav.Brands(n.cat)
            is IrNav.Test -> IrNav.Categories
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📡  Kızılötesi kumanda", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMain)
            if (nav != IrNav.Remote && devices.isNotEmpty()) TextButton(onClick = { nav = IrNav.Remote }) { Text("Kumanda") }
            TextButton(onClick = onClose) { Text("Kapat") }
        }
        if (!Ir.has(ctx)) {
            Text("Bu telefonda kızılötesi verici yok.", color = Danger, modifier = Modifier.padding(16.dp))
            return@Column
        }
        when (val n = nav) {
            IrNav.Remote -> IrRemotePad(
                devices, current,
                onSelect = { current = it; IrDb.current(ctx, it) },
                onAdd = { nav = IrNav.Categories },
                onDelete = { name -> IrDb.remove(ctx, name); devices = IrDb.saved(ctx); if (current == name) current = devices.firstOrNull()?.name },
            )
            IrNav.Categories -> IrPickList(
                "Ne eklemek istiyorsun?",
                IrDb.categories.values.distinct(),
            ) { nav = IrNav.Brands(it) }
            is IrNav.Brands -> {
                val all = remember { IrDb.index(ctx).second.filter { it.category == n.cat } }
                IrPickList(
                    "Marka seç (${all.map { it.brand }.distinct().size})",
                    all.map { it.brand }.distinct().sortedBy { it.lowercase() },
                    footer = "Markan listede yoksa (ör. Nextstar, Dijitsu) → \"Tümünü sırayla dene\": bu türdeki bütün kodlar tek tek denenir.",
                    extraFirst = "🔎 Tümünü sırayla dene (${all.size} kod)",
                    onExtra = { nav = IrNav.Test(all, 0, n.cat) },
                ) { nav = IrNav.Models(n.cat, it) }
            }
            is IrNav.Models -> {
                val list = remember { IrDb.index(ctx).second.filter { it.category == n.cat && it.brand == n.brand } }
                if (list.size == 1) LaunchedEffect(Unit) { nav = IrNav.Test(list, 0, n.brand) }
                IrPickList("${n.brand} – model seç (bilmiyorsan ilkinden başla)", list.map { it.model }) { m ->
                    nav = IrNav.Test(list, list.indexOfFirst { it.model == m }.coerceAtLeast(0), n.brand)
                }
            }
            is IrNav.Test -> IrTest(n) { name, path ->
                IrDb.add(ctx, IrDb.Saved(name, path))
                devices = IrDb.saved(ctx); current = name
                nav = IrNav.Remote
            }
        }
    }
}

@Composable
private fun IrPickList(
    title: String, items: List<String>, footer: String? = null,
    extraFirst: String? = null, onExtra: () -> Unit = {}, onPick: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextMuted)
        if (items.size > 12) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ara") }, singleLine = true)
        footer?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (extraFirst != null) item {
                Text(
                    extraFirst, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.25f))
                        .clickable(onClick = onExtra).padding(14.dp),
                    color = TextMain,
                )
            }
            items(items.filter { q.isBlank() || it.contains(q, ignoreCase = true) }) { s ->
                Text(
                    s, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable { onPick(s) }.padding(14.dp),
                    color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- kod deneme

@Composable
private fun IrTest(n: IrNav.Test, onSave: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var i by remember(n) { mutableIntStateOf(n.start) }
    val m = n.list[i]
    var sigs by remember { mutableStateOf<List<IrSignal>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var name by remember(m) { mutableStateOf(if (n.list.size > 1 && n.list.map { it.brand }.distinct().size > 1) m.brand else n.title) }
    LaunchedEffect(m) {
        sigs = null; err = null
        runCatching { withContext(Dispatchers.IO) { IrDb.load(ctx, m.path) } }
            .onSuccess { sigs = it; if (it.isEmpty()) err = "Bu dosyada kullanılabilir kod yok, sonrakine geç." }
            .onFailure { err = it.message }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Kod ${i + 1} / ${n.list.size}", color = TextMuted)
        Text("${m.brand} · ${m.model}", color = TextMain, style = MaterialTheme.typography.titleMedium)
        Text("Telefonun üst kenarını cihaza doğrult, aşağıdaki tuşlara bas. Cihaz tepki verirse kaydet.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        err?.let { Text(it, color = Danger) }
        val s = sigs
        if (s == null && err == null) Text("Kodlar indiriliyor…", color = TextMuted)
        if (s != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("power" to "⏻ Aç/Kapat", "vol_up" to "🔊 Ses +", "mute" to "🔇", "ch_up" to "Kanal +", "ok" to "OK").forEach { (k, l) ->
                    val sig = Ir.find(s, k)
                    if (sig != null) Button(onClick = { Ir.send(ctx, sig) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l, maxLines = 1) }
                }
            }
            if (listOf("power", "vol_up", "mute", "ch_up", "ok").none { Ir.find(s, it) != null }) {
                Text("Bu kod setinde temel tuş yok; aşağıdan herhangi birini dene:", color = TextMuted)
                s.take(6).forEach { sig -> OutlinedButton(onClick = { Ir.send(ctx, sig) }) { Text(sig.name) } }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Kayıt adı (ör. Salon TV, Uydu)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (i > 0) i-- }, enabled = i > 0) { Text("◀ Önceki") }
            Button(onClick = { onSave(name.ifBlank { m.brand }, m.path) }, enabled = s != null && s.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("✓ Çalıştı, kaydet") }
            OutlinedButton(onClick = { if (i < n.list.size - 1) i++ }, enabled = i < n.list.size - 1) { Text("Sonraki ▶") }
        }
    }
}

// ---------------------------------------------------------------- kumanda düzeni

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IrRemotePad(
    devices: List<IrDb.Saved>, current: String?,
    onSelect: (String) -> Unit, onAdd: () -> Unit, onDelete: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val dev = devices.firstOrNull { it.name == current } ?: devices.firstOrNull()
    var sigs by remember { mutableStateOf<List<IrSignal>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(dev?.path) {
        err = null; sigs = emptyList()
        val p = dev?.path ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { IrDb.load(ctx, p) } }.onSuccess { sigs = it }.onFailure { err = it.message }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Cihazlar
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { d ->
                val on = d.name == dev?.name
                Text(
                    d.name,
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else PanelHi)
                        .combinedClickable(onClick = { onSelect(d.name) }, onLongClick = { askDelete = d.name })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (on) Ink else TextMain,
                )
            }
            Text("+ Cihaz ekle", Modifier.clip(RoundedCornerShape(50)).background(Panel).clickable(onClick = onAdd).padding(horizontal = 16.dp, vertical = 8.dp), color = Accent)
        }
        if (devices.isEmpty()) { Text("Henüz cihaz yok. \"+ Cihaz ekle\" ile TV ya da uydu alıcını ekle.", color = TextMuted); return@Column }
        err?.let { Text(it, color = Danger) }
        Text("Basılı tutunca ses/kanal/yön tuşları tekrarlar · Cihazı silmek için adına uzun bas", color = TextMuted, style = MaterialTheme.typography.bodySmall)

        @Composable
        fun K(key: String, label: String, m: Modifier, color: Color = PanelHi, repeat: Boolean = false) =
            IrKey(Ir.find(sigs, key), label, m, color, repeat)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            K("power", "⏻", Modifier.weight(1f).height(56.dp), Color(0xFF8A2830))
            K("source", "⇄ Kaynak", Modifier.weight(1f).height(56.dp))
            K("mute", "🔇", Modifier.weight(1f).height(56.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                K("vol_up", "🔊 +", Modifier.fillMaxWidth().height(64.dp), repeat = true)
                Text("SES", Modifier.fillMaxWidth(), color = TextMuted, textAlign = TextAlign.Center)
                K("vol_dn", "🔉 −", Modifier.fillMaxWidth().height(64.dp), repeat = true)
            }
            Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                K("up", "▲", Modifier.size(62.dp), repeat = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    K("left", "◀", Modifier.size(62.dp), repeat = true)
                    K("ok", "OK", Modifier.size(62.dp).clip(CircleShape), Color(0xFF2E6F8E))
                    K("right", "▶", Modifier.size(62.dp), repeat = true)
                }
                K("down", "▼", Modifier.size(62.dp), repeat = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                K("ch_up", "P +", Modifier.fillMaxWidth().height(64.dp), repeat = true)
                Text("KANAL", Modifier.fillMaxWidth(), color = TextMuted, textAlign = TextAlign.Center)
                K("ch_dn", "P −", Modifier.fillMaxWidth().height(64.dp), repeat = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = Modifier.weight(1f).height(48.dp)
            K("back", "⟵ Geri", m); K("home", "⌂", m); K("menu", "☰ Menü", m); K("exit", "Çıkış", m)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = Modifier.weight(1f).height(44.dp)
            K("info", "ℹ Bilgi", m); K("guide", "📋 Rehber", m)
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    if (d.isEmpty()) Spacer(Modifier.weight(1f)) else K(d, d, Modifier.weight(1f).height(50.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = Modifier.weight(1f).height(36.dp)
            K("red", "", m, Color(0xFFB3262E)); K("green", "", m, Color(0xFF2E8B3A)); K("yellow", "", m, Color(0xFFC9A227)); K("blue", "", m, Color(0xFF2B5DB8))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = Modifier.weight(1f).height(46.dp)
            K("rew", "⏪", m); K("play", "▶", m); K("pause", "⏸", m); K("stop", "⏹", m); K("ff", "⏩", m)
        }
        val extra = remember(sigs) { Ir.extras(sigs) }
        if (extra.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Diğer tuşları gizle" else "Diğer tuşlar (${extra.size})") }
            if (showAll) extra.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { s -> IrKey(s, s.name.replace('_', ' '), Modifier.weight(1f).height(46.dp), PanelHi, false) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    askDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("$name silinsin mi?") },
            confirmButton = { Button(onClick = { onDelete(name); askDelete = null }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Vazgeç") } },
        )
    }
}

/** Tek tuş: dokununca gönderir; repeat ise basılı tuttukça tekrarlar. Kod yoksa soluk görünür. */
@Composable
private fun IrKey(sig: IrSignal?, label: String, m: Modifier, color: Color, repeat: Boolean) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    if (repeat && sig != null) {
        LaunchedEffect(down) {
            if (!down) return@LaunchedEffect
            delay(450)
            while (isActive && down) { Ir.send(ctx, sig); delay(160) }
        }
    }
    Box(
        m.clip(RoundedCornerShape(16.dp)).background(if (down) Accent else color).alpha(if (sig == null) 0.3f else 1f)
            .pointerInput(sig) {
                detectTapGestures(onPress = {
                    if (sig == null) return@detectTapGestures
                    down = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    Ir.send(ctx, sig)
                    tryAwaitRelease()
                    down = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 18.sp, maxLines = 1) }
}
