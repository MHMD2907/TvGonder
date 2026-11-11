// v1.6 – 2026-11-11: onaylı telefonlar kalıcı saklanır (TV Gönder kapalıyken de fare çalışır); diag; dinleyici hiç ölmez; fare + dokunma + launch
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

    private val prefs by lazy { svc.getSharedPreferences("fare", android.content.Context.MODE_PRIVATE) }

    /**
     * Anahtar doğrulama: TV Gönder'e sorulur ve onaylanan anahtar kalıcı saklanır.
     * TV Gönder kapalıyken (sunucu cevap vermiyor) daha önce onaylanmış telefonlar yine çalışır.
     */
    private fun valid(token: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        ok[token]?.let { if (now - it < 60_000) return true }
        val code = runCatching {
            val c = URL("http://127.0.0.1:8765/auth?token=" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 800; c.readTimeout = 800
            try { c.responseCode } finally { c.disconnect() }
        }.getOrDefault(-1)
        val known = prefs.getStringSet("tokens", emptySet()).orEmpty()
        val good = when (code) {
            200 -> { if (token !in known) prefs.edit().putStringSet("tokens", known + token).apply(); true }
            401 -> { if (token in known) prefs.edit().putStringSet("tokens", known - token).apply(); false }
            else -> token in known   // TV Gönder kapalı: daha önce onaylanmışsa kabul
        }
        if (good) ok[token] = now
        return good
    }

    @Volatile private var stopped = false

    override fun run() {
        // Kapı (8767) bir an meşgulse ya da bir hata olursa dinleyici ölmez: yeniden açılır
        while (!stopped) {
            val s = runCatching {
                DatagramSocket(null as java.net.SocketAddress?).apply { reuseAddress = true; bind(java.net.InetSocketAddress(PORT)) }
            }.getOrNull()
            if (s == null) { runCatching { sleep(2000) }; continue }
            socket = s
            val buf = ByteArray(8192)
            while (!s.isClosed && !stopped) {
                val pkt = DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(pkt) }.isFailure) break
                runCatching { handle(s, pkt) }
            }
            runCatching { s.close() }
            if (!stopped) runCatching { sleep(500) }
        }
    }

    private fun handle(s: DatagramSocket, pkt: DatagramPacket) {
        val parts = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim().split(' ')
        if (parts.size < 2 || !valid(parts[0])) return
        val a = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
        val f = parts.drop(2).map { it.toFloatOrNull() ?: 0f }
        fun g(i: Int, d: Float = 0f) = f.getOrElse(i) { d }
        when (parts[1]) {
            "ping" -> {
                val b = "pong".toByteArray()
                s.send(DatagramPacket(b, b.size, pkt.address, pkt.port))
            }
            // Son tıklamanın sonucu (telefonda gösterilir: TV'de uygulandı mı)
            "diag" -> {
                val b = svc.lastResult.toByteArray()
                s.send(DatagramPacket(b, b.size, pkt.address, pkt.port))
            }
            "m" -> svc.move(a, parts.getOrNull(3)?.toFloatOrNull() ?: 0f)
            "c" -> svc.click()
            "lc" -> svc.longPress()
            "s" -> svc.scroll(a)
            "back" -> svc.back()
            "home" -> svc.home()
            // Yalnız aynı cihazdaki TV Gönder'den: Android'in arka plandan açma engeline takılmadan ekran açar
            "launch" -> if (pkt.address.isLoopbackAddress) svc.launch(parts.drop(2).joinToString(" "))
            "t" -> svc.tapAt(g(0), g(1), 60)
            "lp" -> svc.tapAt(g(0), g(1), 800)
            "sw" -> svc.swipe(g(0), g(1), g(2), g(3), g(4, 250f).toLong())
        }
    }

    fun close() { stopped = true; runCatching { socket?.close() } }
}
