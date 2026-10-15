// v0.17 – 2026-10-15: telefondan gelen fare/kumanda komutlarını (UDP 8766) alır: imleç, tıklama, kaydırma, medya tuşları, ses
package com.onur.tvgonder

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Paket biçimi: "<token> <komut> [a] [b]"
 * Komutlar: m dx dy | c | lc | s dy | back | home | play | next | prev | ff | rw | vu | vd | mute
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
            runCatching { handle(parts[1], parts.getOrNull(2)?.toFloatOrNull() ?: 0f, parts.getOrNull(3)?.toFloatOrNull() ?: 0f) }
        }
    }

    private fun handle(cmd: String, a: Float, b: Float) {
        val m = MouseService.instance
        when (cmd) {
            "m" -> m?.move(a, b)
            "c" -> m?.click()
            "lc" -> m?.longPress()
            "s" -> m?.scroll(a)
            "back" -> m?.back()
            "home" -> m?.home()
            "play" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "ff" -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "rw" -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            "vu" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vd" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun close() { runCatching { socket?.close() } }
}
