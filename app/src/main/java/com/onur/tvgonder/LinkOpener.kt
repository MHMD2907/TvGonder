// v0.29 – 2026-10-20: YouTube öndeyken gelen link de açılır (Fare eklentisi üzerinden); diğerleri kendi tarayıcımızda
package com.onur.tvgonder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object LinkOpener {
    const val EXTRA_URL = "tvgonder.url"

    /** YouTube linkleri YouTube uygulamasında, diğerleri TV Gönder'in tarayıcısında açılır. */
    fun open(ctx: Context, url: String) {
        val uri = Uri.parse(url)
        // Android, izin yoksa arka plandan uygulama açılmasını engelleyebilir: en azından haber ver
        if (!Hub.foreground && android.os.Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(ctx) && !Apps.fareEnabled(ctx)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        ctx, "TV Gönder: link geldi. Açılmazsa TV Gönder'i aç ya da Fare eklentisini aç (Ayarlar).",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        val host = uri.host?.lowercase() ?: ""
        val yt = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (yt) {
            // YouTube açıksa eski görev öne gelip yeni videoyu yok sayıyordu: CLEAR_TASK ile yeniden başlatılır
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube", "com.google.android.apps.youtube.kids")) {
                val i = Intent(Intent.ACTION_VIEW, uri).addFlags(flags).setPackage(pkg)
                    .putExtra("force_fullscreen", true).putExtra("finish_on_ended", true)
                if (ctx.packageManager.resolveActivity(i, 0) == null) continue
                if (Launcher.start(ctx, i)) return
            }
        }
        // Kendi tarayıcımız: ekran öndeyse doğrudan, değilse uygulamayı öne getirerek
        Hub.openUrl.value = url
        if (!Hub.foreground) {
            Launcher.start(
                ctx,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
