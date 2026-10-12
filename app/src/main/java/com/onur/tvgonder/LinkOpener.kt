package com.onur.tvgonder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object LinkOpener {
    /** YouTube linkleri YouTube uygulamasında, diğerleri tarayıcıda açılır. */
    fun open(ctx: Context, url: String) {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: ""
        val yt = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        val packages: List<String?> =
            if (yt) listOf("com.google.android.youtube.tv", "com.google.android.youtube", null)
            else listOf(null)
        for (pkg in packages) {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            try { ctx.startActivity(i); return } catch (_: ActivityNotFoundException) { }
        }
    }
}
