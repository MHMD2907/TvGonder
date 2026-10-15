// v0.19 – 2026-10-15: TV Gönder Fare: imleç, tıklama, uzun basma, kaydırma, geri/ana ekran; komutları kendi dinleyicisinden alır
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
        val m = resources.displayMetrics
        w = m.widthPixels; h = m.heightPixels
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
        listener = MouseListener(this).also { it.isDaemon = true; it.start() }
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

    private fun show() {
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

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(g, null, null)
    }

    fun click() = main.post { show(); gesture(Path().apply { moveTo(x, y) }, 60) }

    fun longPress() = main.post { show(); gesture(Path().apply { moveTo(x, y) }, 800) }

    /** dy > 0: sayfayı aşağı kaydır (parmak yukarı gider). */
    fun scroll(dy: Float) = main.post {
        show()
        val d = dy.coerceIn(-h * 0.6f, h * 0.6f)
        val sy = y.coerceIn(h * 0.2f, h * 0.8f)
        val ey = (sy - d).coerceIn(5f, h - 5f)
        gesture(Path().apply { moveTo(x, sy); lineTo(x, ey) }, 250)
    }

    fun back() = main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home() = main.post { performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        listener?.close()
        main.removeCallbacks(hide)
        runCatching { cursor?.let { wm?.removeView(it) } }
        super.onDestroy()
    }
}
