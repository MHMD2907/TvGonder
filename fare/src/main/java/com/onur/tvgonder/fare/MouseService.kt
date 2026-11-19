// v1.9 – 2026-11-19: TV Gönder Fare 1.9: ⏪⏩ ileri/geri sarma (çubuğu bulur, gizliyse denetimleri açar), ekran yapısı dökümü (destek); otomatik sıradaki; onay; tıklama
package com.onur.tvgonder.fare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView

/**
 * TV'de "TV Gönder Fare" erişilebilirlik hizmeti açıldığında çalışır.
 * Telefondan gelen komutlarla imleci hareket ettirir ve dokunma hareketleri gönderir.
 */
class MouseService : AccessibilityService() {

    companion object {
        @Volatile var instance: MouseService? = null
            private set
        /** Süreçte tek dinleyici (hizmet yeniden bağlanınca eskisi kapatılır). */
        @Volatile private var shared: MouseListener? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursor: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var x = 0f
    private var y = 0f
    private var w = 1920
    private var h = 1080
    private val hide = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching { setup() }
        if (getSharedPreferences("fare", MODE_PRIVATE).getBoolean("autoNext", false)) setAutoNext(true)
        // Önceki (ölmüş/eski) dinleyiciyi kapatıp tek ve yeni bir dinleyici başlat
        runCatching { shared?.close() }
        runCatching { listener = MouseListener(this).also { it.isDaemon = true; it.start(); shared = it } }
    }

