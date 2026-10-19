// v0.26 – 2026-10-19: oynatıcı medya oturumu (telefondan oynat/durdur/ileri/geri/başa al); resim/PDF büyütme, tarayıcıda video
package com.onur.tvgonder.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- yardımcılar

/** Resmi ekran boyutuna göre küçülterek açar (fotoğraf yönü düzeltilir). */
fun decodeForTv(ctx: Context, uri: Uri, maxW: Int = 1920, maxH: Int = 1080): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val s = min(1f, min(maxW.toFloat() / info.size.width, maxH.toFloat() / info.size.height))
            dec.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / s > maxW * 2 || b.outHeight / s > maxH * 2) s *= 2
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
}.getOrNull()

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium, color = Color.White,
    )
}

private fun isKey(e: androidx.compose.ui.input.key.KeyEvent, keys: List<Key>) =
    e.type == KeyEventType.KeyDown && keys.any { it == e.key }

/** Ekrandaki yazı/düğmeler: birkaç saniye sonra kendiliğinden gizlenir; tuşa basınca ya da dokununca geri gelir. */
private class AutoHide {
    var visible by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
    fun poke() { visible = true; tick++ }
    fun toggle() { if (visible) visible = false else poke() }
}

@Composable
private fun rememberAutoHide(ms: Long = 3500): AutoHide {
    val h = remember { AutoHide() }
    LaunchedEffect(h.tick) { delay(ms); h.visible = false }
    return h
}

@Composable
private fun Fade(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}

/** Yakınlaştırma durumu: ölçek + kaydırma, resmin ekrandan taşmayacağı şekilde sınırlanır. */
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    var cw = 1f; var ch = 1f      // ekran
    var bw = 1f; var bh = 1f      // resim
    val zoomed get() = scale > 1.02f

    private fun fit() = min(cw / bw, ch / bh)
    private fun maxX() = max(0f, (bw * fit() * scale - cw) / 2f)
    private fun maxY() = max(0f, (bh * fit() * scale - ch) / 2f)
    fun clamp() { ox = ox.coerceIn(-maxX(), maxX()); oy = oy.coerceIn(-maxY(), maxY()) }
    fun reset() { scale = 1f; ox = 0f; oy = 0f }
    fun zoomBy(f: Float, cx: Float = cw / 2, cy: Float = ch / 2) {
        val ns = (scale * f).coerceIn(1f, 6f)
        val k = ns / scale
        // dokunulan nokta yerinde kalsın
        ox = (ox - (cx - cw / 2)) * k + (cx - cw / 2)
        oy = (oy - (cy - ch / 2)) * k + (cy - ch / 2)
        scale = ns; clamp()
    }
    fun pan(dx: Float, dy: Float) { ox += dx; oy += dy; clamp() }
    /** Genişliği ekrana sığdıran ölçek (dikey sayfalar için okunaklı). */
    fun widthScale(): Float { val w = cw / (bw * fit()); return if (w < 1.3f) 2f else min(w, 4f) }
    fun toTop() { clamp(); oy = maxY() }
    val atBottom get() = oy <= -maxY() + 1f
    val atTop get() = oy >= maxY() - 1f
}

/**
 * Yakınlaştırılabilir resim. Dokunmatik: iki parmakla büyüt/küçült, sürükle, çift dokun = büyüt/sıfırla,
 * tek dokun = düğmeleri göster/gizle, büyütülmemişken sağa/sola kaydır = önceki/sonraki.
 */
