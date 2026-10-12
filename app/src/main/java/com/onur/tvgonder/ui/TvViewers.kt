// v0.7.1 – 2026-10-12: TV görüntüleyicileri: resim, PDF, video/müzik (Media3), tarayıcı (WebView), slayt gösterisi
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
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

// ---------------------------------------------------------------- resim

@Composable
fun TvImageViewer(images: List<Entry>, start: Int) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(start.coerceIn(0, max(0, images.size - 1))) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(index) {
        bmp = withContext(Dispatchers.IO) { decodeForTv(ctx, images[index].uri) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent {
                when {
                    isKey(it, listOf(Key.DirectionRight, Key.MediaNext)) -> { if (index < images.size - 1) index++; true }
                    isKey(it, listOf(Key.DirectionLeft, Key.MediaPrevious)) -> { if (index > 0) index--; true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), images[index].name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("Açılıyor…", color = Color.White)
        Hint("${images[index].name}   ·   ${index + 1} / ${images.size}   ·   ◀ ▶ önceki / sonraki", Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

// ---------------------------------------------------------------- PDF

@Composable
fun TvPdfViewer(uri: Uri, name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var wide by remember { mutableStateOf(false) }   // false: sayfaya sığdır, true: genişliğe sığdır
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }

    DisposableEffect(uri) {
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        val r = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        if (r == null) error = "PDF açılamadı." else { renderer = r; count = r.pageCount }
        onDispose { runCatching { r?.close() } }
    }
    LaunchedEffect(renderer, page, wide) {
        val r = renderer ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    r.openPage(page).use { p ->
                        val s = if (wide) 1900f / p.width else 1060f / p.height
                        val b = Bitmap.createBitmap(max(1, (p.width * s).toInt()), max(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                        b.eraseColor(AColor.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }.getOrNull()
            }
        }
        scroll.scrollTo(0)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF1B1B1B))
            .focusRequester(focus).focusable()
            .onKeyEvent {
                when {
                    isKey(it, listOf(Key.DirectionRight, Key.PageDown, Key.MediaNext)) -> { if (page < count - 1) page++; true }
                    isKey(it, listOf(Key.DirectionLeft, Key.PageUp, Key.MediaPrevious)) -> { if (page > 0) page--; true }
                    isKey(it, listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) -> { wide = !wide; true }
                    isKey(it, listOf(Key.DirectionDown)) -> { scope.launch { scroll.animateScrollBy(400f) }; true }
                    isKey(it, listOf(Key.DirectionUp)) -> { scope.launch { scroll.animateScrollBy(-400f) }; true }
                    else -> false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val b = bmp
        when {
            error != null -> Text(error!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
            b == null -> Text("Açılıyor…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            wide -> Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Image(b.asImageBitmap(), name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            else -> Image(b.asImageBitmap(), name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Hint(
            "$name   ·   Sayfa ${page + 1} / $count   ·   ◀ ▶ sayfa   ·   OK yakınlaştır" + if (wide) "   ·   ▲ ▼ kaydır" else "",
            Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }
}

private suspend fun androidx.compose.foundation.ScrollState.animateScrollBy(d: Float) {
    animateScrollTo((value + d.toInt()).coerceIn(0, maxValue))
}

// ---------------------------------------------------------------- video / müzik

@Composable
fun TvVideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = true
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFocusable = true
                post { requestFocus() }
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

// ---------------------------------------------------------------- tarayıcı

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvBrowser(url: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(url) }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    LaunchedEffect(url) { web?.loadUrl(url) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String) { title = view.title ?: u }
                    }
                    webChromeClient = WebChromeClient()
                    isFocusable = true
                    loadUrl(url)
                    post { requestFocus() }
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Hint("🌐  $title   ·   Geri: önceki sayfa / kapat", Modifier.align(Alignment.TopCenter).padding(12.dp))
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
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) { onExit(); true } else false },
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
