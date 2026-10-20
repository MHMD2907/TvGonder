// v0.29 – 2026-10-20: arka plandan uygulama/ekran açma: TV Gönder öndeyse doğrudan, değilse Fare eklentisi üzerinden (Android engeline takılmaz)
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
        if (!Hub.foreground && Apps.fareEnabled(c) && viaFare(c, i)) return true
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