    private fun setup() {
        val m = resources.displayMetrics
        w = m.widthPixels; h = m.heightPixels
        runCatching {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0) { w = dm.widthPixels; h = dm.heightPixels }
        }
        x = w / 2f; y = h / 2f
        val size = (m.density * 28).toInt().coerceAtLeast(32)
        val view = ImageView(this).apply { setImageBitmap(arrow(size)); visibility = View.GONE }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = this@MouseService.x.toInt(); this.y = this@MouseService.y.toInt() }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm?.addView(view, params) }
        cursor = view; lp = params
    }

    private var listener: MouseListener? = null

    /** Beyaz, siyah kenarlı ok imleci. */
    private fun arrow(s: Int): Bitmap {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Path().apply {
            moveTo(s * 0.08f, s * 0.04f); lineTo(s * 0.08f, s * 0.86f); lineTo(s * 0.30f, s * 0.66f)
            lineTo(s * 0.46f, s * 0.96f); lineTo(s * 0.60f, s * 0.89f); lineTo(s * 0.45f, s * 0.60f)
            lineTo(s * 0.74f, s * 0.60f); close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = s * 0.06f })
        return b
    }

    private fun show() = runCatching { showInner() }

    private fun showInner() {
        val v = cursor ?: return
        val p = lp ?: return
        v.visibility = View.VISIBLE
        p.x = x.toInt(); p.y = y.toInt()
        runCatching { wm?.updateViewLayout(v, p) }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 10_000)
    }

    fun move(dx: Float, dy: Float) = main.post {
        x = (x + dx).coerceIn(0f, w - 2f)
        y = (y + dy).coerceIn(0f, h - 2f)
        show()
    }

    /** Son tıklamanın sonucu: "ok" (dokunma uygulandı), "düğme" (ekrandaki düğmeye basıldı), "iptal", "red", "yok". */
    @Volatile var lastResult = "-"

    /**
     * Dokunma hareketi gönderir. Sistem hareketi iptal eder ya da reddederse (bazı TV'lerde olur)
     * [fx],[fy] verildiyse o noktadaki düğmeye erişilebilirlik ile basılır.
     */
    private fun gesture(path: Path, duration: Long, fx: Float? = null, fy: Float? = null) {
        if (android.os.Build.VERSION.SDK_INT < 24) { if (fx != null && fy != null) nodeClick(fx, fy); return }
        val sent = runCatching {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (fx != null) lastResult = "ok" }
                override fun onCancelled(d: GestureDescription?) {
                    lastResult = "iptal"
                    if (fx != null && fy != null) lastResult = if (nodeClick(fx, fy)) "düğme" else "iptal"
                }
            }, main)
        }.getOrDefault(false)
        if (!sent && fx != null && fy != null) lastResult = if (nodeClick(fx, fy)) "düğme" else "red"
    }

    /** Noktanın altındaki en küçük tıklanabilir öğeyi bulur (üstteki pencereden başlayarak). */
    private fun clickableAt(px: Float, py: Float): android.view.accessibility.AccessibilityNodeInfo? {
        val pt = android.graphics.Rect()
        val roots = buildList {
            runCatching {
                windows.sortedByDescending { it.layer }.forEach { w ->
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
                    w.getBoundsInScreen(pt)
                    if (pt.contains(px.toInt(), py.toInt())) w.root?.let { add(it) }
                }
            }
            if (isEmpty()) rootInActiveWindow?.let { add(it) }
        }
        for (root in roots) {
            var best: android.view.accessibility.AccessibilityNodeInfo? = null
            var bestArea = Long.MAX_VALUE
            val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 3000) {
                val n = stack.removeLast()
                n.getBoundsInScreen(pt)
                if (!pt.contains(px.toInt(), py.toInt()) || !n.isVisibleToUser) continue
                if (n.isClickable && n.isEnabled) {
                    val a = pt.width().toLong() * pt.height()
                    if (a < bestArea) { best = n; bestArea = a }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            if (best != null) return best
        }
        return null
    }

    /** Erişilebilirlikle tıklama (dokunma hareketi işlemeyen ekranlar için). */
    private fun nodeClick(px: Float, py: Float): Boolean = runCatching {
        clickableAt(px, py)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
    }.getOrDefault(false)

    /**
     * Tıklama: noktada gerçek bir düğme varsa (ekranın yarısından küçük) doğrudan ona basılır — dokunmayı yok sayan
     * TV'lerde de çalışır. Yoksa (oyun, çizim alanı, video…) dokunma hareketi gönderilir.
     */
    /** İlerleme çubuğu gibi görünen öğe: aralık bilgisi, sınıf adı ya da ileri/geri kaydırma eylemi. */
    private fun isSeekLike(n: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val ri = n.rangeInfo
        if (ri != null && ri.max > ri.min) return true
        val c = n.className?.toString().orEmpty()
        if (listOf("SeekBar", "TimeBar", "Slider", "ProgressBar").any { c.contains(it, true) }) return true
        val acts = n.actionList.map { it.id }
        return android.R.id.accessibilityActionSetProgress in acts
    }

    /** Öndeki penceredeki ilk ilerleme çubuğu benzeri öğe. */
    private fun findSeek(): android.view.accessibility.AccessibilityNodeInfo? = runCatching {
        val root = rootInActiveWindow ?: return null
        val st = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var guard = 0
        while (st.isNotEmpty() && guard++ < 2500) {
            val n = st.removeFirst()
            if (n.isVisibleToUser && isSeekLike(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
        }
        null
    }.getOrNull()

    /**
     * Videoyu ileri/geri sarar ([forward]). Önce ilerleme çubuğu aranır; yoksa denetimler gizlidir: ekranın ortasına
     * basılıp denetimler açılır ve tekrar denenir.
     */
    fun seek(forward: Boolean) = main.post { seekStep(forward, true) }

    private fun seekStep(forward: Boolean, retry: Boolean) {
        val n = findSeek()
        if (n == null) {
            if (retry) {
                nodeClick(w / 2f, h / 2f).let { if (!it) gesture(Path().apply { moveTo(w / 2f, h / 2f) }, 60) }
                main.postDelayed({ seekStep(forward, false) }, 600)
            } else lastResult = "çubuk yok"
            return
        }
        val ri = n.rangeInfo
        val ok = runCatching {
            if (ri != null && ri.max > ri.min) {
                val step = (ri.max - ri.min) * 0.05f
                val v = (ri.current + if (forward) step else -step).coerceIn(ri.min, ri.max)
                n.performAction(android.R.id.accessibilityActionSetProgress,
                    android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v) }) ||
                    n.performAction(if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            } else n.performAction(if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }.getOrDefault(false)
        lastResult = if (ok) (if (forward) "ileri sarıldı" else "geri sarıldı") else "çubuk sarılamadı"
    }

    /**
     * Destek için: öndeki pencerenin yapısını metne döker (sınıf, yazı, eylemler, konum, aralık) ve TV Gönder'e yollar.
     * Kişisel bilgi taşımaz; yalnız ekrandaki öğelerin türü ve yazısı. Telefondan "Ekran yapısını al" ile istenir.
     */
    fun dump() = Thread {
        val sb = StringBuilder()
        runCatching {
            val r = android.graphics.Rect()
            val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
            sb.append("TV Gönder Fare 1.9 ekran yapısı · ekran ${w}x$h\n")
            var count = 0
            for (root in roots) {
                sb.append("=== pencere: ${root.packageName}\n")
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo, d: Int) {
                    if (count++ > 1500) return
                    n.getBoundsInScreen(r)
                    val acts = n.actionList.mapNotNull { a ->
                        when (a.id) {
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> "tık"
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ileri"
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "geri"
                            android.R.id.accessibilityActionSetProgress -> "ilerleme"
                            else -> null
                        }
                    }
                    val ri = n.rangeInfo?.let { " aralık=${it.min}..${it.max} şu an=${it.current}" } ?: ""
                    val t = (n.text ?: n.contentDescription)?.toString()?.replace("\n", " ")?.take(60) ?: ""
                    sb.append("  ".repeat(d)).append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                        .append(if (t.isNotEmpty()) " \"$t\"" else "")
                        .append(" [${r.left},${r.top} ${r.width()}x${r.height()}]")
                        .append(if (acts.isNotEmpty()) " " + acts.joinToString(",") else "")
                        .append(if (!n.isVisibleToUser) " gizli" else "").append(ri).append('\n')
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
                }
                walk(root, 0)
            }
        }
        runCatching {
            val b = sb.toString().toByteArray(Charsets.UTF_8)
            val c = java.net.URL("http://127.0.0.1:8765/fare/dump").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 1500; c.readTimeout = 3000
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            c.responseCode; c.disconnect()
        }
    }.start()

    /** Noktanın yakınındaki ilerleme çubuğu (video/ses çubuğu ince olduğu için dikeyde payla aranır). */
    private fun rangeAt(px: Float, py: Float): android.view.accessibility.AccessibilityNodeInfo? = runCatching {
        val root = rootInActiveWindow ?: return null
        val r = android.graphics.Rect()
        val st = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var guard = 0
        while (st.isNotEmpty() && guard++ < 2500) {
            val n = st.removeLast()
            n.getBoundsInScreen(r)
            if (!n.isVisibleToUser) continue
            if (isSeekLike(n) && px >= r.left && px <= r.right && py >= r.top - 45 && py <= r.bottom + 45) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
        }
        null
    }.getOrNull()

    /** İlerleme çubuğunu ekrandaki x konumuna karşılık gelen değere getirir. */
    private fun seekTo(n: android.view.accessibility.AccessibilityNodeInfo, px: Float): Boolean = runCatching {
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        if (r.width() <= 0) return false
        val ri = n.rangeInfo
        if (ri == null || ri.max <= ri.min) {
            // Aralık bilinmiyor: çubuğun sağ yarısına dokunuş ileri, sol yarısına geri sarar
            return n.performAction(if (px > r.centerX()) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        val frac = ((px - r.left) / r.width()).coerceIn(0f, 1f)
        val v = ri.min + (ri.max - ri.min) * frac
        n.performAction(android.R.id.accessibilityActionSetProgress,
            android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v) })
    }.getOrDefault(false)

    // ---- Otomatik sıradaki video (telefondan açılıp kapatılır) ----
    @Volatile var autoNext = false
        private set
    private var lastAutoAt = 0L
    private val numbered = Regex("""(\d+)\s*\.\s*(soru|bölüm|ders|video)""", RegexOption.IGNORE_CASE)
    private val nextWords = listOf("sıradaki", "sonraki", "next", "devam")
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoNext) return
            runCatching { checkAutoNext() }
            main.postDelayed(this, 1500)
        }
    }

    fun setAutoNext(on: Boolean) = main.post {
        autoNext = on
        getSharedPreferences("fare", MODE_PRIVATE).edit().putBoolean("autoNext", on).apply()
        main.removeCallbacks(autoTick)
        if (on) main.post(autoTick)
    }

    /**
     * Öndeki uygulamada ilerleme çubuğu sona geldiyse, sağdaki "N. soru" (ya da "Sıradaki/Sonraki") düğmesine basar.
     * Yalnız kullanıcı telefondan açtıysa çalışır; 8 sn'de en fazla bir kez.
     */
    private fun checkAutoNext() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoAt < 8000) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == "com.onur.tvgonder" || pkg == packageName || pkg == "com.android.systemui") return
        var seek: android.view.accessibility.AccessibilityNodeInfo? = null
        val cands = mutableListOf<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()   // düğüm, öncelik
        val st = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var guard = 0
        while (st.isNotEmpty() && guard++ < 2500) {
            val n = st.removeLast()
            if (!n.isVisibleToUser) continue
            val ri = n.rangeInfo
            if (seek == null && ri != null && ri.max > ri.min) seek = n
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                if (numbered.containsMatchIn(t)) cands += n to 2
                else if (nextWords.any { t.contains(it, true) } && t.length < 30) cands += n to 1
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { st.add(it) }
        }
        val ri = seek?.rangeInfo ?: return
        if ((ri.current - ri.min) / (ri.max - ri.min) < 0.985f) return
        val r = android.graphics.Rect()
        // Numaralılardan en sağdaki = sıradaki; yoksa "Sıradaki/Sonraki"
        val pick = cands.filter { it.second == 2 }.maxByOrNull { it.first.getBoundsInScreen(r); r.centerX() }?.first
            ?: cands.firstOrNull { it.second == 1 }?.first ?: return
        var c: android.view.accessibility.AccessibilityNodeInfo? = pick
        var hops = 0
        while (c != null && !c.isClickable && hops++ < 5) c = c.parent
        val ok = (c ?: pick).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) { lastAutoAt = now; lastResult = "otomatik sıradaki" }
    }

    private fun tapPoint(px: Float, py: Float, ms: Long) {
        // İlerleme çubuğuna dokunuş: videoyu o noktaya götür
        if (ms < 300) rangeAt(px, py)?.let { if (seekTo(it, px)) { lastResult = "düğme"; return } }
        if (ms < 300) {
            val n = runCatching { clickableAt(px, py) }.getOrNull()
            if (n != null) {
                val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                if (r.width().toLong() * r.height() < w.toLong() * h / 2 &&
                    runCatching { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                ) { lastResult = "düğme"; return }
            }
        }
        gesture(Path().apply { moveTo(px, py) }, ms, px, py)
    }

    fun click() = main.post { show(); tapPoint(x, y, 100) }

    fun longPress() = main.post { show(); gesture(Path().apply { moveTo(x, y) }, 800) }

    /** dy > 0: sayfayı aşağı kaydır (parmak yukarı gider). */
    fun scroll(dy: Float) = main.post {
        show()
        val d = dy.coerceIn(-h * 0.6f, h * 0.6f)
        val sy = y.coerceIn(h * 0.2f, h * 0.8f)
        val ey = (sy - d).coerceIn(5f, h - 5f)
        gesture(Path().apply { moveTo(x, sy); lineTo(x, ey) }, 250)
    }

    /** Ekranın oranına göre (0..1) bir noktaya dokunur. */
    fun tapAt(nx: Float, ny: Float, ms: Long) = main.post {
        x = (nx * w).coerceIn(0f, w - 2f); y = (ny * h).coerceIn(0f, h - 2f)
        tapPoint(x, y, ms)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = main.post {
        // Video ilerleme çubuğunda kaydırma: çubuğu bırakılan noktaya getir (dokunmayı yok sayan TV'lerde de çalışır)
        val sx = x1 * w; val sy = y1 * h
        rangeAt(sx, sy)?.let { if (seekTo(it, x2 * w)) { lastResult = "düğme"; return@post } }
        val p = Path().apply {
            moveTo((x1 * w).coerceIn(1f, w - 2f), (y1 * h).coerceIn(1f, h - 2f))
            lineTo((x2 * w).coerceIn(1f, w - 2f), (y2 * h).coerceIn(1f, h - 2f))
        }
        gesture(p, ms.coerceIn(80, 2000))
    }

    /** TV Gönder'in istediği ekranı açar (erişilebilirlik hizmeti arka plan açma engeline takılmaz). */
    fun launch(uri: String) = main.post {
        runCatching {
            val i = android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
            i.selector = null
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    fun back() = main.post { runCatching { performGlobalAction(GLOBAL_ACTION_BACK) } }
    fun home() = main.post { runCatching { performGlobalAction(GLOBAL_ACTION_HOME) } }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Otomatik onay: yalnız TV Gönder az önce istediyse (eşleşmiş telefonun "TV ekranını gör" isteği, 20 sn içinde)
     * sistemin "ekranı paylaş" ve mikrofon izni pencerelerindeki onay düğmesine basar. Başka hiçbir durumda dokunmaz.
     */
    @Volatile var acceptUntil = 0L
    private val acceptWords = listOf(
        "şimdi başlat", "start now", "başlat", "start", "paylaş", "share", "ekranı paylaş", "share screen",
        "uygulamayı kullanırken", "while using the app", "yalnızca bu sefer", "only this time", "izin ver", "allow",
    )
    private val acceptPkgs = setOf("com.android.systemui", "android", "com.android.permissioncontroller", "com.google.android.permissioncontroller")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || SystemClock.elapsedRealtime() > acceptUntil) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in acceptPkgs) return
        main.postDelayed({ autoAccept() }, 300)
    }

    private fun autoAccept() {
        if (SystemClock.elapsedRealtime() > acceptUntil) return
        val roots = buildList {
            runCatching { windows.mapNotNullTo(this) { it.root } }
            rootInActiveWindow?.let { add(it) }
        }.filter { it.packageName?.toString() in acceptPkgs }
        for (root in roots) {
            val all = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
            val buttons = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            var guard = 0
            while (all.isNotEmpty() && guard++ < 800) {
                val n = all.removeFirst()
                if (n.isClickable && n.isEnabled) buttons.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { all.add(it) }
            }
            // Düğme yazısı (kendisi ya da çocuğu) sözlükteki ilk eşleşen kelimeye göre: en güçlü eşleşme önce
            for (w in acceptWords) {
                val b = buttons.firstOrNull { b -> textOf(b).equals(w, true) } ?: buttons.firstOrNull { b -> textOf(b).contains(w, true) }
                if (b != null) {
                    if (b.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        // Mikrofon izni sonrası paylaşım penceresi de gelebilir: süreyi kısa tut ama kapatma
                        acceptUntil = minOf(acceptUntil, SystemClock.elapsedRealtime() + 8000)
                        return
                    }
                }
            }
        }
    }

    private fun textOf(n: android.view.accessibility.AccessibilityNodeInfo): String {
        val t = (n.text ?: n.contentDescription)?.toString()
        if (!t.isNullOrBlank()) return t.trim()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val ct = (c.text ?: c.contentDescription)?.toString()
            if (!ct.isNullOrBlank()) return ct.trim()
        }
        return ""
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        runCatching { listener?.close() }
        listener = null
        main.removeCallbacks(hide)
        runCatching { cursor?.let { wm?.removeView(it) } }
        super.onDestroy()
    }
}
