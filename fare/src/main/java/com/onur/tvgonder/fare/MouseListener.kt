// v0.23 – 2026-10-19: fare komutları + konumla dokunma/kaydırma (t, lp, sw: tablette TV ekranı); anahtarı ana uygulamaya doğrulatır
package com.onur.tvgonder.fare

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Paket: "<token> <komut> [a] [b]". Komutlar: ping | m dx dy | c | lc | s dy | back | home
 *   | t x y | lp x y | sw x1 y1 x2 y2 ms   (x,y: ekranın 0..1 oranı)
 * "ping"e "pong" ile cevap verir (telefon farenin açık olduğunu böyle anlar).
 */
class MouseListener(private val svc: MouseService) : Thread("tvgonder-fare") {
    companion object { const val PORT = 8767 }

    @Volatile private var socket: DatagramSocket? = null
    private val ok = HashMap<String, Long>()   // doğrulanmış anahtar -> zaman

    private fun valid(token: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        ok[token]?.let { if (now - it < 60_000) return true }
        val good = runCatching {
            val c = URL("http://127.0.0.1:8765/auth?token=" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 800; c.readTimeout = 800
            try { c.responseCode == 200 } finally { c.disconnect() }
        }.getOrDefault(false)
        if (good) ok[token] = now
        return good
    }

    override fun run() {
        val s = runCatching { DatagramSocket(PORT) }.getOrNull() ?: return
        socket = s
        val buf = ByteArray(256)
        while (!s.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(pkt) }.isFailure) break
            val parts = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim().split(' ')
            if (parts.size < 2 || !valid(parts[0])) continue
            val a = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val f = parts.drop(2).map { it.toFloatOrNull() ?: 0f }
            fun g(i: Int, d: Float = 0f) = f.getOrElse(i) { d }
            when (parts[1]) {
                "ping" -> runCatching {
                    val b = "pong".toByteArray()
                    s.send(DatagramPacket(b, b.size, pkt.address, pkt.port))
                }
                "m" -> svc.move(a, parts.getOrNull(3)?.toFloatOrNull() ?: 0f)
                "c" -> svc.click()
                "lc" -> svc.longPress()
                "s" -> svc.scroll(a)
                "back" -> svc.back()
                "home" -> svc.home()
                "t" -> svc.tapAt(g(0), g(1), 60)
                "lp" -> svc.tapAt(g(0), g(1), 800)
                "sw" -> svc.swipe(g(0), g(1), g(2), g(3), g(4, 250f).toLong())
            }
        }
    }

    fun close() { runCatching { socket?.close() } }
}
