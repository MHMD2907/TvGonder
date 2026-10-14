// v0.15 – 2026-10-14: Dosyalarım: küçük resimler (video karesi, PDF sayfası, kapak), seçip silme, tarih+saat
package com.onur.tvgonder.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import java.io.File
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap

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
        if (tree.scheme == "file") tree
        else DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: ""

    private fun listFiles(dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.map { f ->
            Entry(
                name = f.name,
                uri = Uri.fromFile(f),
                mime = if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(f.name),
                size = if (f.isDirectory) 0 else f.length(),
                modified = f.lastModified(),
            )
        }

    /** Bir klasörün içindekiler: önce klasörler, sonra en yeni dosyalar. */
    fun list(ctx: Context, tree: Uri, dir: Uri): List<Entry> {
        if (dir.scheme == "file") return sort(listFiles(File(dir.path!!).apply { mkdirs() }))
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
        return sort(out)
    }

    private fun sort(out: List<Entry>): List<Entry> {
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

/** Küçük resimler: video karesi, PDF ilk sayfası, fotoğraf, müzik kapağı (bellekte önbellekli). */
object Thumbs {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val gate = kotlinx.coroutines.sync.Semaphore(2)

    suspend fun get(ctx: Context, e: Entry): android.graphics.Bitmap? {
        val key = e.uri.toString()
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) { runCatching { make(ctx, e) }.getOrNull() }
        }?.also { cache.put(key, it) }
    }

    private fun scaled(b: android.graphics.Bitmap?, w: Int = 400): android.graphics.Bitmap? {
        if (b == null || b.width <= w) return b
        return android.graphics.Bitmap.createScaledBitmap(b, w, (b.height * w.toFloat() / b.width).toInt().coerceAtLeast(1), true)
    }

    private fun make(ctx: Context, e: Entry): android.graphics.Bitmap? = when (e.kind) {
        Kind.Image -> decodeForTv(ctx, e.uri, 400, 300)
        Kind.Video, Kind.Audio -> {
            val m = android.media.MediaMetadataRetriever()
            try {
                m.setDataSource(ctx, e.uri)
                if (e.kind == Kind.Audio) m.embeddedPicture?.let { scaled(android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)) }
                else if (android.os.Build.VERSION.SDK_INT >= 27)
                    m.getScaledFrameAtTime(2_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 300)
                else scaled(m.getFrameAtTime(2_000_000))
            } finally {
                runCatching { m.release() }
            }
        }
        Kind.Pdf -> ctx.contentResolver.openFileDescriptor(e.uri, "r")?.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val s = 300f / p.height
                    val b = android.graphics.Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), 300, android.graphics.Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
        else -> null
    }
}

/** Dosyanın küçük resmi; yoksa türünün simgesi. Videolarda ▶ işareti. */
@Composable
fun EntryThumb(e: Entry, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, e.uri) {
        value = if (e.kind == Kind.Folder || e.kind == Kind.Other || e.kind == Kind.Apk) null else Thumbs.get(ctx, e)
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A1622)), contentAlignment = Alignment.Center) {
        val t = thumb
        if (t != null) {
            androidx.compose.foundation.Image(
                t.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (e.kind == Kind.Pdf) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(e.kind.emoji, fontSize = 40.sp)
        }
        if (e.kind == Kind.Video) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 22.sp)
            }
        }
    }
}

/** Dosya/klasör siler (düz klasör veya Android seçicisiyle seçilmiş yer). */
fun deleteEntry(ctx: Context, e: Entry): Boolean = runCatching {
    if (e.uri.scheme == "file") File(e.uri.path!!).deleteRecursively()
    else DocumentsContract.deleteDocument(ctx.contentResolver, e.uri)
}.getOrDefault(false)

