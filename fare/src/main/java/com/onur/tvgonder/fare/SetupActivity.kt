// v0.19 – 2026-10-15: TV Gönder Fare açılış ekranı: durum ve erişilebilirlik ayarına kısayol
package com.onur.tvgonder.fare

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 48).toInt()
        status = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val btn = Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            textSize = 20f
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D1B2A"))
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "🖱️ TV Gönder Fare"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            addView(status)
            addView(btn)
        })
        btn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        status.text = if (MouseService.instance != null)
            "\nAçık ✓\nTelefonda TV Gönder → Fare / Kumanda ile kullanabilirsin.\n"
        else
            "\nKapalı.\nAşağıdaki düğmeye bas, listeden \"TV Gönder Fare\"yi seç ve aç.\n"
    }
}
