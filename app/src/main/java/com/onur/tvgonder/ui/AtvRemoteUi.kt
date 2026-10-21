// v0.32 – 2026-10-21: TV kumandası ekranı: güncel TV adresi önce; eşleşme, yön/OK, ses, kanal, rakamlar, güç, medya
package com.onur.tvgonder.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onur.tvgonder.AtvRemote
import com.onur.tvgonder.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun AtvRemoteScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val st by AtvRemote.state.collectAsState()
    val msg by AtvRemote.message.collectAsState()
    // TV seçimi: eşleşmiş TV Gönder cihazları + elle IP
    val targets = remember { store.targets().filter { it.host.isNotBlank() } }
    var host by remember {
        // TV'nin adresi değişirse (modem yeni IP verirse) TV Gönder'in bulduğu güncel adres önce gelir
        mutableStateOf(store.tvHost ?: ctx.getSharedPreferences("atv", 0).getString("host", null) ?: targets.firstOrNull()?.host ?: "")
    }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    LaunchedEffect(host) { if (host.isNotBlank()) AtvRemote.connect(ctx, host) }

    Column(
        Modifier.fillMaxSize().background(Ink).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📺  TV kumandası", style = MaterialTheme.typography.titleMedium, color = TextMain)
                Text(
                    when (st) {
                        AtvRemote.State.Connected -> "Bağlı ✓  ·  $host"
                        AtvRemote.State.Connecting -> "Bağlanıyor…"
                        else -> msg.ifBlank { "Bağlı değil" }
                    },
                    color = if (st == AtvRemote.State.Connected) Accent else TextMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClose) { Text("Kapat") }
        }

        // TV seçimi
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { t ->
                val on = t.host == host
                Text(
                    t.name, Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else PanelHi)
                        .clickable { host = t.host }.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (on) Ink else TextMain,
                )
            }
            Text("IP yaz", Modifier.clip(RoundedCornerShape(50)).background(Panel).clickable { manual = !manual }.padding(horizontal = 14.dp, vertical = 8.dp), color = Accent)
        }
        if (manual) {
            var ip by remember { mutableStateOf(host) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ip, { ip = it }, Modifier.weight(1f), label = { Text("TV'nin IP adresi") }, singleLine = true)
                TextButton(onClick = { host = ip.trim(); manual = false }) { Text("Bağlan") }
            }
        }

        // Eşleşme
        if (st == AtvRemote.State.NeedPairing || st == AtvRemote.State.Pairing || (st == AtvRemote.State.Error && !AtvRemote.isPaired(ctx, host))) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Bir kerelik eşleşme", color = TextMain, style = MaterialTheme.typography.titleSmall)
                Text("1. TV açık olsun. 2. \"Eşleşmeyi başlat\"a bas → TV ekranında 6 haneli bir kod çıkar. 3. Kodu aşağıya yaz → Eşleştir.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                if (msg.isNotBlank()) Text(msg, color = if (st == AtvRemote.State.Error) Danger else Accent, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { AtvRemote.startPairing(ctx, host) }, enabled = host.isNotBlank()) { Text("Eşleşmeyi başlat") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, Modifier.weight(1f),
                        label = { Text("TV'deki kod") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    Button(onClick = { AtvRemote.finishPairing(ctx, code) }, enabled = code.length == 6) { Text("Eşleştir") }
                }
            }
        } else if (st == AtvRemote.State.Error) {
            Text(msg, color = Danger, modifier = Modifier.clickable { AtvRemote.connect(ctx, host) })
            OutlinedButton(onClick = { AtvRemote.connect(ctx, host) }) { Text("↻ Yeniden bağlan") }
        }

        val on = st == AtvRemote.State.Connected
        @Composable
        fun K(label: String, code: Int, m: Modifier, color: Color = PanelHi, repeat: Boolean = false) = AtvKey(label, code, m, color, repeat, on)

        // Üst sıra
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            K("⏻", KeyEvent.KEYCODE_POWER, Modifier.weight(1f).height(54.dp), Color(0xFF8A2830))
            K("⇄ Kaynak", KeyEvent.KEYCODE_TV_INPUT, Modifier.weight(1f).height(54.dp))
            K("⚙", KeyEvent.KEYCODE_SETTINGS, Modifier.weight(1f).height(54.dp))
            K("🔇", KeyEvent.KEYCODE_VOLUME_MUTE, Modifier.weight(1f).height(54.dp))
        }
        // Yön + OK
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = Modifier.size(78.dp)
            K("▲", KeyEvent.KEYCODE_DPAD_UP, b, repeat = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                K("◀", KeyEvent.KEYCODE_DPAD_LEFT, b, repeat = true)
                K("OK", KeyEvent.KEYCODE_DPAD_CENTER, b.clip(CircleShape), Color(0xFF2E6F8E))
                K("▶", KeyEvent.KEYCODE_DPAD_RIGHT, b, repeat = true)
            }
            K("▼", KeyEvent.KEYCODE_DPAD_DOWN, b, repeat = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val m = Modifier.weight(1f).height(54.dp)
            K("⟵ Geri", KeyEvent.KEYCODE_BACK, m); K("⌂ Ana ekran", KeyEvent.KEYCODE_HOME, m); K("☰", KeyEvent.KEYCODE_MENU, m)
        }
        // Ses / kanal
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                K("🔊 +", KeyEvent.KEYCODE_VOLUME_UP, Modifier.fillMaxWidth().height(56.dp), repeat = true)
                Text("SES", Modifier.fillMaxWidth(), color = TextMuted, textAlign = TextAlign.Center)
                K("🔉 −", KeyEvent.KEYCODE_VOLUME_DOWN, Modifier.fillMaxWidth().height(56.dp), repeat = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                K("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Modifier.fillMaxWidth().height(56.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    K("⏪", KeyEvent.KEYCODE_MEDIA_REWIND, Modifier.weight(1f).height(56.dp))
                    K("⏩", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, Modifier.weight(1f).height(56.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                K("P +", KeyEvent.KEYCODE_CHANNEL_UP, Modifier.fillMaxWidth().height(56.dp), repeat = true)
                Text("KANAL", Modifier.fillMaxWidth(), color = TextMuted, textAlign = TextAlign.Center)
                K("P −", KeyEvent.KEYCODE_CHANNEL_DOWN, Modifier.fillMaxWidth().height(56.dp), repeat = true)
            }
        }
        // Rakamlar
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    when (d) {
                        -1 -> K("ℹ", KeyEvent.KEYCODE_INFO, Modifier.weight(1f).height(48.dp))
                        -2 -> K("📋", KeyEvent.KEYCODE_GUIDE, Modifier.weight(1f).height(48.dp))
                        else -> K("$d", KeyEvent.KEYCODE_0 + d, Modifier.weight(1f).height(48.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = Modifier.weight(1f).height(32.dp)
            K("", KeyEvent.KEYCODE_PROG_RED, m, Color(0xFFB3262E)); K("", KeyEvent.KEYCODE_PROG_GREEN, m, Color(0xFF2E8B3A))
            K("", KeyEvent.KEYCODE_PROG_YELLOW, m, Color(0xFFC9A227)); K("", KeyEvent.KEYCODE_PROG_BLUE, m, Color(0xFF2B5DB8))
        }
        Text(
            "Bu kumanda TV'nin kendi Wi‑Fi kumanda hizmetini kullanır (Google TV uygulamasındaki gibi); TV Gönder'in TV'de açık olması gerekmez. Telefon ve TV aynı Wi‑Fi'da olmalı.",
            color = TextMuted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AtvKey(label: String, code: Int, m: Modifier, color: Color, repeat: Boolean, enabled: Boolean) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    if (repeat) LaunchedEffect(down) {
        if (!down) return@LaunchedEffect
        delay(450)
        while (isActive && down) { AtvRemote.key(code); delay(120) }
    }
    Box(
        m.clip(RoundedCornerShape(16.dp)).background(if (down) Accent else if (enabled) color else color.copy(alpha = 0.35f))
            .pointerInput(code, enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    down = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    AtvRemote.key(code)
                    tryAwaitRelease()
                    down = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 18.sp, maxLines = 1) }
}