@Composable
fun TvFilesScreen(
    tree: Uri,
    dir: Uri,
    title: String,
    onOpenFolder: (Entry) -> Unit,
    onOpen: (Entry, List<Entry>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var entries by remember(dir) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(dir) { mutableStateOf<String?>(null) }
    var reload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<Uri>() }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val first = remember(dir) { FocusRequester() }

    LaunchedEffect(dir, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { Usb.list(ctx, tree, dir) } }
        entries = r.getOrNull() ?: emptyList()
        error = r.exceptionOrNull()?.let { "USB bellek okunamadı. Takılı mı?" }
    }
    LaunchedEffect(entries) { if (!entries.isNullOrEmpty()) runCatching { first.requestFocus() } }
    LaunchedEffect(info) { if (info != null) { kotlinx.coroutines.delay(4000); info = null } }
    androidx.activity.compose.BackHandler(enabled = selecting) { selecting = false; selected.clear() }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📁  $title", style = MaterialTheme.typography.headlineMedium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (selecting) "Seçmek için OK. Geri: seçimden çık." else "Geri tuşuyla bir üst klasöre dönersin.",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                )
            }
            val bar = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            if (!selecting) {
                TvCard(padding = bar, onClick = { selecting = true; selected.clear() }) {
                    Text("☑  Seç / Sil", style = MaterialTheme.typography.titleMedium, color = TextMain)
                }
            } else {
                TvCard(padding = bar, onClick = {
                    val all = entries.orEmpty().map { it.uri }
                    if (selected.size == all.size) selected.clear() else { selected.clear(); selected.addAll(all) }
                }) { Text("Tümü", style = MaterialTheme.typography.titleMedium, color = TextMain) }
                Spacer(Modifier.width(12.dp))
                TvCard(padding = bar, onClick = { if (selected.isNotEmpty()) confirm = true }) {
                    Text("🗑  Sil (${selected.size})", style = MaterialTheme.typography.titleMedium, color = Danger)
                }
                Spacer(Modifier.width(12.dp))
                TvCard(padding = bar, onClick = { selecting = false; selected.clear() }) {
                    Text("Vazgeç", style = MaterialTheme.typography.titleMedium, color = TextMain)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val list = entries
        when {
            list == null -> Text("Yükleniyor…", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            error != null -> Text(error!!, style = MaterialTheme.typography.bodyLarge, color = Danger)
            list.isEmpty() -> Text("Bu klasör boş.", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(list, key = { _, e -> e.uri.toString() }) { i, e ->
                    val isSel = e.uri in selected
                    TvCard(
                        Modifier.height(214.dp),
                        focusRequester = if (i == 0) first else null,
                        padding = PaddingValues(10.dp),
                        onClick = {
                            when {
                                selecting -> if (isSel) selected.remove(e.uri) else selected.add(e.uri)
                                e.kind == Kind.Folder -> onOpenFolder(e)
                                else -> onOpen(e, list)
                            }
                        },
                    ) { focused ->
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            EntryThumb(e, Modifier.fillMaxSize())
                            if (selecting) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(RoundedCornerShape(50))
                                        .background(if (isSel) Accent else Color(0x88000000))
                                        .border(2.dp, Color.White, RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center,
                                ) { if (isSel) Text("✓", color = Ink, fontSize = 18.sp) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            e.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = TextMain,
                            maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (e.kind == Kind.Folder) "Klasör"
                            else Formatter.formatShortFileSize(LocalContext.current, e.size) +
                                (if (e.modified > 0) "  ·  " + DateFormat.format("d MMM yyyy HH:mm", e.modified) else ""),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = TextMuted, maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    info?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                it, Modifier.padding(32.dp).clip(RoundedCornerShape(14.dp)).background(PanelHi).padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge, color = TextMain,
            )
        }
    }

    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = PanelHi,
            title = { Text("${selected.size} öğe silinsin mi?") },
            text = { Text("Seçilen dosya ve klasörler bellekten kalıcı olarak silinir.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    confirm = false
                    val targets = entries.orEmpty().filter { it.uri in selected }
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { targets.count { deleteEntry(ctx, it) } }
                        val store = com.onur.tvgonder.Store(ctx)
                        targets.forEach { store.removeReceived(it.uri.toString()) }
                        info = if (ok == targets.size) "$ok öğe silindi." else "$ok / ${targets.size} öğe silindi."
                        selected.clear(); selecting = false; reload++
                    }
                }) { Text("Sil") }
            },
            dismissButton = { androidx.compose.material3.OutlinedButton(onClick = { confirm = false }) { Text("Vazgeç") } },
        )
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
