// v0.23 – 2026-10-19: TV'de: tablet "TV ekranını gör" deyince ekran kaydı iznini sorar, verilirse TV ekranını tablete yollar
package com.onur.tvgonder.mirror

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MirrorConsentActivity : ComponentActivity() {
    private var host = ""
    private var port = 0
    private var key = ""

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            MirrorService.start(this, r.resultCode, data, host, port, key, Build.MODEL)
        } else {
            Toast.makeText(this, "TV ekranı paylaşılmadı", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra("host") ?: ""
        port = intent.getIntExtra("port", 0)
        key = intent.getStringExtra("key") ?: ""
        if (savedInstanceState != null) return
        val name = intent.getStringExtra("name") ?: "Tablet"
        Toast.makeText(this, "$name TV ekranını görmek istiyor", Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        } else askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val ok = runCatching { capture.launch(mpm.createScreenCaptureIntent()) }.isSuccess
        if (!ok) {
            Toast.makeText(this, "Bu TV ekran paylaşımına izin vermiyor", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
