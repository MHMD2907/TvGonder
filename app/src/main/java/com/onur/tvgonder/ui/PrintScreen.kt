package com.onur.tvgonder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.onur.tvgonder.PrintDoc
import com.onur.tvgonder.Printing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrintScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<PrintDoc?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var a5 by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(true) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Printing.load(ctx, uri) } }
            busy = false
            r.onSuccess { doc = it; landscape = it.thumb.width > it.thumb.height }
                .onFailure { error = it.message ?: "Dosya açılamadı." }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Yazdır", style = MaterialTheme.typography.headlineLarge, color = TextMain)

        val d = doc
        if (d == null) {
            Text(
                "PDF veya resim seç. Ağdaki yazıcıyı ve sayfa aralığını bir sonraki ekranda seçersin.",
                style = MaterialTheme.typography.bodyLarge, color = TextMuted,
            )
            Button(enabled = !busy, onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }) {
                Text(if (busy) "Açılıyor…" else "Dosya seç")
            }
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(
                        d.thumb.asImageBitmap(), null,
                        Modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = TextMain, maxLines = 3)
                        Text("${d.pageCount} sayfa", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        TextButton(onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }) { Text("Başka dosya seç") }
                    }
                }
            }

            Text("Kağıt", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !a5, onClick = { a5 = false }, label = { Text("A4") })
                FilterChip(selected = a5, onClick = { a5 = true }, label = { Text("A5") })
            }
            Text("Yön", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !landscape, onClick = { landscape = false }, label = { Text("Dikey") })
                FilterChip(selected = landscape, onClick = { landscape = true }, label = { Text("Yatay") })
            }
            Text("Renk", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = color, onClick = { color = true }, label = { Text("Renkli") })
                FilterChip(selected = !color, onClick = { color = false }, label = { Text("Siyah-beyaz") })
            }

            Button(onClick = {
                error = runCatching { Printing.print(ctx, d, a5, landscape, color) }.exceptionOrNull()?.message
            }) { Text("Yazıcıya gönder") }
            Text(
                "Sonraki ekranda yazıcıyı, sayfa aralığını (örneğin 1-3, 5) ve kopya sayısını seçersin.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Danger) }
    }
}
