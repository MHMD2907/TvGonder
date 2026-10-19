// v0.22 – 2026-10-19: medya/ses komutları (UDP 8766) + eklentisiz fare (TV Gönder ekrandayken); eklenti varsa fare 8767'de
package com.onur.tvgonder

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Paket biçimi: "<token> <komut> [a] [b]"
 * Komutlar: play | next | prev | ff | rw | vu | vd | mute | ping | m dx dy | c | lc | s dy | back | home
 * (Fare komutları burada yalnız TV Gönder ekrandayken çalışır; her yerde çalışan imleç "TV Gönder Fare" eklentisindedir.)
 * Sadece eşleşmiş telefonların anahtarı kabul edilir.
 */
class RemoteListener(private val ctx: Context, private val store: Store) : Thread("tvgonder-remote") {
    companion object { const val PORT = 8766 }

    @Volatile private var socket: DatagramSocket? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun run() {
        val s = runCatching { DatagramSocket(PORT) }.getOrNull() ?: return
        socket = s
        val buf = ByteArray(256)
        var tokens = store.devices().keys
        var tokensAt = 0L
        while (!s.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(pkt) }.isFailure) break
            val parts = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim().split(' ')
            if (parts.size < 2) continue
            if (SystemClock.elapsedRealtime() - tokensAt > 5000) { tokens = store.devices().keys; tokensAt = SystemClock.elapsedRealtime() }
            if (parts[0] !in tokens) continue
            if (parts[1] == "ping") {
                runCatching { val b = "pong".toByteArray(); s.send(DatagramPacket(b, b.size, pkt.address, pkt.port)) }
                continue
            }
            val f = parts.drop(2).map { it.toFloatOrNull() ?: 0f }
            when (parts[1]) {
                // Tablette TV ekranına dokunma (0..1 konum): yalnız TV Gönder ekrandayken
                "t" -> InAppMouse.tapAt(f.getOrElse(0) { 0f }, f.getOrElse(1) { 0f }, 50)
                "lp" -> InAppMouse.tapAt(f.getOrElse(0) { 0f }, f.getOrElse(1) { 0f }, 700)
                "sw" -> InAppMouse.swipe(f.getOrElse(0) { 0f }, f.getOrElse(1) { 0f }, f.getOrElse(2) { 0f }, f.getOrElse(3) { 0f }, f.getOrElse(4) { 250f }.toLong())
                else -> runCatching { handle(parts[1], f.getOrElse(0) { 0f }, f.getOrElse(1) { 0f }) }
            }
        }
    }

    private fun handle(cmd: String, a: Float, b: Float) {
        when (cmd) {
            "play" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "ff" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "rw" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            "vu" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vd" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "m" -> InAppMouse.move(a, b)
            "c" -> InAppMouse.click()
            "lc" -> InAppMouse.longPress()
            "s" -> InAppMouse.scroll(a)
            "back" -> InAppMouse.back()
            "home" -> InAppMouse.home()
        }
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun close() { runCatching { socket?.close() } }
}
