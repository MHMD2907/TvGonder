// v0.7 – 2026-10-12: TV ortak parçaları (odaklı kart), USB bellek listeleme, Dosyalarım ekranı
package com.onur.tvgonder.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- dosya modeli

enum class Kind(val emoji: String) { Folder("📁"), Video("🎬"), Audio("🎵"), Image("🖼️"), Pdf("📄"), Apk("📦"), Other("📎") }

data class Entry(
    val name: String,
    val uri: Uri,
    val mime: String,
    val size: Long,
    val modified: Long,
) {
    val kind: Kind
        get() = when {
            mime == DocumentsContract.Document.MIME_TYPE_DIR -> Kind.Folder
            mime.startsWith("video/") -> Kind.Video
            mime.startsWith("audio/") -> Kind.Audio
            mime.startsWith("image/") -> Kind.Image
            mime == "application/pdf" || name.endsWith(".pdf", true) -> Kind.Pdf
            mime == "application/vnd.android.package-archive" || name.endsWith(".apk", true) -> Kind.Apk
            else -> Kind.Other
        }
}

object Usb {
    /** Seçilen USB klasörünün kök belgesi. */
    fun rootUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Bir klasörün içindekiler: önce klasörler, sonra en yeni dosyalar. */
    fun list(ctx: Context, tree: Uri, dir: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<Entry>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += Entry(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    mime = c.getString(2) ?: "",
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out.sortedWith(compareBy<Entry> { it.kind != Kind.Folder }.thenByDescending { if (it.kind == Kind.Folder) 0 else it.modified }.thenBy { it.name.lowercase() })
    }

    /** Slayt gösterisi için tüm resimler (alt klasörler dahil, sınırlı). */
    fun allImages(ctx: Context, tree: Uri, limit: Int = 1500): List<Entry> {
        val out = mutableListOf<Entry>()
        val queue = ArrayDeque<Pair<Uri, Int>>().apply { add(rootUri(tree) to 0) }
        while (queue.isNotEmpty() && out.size < limit) {
            val (dir, depth) = queue.removeFirst()
            for (e in runCatching { list(ctx, tree, dir) }.getOrDefault(emptyList())) {
                if (e.kind == Kind.Folder && depth < 4) queue.add(e.uri to depth + 1)
                else if (e.kind == Kind.Image) out += e
            }
        }
        return out
    }
}

// ---------------------------------------------------------------- kumandayla odaklanan kart

@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    padding: PaddingValues = PaddingValues(24.dp),
    onClick: () -> Unit,
    content: @Composable ColumnScope.(focused: Boolean) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvcard")
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(if (focused) PanelHi else Panel)
            .border(3.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(padding),
    ) { content(focused) }
}

// ---------------------------------------------------------------- Dosyalarım

@Composable
fun TvFilesScreen(
    tree: Uri,
    dir: Uri,
    title: String,
    onOpenFolder: (Entry) -> Unit,
    onOpen: (Entry, List<Entry>) -> Unit,
) {
    val ctx = LocalContext.current
    var entries by remember(dir) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(dir) { mutableStateOf<String?>(null) }
    val first = remember(dir) { FocusRequester() }

    LaunchedEffect(dir) {
        val r = withContext(Dispatchers.IO) { runCatching { Usb.list(ctx, tree, dir) } }
        entries = r.getOrNull() ?: emptyList()
        error = r.exceptionOrNull()?.let { "USB bellek okunamadı. Takılı mı?" }
    }
    LaunchedEffect(entries) { if (!entries.isNullOrEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 56.dp, vertical = 40.dp)) {
        Text("📁  $title", style = MaterialTheme.typography.headlineMedium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Geri tuşuyla bir üst klasöre dönersin.", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.height(24.dp))
        val list = entries
        when {
            list == null -> Text("Yükleniyor…", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            error != null -> Text(error!!, style = MaterialTheme.typography.bodyLarge, color = Danger)
            list.isEmpty() -> Text("Bu klasör boş.", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(list, key = { _, e -> e.uri.toString() }) { i, e ->
                    TvCard(
                        Modifier.height(150.dp),
                        focusRequester = if (i == 0) first else null,
                        padding = PaddingValues(18.dp),
                        onClick = { if (e.kind == Kind.Folder) onOpenFolder(e) else onOpen(e, list) },
                    ) { focused ->
                        Text(e.kind.emoji, fontSize = 40.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            e.name, style = MaterialTheme.typography.titleMedium, color = TextMain,
                            maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (e.kind == Kind.Folder) "Klasör"
                            else Formatter.formatShortFileSize(LocalContext.current, e.size) +
                                (if (e.modified > 0) "  ·  " + DateFormat.format("d MMM yyyy", e.modified) else ""),
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Küçük bilgi rozeti (TV ana ekranı). */
@Composable
fun TvChip(text: String, color: Color = TextMuted) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextMain)
    }
}
