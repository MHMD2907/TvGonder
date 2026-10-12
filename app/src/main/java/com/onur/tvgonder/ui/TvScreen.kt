// v0.4 – 2026-10-12: sol üstte M-ONUR logosu
package com.onur.tvgonder.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.onur.tvgonder.Hub
import com.onur.tvgonder.Net
import com.onur.tvgonder.R
import com.onur.tvgonder.ReceiverServer
import com.onur.tvgonder.Store

@Composable
fun TvScreen(store: Store, tree: String?, onPickStorage: () -> Unit) {
    val ctx = LocalContext.current
    val devices by Hub.devices.collectAsState()
    val code by Hub.pairingCode.collectAsState()
    val pending by Hub.pending.collectAsState()
    val running by Hub.running.collectAsState()
    val ip = remember { Net.localIp() }
    val storageName = remember(tree) { tree?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name } }

    Row(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 72.dp, vertical = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        // Sol: durum
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(painterResource(R.drawable.logo_round), "M-ONUR", Modifier.size(120.dp))
            Text("Telefondan gelenleri bekliyor", style = MaterialTheme.typography.headlineLarge, color = TextMain)
            Text(
                when {
                    !running -> "Alıcı başlatılamadı. Uygulamayı kapatıp yeniden aç."
                    ip == null -> "Wi‑Fi bağlantısı yok. TV'yi ağa bağla."
                    else -> "Eşleşmiş telefonlar aynı Wi‑Fi ağından dosya ve link gönderebilir."
                },
                style = MaterialTheme.typography.bodyLarge, color = TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            if (tree == null) {
                Text("Önce dosyaların kaydedileceği USB belleği seç.", style = MaterialTheme.typography.bodyLarge, color = TextMain)
                Button(onClick = onPickStorage) { Text("USB belleği seç") }
            } else {
                Text("Kayıt yeri", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Text(storageName ?: "Seçili klasör", style = MaterialTheme.typography.headlineMedium, color = TextMain)
                OutlinedButton(onClick = onPickStorage) { Text("Değiştir") }
            }
            Spacer(Modifier.weight(1f))
            if (ip != null) Text("$ip:${ReceiverServer.PORT}", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }

        // Sağ: eşleşme / cihazlar
        Surface(Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(28.dp), color = Panel) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (code != null && ip != null) {
                    val bmp = remember(code) {
                        Net.qr("tvgonder://pair?host=$ip&port=${ReceiverServer.PORT}&code=$code")
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Image(bmp.asImageBitmap(), null, Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)))
                        Text("Telefonda uygulamayı aç, TV'yi eşleştir'e dokun ve bu kodu okut.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        Text(code!!, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Accent, letterSpacing = 6.sp)
                        OutlinedButton(onClick = { Hub.pairingCode.value = null }) { Text("Vazgeç") }
                    }
                } else {
                    Text("Eşleşmiş telefonlar", style = MaterialTheme.typography.headlineMedium, color = TextMain)
                    if (devices.isEmpty()) {
                        Text("Henüz eşleşmiş telefon yok.", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
                    }
                    devices.forEach { (token, name) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextMain)
                            TextButton(onClick = { store.removeDevice(token); Hub.devices.value = store.devices() }) {
                                Text("Kaldır", color = Danger)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = tree != null && ip != null,
                        onClick = { Hub.pairingCode.value = (100000..999999).random().toString() },
                    ) { Text("Telefon ekle") }
                }
            }
        }
    }

    pending?.let { req ->
        AlertDialog(
            onDismissRequest = { req.decision.complete(false) },
            containerColor = PanelHi,
            title = { Text("Yeni telefon") },
            text = { Text("${req.name} bu TV'ye bağlanmak istiyor.") },
            confirmButton = { Button(onClick = { req.decision.complete(true) }) { Text("Onayla") } },
            dismissButton = { OutlinedButton(onClick = { req.decision.complete(false) }) { Text("Reddet") } },
        )
    }
}