@Composable
private fun ZoomImage(
    bmp: Bitmap, desc: String, z: ZoomState, key: Any,
    onSwipe: (Int) -> Unit, onTap: () -> Unit,
) {
    val slop = LocalViewConfiguration.current.touchSlop
    z.bw = bmp.width.toFloat(); z.bh = bmp.height.toFloat()
    Image(
        bmp.asImageBitmap(), desc,
        Modifier.fillMaxSize()
            .onSizeChanged { z.cw = it.width.toFloat(); z.ch = it.height.toFloat(); z.clamp() }
            .pointerInput(key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p -> if (z.zoomed) z.reset() else z.zoomBy(2.5f, p.x, p.y) },
                )
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var swipe = 0f
                    var multi = false
                    var swiping = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) multi = true
                        val zc = ev.calculateZoom()
                        val pc = ev.calculatePan()
                        if (multi || z.zoomed) {
                            if (zc != 1f) z.zoomBy(zc, ev.calculateCentroid().x, ev.calculateCentroid().y)
                            z.pan(pc.x, pc.y)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            swipe += pc.x
                            if (abs(swipe) > slop) swiping = true
                            if (swiping) ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (!multi && !z.zoomed && abs(swipe) > size.width / 8f) onSwipe(if (swipe < 0) 1 else -1)
                }
            }
            .graphicsLayer { scaleX = z.scale; scaleY = z.scale; translationX = z.ox; translationY = z.oy },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun TouchBar(modifier: Modifier, items: List<Pair<String, () -> Unit>>) {
    androidx.compose.foundation.layout.Row(
        modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { (label, act) ->
            Text(
                label,
                Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000))
                    .clickable { act() }.padding(horizontal = 20.dp, vertical = 12.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ---------------------------------------------------------------- resim

/**
 * Resim görüntüleyici. Resim her zaman TAMAMI görünecek şekilde tam ekran açılır (kırpılmaz).
 * Kumanda: ◀ ▶ önceki/sonraki, ▲ ▼ döndür, OK büyüt (büyükken oklar kaydırır, Geri küçültür).
 * Dokunmatik: iki parmakla büyüt, kaydırarak geç, dokununca düğmeler çıkar.
 */
@Composable
fun TvImageViewer(images: List<Entry>, start: Int) {
    val ctx = LocalContext.current
    val store = remember { com.onur.tvgonder.Store(ctx) }
    val touch = remember { !com.onur.tvgonder.isTv(ctx) }
    var index by remember { mutableIntStateOf(start.coerceIn(0, max(0, images.size - 1))) }
    var rot by remember(index) { mutableIntStateOf(store.rotation(images[index].uri.toString())) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var raw by remember { mutableStateOf<Bitmap?>(null) }
    val focus = remember { FocusRequester() }
    val z = remember { ZoomState() }
    val ui = rememberAutoHide()

    fun go(d: Int) { val n = (index + d).coerceIn(0, images.size - 1); if (n != index) index = n; ui.poke() }
    fun rotate(d: Int) {
        rot = ((rot + d) % 360 + 360) % 360
        store.setRotation(images[index].uri.toString(), rot)
        ui.poke()
    }

    LaunchedEffect(index) {
        z.reset()
        bmp = null
        raw = withContext(Dispatchers.IO) { decodeForTv(ctx, images[index].uri, 2560, 2560) }
    }
    LaunchedEffect(raw, rot) {
        val r = raw ?: return@LaunchedEffect
        z.reset()
        bmp = if (rot == 0) r else withContext(Dispatchers.Default) {
            val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(r, 0, 0, r.width, r.height, m, true)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = z.zoomed) { z.reset() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                ui.poke()
                val step = 0.25f
                when {
                    z.zoomed && isKey(it, listOf(Key.DirectionRight)) -> { z.pan(-z.cw * step, 0f); true }
                    z.zoomed && isKey(it, listOf(Key.DirectionLeft)) -> { z.pan(z.cw * step, 0f); true }
                    z.zoomed && isKey(it, listOf(Key.DirectionDown)) -> { z.pan(0f, -z.ch * step); true }
                    z.zoomed && isKey(it, listOf(Key.DirectionUp)) -> { z.pan(0f, z.ch * step); true }
                    isKey(it, listOf(Key.DirectionRight, Key.MediaNext)) -> { go(1); true }
                    isKey(it, listOf(Key.DirectionLeft, Key.MediaPrevious)) -> { go(-1); true }
                    isKey(it, listOf(Key.DirectionUp)) -> { rotate(-90); true }
                    isKey(it, listOf(Key.DirectionDown)) -> { rotate(90); true }
                    isKey(it, listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) -> {
                        when {
                            !z.zoomed -> z.zoomBy(2f)
                            z.scale < 3.9f -> z.zoomBy(2f)
                            else -> z.reset()
                        }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) ZoomImage(b, images[index].name, z, Pair(index, rot), onSwipe = { go(it) }, onTap = { ui.toggle() })
        else Text("Açılıyor…", color = Color.White)

        Fade(ui.visible, Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Hint("${images[index].name}   ·   ${index + 1} / ${images.size}" + if (z.zoomed) "   ·   ${"%.1f".format(z.scale)}×" else "")
        }
        if (touch) {
            Fade(ui.visible, Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                TouchBar(
                    Modifier,
                    listOf(
                        "◀" to { go(-1) },
                        "⟲" to { rotate(-90) },
                        (if (z.zoomed) "🔍 Sığdır" else "🔍 Büyüt") to { if (z.zoomed) z.reset() else z.zoomBy(2.5f); ui.poke() },
                        "⟳" to { rotate(90) },
                        "▶" to { go(1) },
                    ),
                )
            }
        } else {
            Fade(ui.visible, Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Hint(
                    if (z.zoomed) "Oklar: kaydır   ·   OK: daha çok büyüt / sığdır   ·   Geri: sığdır"
                    else "◀ ▶ önceki/sonraki   ·   ▲ ▼ döndür   ·   OK büyüt",
                )
            }
        }
    }
}

// ---------------------------------------------------------------- PDF

/**
 * PDF: sayfa tam ekrana sığdırılarak açılır. Kumanda: ◀ ▶ sayfa, OK genişliğe büyüt (▲ ▼ kaydır, sayfa sonunda sonraki sayfaya geçer).
 * Dokunmatik: iki parmakla büyüt/sürükle, kaydırarak sayfa değiştir, dokununca düğmeler.
 */
@Composable
fun TvPdfViewer(uri: Uri, name: String) {
    val ctx = LocalContext.current
    val touch = remember { !com.onur.tvgonder.isTv(ctx) }
    val lock = remember { Mutex() }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val z = remember { ZoomState() }
    val ui = rememberAutoHide()
    var keepZoom by remember { mutableFloatStateOf(1f) }

    DisposableEffect(uri) {
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        val r = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        if (r == null) error = "PDF açılamadı." else { renderer = r; count = r.pageCount }
        onDispose { runCatching { r?.close() } }
    }
    LaunchedEffect(renderer, page) {
        val r = renderer ?: return@LaunchedEffect
        val nb = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    r.openPage(page).use { p ->
                        // büyütünce de net olsun: uzun kenar ~2800 piksel
                        val s = 2800f / max(p.width, p.height)
                        val b = Bitmap.createBitmap(max(1, (p.width * s).toInt()), max(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                        b.eraseColor(AColor.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }.getOrNull()
            }
        }
        bmp = nb
        if (nb != null) {
            z.bw = nb.width.toFloat(); z.bh = nb.height.toFloat()
            z.reset()
            if (keepZoom > 1.02f) { z.scale = keepZoom; z.toTop() }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = z.zoomed) { z.reset(); keepZoom = 1f }

    fun go(d: Int) {
        val n = (page + d).coerceIn(0, max(0, count - 1))
        keepZoom = z.scale
        if (n != page) page = n
        ui.poke()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF1B1B1B))
            .focusRequester(focus).focusable()
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                ui.poke()
                when {
                    isKey(it, listOf(Key.DirectionRight, Key.PageDown, Key.MediaNext)) -> { go(1); true }
                    isKey(it, listOf(Key.DirectionLeft, Key.PageUp, Key.MediaPrevious)) -> { go(-1); true }
                    isKey(it, listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) -> {
                        if (z.zoomed) { z.reset(); keepZoom = 1f } else { z.scale = z.widthScale(); z.toTop(); keepZoom = z.scale }
                        true
                    }
                    isKey(it, listOf(Key.DirectionDown)) -> {
                        if (!z.zoomed) go(1)
                        else if (z.atBottom) { if (page < count - 1) go(1) }
                        else z.pan(0f, -z.ch * 0.4f)
                        true
                    }
                    isKey(it, listOf(Key.DirectionUp)) -> {
                        if (!z.zoomed) go(-1)
                        else if (!z.atTop) z.pan(0f, z.ch * 0.4f)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            error != null -> Text(error!!, color = Color.White)
            b == null -> Text("Açılıyor…", color = Color.White)
            else -> ZoomImage(b, name, z, page, onSwipe = { go(it) }, onTap = { ui.toggle() })
        }
        Fade(ui.visible, Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Hint("$name   ·   Sayfa ${page + 1} / $count")
        }
        if (touch) {
            Fade(ui.visible, Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                TouchBar(
                    Modifier,
                    listOf(
                        "◀ Önceki" to { go(-1) },
                        (if (z.zoomed) "🔍 Sığdır" else "🔍 Büyüt") to {
                            if (z.zoomed) { z.reset(); keepZoom = 1f } else { z.scale = z.widthScale(); z.toTop(); keepZoom = z.scale }
                            ui.poke()
                        },
                        "Sonraki ▶" to { go(1) },
                    ),
                )
            }
        } else {
            Fade(ui.visible, Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Hint(
                    if (z.zoomed) "▲ ▼ kaydır   ·   ◀ ▶ sayfa   ·   OK / Geri: sayfayı sığdır"
                    else "◀ ▶ sayfa   ·   OK büyüt (okunaklı)",
                )
            }
        }
    }
}

// ---------------------------------------------------------------- video / müzik

/**
 * Video/müzik oynatıcı. Müzikte klasördeki tüm şarkılar sırayla çalar (list + start).
 * Videoda kaldığın yer hatırlanır ve bir sonraki açılışta oradan devam eder.
 */
@Composable
fun TvVideoPlayer(list: List<Uri>, start: Int, audio: Boolean, referer: String? = null, userAgent: String? = null) {
    val ctx = LocalContext.current
    val store = remember { com.onur.tvgonder.Store(ctx) }
    var hint by remember { mutableStateOf<String?>(null) }
    val player = remember {
        // İnternetten gelen videolar (tarayıcıdan) için sayfa adresi/tarayıcı kimliği gönderilir
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .apply { userAgent?.takeIf { it.isNotBlank() }?.let { setUserAgent(it) } }
            .apply { referer?.takeIf { it.startsWith("http") }?.let { setDefaultRequestProperties(mapOf("Referer" to it, "Origin" to (Uri.parse(it).let { u -> "${u.scheme}://${u.host}" }))) } }
        val ds = androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds))
            .build().apply {
            setMediaItems(
                list.map { u ->
                    val b = MediaItem.Builder().setUri(u)
                    val t = u.toString().lowercase()
                    if (".m3u8" in t) b.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    else if (".mpd" in t) b.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                    b.build()
                },
                start.coerceIn(0, maxOf(0, list.size - 1)), 0L,
            )
            if (audio) repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    hint = "Video oynatılamadı (${error.errorCodeName}). Site videoyu korumuş olabilir; tarayıcıda oynatmayı dene."
                }
            })
        }
    }
    // Telefondaki kumanda (⏯ ⏪ ⏩ ⏮ ⏭) ve TV kumandasının medya tuşları bu oynatıcıya ulaşsın
    val session = remember {
        runCatching { androidx.media3.session.MediaSession.Builder(ctx, player).setId("tvgonder-${System.nanoTime()}").build() }.getOrNull()
    }
    // Video: kaldığı yerden devam
    LaunchedEffect(Unit) {
        if (!audio && list.isNotEmpty() && list[0].scheme != "http" && list[0].scheme != "https") {
            val key = list[start.coerceIn(0, list.size - 1)].toString()
            val pos = store.position(key)
            if (pos > 5_000) {
                player.seekTo(pos)
                val m = pos / 60_000; val sec = (pos / 1000) % 60
                hint = "Kaldığın yerden devam: %d:%02d".format(m, sec)
                delay(4000); hint = null
            }
        }
    }
    // Konumu düzenli kaydet; sona gelince sil
    LaunchedEffect(Unit) {
        if (audio || list.firstOrNull()?.scheme?.startsWith("http") == true) return@LaunchedEffect
        while (true) {
            delay(5000)
            val key = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: continue
            val d = player.duration; val p = player.currentPosition
            store.setPosition(key, if (d > 0 && p > d - 15_000) 0 else p)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!audio && list.firstOrNull()?.scheme?.startsWith("http") != true) {
                val key = player.currentMediaItem?.localConfiguration?.uri?.toString()
                val d = player.duration; val p = player.currentPosition
                if (key != null) store.setPosition(key, if (d > 0 && p > d - 15_000) 0 else p)
            }
            runCatching { session?.release() }
            player.release()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    if (!audio) resizeMode = if (store.videoFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(audio && list.size > 1)
                    setShowPreviousButton(audio && list.size > 1)
                    if (audio) {
                        setShowShuffleButton(true)
                        setRepeatToggleModes(
                            androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or
                                androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
                        )
                        controllerShowTimeoutMs = 0
                        controllerHideOnTouch = false
                    }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    isFocusable = true
                    post { requestFocus(); if (audio) showController() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (audio) {
            var title by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    val md = player.mediaMetadata
                    title = listOfNotNull(md.title?.toString(), md.artist?.toString()).joinToString("  ·  ").ifBlank {
                        player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment?.substringAfterLast('/') ?: ""
                    } + "   (${player.currentMediaItemIndex + 1} / ${player.mediaItemCount})"
                    delay(1000)
                }
            }
            Hint("🎵  $title", Modifier.align(Alignment.TopCenter).padding(24.dp))
        }
        hint?.let { Hint(it, Modifier.align(Alignment.TopCenter).padding(24.dp)) }
    }
}

// ---------------------------------------------------------------- tarayıcı

private val mediaRx = Regex("""\.(m3u8|mp4|webm|mkv|mpd)(\?|#|$)""", RegexOption.IGNORE_CASE)

/**
 * Uygulamanın kendi tarayıcısı.
 * - Sayfadaki videonun tam ekran düğmesi çalışır (videonun kendi tam ekranı).
 * - Sayfada video bulunursa "🎬 Videoyu tam ekran aç" düğmesi çıkar: video TV Gönder oynatıcısında açılır
 *   (kumanda: ⏯ tuşu veya OK'e uzun bas).
 * - Tablette iki parmakla yakınlaştırılır.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvBrowser(url: String, onClose: () -> Unit, onVideo: (String, String, String) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContext.current
    val touch = remember { !com.onur.tvgonder.isTv(ctx) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(url) }
    var custom by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var media by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val ui = rememberAutoHide(5000)
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun exitFull() { runCatching { customCb?.onCustomViewHidden() }; custom = null; customCb = null; web?.requestFocus() }
    fun playMedia() {
        val m = media ?: return
        val w = web
        onVideo(m, w?.url ?: url, w?.settings?.userAgentString ?: "")
    }

    BackHandler {
        val w = web
        when {
            custom != null -> exitFull()
            w != null && w.canGoBack() -> w.goBack()
            else -> onClose()
        }
    }
    DisposableEffect(Unit) { onDispose { web?.apply { stopLoading(); loadUrl("about:blank"); destroy() } } }
    LaunchedEffect(url) { if (web?.url != url) web?.loadUrl(url) }
    LaunchedEffect(media) { if (media != null) ui.poke() }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true        // iki parmakla yakınlaştırma
                    settings.displayZoomControls = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(true)   // reklam açılır pencereleri sayfanın yerine geçmesin
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { media = null; title = u; ui.poke() }
                        override fun onPageFinished(view: WebView, u: String) { title = view.title?.takeIf { it.isNotBlank() } ?: u }
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val s = request.url.scheme ?: return true
                            // intent://, market:// vb. dış uygulama bağlantıları açılmaz
                            return s != "http" && s != "https"
                        }
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val u = request.url.toString()
                            if (mediaRx.containsMatchIn(request.url.path ?: "") || ".m3u8" in u) {
                                main.post {
                                    val cur = media
                                    // akış listesi (m3u8) tek mp4'ten iyidir; reklam mp4'ü sonradan gelirse yerini almasın
                                    if (cur == null || (".m3u8" !in cur && ".m3u8" in u)) media = u
                                }
                            }
                            return null
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            custom = view; customCb = callback
                        }
                        override fun onHideCustomView() { custom = null; customCb = null; web?.requestFocus() }
                        override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
                    }
                    // Kumanda: ⏯ veya OK'e uzun basınca bulunan videoyu tam ekran aç
                    var longFired = false
                    setOnKeyListener { _, code, e ->
                        val playKey = code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                            code == android.view.KeyEvent.KEYCODE_MENU
                        val center = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                        if (e.action == android.view.KeyEvent.ACTION_DOWN) ui.poke()
                        when {
                            media != null && playKey -> { if (e.action == android.view.KeyEvent.ACTION_UP) playMedia(); true }
                            media != null && center && e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount >= 1 -> {
                                if (!longFired) { longFired = true; playMedia() }
                                true
                            }
                            center && e.action == android.view.KeyEvent.ACTION_UP && longFired -> { longFired = false; true }
                            else -> false
                        }
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadUrl(url)
                    post { requestFocus() }
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
        Fade(ui.visible && custom == null, Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            Hint("🌐  $title   ·   Geri: önceki sayfa / kapat")
        }
        if (media != null && custom == null) {
            Text(
                if (touch) "🎬  Videoyu tam ekran aç" else "🎬  Videoyu tam ekran aç  (⏯ veya OK'e uzun bas)",
                Modifier.align(Alignment.BottomEnd).padding(20.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xE6E50914))
                    .clickable { playMedia() }.padding(horizontal = 20.dp, vertical = 14.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
        // Sayfadaki videonun kendi tam ekranı
        custom?.let { v -> androidx.compose.runtime.key(v) {
            AndroidView(
                factory = { c ->
                    android.widget.FrameLayout(c).apply {
                        setBackgroundColor(AColor.BLACK)
                        (v.parent as? ViewGroup)?.removeView(v)
                        addView(v, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        isFocusable = true
                        post { v.requestFocus() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } }
    }
}

// ---------------------------------------------------------------- slayt gösterisi

@Composable
fun TvSlideshow(tree: Uri, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var images by remember { mutableStateOf<List<Entry>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Bitmap?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(tree) {
        images = withContext(Dispatchers.IO) { Usb.allImages(ctx, tree).shuffled() }
    }
    LaunchedEffect(images) {
        val list = images ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        while (true) {
            current = withContext(Dispatchers.IO) { decodeForTv(ctx, list[index % list.size].uri) } ?: current
            delay(8000)
            index++
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) { onExit(); true } else false }
            .pointerInput(Unit) { detectTapGestures { onExit() } },
        contentAlignment = Alignment.Center,
    ) {
        when {
            images == null -> Text("Fotoğraflar aranıyor…", color = Color.White)
            images!!.isEmpty() -> Text("USB bellekte fotoğraf bulunamadı. Bir tuşa basarak çık.", color = Color.White)
            else -> Crossfade(current, animationSpec = tween(1200), label = "slayt") { b ->
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}
