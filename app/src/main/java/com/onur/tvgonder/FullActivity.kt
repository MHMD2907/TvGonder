// v0.28 – 2026-10-19: tam ekran yatay sayfa (oyun kolu, TV ekranını gör) + kızılötesi kumanda sayfası
package com.onur.tvgonder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.onur.tvgonder.ui.AppTheme
import com.onur.tvgonder.ui.GamepadDialog

class FullActivity : ComponentActivity() {
    companion object {
        fun open(c: Context, mode: String) {
            c.startActivity(Intent(c, FullActivity::class.java).putExtra("mode", mode))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val store = Store(this)
        val mode = intent.getStringExtra("mode") ?: "pad"
        setContent {
            AppTheme {
                when (mode) {
                    "tv" -> com.onur.tvgonder.mirror.TvScreenViewer(TvClient(store), store, full = true) { finish() }
                    else -> GamepadDialog(full = true) { finish() }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); InAppMouse.attach(this) }
    override fun onPause() { InAppMouse.detach(this); super.onPause() }
}

/** Kızılötesi kumanda sayfası (dikey; telefonun üst kenarı cihaza doğrultulur). */
class IrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { com.onur.tvgonder.ui.IrRemoteScreen { finish() } } }
    }
}
