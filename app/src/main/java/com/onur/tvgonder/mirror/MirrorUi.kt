// v0.36 – 2026-10-24: yansıtma görüntüsü TextureView ile: ortalı, Doldur'da düzgün kırpılır (sola kayma giderildi); TV ekranını gör + dokunma
package com.onur.tvgonder.mirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.onur.tvgonder.Hub
import com.onur.tvgonder.Remote
import com.onur.tvgonder.Store
import com.onur.tvgonder.TvClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * Gelen yansıtmayı tam ekran gösterir. touch verilirse (tablette TV ekranı) dokunuşlar o fonksiyona komut olarak gider:
 * "t x y" dokun, "lp x y" basılı tut, "sw x1 y1 x2 y2 ms" kaydır (x,y: 0..1).
 */
@Composable
fun MirrorScreen(session: MirrorSession?, touch: ((String) -> Unit)?, onEnd: () -> Unit) {
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var ui by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiTick) { ui = true; delay(4000); ui = false }
    val ended = rememberUpdatedState(onEnd)
    val player = remember(session) {
        session?.let { s -> MirrorPlayer(s, onSize = { w, h -> size = w to h }, onEnd = { ended.value() }) }
    }
    DisposableEffect(player) { onDispose { player?.stop() } }
    BackHandler { player?.stop(); onEnd() }

    // TV'de: OK (ya da dokunuş) → Doldur (ekranı kaplar, kenarlar kırpılır) ↔ Sığdır (tamamı)
    // Yatay görüntü varsayılan olarak ekranı doldurur, dikey olan sığar; OK ile değişir
    var fillUser by remember { mutableStateOf<Boolean?>(null) }
    val fill = fillUser ?: (size?.let { (w, h) -> w > h } ?: true)
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { delay(2500); note = null } }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun toggle() { val nf = !fill; fillUser = nf; note = if (nf) "Doldur: ekranı kaplar (kenarlar kırpılır) · OK: geri al" else "Sığdır: görüntünün tamamı"; uiTick++ }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).clipToBounds()
            .focusRequester(focus).focusable()
            .onKeyEvent {
                if (touch == null && it.type == KeyEventType.KeyDown &&
                    (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter)
                ) { toggle(); true } else { if (it.type == KeyEventType.KeyDown) uiTick++; false }
            }
            .then(if (touch == null) Modifier.pointerInput(Unit) { detectTapGestures { toggle() } } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (session == null) {
            Text("Yansıtma bekleniyor…", color = Color.White)
            return@BoxWithConstraints
        }
        val ratio = size?.let { (w, h) -> w.toFloat() / h } ?: (16f / 9f)
        val doFill = fill && touch == null
        // TextureView: görüntü ekranın ortasına yerleşir; Doldur'da taşan kısım düzgünce kırpılır
        // (önceki SurfaceView taşan görüntüyü sola yaslıyordu).
        fun fit(tv: TextureView) {
            val (vw, vh) = size ?: return
            val w = tv.width.toFloat(); val h = tv.height.toFloat()
            if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
            val sc = if (doFill) maxOf(w / vw, h / vh) else minOf(w / vw, h / vh)
            tv.setTransform(android.graphics.Matrix().apply { setScale(vw * sc / w, vh * sc / h, w / 2f, h / 2f) })
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { c ->
                    TextureView(c).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            var surf: android.view.Surface? = null
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                surf = android.view.Surface(st); player?.start(surf!!); fit(this@apply)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { fit(this@apply) }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                player?.stop(); runCatching { surf?.release() }; return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                        keepScreenOn = true
                    }
                },
                update = { tv -> fit(tv) },
                modifier = Modifier.fillMaxSize(),
            )
            // Tablette dokunma alanı: görüntünün kapladığı kısım
            if (touch != null) Box(Modifier.align(Alignment.Center).aspectRatio(ratio)) { TouchLayer(touch) { uiTick++ } }
        }
        if (size == null) Text("Görüntü geliyor…", color = Color.White)
        note?.let {
            Text(
                it, Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
            )
        }

        AnimatedVisibility(ui, Modifier.align(Alignment.TopCenter).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(
                "📲  ${session.name}" + if (touch == null) "   ·   OK: Doldur/Sığdır   ·   Geri: kapat" else "   ·   Dokun: tıkla, kaydır, basılı tut",
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (touch != null) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("⟵ Geri" to { touch("back") }, "⌂ Ana ekran" to { touch("home") }, "✕ Kapat" to { player?.stop(); onEnd() }).forEach { (l, a) ->
                    Text(
                        l, Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).clickable { a() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** Görüntünün üstünde: dokunuşları TV'ye komut olarak yollar. */
@Composable
private fun TouchLayer(send: (String) -> Unit, onAny: () -> Unit) {
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onAny()
                val t0 = System.currentTimeMillis()
                val start = down.position
                var last = start
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    last = c.position
                    if ((last - start).getDistance() > slop) moved = true
                    c.consume()
                    if (!c.pressed) break
                }
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                fun n(o: Offset) = "%.4f %.4f".format(java.util.Locale.US, (o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                val dt = System.currentTimeMillis() - t0
                when {
                    moved -> send("sw ${n(start)} ${n(last)} ${dt.coerceIn(80, 2000)}")
                    dt > 550 -> send("lp ${n(start)}")
                    else -> send("t ${n(start)}")
                }
            }
        },
    )
}

// ---------------------------------------------------------------- telefondan başlatma

/** "Ekranı TV'de göster": ses izni (isteğe bağlı) → ekran kaydı izni → yansıtma servisi. */
@Composable
fun rememberMirrorStarter(store: Store): () -> Unit {
    val ctx = LocalContext.current
    val mpm = remember { ctx.getSystemService(MediaProjectionManager::class.java) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            MirrorService.start(ctx, r.resultCode, data, store.tvHost ?: "", Proto.PORT, store.tvToken ?: "", Build.MODEL)
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runCatching { capture.launch(mpm.createScreenCaptureIntent()) }
    }
    return {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) runCatching { capture.launch(mpm.createScreenCaptureIntent()) } else mic.launch(need.toTypedArray())
    }
}

// ---------------------------------------------------------------- TV ekranını telefonda/tablette gör

/**
 * Bu cihazda geçici bir dinleyici açar, TV'den ekranını buraya yollamasını ister.
 * TV'de bir kez "Başla" (ekran kaydı izni) verilir. Dokunuşlar Fare eklentisiyle TV'de tıklamaya dönüşür.
 */
@Composable
fun TvScreenViewer(client: TvClient, store: Store, full: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf("TV'ye istek gönderiliyor…") }
    var session by remember { mutableStateOf<MirrorSession?>(null) }
    var mouse by remember { mutableIntStateOf(-1) }    // 2 eklenti, 1 yalnız TV Gönder içinde, 0 yok
    val remote = remember { Remote(store.tvHost ?: "", store.tvToken ?: "") }
    val server = remember { arrayOfNulls<ServerSocket>(1) }

    // Dokunmatik tablette yatay daha rahat
    DisposableEffect(Unit) {
        val act = if (full) null else ctx.findActivity()
        val old = act?.requestedOrientation
        act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (old != null) act?.requestedOrientation = old
            runCatching { server[0]?.close() }
            session?.close()
            remote.close()
        }
    }
    LaunchedEffect(Unit) {
        mouse = withContext(Dispatchers.IO) { if (remote.probeMouse()) 2 else if (remote.probeApp()) 1 else 0 }
        remote.mousePort = if (mouse == 2) 8767 else com.onur.tvgonder.RemoteListener.PORT
    }
    LaunchedEffect(Unit) {
        val key = UUID.randomUUID().toString()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)); soTimeout = 90_000 }
                server[0] = ss
                client.mirrorRequest(ss.localPort, key, Build.MODEL)
                withContext(Dispatchers.Main) {
                    phase = "TV'ye istek gitti.\n\nŞimdi TV ekranına bak: \"TV ekranı paylaşılsın mı / Şimdi başlat\" penceresi çıkar, kumandayla \"Başla\"ya bas.\n\n" +
                        "Pencere çıkmazsa TV'de TV Gönder'i açıp tekrar dene (Android, arka plandaki uygulamanın pencere açmasına izin vermeyebilir)."
                }
                val sock = ss.accept()
                MirrorSession.accept(sock) { it == key }.also { runCatching { ss.close() } }
            }
        }
        result.onSuccess { s ->
            if (s == null) phase = "Bağlantı reddedildi." else session = s
        }.onFailure {
            phase = if (it is java.net.SocketTimeoutException) "TV'den cevap gelmedi. TV'de izin penceresi çıktı mı? TV'de TV Gönder açık olmalı."
            else if ("Bulunamad" in (it.message ?: "")) "TV'deki TV Gönder eski sürüm. Önce ⋮ → TV uygulamasını güncelle."
            else "Olmadı: ${it.message}"
        }
    }

    com.onur.tvgonder.ui.MaybeDialog(full, onClose) {
        val s = session
        if (s != null) {
            Box(Modifier.fillMaxSize()) {
                MirrorScreen(s, touch = { remote.send(it) }, onEnd = onClose)
                Text(
                    "✕", Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000))
                        .clickable { s.close(); onClose() }.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
                if (mouse == 1 || mouse == 0) {
                    Text(
                        "Dokunarak kontrol yalnızca TV Gönder ekranlarında çalışır. Her uygulama için TV'de Fare eklentisini aç.",
                        Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(0.55f)
                            .clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(10.dp),
                        color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().background(com.onur.tvgonder.ui.Ink).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📺  TV ekranını gör", style = MaterialTheme.typography.headlineSmall, color = com.onur.tvgonder.ui.TextMain)
                Text(
                    "TV'nin ekranı bu cihazda görünür; dokununca TV'de tıklar, kaydırınca kaydırır.",
                    color = com.onur.tvgonder.ui.TextMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Text(phase, color = com.onur.tvgonder.ui.TextMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                androidx.compose.material3.Button(onClick = onClose) { Text("Kapat") }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** TV'deki ekran (Hub'daki gelen oturum) için kısayol. */
@Composable
fun IncomingMirror(onDone: () -> Unit) {
    val s by Hub.mirrorIn.collectAsState()
    MirrorScreen(s, null) { s?.close(); Hub.mirrorIn.value = null; onDone() }
}
