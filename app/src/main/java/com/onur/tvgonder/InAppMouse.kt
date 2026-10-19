// v0.23 – 2026-10-19: eklentisiz fare + ekrana konumla dokunma/kaydırma (tablette TV ekranı); TV Gönder açıkken çalışır
package com.onur.tvgonder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * Erişilebilirlik izni gerektirmeyen fare. Yalnızca TV Gönder ekrandayken çalışır:
 * dokunma olaylarını uygulamanın kendi penceresine gönderir.
 */
object InAppMouse {
    private val main = Handler(Looper.getMainLooper())
    private var act = WeakReference<Activity>(null)
    private var cursor: ImageView? = null
    private var x = -1f
    private var y = -1f
    private val hide = Runnable { cursor?.visibility = View.GONE }

    val active: Boolean get() = act.get() != null

    fun attach(a: Activity) = main.post { act = WeakReference(a) }
    fun detach(a: Activity) = main.post {
        if (act.get() === a) {
            cursor?.let { (it.parent as? ViewGroup)?.removeView(it) }
            cursor = null
            act = WeakReference(null)
        }
    }

    private fun root(): ViewGroup? = act.get()?.window?.decorView as? ViewGroup

    private fun show() {
        val r = root() ?: return
        var c = cursor
        if (c == null || c.parent !== r) {
            c?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val size = (r.resources.displayMetrics.density * 28).toInt().coerceAtLeast(32)
            c = ImageView(r.context).apply {
                setImageBitmap(arrow(size))
                isClickable = false; isFocusable = false
                elevation = 10_000f
            }
            r.addView(c, FrameLayout.LayoutParams(size, size))
            cursor = c
        }
        val cv = c ?: return
        if (x < 0) { x = r.width / 2f; y = r.height / 2f }
        cv.translationX = x; cv.translationY = y
        cv.visibility = View.VISIBLE
        cv.bringToFront()
        main.removeCallbacks(hide)
        main.postDelayed(hide, 10_000)
    }

    fun move(dx: Float, dy: Float) = main.post {
        val r = root() ?: return@post
        if (x < 0) { x = r.width / 2f; y = r.height / 2f }
        x = (x + dx).coerceIn(0f, r.width - 2f)
        y = (y + dy).coerceIn(0f, r.height - 2f)
        show()
    }

    private fun touch(action: Int, down: Long, px: Float, py: Float) {
        val a = act.get() ?: return
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        runCatching { a.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun press(holdMs: Long) = main.post {
        if (root() == null) return@post
        show()
        val px = x; val py = y
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, px, py)
        main.postDelayed({ touch(MotionEvent.ACTION_UP, down, px, py) }, holdMs)
    }

    fun click() = press(50)
    fun longPress() = press(700)

    /** dy > 0: sayfa aşağı (parmak yukarı gider). */
    fun scroll(dy: Float) = main.post {
        val r = root() ?: return@post
        show()
        val h = r.height.toFloat()
        val d = dy.coerceIn(-h * 0.6f, h * 0.6f)
        val sy = y.coerceIn(h * 0.2f, h * 0.8f)
        val ey = (sy - d).coerceIn(5f, h - 5f)
        val px = x
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, px, sy)
        val steps = 8
        for (i in 1..steps) {
            main.postDelayed({ touch(MotionEvent.ACTION_MOVE, down, px, sy + (ey - sy) * i / steps) }, i * 18L)
        }
        main.postDelayed({ touch(MotionEvent.ACTION_UP, down, px, ey) }, steps * 18L + 20)
    }

    /** Ekranın oranına göre (0..1) bir noktaya dokunur. */
    fun tapAt(nx: Float, ny: Float, holdMs: Long) = main.post {
        val r = root() ?: return@post
        x = nx * r.width; y = ny * r.height
        show()
        val px = x; val py = y
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, px, py)
        main.postDelayed({ touch(MotionEvent.ACTION_UP, down, px, py) }, holdMs)
    }

    /** (x1,y1)'den (x2,y2)'ye ms sürede kaydırır (0..1 konum). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = main.post {
        val r = root() ?: return@post
        val w = r.width.toFloat(); val h = r.height.toFloat()
        val sx = x1 * w; val sy = y1 * h; val ex = x2 * w; val ey = y2 * h
        val dur = ms.coerceIn(80, 2000)
        val steps = (dur / 16).toInt().coerceIn(4, 60)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, sx, sy)
        for (i in 1..steps) {
            main.postDelayed({ touch(MotionEvent.ACTION_MOVE, down, sx + (ex - sx) * i / steps, sy + (ey - sy) * i / steps) }, dur * i / steps)
        }
        main.postDelayed({ touch(MotionEvent.ACTION_UP, down, ex, ey) }, dur + 16)
        x = ex; y = ey
    }

    fun back() = main.post {
        val a = act.get() as? androidx.activity.ComponentActivity ?: return@post
        runCatching { a.onBackPressedDispatcher.onBackPressed() }
    }

    fun home() = main.post {
        val a = act.get() ?: return@post
        runCatching { a.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

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
}
