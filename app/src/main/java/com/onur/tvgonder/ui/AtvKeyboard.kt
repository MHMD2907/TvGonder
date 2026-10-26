// v0.37 – 2026-10-26: Telefondan TV'ye yazı yazma (Google TV kumanda hizmeti üzerinden) – tüm kumanda ekranlarında ortak
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
    fun send() {
        if (t.isNotEmpty()) { AtvRemote.text(t); t = "" }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = t, onValueChange = { t = it }, enabled = on, singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("⌨ TV'ye yaz") },
                placeholder = { Text("Arama, adres, şifre…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = { send() }, enabled = on && t.isNotEmpty(), modifier = Modifier.height(56.dp)) { Text("Gönder") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { AtvRemote.key(KeyEvent.KEYCODE_DEL) }, enabled = on, modifier = Modifier.weight(1f)) { Text("⌫ Sil") }
            OutlinedButton(onClick = { AtvRemote.key(KeyEvent.KEYCODE_SPACE) }, enabled = on, modifier = Modifier.weight(1f)) { Text("␣ Boşluk") }
            Button(onClick = { AtvRemote.key(KeyEvent.KEYCODE_ENTER) }, enabled = on, modifier = Modifier.weight(1f)) { Text("↵ Ara / Git") }
        }
        Text(
            if (on) "Önce TV'de yazı kutusunu seçin (ör. YouTube'da 🔍), sonra burada yazıp Gönder'e basın."
            else "Yazmak için TV kumandası (hızlı) ile TV'ye bağlanın.",
            color = Color(0xFF9AA4B2), style = MaterialTheme.typography.bodySmall,
        )
    }
}
