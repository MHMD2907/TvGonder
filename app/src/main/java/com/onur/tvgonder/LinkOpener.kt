// v0.7 – 2026-10-12: YouTube dışındaki linkler uygulamanın kendi tarayıcısında açılır
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
        val host = uri.host?.lowercase() ?: ""
        val yt = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (yt) {
            for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube")) {
                val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setPackage(pkg)
                try { ctx.startActivity(i); return } catch (_: ActivityNotFoundException) { }
            }
        }
        // Kendi tarayıcımız: ekran öndeyse doğrudan, değilse uygulamayı öne getirerek
        Hub.openUrl.value = url
        if (!Hub.foreground) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(EXTRA_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }
}
