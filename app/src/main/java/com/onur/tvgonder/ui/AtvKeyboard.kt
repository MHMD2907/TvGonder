// v0.41 – 2026-10-27: TV'ye yazı: bağlı değilken de yazılabilir, daha önce eşleşilen TV'ye kendiliğinden bağlanır; tüm kumanda ekranlarında ortak
package com.onur.tvgonder.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.onur.tvgonder.AtvRemote

/**
 * TV'de odaklı yazı alanına (YouTube arama, tarayıcı adres çubuğu, giriş ekranları…) telefondan yazı gönderir.
 * TV kumandası bağlı değilse soluk görünür ve ipucu gösterir.
 */
@Composable
fun AtvKeyboard(modifier: Modifier = Modifier) {
    val st by AtvRemote.state.collectAsState()
    val on = st == AtvRemote.State.Connected
    var t by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val known = remember { ctx.getSharedPreferences("atv", 0).getString("host", null) }
    // Bağlı değilse daha önce eşleşilen TV'ye kendiliğinden bağlan; yazmak için beklemek gerekmez
    LaunchedEffect(Unit) { if (!on && known != null) AtvRemote.connect(ctx, known) }
    fun send() {
        if (t.isNotEmpty()) { AtvRemote.text(t); t = "" }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = t, onValueChange = { t = it }, enabled = known != null || on, singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("⌨ TV'ye yaz") },
                placeholder = { Text("Arama, adres, şifre…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = { send() }, enabled = (on || known != null) && t.isNotEmpty(), modifier = Modifier.height(56.dp)) { Text("Gönder") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { AtvRemote.key(KeyEvent.KEYCODE_DEL) }, enabled = on, modifier = Modifier.weight(1f)) { Text("⌫ Sil") }
            OutlinedButton(onClick = { AtvRemote.key(KeyEvent.KEYCODE_SPACE) }, enabled = on, modifier = Modifier.weight(1f)) { Text("␣ Boşluk") }
            Button(onClick = { AtvRemote.key(KeyEvent.KEYCODE_ENTER) }, enabled = on, modifier = Modifier.weight(1f)) { Text("↵ Ara / Git") }
        }
        Text(
            when {
                on -> "Önce TV'de yazı kutusunu seç (ör. YouTube'da 🔍), sonra burada yaz ve Gönder'e bas."
                known != null -> "TV'ye bağlanıyor… Yazabilirsin; Gönder'e basınca bağlanır bağlanmaz gider."
                else -> "Yazmak için önce \"TV kumandası (hızlı)\" ekranında TV ile bir kez eşleş."
            },
            color = Color(0xFF9AA4B2), style = MaterialTheme.typography.bodySmall,
        )
    }
}
