// v0.51 – 2026-11-13: TV'ye yazı: TV yazı alanını açınca telefonda klavye kendiliğinden açılır, yazdıkça TV'de görünür (Türkçe dahil); değilse harf harf; tüm kumanda ekranlarında ortak
package com.onur.tvgonder.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.onur.tvgonder.AtvRemote

/**
 * TV'deki yazı alanına telefondan yazma.
 * - Canlı mod: TV'de bir yazı kutusu seçilince TV "Mobil cihazınızdaki klavyeyi kullanın" der; telefonda klavye
 *   kendiliğinden açılır ve yazdığın her harf anında TV'deki kutuya gider (Türkçe harfler dahil).
 * - Yedek mod: TV kutuyu bildirmezse yazı "Gönder" ile harf harf tuş olarak gider.
 */
@Composable
fun AtvKeyboard(modifier: Modifier = Modifier) {
    val st by AtvRemote.state.collectAsState()
    val on = st == AtvRemote.State.Connected
    val imeOpen by AtvRemote.imeActive.collectAsState()
    val tvText by AtvRemote.imeText.collectAsState()
    var t by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val known = remember { ctx.getSharedPreferences("atv", 0).getString("host", null) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    // Bağlı değilse daha önce eşleşilen TV'ye kendiliğinden bağlan
    LaunchedEffect(Unit) { if (!on && known != null) AtvRemote.connect(ctx, known) }
    // TV yazı kutusunu açınca: TV'deki mevcut yazıyı al, telefonda klavyeyi aç
    LaunchedEffect(imeOpen) {
        if (imeOpen) {
            t = tvText
            runCatching { focus.requestFocus(); kb?.show() }
        }
    }
    val live = imeOpen && AtvRemote.imeReady()
    fun send() {
        if (live) AtvRemote.key(KeyEvent.KEYCODE_ENTER)
        else if (t.isNotEmpty()) { AtvRemote.text(t); t = "" }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = t,
                onValueChange = { v -> t = v; if (live) AtvRemote.text(v) },
                enabled = known != null || on, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                label = { Text(if (live) "⌨ TV'deki kutuya yazılıyor" else "⌨ TV'ye yaz") },
                placeholder = { Text("Arama, adres, şifre…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = if (live) ImeAction.Search else ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }, onSearch = { send() }),
            )
            Button(onClick = { send() }, enabled = (on || known != null) && (live || t.isNotEmpty()), modifier = Modifier.height(56.dp)) {
                Text(if (live) "Ara ↵" else "Gönder")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (live) { t = t.dropLast(1); AtvRemote.text(t) } else AtvRemote.key(KeyEvent.KEYCODE_DEL)
            }, enabled = on, modifier = Modifier.weight(1f)) { Text("⌫ Sil") }
            OutlinedButton(onClick = {
                if (live) { t = ""; AtvRemote.text("") } else AtvRemote.key(KeyEvent.KEYCODE_SPACE)
            }, enabled = on, modifier = Modifier.weight(1f)) { Text(if (live) "✕ Temizle" else "␣ Boşluk") }
            Button(onClick = { AtvRemote.key(KeyEvent.KEYCODE_ENTER) }, enabled = on, modifier = Modifier.weight(1f)) { Text("↵ Ara / Git") }
        }
        Text(
            when {
                live -> "TV'deki yazı kutusu açık: yazdıkça TV'de görünür. Bitince Ara'ya bas."
                on -> "TV'de bir yazı kutusu seç (ör. YouTube'da 🔍). TV \"Mobil cihazınızdaki klavyeyi kullanın\" deyince buradaki klavye kendiliğinden açılır."
                known != null -> "TV'ye bağlanıyor…"
                else -> "Yazmak için önce \"TV kumandası (hızlı)\" ekranında TV ile bir kez eşleş."
            },
            color = Color(0xFF9AA4B2), style = MaterialTheme.typography.bodySmall,
        )
    }
}
