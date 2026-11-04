// v0.46 – 2026-11-04: arka plandan açma: Fare eklentisi yalnız cevap veriyorsa kullanılır; öndeyse doğrudan
package com.onur.tvgonder

import android.content.Context
import android.content.Intent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object Launcher {
    /**
     * Başka bir uygulama (ör. YouTube) öndeyken Android, arka plandaki TV Gönder'in ekran açmasını engeller.
     * Fare eklentisi açıksa (erişilebilirlik hizmeti) bu engel ona uygulanmaz: açma işini ona yaptırırız.
     */
    fun start(c: Context, i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Eklenti yalnız gerçekten cevap veriyorsa kullanılır (takılmışsa istek boşa giderdi); ana iş parçacığından (ön plandaki ekrandan) doğrudan
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        if (!Hub.foreground && !onMain && Apps.fareAlive(c) && viaFare(c, i)) return true
        return runCatching { c.startActivity(i) }.isSuccess
    }

    private fun viaFare(c: Context, i: Intent): Boolean = runCatching {
        val token = Store(c).devices().keys.firstOrNull() ?: return false
        val msg = "$token launch ${i.toUri(Intent.URI_INTENT_SCHEME)}".toByteArray()
        if (msg.size > 8000) return false
        DatagramSocket().use { it.send(DatagramPacket(msg, msg.size, InetAddress.getByName("127.0.0.1"), 8767)) }
        true
    }.getOrDefault(false)
}
